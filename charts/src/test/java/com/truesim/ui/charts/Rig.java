package com.truesim.ui.charts;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.draw.UiTextures;
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
import com.truesim.ui.text.jdk.JdkTextBackend;

/** A UI with synthetic input and a clock, driven one real frame at a time, for chart tests. */
final class Rig {

    static final StyleSheet DEFAULT = Tss.defaultTheme();
    static final StyleSheet GARISH = Tss.testTheme();
    private static TextSystem jdk;

    final Ui ui;
    final UiInput input = new UiInput(32);
    final SyntheticInput synthetic = new SyntheticInput();
    long now = 1_000_000_000L;
    float width = 800;
    float height = 500;

    Rig() {
        this(DEFAULT, jdkText());
    }

    Rig(StyleSheet sheet, TextSystem text) {
        ui = text == null ? new Ui(sheet, null) : new Ui(sheet, text, text.atlas() == null ? new UiTextures()
            : texturesOf(text));
    }

    private static UiTextures texturesOf(TextSystem text) {
        return TEXTURES.get(text);
    }

    private static final java.util.Map<TextSystem, UiTextures> TEXTURES = new java.util.IdentityHashMap<>();

    /** The JDK fallback over the bundled fonts (shared: building it loads fonts). */
    static synchronized TextSystem jdkText() {
        if (jdk == null) {
            JdkTextBackend backend = new JdkTextBackend();
            FontRegistry fonts = BundledCjkFonts.registry(backend);
            UiTextures textures = new UiTextures();
            GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
                new AtlasSettings());
            jdk = new TextSystem(fonts, backend.newShaper(), atlas);
            TEXTURES.put(jdk, textures);
        }
        return jdk;
    }

    /** The real text path (FreeType, HarfBuzz), new each time: what allocation tests and previews need. */
    static synchronized TextSystem nativeText() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        TextSystem t = new TextSystem(fonts, backend.newShaper(), atlas);
        TEXTURES.put(t, textures);
        return t;
    }

    /** Adds a chart to the root, filling the UI. */
    Chart chart(String name) {
        Chart c = new Chart(ui, name);
        ui.root().add(c.element());
        ui.styles().inline(c.element().slot(), com.truesim.ui.style.InlineStyle.builder()
            .set(com.truesim.ui.style.Styles.WIDTH, com.truesim.ui.style.value.Length.px(width))
            .set(com.truesim.ui.style.Styles.HEIGHT, com.truesim.ui.style.value.Length.px(height)).build());
        return c;
    }

    /** One frame 16 ms after the last. */
    Rig run() {
        input.beginFrame();
        synthetic.poll(input);
        ui.update(width, height, input, now);
        now += 16_000_000L;
        return this;
    }

    Rig run(int n) {
        for (int i = 0; i < n; i++) {
            run();
        }
        return this;
    }

    /** Frames until nothing records and every glyph is in the atlas. */
    Rig settle() {
        for (int i = 0; i < 300; i++) {
            run();
            if ((ui.textSystem() == null || ui.textSystem().atlas().pending() == 0) && ui.recorded() == 0) {
                return this;
            }
        }
        return this;
    }

    /** Moves the mouse to content-box pixel {@code x, y} of {@code chart} with {@code buttons}, one frame. */
    Rig mouse(Chart chart, float x, float y, int buttons) {
        synthetic.mouse(chart.contentX() + x, chart.contentY() + y, buttons);
        return run();
    }

    /** As {@link #mouse(Chart, float, float, int)} with modifier keys held. */
    Rig mouse(Chart chart, float x, float y, int buttons, int modifiers) {
        synthetic.modifiers(SyntheticInput.MOUSE, modifiers);
        synthetic.mouse(chart.contentX() + x, chart.contentY() + y, buttons);
        run();
        synthetic.modifiers(SyntheticInput.MOUSE, 0);
        return this;
    }

    /** Drags with {@code buttons} from {@code x0, y0} to {@code x1, y1} in {@code steps} frames, then releases. */
    Rig drag(Chart chart, float x0, float y0, float x1, float y1, int buttons, int modifiers, int steps) {
        synthetic.modifiers(SyntheticInput.MOUSE, modifiers);
        mouse(chart, x0, y0, 0);
        mouse(chart, x0, y0, buttons);
        for (int i = 1; i <= steps; i++) {
            mouse(chart, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, buttons);
        }
        mouse(chart, x1, y1, 0);
        synthetic.modifiers(SyntheticInput.MOUSE, 0);
        return this;
    }

    /** Scrolls the wheel {@code notches} over content-box pixel {@code x, y}. */
    Rig wheel(Chart chart, float x, float y, float notches, int modifiers) {
        synthetic.modifiers(SyntheticInput.MOUSE, modifiers);
        synthetic.mouse(chart.contentX() + x, chart.contentY() + y, 0);
        synthetic.scroll(SyntheticInput.MOUSE, 0, notches);
        run();
        synthetic.scroll(SyntheticInput.MOUSE, 0, 0);
        synthetic.modifiers(SyntheticInput.MOUSE, 0);
        return this;
    }

    /** Clicks at content-box pixel {@code x, y}. */
    Rig click(Chart chart, float x, float y) {
        mouse(chart, x, y, 0);
        mouse(chart, x, y, Pointer.PRIMARY);
        return mouse(chart, x, y, 0);
    }

    /** Sends {@code command} in one frame. */
    Rig press(UiCommand command) {
        synthetic.press(command);
        return run();
    }
}
