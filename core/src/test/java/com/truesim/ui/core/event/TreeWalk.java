package com.truesim.ui.core.event;

import com.truesim.ui.core.UiStore;

/**
 * The plain alternative to the hit grid, as a test oracle: walk the whole tree in draw order, test every pickable
 * element's shape and its clips, keep the hits front-most first.
 */
final class TreeWalk {

    private TreeWalk() {
    }

    /** Every hit under {@code root} at {@code x, y}, front-most first, into {@code out}; returns how many. */
    static int pick(Interaction ia, PickGeometry geo, int root, float x, float y, int[] out) {
        int[] all = new int[4096];
        int n = walk(ia, geo, root, x, y, all, 0, true);
        int k = 0;
        for (int i = n - 1; i >= 0 && k < out.length; i--) {
            out[k++] = all[i];
        }
        return k;
    }

    private static int walk(Interaction ia, PickGeometry geo, int slot, float x, float y, int[] all, int n,
        boolean inClip) {
        UiStore store = ia.store();
        if (!geo.shown(slot)) {
            return n;
        }
        boolean inside = Picking.inside(x, y, geo.x(slot), geo.y(slot), geo.width(slot), geo.height(slot),
            geo.radius(slot, 0), geo.radius(slot, 1), geo.radius(slot, 2), geo.radius(slot, 3));
        if (inClip && inside && ia.pickable(slot)) {
            all[n++] = slot;
        }
        boolean childClip = inClip && (!geo.clips(slot) || inside);
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            n = walk(ia, geo, c, x, y, all, n, childClip);
        }
        return n;
    }
}
