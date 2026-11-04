package com.truesim.ui.props.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validators of the value (each with a public no-argument constructor), checked before an edit is written.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Validate {
    /** The validators. */
    Class<? extends com.truesim.ui.core.bind.Validator<?>>[] value();
}
