package com.truesim.ui.props.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A reference to an asset of a kind ({@code "texture"}): an asset field (fact {@code asset=texture}); the engine registers an asset picker as editor {@code asset}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface AssetType {
    /** The asset kind. */
    String value();
}
