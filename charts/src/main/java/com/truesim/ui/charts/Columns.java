package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.RangeVisitor;
import com.truesim.common.state.Versioned;
import com.truesim.common.state.VersionedDoubleArray;
import com.truesim.common.state.VersionedDoubleRing;
import com.truesim.common.state.VersionedFloatArray;
import com.truesim.common.state.VersionedFloatRing;
import java.util.function.IntToDoubleFunction;

/**
 * Makes {@link Column}s: over {@code common-state}'s versioned arrays and rings (the chart polls their versions; a
 * ring's appended samples are added incrementally), over plain arrays (call {@link Values#changed()} after writing
 * them), implicit evenly spaced values ({@link #index}: sample numbers or times of a fixed rate), and getters over
 * computed data (with a {@link Versioned} that says when they change, or none for data that never does).
 */
@AllowAllocation("columns are made when a chart is built")
public final class Columns {

    private Columns() {
    }

    /** A column over {@code values} (not copied): call {@link Values#changed()} after changing them. */
    public static Values of(double... values) {
        return new Values(values);
    }

    /** A column over {@code values} (not copied): call {@link FloatValues#changed()} after changing them. */
    public static FloatValues of(float... values) {
        return new FloatValues(values);
    }

    /** A column over a versioned array. */
    public static Column of(VersionedDoubleArray values) {
        return new Column() {
            @Override
            public long first() {
                return 0;
            }

            @Override
            public long end() {
                return values.length();
            }

            @Override
            public double get(long index) {
                return values.get((int) index);
            }

            @Override
            public long version() {
                return values.version();
            }
        };
    }

    /** A column over a versioned array of floats. */
    public static Column of(VersionedFloatArray values) {
        return new Column() {
            @Override
            public long first() {
                return 0;
            }

            @Override
            public long end() {
                return values.length();
            }

            @Override
            public double get(long index) {
                return values.get((int) index);
            }

            @Override
            public long version() {
                return values.version();
            }
        };
    }

    /** A column over a streaming ring: its window moves as samples are appended, and only they are added. */
    public static Column of(VersionedDoubleRing ring) {
        return new Column() {
            @Override
            public long first() {
                return ring.first();
            }

            @Override
            public long end() {
                return ring.appended();
            }

            @Override
            public double get(long index) {
                return ring.get(index);
            }

            @Override
            public long version() {
                return ring.version();
            }

            @Override
            public long structureVersion() {
                return ring.structureVersion();
            }

            @Override
            public boolean appendedSince(long since, RangeVisitor visitor) {
                return ring.changesSince(since, visitor);
            }

            @Override
            public long capacity() {
                return ring.capacity();
            }
        };
    }

    /** A column over a streaming ring of floats. */
    public static Column of(VersionedFloatRing ring) {
        return new Column() {
            @Override
            public long first() {
                return ring.first();
            }

            @Override
            public long end() {
                return ring.appended();
            }

            @Override
            public double get(long index) {
                return ring.get(index);
            }

            @Override
            public long version() {
                return ring.version();
            }

            @Override
            public long structureVersion() {
                return ring.structureVersion();
            }

            @Override
            public boolean appendedSince(long since, RangeVisitor visitor) {
                return ring.changesSince(since, visitor);
            }

            @Override
            public long capacity() {
                return ring.capacity();
            }
        };
    }

    /**
     * Evenly spaced values {@code start + index * step} at every index: the X of samples by number or at a fixed
     * rate (a ring's sample {@code i} at {@code t0 + i / rate}). Never changes; it takes the window of the other
     * columns of its series.
     */
    public static Column index(double start, double step) {
        return new Column() {
            @Override
            public long first() {
                return Long.MIN_VALUE / 4;
            }

            @Override
            public long end() {
                return Long.MAX_VALUE / 4;
            }

            @Override
            public double get(long index) {
                return start + index * step;
            }

            @Override
            public long version() {
                return 0;
            }

            @Override
            public double uniformStart() {
                return start;
            }

            @Override
            public double uniformStep() {
                return step;
            }
        };
    }

    /**
     * {@code count} values computed by {@code getter} (indices 0 to count - 1), changing when {@code changes} does
     * (null: never).
     */
    public static Column of(int count, IntToDoubleFunction getter, Versioned changes) {
        return new Column() {
            @Override
            public long first() {
                return 0;
            }

            @Override
            public long end() {
                return count;
            }

            @Override
            public double get(long index) {
                return getter.applyAsDouble((int) index);
            }

            @Override
            public long version() {
                return changes == null ? 0 : changes.version();
            }
        };
    }

    /** A column over a plain {@code double[]}: the caller says when it changed. */
    public static final class Values extends Column {

        private double[] values;
        private int length;
        private long version = 1;

        Values(double[] values) {
            this.values = values;
            this.length = values.length;
        }

        /** The array (not a copy). */
        public double[] values() {
            return values;
        }

        /** Sets value {@code index} and marks the column changed. */
        public void set(int index, double value) {
            values[index] = value;
            version++;
        }

        /** Uses the first {@code count} values of {@code array} from now on; marks the column changed. */
        public void replace(double[] array, int count) {
            values = array;
            length = count;
            version++;
        }

        /** Marks the column changed (after writing its array). */
        public void changed() {
            version++;
        }

        @Override
        public long first() {
            return 0;
        }

        @Override
        public long end() {
            return length;
        }

        @Override
        public double get(long index) {
            return values[(int) index];
        }

        @Override
        public long version() {
            return version;
        }
    }

    /** A column over a plain {@code float[]}: the caller says when it changed. */
    public static final class FloatValues extends Column {

        private final float[] values;
        private long version = 1;

        FloatValues(float[] values) {
            this.values = values;
        }

        /** Sets value {@code index} and marks the column changed. */
        public void set(int index, float value) {
            values[index] = value;
            version++;
        }

        /** Marks the column changed (after writing its array). */
        public void changed() {
            version++;
        }

        @Override
        public long first() {
            return 0;
        }

        @Override
        public long end() {
            return values.length;
        }

        @Override
        public double get(long index) {
            return values[(int) index];
        }

        @Override
        public long version() {
            return version;
        }
    }
}
