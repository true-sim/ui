package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.basic.Collapsible;
import com.truesim.ui.core.Element;
import java.util.List;

/**
 * The LAYOUT step of the pipeline (design 5.11): arranges a panel's ordered, grouped rows into elements under the
 * panel's root. The standard step picks a registered strategy by name ({@link Layouts}): code first
 * ({@link PropertyBuilder#layout(String)}), then the style {@code props-layout} of the panel, then the builder's
 * default. A strategy runs again whenever the choice, or the rows' order, changes; the rows are detached before and
 * the containers it made through {@link Context#container} destroyed.
 */
@FunctionalInterface
public interface LayoutStrategy {

    /** Arranges {@code context}'s sections into its panel. */
    void arrange(Context context);

    /**
     * A group of rows.
     *
     * @param name the group ({@code ""} for none)
     * @param rows the rows in order
     * @param foldout whether the group asked to be collapsible ({@code @Foldout})
     * @param expanded whether a foldout starts expanded
     */
    record Section(String name, List<PropertyRow> rows, boolean foldout, boolean expanded) {
    }

    /** What a strategy arranges and the elements it may make. */
    @AllowAllocation("panels are arranged when built or restyled")
    final class Context {
        private final PropertyPanel<?> panel;
        private final String name;
        private final List<Section> sections;

        Context(PropertyPanel<?> panel, String name, List<Section> sections) {
            this.panel = panel;
            this.name = name;
            this.sections = sections;
        }

        /** The panel. */
        public PropertyPanel<?> panel() {
            return panel;
        }

        /** The chosen layout's name. */
        public String name() {
            return name;
        }

        /** The sections in order. */
        public List<Section> sections() {
            return sections;
        }

        /** The panel's element: arrange into it. */
        public Element root() {
            return panel.element();
        }

        /** A new element of type {@code props} and style name {@code style}, destroyed at the next arrangement. */
        public Element container(String style) {
            Element e = panel.ui().element("props", style);
            panel.container(e);
            return e;
        }

        /** A new label of style {@code style}, destroyed at the next arrangement. */
        public Element label(String style, CharSequence text) {
            Element e = panel.ui().label(style, text);
            panel.container(e);
            return e;
        }

        /** The collapsible of {@code section}, kept across arrangements (its body is {@code content()}). */
        public Collapsible foldout(Section section) {
            return panel.foldout(section.name(), section.expanded());
        }
    }
}
