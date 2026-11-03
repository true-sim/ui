package com.truesim.ui.controls.windows;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.core.draw.UiTextures;

/** A UI with overlays, its synthetic input and a clock, one frame at a time (60 frames a second). */
final class Fx {

    static final long FRAME = 16_666_667L;
    static StyleSheet defaultTheme;
    static StyleSheet testTheme;

    final Ui ui;
    final Overlays overlays;
    final UiInput input = new UiInput(32);
    final SyntheticInput in = new SyntheticInput();
    long now = 1_000_000_000L;
    float width = 1280;
    float height = 720;
    float mouseX;
    float mouseY;
    int buttons;

    Fx() {
        this(theme(), true);
    }

    Fx(StyleSheet sheet, boolean text) {
        ui = new Ui(sheet, text ? nativeText() : null);
        overlays = new Overlays(ui);
    }

    static synchronized StyleSheet theme() {
        if (defaultTheme == null) {
            defaultTheme = Tss.defaultTheme();
        }
        return defaultTheme;
    }

    static synchronized StyleSheet garish() {
        if (testTheme == null) {
            testTheme = Tss.testTheme();
        }
        return testTheme;
    }

    /** The real text path over the bundled fonts. */
    static TextSystem nativeText() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null,
            null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    Fx run() {
        input.beginFrame();
        in.poll(input);
        ui.update(width, height, input, now);
        now += FRAME;
        return this;
    }

    Fx frames(int n) {
        for (int i = 0; i < n; i++) {
            run();
        }
        return this;
    }

    /** Runs frames for {@code seconds} of the UI clock. */
    Fx idle(double seconds) {
        long until = now + (long) (seconds * 1e9);
        while (now < until) {
            run();
        }
        return this;
    }

    Fx mouse(float x, float y) {
        return mouse(x, y, buttons);
    }

    Fx mouse(float x, float y, int pressed) {
        mouseX = x;
        mouseY = y;
        buttons = pressed;
        in.mouse(x, y, pressed);
        return run();
    }

    Fx press(float x, float y) {
        return mouse(x, y, Pointer.PRIMARY);
    }

    Fx release() {
        return mouse(mouseX, mouseY, 0);
    }

    Fx click(float x, float y) {
        return press(x, y).release();
    }

    /** Presses at {@code x0, y0}, drags in steps to {@code x1, y1} and releases there. */
    Fx drag(float x0, float y0, float x1, float y1) {
        dragTo(x0, y0, x1, y1);
        return release();
    }

    /** Presses at {@code x0, y0} and drags in steps to {@code x1, y1}, still holding. */
    Fx dragTo(float x0, float y0, float x1, float y1) {
        press(x0, y0);
        for (int i = 1; i <= 4; i++) {
            mouse(x0 + (x1 - x0) * i / 4, y0 + (y1 - y0) * i / 4, Pointer.PRIMARY);
        }
        return this;
    }

    Fx command(UiCommand... commands) {
        in.press(commands);
        return run();
    }

    float x(Element e) {
        return ui.layout().x(e.slot());
    }

    float y(Element e) {
        return ui.layout().y(e.slot());
    }

    float w(Element e) {
        return ui.layout().width(e.slot());
    }

    float h(Element e) {
        return ui.layout().height(e.slot());
    }

    float cx(Element e) {
        return x(e) + w(e) / 2;
    }

    float cy(Element e) {
        return y(e) + h(e) / 2;
    }

    /** A fixed-size panel at {@code x, y} on the HUD (the root lays out as {@code none} here). */
    Element box(float x, float y, float w, float h) {
        Element e = ui.panel("card");
        ui.root().add(e);
        ui.layout().kind(ui.store().rootSlot(), com.truesim.ui.layout.LayoutKind.NONE);
        ui.addFrameHook(new Pin(e, x, y, w, h));
        return e;
    }

    /** Puts {@code e} on the HUD at {@code x, y}, {@code w x h}, kept there. */
    Element pin(Element e, float x, float y, float w, float h) {
        ui.root().add(e);
        ui.addFrameHook(new Pin(e, x, y, w, h));
        return e;
    }

    /** Keeps an element at a place set in code (restyles reset it). */
    private record Pin(Element e, float x, float y, float w, float h) implements com.truesim.ui.controls.UiFrameHook {
        @Override
        public void beforeLayout(Ui ui) {
            ui.layout().kind(ui.store().rootSlot(), com.truesim.ui.layout.LayoutKind.NONE);
            ui.layout().offsets(e.slot(), x, y, 0, 0);
            ui.layout().width(e.slot(), w, com.truesim.ui.layout.Unit.PX);
            ui.layout().height(e.slot(), h, com.truesim.ui.layout.Unit.PX);
        }
    }
}
