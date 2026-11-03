package com.truesim.ui.style;

import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.value.Color;

/**
 * The values of every key for one combination of selector inputs, resolved once and shared by every element with
 * that combination: a flat slot array read by {@link StyleKey#index()}. Reading allocates nothing. A key no rule sets
 * reads as its default. Styles with live values are evaluated again in place when their tokens change; then
 * {@link #version()} grows, which tells elements to redraw.
 */
public final class ResolvedStyle {

    private final int handle;
    final Object[] values;
    final int[] liveKeys;
    final TokenExpr[] liveExpressions;
    final int[] tokenSlots;
    long version;

    ResolvedStyle(int handle, Object[] values, int[] liveKeys, TokenExpr[] liveExpressions, int[] tokenSlots,
        long version) {
        this.handle = handle;
        this.values = values;
        this.liveKeys = liveKeys;
        this.liveExpressions = liveExpressions;
        this.tokenSlots = tokenSlots;
        this.version = version;
    }

    /** {@code key}'s value. */
    @SuppressWarnings("unchecked")
    public <T> T get(StyleKey<T> key) {
        int i = key.index();
        Object v = i < values.length ? values[i] : null;
        return v == null ? key.defaultValue() : (T) v;
    }

    /** A number key's value, unboxed. */
    public float getFloat(StyleKey<Float> key) {
        return get(key);
    }

    /** An integer key's value, unboxed. */
    public int getInt(StyleKey<Integer> key) {
        return get(key);
    }

    /** A colour key's value as the draw list takes it (packed linear, {@code Rgba}). */
    public long rgba(StyleKey<Color> key) {
        return get(key).rgba();
    }

    /** Whether a rule or inline value sets {@code key} (else it reads as its default). */
    public boolean isSet(StyleKey<?> key) {
        int i = key.index();
        return i < values.length && values[i] != null;
    }

    /** Whether some value depends on live tokens. */
    public boolean isLive() {
        return liveKeys.length > 0;
    }

    /** The handle {@link StyleResolver#style(int)} takes. */
    public int handle() {
        return handle;
    }

    /** When it was resolved or last re-evaluated, on the store's clock. */
    public long version() {
        return version;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ResolvedStyle#").append(handle).append('{');
        boolean first = true;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                sb.append(first ? "" : ", ").append(StyleKeys.key(i).name()).append(": ").append(values[i]);
                first = false;
            }
        }
        return sb.append('}').toString();
    }
}
