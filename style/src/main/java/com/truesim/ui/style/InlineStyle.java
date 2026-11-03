package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.TokenExpr;
import java.util.ArrayList;
import java.util.List;

/**
 * Values set on one element in code: they beat every rule (design 4.4: specificity, then pseudo-class rules, then
 * inline values). Value equality, so elements with equal inline values share one resolved style.
 */
@AllowAllocation("inline styles are built when an element is configured")
public record InlineStyle(List<Declaration> declarations) {

    /** No inline values. */
    public static final InlineStyle NONE = new InlineStyle(List.of());

    public InlineStyle {
        declarations = Rule.dedupe(declarations);
    }

    /** Inline values {@code declarations}. */
    public static InlineStyle of(Declaration... declarations) {
        return new InlineStyle(List.of(declarations));
    }

    /** A builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds an inline style. */
    @AllowAllocation("built when an element is configured")
    public static final class Builder {
        private final List<Declaration> declarations = new ArrayList<>();

        /** Sets {@code key} to {@code value}. */
        public <T> Builder set(StyleKey<T> key, T value) {
            declarations.add(Declaration.of(key, value));
            return this;
        }

        /** Sets the keys {@code shorthand} expands to. */
        public <T> Builder set(Shorthand<T> shorthand, T value) {
            declarations.addAll(shorthand.expand(value));
            return this;
        }

        /** Sets {@code key} to a live expression over tokens. */
        public Builder live(StyleKey<?> key, TokenExpr expression) {
            declarations.add(Declaration.live(key, expression));
            return this;
        }

        /** The inline style. */
        public InlineStyle build() {
            return new InlineStyle(declarations);
        }
    }
}
