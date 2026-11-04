package com.truesim.ui.core.draw;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DirtyRangesTest {

    @AfterEach
    void restore() {
        DirtyRanges.MERGE_GAP.set(DirtyRanges.MERGE_GAP.initial());
        DirtyRanges.MAX_RANGES.set(DirtyRanges.MAX_RANGES.initial());
    }

    @Test
    void rangesStaySortedDisjointAndMergeWhenClose() {
        DirtyRanges.MERGE_GAP.set(4);
        DirtyRanges r = new DirtyRanges();
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.first()).isZero();
        assertThat(r.end()).isZero();
        r.add(100, 101);
        r.add(10, 12);
        r.add(50, 51);
        r.add(5, 5); // empty: nothing
        assertThat(ranges(r)).containsExactly(10, 12, 50, 51, 100, 101);
        r.add(14, 15); // 2 past range one: merged
        assertThat(ranges(r)).containsExactly(10, 15, 50, 51, 100, 101);
        r.add(18, 47); // within the gap of both neighbours: one range
        assertThat(ranges(r)).containsExactly(10, 51, 100, 101);
        r.add(0, 200); // covers all
        assertThat(ranges(r)).containsExactly(0, 200);
        assertThat(r.records()).isEqualTo(200);
        r.clear();
        assertThat(r.count()).isZero();
    }

    @Test
    void pastTheMostRangesTheClosestMerge() {
        DirtyRanges.MERGE_GAP.set(0);
        DirtyRanges.MAX_RANGES.set(3);
        DirtyRanges r = new DirtyRanges();
        r.add(0, 1);
        r.add(100, 101);
        r.add(200, 201);
        r.add(210, 211); // closest to 200
        assertThat(ranges(r)).containsExactly(0, 1, 100, 101, 200, 211);
    }

    @Test
    void randomChangesAreCoveredAndRangesNeverOverlap() {
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            DirtyRanges.MERGE_GAP.set(random.nextInt(8));
            DirtyRanges.MAX_RANGES.set(1 + random.nextInt(20));
            DirtyRanges r = new DirtyRanges();
            boolean[] changed = new boolean[520];
            for (int k = 0; k < 40; k++) {
                int a = random.nextInt(490);
                int b = a + 1 + random.nextInt(random.nextBoolean() ? 2 : 10);
                for (int i = a; i < b; i++) {
                    changed[i] = true;
                }
                r.add(a, b);
            }
            assertThat(r.count()).isLessThanOrEqualTo((int) DirtyRanges.MAX_RANGES.get());
            for (int i = 0; i + 1 < r.count(); i++) {
                assertThat(r.to(i)).isLessThan(r.from(i + 1));
            }
            for (int i = 0; i < changed.length; i++) {
                if (changed[i]) {
                    boolean in = false;
                    for (int k = 0; k < r.count(); k++) {
                        in |= r.from(k) <= i && i < r.to(k);
                    }
                    assertThat(in).as("record %d covered", i).isTrue();
                }
            }
        }
    }

    @Test
    void addingAllocatesNothing() {
        DirtyRanges r = new DirtyRanges();
        for (int i = 0; i < 64; i++) {
            r.add(i * 100, i * 100 + 1); // grown once
        }
        int[] n = {0};
        AllocationAssertions.assertAllocationFree(() -> {
            r.clear();
            r.add(n[0] & 1023, (n[0]++ & 1023) + 3);
            r.add(2000, 2001);
            r.add(5, 9);
        });
    }

    private static int[] ranges(DirtyRanges r) {
        int[] out = new int[r.count() * 2];
        for (int i = 0; i < r.count(); i++) {
            out[i * 2] = r.from(i);
            out[i * 2 + 1] = r.to(i);
        }
        return out;
    }
}
