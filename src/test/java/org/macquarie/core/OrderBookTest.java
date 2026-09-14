package org.macquarie.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderBookTest {

    private static final double TICK = 0.01;

    @Test
    void addOrderAggregatesVolumeByTickAndSide() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);
        book.addOrder(2L, 10.05, 40, true);
        book.addOrder(3L, 10.03, 80, false);

        int buyTick = tickIndex(10.05);
        int sellTick = tickIndex(10.03);

        assertEquals(140L, book.getBuyVolumeAt(buyTick));
        assertEquals(80L, book.getSellVolumeAt(sellTick));
        assertEquals(140L, book.getTotalBuyVolume());
        assertEquals(buyTick, book.getMaxActiveTick());
        assertEquals(sellTick, book.getMinActiveTick());
        assertEquals(10.05, book.tickIndexToPrice(buyTick), 1e-9);
    }

    @Test
    void constructorRejectsInvalidTickSizeAndCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(0.0, 16));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(-0.01, 16));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(Double.NaN, 16));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(Double.POSITIVE_INFINITY, 16));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(TICK, 0));
    }

    @Test
    void addOrderRejectsDuplicateIds() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.00, 10, true);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> book.addOrder(1L, 10.01, 5, false)
        );
        assertEquals("Order ID already exists: 1", ex.getMessage());
        assertEquals(10L, book.getTotalBuyVolume());
    }

    @Test
    void addOrderRejectsInvalidPriceAndQuantity() {
        OrderBook book = new OrderBook(TICK, 16);

        IllegalArgumentException badPrice = assertThrows(
                IllegalArgumentException.class,
                () -> book.addOrder(1L, -10.00, 10, true)
        );
        assertEquals("Price must be a finite positive value", badPrice.getMessage());

        IllegalArgumentException badQuantity = assertThrows(
                IllegalArgumentException.class,
                () -> book.addOrder(2L, 10.00, 0, true)
        );
        assertEquals("Quantity must be > 0", badQuantity.getMessage());
        assertEquals(0L, book.getTotalBuyVolume());
    }

    @Test
    void amendOrderUpdatesQuantityAtSamePrice() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);
        book.amendOrder(1L, 10.05, 40, true);

        int tick = tickIndex(10.05);
        assertEquals(40L, book.getBuyVolumeAt(tick));
        assertEquals(40L, book.getTotalBuyVolume());
    }

    @Test
    void amendOrderMovesVolumeToNewPrice() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, false);
        book.amendOrder(1L, 10.08, 70, false);

        int oldTick = tickIndex(10.05);
        int newTick = tickIndex(10.08);
        assertEquals(0L, book.getSellVolumeAt(oldTick));
        assertEquals(70L, book.getSellVolumeAt(newTick));
        assertEquals(0L, book.getTotalBuyVolume());
        assertEquals(newTick, book.getMaxActiveTick());
    }

    @Test
    void amendOrderRejectsUnknownId() {
        OrderBook book = new OrderBook(TICK, 16);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> book.amendOrder(99L, 10.00, 10, true)
        );
        assertEquals("Order ID not found for amendment: 99", ex.getMessage());
    }

    @Test
    void amendOrderRejectsInvalidPriceAndQuantityAndLeavesBookUnchanged() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);

        IllegalArgumentException badPrice = assertThrows(
                IllegalArgumentException.class,
                () -> book.amendOrder(1L, 0.0, 50, true)
        );
        assertEquals("Price must be a finite positive value", badPrice.getMessage());

        IllegalArgumentException badQuantity = assertThrows(
                IllegalArgumentException.class,
                () -> book.amendOrder(1L, 10.06, -1, true)
        );
        assertEquals("Quantity must be > 0", badQuantity.getMessage());

        int tick = tickIndex(10.05);
        assertEquals(100L, book.getBuyVolumeAt(tick));
        assertEquals(100L, book.getTotalBuyVolume());
    }

    @Test
    void cancelOrderRemovesBuyAndSellVolume() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);
        book.addOrder(2L, 10.03, 80, false);

        book.cancelOrder(1L, true);
        book.cancelOrder(2L, false);

        assertEquals(0L, book.getBuyVolumeAt(tickIndex(10.05)));
        assertEquals(0L, book.getSellVolumeAt(tickIndex(10.03)));
        assertEquals(0L, book.getTotalBuyVolume());
    }

    @Test
    void cancelOrderIsNoOpForUnknownId() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);
        book.cancelOrder(99L, true);
        assertEquals(100L, book.getTotalBuyVolume());
    }

    @Test
    void amendWithWrongSideThrowsAndLeavesBookUnchanged() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> book.amendOrder(1L, 10.08, 70, false)
        );
        assertEquals("Order side mismatch for amendment: 1", ex.getMessage());

        int oldTick = tickIndex(10.05);
        int newTick = tickIndex(10.08);
        assertEquals(100L, book.getBuyVolumeAt(oldTick));
        assertEquals(100L, book.getTotalBuyVolume());
        assertEquals(0L, book.getSellVolumeAt(oldTick));
        assertEquals(0L, book.getSellVolumeAt(newTick));
    }

    @Test
    void cancelWithWrongSideThrowsAndLeavesBookUnchanged() {
        OrderBook book = new OrderBook(TICK, 16);
        book.addOrder(1L, 10.05, 100, true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> book.cancelOrder(1L, false)
        );
        assertEquals("Order side mismatch for cancel: 1", ex.getMessage());

        int tick = tickIndex(10.05);
        assertEquals(100L, book.getBuyVolumeAt(tick));
        assertEquals(100L, book.getTotalBuyVolume());
        assertEquals(0L, book.getSellVolumeAt(tick));
    }

    @Test
    void highPriceAllocatesAdditionalSegments() {
        OrderBook book = new OrderBook(TICK, 8);
        book.addOrder(1L, 250.00, 15, true);

        int tick = tickIndex(250.00);
        assertEquals(15L, book.getBuyVolumeAt(tick));
        assertEquals(15L, book.getTotalBuyVolume());
        assertEquals(tick, book.getMinActiveTick());
        assertEquals(tick, book.getMaxActiveTick());
    }

    private static int tickIndex(double price) {
        return (int) Math.round(price / TICK);
    }
}
