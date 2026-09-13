# Macquarie Auction Engine

In-process, mid-scale call-auction engine. Producers submit add/amend/cancel commands through an MPSC ring buffer; a single worker thread owns the order book and clearing match.

This is a **mid-level scale** in-memory engine (on the order of **10⁶ live orders** when `maxOrders` is set accordingly), not a production exchange. See [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md) for architecture, what is implemented, and known gaps.

## Requirements

- JDK 17+
- Gradle Wrapper (included)

Windows:

```bat
.\gradlew.bat test
.\gradlew.bat run
```

Linux / macOS:

```bash
./gradlew test
./gradlew run
```

`run` starts `org.macquarie.Main`: two producer threads submit sample orders, then the demo sends `END_AUCTION` and `SHUTDOWN` (with bounded retry) and prints the clearing price and matched volume.

## Tests

```bat
.\gradlew.bat test
```

Useful subsets:

```bat
.\gradlew.bat test --tests org.macquarie.engine.LowLatencyAuctionEngineTest
.\gradlew.bat test --tests org.macquarie.engine.LowLatencyAuctionEngineIntegrationTest
.\gradlew.bat test --tests org.macquarie.engine.LowLatencyAuctionEngineStressTest
.\gradlew.bat test --tests org.macquarie.engine.LowLatencyAuctionEngineLatencyTest
```

## Embed in code

```java
LowLatencyAuctionEngine engine = new LowLatencyAuctionEngine(
        0.01,   // tick size
        1000,   // max live orders
        2048,   // inbound ring size (power of 2)
        (actionType, orderId, ex) -> { /* error handling */ }
);

Thread worker = new Thread(() -> {
    while (engine.isRunning() || engine.hasPendingCommands()) {
        engine.processNextCommand();
    }
});
worker.start();

engine.submitOrderCommand(LowLatencyAuctionEngine.ACTION_ADD, 1L, 100.00, 100, true);
engine.submitEndAuctionCommand();
engine.submitShutdownCommand();
worker.join();

AuctionResult result = engine.getLatestAuctionResult();
```

The worker thread is **not** started by the engine. Callers must drive `processNextCommand()`.
