package org.macquarie;

import org.macquarie.engine.EngineErrorListener;
import org.macquarie.engine.LowLatencyAuctionEngine;
import org.macquarie.model.AuctionResult;

import java.util.function.BooleanSupplier;

import static org.macquarie.engine.LowLatencyAuctionEngine.ACTION_ADD;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(
                0.01,
                1000,
                2048,
                getEngineErrorListener()
        );

        // Replace the engine thread initialization here:
        Thread engineThread = new Thread(() -> {
            while (engine.isRunning() || engine.hasPendingCommands()) {
                engine.processNextCommand();
            }
        });
        engineThread.start();

        // Simulate producer threads submitting orders concurrently
        Thread producer1 = new Thread(() -> {
            engine.submitOrderCommand(ACTION_ADD, 1L, 100.00, 100, true);
            engine.submitOrderCommand(ACTION_ADD, 2L, 99.00, 1000, true);
        });

        Thread producer2 = new Thread(() -> {
            engine.submitOrderCommand(ACTION_ADD, 3L, 39.00, 100, false);
            engine.submitOrderCommand(ACTION_ADD, 4L, 99.00, 200, false);
        });

        producer1.start();
        producer2.start();
        producer1.join();
        producer2.join();

        submitControlCommandWithRetry("END_AUCTION", engine::submitEndAuctionCommand);
        submitControlCommandWithRetry("SHUTDOWN", engine::submitShutdownCommand);

        // Wait for the worker thread to completely drain the queue and exit
        engineThread.join();

        AuctionResult result = engine.getLatestAuctionResult();
        if (result != null) {
            System.out.println("Auction Clearing Price: $" + result.auctionPrice());
            System.out.println("Total Matched Volume: " + result.matchedVolume());
        } else {
            System.out.println("Auction result not available.");
        }
    }

    private static final int MAX_CONTROL_SUBMIT_ATTEMPTS = 1024;

    private static void submitControlCommandWithRetry(String commandName, BooleanSupplier submit) {
        for (int attempt = 1; attempt <= MAX_CONTROL_SUBMIT_ATTEMPTS; attempt++) {
            try {
                if (submit.getAsBoolean()) {
                    return;
                }
            } catch (IllegalStateException ex) {
                if (attempt == MAX_CONTROL_SUBMIT_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Failed to submit " + commandName + " after " + MAX_CONTROL_SUBMIT_ATTEMPTS + " attempts",
                            ex
                    );
                }
                Thread.onSpinWait();
                continue;
            }
            if (attempt == MAX_CONTROL_SUBMIT_ATTEMPTS) {
                throw new IllegalStateException(
                        "Failed to submit " + commandName + " after " + MAX_CONTROL_SUBMIT_ATTEMPTS + " attempts"
                );
            }
            Thread.onSpinWait();
        }
    }

    private static EngineErrorListener getEngineErrorListener() {
        return (actionType, orderId, exception) -> System.err.println(
                "Engine error actionType=" + actionType +
                        ", orderId=" + orderId +
                        ", message=" + exception.getMessage()
        );
    }
}