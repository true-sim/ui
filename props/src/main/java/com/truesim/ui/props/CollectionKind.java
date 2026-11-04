package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A collection editor kind (design 5.11, UI18), registered by name and chosen by the style {@code collection} on the
 * {@code collection} element (code and {@code @ListStyle} below and above it as usual). The shipped kinds:
 * <ul>
 * <li>{@code table}: a header of the element's property labels and a row of editors per item (a nested panel laid out
 * as {@code row}); above {@code virtualize-above} items a virtualized {@code TableView} editing cells in place;
 * <li>{@code foldouts}: each item a collapsible nested panel titled with its label; above {@code virtualize-above}
 * items a virtualized master-detail list;
 * <li>{@code master-detail}: a virtualized {@code ListView} (rows dragged to reorder) and the selected item's panel;
 * <li>{@code chips}: tags with a remove button each, added from a menu of enum constants or typed;
 * <li>{@code compact}: "12 items" and a button opening a dialog with a master-detail editor;
 * <li>{@code map}: a key and a value editor per entry, keys kept unique.
 * </ul>
 * A code-supplied kind is a class implementing this, registered with {@link PropertyBuilder#collectionKind}; styles then
 * select it by name.
 */
@FunctionalInterface
public interface CollectionKind {

    /** A table of rows. */
    String TABLE = "table";
    /** Collapsible items. */
    String FOLDOUTS = "foldouts";
    /** A list and the selected item. */
    String MASTER_DETAIL = "master-detail";
    /** Tags. */
    String CHIPS = "chips";
    /** A summary and a dialog. */
    String COMPACT = "compact";
    /** Keys and values. */
    String MAP = "map";

    /** A new view of {@code editor}'s items. */
    View create(CollectionEditor editor);

    /** The shipped kinds by name. */
    @AllowAllocation("made once per builder")
    static Map<String, CollectionKind> standard() {
        Map<String, CollectionKind> m = new LinkedHashMap<>();
        m.put(TABLE, CollectionViews::table);
        m.put(FOLDOUTS, CollectionViews::foldouts);
        m.put(MASTER_DETAIL, CollectionViews.MasterDetail::new);
        m.put(CHIPS, CollectionViews.Chips::new);
        m.put(COMPACT, CollectionViews.Compact::new);
        m.put(MAP, CollectionViews.InlineTable::new);
        return m;
    }

    /** A view of a collection editor's items. */
    interface View {

        /** The view's element, in the editor's body. */
        Element element();

        /** The number of items changed (an add, a remove, a clear, a new value): rebuild what depends on it. */
        void structure();

        /** Items were replaced, their number the same (an edit, a move): refresh labels and mirrors. */
        default void update() {
            // nested panels follow their lenses by themselves
        }

        /** Once a frame. */
        default void poll() {
            // nothing by default
        }

        /** The selected item the header's item operations act on, or -1 when the view has no selection. */
        default int selected() {
            return -1;
        }

        /** Whether the view adds items itself (the header then has no add). */
        default boolean adds() {
            return false;
        }

        /** Whether the view shows the number of items itself (the header then does not). */
        default boolean counts() {
            return false;
        }

        /** Whether the view selects items (the header then holds the item operations). */
        default boolean selects() {
            return false;
        }

        /** The view is dropped. */
        default void dispose() {
            // nothing by default
        }
    }
}
