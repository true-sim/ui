package com.truesim.ui.style;

import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.Selector.Combinator;
import com.truesim.ui.style.Selector.Compound;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The plain technique, for comparison and as a test oracle: no interning, no cache and no rule index; every element
 * scans every rule in priority order and matches selectors directly against the store, walking its ancestors.
 * Written apart from {@link StyleResolver} from the documented precedence, so agreeing with it means something.
 */
final class LinearResolver {

    private final UiStore store;
    private final List<Rule> rules;
    private Facts[] facts = new Facts[0];

    LinearResolver(UiStore store, StyleSheet sheet) {
        this.store = store;
        List<Rule> list = new ArrayList<>();
        for (Rule r : sheet.rules()) {
            if (!r.selector().hasPlaceholder()) {
                list.add(r);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            order.add(i);
        }
        // state rules first, then specificity, then more states, then the later rule
        order.sort(Comparator.comparing((Integer i) -> list.get(i).selector().stateCount() > 0)
            .thenComparingLong(i -> list.get(i).selector().specificity())
            .thenComparingInt(i -> list.get(i).selector().stateCount())
            .thenComparingInt(i -> i).reversed());
        rules = new ArrayList<>();
        for (int i : order) {
            rules.add(list.get(i));
        }
    }

    void facts(int slot, Facts f) {
        if (facts.length <= slot) {
            facts = java.util.Arrays.copyOf(facts, store.capacity());
        }
        facts[slot] = f;
    }

    private Facts factsOf(int slot) {
        return slot < facts.length && facts[slot] != null ? facts[slot] : Facts.NONE;
    }

    /** The value of every key for the element in {@code slot}, by key index (null: the default). */
    Object[] resolve(int slot) {
        Object[] values = new Object[StyleKeys.count()];
        boolean[] taken = new boolean[values.length];
        for (Rule r : rules) {
            if (matches(r.selector(), slot)) {
                for (Declaration d : r.declarations()) {
                    int i = d.key().index();
                    if (!taken[i]) {
                        taken[i] = true;
                        values[i] = d.value();
                    }
                }
            }
        }
        return values;
    }

    private boolean matches(Selector s, int slot) {
        int last = s.compounds().size() - 1;
        return matches(s.compounds().get(last), slot) && (last == 0 || ancestors(s, last - 1, slot));
    }

    private boolean ancestors(Selector s, int k, int slot) {
        Compound c = s.compounds().get(k);
        if (s.combinators().get(k) == Combinator.CHILD) {
            int p = store.parent(slot);
            return p != UiStore.NONE && matches(c, p) && (k == 0 || ancestors(s, k - 1, p));
        }
        for (int p = store.parent(slot); p != UiStore.NONE; p = store.parent(p)) {
            if (matches(c, p) && (k == 0 || ancestors(s, k - 1, p))) {
                return true;
            }
        }
        return false;
    }

    private String style(int slot) {
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (store.style(s) != null) {
                return store.style(s);
            }
        }
        return null;
    }

    private List<String> path(int slot) {
        List<String> out = new ArrayList<>();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            String id = store.id(s);
            if (id != null) {
                String[] parts = id.split("\\.");
                for (int i = parts.length - 1; i >= 0; i--) {
                    out.add(0, parts[i]);
                }
            }
        }
        return out;
    }

    private boolean matches(Compound c, int slot) {
        if (c.type() != null && !c.type().equals(store.type(slot))) {
            return false;
        }
        if (c.style() != null && !c.style().equals(style(slot))) {
            return false;
        }
        if ((store.state(slot) & c.states()) != c.states()) {
            return false;
        }
        if (!c.id().isEmpty()) {
            if (store.id(slot) == null) {
                return false;
            }
            List<String> path = path(slot);
            if (path.size() < c.id().size() || !path.subList(path.size() - c.id().size(), path.size())
                .equals(c.id())) {
                return false;
            }
        }
        for (Selector.Fact f : c.facts()) {
            if (!factsOf(slot).matches(f)) {
                return false;
            }
        }
        return true;
    }
}
