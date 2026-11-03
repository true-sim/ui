package com.truesim.ui.style.value;

import java.util.Objects;

/** A grid track's size: fixed panel units, a fraction of the free space, sized to content, or between two sizes. */
public sealed interface Track {

    /** Sized to its content. */
    Track AUTO = new Auto();

    /** {@code px} panel units. */
    static Track px(float px) {
        return new Fixed(px);
    }

    /** {@code fr} shares of the free space. */
    static Track fr(float fr) {
        return new Fraction(fr);
    }

    /** At least {@code min}, at most {@code max}. */
    static Track minmax(Track min, Track max) {
        return new MinMax(min, max);
    }

    /** A fixed size. */
    record Fixed(float px) implements Track {
    }

    /** A share of the free space. */
    record Fraction(float fr) implements Track {
    }

    /** Sized to content. */
    record Auto() implements Track {
        @Override
        public String toString() {
            return "auto";
        }
    }

    /** Between two sizes. */
    record MinMax(Track min, Track max) implements Track {
        public MinMax {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
        }
    }
}
