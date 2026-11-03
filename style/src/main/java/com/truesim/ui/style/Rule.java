package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A selector and the declarations it applies. A key declared twice keeps its last value, in the last place, whether
 * the rule was written in Java or in {@code .tss}, so both forms give equal rules.
 */
@AllowAllocation("rules are built when a sheet is built")
public record Rule(Selector selector, List<Declaration> declarations) {

    public Rule {
        Objects.requireNonNull(selector, "selector");
        declarations = dedupe(declarations);
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
