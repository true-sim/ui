package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.Versioned;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;

/**
 * A drop-down (combo box) choosing one of a list of items, bound two-way to a {@link VersionedInt} (the chosen index,
 * -1 for none). Closed, it is a button (type {@code dropdown}: a {@code label} with the chosen item's text and a
 * {@code dropdown-arrow} icon); open ({@code :expanded}), a {@code dropdown-list} on the pop-up layer below it (above
 * when there is no room), at least as wide, its {@code dropdown-item}s ({@code :selected} for the chosen one) in a
 * {@code dropdown-items} column in a {@code dropdown-viewport} that scrolls when the theme caps its height (a minimal
 * internal scroll: the wheel, and keeping the focused item in view).
 *
 * <p>Keys: ACCEPT, DOWN or UP opens it (focusing the chosen item); in the list UP and DOWN move, HOME and END jump,
 * PAGE_UP and PAGE_DOWN move by ten, ACCEPT chooses, CANCEL closes and returns focus. Typing letters while it is open
 * jumps to the first item starting with them ({@code ui.dropdown.typeAheadTimeout} between letters; on by default,
 * which starts the engine's text input while the list has focus). {@link #searchable} turns typed letters into a
 * filter shown in a {@code dropdown-filter} label at the top, or a {@link FilterSource} (a search field of another
 * control library) supplies the filter text.
 *
 * @param <T> the item type
 */
public final class DropDown<T> extends Button implements Popup.Owner {

    /** The text an item shows. */
    @FunctionalInterface
    public interface ItemText<T> {
        /** The text of {@code item}. */
        CharSequence text(T item);
    }

    /** A filter text from elsewhere (a search field): its element shows at the top of the list. */
    public interface FilterSource extends Versioned {
        /** The element to show above the items (a text field). */
        Element element();

        /** The filter text now. */
        CharSequence text();
    }

    private final ItemText<T> itemText;
    private final Element arrow;
    private final Element list;
    private final Element viewport;
    private final Element items;
    private final Popup popup;
    private final ScrollBox scroll;
    private final List<T> values = new ArrayList<>();
    private Element[] itemElements = new Element[0];
    private CharSequence[] texts = new CharSequence[0];
    private boolean[] shown = new boolean[0];
    private VersionedInt model;
    private Watch watch;
    private boolean typeAhead = true;
    private boolean searchable;
    private Element filterLabel;
    private FilterSource filterSource;
    private Watch filterWatch;
    private final StringBuilder typed = new StringBuilder();
    private long typedAt;
    private int player;

    /** A drop-down named {@code name} over {@code items}, showing them by {@code text}, bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public DropDown(Controls controls, String name, List<T> items, ItemText<T> text, VersionedInt model) {
        super(controls, "dropdown", Role.COMBO_BOX, "", null, name);
        this.itemText = text;
        arrow = controls.icon("dropdown-arrow");
        root.add(arrow);
        list = part("dropdown-list");
        viewport = part("dropdown-viewport");
        list.add(viewport);
        list.semantics(Role.LIST, name);
        this.items = part("dropdown-items");
        scroll = new ScrollBox(controls, viewport, this.items, false);
        popup = new Popup(controls, list, this);
        listen(list, EventType.COMMAND.bit() | EventType.TEXT.bit() | EventType.WHEEL.bit());
        items(items);
        bind(model);
    }

    /** Shows {@code newItems} (the chosen index stays, clamped); returns this. */
    @AllowAllocation("items change on edit")
    public DropDown<T> items(List<T> newItems) {
        for (Element e : itemElements) {
            ui.destroy(e);
        }
        values.clear();
        values.addAll(newItems);
        int n = values.size();
        itemElements = new Element[n];
        texts = new CharSequence[n];
        shown = new boolean[n];
        for (int i = 0; i < n; i++) {
            CharSequence t = itemText.text(values.get(i));
            texts[i] = t;
            Element item = part("dropdown-item");
            item.semantics(Role.LIST_ITEM, t.toString());
            Element label = label(t);
            label.semantics(Role.NONE, null);
            item.add(label);
            focusable(item);
            interaction().textInput(item, typeAhead);
            listen(item, EventType.CLICK.bit());
            itemElements[i] = item;
            shown[i] = true;
            this.items.add(item);
        }
        if (model != null) {
            show();
        }
        return this;
    }

    /** Binds the drop-down to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public DropDown<T> bind(VersionedInt value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** Types letters to jump to items while open (on by default); returns this. */
    public DropDown<T> typeAhead(boolean on) {
        typeAhead = on;
        for (Element e : itemElements) {
            interaction().textInput(e, on || searchable);
        }
        return this;
    }

    /** Typed letters filter the items (shown in a {@code dropdown-filter} label at the top); returns this. */
    @AllowAllocation("configured once")
    public DropDown<T> searchable(boolean on) {
        searchable = on;
        if (on && filterLabel == null && filterSource == null) {
            filterLabel = ui.label("dropdown-filter", "");
            filterLabel.semantics(Role.TEXT, null);
        }
        typeAhead(typeAhead);
        return this;
    }

    /** Filters the items by {@code source}'s text, its element shown at the top of the list; returns this. */
    @AllowAllocation("configured once")
    public DropDown<T> filter(FilterSource source) {
        filterSource = source;
        filterWatch = source.watch();
        searchable = true;
        if (filterLabel != null) {
            ui.destroy(filterLabel);
            filterLabel = null;
        }
        return this;
    }

    /** The model. */
    public VersionedInt model() {
        return model;
    }

    /** The chosen index (-1: none). */
    public int selected() {
        return model.get();
    }

    /** The chosen item, or null. */
    public T selectedItem() {
        int i = model.get();
        return i >= 0 && i < values.size() ? values.get(i) : null;
    }

    /** Chooses item {@code index} (-1: none). */
    public void select(int index) {
        model.set(index < -1 || index >= values.size() ? -1 : index);
        show();
    }

    /** Whether the list shows. */
    public boolean isOpen() {
        return popup.isOpen();
    }

    /** The list element (on the pop-up layer while open). */
    public Element list() {
        return list;
    }

    /** Item element {@code index}. */
    public Element item(int index) {
        return itemElements[index];
    }

    /** Whether item {@code index} passes the filter. */
    public boolean shown(int index) {
        return shown[index];
    }

    /** The typed filter or type-ahead text. */
    public CharSequence typed() {
        return typed;
    }

    private void show() {
        watch.update();
        int chosen = model.get();
        ui.text(labelElement(), chosen >= 0 && chosen < texts.length ? texts[chosen] : "");
        for (int i = 0; i < itemElements.length; i++) {
            deepState(itemElements[i], UiState.SELECTED, i == chosen);
        }
    }

    // ---------------------------------------------------------------- opening

    /** Opens the list, focusing the chosen item (or the filter field). */
    public void open() {
        if (popup.isOpen() || !enabled()) {
            return;
        }
        typed.setLength(0);
        applyFilter();
        Element top = filterSource != null ? filterSource.element() : filterLabel;
        if (top != null && top.parent() != list) {
            viewport.detach();
            list.add(top).add(viewport);
        }
        if (filterLabel != null) {
            ui.text(filterLabel, typed);
        }
        popup.open(root, true);
        root.state(UiState.EXPANDED, true);
        scroll.reset();
        float w = ui.layout().width(root.slot());
        ui.visuals().minWidth(list.slot(), controls.units(w));
        int chosen = model.get();
        Element target = filterSource != null ? filterSource.element()
            : chosen >= 0 && chosen < itemElements.length && shown[chosen] ? itemElements[chosen] : firstShown();
        if (target != null) {
            controls.focusSoon(target, player);
        }
    }

    /** Closes the list; focus returns to the drop-down when it was in the list. */
    public void close() {
        if (!popup.isOpen()) {
            return;
        }
        Element f = interaction().focused(player);
        boolean inside = f != null && popup.contains(f.slot());
        popup.close();
        root.state(UiState.EXPANDED, false);
        if (inside) {
            interaction().focus(root, player);
        }
    }

    private Element firstShown() {
        for (int i = 0; i < itemElements.length; i++) {
            if (shown[i]) {
                return itemElements[i];
            }
        }
        return null;
    }

    @Override
    void clicked() {
        if (popup.isOpen()) {
            close();
        } else {
            open();
        }
    }

    @Override
    public boolean owns(int slot) {
        return controls.inside(root.slot(), slot);
    }

    @Override
    public void dismissed() {
        root.state(UiState.EXPANDED, false);
    }

    // ---------------------------------------------------------------- events

    private int indexOf(int slot) {
        for (int i = 0; i < itemElements.length; i++) {
            if (itemElements[i].slot() == slot) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() == EventType.CLICK || event.type() == EventType.COMMAND) {
            player = event.player();
        }
        int slot = event.currentSlot();
        if (slot == root.slot()) {
            if (is(event, UiCommand.DOWN) || is(event, UiCommand.UP)) {
                event.consume();
                open();
                return;
            }
            super.handle(event);
            return;
        }
        int item = indexOf(slot);
        if (item >= 0) {
            if (event.type() == EventType.CLICK) {
                event.consume();
                select(item);
                close();
            }
            return;
        }
        if (slot != list.slot()) {
            return;
        }
        switch (event.type()) {
            case WHEEL -> {
                if (scroll.wheel(event.scrollY())) {
                    event.consume();
                }
            }
            case TEXT -> typed(event);
            case COMMAND -> listCommand(event);
            default -> {
                // nothing else
            }
        }
    }

    private void listCommand(UiEvent event) {
        UiCommand c = event.command();
        if (c == UiCommand.CANCEL) {
            event.consume();
            close();
            return;
        }
        if (c == UiCommand.DELETE_BACK && typed.length() > 0) {
            event.consume();
            typed.setLength(typed.length() - 1);
            afterTyping();
            return;
        }
        Element f = interaction().focused(event.player());
        int at = f == null ? -1 : indexOf(f.slot());
        int to;
        switch (c) {
            case DOWN -> to = step(at, 1, 1);
            case UP -> to = step(at, -1, 1);
            case PAGE_DOWN -> to = step(at, 1, 10);
            case PAGE_UP -> to = step(at, -1, 10);
            case HOME -> to = step(-1, 1, 1);
            case END -> to = step(itemElements.length, -1, 1);
            default -> {
                return;
            }
        }
        event.consume();
        if (to >= 0) {
            focusItem(to, event.player());
        }
    }

    /** The shown item {@code count} shown items from {@code from} in direction {@code dir}, stopping at the ends. */
    private int step(int from, int dir, int count) {
        int found = -1;
        int left = count;
        for (int i = from + dir; i >= 0 && i < itemElements.length; i += dir) {
            if (shown[i]) {
                found = i;
                if (--left == 0) {
                    break;
                }
            }
        }
        if (found < 0 && from >= 0 && from < shown.length && shown[from]) {
            found = from;
        }
        return found;
    }

    private void focusItem(int index, int p) {
        controls.focusSoon(itemElements[index], p);
        scroll.reveal(itemElements[index]);
    }

    private void typed(UiEvent event) {
        CharSequence text = event.text();
        long now = event.time();
        if (!searchable && now - typedAt > (long) (Controls.TYPE_AHEAD_TIMEOUT.get() * 1e9f)) {
            typed.setLength(0);
        }
        typedAt = now;
        typed.append(text);
        event.consume();
        afterTyping();
    }

    private void afterTyping() {
        if (searchable && filterSource == null) {
            applyFilter();
            if (filterLabel != null) {
                ui.text(filterLabel, typed);
            }
            Element first = firstShown();
            if (first != null) {
                focusItem(indexOf(first.slot()), player);
            }
            return;
        }
        for (int i = 0; i < texts.length; i++) {
            if (shown[i] && startsWith(texts[i], typed)) {
                focusItem(i, player);
                return;
            }
        }
    }

    /** Shows the items matching the filter (all without one), in order. */
    private void applyFilter() {
        CharSequence filter = filterSource != null ? filterSource.text() : searchable ? typed : "";
        for (Element e : itemElements) {
            if (e.parent() != null) {
                e.detach();
            }
        }
        for (int i = 0; i < itemElements.length; i++) {
            shown[i] = filter.length() == 0 || contains(texts[i], filter);
            if (shown[i]) {
                items.add(itemElements[i]);
            }
        }
        scroll.reset();
    }

    static boolean startsWith(CharSequence text, CharSequence prefix) {
        if (prefix.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.toLowerCase(text.charAt(i)) != Character.toLowerCase(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean contains(CharSequence text, CharSequence part) {
        int n = part.length();
        for (int start = 0; start + n <= text.length(); start++) {
            boolean match = true;
            for (int i = 0; i < n && match; i++) {
                match = Character.toLowerCase(text.charAt(start + i)) == Character.toLowerCase(part.charAt(i));
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void poll(long nanos) {
        super.poll(nanos);
        if (watch.isChanged()) {
            show();
        }
        popup.place();
        if (filterWatch != null && filterWatch.update() && popup.isOpen()) {
            applyFilter();
        }
    }
}
