package com.truesim.ui.props.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A colour: a {@code long} draw-list colour ({@code Rgba}: linear half floats) or an {@code int} 8-bit sRGB
 * {@code 0xRRGGBBAA}, edited by a colour picker; {@link #hdr} for values above 1.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Color {
    /** Whether the colour may be brighter than white (fact {@code hdr=true}). */
    boolean hdr() default false;
}
