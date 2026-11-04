package com.truesim.ui.props.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The collection editor kind (styles override it with {@code collection}): one of the constants here or a registered kind's name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface ListStyle {
    /** Each item a row of a table, a column per property. */
    String TABLE = "table";
    /** Each item a collapsible panel. */
    String FOLDOUTS = "foldouts";
    /** A list and the selected item's panel. */
    String MASTER_DETAIL = "master-detail";
    /** Tags. */
    String CHIPS = "chips";
    /** A summary and a pop-up editor. */
    String COMPACT = "compact";
    /** Key and value columns. */
    String MAP = "map";

    /** The kind. */
    String value();
}
