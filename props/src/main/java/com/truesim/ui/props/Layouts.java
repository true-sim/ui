package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.basic.Collapsible;
import com.truesim.ui.core.Element;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shipped layout strategies (design 5.11), registered by name; themes choose one with {@code props-layout} on a
 * {@code props.panel}, and style what they make:
 * <ul>
 * <li>{@code form}: each group a {@code props.group} with a title ({@code label.prop-group-title}) over its rows
 * (label beside field); a {@code @Foldout} group a collapsible instead;
 * <li>{@code grid}: as form, the rows of each group in a {@code props.grid} (themes give it a grid of tiles);
 * <li>{@code inspector}: every named group a collapsible section (kept expanded or not across rebuilds), the
 * ungrouped rows above them;
 * <li>{@code compact}: every row in one column, no group titles;
 * <li>{@code row}: every row side by side (a table's row of a collection's item; themes hide the labels).
 * </ul>
 */
@AllowAllocation("panels are arranged when built or restyled")
public final class Layouts {

    /** Labels beside fields, groups titled. */
    public static final String FORM = "form";
    /** Groups as grids of tiles. */
    public static final String GRID = "grid";
    /** Groups as collapsible sections. */
    public static final String INSPECTOR = "inspector";
    /** One column, no titles. */
    public static final String COMPACT = "compact";
    /** Side by side: a table row. */
    public static final String ROW = "row";

    /** The standard LAYOUT step: the strategy registered under the chosen name. */
    static final LayoutStrategy BY_NAME = context -> context.panel().builder().layouts().get(context.name())
        .arrange(context);

    private Layouts() {
    }

    /** The shipped strategies by name. */
    public static Map<String, LayoutStrategy> standard() {
        Map<String, LayoutStrategy> m = new LinkedHashMap<>();
        m.put(FORM, c -> grouped(c, false, false));
        m.put(GRID, c -> grouped(c, true, false));
        m.put(INSPECTOR, c -> grouped(c, false, true));
        m.put(COMPACT, Layouts::flat);
        m.put(ROW, Layouts::flat);
        return m;
    }

    private static void flat(LayoutStrategy.Context c) {
        for (LayoutStrategy.Section s : c.sections()) {
            for (PropertyRow r : s.rows()) {
                c.root().add(r.element());
            }
        }
    }

    private static void grouped(LayoutStrategy.Context c, boolean grid, boolean foldouts) {
        if (c.sections().isEmpty()) {
            return;
        }
        boolean titles = c.sections().size() > 1 || !c.sections().getFirst().name().isEmpty();
        for (LayoutStrategy.Section s : c.sections()) {
            Element into;
            if (s.name().isEmpty() && (foldouts || !titles)) {
                into = c.root(); // the ungrouped rows of an inspector, or a form without groups
            } else if (foldouts || s.foldout()) {
                Collapsible f = c.foldout(s);
                c.root().add(f.element());
                into = f.content();
            } else {
                Element group = c.container("group");
                group.id(s.name().isEmpty() ? "general" : s.name());
                group.add(c.label("prop-group-title", s.name().isEmpty() ? c.panel().model().label() : s.name()));
                c.root().add(group);
                into = group;
            }
            if (grid) {
                Element g = c.container("grid");
                into.add(g);
                into = g;
            }
            for (PropertyRow r : s.rows()) {
                into.add(r.element());
            }
        }
    }
}
