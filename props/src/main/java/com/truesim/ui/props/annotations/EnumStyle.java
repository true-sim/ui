package com.truesim.ui.props.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How an enum is chosen: a drop-down (the default), radio buttons or a segmented control.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface EnumStyle {
    /** The style. */
    Kind value();

    /** The styles. */
    enum Kind {
        /** A drop-down list. */
        DROPDOWN,
        /** Radio buttons. */
        RADIO,
        /** A segmented control. */
        SEGMENTED
    }
}
