package org.macquarie.model;

public class PrimitiveOpenAddressingMap {
    private static final long EMPTY_KEY = 0L;
    private static final long TOMBSTONE_KEY = Long.MIN_VALUE;

    private final int mapCapacity;
    private final long[] idKeys;
    private final long[] orderValues; // Packed: [tickIndex (32 bits) | quantity (32 bits)]
    private int activeOrderCount = 0;

    public PrimitiveOpenAddressingMap(int maxOrders) {
        int cap = 1;
        while (cap < maxOrders * 2) {
            cap <<= 1;
        }
        this.mapCapacity = cap;
        this.idKeys = new long[mapCapacity];
        this.orderValues = new long[mapCapacity];

        java.util.Arrays.fill(idKeys, EMPTY_KEY);
    }

    private int hash(long id) {
        long h = id ^ (id >>> 30);
        h *= 0xbf58476d1ce4e5b9L;
        return (int) (h & (mapCapacity - 1));
    }

    public void put(long orderId, int tickIndex, long quantity) {
        if (orderId == EMPTY_KEY || orderId == TOMBSTONE_KEY) {
            throw new IllegalArgumentException("Reserved order ID cannot be used");
        }

        int idx = hash(orderId);
        int firstTombstoneIdx = -1;
        int probes = 0;

        while (idKeys[idx] != EMPTY_KEY) {
            if (idKeys[idx] == orderId) {
                orderValues[idx] = (((long) tickIndex) << 32) | (quantity & 0xFFFFFFFFL);
                return;
            }
            if (idKeys[idx] == TOMBSTONE_KEY && firstTombstoneIdx == -1) {
                firstTombstoneIdx = idx;
            }
            idx = (idx + 1) & (mapCapacity - 1);
            probes++;
            if (probes >= mapCapacity) {
                throw new IllegalStateException("Max order capacity reached or hash map is full.");
            }
        }

        int targetIdx = (firstTombstoneIdx != -1) ? firstTombstoneIdx : idx;
        boolean occupyingNewSlot = idKeys[targetIdx] == EMPTY_KEY || idKeys[targetIdx] == TOMBSTONE_KEY;

        if (occupyingNewSlot) {
            if (activeOrderCount >= mapCapacity / 2) {
                // Simple safeguard for linear probing load factor
                throw new IllegalStateException("Max order capacity reached.");
            }
            activeOrderCount++;
        }

        idKeys[targetIdx] = orderId;
        orderValues[targetIdx] = (((long) tickIndex) << 32) | (quantity & 0xFFFFFFFFL);
    }

    public long get(long orderId) {
        int idx = hash(orderId);
        int probes = 0;
        while (idKeys[idx] != EMPTY_KEY && probes < mapCapacity) {
            if (idKeys[idx] == orderId) {
                return orderValues[idx];
            }
            idx = (idx + 1) & (mapCapacity - 1);
            probes++;
        }
        return -1L; // Not found
    }

    public void remove(long orderId) {
        int mask = mapCapacity - 1;
        int idx = hash(orderId);
        for (int probes = 0; probes < mapCapacity; probes++) {
            long key = idKeys[idx];
            if (key == EMPTY_KEY) {
                return;
            }
            if (key == orderId) {
                compactProbeChain(idx);
                activeOrderCount--;
                return;
            }
            idx = (idx + 1) & mask;
        }
    }

    /**
     * Knuth Algorithm R: close the deletion hole by shifting later probe-chain
     * occupants backward so lookups never stop early on an empty slot.
     */
    private void compactProbeChain(int hole) {
        int mask = mapCapacity - 1;
        while (true) {
            idKeys[hole] = EMPTY_KEY;
            orderValues[hole] = 0;

            int idx = hole;
            while (true) {
                idx = (idx + 1) & mask;
                long key = idKeys[idx];
                if (key == EMPTY_KEY || key == TOMBSTONE_KEY) {
                    return;
                }
                int home = hash(key);
                if (homeLiesOnRemainingProbePath(home, hole, idx)) {
                    continue;
                }
                idKeys[hole] = key;
                orderValues[hole] = orderValues[idx];
                hole = idx;
                break;
            }
        }
    }

    /**
     * True when {@code home} is cyclically in {@code (hole, idx]}, so the occupant
     * at {@code idx} does not probe through {@code hole} and must stay put.
     */
    private static boolean homeLiesOnRemainingProbePath(int home, int hole, int idx) {
        if (hole < idx) {
            return hole < home && home <= idx;
        }
        return hole < home || home <= idx;
    }

    public boolean containsKey(long orderId) {
        return get(orderId) != -1L;
    }

    public int size() {
        return activeOrderCount;
    }
}