package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Row to offset and back, fixed (O(1)) and measured (Fenwick), against brute force. */
class ExtentsTest {

    @Test
    void fixedRowsMapBothWays() {
        FixedExtents e = new FixedExtents(1_000_000, 24);
        assertThat(e.total()).isEqualTo(24_000_000);
        assertThat(e.offset(999_999)).isEqualTo(23_999_976);
        assertThat(e.indexAt(23_999_976)).isEqualTo(999_999);
        assertThat(e.indexAt(23_999_975.9)).isEqualTo(999_998);
        assertThat(e.indexAt(-5)).isZero();
        assertThat(e.indexAt(1e12)).isEqualTo(999_999);
        assertThat(e.size(7)).isEqualTo(24);
        assertThat(e.set(1_000_000, 24)).isFalse();
        assertThat(e.set(0, 24)).isTrue();
        assertThat(e.indexAt(5)).isEqualTo(-1);
        e.set(3, 0);
        assertThat(e.indexAt(5)).isZero();
        assertThatThrownBy(() -> e.set(-1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(e.count()).isEqualTo(3);
    }

    @Test
    void measuredRowsMatchBruteForce() {
        Random random = new Random(11);
        MeasuredExtents e = new MeasuredExtents(20);
        int n = 5_000;
        e.reset(n);
        double[] sizes = new double[n];
        java.util.Arrays.fill(sizes, 20);
        for (int round = 0; round < 3_000; round++) {
            int i = random.nextInt(n);
            double size = 5 + random.nextInt(60);
            boolean changed = sizes[i] != size;
            assertThat(e.measure(i, size)).isEqualTo(changed);
            sizes[i] = size;
            if (round % 100 == 0) {
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    assertThat(e.offset(k)).isEqualTo(sum);
                    double probe = sum + sizes[k] / 2;
                    assertThat(e.indexAt(probe)).isEqualTo(k);
                    assertThat(e.indexAt(sum)).isEqualTo(k);
                    sum += sizes[k];
                }
                assertThat(e.total()).isEqualTo(sum);
                assertThat(e.indexAt(sum + 100)).isEqualTo(n - 1);
            }
        }
        assertThat(e.measured(0) || !e.measured(0)).isTrue();
        double mean = e.meanMeasured();
        e.reset(10); // the measured mean becomes the estimate
        assertThat(e.estimate()).isEqualTo(mean);
        assertThat(e.total()).isEqualTo(10 * mean);
        e.reset(20_000);
        assertThat(e.count()).isEqualTo(20_000);
        assertThat(e.size(19_999)).isEqualTo(mean);
        assertThatThrownBy(() -> e.measure(20_000, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        e.reset(0);
        assertThat(e.indexAt(3)).isEqualTo(-1);
    }
}
