package com.truesim.ui.controls.anim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Easing;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Transition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The tween and transition engine: channels, reversal, completion, style transitions, drawing with transforms. */
class AnimatorTest {

    static final StyleSheet SHEET = Tss.compileString("anim.tss", """
        root { layout: flex; direction: row; align-items: start; }
        panel { layout: stack; width: 100px; height: 40px; background: box(#000000); }
        panel.fade {
          opacity: 0.5;
          transition: background 100ms linear, opacity 100ms linear, scale 100ms linear, color 100ms linear,
            border 100ms linear, width 100ms linear, height 100ms ease-in-out, offsets 100ms linear;
          border: 2px #000000;
          &:hover { background: box(#ffffff); opacity: 1; scale: 2; border: 2px #ffffff; width: 200px; height: 80px;
            offsets: 10px 0 0 0; }
        }
        panel.grad { background: linear-gradient(0deg, #000000, #000000); transition: all 100ms linear;
          &:hover { background: linear-gradient(0deg, #ffffff, #ffffff); } }
        panel.sudden { transition: background 0s, scale 0s; &:hover { background: box(#ff0000); scale: 1.5; } }
        panel.mixed { transition: background 100ms; &:hover { background: linear-gradient(0deg, red, blue); } }
        panel.late { transition: background 100ms linear 50ms; &:hover { background: box(#ffffff); } }
        panel.auto { transition: width 100ms; &:hover { width: auto; } }
        label { color: #000000; transition: color 100ms linear; &:hover { color: #ffffff; } }
        """);

    static final long MS = 1_000_000L;

    @AfterEach
    void normalSpeed() {
        Animator.SPEED.set(1);
    }

    static final class Rig {
        final Ui ui = new Ui(SHEET, null);
        long now = 1_000 * MS;

        Element panel(String style) {
            Element e = ui.panel(style);
            ui.root().add(e);
            return e;
        }

        Rig frame(long ms) {
            now += ms * MS;
            ui.update(800, 600, null, now);
            return this;
        }

        long fill(Element e) {
            return ui.layers()[0].colorAt(ui.store().rangeStart(e.slot()), Primitive.COLOR_A);
        }
    }

    @Test
    void easingsMatchCss() {
        assertThat(Easings.ease(Easing.LINEAR, 0.3f)).isEqualTo(0.3f);
        assertThat(Easings.ease(Easing.EASE, 0)).isZero();
        assertThat(Easings.ease(Easing.EASE, 1)).isEqualTo(1);
        assertThat(Easings.ease(Easing.EASE, -1)).isZero();
        assertThat(Easings.ease(Easing.EASE, 2)).isEqualTo(1);
        assertThat(Easings.ease(Easing.EASE, 0.5f)).isEqualTo(0.8024f, offset(1e-3f));
        assertThat(Easings.ease(Easing.EASE_IN, 0.5f)).isEqualTo(0.3153f, offset(1e-3f));
        assertThat(Easings.ease(Easing.EASE_OUT, 0.5f)).isEqualTo(0.6847f, offset(1e-3f));
        assertThat(Easings.ease(Easing.EASE_IN_OUT, 0.5f)).isEqualTo(0.5f, offset(1e-3f));
        float last = 0;
        for (int i = 1; i <= 100; i++) {
            float v = Easings.ease(Easing.EASE_IN_OUT, i / 100f);
            assertThat(v).isGreaterThanOrEqualTo(last);
            last = v;
        }
        assertThat(Easings.cubicBezier(0, 0, 0, 0, 0.5f)).isGreaterThan(0.5f); // a flat start: bisection
        assertThat(Easings.smoothStep(0.5f)).isEqualTo(0.5f);
        assertThat(Easings.smoothStep(-1)).isZero();
        assertThat(Easings.smoothStep(3)).isEqualTo(1);
    }

    @Test
    void aStyleTransitionAnimatesAndHandsBackToTheStyle() {
        Rig r = new Rig();
        Element p = r.panel("fade");
        r.frame(16);
        assertThat(Rgba.r(r.fill(p))).isZero();
        p.state(UiState.HOVER, true);
        r.frame(16);
        Animator a = r.ui.animator();
        assertThat(a.running(p.slot(), Animator.BACKGROUND)).isTrue();
        assertThat(a.running()).isGreaterThanOrEqualTo(6);
        r.frame(50);
        float mid = Rgba.r(r.fill(p));
        assertThat(mid).isBetween(0.1f, 0.9f);
        assertThat(a.progress(p.slot(), Animator.BACKGROUND)).isEqualTo(0.5f, offset(0.01f));
        assertThat(r.ui.layout().width(p.slot())).isEqualTo(150, offset(1f));
        assertThat(r.ui.visuals().scale(p.slot())).isEqualTo(1.5f, offset(0.02f));
        assertThat(r.ui.visuals().opacity(p.slot())).isEqualTo(0.75f, offset(0.02f));
        r.frame(60);
        assertThat(a.running(p.slot(), Animator.BACKGROUND)).isFalse();
        assertThat(r.ui.visuals().flags(p.slot())).as("handed back to the style").isZero();
        assertThat(Rgba.r(r.fill(p))).isEqualTo(1);
        assertThat(r.ui.layout().width(p.slot())).isEqualTo(200);
        assertThat(r.ui.layout().height(p.slot())).isEqualTo(80);
    }

    @Test
    void anInterruptedTransitionReversesFromWhereItIs() {
        Rig r = new Rig();
        Element p = r.panel("fade");
        r.frame(16);
        p.state(UiState.HOVER, true);
        r.frame(16);
        r.frame(30);
        float at = r.ui.layout().width(p.slot());
        p.state(UiState.HOVER, false);
        r.frame(0);
        Animator a = r.ui.animator();
        assertThat(a.reversed(p.slot(), Animator.WIDTH)).isTrue();
        assertThat(r.ui.layout().width(p.slot())).as("no jump").isEqualTo(at, offset(1f));
        r.frame(15);
        assertThat(r.ui.layout().width(p.slot())).isLessThan(at);
        r.frame(20);
        assertThat(a.running(p.slot(), Animator.WIDTH)).as("as long as it had run").isFalse();
        assertThat(r.ui.layout().width(p.slot())).isEqualTo(100);
    }

    @Test
    void transitionsWithoutTimeOrOfMismatchedValuesChangeAtOnce() {
        Rig r = new Rig();
        Element sudden = r.panel("sudden");
        Element mixed = r.panel("mixed");
        Element grad = r.panel("grad");
        Element late = r.panel("late");
        Element auto = r.panel("auto");
        r.frame(16);
        for (Element e : new Element[] {sudden, mixed, grad, late, auto}) {
            e.state(UiState.HOVER, true);
        }
        r.frame(16);
        Animator a = r.ui.animator();
        assertThat(a.running(sudden.slot(), Animator.BACKGROUND)).isFalse();
        assertThat(a.running(mixed.slot(), Animator.BACKGROUND)).as("box to gradient").isFalse();
        assertThat(a.running(grad.slot(), Animator.BACKGROUND)).as("all").isTrue();
        assertThat(a.running(auto.slot(), Animator.WIDTH)).as("auto cannot be interpolated").isFalse();
        assertThat(Rgba.r(r.fill(late))).as("delayed").isZero();
        r.frame(40);
        assertThat(Rgba.r(r.fill(late))).isZero();
        r.frame(60);
        assertThat(Rgba.r(r.fill(late))).isBetween(0.01f, 0.99f);
        r.frame(200);
        assertThat(a.running()).isZero();
    }

    @Test
    void aRunningTransitionStopsWhenTheNewStyleHasNone() {
        Rig r = new Rig();
        Element p = r.panel("fade");
        r.frame(16);
        p.state(UiState.HOVER, true);
        r.frame(16);
        r.ui.styles().inline(p.slot(), com.truesim.ui.style.InlineStyle.builder()
            .set(com.truesim.ui.style.Styles.TRANSITION, List.of())
            .set(com.truesim.ui.style.Styles.BACKGROUND, com.truesim.ui.style.value.Paint.box(
                com.truesim.ui.style.value.Color.hex("#00ff00"))).build());
        r.frame(16);
        assertThat(r.ui.animator().running(p.slot(), Animator.BACKGROUND)).isFalse();
        assertThat(Rgba.g(r.fill(p))).isEqualTo(1);
    }

    @Test
    void transformsAndOpacityApplyToTheSubtree() {
        Rig r = new Rig();
        Element outer = r.ui.panel(null);
        r.ui.root().add(outer);
        Element inner = r.ui.panel(null);
        outer.add(inner);
        r.frame(16);
        DrawList list = r.ui.layers()[0];
        int at = r.ui.store().rangeStart(inner.slot());
        float x0 = list.floatAt(at, Primitive.X0);
        Visuals v = r.ui.visuals();
        v.scale(outer, 2);
        v.opacity(outer, 0.5f);
        r.frame(16);
        assertThat(v.transformed(outer.slot())).isTrue();
        float cx = r.ui.layout().x(outer.slot()) + r.ui.layout().width(outer.slot()) / 2;
        assertThat(list.floatAt(at, Primitive.X0)).isEqualTo(cx + 2 * (x0 - cx), offset(0.01f));
        assertThat(Rgba.a(list.colorAt(at, Primitive.COLOR_A))).isEqualTo(0.5f, offset(0.01f));
        v.translate(inner, 10, 0);
        r.frame(16);
        assertThat(list.floatAt(at, Primitive.X0)).isEqualTo(cx + 2 * (x0 - cx) + 20, offset(0.01f));
        v.clear(outer, Visuals.SCALE | Visuals.OPACITY);
        v.clear(inner, Visuals.TRANSLATE);
        v.clear(inner, Visuals.TRANSLATE);
        r.frame(16);
        assertThat(list.floatAt(at, Primitive.X0)).isEqualTo(x0);
        r.ui.layout().width(outer.slot(), 300, Unit.PX);
        v.scale(outer, 1.5f);
        r.frame(16);
        r.ui.layout().width(outer.slot(), 200, Unit.PX);
        r.frame(16);
    }

    @Test
    void elementChannelsWriteOverridesAndCanBeKeptCancelledOrFinished() {
        Rig r = new Rig();
        Element p = r.panel(null);
        Element label = r.panel(null);
        r.frame(16);
        Animator a = r.ui.animator();
        Visuals v = r.ui.visuals();
        List<String> ended = new ArrayList<>();
        Animator.Listener l = (key, channel, completed) -> ended.add(channel + ":" + completed);
        a.animate(p.slot(), Animator.OPACITY, 0, 1, 0.1f, Easing.LINEAR, true, l);
        a.translate(p.slot(), 0, 0, 10, 20, 0.1f, Easing.LINEAR, true, null);
        a.translate(p.slot(), Float.NaN, Float.NaN, 30, 20, 0.1f, Easing.LINEAR, true, null);
        a.color(p.slot(), Animator.BACKGROUND, Rgba.BLACK, Rgba.BLACK, Rgba.WHITE, Rgba.WHITE, 0.1f, null, true, null);
        a.color(label.slot(), Animator.COLOR, Rgba.BLACK, 0, Rgba.WHITE, 0, 0.1f, null, true, null);
        a.color(label.slot(), Animator.BORDER_COLOR, Rgba.BLACK, 0, Rgba.WHITE, 0, 0.1f, null, true, null);
        a.edges(p.slot(), Animator.OFFSETS, Insets.NONE, Insets.of(0, 0, 0, 40), 0.1f, null, true, null);
        a.edges(label.slot(), Animator.ANCHORS, Insets.NONE, Insets.of(0, 0, 0, 0), 0.1f, null, true, null);
        a.animate(label.slot(), Animator.HEIGHT, 10, 20, 0.1f, null, true, null);
        assertThatThrownBy(() -> a.color(p.slot(), Animator.WIDTH, 0, 0, 0, 0, 1, null, true, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.animate(p.slot(), Animator.TRANSLATE, 0, 1, 1, null, true, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.animate(null, 0, 0, 0, 1, 1, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        r.frame(50);
        assertThat(v.opacity(p.slot())).isEqualTo(0.5f, offset(0.02f));
        assertThat(v.translateX(p.slot())).isBetween(5f, 25f);
        assertThat(v.offset(p.slot(), 0)).isBetween(10f, 30f);
        a.cancel(p.slot(), Animator.OPACITY);
        assertThat(ended).containsExactly("0:false");
        a.finish(p.slot(), Animator.TRANSLATE);
        assertThat(v.translateX(p.slot())).isEqualTo(30);
        assertThat(v.translateY(p.slot())).isEqualTo(20);
        r.frame(60);
        assertThat(v.has(p.slot(), Visuals.BACKGROUND)).as("kept").isTrue();
        assertThat(v.backgroundA(p.slot())).isEqualTo(Rgba.WHITE);
        assertThat(v.color(label.slot())).isEqualTo(Rgba.WHITE);
        assertThat(v.borderColor(label.slot())).isEqualTo(Rgba.WHITE);
        assertThat(v.anchor(label.slot(), 0)).isZero();
        assertThat(v.height(label.slot())).isEqualTo(20);
        a.finish(p.slot(), Animator.OPACITY);
        a.cancel(p.slot(), Animator.OPACITY);

        // retargeting from the value showing, and the same target again
        a.animate(p.slot(), Animator.SCALE, 1, 2, 0.1f, Easing.LINEAR, false, l);
        r.frame(50);
        a.animate(p.slot(), Animator.SCALE, Float.NaN, 2, 0.1f, Easing.LINEAR, false, l);
        a.animate(p.slot(), Animator.SCALE, Float.NaN, 3, 0.1f, Easing.LINEAR, false, l);
        assertThat(ended).contains("1:false");
        assertThat(v.scale(p.slot())).isEqualTo(1.5f, offset(0.02f));
        r.frame(110);
        assertThat(v.has(p.slot(), Visuals.SCALE)).as("handed back").isFalse();

        // an element destroyed while animating ends its animations
        a.animate(label.slot(), Animator.WIDTH, 10, 20, 1, null, true, l);
        r.ui.destroy(label);
        r.frame(16);
        assertThat(ended).contains("6:false");
        assertThat(a.running()).isZero();
    }

    @Test
    void ownChannelsDeliverTheirValuesAndSpeedZeroEndsEverything() {
        Rig r = new Rig();
        r.frame(16);
        Animator a = r.ui.animator();
        float[] got = new float[1];
        Animator.Animated target = (key, channel, value) -> got[0] = value;
        List<String> ended = new ArrayList<>();
        Animator.Listener done = (key, channel, completed) -> ended.add(key + "/" + channel + ":" + completed);
        a.animate(target, 7, 3, 0, 10, 0.1f, Easing.LINEAR, done);
        r.frame(50);
        assertThat(got[0]).isEqualTo(5, offset(0.2f));
        assertThat(a.running(target, 7, 3)).isTrue();
        a.animate(target, 7, 3, Float.NaN, 0, 0.1f, Easing.LINEAR, done);
        r.frame(50);
        assertThat(got[0]).as("reversed").isZero();
        assertThat(ended).containsExactly("7/3:true");
        a.animate(target, 1, 0, Float.NaN, 4, 0.1f, Easing.LINEAR, null);
        assertThat(got[0]).isEqualTo(4);
        a.animate(target, 1, 0, 0, 4, 1, Easing.LINEAR, null);
        a.finish(target, 1, 0);
        assertThat(got[0]).isEqualTo(4);
        a.animate(target, 1, 0, 0, 4, 1, Easing.LINEAR, null);
        a.cancel(target, 1, 0);
        assertThat(a.running(target, 1, 0)).isFalse();
        Animator.SPEED.set(0);
        a.animate(target, 2, 0, 0, 9, 1, Easing.LINEAR, null);
        assertThat(got[0]).as("reduced motion: at once").isEqualTo(9);
        Animator.SPEED.set(1);
        a.animate(target, 2, 0, 0, 3, 1, Easing.LINEAR, null);
        r.ui.update(800, 600);
        assertThat(got[0]).as("a frame without a clock ends it").isEqualTo(3);
        for (int i = 0; i < 40; i++) {
            a.animate(target, 100 + i, 0, 0, 1, 1, Easing.LINEAR, done);
        }
        assertThat(a.running()).isEqualTo(40);
        r.ui.update(800, 600);
        assertThat(a.running()).isZero();
        assertThat(ended).hasSize(41);
        assertThat(a.frames()).isPositive();
        assertThat(a.visuals()).isSameAs(r.ui.visuals());
        assertThat(Animator.rgba(com.truesim.ui.style.value.Color.WHITE)).isEqualTo(Rgba.WHITE);
        assertThat(Animator.find(List.of(Transition.of("all", 1, Easing.EASE)), "x").property()).isEqualTo("all");
        assertThat(Animator.find(List.of(), "x")).isNull();
        assertThat(a.progress(0, Animator.OPACITY)).isEqualTo(1);
        assertThat(a.reversed(0, Animator.OPACITY)).isFalse();
    }

    @Test
    void layoutOverridesSurviveRestylesAndClearingRestoresTheStyle() {
        Rig r = new Rig();
        Element p = r.panel(null);
        r.frame(16);
        Visuals v = r.ui.visuals();
        v.width(p, 50, Unit.PX);
        v.height(p, 10, Unit.PX);
        v.minWidth(p.slot(), 60);
        v.minHeight(p.slot(), 12);
        r.frame(16);
        assertThat(r.ui.layout().width(p.slot())).isEqualTo(60);
        p.state(UiState.HOVER, true); // restyles: the overrides stay
        r.frame(16);
        assertThat(r.ui.layout().width(p.slot())).isEqualTo(60);
        assertThat(r.ui.layout().height(p.slot())).isEqualTo(12);
        v.clear(p, Visuals.WIDTH | Visuals.HEIGHT | Visuals.MIN_WIDTH | Visuals.MIN_HEIGHT);
        r.frame(16);
        assertThat(r.ui.layout().width(p.slot())).isEqualTo(100);
        v.offsets(p, 5, 0, 0, 0);
        v.anchors(p, 0, 0, 1, 1);
        v.background(p, Rgba.WHITE, Rgba.WHITE);
        v.color(p, Rgba.WHITE);
        r.frame(16);
        assertThat(Rgba.r(r.fill(p))).isEqualTo(1);
        r.ui.destroy(p);
        Element q = r.panel(null);
        assertThat(v.flags(q.slot())).as("a reused slot starts clean").isZero();
        assertThat(v.has(q.slot(), Visuals.WIDTH)).isFalse();
    }

    @Test
    void animatedFramesAllocateNothing() {
        Rig r = new Rig();
        Element[] panels = new Element[6];
        for (int i = 0; i < panels.length; i++) {
            panels[i] = r.panel(i % 2 == 0 ? "fade" : "grad");
        }
        r.frame(16);
        int[] step = new int[1];
        Animator.Animated sink = (key, channel, value) -> step[0] += 0;
        Runnable frame = () -> {
            int s = step[0]++;
            if (s % 7 == 0) {
                Element e = panels[s % panels.length];
                e.state(UiState.HOVER, !e.is(UiState.HOVER));
            }
            if (s % 11 == 0) {
                r.ui.animator().animate(sink, 1, 1, 0, 1, 0.05f, Easing.EASE, null);
            }
            r.frame(16);
        };
        for (int i = 0; i < 3_000; i++) {
            frame.run();
        }
        AllocationAssertions.assertAllocationFree(frame);
    }
}
