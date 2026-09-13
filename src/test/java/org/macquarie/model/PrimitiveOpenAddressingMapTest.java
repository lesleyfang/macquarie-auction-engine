package org.macquarie.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveOpenAddressingMapTest {

    @Test
    void putGetAndRemoveRoundTrip() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(8);
        map.put(7L, 12, 100L);

        long packed = map.get(7L);
        assertEquals(12, packed >>> 32);
        assertEquals(100L, packed & 0xFFFFFFFFL);
        assertEquals(1, map.size());

        map.remove(7L);
        assertFalse(map.containsKey(7L));
        assertEquals(0, map.size());
    }

    @Test
    void removeKeepsLaterProbeChainEntriesReachable() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(32);
        for (long id = 1; id <= 24; id++) {
            map.put(id, (int) id, id * 10);
        }

        map.remove(1L);
        map.remove(8L);
        map.remove(17L);

        for (long id = 1; id <= 24; id++) {
            if (id == 1L || id == 8L || id == 17L) {
                assertFalse(map.containsKey(id));
            } else {
                assertTrue(map.containsKey(id), "probe chain lost order " + id);
                long packed = map.get(id);
                assertEquals(id, packed >>> 32);
                assertEquals(id * 10, packed & 0xFFFFFFFFL);
            }
        }
        assertEquals(21, map.size());
    }

    @Test
    void removedSlotCanBeReusedWithoutLosingNeighbors() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(8);
        map.put(1L, 1, 10L);
        map.put(2L, 2, 20L);
        map.put(3L, 3, 30L);

        map.remove(2L);
        map.put(2L, 99, 77L);

        assertEquals(99, map.get(2L) >>> 32);
        assertEquals(77L, map.get(2L) & 0xFFFFFFFFL);
        assertTrue(map.containsKey(1L));
        assertTrue(map.containsKey(3L));
        assertEquals(3, map.size());
    }

    @Test
    void reservedKeysAreRejected() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(4);
        assertThrows(IllegalArgumentException.class, () -> map.put(0L, 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> map.put(Long.MIN_VALUE, 1, 1L));
    }

    @Test
    void putRejectsWhenLiveOrderCapIsReached() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(4);
        map.put(1L, 1, 10L);
        map.put(2L, 2, 20L);
        map.put(3L, 3, 30L);
        map.put(4L, 4, 40L);
        assertEquals(4, map.size());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> map.put(5L, 5, 50L));
        assertEquals("Max order capacity reached.", ex.getMessage());
        assertFalse(map.containsKey(5L));
        assertEquals(4, map.size());
    }

    @Test
    void putOverwritesExistingIdWithoutChangingSize() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(8);
        map.put(1L, 10, 100L);
        map.put(1L, 22, 77L);

        assertEquals(1, map.size());
        long packed = map.get(1L);
        assertEquals(22, packed >>> 32);
        assertEquals(77L, packed & 0xFFFFFFFFL);
    }

    @Test
    void removeMissingIdIsNoOp() {
        PrimitiveOpenAddressingMap map = new PrimitiveOpenAddressingMap(8);
        map.put(1L, 10, 100L);

        map.remove(99L);

        assertEquals(1, map.size());
        assertTrue(map.containsKey(1L));
        assertFalse(map.containsKey(99L));
        assertEquals(-1L, map.get(99L));
    }
}
