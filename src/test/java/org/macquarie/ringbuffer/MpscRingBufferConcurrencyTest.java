package org.macquarie.ringbuffer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpscRingBufferConcurrencyTest {

    @Test
    void testMpscRingBufferConcurrentSingleProducerSingleConsumer() throws Exception {
        int capacity = 1024;
        MpscRingBuffer buffer = new MpscRingBuffer(capacity);
        int messageCount = 100_000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger(0);

        ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService consumerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> producer = producerExecutor.submit(() -> {
                start.await();
                for (int i = 0; i < messageCount; ) {
                    if (buffer.tryPublish(i + 1L)) {
                        i++;
                    } else {
                        Thread.onSpinWait();
                    }
                }
                return null;
            });

            Future<Integer> consumer = consumerExecutor.submit(() -> {
                start.await();
                int expected = 1;
                int received = 0;
                while (received < messageCount) {
                    long value = buffer.poll();
                    if (value != -1L) {
                        assertEquals(expected, (int) value, "Messages must be consumed in strict FIFO order");
                        expected++;
                        received++;
                        consumed.incrementAndGet();
                    } else {
                        Thread.onSpinWait();
                    }
                }
                return received;
            });

            start.countDown();
            producer.get(5, TimeUnit.SECONDS);
            assertEquals(messageCount, consumer.get(5, TimeUnit.SECONDS));
            assertEquals(messageCount, consumed.get());
        } finally {
            producerExecutor.shutdown();
            consumerExecutor.shutdown();
        }
    }

    @Test
    void testMpscRingBufferConcurrentThreeProducersOneConsumer() throws Exception {
        int producerCount = 3;
        int messagesPerProducer = 20_000;
        int totalMessages = producerCount * messagesPerProducer;
        MpscRingBuffer buffer = new MpscRingBuffer(1024);
        CountDownLatch start = new CountDownLatch(1);
        AtomicIntegerArray seen = new AtomicIntegerArray(totalMessages + 1);

        ExecutorService pool = Executors.newFixedThreadPool(producerCount + 1);
        try {
            for (int p = 0; p < producerCount; p++) {
                final int producerId = p;
                pool.submit(() -> {
                    start.await();
                    long base = (long) producerId * messagesPerProducer;
                    for (int i = 1; i <= messagesPerProducer; ) {
                        if (buffer.tryPublish(base + i)) {
                            i++;
                        } else {
                            Thread.onSpinWait();
                        }
                    }
                    return null;
                });
            }

            Future<Integer> consumer = pool.submit(() -> {
                start.await();
                int received = 0;
                while (received < totalMessages) {
                    long value = buffer.poll();
                    if (value != -1L) {
                        int id = (int) value;
                        assertTrue(id >= 1 && id <= totalMessages);
                        assertEquals(0, seen.getAndIncrement(id), "duplicate message " + id);
                        received++;
                    } else {
                        Thread.onSpinWait();
                    }
                }
                return received;
            });

            start.countDown();
            assertEquals(totalMessages, consumer.get(10, TimeUnit.SECONDS));
            for (int id = 1; id <= totalMessages; id++) {
                assertEquals(1, seen.get(id), "missing message " + id);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void testTryPublishMultiConcurrentThreeProducersOneConsumer() throws Exception {
        int producerCount = 3;
        int messagesPerProducer = 10_000;
        int totalMessages = producerCount * messagesPerProducer;
        MpscRingBuffer buffer = new MpscRingBuffer(512);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger published = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(producerCount + 1);
        try {
            for (int p = 0; p < producerCount; p++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < messagesPerProducer; ) {
                        if (buffer.tryPublishMulti(slot -> {})) {
                            published.incrementAndGet();
                            i++;
                        } else {
                            Thread.onSpinWait();
                        }
                    }
                    return null;
                });
            }

            Future<Integer> consumer = pool.submit(() -> {
                start.await();
                int received = 0;
                while (received < totalMessages) {
                    if (buffer.poll() != -1L) {
                        received++;
                        consumed.incrementAndGet();
                    } else {
                        Thread.onSpinWait();
                    }
                }
                return received;
            });

            start.countDown();
            assertEquals(totalMessages, consumer.get(10, TimeUnit.SECONDS));
            assertEquals(totalMessages, published.get());
            assertEquals(totalMessages, consumed.get());
            assertTrue(buffer.isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }
}
