package com.truesim.ui.testkit;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.Picking;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Radii;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks that the pointer hits what is drawn on top: at points sampled over the screen, the hit test's front-most
 * element ({@link Picking#pickTop}) is the element a plain walk finds drawn on top there, layers front to back, each
 * layer's tree in reverse drawing order (later siblings over earlier ones, children over parents), counting only
 * shown, pickable elements, and only where they are not clipped away by a clipping ancestor (its rounded shape
 * included). The walk shares nothing with the hit test's grid, so a grid that is stale, coarsened wrongly or ignores
 * a clip fails it. Animated transform offsets (a press's scale, a sliding page) are not modelled: the walk reads the
 * layout as the hit test does.
 */
@AllowAllocation("test helper")
public final class PickingSanity {

    /** The distance between sampled points, in UI pixels. */
    public static final float STEP = 9;

    private final Ui ui;
    private final UiStore store;
    private final Layout layout;

    private PickingSanity(Ui ui) {
        this.ui = ui;
        this.store = ui.store();
        this.layout = ui.layout();
    }

    /** Where the hit test and the drawn order disagree in {@code ui} ({@code width x height} UI pixels). */
    public static List<String> check(Ui ui, float width, float height) {
        PickingSanity s = new PickingSanity(ui);
        List<String> out = new ArrayList<>();
        Picking picking = ui.interaction().picking();
        for (float y = STEP / 2; y < height; y += STEP) {
            for (float x = STEP / 2; x < width; x += STEP) {
                int drawn = s.topmost(x, y);
                int hit = picking.pickTop(x, y);
                if (drawn != hit) {
                    out.add(String.format(Locale.ROOT, "at (%.1f, %.1f): hit %s, drawn on top %s", x, y,
                        s.name(hit), s.name(drawn)));
                }
            }
        }
        return out;
    }

    /** Fails listing the first disagreements, if any. */
    public static void assertPicksWhatIsDrawn(Ui ui, float width, float height, String what) {
        report(what, check(ui, width, height));
    }

    /** Fails listing the first of {@code v}, if any. */
    static void report(String what, List<String> v) {
        if (!v.isEmpty()) {
            StringBuilder b = new StringBuilder(what).append(": the hit test disagrees with what is drawn at ")
                .append(v.size()).append(" points");
            for (int i = 0; i < Math.min(12, v.size()); i++) {
                b.append("\n  ").append(v.get(i));
            }
            throw new AssertionError(b.toString());
        }
    }

    private String name(int slot) {
        return slot == UiStore.NONE ? "nothing" : String.valueOf(store.element(slot));
    }

    /** The front-most pickable element drawn at {@code x, y}, or NONE. */
    private int topmost(float x, float y) {
        var layers = ui.layerStack();
        for (int li = layers.count() - 1; li >= 0; li--) {
            Layer layer = layers.at(li);
            if (layer.input() == Layer.Input.NONE) {
                continue;
            }
            int found = walk(layer.rootSlot(), x, y);
            if (found != UiStore.NONE || layer.blocking()) {
                return found;
            }
        }
        return UiStore.NONE;
    }

    /** The front-most pickable element of {@code slot}'s subtree at the point, inside every clip on the way. */
    private int walk(int slot, float x, float y) {
        if (!layout.shown(slot)) {
            return UiStore.NONE;
        }
        boolean inside = within(slot, x, y);
        if (clips(slot) && !inside) {
            return UiStore.NONE; // clipped away: nothing below shows here
        }
        int[] children = children(slot);
        for (int i = children.length - 1; i >= 0; i--) {
            int found = walk(children[i], x, y);
            if (found != UiStore.NONE) {
                return found;
            }
        }
        return inside && ui.interaction().pickable(slot) ? slot : UiStore.NONE;
    }

    private int[] children(int slot) {
        int n = 0;
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            n++;
        }
        int[] out = new int[n];
        int i = 0;
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            out[i++] = c;
        }
        return out;
    }

    private boolean clips(int slot) {
        return ui.styles().styleOf(slot).get(Styles.CLIP);
    }

    /** Whether the point is inside {@code slot}'s rounded box. */
    private boolean within(int slot, float x, float y) {
        Radii r = ui.styles().styleOf(slot).get(Styles.RADIUS);
        return Picking.inside(x, y, layout.x(slot), layout.y(slot), layout.width(slot), layout.height(slot),
            r.topLeft(), r.topRight(), r.bottomRight(), r.bottomLeft());
    }
}
