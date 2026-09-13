package org.macquarie.engine;

@FunctionalInterface
public interface EngineErrorListener {
    void onError(int actionType, long orderId, Exception exception);
}