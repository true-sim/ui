package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.ScreenSample;
import com.truesim.ui.core.input.ScreenSampler;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;

/**
 * Picks a colour off the screen ({@link #start}): while active, a full-screen {@code eyedropper} element on the layer
 * {@code eyedropper} (above tooltips) takes the pointer (its cursor is the theme's), and a magnifier
 * ({@code color-loupe}: {@code loupe-pixels} pixels across, each a square cell, the centre one outlined as its part
 * {@code color-loupe-center}'s border) follows it at {@code loupe-gap}, showing the pixels around it. A click (or
 * {@code ACCEPT}) picks the pixel under the pointer; {@code CANCEL} or the secondary button gives up.
 *
 * <p>The pixels come from a {@link ScreenSampler}: asked every frame for the block under the pointer, its newest answer
 * shown, so nothing waits for the read-back. A click picks the answer for the clicked pixel: at once when the newest
 * one is for that point (the pointer rested), else when it arrives.
 */
public final class Eyedropper implements UiListener, Controls.Polled, Painter {

    /** What an eyedropper hands its owner. */
    @FunctionalInterface
    public interface Listener {
        /** The colour picked: linear, HDR intensities kept, alpha 1. */
        void picked(long color);

        /** The eyedropper closed without a pick. */
        default void cancelled() {
            // nothing by default
        }
    }

    private static final String LAYER = "eyedropper";
    private static final int LAYER_Z = 450;

    private final Controls controls;
    private final Ui ui;
    private final Listener listener;
    private final ScreenSample sample = new ScreenSample();
    private Element catcher;
    private Element loupe;
    private Element center;
    private boolean active;
    private boolean committing;
    private float x;
    private float y;
    private float commitX;
    private float commitY;
    private int player;
    private Element restore;
    private int requests;

    /** An eyedropper of {@code controls} (it reads {@link Controls#screenSampler()}) telling {@code listener}. */
    @AllowAllocation("controls are built once")
    public Eyedropper(Controls controls, Listener listener) {
        this.controls = controls;
        this.ui = controls.ui();
        this.listener = listener;
        controls.poll(this);
    }

    @AllowAllocation("made the first time it is used")
    private void build() {
        Layer layer = ui.layerStack().find(LAYER);
        if (layer == null) {
            layer = ui.layerStack().add(LAYER, LAYER_Z);
        }
        layer.input(Layer.Input.BLOCK);
        ui.layer(LAYER);
        catcher = ui.element("eyedropper", null);
        catcher.semantics(Role.GROUP, "Pick a colour");
        loupe = ui.element("color-loupe", null);
        center = ui.part(loupe, "color-loupe-center", null);
        ui.paint(loupe, this);
        catcher.add(loupe);
        ui.interaction().focusable(catcher, true);
        ui.interaction().on(catcher, EventType.MOVE.bit() | EventType.PRESS.bit() | EventType.COMMAND.bit()
            | EventType.CLICK.bit() | EventType.CONTEXT.bit(), this);
    }

    /** Whether it is picking now. */
    public boolean active() {
        return active;
    }

    /** The full-screen element taking the pointer while active (null before the first use). */
    public Element catcher() {
        return catcher;
    }

    /** The magnifier (null before the first use). */
    public Element loupe() {
        return loupe;
    }

    /** The newest pixels read. */
    public ScreenSample sample() {
        return sample;
    }

    /** Requests made of the sampler so far. */
    public int requests() {
        return requests;
    }

    /**
     * Starts picking for {@code fromPlayer}, the magnifier at the pointer; false (nothing happens) without a screen
     * sampler.
     */
    public boolean start(int fromPlayer) {
        if (controls.screenSampler() == null || active) {
            return false;
        }
        if (catcher == null) {
            build();
        }
        active = true;
        committing = false;
        player = fromPlayer;
        UiInput input = ui.input();
        if (input != null && input.pointerCount() > 0) {
            Pointer p = input.pointerAt(0);
            x = p.x();
            y = p.y();
        }
        restore = ui.interaction().focused(fromPlayer);
        ui.layer(LAYER).root().add(catcher);
        place();
        controls.focusSoon(catcher, fromPlayer);
        request();
        return true;
    }

    /** Moves the magnifier to UI pixel {@code px, py}, as the pointer moving there does. */
    public void moveTo(float px, float py) {
        if (active && !committing) {
            x = px;
            y = py;
            place();
        }
    }

    /** Gives up picking. */
    public void cancel() {
        if (!active) {
            return;
        }
        end();
        listener.cancelled();
    }

    /** Picks the pixel at UI pixel {@code px, py} (as a click there does). */
    public void pick(float px, float py) {
        if (!active) {
            return;
        }
        x = px;
        y = py;
        commitX = px;
        commitY = py;
        committing = true;
        if (sample.size() > 0 && sample.x() == px && sample.y() == py) {
            commit();
        } else {
            request();
        }
    }

    private void commit() {
        long c = sample.center();
        end();
        listener.picked(c);
    }

    private void end() {
        active = false;
        committing = false;
        catcher.detach();
        ScreenSampler s = controls.screenSampler();
        if (s != null) {
            s.stop();
        }
        if (restore != null && restore.alive()) {
            ui.interaction().focus(restore, player);
        }
        restore = null;
    }

    private void request() {
        ScreenSampler s = controls.screenSampler();
        if (s == null) {
            return;
        }
        int size = (int) ui.styles().styleOf(loupe.slot()).getFloat(BasicStyles.LOUPE_PIXELS) | 1;
        float px = committing ? commitX : x;
        float py = committing ? commitY : y;
        if (s.request(px, py, size)) {
            requests++;
        }
    }

    /** Puts the magnifier beside the pointer: below right, flipped where the screen ends. */
    private void place() {
        Layout l = ui.layout();
        int s = loupe.slot();
        float scale = l.scale() > 0 ? l.scale() : 1;
        float w = l.shown(s) ? l.width(s) : 0;
        float h = l.shown(s) ? l.height(s) : 0;
        int r = ui.store().rootSlot();
        float sw = l.width(r);
        float sh = l.height(r);
        float gap = ui.styles().styleOf(s).getFloat(BasicStyles.LOUPE_GAP) * scale;
        float px = x + gap;
        float py = y + gap;
        if (px + w > sw && x - gap - w >= 0) {
            px = x - gap - w;
        }
        if (py + h > sh && y - gap - h >= 0) {
            py = y - gap - h;
        }
        ui.visuals().offsets(s, px / scale, py / scale, 0, 0);
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void poll(long nanos) {
        if (!active) {
            return;
        }
        ScreenSampler s = controls.screenSampler();
        if (s == null) {
            cancel();
            return;
        }
        if (s.poll(sample)) {
            ui.repaint(loupe);
        }
        if (committing && sample.size() > 0 && sample.x() == commitX && sample.y() == commitY) {
            commit();
            return;
        }
        request();
    }

    @Override
    public void handle(UiEvent e) {
        if (!active) {
            return;
        }
        switch (e.type()) {
            case MOVE -> moveTo(e.x(), e.y());
            case PRESS -> {
                e.consume();
                if (e.button() == Pointer.SECONDARY) {
                    cancel();
                } else if (e.button() == Pointer.PRIMARY) {
                    pick(e.x(), e.y());
                }
            }
            case CLICK, CONTEXT -> e.consume();
            case COMMAND -> {
                if (e.command() == UiCommand.CANCEL) {
                    e.consume();
                    cancel();
                } else if (e.command() == UiCommand.ACCEPT) {
                    e.consume();
                    pick(x, y);
                }
            }
            default -> {
                // nothing else
            }
        }
    }

    // ---------------------------------------------------------------- the magnifier

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        return Measure.size(widthMode == Measure.UNDEFINED ? 0 : width, heightMode == Measure.UNDEFINED ? 0 : height);
    }

    @Override
    public int spare(int recorded) {
        return ScreenSampler.MAX_SIZE * ScreenSampler.MAX_SIZE + 8;
    }

    @Override
    public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        int n = sample.size();
        if (n == 0) {
            return;
        }
        float cw = (x1 - x0) / n;
        float ch = (y1 - y0) / n;
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                long c = sample.color(col, row);
                list.box(x0 + col * cw, y0 + row * ch, x0 + (col + 1) * cw, y0 + (row + 1) * ch,
                    Rgba.fade(c, opacity), 0, 0, 0, 0, 0, 0);
            }
        }
        ResolvedStyle cs = ui.styles().styleOf(center.slot());
        float bw = cs.get(Styles.BORDER).width() * (ui.layout().scale() > 0 ? ui.layout().scale() : 1);
        if (bw > 0) {
            int m = n / 2;
            list.box(x0 + m * cw - bw, y0 + m * ch - bw, x0 + (m + 1) * cw + bw, y0 + (m + 1) * ch + bw,
                Rgba.TRANSPARENT, 0, 0, 0, 0, bw, Rgba.fade(cs.get(Styles.BORDER).color().rgba(), opacity));
        }
    }
}
