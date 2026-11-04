package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A selector and the declarations it applies, and where it was written ({@code source}: {@code file:line} for a
 * {@code .tss} rule, null for one built in Java; the UI debugger shows it beside each resolved value). A key declared
 * twice keeps its last value, in the last place, whether the rule was written in Java or in {@code .tss}, so both
 * forms give equal rules: the source is not part of a rule's equality.
 */
@AllowAllocation("rules are built when a sheet is built")
public record Rule(Selector selector, List<Declaration> declarations, String source) {

    public Rule {
        Objects.requireNonNull(selector, "selector");
        declarations = dedupe(declarations);
    }

    /** A rule without a source (built in Java). */
    public Rule(Selector selector, List<Declaration> declarations) {
        this(selector, declarations, null);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rule r && selector.equals(r.selector) && declarations.equals(r.declarations);
    }

    @Override
    public int hashCode() {
        return selector.hashCode() * 31 + declarations.hashCode();
    }

    static List<Declaration> dedupe(List<Declaration> declarations) {
        List<Declaration> out = new ArrayList<>(declarations.size());
        for (Declaration d : declarations) {
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).key() == d.key()) {
                    out.remove(i);
                    break;
                }
            }
            out.add(d);
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return selector + " " + declarations;
    }
}
