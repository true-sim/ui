package com.truesim.ui.controls.basic;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Semantics;
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
import java.util.List;

/** A UI with controls, synthetic input and a clock, driven one real frame at a time. */
final class Fixture {

    static final StyleSheet DEFAULT = Tss.defaultTheme();
    static final StyleSheet GARISH = Tss.testTheme();
    private static TextSystem jdk;

    final Ui ui;
    final Controls c;
    final UiInput input = new UiInput(32);
    final SyntheticInput synthetic = new SyntheticInput();
    long now = 1_000_000_000L;
    float width = 1280;
    float height = 800;

    Fixture() {
        this(DEFAULT, jdkText());
    }

    Fixture(StyleSheet sheet, TextSystem text) {
        ui = new Ui(sheet, text);
        c = new Controls(ui);
        ControlsGallery.registerIcons(ui);
    }

    /** The JDK fallback over the bundled fonts (shared: building it loads fonts). */
    static synchronized TextSystem jdkText() {
        if (jdk == null) {
            jdk = newJdkText();
        }
        return jdk;
    }

    static TextSystem newJdkText() {
        JdkTextBackend backend = new JdkTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null,
            null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    /** The real text path (FreeType, HarfBuzz): what allocation tests need, the JDK shaper allocates. */
    static TextSystem nativeText() {
        com.truesim.ui.text.natives.NativeTextBackend backend = new com.truesim.ui.text.natives.NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null,
            null, new AtlasSettings());
        return new TextSystem(fonts, backend.newShaper(), atlas);
    }

    /** Adds {@code e} to the HUD's root. */
    Fixture add(Element e) {
        ui.root().add(e);
        return this;
    }

    /** One frame 16 ms after the last. */
    Fixture run() {
        input.beginFrame();
        synthetic.poll(input);
        ui.update(width, height, input, now);
        now += 16_000_000L;
        return this;
    }

    /** {@code n} frames. */
    Fixture run(int n) {
        for (int i = 0; i < n; i++) {
            run();
        }
        return this;
    }

    /** Frames until nothing records and every glyph is in the atlas. */
    Fixture settle() {
        for (int i = 0; i < 300; i++) {
            run();
            if ((ui.textSystem() == null || ui.textSystem().atlas().pending() == 0) && ui.recorded() == 0
                && ui.animator().running() == 0) {
                return this;
            }
        }
        return this;
    }

    /** Advances the clock by {@code seconds} in frames of 16 ms. */
    Fixture advance(float seconds) {
        int frames = (int) Math.ceil(seconds / 0.016f);
        return run(frames);
    }

    float cx(Element e) {
        Layout l = ui.layout();
        return l.x(e.slot()) + l.width(e.slot()) / 2;
    }

    float cy(Element e) {
        Layout l = ui.layout();
        return l.y(e.slot()) + l.height(e.slot()) / 2;
    }

    /** Moves the mouse to {@code x, y} with {@code buttons} and runs a frame. */
    Fixture mouse(float x, float y, int buttons) {
        synthetic.mouse(x, y, buttons);
        return run();
    }

    /** Clicks {@code e}'s centre. */
    Fixture click(Element e) {
        float x = cx(e);
        float y = cy(e);
        mouse(x, y, 0);
        mouse(x, y, Pointer.PRIMARY);
        return mouse(x, y, 0);
    }

    /** Hovers {@code e}'s centre. */
    Fixture hover(Element e) {
        return mouse(cx(e), cy(e), 0);
    }

    /** Sends {@code commands} from player 0 in one frame each. */
    Fixture press(UiCommand... commands) {
        for (UiCommand command : commands) {
            synthetic.press(command);
            run();
        }
        return this;
    }

    /** Types {@code text} in one frame. */
    Fixture type(String text) {
        synthetic.type(text);
        return run();
    }

    /** Focuses {@code e} as by keys (the focus ring shows). */
    Fixture focus(Element e) {
        ui.interaction().focus(e);
        return run();
    }

    /** The interactive semantics nodes a screen reader could not announce. */
    List<Semantics.Node> unnamed() {
        return Semantics.unnamed(Semantics.of(ui.store(), ui.interaction()));
    }

    /** The semantics tree as text. */
    String semantics() {
        return Semantics.of(ui.store(), ui.interaction()).toString();
    }
}
