package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.Character.UnicodeScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The font files of common systems this text system knows by name: Windows' fonts, the DejaVu, Liberation and Noto
 * families of Linux distributions, a few of macOS's. No font ships with the toolkit (choosing one is the product's
 * decision); {@link #discover()} returns what this machine has.
 */
@AllowAllocation("runs once, when the registry is made")
public final class SystemFonts {

    /** File name, face index, family, weight, italic. */
    private static final Object[][] KNOWN = {
        // Windows
        {"segoeui.ttf", 0, "Segoe UI", 400, false}, {"segoeuib.ttf", 0, "Segoe UI", 700, false},
        {"segoeuii.ttf", 0, "Segoe UI", 400, true}, {"segoeuiz.ttf", 0, "Segoe UI", 700, true},
        {"segoeuil.ttf", 0, "Segoe UI", 300, false}, {"seguisb.ttf", 0, "Segoe UI", 600, false},
        {"arial.ttf", 0, "Arial", 400, false}, {"arialbd.ttf", 0, "Arial", 700, false},
        {"ariali.ttf", 0, "Arial", 400, true}, {"arialbi.ttf", 0, "Arial", 700, true},
        {"times.ttf", 0, "Times New Roman", 400, false}, {"timesbd.ttf", 0, "Times New Roman", 700, false},
        {"timesi.ttf", 0, "Times New Roman", 400, true}, {"timesbi.ttf", 0, "Times New Roman", 700, true},
        {"tahoma.ttf", 0, "Tahoma", 400, false}, {"tahomabd.ttf", 0, "Tahoma", 700, false},
        {"calibri.ttf", 0, "Calibri", 400, false}, {"calibrib.ttf", 0, "Calibri", 700, false},
        {"calibrii.ttf", 0, "Calibri", 400, true}, {"calibriz.ttf", 0, "Calibri", 700, true},
        {"cambria.ttc", 0, "Cambria", 400, false}, {"cambriab.ttf", 0, "Cambria", 700, false},
        {"consola.ttf", 0, "Consolas", 400, false}, {"consolab.ttf", 0, "Consolas", 700, false},
        {"Nirmala.ttc", 0, "Nirmala UI", 400, false}, {"Nirmala.ttc", 1, "Nirmala UI", 700, false},
        {"msyh.ttc", 0, "Microsoft YaHei", 400, false}, {"msyhbd.ttc", 0, "Microsoft YaHei", 700, false},
        {"YuGothR.ttc", 0, "Yu Gothic", 400, false}, {"YuGothB.ttc", 0, "Yu Gothic", 700, false},
        {"malgun.ttf", 0, "Malgun Gothic", 400, false}, {"malgunbd.ttf", 0, "Malgun Gothic", 700, false},
        {"LeelawUI.ttf", 0, "Leelawadee UI", 400, false},
        {"seguiemj.ttf", 0, "Segoe UI Emoji", 400, false}, {"seguisym.ttf", 0, "Segoe UI Symbol", 400, false},
        // Linux
        {"DejaVuSans.ttf", 0, "DejaVu Sans", 400, false}, {"DejaVuSans-Bold.ttf", 0, "DejaVu Sans", 700, false},
        {"DejaVuSans-Oblique.ttf", 0, "DejaVu Sans", 400, true},
        {"DejaVuSans-BoldOblique.ttf", 0, "DejaVu Sans", 700, true},
        {"DejaVuSerif.ttf", 0, "DejaVu Serif", 400, false}, {"DejaVuSerif-Bold.ttf", 0, "DejaVu Serif", 700, false},
        {"DejaVuSansMono.ttf", 0, "DejaVu Sans Mono", 400, false},
        {"DejaVuSansMono-Bold.ttf", 0, "DejaVu Sans Mono", 700, false},
        {"LiberationSans-Regular.ttf", 0, "Liberation Sans", 400, false},
        {"LiberationSans-Bold.ttf", 0, "Liberation Sans", 700, false},
        {"LiberationSerif-Regular.ttf", 0, "Liberation Serif", 400, false},
        {"LiberationMono-Regular.ttf", 0, "Liberation Mono", 400, false},
        {"NotoSans-Regular.ttf", 0, "Noto Sans", 400, false}, {"NotoSans-Bold.ttf", 0, "Noto Sans", 700, false},
        {"NotoSans-Italic.ttf", 0, "Noto Sans", 400, true}, {"NotoSerif-Regular.ttf", 0, "Noto Serif", 400, false},
        {"NotoSansArabic-Regular.ttf", 0, "Noto Sans Arabic", 400, false},
        {"NotoNaskhArabic-Regular.ttf", 0, "Noto Naskh Arabic", 400, false},
        {"NotoSansHebrew-Regular.ttf", 0, "Noto Sans Hebrew", 400, false},
        {"NotoSansDevanagari-Regular.ttf", 0, "Noto Sans Devanagari", 400, false},
        {"NotoSansThai-Regular.ttf", 0, "Noto Sans Thai", 400, false},
        {"NotoSansCJK-Regular.ttc", 0, "Noto Sans CJK JP", 400, false},
        {"NotoSansCJK-Regular.ttc", 2, "Noto Sans CJK SC", 400, false},
        {"NotoSansCJK-Bold.ttc", 2, "Noto Sans CJK SC", 700, false},
        {"NotoEmoji-Regular.ttf", 0, "Noto Emoji", 400, false},
        // macOS
        {"Helvetica.ttc", 0, "Helvetica", 400, false}, {"Arial.ttf", 0, "Arial", 400, false},
        {"GeezaPro.ttc", 0, "Geeza Pro", 400, false}, {"Menlo.ttc", 0, "Menlo", 400, false},
    };

    private SystemFonts() {
    }

    /** The known fonts found in this machine's font directories; none with {@code TRUESIM_SYSTEM_FONTS=false}. */
    public static List<FontSource> discover() {
        if ("false".equals(System.getenv("TRUESIM_SYSTEM_FONTS"))) {
            return List.of(); // a machine without fonts, as a CI runner may be: checks that bundled fonts suffice
        }
        List<Path> dirs = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            dirs.add(Paths.get(windir, "Fonts"));
        }
        dirs.add(Paths.get("/usr/share/fonts"));
        dirs.add(Paths.get("/usr/local/share/fonts"));
        dirs.add(Paths.get(System.getProperty("user.home"), ".fonts"));
        dirs.add(Paths.get(System.getProperty("user.home"), ".local", "share", "fonts"));
        dirs.add(Paths.get("/System/Library/Fonts"));
        dirs.add(Paths.get("/Library/Fonts"));
        return discover(dirs);
    }

    /** The known fonts found in {@code dirs} and their subdirectories. */
    public static List<FontSource> discover(List<Path> dirs) {
        Map<String, Path> files = new HashMap<>();
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                index(dir, files);
            }
        }
        List<FontSource> found = new ArrayList<>();
        for (Object[] known : KNOWN) {
            Path file = files.get(((String) known[0]).toLowerCase(Locale.ROOT));
            if (file != null) {
                found.add(FontSource.of(file, (Integer) known[1], (String) known[2], (Integer) known[3],
                    (Boolean) known[4]));
            }
        }
        return found;
    }

    private static void index(Path dir, Map<String, Path> files) {
        try (Stream<Path> walk = Files.walk(dir, 6)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                // the first found wins: Windows' own directory before user directories
                files.putIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT), p);
            }
        } catch (IOException | UncheckedIOException e) {
            // an unreadable directory has no fonts for us
        }
    }

    /** The generic aliases, script fallbacks and last resort for the known families. */
    static void defaults(FontRegistry registry) {
        registry.alias("sans-serif", "Segoe UI", "Arial", "DejaVu Sans", "Noto Sans", "Liberation Sans", "Helvetica");
        registry.alias("system-ui", "Segoe UI", "DejaVu Sans", "Noto Sans", "Helvetica", "Arial");
        registry.alias("serif", "Times New Roman", "Cambria", "DejaVu Serif", "Noto Serif", "Liberation Serif");
        registry.alias("monospace", "Consolas", "DejaVu Sans Mono", "Liberation Mono", "Menlo");
        registry.fallback(UnicodeScript.ARABIC, "Segoe UI", "Tahoma", "Arial", "Noto Sans Arabic",
            "Noto Naskh Arabic", "DejaVu Sans", "Geeza Pro");
        registry.fallback(UnicodeScript.HEBREW, "Segoe UI", "Arial", "Noto Sans Hebrew", "DejaVu Sans");
        registry.fallback(UnicodeScript.DEVANAGARI, "Nirmala UI", "Noto Sans Devanagari");
        registry.fallback(UnicodeScript.THAI, "Leelawadee UI", "Tahoma", "Noto Sans Thai");
        String[] cjk = {"Microsoft YaHei", "Yu Gothic", "Noto Sans CJK SC", "Noto Sans CJK JP"};
        registry.fallback(UnicodeScript.HAN, cjk);
        registry.fallback(UnicodeScript.HIRAGANA, "Yu Gothic", "Microsoft YaHei", "Noto Sans CJK JP");
        registry.fallback(UnicodeScript.KATAKANA, "Yu Gothic", "Microsoft YaHei", "Noto Sans CJK JP");
        registry.fallback(UnicodeScript.BOPOMOFO, cjk);
        registry.fallback(UnicodeScript.HANGUL, "Malgun Gothic", "Noto Sans CJK SC");
        registry.lastResort("Segoe UI", "Arial", "DejaVu Sans", "Noto Sans", "Segoe UI Emoji", "Noto Emoji",
            "Segoe UI Symbol", "Microsoft YaHei", "Noto Sans CJK SC");
    }
}
