package com.truesim.ui.fonts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.TextBackend;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.Character.UnicodeScript;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The fonts that ship with the toolkit, so text looks and measures the same on every machine: Inter for Latin, Greek
 * and Cyrillic (weights 400 to 700 and italic), Noto Sans Arabic and Noto Sans Devanagari (400 and 700), all under the
 * SIL Open Font License 1.1 (the licences and CREDITS.md sit beside the files). CJK is the optional
 * {@code ui-fonts-cjk} module, for its size.
 *
 * <p>Faces are read by path (FreeType opens files), so the resources are copied once into a directory of the system's
 * temporary files named by the font set's version, then registered from there.
 *
 * <pre>{@code
 * FontRegistry fonts = BundledFonts.registry(backend);   // bundled only: the same on every machine
 * FontRegistry both = FontRegistry.system(backend);
 * BundledFonts.register(both);                            // bundled first, installed fonts after
 * }</pre>
 */
public final class BundledFonts {

    /** The bundled set's version: part of the directory the files are copied into. */
    public static final String VERSION = "1";

    /** The families the generic names stand for, the bundled ones first, then common installed ones. */
    static final List<String> SANS = List.of("Inter", "Noto Sans Arabic", "Noto Sans Devanagari", "Segoe UI", "Arial",
        "DejaVu Sans", "Noto Sans", "Liberation Sans", "Helvetica");

    private static final String ROOT = "com/truesim/ui/fonts/";
    private static final Object[][] FACES = {
        {"Inter-Regular.ttf", "Inter", 400, false},
        {"Inter-Italic.ttf", "Inter", 400, true},
        {"Inter-Medium.ttf", "Inter", 500, false},
        {"Inter-SemiBold.ttf", "Inter", 600, false},
        {"Inter-Bold.ttf", "Inter", 700, false},
        {"NotoSansArabic-Regular.ttf", "Noto Sans Arabic", 400, false},
        {"NotoSansArabic-Bold.ttf", "Noto Sans Arabic", 700, false},
        {"NotoSansDevanagari-Regular.ttf", "Noto Sans Devanagari", 400, false},
        {"NotoSansDevanagari-Bold.ttf", "Noto Sans Devanagari", 700, false},
    };

    private BundledFonts() {
    }

    /** A registry of the bundled fonts only, with the default chain: text is laid out the same everywhere. */
    @AllowAllocation("fonts are registered at start-up")
    public static FontRegistry registry(TextBackend backend) {
        FontRegistry registry = new FontRegistry(backend);
        register(registry);
        return registry;
    }

    /**
     * Adds the bundled fonts to {@code registry} and makes the generic families ({@code sans-serif}, {@code system-ui},
     * {@code ui}), the Arabic and Devanagari fallbacks and the last resort try them first, installed fonts after.
     */
    @AllowAllocation("fonts are registered at start-up")
    public static void register(FontRegistry registry) {
        Path dir = extract(BundledFonts.class.getClassLoader(), ROOT, "base", FACES);
        for (Object[] face : FACES) {
            registry.register(FontSource.of(dir.resolve((String) face[0]), 0, (String) face[1], (Integer) face[2],
                (Boolean) face[3]));
        }
        String[] sans = SANS.toArray(new String[0]);
        registry.alias("sans-serif", sans);
        registry.alias("system-ui", sans);
        registry.alias("ui", sans);
        registry.fallback(UnicodeScript.ARABIC, "Noto Sans Arabic", "Segoe UI", "Tahoma", "Arial", "DejaVu Sans");
        registry.fallback(UnicodeScript.DEVANAGARI, "Noto Sans Devanagari", "Nirmala UI");
        lastResort(registry);
    }

    /** Sets the last resort: the bundled families, installed ones, then CJK and emoji. */
    @AllowAllocation("fonts are registered at start-up")
    public static void lastResort(FontRegistry registry) {
        List<String> chain = new ArrayList<>(SANS);
        chain.addAll(List.of("Noto Sans SC", "Noto Sans JP", "Microsoft YaHei", "Segoe UI Emoji", "Noto Emoji",
            "Segoe UI Symbol"));
        registry.lastResort(chain.toArray(new String[0]));
    }

    /**
     * Copies {@code faces} (rows starting with a file name) from {@code root} of {@code loader}'s resources into the
     * directory {@code set} under the system's temporary files, once: files already there with the right size stay.
     */
    @AllowAllocation("fonts are registered at start-up")
    public static Path extract(ClassLoader loader, String root, String set, Object[][] faces) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "truesim-ui-fonts-" + VERSION, set);
        try {
            Files.createDirectories(dir);
            for (Object[] face : faces) {
                String name = (String) face[0];
                Path target = dir.resolve(name);
                try (InputStream in = loader.getResourceAsStream(root + name)) {
                    if (in == null) {
                        throw new IOException("missing bundled font " + root + name);
                    }
                    byte[] bytes = in.readAllBytes();
                    if (Files.isRegularFile(target) && Files.size(target) == bytes.length) {
                        continue;
                    }
                    Path temp = Files.createTempFile(dir, name, ".part");
                    Files.write(temp, bytes);
                    move(temp, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
