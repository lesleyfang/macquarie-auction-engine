package org.macquarie.matcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.macquarie.core.OrderBook;
import org.macquarie.model.AuctionResult;
import org.macquarie.support.HeapSnapshot;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionMatcherStressTest {

    private static final int ORDER_COUNT = 1_000_000;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void stressPopulateOrderBookAndMatch() {
        OrderBook book = new OrderBook(0.01, ORDER_COUNT);
        AuctionMatcher matcher = new AuctionMatcher(book);

        long ingestStart = System.nanoTime();
        for (int i = 0; i < ORDER_COUNT; i++) {
            if (i % 2 == 0) {
                book.addOrder(i + 1L, 80.00, 10L, true);
            } else {
                book.addOrder(i + 1L, 20.00, 10L, false);
            }
        }
        long ingestNanos = System.nanoTime() - ingestStart;

        long matchStart = System.nanoTime();
        AuctionResult result = matcher.calculateAuctionMatch();
        long matchNanos = System.nanoTime() - matchStart;

        System.out.println("--- Matcher Stress Test ---");
        System.out.println("Orders: " + ORDER_COUNT);
        System.out.println("Ingest: " + ingestNanos / 1_000_000 + " ms");
        System.out.println("Match: " + matchNanos + " ns (" + (matchNanos / 1_000_000.0) + " ms)");
        System.out.println("Price: " + result.auctionPrice());
        System.out.println("Matched volume: " + result.matchedVolume());
        System.out.println(HeapSnapshot.report("After 1M book ingest+match"));

        assertEquals(5_000_000L, result.matchedVolume());
        assertEquals(50.00, result.auctionPrice(), 1e-6);
        assertTrue(matchNanos < TimeUnit.SECONDS.toNanos(2), "Match exceeded 2s");
    }
}
