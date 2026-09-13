package org.macquarie.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.macquarie.model.AuctionResult;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.macquarie.engine.LowLatencyAuctionEngine.ACTION_ADD;
import static org.macquarie.engine.LowLatencyAuctionEngineStressTest.submitControlWithRetry;
import static org.macquarie.engine.LowLatencyAuctionEngineStressTest.submitWithRetry;

class LowLatencyAuctionEngineLatencyTest {

    private static final double TICK = 0.01;
    private static final int WARMUP = 2_000;
    private static final int SAMPLES = 10_000;
    private static final long P99_BUDGET_NS = TimeUnit.MILLISECONDS.toNanos(5);

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void commandProcessingLatencyStaysWithinBudget() {
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, SAMPLES + WARMUP + 8, 16_384, null);

        long[] samples = new long[SAMPLES];
        int orderId = 1;
        for (int i = 0; i < WARMUP + SAMPLES; i++) {
            boolean isBuy = (i % 2 == 0);
            double price = 10.00 + (i % 200) * TICK;
            engine.submitOrderCommand(ACTION_ADD, orderId++, price, 1L, isBuy);

            long start = System.nanoTime();
            engine.processNextCommand();
            long elapsed = System.nanoTime() - start;
            if (i >= WARMUP) {
                samples[i - WARMUP] = elapsed;
            }
        }

        Arrays.sort(samples);
        long p50 = percentile(samples, 0.50);
        long p99 = percentile(samples, 0.99);
        long max = samples[samples.length - 1];

        System.out.println("--- Command Processing Latency ---");
        System.out.println("p50=" + p50 + " ns, p99=" + p99 + " ns, max=" + max + " ns");

        assertTrue(p99 < P99_BUDGET_NS, "p99 command latency " + p99 + " ns exceeds " + P99_BUDGET_NS + " ns");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void auctionMatchLatencyStaysWithinBudget() {
        int bookSize = 20_000;
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, bookSize, 32_768, null);

        for (int i = 0; i < bookSize; i++) {
            boolean isBuy = (i % 2 == 0);
            double price = isBuy
                    ? 50.00 + (i % 1000) * TICK
                    : 50.02 + (i % 1000) * TICK;
            engine.submitOrderCommand(ACTION_ADD, i + 1L, price, 10L, isBuy);
        }
        while (engine.hasPendingCommands()) {
            engine.processNextCommand();
        }

        engine.submitEndAuctionCommand();
        long start = System.nanoTime();
        engine.processNextCommand();
        long matchNanos = System.nanoTime() - start;

        AuctionResult result = engine.getLatestAuctionResult();
        assertNotNull(result);
        assertTrue(result.matchedVolume() > 0);

        System.out.println("--- Auction Match Latency ---");
        System.out.println("Book size: " + bookSize);
        System.out.println("Match: " + matchNanos + " ns (" + (matchNanos / 1_000_000.0) + " ms)");
        System.out.println("Price=" + result.auctionPrice() + " volume=" + result.matchedVolume());

        assertTrue(matchNanos < TimeUnit.MILLISECONDS.toNanos(500),
                "Match latency " + matchNanos + " ns exceeds 500 ms");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void endToEndSubmitProcessMatchPathCompletesQuickly() {
        int orders = 8_000;
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, orders, 16_384, null);

        Thread worker = new Thread(() -> {
            while (engine.isRunning() || engine.hasPendingCommands()) {
                engine.processNextCommand();
            }
        }, "engine-latency-worker");
        worker.start();

        long start = System.nanoTime();
        for (int i = 0; i < orders; i++) {
            boolean isBuy = (i % 2 == 0);
            submitWithRetry(engine, ACTION_ADD, i + 1L, 100.00 + (i % 50) * TICK, 5L, isBuy);
        }
        submitControlWithRetry(engine::submitEndAuctionCommand);
        submitControlWithRetry(engine::submitShutdownCommand);
        try {
            worker.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsed = System.nanoTime() - start;

        AuctionResult result = engine.getLatestAuctionResult();
        assertNotNull(result);
        assertTrue(result.matchedVolume() > 0);
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(10),
                "End-to-end path took " + elapsed + " ns");

        System.out.println("--- End-to-end Latency ---");
        System.out.println("Orders: " + orders);
        System.out.println("Total: " + elapsed / 1_000_000 + " ms");
    }

    private static long percentile(long[] sorted, double p) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }
}
