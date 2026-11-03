package com.truesim.ui.text.natives.tools;

import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.fonts.BundledFonts;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.text.AtlasBaker;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.GlyphCache;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The build-time atlas baker's command line (Gradle task {@code bakeAtlas}): shapes Latin plus a game's text with the
 * bundled fonts (ui-fonts, ui-fonts-cjk) first and the installed ones after, through the run-time code ({@link AtlasBaker}, {@link GlyphAtlas}, msdfgen) and writes the
 * {@link GlyphCache} the run time loads.
 *
 * <pre>
 * --out FILE            the cache file to write
 * --families LIST       font fallback lists separated by '|', families in a list by ';' (default sans-serif)
 * --text-file FILE      UTF-8 text whose glyphs to bake besides Latin
 * --text TEXT           text whose glyphs to bake besides Latin
 * --em-size N           texels per em (default 40)
 * --pixel-range N       distance range in texels (default 6)
 * --weight N            100 to 900 (default 400); --italic
 * </pre>
 */
public final class BakeAtlas {

    private BakeAtlas() {
    }

    /** Runs the baker; see the class comment for the arguments. */
    public static void main(String[] args) throws Exception {
        Path out = null;
        String families = "sans-serif";
        StringBuilder text = new StringBuilder(AtlasBaker.LATIN);
        float emSize = 40;
        float pixelRange = 6;
        int weight = 400;
        boolean italic = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--families" -> families = args[++i];
                case "--text-file" -> text.append(Files.readString(Path.of(args[++i]), StandardCharsets.UTF_8));
                case "--text" -> text.append(args[++i]);
                case "--em-size" -> emSize = Float.parseFloat(args[++i]);
                case "--pixel-range" -> pixelRange = Float.parseFloat(args[++i]);
                case "--weight" -> weight = Integer.parseInt(args[++i]);
                case "--italic" -> italic = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        List<List<String>> chains = new ArrayList<>();
        for (String chain : families.split("\\|")) {
            chains.add(List.of(chain.split(";")));
        }
        try (NativeTextBackend backend = new NativeTextBackend()) {
            AtlasSettings settings = new AtlasSettings();
            settings.emSize().set(emSize);
            settings.pixelRange().set(pixelRange);
            GlyphCache cache = new GlyphCache(settings.emSize().get(), settings.pixelRange().get());
            GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), null, cache, settings);
            FontRegistry fonts = FontRegistry.system(backend);
            BundledFonts.register(fonts);
            BundledCjkFonts.register(fonts);
            TextSystem system = new TextSystem(fonts, backend.newShaper(), atlas);
            int glyphs = AtlasBaker.bakeTo(out, system, text, chains, weight, italic);
            System.out.println("baked " + glyphs + " glyphs on " + atlas.pageCount() + " page(s) into " + out);
        }
    }
}
