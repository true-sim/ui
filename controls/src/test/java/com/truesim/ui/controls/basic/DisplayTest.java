package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/** Labels with icons, badges, images, separators, progress bars, spinners and sliders. */
class DisplayTest {

    @Test
    void labelsBadgesImagesAndSeparators() {
        Fixture f = new Fixture();
        IconLabel l = f.c.iconLabel("more", "Settings");
        Badge b = f.c.badge("new", "Mail");
        ImageView img = f.c.image("portrait", "Ada");
        Separator h = f.c.separator(false);
        Separator v = f.c.separator(true);
        f.add(l.element()).add(b.element()).add(img.element()).add(h.element()).add(v.element()).settle();
        assertThat(l.icon().id()).isEqualTo("more");
        l.text("Options");
        assertThat(f.ui.text(l.labelElement()).toString()).isEqualTo("Options");
        assertThat(f.ui.store().label(l.element().slot())).isEqualTo("Options");
        b.count(7);
        assertThat(f.ui.text(b.labelElement()).toString()).isEqualTo("7");
        b.count(7);
        b.max(99).count(120);
        assertThat(f.ui.text(b.labelElement()).toString()).isEqualTo("99+");
        b.count(-3);
        assertThat(f.ui.text(b.labelElement()).toString()).isEqualTo("-3");
        b.count(0);
        assertThat(f.ui.text(b.labelElement()).toString()).isEmpty();
        b.text("hi");
        assertThat(b.count()).isEqualTo(Integer.MIN_VALUE);
        assertThat(f.ui.store().role(img.element().slot())).isEqualTo(Role.IMAGE);
        f.settle();
        assertThat(f.ui.layout().height(h.element().slot())).isEqualTo(1);
        assertThat(f.ui.layout().width(v.element().slot())).isEqualTo(1);
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void aProgressBarShowsItsModelAndSweepsWhenIndeterminate() {
        Fixture f = new Fixture();
        VersionedDouble model = f.c.clock().doubleValue(0.25);
        ProgressBar p = f.c.progress("Loading", model);
        f.add(p.element()).settle();
        float track = f.ui.layout().width(p.element().slot());
        assertThat(f.ui.layout().width(p.fill().slot()))
            .isEqualTo(track * 0.25f, org.assertj.core.data.Offset.offset(1f));
        model.set(2);
        f.run();
        assertThat(f.ui.layout().width(p.fill().slot())).as("clamped").isEqualTo(track);
        model.set(Double.NaN);
        f.run();
        assertThat(f.ui.layout().width(p.fill().slot())).isZero();
        p.indeterminate(true).indeterminate(true);
        assertThat(p.indeterminate()).isTrue();
        assertThat(p.fill().is(UiState.INDETERMINATE)).isTrue();
        f.run(3);
        float x1 = f.ui.visuals().translateX(p.fill().slot());
        f.run(5);
        float x2 = f.ui.visuals().translateX(p.fill().slot());
        assertThat(x2).as("it sweeps on the clock").isGreaterThan(x1);
        p.indeterminate(false);
        assertThat(f.ui.visuals().has(p.fill().slot(), Visuals.TRANSLATE)).isFalse();
        p.bind(f.c.clock().doubleValue(0.5));
        assertThat(p.model().get()).isEqualTo(0.5);
        p.element().detach();
        f.run();
    }

    @Test
    void aSpinnerTurnsItsDotsOnTheClockAndFollowsItsThemesDotCount() {
        Fixture f = new Fixture();
        Spinner s = f.c.spinner("Loading");
        f.add(s.element()).run(2);
        assertThat(s.dots()).isEqualTo(8);
        float a = f.ui.visuals().opacity(s.dot(0).slot());
        f.run(4);
        assertThat(f.ui.visuals().opacity(s.dot(0).slot())).isNotEqualTo(a);
        f.ui.styles().sheet(Fixture.GARISH);
        f.run(2);
        assertThat(s.dots()).isEqualTo(5);
        s.element().detach();
        f.run();
    }

    @Test
    void aSliderFollowsThePointerKeysAndWheel() {
        Fixture f = new Fixture();
        VersionedDouble v = f.c.clock().doubleValue(0.5);
        Slider s = f.c.slider("Volume", v, 0, 1).step(0.1).ticks(11);
        f.add(s.element()).settle();
        assertThat(s.ticks()).isEqualTo(11);
        assertThat(s.step()).isEqualTo(0.1);
        int t = s.track().slot();
        float x0 = f.ui.layout().x(t);
        float w = f.ui.layout().width(t);
        float y = f.cy(s.track());
        f.mouse(x0 + w * 0.8f, y, 0);
        f.mouse(x0 + w * 0.8f, y, Pointer.PRIMARY);
        assertThat(v.get()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.thumb(0).is(UiState.PRESSED)).isTrue();
        f.mouse(x0 + w * 0.31f, y, Pointer.PRIMARY);
        assertThat(v.get()).as("snapped to the step").isEqualTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        f.mouse(x0 - 50, y, Pointer.PRIMARY);
        assertThat(v.get()).isZero();
        f.mouse(x0 - 50, y, 0);
        assertThat(s.thumb(0).is(UiState.PRESSED)).isFalse();
        float thumbX = f.cx(s.thumb(0));
        assertThat(thumbX).isEqualTo(x0, org.assertj.core.data.Offset.offset(1f));
        f.focus(s.element()).press(UiCommand.RIGHT, UiCommand.RIGHT);
        assertThat(v.get()).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        f.press(UiCommand.END);
        assertThat(s.value()).isEqualTo(1);
        f.press(UiCommand.PAGE_DOWN);
        assertThat(s.value()).isEqualTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
        f.press(UiCommand.HOME, UiCommand.PAGE_UP, UiCommand.LEFT);
        assertThat(s.value()).isZero();
        f.press(UiCommand.UP);
        assertThat(s.value()).as("up is not a horizontal slider's").isZero();
        f.synthetic.mouse(f.cx(s.element()), y, 0).scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE, 0, 1);
        f.run();
        assertThat(s.value()).isEqualTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        v.set(0.7);
        f.run();
        assertThat(f.cx(s.thumb(0))).as("a model change moves the thumb in the frame")
            .isEqualTo(x0 + w * 0.7f, org.assertj.core.data.Offset.offset(1f));
        assertThat(s.min()).isZero();
        assertThat(s.max()).isEqualTo(1);
        assertThat(s.high()).isEqualTo(0.7);
        assertThat(s.model(0)).isSameAs(v);
        s.ticks(0);
        assertThat(s.ticks()).isZero();
        assertThatThrownBy(() -> f.c.slider("bad", v, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(s.step(-1).step()).isZero();
    }

    @Test
    void aVerticalSliderAndARangeSlider() {
        Fixture f = new Fixture();
        VersionedDouble g = f.c.clock().doubleValue(0.5);
        Slider vertical = new Slider(f.c, "Gain", g, 0, 1, true);
        VersionedDouble lo = f.c.clock().doubleValue(20);
        VersionedDouble hi = f.c.clock().doubleValue(80);
        Slider range = f.c.rangeSlider("Range", lo, hi, 0, 100);
        f.add(vertical.element()).add(range.element()).settle();
        int t = vertical.track().slot();
        float top = f.ui.layout().y(t);
        float h = f.ui.layout().height(t);
        float x = f.cx(vertical.track());
        f.mouse(x, top + h * 0.25f, 0);
        f.mouse(x, top + h * 0.25f, Pointer.PRIMARY);
        f.mouse(x, top + h * 0.25f, 0);
        assertThat(g.get()).isEqualTo(0.75, org.assertj.core.data.Offset.offset(0.02));
        f.focus(vertical.element()).press(UiCommand.UP);
        assertThat(g.get()).isEqualTo(0.76, org.assertj.core.data.Offset.offset(0.02));
        f.press(UiCommand.DOWN, UiCommand.DOWN);
        assertThat(g.get()).isEqualTo(0.74, org.assertj.core.data.Offset.offset(0.02));
        f.synthetic.mouse(x, top + h / 2, 0).scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE, 0, -1);
        f.run();
        assertThat(g.get()).isEqualTo(0.73, org.assertj.core.data.Offset.offset(0.02));

        int rt = range.track().slot();
        float rx = f.ui.layout().x(rt);
        float rw = f.ui.layout().width(rt);
        float ry = f.cy(range.track());
        f.mouse(rx + rw * 0.9f, ry, Pointer.PRIMARY);
        f.mouse(rx + rw * 0.9f, ry, 0);
        assertThat(hi.get()).as("the nearer thumb moves").isEqualTo(90.0, org.assertj.core.data.Offset.offset(1d));
        f.mouse(rx + rw * 0.95f, ry, Pointer.PRIMARY);
        f.mouse(rx + rw * 0.05f, ry, Pointer.PRIMARY);
        f.mouse(rx + rw * 0.05f, ry, 0);
        assertThat(hi.get()).as("a thumb stops at the other").isEqualTo(20.0, org.assertj.core.data.Offset.offset(1d));
        assertThat(f.ui.interaction().focused(0)).isEqualTo(range.thumb(1));
        f.press(UiCommand.LEFT);
        assertThat(hi.get()).isEqualTo(20.0, org.assertj.core.data.Offset.offset(1d));
        f.focus(range.thumb(0)).press(UiCommand.LEFT);
        assertThat(lo.get()).isEqualTo(19.0, org.assertj.core.data.Offset.offset(0.01d));
        f.mouse(rx + rw * 0.1f, ry, Pointer.PRIMARY);
        f.mouse(rx + rw * 0.1f, ry, 0);
        assertThat(lo.get()).isEqualTo(10.0, org.assertj.core.data.Offset.offset(1d));
        range.set(1, 10);
        range.set(0, 10);
        f.mouse(rx + rw * 0.05f, ry, Pointer.PRIMARY);
        f.mouse(rx + rw * 0.05f, ry, 0);
        assertThat(lo.get()).as("equal thumbs: the lower one goes lower")
            .isEqualTo(5.0, org.assertj.core.data.Offset.offset(1d));
        f.synthetic.mouse(rx + rw / 2, ry, 0).scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE, 0, 1);
        f.run();
        assertThat(f.unnamed()).isEmpty();
        f.ui.interaction().blur(0);
        f.press(UiCommand.RIGHT);
        f.ui.on(range.element(), EventType.CLICK, e -> { });
    }
}
