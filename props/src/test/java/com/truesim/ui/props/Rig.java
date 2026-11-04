package com.truesim.ui.props;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkTextBackend;

/** A UI with controls, synthetic input and a clock, driven one frame at a time (the props tests' fixture). */
final class Rig {

    static final StyleSheet DEFAULT = Tss.defaultTheme();
    static final StyleSheet GARISH = Tss.testTheme();
    private static TextSystem jdk;

    final Ui ui;
    final Controls c;
    final UiInput input = new UiInput(32);
    final SyntheticInput synthetic = new SyntheticInput();
    long now = 1_000_000_000L;
    float width = 1280;
    float height = 960;

    Rig() {
        this(DEFAULT, jdkText());
    }

    Rig(StyleSheet sheet) {
        this(sheet, jdkText());
    }

    Rig(StyleSheet sheet, TextSystem text) {
        ui = new Ui(sheet, text);
        c = new Controls(ui);
    }

    static synchronized TextSystem jdkText() {
        if (jdk == null) {
            JdkTextBackend backend = new JdkTextBackend();
            FontRegistry fonts = BundledCjkFonts.registry(backend);
            GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(),
                null, null, new AtlasSettings());
            jdk = new TextSystem(fonts, backend.newShaper(), atlas);
        }
        return jdk;
    }

    /** The real text path (allocation tests: the JDK shaper allocates). */
    static TextSystem nativeText() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null,
            null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    Rig add(Element e) {
        ui.root().add(e);
        return this;
    }

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

    Rig settle() {
        for (int i = 0; i < 300; i++) {
            run();
            if ((ui.textSystem() == null || ui.textSystem().atlas().pending() == 0) && ui.recorded() == 0
                && ui.animator().running() == 0) {
                return this;
            }
        }
        return this;
    }

    float cx(Element e) {
        Layout l = ui.layout();
        return l.x(e.slot()) + l.width(e.slot()) / 2;
    }

    float cy(Element e) {
        Layout l = ui.layout();
        return l.y(e.slot()) + l.height(e.slot()) / 2;
    }

    Rig mouse(float x, float y, int buttons) {
        synthetic.mouse(x, y, buttons);
        return run();
    }

    Rig click(Element e) {
        float x = cx(e);
        float y = cy(e);
        mouse(x, y, 0);
        mouse(x, y, Pointer.PRIMARY);
        return mouse(x, y, 0);
    }

    Rig press(UiCommand... commands) {
        for (UiCommand command : commands) {
            synthetic.press(command);
            run();
        }
        return this;
    }

    Rig type(String text) {
        synthetic.type(text);
        return run();
    }
}
