package org.macquarie.matcher;

import org.junit.jupiter.api.Test;
import org.macquarie.core.OrderBook;
import org.macquarie.model.AuctionResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuctionMatcherTest {

    private static final double TICK = 0.01;
    private static final double EPS = 1e-6;

    @Test
    void returnsZeroWhenNoOrdersPresent() {
        AuctionResult result = match();
        assertEquals(0.0, result.auctionPrice(), EPS);
        assertEquals(0L, result.matchedVolume());
    }

    @Test
    void matchesAtSingleBestPriceWhenNoTieRange() {
        AuctionResult result = match(
                buy(1, 10.05, 100),
                sell(2, 10.03, 80)
        );
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void choosesUpperBoundWhenBuySurplusDominatesTieRange() {
        AuctionResult result = match(
                buy(1, 10.06, 100),
                sell(2, 10.02, 20),
                sell(3, 10.03, 20),
                sell(4, 10.04, 20)
        );
        assertEquals(10.06, result.auctionPrice(), EPS);
        assertEquals(60L, result.matchedVolume());
    }

    @Test
    void choosesLowerBoundWhenSellSurplusDominatesTieRange() {
        AuctionResult result = match(
                buy(1, 10.03, 20),
                buy(2, 10.04, 20),
                buy(3, 10.05, 20),
                sell(4, 10.02, 100)
        );
        assertEquals(10.02, result.auctionPrice(), EPS);
        assertEquals(60L, result.matchedVolume());
    }

    @Test
    void returnsZeroWhenOnlyBuyOrdersPresent() {
        AuctionResult result = match(buy(1, 10.05, 100), buy(2, 10.04, 50));
        assertEquals(0.0, result.auctionPrice(), EPS);
        assertEquals(0L, result.matchedVolume());
    }

    @Test
    void returnsZeroWhenOnlySellOrdersPresent() {
        AuctionResult result = match(sell(1, 10.03, 80), sell(2, 10.04, 40));
        assertEquals(0.0, result.auctionPrice(), EPS);
        assertEquals(0L, result.matchedVolume());
    }

    @Test
    void returnsZeroWhenBidsAndOffersDoNotCross() {
        AuctionResult result = match(
                buy(1, 10.00, 100),
                sell(2, 10.10, 100)
        );
        assertEquals(0.0, result.auctionPrice(), EPS);
        assertEquals(0L, result.matchedVolume());
    }

    @Test
    void choosesMidpointWhenSurplusIsBalancedAcrossTieRange() {
        AuctionResult result = match(
                buy(1, 10.06, 80),
                sell(2, 10.02, 40),
                sell(3, 10.03, 40)
        );
        assertEquals(10.04, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    private static AuctionResult match(Order... orders) {
        OrderBook book = new OrderBook(TICK, 32);
        for (Order order : orders) {
            book.addOrder(order.id, order.price, order.quantity, order.buy);
        }
        return new AuctionMatcher(book).calculateAuctionMatch();
    }

    private static Order buy(long id, double price, long quantity) {
        return new Order(id, price, quantity, true);
    }

    private static Order sell(long id, double price, long quantity) {
        return new Order(id, price, quantity, false);
    }

    private record Order(long id, double price, long quantity, boolean buy) {}
}
