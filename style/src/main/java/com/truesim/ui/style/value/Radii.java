package com.truesim.ui.style.value;

/** Corner radii in panel units, in CSS order: top left, top right, bottom right, bottom left. */
public record Radii(float topLeft, float topRight, float bottomRight, float bottomLeft) {

    /** Square corners. */
    public static final Radii NONE = new Radii(0, 0, 0, 0);

    /** The same radius at every corner. */
    public static Radii of(float all) {
        return new Radii(all, all, all, all);
    }

    /** Each corner. */
    public static Radii of(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        return new Radii(topLeft, topRight, bottomRight, bottomLeft);
    }
}
