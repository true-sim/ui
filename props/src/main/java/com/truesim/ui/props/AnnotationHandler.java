package com.truesim.ui.props;

import com.truesim.ui.core.bind.Validator;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Gives a custom annotation meaning in property panels (design 5.11), registered with
 * {@link PropertyBuilder#handler}: style facts for its rows (so themes can select them), an editor by name, and a
 * validator. A property's annotation always adds the fact {@code annotation=<SimpleName>}; a handler adds more.
 *
 * @param <A> the annotation type
 */
public interface AnnotationHandler<A extends Annotation> {

    /** The annotation type handled. */
    Class<A> type();

    /** Adds name, value pairs of style facts for a row of {@code property} carrying {@code annotation}. */
    default void facts(A annotation, Property<?, ?> property, List<String> facts) {
        // none by default
    }

    /** The editor name for {@code property} (beaten by code and styles), or null. */
    default String editor(A annotation, Property<?, ?> property) {
        return null;
    }

    /** A validator of {@code property}'s values, or null. */
    default Validator<Object> validator(A annotation, Property<?, ?> property) {
        return null;
    }
}
