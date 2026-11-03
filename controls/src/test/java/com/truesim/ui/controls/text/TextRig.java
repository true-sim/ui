package com.truesim.ui.controls.text;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.Clipboard;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.util.ArrayList;
import java.util.List;

/**
 * A UI with the real text path (FreeType, HarfBuzz) over the bundled fonts and emoji, the default theme (or another
 * sheet), synthetic input, a clipboard and a recording text input host, one frame at a time.
 */
final class TextRig {

    static final NativeTextBackend BACKEND = new NativeTextBackend();
    static final FontRegistry FONTS = fonts();

    private static FontRegistry fonts() {
        FontRegistry f = BundledCjkFonts.registry(BACKEND);
        BundledEmojiFonts.register(f);
        return f;
    }

    /** A text system of its own (its atlas), over the shared fonts. */
    static TextSystem text() {
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), BACKEND.msdfGenerator(), BACKEND.newRasterizer(), null,
            null, new AtlasSettings());
        return new TextSystem(FONTS, BACKEND.newShaper(), atlas);
    }

    /** Records what the platform's text input is asked. */
    static final class Host implements TextInputHost {
        final List<String> calls = new ArrayList<>();
        int kind = -1;
        float x;
        float y;

        @Override
        public void start(float ax, float ay, float w, float h) {
            start(ax, ay, w, h, TEXT);
        }

        @Override
        public void start(float ax, float ay, float w, float h, int k) {
            kind = k;
            x = ax;
            y = ay;
            calls.add("start " + k);
        }

        @Override
        public void area(float ax, float ay, float w, float h) {
            x = ax;
            y = ay;
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    final Ui ui;
    final UiInput input = new UiInput(64);
    final SyntheticInput synthetic = new SyntheticInput();
    final Clipboard clipboard = Clipboard.inMemory();
    final Host host = new Host();
    long now = 1_000_000_000L;
    float width = 800;
    float height = 600;

    TextRig() {
        this(Tss.defaultTheme());
    }

    TextRig(StyleSheet sheet) {
        ui = new Ui(sheet, text());
        ui.interaction().clipboard(clipboard);
        ui.interaction().textInputHost(host);
    }

    /** Adds {@code e} under the root. */
    TextRig add(Element e) {
        ui.root().add(e);
        return this;
    }

    /** One frame, 16 ms after the last. */
    TextRig frame() {
        input.beginFrame();
        synthetic.poll(input);
        ui.update(width, height, input, now);
        now += 16_000_000L;
        return this;
    }

    /** Frames until the atlas has every glyph (their quads have size). */
    TextRig settle() {
        for (int i = 0; i < 20; i++) {
            frame();
        }
        return this;
    }

    TextRig press(UiCommand... commands) {
        synthetic.press(commands);
        return frame();
    }

    TextRig extend(UiCommand command) {
        synthetic.command(command, 0, UiInput.EXTEND_SELECTION);
        return frame();
    }

    TextRig type(String text) {
        synthetic.type(text);
        return frame();
    }

    TextRig compose(String text, int cursor) {
        synthetic.compose(text, cursor);
        return frame();
    }

    /** A click (press and release) at {@code (x, y)}. */
    TextRig click(float x, float y) {
        synthetic.mouse(x, y, Pointer.PRIMARY);
        frame();
        synthetic.mouse(x, y, 0);
        return frame();
    }

    TextRig mouse(float x, float y, int buttons) {
        synthetic.mouse(x, y, buttons);
        return frame();
    }

    /** The frame's time later by {@code nanos}. */
    TextRig later(long nanos) {
        now += nanos;
        return this;
    }

    /** The element's left, top, right, bottom in pixels. */
    float[] box(Element e) {
        float x = ui.layout().x(e.slot());
        float y = ui.layout().y(e.slot());
        return new float[] {x, y, x + ui.layout().width(e.slot()), y + ui.layout().height(e.slot())};
    }

    /** The visible glyph quads recorded for the HUD layer. */
    int glyphs() {
        DrawList list = ui.layers()[0];
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            int h = list.header(i);
            if (Primitive.kind(h) == Primitive.GLYPH && list.floatAt(i, Primitive.X1) > list.floatAt(i, Primitive.X0)) {
                n++;
            }
        }
        return n;
    }
}
