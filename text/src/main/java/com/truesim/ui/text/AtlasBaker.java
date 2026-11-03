package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Pre-bakes the glyphs a game's text needs, the build-time half of the atlas: it shapes the text with the run-time
 * fonts and shaper (so fallback fonts, ligatures and contextual forms are the glyphs the game will ask for), generates
 * them through the same {@link GlyphAtlas} and {@link MsdfGenerator} the run time uses, and saves the atlas's
 * {@link GlyphCache}. At run time an atlas over the loaded cache takes these glyphs without generating them.
 */
@AllowAllocation("a build-time tool")
public final class AtlasBaker {

    /** Printable ASCII and Latin-1: the default Latin set. */
    public static final String LATIN;

    static {
        StringBuilder s = new StringBuilder();
        for (char c = 0x20; c < 0x7F; c++) {
            s.append(c);
        }
        for (char c = 0xA0; c <= 0xFF; c++) {
            s.append(c);
        }
        LATIN = s.toString();
    }

    private AtlasBaker() {
    }

    /**
     * Requests the glyphs of {@code text} in each of {@code families} (each a fallback list) at {@code weight} and
     * {@code italic}, from {@code system}'s atlas, and generates them now. Returns the glyphs in the atlas.
     */
    public static int bake(TextSystem system, CharSequence text, List<List<String>> families, int weight,
        boolean italic) throws InterruptedException {
        GlyphAtlas atlas = system.atlas();
        for (List<String> chain : families) {
            TextBlock block = new TextBlock(system);
            block.set(text, chain, 32, weight, italic, TextAlign.START, Float.POSITIVE_INFINITY, 0);
        }
        atlas.finishAll();
        return atlas.size();
    }

    /** Bakes as {@link #bake} does and saves the atlas's cache to {@code file}. */
    public static int bakeTo(Path file, TextSystem system, CharSequence text, List<List<String>> families, int weight,
        boolean italic) throws IOException, InterruptedException {
        int glyphs = bake(system, text, families, weight, italic);
        system.atlas().cache().save(file);
        return glyphs;
    }
}
