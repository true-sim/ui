package com.truesim.ui.style.value;

/**
 * A size as layout reads it: a number in a {@link Unit} ({@code 12px}, {@code 1.5em}, {@code 50%}) or {@link #AUTO}.
 * Units are kept, not converted, because em and percent depend on the element's font and parent at layout time.
 */
public record Length(float value, Unit unit) {

    /** A length's unit. */
    public enum Unit {
        /** Panel units (scaled by {@code ui.scale}). */
        PX,
        /** Multiples of the element's font size. */
        EM,
        /** Percent of the parent's size along the same axis. */
        PERCENT,
        /** Decided by the layout (content size, stretch). */
        AUTO
    }

    /** Decided by the layout. */
    public static final Length AUTO = new Length(0, Unit.AUTO);
    /** Zero. */
    public static final Length ZERO = new Length(0, Unit.PX);

    /** {@code value} panel units. */
    public static Length px(float value) {
        return new Length(value, Unit.PX);
    }

    /** {@code value} times the font size. */
    public static Length em(float value) {
        return new Length(value, Unit.EM);
    }

    /** {@code value} percent of the parent. */
    public static Length percent(float value) {
        return new Length(value, Unit.PERCENT);
    }

    /** Whether this is {@link #AUTO}. */
    public boolean isAuto() {
        return unit == Unit.AUTO;
    }

    @Override
    public String toString() {
        return switch (unit) {
            case PX -> value + "px";
            case EM -> value + "em";
            case PERCENT -> value + "%";
            case AUTO -> "auto";
        };
    }
}
