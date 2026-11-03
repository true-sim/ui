package com.truesim.ui.fonts.cjk;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.fonts.BundledFonts;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.TextBackend;
import java.lang.Character.UnicodeScript;
import java.nio.file.Path;

/**
 * The optional CJK fonts: Noto Sans SC and Noto Sans JP (the region subsets of Noto Sans CJK 2.004, regular weight),
 * under the SIL Open Font License 1.1. Han characters take the Simplified Chinese forms, kana the Japanese face.
 * Korean Hangul is not in these subsets.
 */
public final class BundledCjkFonts {

    private static final String ROOT = "com/truesim/ui/fonts/cjk/";
    private static final Object[][] FACES = {
        {"NotoSansSC-Regular.otf", "Noto Sans SC", 400, false},
        {"NotoSansJP-Regular.otf", "Noto Sans JP", 400, false},
    };

    private BundledCjkFonts() {
    }

    /** A registry of every bundled font, CJK included. */
    @AllowAllocation("fonts are registered at start-up")
    public static FontRegistry registry(TextBackend backend) {
        FontRegistry registry = BundledFonts.registry(backend);
        register(registry);
        return registry;
    }

    /** Adds the CJK faces to {@code registry} (after {@link BundledFonts#register}) and their script fallbacks. */
    @AllowAllocation("fonts are registered at start-up")
    public static void register(FontRegistry registry) {
        Path dir = BundledFonts.extract(BundledCjkFonts.class.getClassLoader(), ROOT, "cjk", FACES);
        for (Object[] face : FACES) {
            registry.register(FontSource.of(dir.resolve((String) face[0]), 0, (String) face[1], (Integer) face[2],
                (Boolean) face[3]));
        }
        registry.fallback(UnicodeScript.HAN, "Noto Sans SC", "Noto Sans JP", "Microsoft YaHei", "Yu Gothic");
        registry.fallback(UnicodeScript.BOPOMOFO, "Noto Sans SC", "Microsoft YaHei");
        registry.fallback(UnicodeScript.HIRAGANA, "Noto Sans JP", "Yu Gothic", "Noto Sans SC");
        registry.fallback(UnicodeScript.KATAKANA, "Noto Sans JP", "Yu Gothic", "Noto Sans SC");
        BundledFonts.lastResort(registry);
    }
}
