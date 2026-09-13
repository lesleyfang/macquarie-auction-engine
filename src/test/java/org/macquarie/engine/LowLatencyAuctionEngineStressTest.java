package org.macquarie.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.macquarie.model.AuctionResult;
import org.macquarie.support.HeapSnapshot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.macquarie.engine.LowLatencyAuctionEngine.ACTION_ADD;

class LowLatencyAuctionEngineStressTest {

    private static final double TICK = 0.01;
    private static final int ORDER_COUNT = 1_000_000;
    private static final int PRODUCER_THREADS = 4;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void stressConcurrentProducersThenClearAuction() throws Exception {
        int maxOrders = ORDER_COUNT;
        int ringBufferSize = 8192;
        AtomicInteger errorCount = new AtomicInteger();

        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(
                TICK, maxOrders, ringBufferSize, (type, id, ex) -> errorCount.incrementAndGet());

        Thread engineThread = new Thread(() -> {
            while (engine.isRunning() || engine.hasPendingCommands()) {
                engine.processNextCommand();
            }
        }, "engine-stress-worker");
        engineThread.start();

        int ordersPerProducer = ORDER_COUNT / PRODUCER_THREADS;
        ExecutorService pool = Executors.newFixedThreadPool(PRODUCER_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PRODUCER_THREADS);

        for (int p = 0; p < PRODUCER_THREADS; p++) {
            final int producerId = p;
            pool.submit(() -> {
                try {
                    start.await();
                    long baseId = (long) producerId * ordersPerProducer;
                    for (int i = 0; i < ordersPerProducer; i++) {
                        long orderId = baseId + i + 1;
                        boolean isBuy = (i % 2 == 0);
                        // Closed-form match: 500k buys @ 80, 500k sells @ 20, qty 10
                        // → 5_000_000 matched @ midpoint 50.00
                        double price = isBuy ? 80.00 : 20.00;
                        submitWithRetry(engine, ACTION_ADD, orderId, price, 10L, isBuy);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        long ingestStart = System.nanoTime();
        start.countDown();
        assertTrue(done.await(90, TimeUnit.SECONDS), "Producers timed out");
        pool.shutdown();

        submitControlWithRetry(engine::submitEndAuctionCommand);
        submitControlWithRetry(engine::submitShutdownCommand);
        engineThread.join(60_000);
        long ingestNanos = System.nanoTime() - ingestStart;

        assertEquals(0, errorCount.get(), "No domain errors expected under unique-id load");
        AuctionResult result = engine.getLatestAuctionResult();
        assertNotNull(result, "Auction result should be present after END_AUCTION");
        assertEquals(5_000_000L, result.matchedVolume());
        assertEquals(50.00, result.auctionPrice(), 1e-6);
        assertTrue(ingestNanos < TimeUnit.SECONDS.toNanos(120));

        System.out.println("--- Engine Stress Test ---");
        System.out.println("Orders: " + ORDER_COUNT);
        System.out.println("Ingest+match+shutdown: " + ingestNanos / 1_000_000 + " ms");
        System.out.println("Clearing price: " + result.auctionPrice());
        System.out.println("Matched volume: " + result.matchedVolume());
        System.out.println(HeapSnapshot.report("After 1M engine ingest+match"));
    }

    static void submitWithRetry(LowLatencyAuctionEngine engine,
                                int actionType,
                                long orderId,
                                double price,
                                long quantity,
                                boolean isBuy) {
        while (true) {
            try {
                engine.submitOrderCommand(actionType, orderId, price, quantity, isBuy);
                return;
            } catch (IllegalStateException ignored) {
                Thread.onSpinWait();
            }
        }
    }

    static void submitControlWithRetry(java.util.function.BooleanSupplier submit) {
        while (true) {
            try {
                if (submit.getAsBoolean()) {
                    return;
                }
            } catch (IllegalStateException ignored) {
                Thread.onSpinWait();
                continue;
            }
            Thread.onSpinWait();
        }
    }
}
