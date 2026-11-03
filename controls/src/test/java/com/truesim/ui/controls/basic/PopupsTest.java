package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.Versioned;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedInt;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drop-downs, menus, menu bars, context menus, dialogs and notifications. */
class PopupsTest {

    static final List<String> ITEMS = List.of("Low", "Medium", "High", "Ultra", "Custom", "Lowest", "Medium-low",
        "Higher", "Extreme", "Insane", "Ludicrous", "Plaid");

    @Test
    void aDropDownOpensChoosesAndCloses() {
        Fixture f = new Fixture();
        VersionedInt model = f.c.clock().intValue(2);
        DropDown<String> d = f.c.dropDown("Quality", ITEMS, String::toString, model);
        f.add(d.element()).settle();
        assertThat(f.ui.text(d.labelElement()).toString()).isEqualTo("High");
        f.click(d.element());
        assertThat(d.isOpen()).isTrue();
        assertThat(d.element().is(UiState.EXPANDED)).isTrue();
        assertThat(d.list().parent()).isEqualTo(f.ui.layer(Layers.POPUP).root());
        f.settle();
        assertThat(f.ui.layout().y(d.list().slot())).as("below the button")
            .isGreaterThanOrEqualTo(f.ui.layout().y(d.element().slot()) + f.ui.layout().height(d.element().slot()));
        assertThat(f.ui.interaction().focused(0)).isEqualTo(d.item(2));
        assertThat(d.item(2).is(UiState.SELECTED)).isTrue();
        f.click(d.item(0));
        assertThat(model.get()).isZero();
        assertThat(d.isOpen()).isFalse();
        assertThat(f.ui.text(d.labelElement()).toString()).isEqualTo("Low");

        f.focus(d.element()).press(UiCommand.DOWN);
        assertThat(d.isOpen()).isTrue();
        f.press(UiCommand.DOWN, UiCommand.DOWN, UiCommand.UP, UiCommand.PAGE_DOWN, UiCommand.PAGE_UP, UiCommand.END);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(d.item(ITEMS.size() - 1));
        f.press(UiCommand.HOME, UiCommand.DOWN, UiCommand.ACCEPT);
        assertThat(model.get()).isEqualTo(1);
        assertThat(f.ui.interaction().focused(0)).as("focus returns").isEqualTo(d.element());
        f.press(UiCommand.ACCEPT);
        assertThat(d.isOpen()).isTrue();
        f.type("ul");
        assertThat(f.ui.interaction().focused(0)).as("type-ahead").isEqualTo(d.item(3));
        f.now += 2_000_000_000L;
        f.type("h");
        assertThat(f.ui.interaction().focused(0)).as("a new search after the timeout").isEqualTo(d.item(2));
        f.synthetic.mouse(f.cx(d.item(2)), f.cy(d.item(2)), 0).scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE, 0,
            -3);
        f.run();
        f.press(UiCommand.CANCEL);
        assertThat(d.isOpen()).isFalse();
        assertThat(model.get()).isEqualTo(1);

        f.click(d.element());
        f.click(d.element());
        assertThat(d.isOpen()).as("the button toggles").isFalse();
        f.click(d.element());
        f.mouse(1000, 700, Pointer.PRIMARY);
        f.mouse(1000, 700, 0);
        assertThat(d.isOpen()).as("a press outside closes").isFalse();
        assertThat(d.element().is(UiState.EXPANDED)).isFalse();
        model.set(4);
        f.run();
        assertThat(d.selectedItem()).isEqualTo("Custom");
        d.select(99);
        assertThat(d.selected()).isEqualTo(-1);
        assertThat(d.selectedItem()).isNull();
        d.items(List.of("A", "B"));
        assertThat(d.typed()).isEmpty();
        d.bind(f.c.clock().intValue(1));
        assertThat(d.model().get()).isEqualTo(1);
        d.typeAhead(false);
        d.enabled(false);
        d.open();
        assertThat(d.isOpen()).isFalse();
        d.close();
    }

    @Test
    void aSearchableDropDownFiltersByTypedLettersOrASource() {
        Fixture f = new Fixture();
        VersionedInt model = f.c.clock().intValue(0);
        DropDown<String> d = f.c.dropDown("Quality", ITEMS, String::toString, model).searchable(true);
        f.add(d.element()).settle();
        d.open();
        f.run();
        f.type("me");
        assertThat(d.shown(1)).isTrue();
        assertThat(d.shown(0)).isFalse();
        assertThat(d.shown(6)).isTrue();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(d.item(1));
        f.press(UiCommand.DELETE_BACK);
        assertThat(d.typed().toString()).isEqualTo("m");
        f.press(UiCommand.DOWN, UiCommand.ACCEPT);
        assertThat(model.get()).as("Medium, then Custom: both have an m").isEqualTo(4);

        DropDown<String> e = f.c.dropDown("Other", ITEMS, String::toString, f.c.clock().intValue(0));
        StringBuilder text = new StringBuilder();
        long[] version = {1};
        Element field = f.ui.label(null, "search");
        e.filter(new DropDown.FilterSource() {
            @Override
            public Element element() {
                return field;
            }

            @Override
            public CharSequence text() {
                return text;
            }

            @Override
            public long version() {
                return version[0];
            }
        });
        f.add(e.element()).settle();
        e.open();
        f.run();
        assertThat(field.parent()).isEqualTo(e.list());
        text.append("x");
        version[0]++;
        f.run();
        assertThat(e.shown(8)).isTrue();
        assertThat(e.shown(0)).isFalse();
        e.close();
        Versioned unused = model;
        assertThat(unused).isNotNull();
    }

    private static Menu fileMenu(Fixture f, List<String> log, VersionedBoolean grid, VersionedInt zoom) {
        Menu m = f.c.menu("File");
        m.item("New", "new", () -> log.add("new"));
        m.separator();
        Menu recent = m.submenu("Recent");
        recent.item("forest", () -> log.add("forest"));
        recent.item("desert", () -> log.add("desert"));
        m.check("Grid", grid);
        m.radio("50%", zoom, 0);
        m.radio("100%", zoom, 1);
        m.item("Locked", () -> log.add("locked")).enabled(false);
        return m;
    }

    @Test
    void aMenuNavigatesByKeysOpensSubmenusWithHoverIntentAndActivates() {
        Fixture f = new Fixture();
        List<String> log = new ArrayList<>();
        VersionedBoolean grid = f.c.clock().booleanValue(false);
        VersionedInt zoom = f.c.clock().intValue(0);
        Menu m = fileMenu(f, log, grid, zoom);
        Button opener = f.c.button("Open menu");
        opener.onClick(() -> m.open(opener.element(), true));
        long[] glyphVersion = {1};
        f.c.glyphs(new ShortcutGlyphs() {
            @Override
            public CharSequence label(Object action) {
                return glyphVersion[0] == 1 ? "Ctrl+N" : "Start";
            }

            @Override
            public long version() {
                return glyphVersion[0];
            }
        });
        f.add(opener.element()).settle();
        f.focus(opener.element()).press(UiCommand.ACCEPT);
        assertThat(m.isOpen()).isTrue();
        f.settle();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(m.item(0).element());
        assertThat(f.ui.text(f.ui.store().element(f.ui.store().lastChild(m.item(0).element().slot()))).toString())
            .isEqualTo("Ctrl+N");
        glyphVersion[0]++;
        f.run();
        assertThat(f.ui.text(f.ui.store().element(f.ui.store().lastChild(m.item(0).element().slot()))).toString())
            .as("labels follow the device").isEqualTo("Start");
        f.press(UiCommand.DOWN);
        assertThat(f.ui.interaction().focused(0)).as("skips the separator").isEqualTo(m.item(2).element());
        f.press(UiCommand.RIGHT);
        Menu recent = m.item(2).submenu();
        assertThat(recent.isOpen()).isTrue();
        assertThat(m.openSubmenu()).isSameAs(recent);
        assertThat(m.item(2).element().is(UiState.EXPANDED)).isTrue();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(recent.item(0).element());
        f.press(UiCommand.DOWN, UiCommand.LEFT);
        assertThat(recent.isOpen()).isFalse();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(m.item(2).element());
        f.press(UiCommand.RIGHT, UiCommand.CANCEL);
        assertThat(recent.isOpen()).isFalse();
        f.press(UiCommand.ACCEPT);
        assertThat(recent.isOpen()).as("ACCEPT opens a submenu").isTrue();
        f.press(UiCommand.END, UiCommand.HOME, UiCommand.UP, UiCommand.ACCEPT);
        assertThat(log).containsExactly("desert");
        assertThat(m.isOpen()).isFalse();
        assertThat(f.ui.interaction().focused(0)).as("focus returns").isEqualTo(opener.element());

        opener.click();
        f.settle();
        f.press(UiCommand.DOWN, UiCommand.DOWN, UiCommand.ACCEPT);
        assertThat(grid.get()).isTrue();
        opener.click();
        f.settle();
        assertThat(m.item(3).element().is(UiState.CHECKED)).isTrue();
        f.click(m.item(5).element());
        assertThat(zoom.get()).isEqualTo(1);
        opener.click();
        f.settle();
        f.click(m.item(6).element());
        assertThat(m.isOpen()).as("a disabled item does nothing").isTrue();
        f.press(UiCommand.UP, UiCommand.LEFT, UiCommand.RIGHT);

        // hover intent: resting on the submenu item opens it; passing over another item on the way does not close it
        f.hover(m.item(2).element());
        assertThat(recent.isOpen()).isFalse();
        f.advance(0.3f);
        assertThat(recent.isOpen()).isTrue();
        f.hover(m.item(3).element());
        f.hover(recent.item(0).element());
        f.advance(0.3f);
        assertThat(recent.isOpen()).as("the pointer reached the submenu in time").isTrue();
        f.hover(m.item(0).element());
        f.advance(0.3f);
        assertThat(recent.isOpen()).isFalse();
        f.hover(m.item(2).element());
        f.advance(0.3f);
        f.mouse(f.cx(recent.item(1).element()), f.cy(recent.item(1).element()), Pointer.PRIMARY);
        assertThat(m.isOpen()).as("a press in the submenu keeps the menus").isTrue();
        f.mouse(f.cx(recent.item(1).element()), f.cy(recent.item(1).element()), 0);
        assertThat(log).containsExactly("desert", "desert");
        opener.click();
        f.settle();
        f.synthetic.mouse(f.cx(m.item(0).element()), f.cy(m.item(0).element()), 0)
            .scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE, 0, -1);
        f.run();
        f.mouse(1200, 780, Pointer.PRIMARY);
        f.mouse(1200, 780, 0);
        assertThat(m.isOpen()).isFalse();
        m.clear();
        assertThat(m.count()).isZero();
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void aMenuBarSwitchesMenusAndAContextMenuOpensAtThePointer() {
        Fixture f = new Fixture();
        List<String> log = new ArrayList<>();
        MenuBar bar = f.c.menuBar("Main");
        Menu file = bar.menu("File");
        file.item("New", () -> log.add("new"));
        Menu sub = file.submenu("More");
        sub.item("Deep", () -> log.add("deep"));
        Menu edit = bar.menu("Edit");
        edit.item("Undo", () -> log.add("undo"));
        Card card = f.c.card("Target", null).onClick(() -> log.add("card"));
        Element target = card.element();
        Menu context = f.c.menu("Context");
        context.item("Rename", () -> log.add("rename"));
        context.attachContext(target);
        f.add(bar.element()).add(target).settle();
        f.click(bar.title(0).element());
        assertThat(bar.openMenu()).isSameAs(file);
        assertThat(bar.title(0).element().is(UiState.EXPANDED)).isTrue();
        f.hover(bar.title(1).element());
        assertThat(bar.openMenu()).as("hovering another title switches").isSameAs(edit);
        f.settle();
        f.press(UiCommand.RIGHT);
        assertThat(bar.openMenu()).isSameAs(file);
        f.press(UiCommand.LEFT);
        assertThat(bar.openMenu()).isSameAs(edit);
        f.press(UiCommand.ACCEPT);
        assertThat(log).containsExactly("undo");
        assertThat(bar.openMenu()).isNull();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(bar.title(1).element());
        f.press(UiCommand.LEFT);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(bar.title(0).element());
        f.press(UiCommand.DOWN);
        assertThat(file.isOpen()).isTrue();
        f.press(UiCommand.DOWN, UiCommand.RIGHT, UiCommand.RIGHT);
        assertThat(edit.isOpen()).as("right past a submenu moves to the next menu").isTrue();
        f.click(bar.title(1).element());
        assertThat(bar.openMenu()).isNull();
        f.click(bar.title(0).element());
        f.mouse(1200, 780, Pointer.PRIMARY);
        f.mouse(1200, 780, 0);
        assertThat(bar.openMenu()).isNull();
        assertThat(bar.menu(0)).isSameAs(file);

        f.mouse(f.cx(target), f.cy(target), Pointer.SECONDARY);
        f.mouse(f.cx(target), f.cy(target), 0);
        assertThat(context.isOpen()).isTrue();
        f.settle();
        assertThat(f.ui.layout().x(context.element().slot()))
            .isEqualTo(f.cx(target), org.assertj.core.data.Offset.offset(1f));
        f.press(UiCommand.CANCEL);
        assertThat(context.isOpen()).isFalse();
        f.focus(target).press(UiCommand.CONTEXT_MENU);
        assertThat(context.isOpen()).as("from the keyboard, beside the element").isTrue();
        f.press(UiCommand.ACCEPT);
        assertThat(log).containsExactly("undo", "rename");
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void dialogsAreModalAndReportTheirResult() {
        Fixture f = new Fixture();
        Button behind = f.c.button("Behind");
        int[] behindClicks = new int[1];
        behind.onClick(() -> behindClicks[0]++);
        f.add(behind.element()).settle();
        f.focus(behind.element());
        int[] result = {-99};
        Dialog d = f.c.confirm("Quit?", "Unsaved changes.", "Quit", "Stay").onClose(r -> result[0] = r);
        d.show();
        d.show();
        assertThat(d.showing()).isTrue();
        f.run();
        assertThat(f.ui.interaction().focused(0)).as("the default button, from the next frame's input on")
            .isEqualTo(d.button(1).element());
        f.run();
        assertThat(d.element().is(UiState.EXPANDED)).isTrue();
        f.click(behind.element());
        assertThat(behindClicks[0]).as("the backdrop blocks").isZero();
        f.press(UiCommand.NEXT, UiCommand.NEXT, UiCommand.NEXT);
        assertThat(f.ui.layers()).isNotEmpty();
        assertThat(f.ui.interaction().focused(0).parent()).as("focus stays inside")
            .isEqualTo(d.button(0).element().parent());
        f.press(UiCommand.CANCEL);
        assertThat(result[0]).isZero();
        assertThat(d.result()).isZero();
        assertThat(d.showing()).as("fading out").isTrue();
        f.advance(0.5f);
        assertThat(d.showing()).isFalse();
        assertThat(d.backdrop().parent()).isNull();
        assertThat(f.ui.interaction().focused(0)).as("focus returns").isEqualTo(behind.element());
        d.close(5);

        Dialog m = f.c.message("Saved", "All good.", "OK");
        m.show();
        f.press(UiCommand.ACCEPT);
        assertThat(m.result()).isZero();
        m.show();
        f.run();
        m.close(3);
        m.show();
        assertThat(m.showing()).as("shown again while fading out").isTrue();
        f.press(UiCommand.CANCEL);
        assertThat(m.result()).isZero();
        assertThat(m.messageLabel()).isNotNull();
        assertThat(m.titleLabel()).isNotNull();
        f.advance(0.5f);
        Dialog plain = new Dialog(f.c, "Plain", "No buttons");
        plain.button("Extra", 7);
        plain.show();
        f.run();
        f.press(UiCommand.CANCEL);
        assertThat(plain.result()).isEqualTo(Dialog.CANCELLED);
    }

    @Test
    void notificationsTimeOutPauseOnHoverAndStack() {
        Fixture f = new Fixture();
        Toasts toasts = f.c.toasts();
        assertThat(f.c.toasts()).isSameAs(toasts);
        assertThat(toasts.stack()).isNull();
        int[] dismissed = new int[1];
        Toasts.Toast a = toasts.show("Saved", "The level was saved.").onDismiss(() -> dismissed[0]++);
        f.settle();
        assertThat(a.element().is(UiState.EXPANDED)).isTrue();
        assertThat(toasts.stack().parent()).isEqualTo(f.ui.layerStack().find("notifications").root());
        f.hover(a.element());
        f.advance(Controls.TOAST_DURATION.get() + 1);
        assertThat(a.dismissed()).as("paused under the pointer").isFalse();
        f.mouse(5, 5, 0);
        f.advance(Controls.TOAST_DURATION.get() + 0.5f);
        assertThat(a.dismissed()).isTrue();
        assertThat(dismissed[0]).isEqualTo(1);
        f.advance(0.5f);
        assertThat(toasts.count()).isZero();
        Toasts.Toast sticky = toasts.show(null, "Stays", 0);
        f.advance(10);
        assertThat(sticky.dismissed()).isFalse();
        f.click(sticky.closeButton().element());
        assertThat(sticky.dismissed()).isTrue();
        sticky.dismiss();
        for (int i = 0; i < 6; i++) {
            toasts.show("n" + i, "m", 0);
        }
        f.run();
        int live = 0;
        for (int i = 0; i < toasts.count(); i++) {
            live += toasts.toast(i).dismissed() ? 0 : 1;
        }
        assertThat(live).isEqualTo((int) Controls.TOAST_LIMIT.get());
        f.ui.update(f.width, f.height);
        assertThat(f.unnamed()).isEmpty();
    }
}
