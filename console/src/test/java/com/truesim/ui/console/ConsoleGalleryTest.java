package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.LayoutSanity;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The console gallery in both themes, as the engine's screenshots take it: {@link LayoutSanity} (the console module's
 * share of {@code GallerySanityTest}, which cannot see this module), every interactive node named, and CPU previews
 * with {@code TRUESIM_SCREENSHOTS=true}.
 */
class ConsoleGalleryTest {

    private static StyleSheet theme(String name) {
        return name.equals("default") ? Tss.defaultTheme() : Tss.testTheme();
    }

    /** The real text path over the bundled fonts, as the screenshots use. */
    static Ui ui(String theme) {
        NativeTextBackend backend = new NativeTextBackend();
        FontRegistry fonts = BundledCjkFonts.registry(backend);
        BundledEmojiFonts.register(fonts);
        UiTextures textures = new UiTextures();
        GlyphAtlas atlas = new GlyphAtlas(textures, backend.msdfGenerator(), backend.newRasterizer(), null, null,
            new AtlasSettings());
        return new Ui(theme(theme), new TextSystem(fonts, backend.newShaper(), atlas), textures);
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "test"})
    void theGalleryIsSaneAndNamed(String theme) throws Exception {
        Ui ui = ui(theme);
        ConsoleGallery g = ConsoleGallery.build(ui, 1280, 720);
        assertThat(g.console.popupShown()).isTrue();
        assertThat(g.console.selected()).isZero();
        assertThat(g.console.completion().candidates().getFirst().text()).isEqualTo("orc");
        assertThat(g.console.completion().hint()).startsWith("spawn <count: 1..100> [kind: orc|elf|ogre]");
        LayoutSanity.assertSane(ui, "console gallery (" + theme + ")");
        assertThat(Semantics.unnamed(Semantics.of(ui.store(), ui.interaction()))).isEmpty();
        Path dir = CpuPreview.dir();
        if (dir != null) {
            long bg = theme.equals("default") ? Rgba.srgb(13, 17, 23, 1) : Rgba.srgb(43, 0, 64, 1);
            new CpuPreview(1280, 720, bg).draw(ui.layers(), ui.layerCount(), ui.textures())
                .save(dir.resolve("console-" + theme + ".png"));
        }
    }
}
