package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * An ordered sequence of distinct ints (source indices) with O(log n) positional access and O(B) insertion and
 * removal: blocks of at most {@code 2B} entries in order, a Fenwick tree over the blocks' sizes for positions, and
 * for each entry the block holding it. What {@link DataView} keeps its sorted, filtered rows in, so a change of one
 * row of a million moves a few hundred ints, not a million.
 */
final class OrderIndex {

    /** Compares two entries (source indices): the view's order. */
    interface Order {
        int compare(int a, int b);
    }

    static final int B = 512;

    private int[][] blocks = new int[8][];
    private int[] blockSize = new int[8];
    private int[] freeIds = new int[8];
    private int freeCount;
    private int idCount;
    /** Block ids in order. */
    private int[] order = new int[8];
    private int[] rankOf = new int[8];
    private int blockCount;
    private int[] fenwick = new int[9];
    private int highBit = 1;
    /** Entry -> block id, or -1 when absent. */
    private int[] blockOf = new int[0];
    private int size;

    /** Entries in it. */
    int size() {
        return size;
    }

    /** Whether {@code entry} is in it. */
    boolean contains(int entry) {
        return entry < blockOf.length && blockOf[entry] >= 0;
    }

    /** Makes room for entries below {@code n}. */
    @AllowAllocation("grows with the source, amortized")
    void capacity(int n) {
        if (n > blockOf.length) {
            int old = blockOf.length;
            blockOf = Arrays.copyOf(blockOf, Math.max(n, old * 2));
            Arrays.fill(blockOf, old, blockOf.length, -1);
        }
    }

    /** Replaces everything with the first {@code n} entries of {@code sorted}, in that order. */
    @AllowAllocation("a full rebuild: a new order or filter")
    void build(int[] sorted, int n, int entries) {
        capacity(entries);
        Arrays.fill(blockOf, -1);
        freeCount = 0;
        idCount = 0;
        blockCount = 0;
        size = 0;
        for (int at = 0; at < n; at += B) {
            int count = Math.min(B, n - at);
            int id = newBlock();
            System.arraycopy(sorted, at, blocks[id], 0, count);
            blockSize[id] = count;
            for (int i = 0; i < count; i++) {
                blockOf[sorted[at + i]] = id;
            }
            appendOrder(id);
            size += count;
        }
        reindex();
    }

    /** The entry at {@code position}. */
    int get(int position) {
        // descend the Fenwick tree to the block holding the position
        int at = 0;
        int rest = position;
        for (int step = highBit; step > 0; step >>= 1) {
            int next = at + step;
            if (next <= blockCount && fenwick[next] <= rest) {
                at = next;
                rest -= fenwick[next];
            }
        }
        return blocks[order[at]][rest];
    }

    /** The position of {@code entry} (in it). */
    int positionOf(int entry) {
        int id = blockOf[entry];
        int[] block = blocks[id];
        int n = blockSize[id];
        int j = 0;
        while (block[j] != entry) {
            j++;
            if (j == n) {
                throw new IllegalStateException("entry " + entry + " is not in its block");
            }
        }
        return before(rankOf[id]) + j;
    }

    /** Removes {@code entry} (in it). */
    void remove(int entry) {
        int id = blockOf[entry];
        int[] block = blocks[id];
        int n = blockSize[id];
        int j = 0;
        while (block[j] != entry) {
            j++;
        }
        System.arraycopy(block, j + 1, block, j, n - j - 1);
        blockSize[id] = n - 1;
        blockOf[entry] = -1;
        size--;
        if (n == 1) {
            removeBlock(id);
        } else {
            add(rankOf[id], -1);
        }
    }

    /** Inserts {@code entry} (not in it) at its place by {@code by}; returns its position. */
    int insert(int entry, Order by) {
        if (blockCount == 0) {
            int id = newBlock();
            appendOrder(id);
            reindex();
        }
        // the first block whose last entry comes after the new one (else the last block)
        int low = 0;
        int high = blockCount - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            int id = order[mid];
            if (blockSize[id] > 0 && by.compare(entry, blocks[id][blockSize[id] - 1]) < 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        int rank = low;
        int id = order[rank];
        int[] block = blocks[id];
        int n = blockSize[id];
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (by.compare(entry, block[mid]) < 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        System.arraycopy(block, lo, block, lo + 1, n - lo);
        block[lo] = entry;
        blockSize[id] = n + 1;
        blockOf[entry] = id;
        size++;
        add(rank, 1);
        int position = before(rank) + lo;
        if (n + 1 == 2 * B) {
            split(rank);
        }
        return position;
    }

    // ---------------------------------------------------------------- blocks

    @AllowAllocation("blocks are made as the view grows, then reused")
    private int newBlock() {
        int id;
        if (freeCount > 0) {
            id = freeIds[--freeCount];
        } else {
            id = idCount++;
            if (id == blocks.length) {
                int n = blocks.length * 2;
                blocks = Arrays.copyOf(blocks, n);
                blockSize = Arrays.copyOf(blockSize, n);
                rankOf = Arrays.copyOf(rankOf, n);
            }
            if (blocks[id] == null) {
                blocks[id] = new int[2 * B];
            }
        }
        blockSize[id] = 0;
        return id;
    }

    @AllowAllocation("grows with the blocks, rarely")
    private void appendOrder(int id) {
        if (blockCount == order.length) {
            order = Arrays.copyOf(order, blockCount * 2);
        }
        order[blockCount++] = id;
    }

    /** Splits the full block at {@code rank} in two halves. */
    private void split(int rank) {
        int id = order[rank];
        int fresh = newBlock();
        int half = B;
        System.arraycopy(blocks[id], half, blocks[fresh], 0, blockSize[id] - half);
        blockSize[fresh] = blockSize[id] - half;
        blockSize[id] = half;
        int[] moved = blocks[fresh];
        for (int i = 0; i < blockSize[fresh]; i++) {
            blockOf[moved[i]] = fresh;
        }
        appendOrder(fresh); // grow; then move it in place
        System.arraycopy(order, rank + 1, order, rank + 2, blockCount - rank - 2);
        order[rank + 1] = fresh;
        reindex();
    }

    @AllowAllocation("grows with the blocks, rarely")
    private void removeBlock(int id) {
        int rank = rankOf[id];
        System.arraycopy(order, rank + 1, order, rank, blockCount - rank - 1);
        blockCount--;
        if (freeCount == freeIds.length) {
            freeIds = Arrays.copyOf(freeIds, freeCount * 2);
        }
        freeIds[freeCount++] = id;
        reindex();
    }

    /** Ranks and the Fenwick tree from the order: O(blocks), after blocks came or went. */
    @AllowAllocation("grows with the blocks, rarely")
    private void reindex() {
        if (fenwick.length < blockCount + 1) {
            fenwick = new int[Math.max(blockCount + 1, fenwick.length * 2)];
        }
        Arrays.fill(fenwick, 0, blockCount + 1, 0);
        for (int r = 0; r < blockCount; r++) {
            int id = order[r];
            rankOf[id] = r;
            int i = r + 1;
            fenwick[i] += blockSize[id];
            int parent = i + (i & -i);
            if (parent <= blockCount) {
                fenwick[parent] += fenwick[i];
            }
        }
        highBit = Integer.highestOneBit(Math.max(1, blockCount));
    }

    private void add(int rank, int delta) {
        for (int i = rank + 1; i <= blockCount; i += i & -i) {
            fenwick[i] += delta;
        }
    }

    /** Entries in the blocks before rank {@code rank}. */
    private int before(int rank) {
        int sum = 0;
        for (int i = rank; i > 0; i -= i & -i) {
            sum += fenwick[i];
        }
        return sum;
    }
}
