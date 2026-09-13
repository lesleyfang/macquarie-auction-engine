package org.macquarie.support;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

public final class HeapSnapshot {

    private HeapSnapshot() {}

    public static String report(String label) {
        System.gc();
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Runtime rt = Runtime.getRuntime();
        long used = heap.getUsed();
        long committed = heap.getCommitted();
        long runtimeUsed = rt.totalMemory() - rt.freeMemory();
        return label
                + " heap used=" + mb(used)
                + " committed=" + mb(committed)
                + " runtime used=" + mb(runtimeUsed)
                + " max=" + mb(heap.getMax());
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
