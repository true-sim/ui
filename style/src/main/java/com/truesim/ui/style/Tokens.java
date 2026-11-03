package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.Versioned;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.ValueException;
import com.truesim.ui.style.value.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The live token table: {@code --name} tokens (and {@code $name !live} variables) that survive compilation as slots,
 * so a palette swap is one change at run time (design 4.4). A token's value is, in order: a value {@link #set} in
 * code, else the active token set's ({@link #use}: light, dark, high contrast), else the sheet's default. A token may
 * itself depend on other tokens ({@code --accent-hover: lighten(var(--accent), 8%)}).
 *
 * <p>Every change bumps {@link #version()} and stamps only the slots whose value really changed
 * ({@link #changed(int)}), so a {@link StyleResolver} re-resolves only the styles depending on them. Changing tokens
 * is an event (a theme switch, a settings change), never per-frame work. Not thread-safe: the UI thread.
 */
@AllowAllocation("tokens change on theme switches and settings changes, never per frame")
public final class Tokens implements Versioned, TokenExpr.Source {

    private final Map<String, Integer> slots = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private Value[] values = new Value[0];
    private long[] changed = new long[0];
    private Map<String, Value> defaults = Map.of();
    private Map<String, Map<String, Value>> sets = Map.of();
    private final Map<String, Value> overrides = new LinkedHashMap<>();
    private String active;
    private long version;

    /** An empty table: {@link #load} a sheet's tokens. */
    public Tokens() {
    }

    /** A table with {@code sheet}'s tokens. */
    public static Tokens of(StyleSheet sheet) {
        Tokens tokens = new Tokens();
        tokens.load(sheet);
        return tokens;
    }

    /**
     * Takes {@code sheet}'s token defaults and sets (a new or reloaded sheet). Values set in code and the active set
     * stay, if the sheet still has that set.
     */
    public void load(StyleSheet sheet) {
        Map<String, Value> oldDefaults = defaults;
        Map<String, Map<String, Value>> oldSets = sets;
        String oldActive = active;
        defaults = sheet.tokens();
        sets = sheet.tokenSets();
        if (active != null && !sets.containsKey(active)) {
            active = null;
        }
        try {
            recompute();
        } catch (ValueException e) {
            defaults = oldDefaults;
            sets = oldSets;
            active = oldActive;
            throw e;
        }
    }

    /**
     * Sets token {@code name} in code: it overrides the active set and the default until {@link #reset}.
     *
     * @throws ValueException if a token depending on it cannot be evaluated with the new value; nothing changes
     */
    public void set(String name, Value value) {
        Value old = overrides.put(name, value);
        try {
            recompute();
        } catch (ValueException e) {
            if (old == null) {
                overrides.remove(name);
            } else {
                overrides.put(name, old);
            }
            throw e;
        }
    }

    /** Sets colour token {@code name} in code. */
    public void set(String name, Color value) {
        set(name, Value.color(value));
    }

    /** Drops the value set in code for {@code name}: the set's or the default value applies again. */
    public void reset(String name) {
        if (overrides.remove(name) != null) {
            recompute();
        }
    }

    /**
     * Switches to token set {@code set} (null: the defaults only).
     *
     * @throws IllegalArgumentException if the sheet has no such set
     */
    public void use(String set) {
        if (set != null && !sets.containsKey(set)) {
            String close = StyleKeys.closest(set, sets.keySet());
            throw new IllegalArgumentException("no token set " + set + (close == null ? "" : " (did you mean "
                + close + "?)"));
        }
        if (!java.util.Objects.equals(set, active)) {
            active = set;
            recompute();
        }
    }

    /** The active token set, or null. */
    public String active() {
        return active;
    }

    /** The token set names. */
    public Set<String> setNames() {
        return sets.keySet();
    }

    /** The slot of token {@code name}, or -1. */
    public int slot(String name) {
        Integer slot = slots.get(name);
        return slot == null ? -1 : slot;
    }

    /** The token names, by slot. */
    public List<String> names() {
        return List.copyOf(names);
    }

    /** The current value in {@code slot}. */
    public Value value(int slot) {
        return values[slot];
    }

    /** When {@code slot}'s value last changed, on this table's {@link #version()} scale. */
    public long changed(int slot) {
        return changed[slot];
    }

    /** How many slots there are. */
    public int count() {
        return names.size();
    }

    @Override
    public Value token(String name) {
        int slot = slot(name);
        if (slot < 0) {
            throw unknown(name);
        }
        return values[slot];
    }

    private ValueException unknown(String name) {
        String close = StyleKeys.closest(name, names);
        return new ValueException("unknown token --" + name + (close == null ? "" : " (did you mean --" + close
            + "?)"));
    }

    @Override
    public long version() {
        return version;
    }

    private void recompute() {
        Set<String> all = new LinkedHashSet<>(defaults.keySet());
        for (Map<String, Value> set : sets.values()) {
            all.addAll(set.keySet());
        }
        all.addAll(overrides.keySet());
        int known = names.size();
        for (String name : all) {
            if (!slots.containsKey(name)) {
                slots.put(name, names.size());
                names.add(name);
            }
        }
        try {
            evaluate();
        } catch (ValueException e) {
            while (names.size() > known) {
                slots.remove(names.remove(names.size() - 1));
            }
            throw e;
        }
    }

    private void evaluate() {
        int n = names.size();
        Value[] raw = new Value[n];
        Map<String, Value> activeSet = active == null ? Map.of() : sets.get(active);
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            Value v = overrides.get(name);
            if (v == null) {
                v = activeSet.get(name);
            }
            if (v == null) {
                v = defaults.get(name);
            }
            raw[i] = v == null ? Value.NULL : v;
        }
        Value[] next = new Evaluation(raw).run();
        long[] stamps = Arrays.copyOf(changed, n);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (i >= values.length || !next[i].equals(values[i])) {
                if (!any) {
                    version++;
                    any = true;
                }
                stamps[i] = version;
            }
        }
        values = next;
        changed = stamps;
    }

    /** Evaluates tokens that depend on tokens, in dependency order, failing on cycles. */
    private final class Evaluation implements TokenExpr.Source {
        private final Value[] raw;
        private final Value[] done;
        private final boolean[] busy;

        Evaluation(Value[] raw) {
            this.raw = raw;
            this.done = new Value[raw.length];
            this.busy = new boolean[raw.length];
        }

        Value[] run() {
            for (int i = 0; i < raw.length; i++) {
                evaluate(i);
            }
            return done;
        }

        private Value evaluate(int i) {
            if (done[i] != null) {
                return done[i];
            }
            if (busy[i]) {
                throw new ValueException("token --" + names.get(i) + " depends on itself");
            }
            busy[i] = true;
            Value v = raw[i];
            if (v instanceof Value.Live live) {
                try {
                    v = live.expression().evaluate(this);
                } catch (ValueException e) {
                    if (e.getMessage().startsWith("token --")) {
                        throw e;
                    }
                    throw new ValueException("token --" + names.get(i) + ": " + e.getMessage());
                }
            }
            done[i] = v;
            busy[i] = false;
            return v;
        }

        @Override
        public Value token(String name) {
            int slot = slot(name);
            if (slot < 0) {
                throw unknown(name);
            }
            return evaluate(slot);
        }
    }
}
