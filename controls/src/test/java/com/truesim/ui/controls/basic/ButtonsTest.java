package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.ImageSpec;
import com.truesim.ui.style.value.Paint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Buttons, toggles, checkboxes, radios, switches and segmented controls: pointer, keys, models, states, looks. */
class ButtonsTest {

    @Test
    void aButtonClicksByPointerAndByAcceptShowingThePressBoth() {
        Fixture f = new Fixture();
        List<String> log = new ArrayList<>();
        Button b = f.c.button("Save").onClick(() -> log.add("save"));
        f.ui.on(b.element(), EventType.CLICK, e -> log.add("listener"));
        f.add(b.element()).settle();
        assertThat(f.ui.store().role(b.element().slot())).isEqualTo(Role.BUTTON);
        f.click(b.element());
        assertThat(log).as("other listeners hear the click too").containsExactly("save", "listener");
        assertThat(b.clicks()).isEqualTo(1);
        f.focus(b.element());
        f.press(UiCommand.ACCEPT);
        assertThat(log).containsExactly("save", "listener", "save", "listener");
        assertThat(b.element().is(UiState.PRESSED)).as("ACCEPT shows the press").isTrue();
        f.advance(0.2f);
        assertThat(b.element().is(UiState.PRESSED)).as("for press-flash seconds").isFalse();
        b.text("Saved");
        assertThat(f.ui.text(b.labelElement()).toString()).isEqualTo("Saved");
        assertThat(b.name()).isEqualTo("Saved");
        b.enabled(false);
        f.click(b.element());
        b.click();
        assertThat(log).hasSize(4);
        assertThat(b.enabled()).isFalse();
        Button icon = f.c.iconButton("close", "Close");
        assertThat(icon.labelElement()).isNull();
        assertThat(icon.iconElement().id()).isEqualTo("close");
        assertThatThrownBy(() -> icon.text("x")).isInstanceOf(IllegalStateException.class);
        f.add(icon.element()).settle();
        ImageSpec spec = f.ui.styles().styleOf(icon.iconElement().slot()).get(Styles.IMAGE);
        assertThat(spec.name()).isEqualTo("icons/close");
        assertThat(icon.name("Shut").name()).isEqualTo("Shut");
    }

    @Test
    void anAcceptWithoutAClockClearsThePressNextFrame() {
        Fixture f = new Fixture();
        Button b = f.c.button("Go");
        f.add(b.element());
        f.ui.update(400, 300);
        f.ui.interaction().focus(b.element());
        f.input.beginFrame();
        f.input.command(UiCommand.ACCEPT, 0, 0);
        f.ui.update(400, 300, f.input, 0);
        assertThat(b.clicks()).isEqualTo(1);
        f.ui.update(400, 300);
        assertThat(b.element().is(UiState.PRESSED)).isFalse();
    }

    @Test
    void aToggleFollowsItsModelBothWays() {
        Fixture f = new Fixture();
        VersionedBoolean bold = f.c.clock().booleanValue(false);
        ToggleButton t = f.c.toggle("Bold", bold);
        f.add(t.element()).settle();
        assertThat(t.on()).isFalse();
        f.click(t.element());
        assertThat(bold.get()).isTrue();
        assertThat(t.element().is(UiState.CHECKED)).isTrue();
        assertThat(t.labelElement().is(UiState.CHECKED)).as("parts carry the state").isTrue();
        bold.set(false);
        f.run();
        assertThat(t.element().is(UiState.CHECKED)).as("a model change shows without a refresh").isFalse();
        VersionedBoolean other = f.c.clock().booleanValue(true);
        t.bind(other);
        assertThat(t.model()).isSameAs(other);
        assertThat(t.element().is(UiState.CHECKED)).isTrue();
        assertThat(f.ui.store().role(t.element().slot())).isEqualTo(Role.TOGGLE_BUTTON);
    }

    @Test
    void aCheckboxCyclesItsThreeStates() {
        Fixture f = new Fixture();
        VersionedValue<CheckState> model = f.c.clock().value(CheckState.INDETERMINATE);
        Checkbox box = f.c.checkbox("All", model);
        f.add(box.element()).settle();
        assertThat(box.element().is(UiState.INDETERMINATE)).isTrue();
        assertThat(box.mark().is(UiState.INDETERMINATE)).isTrue();
        f.click(box.element());
        assertThat(box.state()).isEqualTo(CheckState.CHECKED);
        assertThat(box.box().is(UiState.CHECKED)).isTrue();
        f.focus(box.element()).press(UiCommand.ACCEPT);
        assertThat(model.get()).isEqualTo(CheckState.UNCHECKED);
        model.set(CheckState.CHECKED);
        f.run();
        assertThat(box.element().is(UiState.CHECKED)).isTrue();
        Paint checked = f.ui.styles().styleOf(box.box().slot()).get(Styles.BACKGROUND);
        model.set(CheckState.UNCHECKED);
        f.run();
        assertThat(f.ui.styles().styleOf(box.box().slot()).get(Styles.BACKGROUND)).isNotEqualTo(checked);
        assertThat(box.model()).isSameAs(model);
        Checkbox own = f.c.checkbox("Own");
        assertThat(own.state()).isEqualTo(CheckState.UNCHECKED);
        own.bind(f.c.clock().value(CheckState.CHECKED));
        assertThat(own.element().is(UiState.CHECKED)).isTrue();
    }

    @Test
    void aSwitchFlipsAndMovesItsKnobByTheTheme() {
        Fixture f = new Fixture();
        VersionedBoolean on = f.c.clock().booleanValue(false);
        Switch s = f.c.toggleSwitch("V-sync", on);
        f.add(s.element()).settle();
        float off = f.ui.layout().x(s.knob().slot());
        f.click(s.element());
        assertThat(on.get()).isTrue();
        assertThat(s.on()).isTrue();
        f.settle();
        assertThat(f.ui.layout().x(s.knob().slot())).as("offsets transition to the checked place").isGreaterThan(off);
        on.set(false);
        f.run();
        assertThat(s.knob().is(UiState.CHECKED)).isFalse();
        s.bind(f.c.clock().booleanValue(true));
        assertThat(s.model().get()).isTrue();
        Switch unnamed = new Switch(f.c, null, "Mute", f.c.clock().booleanValue(false));
        assertThat(unnamed.name()).isEqualTo("Mute");
    }

    @Test
    void radiosChooseOneAndArrowsMoveTheChoice() {
        Fixture f = new Fixture();
        VersionedInt model = f.c.clock().intValue(0);
        RadioGroup g = f.c.radioGroup("Difficulty", model);
        g.add("Easy");
        g.add("Normal");
        RadioGroup.Option hard = g.add("Hard");
        f.add(g.element()).settle();
        assertThat(g.count()).isEqualTo(3);
        assertThat(hard.index()).isEqualTo(2);
        f.click(hard.element());
        assertThat(model.get()).isEqualTo(2);
        assertThat(g.option(2).element().is(UiState.CHECKED)).isTrue();
        assertThat(g.option(0).element().is(UiState.CHECKED)).isFalse();
        f.focus(g.option(2).element());
        f.press(UiCommand.DOWN);
        assertThat(g.selected()).as("wraps").isZero();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(g.option(0).element());
        g.option(2).enabled(false);
        f.press(UiCommand.UP);
        assertThat(g.selected()).as("skips the disabled").isEqualTo(1);
        f.press(UiCommand.LEFT);
        assertThat(g.selected()).as("left is not this group's").isEqualTo(1);
        model.set(-1);
        f.run();
        for (int i = 0; i < 3; i++) {
            assertThat(g.option(i).element().is(UiState.CHECKED)).isFalse();
        }
        g.bind(f.c.clock().intValue(1));
        assertThat(g.model().get()).isEqualTo(1);
        assertThat(f.ui.store().role(hard.element().slot())).isEqualTo(Role.RADIO);
    }

    @Test
    void aSegmentedControlMovesWithLeftAndRight() {
        Fixture f = new Fixture();
        SegmentedControl s = f.c.segmented("Range", f.c.clock().intValue(0));
        s.add("Day");
        s.add(null, "more", "More");
        f.add(s.element()).settle();
        f.focus(s.option(0).element()).press(UiCommand.RIGHT);
        assertThat(s.selected()).isEqualTo(1);
        f.press(UiCommand.DOWN);
        assertThat(s.selected()).as("down moves focus, not the choice").isEqualTo(1);
        s.select(0);
        assertThat(s.option(0).element().is(UiState.CHECKED)).isTrue();
    }
}
