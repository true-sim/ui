package com.truesim.ui.style.tss;

import com.truesim.ui.style.StyleSheet;
import java.util.List;

/**
 * A compiled {@code .tss} sheet: the typed {@link StyleSheet}, every source path it read (the sheet and its partials,
 * which a hot reload watches), and the messages of {@code @warn} and {@code @debug}.
 */
public record Compilation(StyleSheet sheet, List<String> sources, List<String> warnings) {

    public Compilation {
        sources = List.copyOf(sources);
        warnings = List.copyOf(warnings);
    }
}
