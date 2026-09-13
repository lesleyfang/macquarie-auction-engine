package org.macquarie.engine;

import org.junit.jupiter.api.Test;
import org.macquarie.model.AuctionResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class LowLatencyAuctionEngineIntegrationTest {

    @Test
    public void testConcurrentMultiProducerOrderSubmissionAndClearing() throws InterruptedException {
        int ringBufferSize = 4096;
        int maxOrders = 5000;
        AtomicInteger errorCount = new AtomicInteger(0);

        // Initialize engine with error listener
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(0.01, maxOrders, ringBufferSize, (type, id, ex) -> {
            errorCount.incrementAndGet();
        });

        // Start the single-writer engine worker thread
        Thread engineThread = new Thread(() -> {
            while (engine.isRunning() || engine.hasPendingCommands()) {
                engine.processNextCommand();
            }
        });
        engineThread.start();

        // Use multiple producer threads to simulate concurrent market participants
        int producerThreads = 4;
        int ordersPerProducer = 200;
        ExecutorService pool = Executors.newFixedThreadPool(producerThreads);
        CountDownLatch latch = new CountDownLatch(producerThreads);

        for (int p = 0; p < producerThreads; p++) {
            final int producerId = p;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < ordersPerProducer; i++) {
                        long orderId = (producerId * 1000L) + i + 1;
                        boolean isBuy = (i % 2 == 0);
                        double price = isBuy ? 100.00 : 99.00;
                        long qty = 10;

                        engine.submitOrderCommand(LowLatencyAuctionEngine.ACTION_ADD, orderId, price, qty, isBuy);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all producers to finish submitting
        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertTrue(finished, "Producer threads timed out");
        pool.shutdown();

        // Allow a brief moment for processing, then trigger auction end and shutdown
        engine.submitEndAuctionCommand();
        engine.submitShutdownCommand();

        // Wait for the consumer thread to drain the queue and exit
        engineThread.join(5000);

        // Verify results
        assertEquals(0, errorCount.get(), "No engine exceptions or rejected orders should occur under normal load");

        AuctionResult result = engine.getLatestAuctionResult();
        assertNotNull(result, "Auction matching result should be calculated and present");
        // 4 producers × 100 buys @ 100.00 and 100 sells @ 99.00, qty 10
        // → 4000 matched, balanced surplus, midpoint 99.50
        assertEquals(4000L, result.matchedVolume());
        assertEquals(99.50, result.auctionPrice(), 1e-6);
    }
}