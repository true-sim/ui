package com.truesim.ui.props.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shows the property only while another property holds: a boolean is true, or its value's name or text equals {@link #is}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface ShowIf {
    /** The other property. */
    String value();

    /** The value it must have (its {@code toString} or enum name); empty: true, non-null and non-empty. */
    String is() default "";
}
