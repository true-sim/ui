package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedLong;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The colour, date and time pickers and the key-binding field. */
class PickersTest {

    @Test
    void theColourPickerConvertsAndEditsHsvTwoWays() {
        Fixture f = new Fixture();
        VersionedLong model = f.c.clock().longValue(Rgba.srgb(255, 0, 0, 1));
        ColorPicker p = f.c.colorPicker("Tint", model);
        int[] changes = new int[1];
        p.onChange(() -> changes[0]++);
        p.swatches(Rgba.srgb(0, 0, 255, 1));
        f.add(p.element()).settle();
        assertThat(p.hue()).isZero();
        assertThat(p.saturation()).isEqualTo(1, offset(1e-3f));
        assertThat(p.value()).isEqualTo(1, offset(1e-3f));
        assertThat(p.hex().toString()).isEqualTo("#FF0000FF");
        Layout l = f.ui.layout();
        int area = p.area().slot();
        float ax = l.x(area);
        float ay = l.y(area);
        float aw = l.width(area);
        float ah = l.height(area);
        f.mouse(ax + aw * 0.5f, ay + ah * 0.5f, Pointer.PRIMARY);
        assertThat(p.dragging()).isTrue();
        f.mouse(ax + aw * 0.5f, ay + ah * 0.25f, Pointer.PRIMARY);
        f.mouse(ax + aw * 0.5f, ay + ah * 0.25f, 0);
        assertThat(p.saturation()).isEqualTo(0.5f, offset(0.02f));
        assertThat(p.value()).isEqualTo(0.75f, offset(0.02f));
        assertThat(p.hue()).as("kept").isZero();
        assertThat(changes[0]).isPositive();
        int hue = p.hueBar().slot();
        f.mouse(l.x(hue) + l.width(hue) / 3, f.cy(p.hueBar()), Pointer.PRIMARY);
        f.mouse(l.x(hue) + l.width(hue) / 3, f.cy(p.hueBar()), 0);
        assertThat(p.hue()).isEqualTo(1 / 3f, offset(0.02f));
        assertThat(Rgba.g(model.get())).as("green now").isGreaterThan(Rgba.r(model.get()));
        int alpha = p.alphaBar().slot();
        f.mouse(l.x(alpha) + l.width(alpha) * 0.25f, f.cy(p.alphaBar()), Pointer.PRIMARY);
        f.mouse(l.x(alpha) + l.width(alpha) * 0.25f, f.cy(p.alphaBar()), 0);
        assertThat(p.alpha()).isEqualTo(0.25f, offset(0.02f));
        f.focus(p.area()).press(UiCommand.LEFT, UiCommand.UP);
        assertThat(p.saturation()).isEqualTo(0.49f, offset(0.02f));
        f.focus(p.hueBar()).press(UiCommand.PAGE_UP, UiCommand.UP);
        assertThat(p.hue()).isEqualTo(1 / 3f + 0.1f, offset(0.02f));
        f.focus(p.alphaBar()).press(UiCommand.RIGHT, UiCommand.PAGE_DOWN, UiCommand.DOWN, UiCommand.HOME);
        assertThat(p.alpha()).isEqualTo(0.16f, offset(0.02f));
        f.click(p.swatch(0));
        assertThat(p.hex().toString()).isEqualTo("#0000FFFF");
        assertThat(p.hex("#0f08")).isTrue();
        assertThat(p.hex().toString()).isEqualTo("#00FF0088");
        assertThat(p.hex("336699")).isTrue();
        assertThat(p.color()).isEqualTo(model.get());
        assertThat(p.hex().toString()).isEqualTo("#336699FF");
        assertThat(p.hex("#12")).isFalse();
        assertThat(p.hex("#zzzzzz")).isFalse();
        assertThat(p.hex("")).isFalse();
        model.set(Rgba.srgb(128, 128, 128, 1));
        f.run();
        assertThat(p.saturation()).isZero();
        assertThat(f.ui.text(p.hexLabel()).toString()).isEqualTo("#808080FF");
        model.set(Rgba.srgb(0, 0, 255, 1));
        f.run();
        assertThat(p.hue()).isEqualTo(2 / 3f, offset(1e-3f));
        model.set(Rgba.srgb(255, 0, 128, 1));
        f.run();
        assertThat(p.hue()).isGreaterThan(0.9f);
        model.set(Rgba.srgb(0, 255, 128, 1));
        f.run();
        assertThat(p.hue()).isEqualTo(0.42f, offset(0.01f));
        float[] rgb = new float[3];
        for (int sector = 0; sector < 6; sector++) {
            ColorPicker.hsvToRgb(sector / 6f + 0.01f, 1, 1, rgb);
            assertThat(Math.max(rgb[0], Math.max(rgb[1], rgb[2]))).isEqualTo(1, offset(1e-6f));
        }
        assertThat(p.hexSlot().parent()).isEqualTo(p.element());
        assertThat(p.preview()).isNotNull();
        assertThat(p.model()).isSameAs(model);
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void theDatePickerNavigatesByKeysAndChoosesByClick() {
        Fixture f = new Fixture();
        VersionedValue<LocalDate> model = f.c.clock().value(LocalDate.of(2026, 11, 3));
        DatePicker d = f.c.datePicker("Date", Locale.UK, model).today(LocalDate.of(2026, 11, 3));
        f.add(d.element()).settle();
        assertThat(d.month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(f.ui.text(f.ui.store().element(d.title().slot())).toString()).isEqualTo("November 2026");
        int at = d.cellOf(LocalDate.of(2026, 11, 3));
        assertThat(at).isEqualTo(8); // Monday first: 26 Oct ... 1 Nov, 2 Nov, 3 Nov
        assertThat(d.cell(at).is(UiState.SELECTED)).isTrue();
        assertThat(f.ui.store().style(d.cell(at).slot())).isEqualTo("today");
        assertThat(f.ui.store().style(d.cell(0).slot())).isEqualTo("outside");
        f.focus(d.cell(at)).press(UiCommand.RIGHT, UiCommand.DOWN);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(d.cell(d.cellOf(LocalDate.of(2026, 11, 11))));
        f.press(UiCommand.ACCEPT);
        assertThat(model.get()).isEqualTo(LocalDate.of(2026, 11, 11));
        f.press(UiCommand.PAGE_DOWN);
        assertThat(d.month()).isEqualTo(YearMonth.of(2026, 12));
        f.press(UiCommand.PAGE_UP, UiCommand.PAGE_UP);
        assertThat(d.month()).isEqualTo(YearMonth.of(2026, 10));
        f.press(UiCommand.HOME, UiCommand.END, UiCommand.LEFT, UiCommand.UP, UiCommand.CANCEL);
        f.click(d.nextButton().element());
        assertThat(d.month()).isEqualTo(YearMonth.of(2026, 11));
        f.click(d.previousButton().element());
        f.click(d.nextButton().element());
        f.click(d.cell(0));
        assertThat(model.get()).as("a day of the previous month")
            .isEqualTo(d.date(d.cellOf(LocalDate.of(2026, 10, 26))));
        assertThat(d.month()).isEqualTo(YearMonth.of(2026, 10));
        model.set(LocalDate.of(2027, 2, 14));
        f.run();
        assertThat(d.month()).isEqualTo(YearMonth.of(2027, 2));
        model.set(null);
        f.run();
        d.select(null);
        assertThat(d.month()).isEqualTo(YearMonth.of(2027, 2));
        assertThat(d.cellOf(LocalDate.of(1999, 1, 1))).isEqualTo(-1);
        assertThat(d.model()).isSameAs(model);
        d.bind(f.c.clock().value(null));
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void theTimePickerStepsAndWrapsItsFields() {
        Fixture f = new Fixture();
        VersionedInt model = f.c.clock().intValue(23 * 3600 + 59 * 60 + 30);
        TimePicker t = new TimePicker(f.c, "Alarm", model, true, true, "AM", "PM");
        f.add(t.element()).settle();
        assertThat(f.ui.text(t.valueLabel(0)).toString()).isEqualTo("11");
        assertThat(f.ui.text(t.periodButton().labelElement()).toString()).isEqualTo("PM");
        f.focus(t.field(1)).press(UiCommand.UP);
        assertThat(model.get()).as("minutes wrap within the hour").isEqualTo(23 * 3600 + 30);
        f.press(UiCommand.RIGHT, UiCommand.DOWN);
        assertThat(model.get() % 60).isEqualTo(29);
        f.press(UiCommand.LEFT, UiCommand.LEFT, UiCommand.UP);
        assertThat(model.get() / 3600).as("hours wrap within the day").isZero();
        assertThat(f.ui.text(t.valueLabel(0)).toString()).isEqualTo("12");
        f.press(UiCommand.PAGE_UP, UiCommand.PAGE_DOWN, UiCommand.PAGE_DOWN);
        assertThat(model.get() / 3600).isEqualTo(18);
        f.press(UiCommand.LEFT);
        f.synthetic.mouse(f.cx(t.field(2)), f.cy(t.field(2)), 0).scroll(com.truesim.ui.testkit.SyntheticInput.MOUSE,
            0, 1);
        f.run();
        assertThat(model.get() % 60).isEqualTo(30);
        f.click(t.periodButton().element());
        assertThat(model.get() / 3600).isEqualTo(6);
        assertThat(f.ui.text(t.periodButton().labelElement()).toString()).isEqualTo("AM");
        model.set(3600);
        f.run();
        assertThat(f.ui.text(t.valueLabel(0)).toString()).isEqualTo("01");
        assertThat(t.get()).isEqualTo(3600);
        TimePicker day = f.c.timePicker("Time", f.c.clock().intValue(0));
        f.add(day.element()).settle();
        assertThat(day.periodButton()).isNull();
        f.focus(day.field(1)).press(UiCommand.PAGE_UP, UiCommand.PAGE_DOWN, UiCommand.RIGHT, UiCommand.HOME);
        assertThat(day.get()).isZero();
        day.bind(f.c.clock().intValue(60));
        assertThat(day.model().get()).isEqualTo(60);
        assertThat(f.unnamed()).isEmpty();
    }

    @Test
    void aKeyBindingFieldCapturesTheNextInput() {
        Fixture f = new Fixture();
        VersionedValue<String> model = f.c.clock().value("Space");
        KeyBindingField<String> k = f.c.keyBinding("Jump", "Press a key", model);
        f.add(k.element()).settle();
        assertThat(f.ui.text(k.labelElement()).toString()).as("no capture: no label").isEmpty();
        k.click();
        assertThat(k.capturing()).isFalse();
        ControlsGallery.FakeCapture capture = new ControlsGallery.FakeCapture();
        f.c.capture(capture);
        model.set("Enter");
        f.run();
        assertThat(f.ui.text(k.labelElement()).toString()).isEqualTo("Enter");
        f.click(k.element());
        assertThat(k.capturing()).isTrue();
        assertThat(k.element().is(UiState.EDITING)).isTrue();
        assertThat(f.ui.text(k.labelElement()).toString()).isEqualTo("Press a key");
        capture.press("F");
        assertThat(model.get()).isEqualTo("F");
        assertThat(k.capturing()).isFalse();
        f.focus(k.element()).press(UiCommand.ACCEPT);
        assertThat(capture.player).isZero();
        f.press(UiCommand.CANCEL);
        assertThat(k.capturing()).isFalse();
        assertThat(model.get()).isEqualTo("F");
        f.click(k.element());
        f.click(k.element());
        assertThat(k.capturing()).as("a second click stops").isFalse();
        k.bind(f.c.clock().value(null));
        assertThat(f.ui.text(k.labelElement()).toString()).isEmpty();
        assertThat(k.model().get()).isNull();
    }
}
