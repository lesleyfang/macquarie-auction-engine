package org.macquarie.core;

import org.macquarie.model.PrimitiveOpenAddressingMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

public class OrderBook {

    private static final int SEGMENT_SHIFT = 10; // 1024 ticks per segment
    private static final int SEGMENT_SIZE = 1 << SEGMENT_SHIFT;
    private static final int SEGMENT_MASK = SEGMENT_SIZE - 1;

    private final long tickSizeCents;

    // Sparse segments allocated lazily on demand
    private long[][] buySegments = new long[16][];
    private long[][] sellSegments = new long[16][];
    private long totalBuyVolume = 0;

    private int minActiveTick = Integer.MAX_VALUE;
    private int maxActiveTick = Integer.MIN_VALUE;

    private static final long SIDE_BIT = 1L << 31;
    private static final long QTY_MASK = 0x7FFFFFFFL;

    private final PrimitiveOpenAddressingMap orderMap;

    public OrderBook(double tickSize, int maxOrders) {
        if (!Double.isFinite(tickSize) || tickSize <= 0.0) {
            throw new IllegalArgumentException("Tick size must be a finite positive value");
        }
        if (maxOrders <= 0) {
            throw new IllegalArgumentException("maxOrders must be > 0");
        }
        this.tickSizeCents = parsePriceToCents(tickSize);
        this.orderMap = new PrimitiveOpenAddressingMap(maxOrders);
    }

    private long parsePriceToCents(double price) {
        return BigDecimal.valueOf(price)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }

    private int priceToTickIndex(long priceCents) {
        // Supports any positive price scale dynamically
        return (int) (priceCents / tickSizeCents);
    }

    public double tickIndexToPrice(int index) {
        return (index * tickSizeCents) / 100.0;
    }

    private void ensureCapacity(int tickIndex) {
        int segmentId = tickIndex >>> SEGMENT_SHIFT;

        if (segmentId >= buySegments.length) {
            int newLen = Math.max(buySegments.length * 2, segmentId + 1);
            buySegments = Arrays.copyOf(buySegments, newLen);
            sellSegments = Arrays.copyOf(sellSegments, newLen);
        }

        if (buySegments[segmentId] == null) {
            buySegments[segmentId] = new long[SEGMENT_SIZE];
            sellSegments[segmentId] = new long[SEGMENT_SIZE];
        }

        if (tickIndex < minActiveTick) minActiveTick = tickIndex;
        if (tickIndex > maxActiveTick) maxActiveTick = tickIndex;
    }

    public void addOrder(long orderId, double price, long quantity, boolean isBuy) {
        validateOrderInputs(price, quantity);
        long priceCents = parsePriceToCents(price);
        int index = priceToTickIndex(priceCents);
        ensureCapacity(index);

        if (orderMap.containsKey(orderId)) {
            throw new IllegalArgumentException("Order ID already exists: " + orderId);
        }

        int segmentId = index >>> SEGMENT_SHIFT;
        int innerIndex = index & SEGMENT_MASK;

        if (isBuy) {
            buySegments[segmentId][innerIndex] += quantity;
            totalBuyVolume += quantity;
        } else {
            sellSegments[segmentId][innerIndex] += quantity;
        }

        orderMap.put(orderId, index, packQuantityAndSide(quantity, isBuy));
    }

    public void amendOrder(long orderId, double newPrice, long newQuantity, boolean isBuy) {
        long packed = orderMap.get(orderId);
        if (packed == -1L) {
            throw new IllegalArgumentException("Order ID not found for amendment: " + orderId);
        }

        int oldTickIndex = (int) (packed >>> 32);
        long oldQuantity = unpackQuantity(packed);
        if (unpackIsBuy(packed) != isBuy) {
            throw new IllegalArgumentException("Order side mismatch for amendment: " + orderId);
        }

        validateOrderInputs(newPrice, newQuantity);
        long newPriceCents = parsePriceToCents(newPrice);
        int newTickIndex = priceToTickIndex(newPriceCents);
        ensureCapacity(newTickIndex);

        int oldSeg = oldTickIndex >>> SEGMENT_SHIFT;
        int oldIn = oldTickIndex & SEGMENT_MASK;
        int newSeg = newTickIndex >>> SEGMENT_SHIFT;
        int newIn = newTickIndex & SEGMENT_MASK;

        if (isBuy) {
            buySegments[oldSeg][oldIn] -= oldQuantity;
            totalBuyVolume -= oldQuantity;

            buySegments[newSeg][newIn] += newQuantity;
            totalBuyVolume += newQuantity;
        } else {
            sellSegments[oldSeg][oldIn] -= oldQuantity;
            sellSegments[newSeg][newIn] += newQuantity;
        }

        orderMap.put(orderId, newTickIndex, packQuantityAndSide(newQuantity, isBuy));
    }

    public void cancelOrder(long orderId, boolean isBuy) {
        long packed = orderMap.get(orderId);
        if (packed == -1L) return;

        int tickIndex = (int) (packed >>> 32);
        long quantity = unpackQuantity(packed);
        if (unpackIsBuy(packed) != isBuy) {
            throw new IllegalArgumentException("Order side mismatch for cancel: " + orderId);
        }

        int segmentId = tickIndex >>> SEGMENT_SHIFT;
        int innerIndex = tickIndex & SEGMENT_MASK;

        if (isBuy) {
            buySegments[segmentId][innerIndex] -= quantity;
            totalBuyVolume -= quantity;
        } else {
            sellSegments[segmentId][innerIndex] -= quantity;
        }

        orderMap.remove(orderId);
    }

    private static void validateOrderInputs(double price, long quantity) {
        if (!Double.isFinite(price) || price <= 0.0) {
            throw new IllegalArgumentException("Price must be a finite positive value");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be > 0");
        }
    }

    private static long packQuantityAndSide(long quantity, boolean isBuy) {
        long packedQty = quantity & QTY_MASK;
        return isBuy ? packedQty | SIDE_BIT : packedQty;
    }

    private static long unpackQuantity(long packed) {
        return packed & QTY_MASK;
    }

    private static boolean unpackIsBuy(long packed) {
        return (packed & SIDE_BIT) != 0;
    }

    // Accessors for AuctionMatcher
    public long getBuyVolumeAt(int index) {
        int seg = index >>> SEGMENT_SHIFT;
        return (seg < buySegments.length && buySegments[seg] != null) ? buySegments[seg][index & SEGMENT_MASK] : 0;
    }

    public long getSellVolumeAt(int index) {
        int seg = index >>> SEGMENT_SHIFT;
        return (seg < sellSegments.length && sellSegments[seg] != null) ? sellSegments[seg][index & SEGMENT_MASK] : 0;
    }

    public long getTotalBuyVolume() { return totalBuyVolume; }
    public int getMinActiveTick() { return minActiveTick; }
    public int getMaxActiveTick() { return maxActiveTick; }
}