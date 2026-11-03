package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.ui.controls.text.HexField;
import com.truesim.ui.controls.text.SearchField;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.UiCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The text controls in the basic controls' hooks: a search field filtering a drop-down, a hex field in a colour picker. */
class TextHooksTest {

    @Test
    void aSearchFieldFiltersADropDownAsItIsTyped() {
        Fixture f = new Fixture();
        ChangeClock clock = f.ui.store().clock();
        SearchField search = SearchField.create(f.ui, null, "Filter", "Clear");
        DropDown<String> d = f.c.dropDown("Country", List.of("Austria", "Australia", "Belgium", "Brazil"),
            String::toString, clock.intValue(0)).filter(search);
        f.add(d.element()).settle();
        d.open();
        f.run(3);
        assertThat(d.isOpen()).isTrue();
        assertThat(search.element().parent()).isEqualTo(d.list());
        assertThat(f.ui.interaction().focused(0)).isEqualTo(search.editor().element());
        f.type("au");
        f.run();
        assertThat(search.text().toString()).isEqualTo("au");
        assertThat(d.shown(0)).isTrue();
        assertThat(d.shown(1)).isTrue();
        assertThat(d.shown(2)).isFalse();
        assertThat(d.shown(3)).isFalse();
        search.clear();
        f.run();
        assertThat(d.shown(2)).isTrue();
        assertThat(search.version()).isPositive();
    }

    @Test
    void aHexFieldShowsAndSetsThePickersColour() {
        Fixture f = new Fixture();
        ColorPicker p = f.c.colorPicker("Tint", f.ui.store().clock().longValue(Rgba.srgb(255, 0, 0, 1)));
        HexField hex = HexField.attach(f.ui, p, "Hex value");
        f.add(p.element()).settle();
        assertThat(p.hexLabel().parent()).isNull();
        assertThat(hex.field().element().parent()).isEqualTo(p.hexSlot());
        assertThat(hex.field().text().toString()).isEqualToIgnoringCase("#ff0000ff");
        // typed and submitted
        f.focus(hex.field().element());
        hex.field().text("");
        f.type("#00ff00");
        assertThat(hex.field().text().toString()).isEqualTo("#00ff00");
        f.press(UiCommand.SUBMIT);
        assertThat(p.hex().toString()).isEqualToIgnoringCase("#00ff00ff");
        // invalid text changes nothing and shows :invalid
        hex.field().text("");
        f.type("12345");
        assertThat(hex.field().valid()).isFalse();
        f.press(UiCommand.SUBMIT);
        assertThat(p.hex().toString()).isEqualToIgnoringCase("#00ff00ff");
        // leaving puts the picker's value back; a change elsewhere shows
        f.ui.interaction().blur(0);
        f.run();
        assertThat(hex.field().text().toString()).isEqualToIgnoringCase("#00ff00ff");
        p.hex("#0000ff");
        f.run();
        assertThat(hex.field().text().toString()).isEqualToIgnoringCase("#0000ffff");
    }
}
