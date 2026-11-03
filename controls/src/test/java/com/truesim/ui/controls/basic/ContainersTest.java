package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedInt;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Toolbars, status bars, breadcrumbs, tabs, collapsibles, groups, cards, split panes, carousels, steppers, drawers. */
class ContainersTest {

    static Element box(Fixture f, String text, float w, float h) {
        Element e = f.ui.panel(null);
        f.ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w))
            .set(Styles.HEIGHT, Length.px(h)).build());
        e.add(f.ui.label(null, text));
        return e;
    }

    static void width(Fixture f, Element e, float w) {
        f.ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w)).build());
    }

    @Test
    void aToolbarMovesWhatDoesNotFitIntoItsMenu() {
        Fixture f = new Fixture();
        Toolbar t = f.c.toolbar("Tools", "More");
        List<String> log = new ArrayList<>();
        for (String name : new String[] {"Cut", "Copy", "Paste", "Delete", "Rename"}) {
            Button b = f.c.button(name).onClick(() -> log.add(name));
            t.add(b, name);
        }
        t.separator();
        width(f, t.element(), 600);
        f.add(t.element()).settle();
        assertThat(t.fitting()).isEqualTo(6);
        assertThat(t.overflowButton().element().parent()).isNull();
        width(f, t.element(), 170);
        f.settle();
        assertThat(t.fitting()).isLessThan(6).isPositive();
        assertThat(t.overflowButton().element().parent()).isEqualTo(t.element());
        f.click(t.overflowButton().element());
        assertThat(t.overflowMenu().isOpen()).isTrue();
        f.settle();
        f.click(t.overflowMenu().item(t.overflowMenu().count() - 1).element());
        assertThat(log).containsExactly("Rename");
        f.click(t.overflowButton().element());
        f.click(t.overflowButton().element());
        assertThat(t.overflowMenu().isOpen()).isFalse();
        width(f, t.element(), 600);
        f.settle();
        assertThat(t.fitting()).isEqualTo(6);
    }

    @Test
    void statusBarsAndBreadcrumbs() {
        Fixture f = new Fixture();
        StatusBar s = f.c.statusBar("Status");
        int ready = s.section("Ready");
        s.section(f.c.badge("3", "Errors").element());
        s.text(ready, "Saving");
        Breadcrumbs b = f.c.breadcrumbs("Location");
        int[] chosen = {-1};
        b.onChosen(i -> chosen[0] = i);
        b.path(List.of("Home", "Levels", "Forest"));
        f.add(s.element()).add(b.element()).settle();
        assertThat(f.ui.text(s.section(ready)).toString()).isEqualTo("Saving");
        assertThat(b.steps()).isEqualTo(3);
        assertThat(b.crumb(2).element().is(UiState.SELECTED)).isTrue();
        f.click(b.crumb(1).element());
        assertThat(chosen[0]).isEqualTo(1);
        b.path(List.of("Home"));
        f.settle();
        assertThat(b.steps()).isEqualTo(1);
        b.onChosen(null);
        f.click(b.crumb(0).element());
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void tabsSelectCloseReorderAndOverflow() {
        Fixture f = new Fixture();
        VersionedInt model = f.c.clock().intValue(0);
        TabPane t = f.c.tabs("Panels", TabPane.Side.TOP, model).reorderable(true);
        Element a = box(f, "A", 100, 30);
        Element b = box(f, "B", 100, 30);
        Element c = box(f, "C", 100, 30);
        t.add("Alpha", a, false);
        t.add("Beta", b, true);
        TabPane.Tab gamma = t.add("Gamma", c, true);
        width(f, t.element(), 600);
        f.add(t.element()).settle();
        assertThat(a.parent()).isNotNull();
        assertThat(b.parent()).isNull();
        f.click(t.tab(1).button().element());
        assertThat(model.get()).isEqualTo(1);
        assertThat(b.parent()).isNotNull();
        assertThat(a.parent()).isNull();
        f.focus(t.tab(1).button().element()).press(UiCommand.RIGHT);
        assertThat(t.selected()).isEqualTo(2);
        f.press(UiCommand.RIGHT, UiCommand.HOME, UiCommand.END, UiCommand.LEFT, UiCommand.UP);
        assertThat(t.selected()).isEqualTo(1);
        model.set(0);
        f.run();
        assertThat(a.parent()).isNotNull();

        // reorder: drag Alpha past Gamma
        Element alpha = t.tab(0).button().element();
        float y = f.cy(alpha);
        f.mouse(f.cx(alpha), y, Pointer.PRIMARY);
        float end = f.cx(gamma.button().element()) + 30;
        for (float x = f.cx(alpha); x <= end; x += 20) {
            f.mouse(x, y, Pointer.PRIMARY);
        }
        assertThat(alpha.is(UiState.DRAGGING)).isTrue();
        f.mouse(end, y, 0);
        assertThat(t.tab(2).button().element()).isEqualTo(alpha);
        assertThat(t.selected()).as("the selection follows its tab").isEqualTo(2);
        assertThat(alpha.is(UiState.DRAGGING)).isFalse();

        // close: Beta is now first
        boolean[] allow = {false};
        t.onClose(i -> allow[0]);
        f.settle();
        f.click(t.tab(0).closeButton().element());
        assertThat(t.count()).isEqualTo(3);
        allow[0] = true;
        f.click(t.tab(0).closeButton().element());
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.selected()).isEqualTo(1);
        assertThat(t.indexOf(gamma)).isZero();
        assertThat(t.tab(1).content()).isEqualTo(a);
        t.remove(1);
        assertThat(t.selected()).isZero();
        t.select(9);
        assertThat(t.selected()).isZero();

        // overflow
        for (int i = 0; i < 8; i++) {
            t.add("Tab number " + i, box(f, "x", 50, 20), false);
        }
        f.ui.styles().inline(t.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(300))
            .set(BasicStyles.TAB_OVERFLOW, BasicStyles.TabOverflow.SCROLL).build());
        f.settle();
        assertThat(t.overflowButton().element().parent()).as("scroll mode: the menu is offered").isNotNull();
        assertThat(t.overflowing(7)).as("scroll mode: tabs out of view are clipped, not hidden").isFalse();
        f.synthetic.mouse(f.cx(t.tab(1).button().element()), y, 0).scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE,
            0, -2);
        f.run();
        assertThat(t.stripScroll()).isPositive();
        f.click(t.overflowButton().element());
        assertThat(t.overflowMenu().isOpen()).isTrue();
        f.settle();
        f.click(t.overflowMenu().item(8).element());
        assertThat(t.selected()).isEqualTo(8);
        f.click(t.overflowButton().element());
        f.click(t.overflowButton().element());

        // menu mode: only whole tabs show, the selected one always; the rest are in the menu
        f.ui.styles().inline(t.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(300))
            .set(BasicStyles.TAB_OVERFLOW, BasicStyles.TabOverflow.MENU).build());
        f.settle();
        assertThat(t.overflowButton().element().parent()).as("menu mode: the menu is offered").isNotNull();
        assertThat(t.overflowing(8)).as("the selected tab shows").isFalse();
        int hidden = 0;
        for (int i = 0; i < t.count(); i++) {
            hidden += t.overflowing(i) ? 1 : 0;
        }
        assertThat(hidden).isPositive();
        f.click(t.tab(0).button().element()); // hidden: not picked
        assertThat(t.selected()).isEqualTo(8);
        f.click(t.overflowButton().element());
        f.settle();
        f.click(t.overflowMenu().item(0).element());
        assertThat(t.selected()).isZero();
        f.settle();
        assertThat(t.overflowing(0)).isFalse();
        assertThat(t.overflowing(8)).as("revealing the first tab hid the last").isTrue();
        t.bind(f.c.clock().intValue(0));
        assertThat(t.model().get()).isZero();
        width(f, t.element(), 300); // the theme's mode again
        f.ui.styles().sheet(Fixture.GARISH);
        f.settle();
        assertThat(t.overflowButton().element().parent()).as("wrap mode: no menu").isNull();
        assertThat(f.unnamed()).isEmpty();
        TabPane side = new TabPane(f.c, "Side", TabPane.Side.LEFT, f.c.clock().intValue(-1));
        side.add("One", box(f, "1", 20, 20), false);
        f.add(side.element()).settle();
        f.focus(side.tab(0).button().element()).press(UiCommand.DOWN);
        assertThat(side.selected()).isZero();
    }

    @Test
    void collapsiblesAnimateTheirHeightAndReverse() {
        Fixture f = new Fixture();
        VersionedBoolean open = f.c.clock().booleanValue(false);
        Element content = box(f, "Body", 120, 80);
        Collapsible c = f.c.collapsible("Details", content, open);
        f.add(c.element()).settle();
        assertThat(content.parent()).as("closed content is out of the tree").isNull();
        assertThat(f.ui.layout().height(c.body().slot())).isZero();
        f.click(c.header().element());
        assertThat(open.get()).isTrue();
        assertThat(c.element().is(UiState.EXPANDED)).isTrue();
        assertThat(c.header().element().is(UiState.EXPANDED)).isTrue();
        f.run(5);
        float mid = f.ui.layout().height(c.body().slot());
        assertThat(mid).isBetween(1f, 79f);
        assertThat(c.animating()).isTrue();
        c.toggle();
        f.run();
        assertThat(f.ui.layout().height(c.body().slot())).as("reverses from where it was").isLessThan(mid);
        c.expanded(true);
        f.advance(0.5f);
        assertThat(c.animating()).isFalse();
        assertThat(f.ui.layout().height(c.body().slot())).isEqualTo(80);
        assertThat(f.ui.visuals().has(c.body().slot(), Visuals.HEIGHT)).as("follows its content").isFalse();
        open.set(false);
        f.advance(0.5f);
        assertThat(content.parent()).isNull();
        assertThat(c.expanded()).isFalse();
        assertThat(c.content()).isSameAs(content);
        assertThat(c.model()).isSameAs(open);

        Collapsible expander = f.c.expander("More", box(f, "m", 10, 10), f.c.clock().booleanValue(true));
        f.add(expander.element()).settle();
        assertThat(f.ui.store().type(expander.element().slot())).isEqualTo("expander");
        assertThat(expander.content().parent()).isNotNull();

        Accordion single = f.c.accordion("Sections", true);
        Collapsible one = single.add("One", box(f, "1", 10, 10), true);
        Collapsible two = single.add("Two", box(f, "2", 10, 10), false);
        f.add(single.element()).settle();
        f.click(two.header().element());
        assertThat(one.expanded()).isFalse();
        assertThat(single.single()).isTrue();
        assertThat(single.count()).isEqualTo(2);
        assertThat(single.section(1)).isSameAs(two);
        Accordion multi = f.c.accordion("Multi", false);
        Collapsible m1 = multi.add("A", box(f, "a", 10, 10), true);
        Collapsible m2 = multi.add("B", box(f, "b", 10, 10), true);
        assertThat(m1.expanded() && m2.expanded()).isTrue();
    }

    @Test
    void groupsAndCards() {
        Fixture f = new Fixture();
        GroupBox g = f.c.groupBox("Audio");
        Button inside = f.c.button("Test");
        g.add(inside.element());
        Card card = f.c.card("Forest", "Level 3").add(f.ui.label(null, "Quiet")).action(f.c.button("Play"));
        int[] clicks = new int[1];
        card.onClick(() -> clicks[0]++).onClick(() -> clicks[0]++);
        Card bare = f.c.card(null, null);
        f.add(g.element()).add(card.element()).add(bare.element()).settle();
        g.enabled(false);
        f.click(inside.element());
        assertThat(inside.clicks()).as("disabled with its group").isZero();
        assertThat(g.title()).isNotNull();
        assertThat(g.content()).isNotNull();
        f.click(card.header());
        assertThat(clicks[0]).isEqualTo(2);
        assertThat(card.body()).isNotNull();
        assertThat(card.footer()).isNotNull();
        assertThat(bare.footer()).isNull();
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void aSplitPaneDragsCollapsesAndKeepsItsMinimums() {
        Fixture f = new Fixture();
        VersionedDouble share = f.c.clock().doubleValue(0.5);
        SplitPane s = f.c.split("Editor", false, share);
        f.ui.styles().inline(s.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(404))
            .set(Styles.HEIGHT, Length.px(100)).build());
        f.add(s.element()).settle();
        assertThat(f.ui.layout().width(s.first().slot())).isEqualTo(202);
        Element splitter = s.splitter();
        float y = f.cy(splitter);
        f.mouse(f.cx(splitter), y, Pointer.PRIMARY);
        assertThat(splitter.is(UiState.PRESSED)).isTrue();
        f.mouse(f.cx(splitter) + 50, y, Pointer.PRIMARY);
        f.mouse(f.cx(splitter), y, 0);
        assertThat(share.get()).isEqualTo(252 / 404.0, offset(0.01));
        f.mouse(f.cx(splitter), y, Pointer.PRIMARY);
        f.mouse(390, y, Pointer.PRIMARY);
        f.mouse(390, y, 0);
        assertThat(f.ui.layout().width(s.second().slot())).as("the second pane's minimum").isGreaterThanOrEqualTo(39);
        f.focus(splitter).press(UiCommand.LEFT, UiCommand.LEFT);
        double before = share.get();
        f.press(UiCommand.RIGHT);
        assertThat(share.get()).isGreaterThan(before);
        f.press(UiCommand.HOME);
        assertThat(s.collapsed()).isTrue();
        assertThat(share.get()).isZero();
        f.press(UiCommand.ACCEPT);
        assertThat(s.collapsed()).isFalse();
        f.press(UiCommand.END);
        assertThat(share.get()).isEqualTo(1);
        f.settle();
        f.click(splitter);
        assertThat(f.ui.layout().width(splitter.slot())).as("the splitter keeps its width").isEqualTo(4);
        f.click(splitter);
        assertThat(s.collapsed()).as("a double click restores").isFalse();
        share.set(0.3);
        f.run();
        assertThat(s.share()).isEqualTo(0.3);
        f.press(UiCommand.PAGE_UP);
        SplitPane v = new SplitPane(f.c, "Vertical", true, f.c.clock().doubleValue(0.5));
        f.ui.styles().inline(v.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(100))
            .set(Styles.HEIGHT, Length.px(204)).build());
        f.add(v.element()).settle();
        f.focus(v.splitter()).press(UiCommand.DOWN, UiCommand.UP, UiCommand.UP);
        assertThat(v.share()).isLessThan(0.5);
        f.mouse(f.cx(v.splitter()), f.cy(v.splitter()), Pointer.PRIMARY);
        f.mouse(f.cx(v.splitter()), f.cy(v.splitter()) + 20, Pointer.PRIMARY);
        f.mouse(f.cx(v.splitter()), f.cy(v.splitter()), 0);
        v.bind(f.c.clock().doubleValue(0.6));
        assertThat(v.model().get()).isEqualTo(0.6);
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void aCarouselTurnsBySwipeKeysButtonsAndDots() {
        Fixture f = new Fixture();
        VersionedInt page = f.c.clock().intValue(0);
        Carousel c = f.c.carousel("Shots", page);
        for (int i = 0; i < 3; i++) {
            c.add(box(f, "P" + i, 50, 50));
        }
        width(f, c.element(), 300);
        f.add(c.element()).settle();
        assertThat(c.count()).isEqualTo(3);
        assertThat(c.previousButton().element().is(UiState.DISABLED)).isTrue();
        f.click(c.nextButton().element());
        assertThat(page.get()).isEqualTo(1);
        f.run(3);
        assertThat(c.position()).as("animating").isBetween(0.01f, 0.99f);
        f.advance(0.5f);
        assertThat(c.position()).isEqualTo(1);
        float vx = f.ui.layout().x(c.viewport().slot());
        assertThat(f.ui.layout().x(c.pageElement(1).slot())).isEqualTo(vx, offset(1f));
        float y = f.cy(c.viewport());
        float x = f.cx(c.viewport());
        f.mouse(x, y, Pointer.PRIMARY);
        for (int i = 1; i <= 10; i++) {
            f.mouse(x - i * 12, y, Pointer.PRIMARY);
        }
        f.mouse(x - 120, y, 0);
        assertThat(page.get()).as("a swipe left turns forward").isEqualTo(2);
        f.focus(c.element()).press(UiCommand.LEFT);
        assertThat(page.get()).isEqualTo(1);
        f.press(UiCommand.HOME, UiCommand.END, UiCommand.RIGHT, UiCommand.UP);
        assertThat(page.get()).isEqualTo(2);
        f.click(c.dot(0).element());
        assertThat(c.page()).isZero();
        assertThat(c.dot(0).element().is(UiState.SELECTED)).isTrue();
        page.set(2);
        f.run();
        f.advance(0.5f);
        f.mouse(x, y, Pointer.PRIMARY);
        f.mouse(x + 8, y, Pointer.PRIMARY);
        f.mouse(x + 20, y, Pointer.PRIMARY);
        f.mouse(x + 20, y, 0);
        assertThat(page.get()).as("a flick right turns back").isEqualTo(1);
        f.ui.styles().sheet(Fixture.DEFAULT);
        c.turn(0);
        assertThat(c.model()).isSameAs(page);
        Carousel empty = f.c.carousel("Empty", f.c.clock().intValue(0));
        empty.turn(1);
        assertThat(empty.page()).isZero();
    }

    @Test
    void aStepperGuardsAndFinishes() {
        Fixture f = new Fixture();
        VersionedInt step = f.c.clock().intValue(0);
        Stepper s = f.c.stepper("Setup", "Back", "Next", "Finish", step);
        Element a = box(f, "A", 20, 20);
        Element b = box(f, "B", 20, 20);
        Element c = box(f, "C", 20, 20);
        s.add("One", a).add("Two", b).add("Three", c);
        boolean[] ok = {false};
        int[] finished = new int[1];
        s.guard(i -> ok[0]).onFinish(() -> finished[0]++);
        f.add(s.element()).settle();
        assertThat(s.backButton().element().is(UiState.DISABLED)).isTrue();
        f.click(s.nextButton().element());
        assertThat(step.get()).as("the guard keeps it").isZero();
        ok[0] = true;
        f.click(s.nextButton().element());
        assertThat(step.get()).isEqualTo(1);
        assertThat(b.parent()).isNotNull();
        assertThat(a.parent()).isNull();
        assertThat(s.stepButton(0).element().is(UiState.CHECKED)).isTrue();
        assertThat(s.stepButton(2).element().is(UiState.DISABLED)).isTrue();
        f.click(s.nextButton().element());
        assertThat(f.ui.text(s.nextButton().labelElement()).toString()).isEqualTo("Finish");
        f.click(s.nextButton().element());
        assertThat(finished[0]).isEqualTo(1);
        f.click(s.stepButton(0).element());
        assertThat(s.step()).isZero();
        assertThat(s.reached()).isEqualTo(2);
        f.click(s.stepButton(2).element());
        assertThat(s.step()).isEqualTo(2);
        f.click(s.backButton().element());
        assertThat(s.step()).isEqualTo(1);
        step.set(0);
        f.run();
        assertThat(a.parent()).isNotNull();
        s.go(7);
        assertThat(s.step()).isEqualTo(2);
        s.onFinish(null).forward();
        Stepper empty = f.c.stepper("E", "<", ">", "!", f.c.clock().intValue(0));
        empty.go(1);
        assertThat(empty.model().get()).isZero();
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void drawersSlideInModalOrPushTheContent() {
        Fixture f = new Fixture();
        Button behind = f.c.button("Behind");
        f.add(behind.element()).settle();
        f.focus(behind.element());
        VersionedBoolean open = f.c.clock().booleanValue(false);
        Drawer d = f.c.drawer("Settings", Drawer.Side.RIGHT, true, open);
        d.content().add(f.c.button("Inside").element());
        assertThat(d.isOpen()).isFalse();
        d.open(true);
        assertThat(d.backdrop().parent()).isNotNull();
        f.run(4);
        assertThat(d.openness()).isBetween(0.01f, 0.99f);
        f.advance(0.5f);
        assertThat(d.openness()).isEqualTo(1);
        float right = f.ui.layout().x(d.element().slot()) + f.ui.layout().width(d.element().slot());
        assertThat(right).isEqualTo(f.width, offset(1f));
        f.press(UiCommand.CANCEL);
        assertThat(open.get()).isFalse();
        f.advance(0.5f);
        assertThat(d.backdrop().parent()).isNull();
        open.set(true);
        f.run();
        f.advance(0.5f);
        f.click(d.backdrop());
        assertThat(d.isOpen()).isFalse();
        f.advance(0.5f);
        for (Drawer.Side side : Drawer.Side.values()) {
            Drawer m = f.c.drawer("Side", side, true, f.c.clock().booleanValue(true));
            f.run();
            m.toggle();
            f.advance(0.5f);
        }

        Element row = f.ui.panel(null);
        f.ui.styles().inline(row.slot(), InlineStyle.builder().set(Styles.LAYOUT,
            com.truesim.ui.style.value.LayoutKind.FLEX).set(Styles.DIRECTION, com.truesim.ui.style.value.Direction.ROW)
            .set(Styles.HEIGHT, Length.px(100)).build());
        VersionedBoolean shown = f.c.clock().booleanValue(false);
        Drawer push = f.c.drawer("Sidebar", Drawer.Side.LEFT, false, shown);
        Element main = box(f, "Main", 100, 100);
        row.add(push.element()).add(main);
        f.add(row).settle();
        assertThat(f.ui.layout().width(push.element().slot())).as("its border only").isLessThanOrEqualTo(2);
        assertThat(push.content().parent()).isNull();
        push.toggle();
        f.advance(0.5f);
        assertThat(f.ui.layout().width(push.element().slot())).isEqualTo(260);
        assertThat(f.ui.layout().x(main.slot())).as("pushed").isGreaterThanOrEqualTo(260);
        push.open(false);
        f.advance(0.5f);
        assertThat(push.content().parent()).isNull();
        Drawer top = f.c.drawer("Top", Drawer.Side.TOP, false, f.c.clock().booleanValue(true));
        f.add(top.element()).settle();
        top.open(false);
        f.advance(0.5f);
        assertThat(f.ui.layout().height(top.element().slot())).isLessThanOrEqualTo(2);
        assertThat(push.model()).isSameAs(shown);
    }
}
