package org.macquarie.engine;

import org.macquarie.core.OrderBook;
import org.macquarie.matcher.AuctionMatcher;
import org.macquarie.model.AuctionResult;
import org.macquarie.ringbuffer.MpscRingBuffer;

public class LowLatencyAuctionEngine {

    public static final int ACTION_ADD = 1;
    public static final int ACTION_AMEND = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_END_AUCTION = 4;
    public static final int ACTION_SHUTDOWN = 5;

    public static class Command {
        public int actionType;
        public long orderId;
        public double price;
        public long quantity;
        public boolean isBuy;
    }

    private final OrderBook orderBook;
    private final AuctionMatcher auctionMatcher;
    private final MpscRingBuffer inboundQueue;
    private final EngineErrorListener errorListener;

    // Pre-allocated command structure to guarantee zero-allocation during runtime
    private final Command[] commandPool;

    private volatile AuctionResult latestAuctionResult = null;
    private volatile boolean running = true;

    public LowLatencyAuctionEngine(double tickSize, int maxOrders, int ringBufferSize, EngineErrorListener errorListener) {
        if (ringBufferSize <= 0 || (ringBufferSize & (ringBufferSize - 1)) != 0) {
            throw new IllegalArgumentException("Ring buffer size must be a positive power of 2");
        }
        this.orderBook = new OrderBook(tickSize, maxOrders);
        this.auctionMatcher = new AuctionMatcher(orderBook);
        this.inboundQueue = new MpscRingBuffer(ringBufferSize);
        this.errorListener = errorListener != null ? errorListener : (type, id, ex) -> {};

        // Command pool indexed identically to the ring buffer slots for zero-GC messaging
        this.commandPool = new Command[ringBufferSize];
        for (int i = 0; i < ringBufferSize; i++) {
            commandPool[i] = new Command();
        }
    }

    /**
     * Thread-safe multi-producer entry point to submit an order action.
     * Writes directly into the pre-allocated command pool slot before publishing the index.
     * Throws an exception if the inbound ring buffer is saturated (Backpressure).
     */
    public boolean submitOrderCommand(int actionType, long orderId, double price, long quantity, boolean isBuy) {
        boolean success = inboundQueue.tryPublishMulti(slotIndex -> {
            Command cmd = commandPool[slotIndex];
            cmd.actionType = actionType;
            cmd.orderId = orderId;
            cmd.price = price;
            cmd.quantity = quantity;
            cmd.isBuy = isBuy;
        });

        if (!success) {
            throw new IllegalStateException("Auction engine inbound queue is full. Backpressure triggered.");
        }
        return true;
    }

    /**
     * Submits an end auction command to the ring buffer for asynchronous clearing calculation.
     */
    public boolean submitEndAuctionCommand() {
        boolean success = inboundQueue.tryPublishMulti(slotIndex -> {
            Command cmd = commandPool[slotIndex];
            cmd.actionType = ACTION_END_AUCTION;
        });

        if (!success) {
            throw new IllegalStateException("Auction engine inbound queue is full. Backpressure triggered.");
        }
        return true;
    }

    /**
     * Submits a graceful shutdown command to terminate the single-writer loop cleanly.
     */
    public boolean submitShutdownCommand() {
        boolean success = inboundQueue.tryPublishMulti(slotIndex -> {
            Command cmd = commandPool[slotIndex];
            cmd.actionType = ACTION_SHUTDOWN;
        });

        if (!success) {
            throw new IllegalStateException("Auction engine inbound queue is full. Cannot submit shutdown command.");
        }
        return true;
    }

    /**
     * Executed exclusively by the single background engine worker thread.
     * Utilizes Thread.onSpinWait() when idle to optimize CPU hyperthreading performance.
     */
    public void processNextCommand() {
        long slotIndexLong = inboundQueue.pollHold();
        if (slotIndexLong != -1L) {
            int slotIndex = (int) slotIndexLong;
            Command pooled = commandPool[slotIndex];
            int actionType = pooled.actionType;
            long orderId = pooled.orderId;
            double price = pooled.price;
            long quantity = pooled.quantity;
            boolean isBuy = pooled.isBuy;
            inboundQueue.releaseHeld();

            try {
                switch (actionType) {
                    case ACTION_ADD:
                        orderBook.addOrder(orderId, price, quantity, isBuy);
                        break;
                    case ACTION_AMEND:
                        orderBook.amendOrder(orderId, price, quantity, isBuy);
                        break;
                    case ACTION_CANCEL:
                        orderBook.cancelOrder(orderId, isBuy);
                        break;
                    case ACTION_END_AUCTION:
                        this.latestAuctionResult = auctionMatcher.calculateAuctionMatch();
                        break;
                    case ACTION_SHUTDOWN:
                        this.running = false;
                        break;
                    default:
                        errorListener.onError(
                                actionType,
                                orderId,
                                new IllegalArgumentException("Unknown action type: " + actionType)
                        );
                        break;
                }
            } catch (Exception e) {
                // Intercept domain rule exceptions (e.g., order not found, duplicate ID)
                // and route to listener without killing the engine thread loop.
                errorListener.onError(actionType, orderId, e);
            }
        } else {
            // Hint to the CPU that we are spinning, reducing power waste and cache contention
            Thread.onSpinWait();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Checks if there are any unconsumed commands remaining in the inbound ring buffer.
     */
    public boolean hasPendingCommands() {
        return !inboundQueue.isEmpty();
    }

    public AuctionResult getLatestAuctionResult() {
        return latestAuctionResult;
    }

    public OrderBook getOrderBook() {
        return orderBook;
    }
}