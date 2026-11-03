package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import java.nio.file.Path;
import java.util.Map;

/**
 * Compiles {@code .tss} ("true-sim style sheets", a Sass-like language) into typed {@link StyleSheet}s, all at load
 * time. Keys are found by name in {@link StyleKeys}: the standard {@link Styles} always, and the keys of the holder
 * classes passed in (a control's style keys), so a new control's keys are usable at once.
 *
 * <pre>{@code
 * StyleSheet theme = Tss.defaultTheme();
 * Compilation c = Tss.compileFile(Path.of("ui/hud.tss"), HudStyles.class);
 * StyleSheet fromResource = Tss.compileResource("themes/hud.tss").sheet();
 * }</pre>
 *
 * @throws TssException from every method, for an error in a sheet: file, line, column, rule and include trace
 */
@AllowAllocation("sheets are compiled at load")
public final class Tss {

    /** The class path folder of the shipped themes. */
    public static final String THEMES = "com/truesim/ui/style";

    private Tss() {
    }

    /** Compiles {@code path} from {@code loader}, after loading the keys of {@code keyHolders}. */
    public static Compilation compile(SourceLoader loader, String path, Class<?>... keyHolders) {
        StyleKeys.load(Styles.class);
        StyleKeys.load(keyHolders);
        return new Compiler(loader).compile(path);
    }

    /** Compiles the class path resource {@code path} (with partials beside it). */
    public static Compilation compileResource(String path, Class<?>... keyHolders) {
        return compile(SourceLoader.classpath(Tss.class.getClassLoader(), ""), path, keyHolders);
    }

    /** Compiles the file {@code file} (with partials beside it). */
    public static Compilation compileFile(Path file, Class<?>... keyHolders) {
        Path absolute = file.toAbsolutePath().normalize();
        return compile(SourceLoader.files(absolute.getParent()), absolute.getFileName().toString(), keyHolders);
    }

    /** Compiles source text named {@code name} (for messages) with no partials. */
    public static StyleSheet compileString(String name, String source, Class<?>... keyHolders) {
        return compile(SourceLoader.memory(Map.of(name, source)), name, keyHolders).sheet();
    }

    /** The default theme: neutral, a dark game HUD look, with light and dark token sets. */
    public static StyleSheet defaultTheme() {
        return compileResource(THEMES + "/default-theme.tss").sheet();
    }

    /** The deliberately garish test theme: every element it styles is obviously restyled. */
    public static StyleSheet testTheme() {
        return compileResource(THEMES + "/test-theme.tss").sheet();
    }
}
