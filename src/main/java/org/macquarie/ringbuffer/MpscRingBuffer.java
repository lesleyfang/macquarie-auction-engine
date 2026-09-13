package org.macquarie.ringbuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

public class MpscRingBuffer {
    private final long[] buffer;
    private final AtomicLongArray sequence;
    private final int mask;

    // Manual padding: isolates head and tail to prevent false sharing across cores
    private long p01, p02, p03, p04, p05, p06, p07;
    private final AtomicLong head = new AtomicLong(0); // Consumer-only pointer
    private long p08, p09, p10, p11, p12, p13, p14;
    private final AtomicLong tail = new AtomicLong(0); // Multi-producer shared pointer
    private long p15, p16, p17, p18, p19, p20, p21;
    private long heldHead;
    private int heldIdx;

    public MpscRingBuffer(int capacity) {
        if ((capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }
        this.buffer = new long[capacity];
        this.mask = capacity - 1;
        this.sequence = new AtomicLongArray(capacity);

        for (int i = 0; i < capacity; i++) {
            sequence.set(i, i);
        }
    }

    public boolean tryPublish(long value) {
        AtomicLongArray seqArray = this.sequence;
        long tailVal;
        int idx;

        // Concurrent producers coordinate via lock-free CAS on tail
        while (true) {
            tailVal = tail.get();
            idx = (int) (tailVal & mask);
            long seq = seqArray.get(idx);
            long diff = seq - tailVal;

            if (diff == 0) {
                if (tail.compareAndSet(tailVal, tailVal + 1)) {
                    break;
                }
            } else if (diff < 0) {
                return false; // Queue full (backpressure)
            }
        }

        buffer[idx] = value;
        seqArray.set(idx, tailVal + 1);
        return true;
    }

    public long poll() {
        long value = pollHold();
        if (value != -1L) {
            releaseHeld();
        }
        return value;
    }

    /**
     * Claims the next slot without recycling it. Pair every successful call with {@link #releaseHeld()}
     * after the payload (including command-pool data) has been copied.
     */
    public long pollHold() {
        AtomicLongArray seqArray = this.sequence;
        long headVal = head.get();
        int idx = (int) (headVal & mask);
        long seq = seqArray.get(idx);
        long diff = seq - (headVal + 1);

        if (diff == 0) {
            head.lazySet(headVal + 1);
            heldHead = headVal;
            heldIdx = idx;
            return buffer[idx];
        }
        return -1L;
    }

    public void releaseHeld() {
        sequence.set(heldIdx, heldHead + mask + 1);
    }

    public boolean tryPublishMulti(Consumer<Integer> slotWriter) {
        AtomicLongArray seqArray = this.sequence;
        long tailVal;
        int idx;

        while (true) {
            tailVal = tail.get();
            idx = (int) (tailVal & mask);
            long seq = seqArray.get(idx);
            long diff = seq - tailVal;

            if (diff == 0) {
                if (tail.compareAndSet(tailVal, tailVal + 1)) {
                    break;
                }
            } else if (diff < 0) {
                return false; // Queue is full (Backpressure)
            }
        }

        // Write command data into the pre-allocated pool slot safely before releasing sequence
        slotWriter.accept(idx);
        buffer[idx] = idx;

        seqArray.set(idx, tailVal + 1);
        return true;
    }

    public boolean isEmpty() { return head.get() == tail.get(); }
}