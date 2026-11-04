package com.truesim.ui.core.bind;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The bindings of a UI, polled once a frame before anything reads the controls' models (the controls' {@code Ui} polls
 * its own at the start of each update, before its frame listeners), so an edit reaches the model and a model change
 * reaches the control in the frame it happened. Polling allocates nothing.
 */
public final class Bindings {

    private Binding<?, ?>[] bindings = new Binding<?, ?>[16];
    private int count;

    /** Binds {@code control} to {@code model} without conversion and adds the binding. */
    @AllowAllocation("bindings are made when a UI is built")
    public <T> Binding<T, T> bind(Bindable<T> control, Bindable<T> model) {
        return add(new Binding<>(model, control, Converter.identity()));
    }

    /** Binds {@code control} to {@code model} through {@code converter} and adds the binding. */
    @AllowAllocation("bindings are made when a UI is built")
    public <M, C> Binding<M, C> bind(Bindable<C> control, Bindable<M> model, Converter<M, C> converter) {
        return add(new Binding<>(model, control, converter));
    }

    /** Adds {@code binding}; returns it. */
    @AllowAllocation("bindings are made when a UI is built")
    public <M, C> Binding<M, C> add(Binding<M, C> binding) {
        if (count == bindings.length) {
            bindings = Arrays.copyOf(bindings, count * 2);
        }
        bindings[count++] = binding;
        return binding;
    }

    /** Removes {@code binding}; returns whether it was here. */
    public boolean remove(Binding<?, ?> binding) {
        for (int i = 0; i < count; i++) {
            if (bindings[i] == binding) {
                System.arraycopy(bindings, i + 1, bindings, i, count - i - 1);
                bindings[--count] = null;
                return true;
            }
        }
        return false;
    }

    /** How many bindings there are. */
    public int size() {
        return count;
    }

    /** How many bindings are broken now ({@link Binding#broken()}): a getter, converter or control threw. */
    public int broken() {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (bindings[i].broken() != null) {
                n++;
            }
        }
        return n;
    }

    /** Polls every binding; returns how many changed. */
    public int poll() {
        int changed = 0;
        for (int i = 0; i < count; i++) {
            if (bindings[i].poll()) {
                changed++;
            }
        }
        return changed;
    }
}
