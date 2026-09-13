package org.macquarie.engine;

import org.junit.jupiter.api.Test;
import org.macquarie.model.AuctionResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.macquarie.engine.LowLatencyAuctionEngine.ACTION_ADD;
import static org.macquarie.engine.LowLatencyAuctionEngine.ACTION_AMEND;
import static org.macquarie.engine.LowLatencyAuctionEngine.ACTION_CANCEL;

class LowLatencyAuctionEngineTest {

    private static final double TICK = 0.01;
    private static final double EPS = 1e-6;

    @Test
    void rejectsNonPowerOfTwoRingBufferSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new LowLatencyAuctionEngine(TICK, 16, 1000, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LowLatencyAuctionEngine(TICK, 16, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LowLatencyAuctionEngine(TICK, 16, -8, null));
    }

    @Test
    void endAuctionAndShutdownThrowWhenInboundQueueIsFull() {
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, 16, 2, null);
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.00, 10, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.00, 10, false);

        IllegalStateException endEx = assertThrows(IllegalStateException.class, engine::submitEndAuctionCommand);
        assertTrue(endEx.getMessage().contains("Backpressure"));

        IllegalStateException shutdownEx = assertThrows(IllegalStateException.class, engine::submitShutdownCommand);
        assertTrue(shutdownEx.getMessage().contains("Cannot submit shutdown command"));
    }

    @Test
    void shouldReturnZeroWhenNoOrdersPresent() {
        LowLatencyAuctionEngine engine = newEngine();
        AuctionResult result = runToCompletion(engine);
        assertEquals(0.0, result.auctionPrice(), EPS);
        assertEquals(0L, result.matchedVolume());
        assertFalse(engine.isRunning());
    }

    @Test
    void shouldMatchAtSingleBestPriceWhenNoTieRange() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.05, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void shouldChooseUpperBoundWhenBuySurplusDominatesTieRange() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.06, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.02, 20, false);
        engine.submitOrderCommand(ACTION_ADD, 3L, 10.03, 20, false);
        engine.submitOrderCommand(ACTION_ADD, 4L, 10.04, 20, false);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.06, result.auctionPrice(), EPS);
        assertEquals(60L, result.matchedVolume());
    }

    @Test
    void shouldChooseLowerBoundWhenSellSurplusDominatesTieRange() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.03, 20, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.04, 20, true);
        engine.submitOrderCommand(ACTION_ADD, 3L, 10.05, 20, true);
        engine.submitOrderCommand(ACTION_ADD, 4L, 10.02, 100, false);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.02, result.auctionPrice(), EPS);
        assertEquals(60L, result.matchedVolume());
    }

    @Test
    void shouldChooseMidpointWhenSurplusIsBalancedAcrossTieRange() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.06, 80, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.02, 40, false);
        engine.submitOrderCommand(ACTION_ADD, 3L, 10.03, 40, false);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.04, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void routesUnknownActionTypesToErrorListener() {
        List<Integer> actionTypes = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, 16, 16,
                (actionType, orderId, exception) -> {
                    actionTypes.add(actionType);
                    orderIds.add(orderId);
                    messages.add(exception.getMessage());
                });

        engine.submitOrderCommand(99, 42L, 10.00, 10, true);
        engine.submitShutdownCommand();
        drain(engine);

        assertEquals(List.of(99), actionTypes);
        assertEquals(List.of(42L), orderIds);
        assertTrue(messages.get(0).contains("Unknown action type: 99"));
        assertFalse(engine.isRunning());
    }

    @Test
    void shouldAmendQuantityBeforeMatching() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.05, 20, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);
        engine.submitOrderCommand(ACTION_AMEND, 1L, 10.05, 100, true);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void shouldAmendPriceBeforeMatching() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.00, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);
        engine.submitOrderCommand(ACTION_AMEND, 1L, 10.05, 100, true);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void shouldCancelOrderBeforeMatching() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.05, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);
        engine.submitOrderCommand(ACTION_CANCEL, 2L, 0.0, 0, false);

        AuctionResult result = runToCompletion(engine);
        assertEquals(0.0, result.auctionPrice(), EPS);
        assertEquals(0L, result.matchedVolume());
    }

    @Test
    void cancelOfUnknownOrderIsIgnored() {
        LowLatencyAuctionEngine engine = newEngine();
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.05, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);
        engine.submitOrderCommand(ACTION_CANCEL, 99L, 0.0, 0, true);

        AuctionResult result = runToCompletion(engine);
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void wrongSideAmendIsRoutedToErrorListenerAndDoesNotCorruptTheBook() {
        List<Integer> actionTypes = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, 16, 16,
                (actionType, orderId, exception) -> {
                    actionTypes.add(actionType);
                    orderIds.add(orderId);
                });

        engine.submitOrderCommand(ACTION_ADD, 1L, 10.05, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);
        engine.submitOrderCommand(ACTION_AMEND, 1L, 10.08, 70, false);

        AuctionResult result = runToCompletion(engine);

        assertEquals(List.of(ACTION_AMEND), actionTypes);
        assertEquals(List.of(1L), orderIds);
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void wrongSideCancelIsRoutedToErrorListenerAndDoesNotCorruptTheBook() {
        List<Integer> actionTypes = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, 16, 16,
                (actionType, orderId, exception) -> {
                    actionTypes.add(actionType);
                    orderIds.add(orderId);
                });

        engine.submitOrderCommand(ACTION_ADD, 1L, 10.05, 100, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.03, 80, false);
        engine.submitOrderCommand(ACTION_CANCEL, 1L, 0.0, 0, false);

        AuctionResult result = runToCompletion(engine);

        assertEquals(List.of(ACTION_CANCEL), actionTypes);
        assertEquals(List.of(1L), orderIds);
        assertEquals(10.05, result.auctionPrice(), EPS);
        assertEquals(80L, result.matchedVolume());
    }

    @Test
    void amendOfUnknownOrderIsRoutedToErrorListener() {
        List<Integer> actionTypes = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, 16, 16,
                (actionType, orderId, exception) -> {
                    actionTypes.add(actionType);
                    orderIds.add(orderId);
                });

        engine.submitOrderCommand(ACTION_AMEND, 42L, 10.00, 10, true);
        engine.submitShutdownCommand();
        drain(engine);

        assertEquals(List.of(ACTION_AMEND), actionTypes);
        assertEquals(List.of(42L), orderIds);
        assertFalse(engine.isRunning());
    }

    @Test
    void routesDomainErrorsToErrorListenerWithoutStoppingTheEngine() {
        List<Integer> actionTypes = new ArrayList<>();
        LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(TICK, 16, 16,
                (actionType, orderId, exception) -> actionTypes.add(actionType));

        engine.submitOrderCommand(ACTION_ADD, 1L, 10.00, 10, true);
        engine.submitOrderCommand(ACTION_ADD, 1L, 10.01, 5, true);
        engine.submitOrderCommand(ACTION_ADD, 2L, 10.00, 10, false);
        AuctionResult result = runToCompletion(engine);

        assertEquals(List.of(ACTION_ADD), actionTypes);
        assertEquals(10L, result.matchedVolume());
    }

    private static LowLatencyAuctionEngine newEngine() {
        return new LowLatencyAuctionEngine(TICK, 32, 32, null);
    }

    private static AuctionResult runToCompletion(LowLatencyAuctionEngine engine) {
        engine.submitEndAuctionCommand();
        engine.submitShutdownCommand();
        drain(engine);
        return engine.getLatestAuctionResult();
    }

    private static void drain(LowLatencyAuctionEngine engine) {
        while (engine.isRunning() || engine.hasPendingCommands()) {
            engine.processNextCommand();
        }
    }
}
