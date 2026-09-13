package org.macquarie.ringbuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpscRingBufferTest {

    @Test
    void testRingBufferInitializationFailure() {
        assertThrows(IllegalArgumentException.class, () -> new MpscRingBuffer(1000));
    }

    @Test
    void testPublishAndPoll() {
        MpscRingBuffer buffer = new MpscRingBuffer(4);
        assertTrue(buffer.tryPublish(12345L));
        assertTrue(buffer.tryPublish(67890L));
        assertEquals(12345L, buffer.poll());
        assertEquals(67890L, buffer.poll());
        assertEquals(-1L, buffer.poll());
    }

    @Test
    void tryPublishMultiReturnsDistinctSlotIndexes() {
        MpscRingBuffer buffer = new MpscRingBuffer(4);
        int[] written = new int[1];
        assertTrue(buffer.tryPublishMulti(slot -> written[0] = slot));
        assertEquals(0, buffer.poll());
        assertEquals(0, written[0]);

        assertTrue(buffer.tryPublishMulti(slot -> written[0] = slot));
        assertEquals(1, buffer.poll());
        assertEquals(1, written[0]);
    }

    @Test
    void testRingBufferFullBackpressure() {
        MpscRingBuffer buffer = new MpscRingBuffer(2);
        assertTrue(buffer.tryPublish(111L));
        assertTrue(buffer.tryPublish(222L));
        assertFalse(buffer.tryPublish(333L));

        assertEquals(111L, buffer.poll());
        assertTrue(buffer.tryPublish(333L));
        assertEquals(222L, buffer.poll());
        assertEquals(333L, buffer.poll());
    }
}
