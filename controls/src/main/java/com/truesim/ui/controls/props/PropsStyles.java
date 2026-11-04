package com.truesim.ui.controls.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;
import com.truesim.ui.style.StyleTypes;
import java.util.List;

/**
 * The style keys of generated property panels and collection editors (design 5.11, UI17, UI18), read by
 * {@code ui-props}. They are declared here, with the controls, so the shipped themes (partials {@code _props.tss} and
 * {@code _props-test.tss}) compile wherever the controls are, with or without {@code ui-props}. Each is a hint over
 * annotations: when a rule sets it, it beats the property's annotations (instance code beats both).
 *
 * <pre>{@code
 * props.panel[model=Material] { props-layout: inspector; }
 * props.row[annotation=Range] { editor: number; }
 * props.row[property=secret] { visible: false; }
 * collection[element=Waypoint] { collection: master-detail; ops: add remove reorder; }
 * }</pre>
 */
@AllowAllocation("keys are declared once")
public final class PropsStyles implements StyleKeyHolder {

    /** The layout strategy of a property panel by name: form, grid, inspector, compact, or a registered one. */
    public static final StyleKey<String> PROPS_LAYOUT = StyleKey.of("props-layout", StyleTypes.string(), "");
    /** The editor of a property row by name ({@code number}, {@code slider}, ... or a registered one). */
    public static final StyleKey<String> EDITOR = StyleKey.of("editor", StyleTypes.string(), "");
    /** Where a row's label goes. */
    public static final StyleKey<LabelPlacement> LABEL_PLACEMENT =
        StyleKey.of("label-placement", LabelPlacement.class, LabelPlacement.START);
    /** Whether a property row shows (over {@code @Hidden} and {@code @ShowIf}). */
    public static final StyleKey<Boolean> VISIBLE = StyleKey.of("visible", StyleTypes.bool(), true);
    /** A property row's order (over {@code @Order}): lower first. */
    public static final StyleKey<Integer> ORDER = StyleKey.of("order", StyleTypes.integer(-1_000_000, 1_000_000), 0);
    /** The collection editor kind by name: table, foldouts, master-detail, chips, compact, map, or a registered one. */
    public static final StyleKey<String> COLLECTION = StyleKey.of("collection", StyleTypes.string(), "");
    /** The operations a collection editor offers: add remove reorder duplicate clear context-menu. */
    public static final StyleKey<List<String>> OPS = StyleKey.of("ops", StyleTypes.list(StyleTypes.string()),
        List.of());
    /** Where a collection editor's operations show. */
    public static final StyleKey<OpsPlacement> OPS_PLACEMENT =
        StyleKey.of("ops-placement", OpsPlacement.class, OpsPlacement.HEADER);
    /** The operations that ask before they run ({@code confirm: clear remove}). */
    public static final StyleKey<List<String>> CONFIRM = StyleKey.of("confirm", StyleTypes.list(StyleTypes.string()),
        List.of());
    /** Above how many items foldouts give way to a virtualized list (tables switch to a virtualized table). */
    public static final StyleKey<Integer> VIRTUALIZE_ABOVE =
        StyleKey.of("virtualize-above", StyleTypes.integer(0, 1_000_000_000), 50);
    /** The text of an operation's button or menu item ({@code props-op#add { op-label: "Add"; }}); empty: none. */
    public static final StyleKey<String> OP_LABEL = StyleKey.of("op-label", StyleTypes.string(), "");

    /** Made by {@link java.util.ServiceLoader}: initialises the keys. */
    public PropsStyles() {
        // the keys are static
    }

    /** Where a property row's label goes ({@link #LABEL_PLACEMENT}). */
    public enum LabelPlacement {
        /** Before the editor (the row's fact {@code label=start}). */
        START,
        /** Above the editor ({@code label=top}). */
        TOP,
        /** No label ({@code label=none}). */
        NONE
    }

    /** Where a collection editor's operations show ({@link #OPS_PLACEMENT}). */
    public enum OpsPlacement {
        /** In the editor's header (add, clear) and on the selected item (the rest). */
        HEADER,
        /** At the end of each item's row (add and clear stay in the header). */
        ROW_END,
        /** At the end of each item's row, shown while the item is hovered. */
        HOVER
    }
}
