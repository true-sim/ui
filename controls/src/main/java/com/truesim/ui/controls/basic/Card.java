package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import java.util.Arrays;

/**
 * A card (type {@code card}): a {@code card-header} (title label of style name {@code card-title}, optional subtitle
 * of {@code card-subtitle}), a {@code card-body} and a {@code card-footer} for actions (added when the first action
 * is). With {@link #onClick} the whole card is a focusable, clickable surface. Announced as a group named by its title.
 */
public final class Card extends Control {

    private final Element header;
    private final Element body;
    private Element footer;
    private Runnable[] actions = new Runnable[1];
    private int actionCount;

    /** A card titled {@code title} (null: no header) with {@code subtitle} (null: none). */
    @AllowAllocation("controls are built once")
    public Card(Controls controls, CharSequence title, CharSequence subtitle) {
        super(controls, "card", Role.GROUP, title == null ? null : title.toString());
        header = part("card-header");
        if (title != null) {
            Element t = ui.label("card-title", title);
            t.semantics(Role.NONE, null);
            header.add(t);
            if (subtitle != null) {
                header.add(ui.label("card-subtitle", subtitle));
            }
            root.add(header);
        }
        body = part("card-body");
        root.add(body);
    }

    /** Adds {@code child} to the body; returns this. */
    public Card add(Element child) {
        body.add(child);
        return this;
    }

    /** Adds an action button to the footer; returns this. */
    @AllowAllocation("controls are built once")
    public Card action(Button button) {
        if (footer == null) {
            footer = part("card-footer");
            root.add(footer);
        }
        footer.add(button.element());
        return this;
    }

    /** Makes the card clickable (focusable, ACCEPT clicks it) running {@code action}; returns this. */
    @AllowAllocation("controls are built once")
    public Card onClick(Runnable action) {
        if (actionCount == 0) {
            focusable(root);
            listen(root, EventType.CLICK.bit());
        }
        if (actionCount == actions.length) {
            actions = Arrays.copyOf(actions, actionCount * 2);
        }
        actions[actionCount++] = action;
        return this;
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() == EventType.CLICK && event.currentSlot() == root.slot()) {
            event.consume();
            for (int i = 0; i < actionCount; i++) {
                actions[i].run();
            }
        }
    }

    /** The header. */
    public Element header() {
        return header;
    }

    /** The body. */
    public Element body() {
        return body;
    }

    /** The footer, or null before the first action. */
    public Element footer() {
        return footer;
    }
}
