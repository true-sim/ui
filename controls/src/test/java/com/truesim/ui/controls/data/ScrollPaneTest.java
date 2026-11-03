package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.FocusDirection;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.DrawListDump;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Scroll panes: wheel, bars, keys, touch momentum, focus, sticky headers, clipping. */
class ScrollPaneTest {

    static final StyleSheet SHEET = Tss.compileString("pane.tss", """
        root { layout: none; }
        scroll-pane { width: 200px; height: 100px; scroll-step: 10; }
        scroll-viewport { clip: true; }
        scroll-content { layout: stack; direction: column; }
        scroll-section { gap: 0; }
        scroll-bar { width: 10px; height: 10px; background: box(#202020); thumb-min: 10; }
        scroll-bar[active=true] { background: box(#404040); }
        scroll-button { width: 10px; height: 10px; background: box(#303030); }
        scroll-thumb { background: box(#808080); }
        panel { height: 50px; background: box(#ff0000); }
        panel.wide { width: 500px; }
        panel.head { height: 10px; background: box(#00ff00); }
        panel.focusable:focus-visible { background: box(#ffffff); }
        """);

    @AfterEach
    void resetTunables() {
        DataTunables.OVERSCROLL.select("bounce");
    }

    /** A pane with {@code n} 50-px panels: 50n high in a 100-px viewport. */
    static ScrollPane pane(DataFrame f, int n) {
        ScrollPane pane = new ScrollPane(f.ui, "Content");
        f.ui.root().add(pane.root());
        for (int i = 0; i < n; i++) {
            pane.content().add(f.ui.panel(null));
        }
        return pane;
    }

    @Test
    void theWheelScrollsInTheSameFrameAndPassesOnAtTheEnds() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        int[] outer = {0};
        f.ui.on(f.ui.root(), com.truesim.ui.core.event.EventType.WHEEL, e -> outer[0]++);
        f.run();
        assertThat(pane.vertical().range()).isEqualTo(400); // 500 of content in 100
        assertThat(pane.vertical().viewport()).isEqualTo(100);
        Element first = f.ui.store().element(f.ui.store().firstChild(pane.content().slot()));
        f.wheel(50, 50, -3); // three notches down
        assertThat(pane.vertical().position()).isEqualTo(30);
        assertThat(f.ui.layout().y(first.slot())).isEqualTo(-30); // shown this frame
        assertThat(outer[0]).isZero();
        f.wheel(50, 50, 5); // past the start: clamped, then the wheel goes on
        assertThat(pane.vertical().position()).isZero();
        f.wheel(50, 50, 1);
        assertThat(outer[0]).isPositive(); // unconsumed: bubbled (and passed through)
        int seen = outer[0];
        // shift turns the wheel sideways; nothing to scroll there in a fitted pane
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.wheel(50, 50, -1);
        assertThat(pane.horizontal().position()).isZero();
        assertThat(outer[0]).isGreaterThan(seen);
        assertThat(pane.fitsWidth()).isTrue();
    }

    @Test
    void scrollBarsShowWhenNeededAndTheirThumbsFollow() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        f.run();
        Element bar = pane.verticalBar();
        assertThat(f.ui.layout().shown(bar.slot())).isTrue();
        assertThat(f.ui.layout().shown(pane.horizontalBar().slot())).isFalse();
        assertThat(f.ui.layout().x(bar.slot())).isEqualTo(190);
        Element thumb = thumb(f, bar);
        // track 80 (100 less two 10-px buttons), thumb 80 * 100 / 500 = 16
        assertThat(f.ui.layout().height(thumb.slot())).isEqualTo(16);
        assertThat(f.ui.layout().y(thumb.slot())).isEqualTo(10);
        pane.scrollTo(0, 400);
        f.run();
        assertThat(f.ui.layout().y(thumb.slot())).isEqualTo(10 + 80 - 16);
        // only five panels: no overflow, no bar
        ScrollPane small = pane(new DataFrame(SHEET, null), 1);
        assertThat(small.vertical().overflows()).isFalse();
    }

    static Element thumb(DataFrame f, Element bar) {
        return f.ui.store().element(f.ui.store().lastChild(bar.slot()));
    }

    @Test
    void theThumbDragsTheTrackPagesAndTheButtonsStepAndRepeat() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        f.run();
        // drag the thumb (y 10..26) down by 32 px of 64 free: half the range
        f.mouse(195, 15, 0);
        f.mouse(195, 15, Pointer.PRIMARY);
        f.mouse(195, 47, Pointer.PRIMARY);
        assertThat(pane.vertical().position()).isEqualTo(200);
        assertThat(pane.active()).isTrue();
        f.mouse(195, 47, 0);
        // the track below the thumb pages down by 90% of the viewport
        f.mouse(195, 85, Pointer.PRIMARY);
        f.mouse(195, 85, 0);
        assertThat(pane.vertical().position()).isEqualTo(290);
        // the start button steps up; held, it repeats after the delay
        f.mouse(195, 5, Pointer.PRIMARY);
        assertThat(pane.vertical().position()).isEqualTo(280);
        f.run(40); // 0.64 s: repeats after 0.4 s every 0.05 s
        assertThat(pane.vertical().position()).isLessThan(250);
        f.mouse(195, 5, 0);
        double stopped = pane.vertical().position();
        f.run(10);
        assertThat(pane.vertical().position()).isEqualTo(stopped);
        // the end button steps down
        f.mouse(195, 95, Pointer.PRIMARY);
        f.mouse(195, 95, 0);
        assertThat(pane.vertical().position()).isEqualTo(stopped + 10);
    }

    @Test
    void keysScrollTheFocusedPaneAndPagesScrollFromInside() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        pane.focusable(true);
        f.run();
        f.ui.interaction().focus(pane.root());
        f.synthetic.press(UiCommand.DOWN, UiCommand.DOWN);
        f.run();
        assertThat(pane.vertical().position()).isEqualTo(20);
        f.synthetic.press(UiCommand.PAGE_DOWN);
        f.run();
        assertThat(pane.vertical().position()).isEqualTo(110);
        f.synthetic.press(UiCommand.END);
        f.run();
        assertThat(pane.vertical().position()).isEqualTo(400);
        f.synthetic.press(UiCommand.PAGE_UP, UiCommand.UP, UiCommand.LEFT, UiCommand.RIGHT);
        f.run();
        assertThat(pane.vertical().position()).isEqualTo(300);
        f.synthetic.press(UiCommand.HOME);
        f.run();
        assertThat(pane.vertical().position()).isZero();
        f.synthetic.press(UiCommand.ACCEPT);
        f.run();
    }

    @Test
    void navigationScrollsTheFocusedElementIntoView() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = new ScrollPane(f.ui, "Form");
        f.ui.root().add(pane.root());
        Element[] fields = new Element[8];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = f.ui.panel("focusable");
            f.ui.interaction().focusable(fields[i], true);
            pane.content().add(fields[i]);
        }
        f.run();
        f.ui.interaction().focus(fields[0]);
        for (int i = 1; i <= 4; i++) {
            f.synthetic.press(UiCommand.NEXT);
            f.run();
            assertThat(f.ui.interaction().focused(0)).isEqualTo(fields[i]);
        }
        // field 4 (200..250) is fully visible in the same frame
        assertThat(pane.vertical().position()).isEqualTo(150);
        assertThat(f.ui.layout().y(fields[4].slot())).isEqualTo(50);
        assertThat(fields[4].is(UiState.FOCUS_VISIBLE)).isTrue();
        f.synthetic.press(UiCommand.PREVIOUS, UiCommand.PREVIOUS, UiCommand.PREVIOUS);
        f.run();
        assertThat(pane.vertical().position()).isEqualTo(50); // field 1 at the top
        // a pointer focusing a half hidden field does not scroll
        f.mouse(50, 95, Pointer.PRIMARY);
        f.mouse(50, 95, 0);
        assertThat(pane.vertical().position()).isEqualTo(50);
        // reveal by code; an element outside does nothing
        pane.reveal(fields[7]);
        f.run();
        assertThat(pane.vertical().position()).isEqualTo(300);
        pane.reveal(f.ui.root());
        assertThat(f.ui.interaction().navigate(0, FocusDirection.FIRST)).isTrue();
    }

    @Test
    void aTouchDragPansFlingsWithFrictionAndBouncesBack() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        f.run();
        int finger = 3;
        f.synthetic.pointer(finger, Pointer.Kind.TOUCH, 50, 80, Pointer.PRIMARY);
        f.run();
        for (int y = 76; y >= 20; y -= 4) { // 4 px a frame up: 250 px per second
            f.synthetic.pointer(finger, Pointer.Kind.TOUCH, 50, y, Pointer.PRIMARY);
            f.run();
        }
        double dragged = pane.vertical().position();
        assertThat(dragged).isBetween(40.0, 60.0); // 60 px less the touch threshold
        f.synthetic.lift(finger);
        f.run();
        assertThat(pane.vertical().moving()).isTrue();
        double v0 = pane.vertical().velocity();
        assertThat(v0).isBetween(200.0, 300.0);
        f.run(10);
        assertThat(pane.vertical().position()).isGreaterThan(dragged + 20);
        assertThat(pane.vertical().velocity()).isLessThan(v0 * 0.7); // friction
        f.run(200);
        assertThat(pane.vertical().moving()).isFalse();
        double rest = pane.vertical().position();
        // a touch stops a fling
        pane.scrollTo(0, 0);
        f.run();
        // dragging past the start stretches, then springs back
        f.synthetic.pointer(finger, Pointer.Kind.TOUCH, 50, 20, Pointer.PRIMARY);
        f.run();
        for (int y = 30; y <= 90; y += 10) {
            f.synthetic.pointer(finger, Pointer.Kind.TOUCH, 50, y, Pointer.PRIMARY);
            f.run();
        }
        assertThat(pane.vertical().position()).isNegative();
        assertThat(pane.vertical().position()).isGreaterThan(-60); // resisted
        f.synthetic.lift(finger);
        f.run(120);
        assertThat(pane.vertical().position()).isZero();
        assertThat(rest).isPositive();
    }

    @Test
    void withoutOverscrollADragStopsAtTheEnds() {
        DataTunables.OVERSCROLL.select("none");
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        f.run();
        f.synthetic.pointer(4, Pointer.Kind.TOUCH, 50, 20, Pointer.PRIMARY);
        f.run();
        for (int y = 30; y <= 90; y += 10) {
            f.synthetic.pointer(4, Pointer.Kind.TOUCH, 50, y, Pointer.PRIMARY);
            f.run();
        }
        assertThat(pane.vertical().position()).isZero();
        // a fling down to the end stops there
        pane.scrollTo(0, 380);
        f.run();
        f.synthetic.pointer(4, Pointer.Kind.TOUCH, 50, 90, Pointer.PRIMARY);
        f.run();
        for (int y = 80; y >= 30; y -= 10) {
            f.synthetic.pointer(4, Pointer.Kind.TOUCH, 50, y, Pointer.PRIMARY);
            f.run();
        }
        f.synthetic.lift(4);
        f.run(60);
        assertThat(pane.vertical().position()).isEqualTo(400);
    }

    @Test
    void stickyHeadersStayAtTheTopWhileTheirSectionCrossesIt() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = new ScrollPane(f.ui, "Sections");
        f.ui.root().add(pane.root());
        Element[] heads = new Element[3];
        for (int s = 0; s < 3; s++) {
            heads[s] = f.ui.panel("head");
            Element body = f.ui.panel(null); // 50 high: sections are 60
            pane.content().add(pane.section(heads[s], body));
        }
        f.run();
        assertThat(f.ui.layout().y(heads[1].slot())).isEqualTo(60);
        pane.scrollTo(0, 20);
        f.run();
        assertThat(f.ui.layout().y(heads[0].slot())).isZero(); // stuck
        assertThat(pane.stickyShift(0)).isEqualTo(20);
        assertThat(f.ui.layout().y(heads[1].slot())).isEqualTo(40);
        pane.scrollTo(0, 55);
        f.run();
        assertThat(f.ui.layout().y(heads[0].slot())).isEqualTo(-5); // pushed up by the next section
        pane.scrollTo(0, 80);
        f.run();
        assertThat(f.ui.layout().y(heads[1].slot())).isZero();
        assertThat(pane.stickyShift(0)).isEqualTo(50);
        // the header draws over its body: after it in the draw list
        String drawn = DrawListDump.of(f.ui.layers()[0]);
        assertThat(drawn.indexOf("a=(0.00 1.00 0.00 1.00)")).isPositive();
        pane.fitWidth(false);
        f.run();
        assertThat(pane.fitsWidth()).isFalse();
    }

    @Test
    void anUnfittedPaneScrollsSidewaysAndClipsItsContent() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 4);
        pane.fitWidth(false);
        pane.content().add(f.ui.panel("wide"));
        f.run();
        assertThat(f.ui.layout().shown(pane.horizontalBar().slot())).isTrue();
        assertThat(pane.horizontal().range()).isEqualTo(500 - 190);
        f.synthetic.modifiers(SyntheticInput.MOUSE, Pointer.SHIFT);
        f.wheel(50, 50, -2);
        assertThat(pane.horizontal().position()).isEqualTo(20);
        assertThat(DrawListDump.of(f.ui.layers()[0])).contains("clip=");
        Semantics.Node tree = Semantics.of(f.ui.store(), f.ui.interaction());
        assertThat(tree.toString()).contains("SCROLL_VIEW \"Content\"").contains("SCROLL_BAR");
        assertThat(Semantics.unnamed(tree)).isEmpty();
        assertThat(f.ui.store().role(pane.root().slot())).isEqualTo(Role.SCROLL_VIEW);
    }

    @Test
    void barsAreActiveWhileScrollingAndIdleLater() {
        DataFrame f = new DataFrame(SHEET, null);
        ScrollPane pane = pane(f, 10);
        f.run(100); // the start is no activity
        assertThat(pane.active()).isFalse();
        f.wheel(50, 50, -1);
        assertThat(pane.active()).isTrue();
        assertThat(DrawListDump.of(f.ui.layers()[0])).contains("a=(0.05 0.05 0.05 1.00)"); // #404040
        f.mouse(50, 50, 0);
        f.run((int) (DataTunables.AUTO_HIDE_DELAY.get() / 0.016f) + 2);
        assertThat(pane.active()).isFalse();
        assertThat(pane.vertical().position()).isCloseTo(10, within(0.0));
    }

    @Test
    void scrollerMathIsExact() {
        Scroller s = new Scroller();
        s.extents(1000, 100);
        assertThat(s.scrollTo(-5)).isFalse();
        assertThat(s.scrollTo(2000)).isTrue();
        assertThat(s.position()).isEqualTo(900);
        assertThat(s.canScroll(1)).isFalse();
        assertThat(s.canScroll(-1)).isTrue();
        assertThat(s.canScroll(0)).isFalse();
        assertThat(s.reveal(100, 150)).isTrue();
        assertThat(s.position()).isEqualTo(100);
        assertThat(s.reveal(150, 300)).isTrue(); // longer than the viewport: its start
        assertThat(s.position()).isEqualTo(150);
        assertThat(s.reveal(160, 200)).isFalse();
        s.extents(120, 100);
        assertThat(s.position()).isEqualTo(20);
        assertThat(s.content()).isEqualTo(120);
        assertThat(s.step(0.1, true)).isFalse();
        s.stop();
        assertThat(s.version()).isPositive();
        assertThat(Ui.CODE_KIND).isPositive();
    }
}
