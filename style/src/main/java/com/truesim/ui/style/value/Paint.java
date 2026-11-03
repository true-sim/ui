package com.truesim.ui.style.value;

import java.util.Objects;

/**
 * How an area is filled: a layer of an element's look ({@code background}). Immutable values with value equality.
 */
public sealed interface Paint {

    /** Nothing is drawn. */
    Paint NONE = new None();

    /** A box filled with one colour (the SDF box primitive takes radii, border and shadow from their own keys). */
    static Paint box(Color color) {
        return new Box(color);
    }

    /** A linear gradient from {@code from} to {@code to}, {@code angle} degrees (CSS: 180 is top to bottom). */
    static Paint gradient(Color from, Color to, float angle) {
        return new LinearGradient(from, to, angle);
    }

    /** An image (by asset name) tinted by {@code tint}. */
    static Paint image(String name, Color tint) {
        return new Image(name, tint);
    }

    /** A nine-slice image: corners {@code left, top, right, bottom} texels wide in the source, drawn at that size. */
    static Paint nineSlice(String image, int left, int top, int right, int bottom) {
        return new NineSlice(image, left, top, right, bottom, null, Color.WHITE);
    }

    /** No fill. */
    record None() implements Paint {
        @Override
        public String toString() {
            return "none";
        }
    }

    /** A solid box. */
    record Box(Color color) implements Paint {
        public Box {
            Objects.requireNonNull(color, "color");
        }
    }

    /** A two-stop linear gradient; {@code angle} in degrees, CSS convention (0 bottom to top, 90 left to right). */
    record LinearGradient(Color from, Color to, float angle) implements Paint {
        public LinearGradient {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** An image asset stretched over the area, multiplied by {@code tint}. */
    record Image(String name, Color tint) implements Paint {
        public Image {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(tint, "tint");
        }
    }

    /**
     * A nine-slice image: the source's border insets in texels ({@code left top right bottom}), the destination insets
     * in panel units (null: draw the corners at their texel size), and a tint.
     */
    record NineSlice(String image, int left, int top, int right, int bottom, Insets destination, Color tint)
        implements Paint {
        public NineSlice {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(tint, "tint");
        }
    }
}
