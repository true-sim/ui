package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.TextDecoration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mnemonics: parsing the marker, underlines following the modifier, menus, buttons, labels for fields, scopes. */
class MnemonicsTest {

    @Test
    void theMarkerIsTakenOutAndItsLetterFound() {
        StringBuilder out = new StringBuilder();
        assertThat(Mnemonics.strip("&File", out)).isZero();
        assertThat(out.toString()).isEqualTo("File");
        assertThat(Mnemonics.strip("Save &as...", out)).isEqualTo(5);
        assertThat(out.toString()).isEqualTo("Save as...");
        assertThat(Mnemonics.strip("Salt & pepper", out)).as("& before a space is literal").isEqualTo(-1);
        assertThat(out.toString()).isEqualTo("Salt & pepper");
        assertThat(Mnemonics.strip("R&&D &Tools", out)).as("&& is an ampersand").isEqualTo(4);
        assertThat(out.toString()).isEqualTo("R&D Tools");
        assertThat(Mnemonics.strip("&One &Two", out)).as("the first marker counts").isZero();
        assertThat(out.toString()).isEqualTo("One Two");
        assertThat(Mnemonics.strip("trailing&", out)).isEqualTo(-1);
        assertThat(out.toString()).isEqualTo("trailing&");
        assertThat(Mnemonics.strip("&Écran", out)).isZero();
        assertThat(Mnemonics.key(out, 0)).isEqualTo('é');
        assertThat(Mnemonics.key(out, -1)).isZero();
    }

    @Test
    void underlinesShowWhileTheModifierIsHeldAndNamesHaveNoMarker() {
        Fixture f = new Fixture();
        Button save = f.c.button("&Save");
        f.add(save.element()).settle();
        assertThat(save.name()).isEqualTo("Save");
        assertThat(f.ui.text(save.labelElement()).toString()).isEqualTo("Save");
        assertThat(f.ui.underlined(save.labelElement())).isZero();
        Element part = part(f, save.labelElement());
        assertThat(f.ui.styles().styleOf(part.slot()).get(Styles.TEXT_DECORATION)).isEqualTo(TextDecoration.NONE);
        int before = drawn(f);
        f.synthetic.mnemonicsShown(true);
        f.run().run();
        assertThat(f.c.mnemonics().shown()).isTrue();
        assertThat(f.ui.styles().styleOf(part.slot()).get(Styles.TEXT_DECORATION))
            .isEqualTo(TextDecoration.UNDERLINE);
        assertThat(drawn(f)).as("the underline draws").isEqualTo(before + 1);
        f.synthetic.mnemonicsShown(false);
        f.run().run();
        assertThat(drawn(f)).isEqualTo(before);
        save.text("Save &all");
        f.run();
        assertThat(f.ui.underlined(save.labelElement())).isEqualTo(5);
        assertThat(save.name()).isEqualTo("Save all");
        save.text("Plain");
        f.run();
        assertThat(f.ui.underlined(save.labelElement())).isEqualTo(-1);
    }

    @Test
    void theGarishThemeAlwaysShowsThem() {
        Fixture f = new Fixture(Fixture.GARISH, Fixture.jdkText());
        Button save = f.c.button("&Save");
        f.add(save.element()).settle();
        Element part = part(f, save.labelElement());
        assertThat(f.ui.styles().styleOf(part.slot()).get(Styles.TEXT_DECORATION))
            .isEqualTo(TextDecoration.UNDERLINE);
    }

    @Test
    void aButtonsLetterClicksItAndDuplicatesCycleTheFocus() {
        Fixture f = new Fixture();
        List<String> log = new ArrayList<>();
        Button save = f.c.button("&Save").onClick(() -> log.add("save"));
        Button search = f.c.button("&Search").onClick(() -> log.add("search"));
        Button open = f.c.button("&Open").onClick(() -> log.add("open"));
        Checkbox grid = f.c.checkbox("&Grid");
        Button off = f.c.button("&Disabled").onClick(() -> log.add("disabled"));
        off.enabled(false);
        f.add(save.element()).add(search.element()).add(open.element()).add(grid.element()).add(off.element())
            .settle();
        f.synthetic.mnemonic('O');
        f.run();
        assertThat(log).as("upper or lower case").containsExactly("open");
        f.synthetic.mnemonic('g');
        f.run();
        assertThat(grid.state()).isEqualTo(CheckState.CHECKED);
        f.synthetic.mnemonic('d');
        f.run();
        assertThat(log).as("not while disabled").containsExactly("open");
        f.synthetic.mnemonic('s');
        f.run();
        assertThat(log).as("two have s: focus moves, nothing runs").containsExactly("open");
        assertThat(f.ui.interaction().focused(0)).isEqualTo(save.element());
        f.synthetic.mnemonic('s');
        f.run();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(search.element());
        f.synthetic.mnemonic('s');
        f.run();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(save.element());
        f.synthetic.mnemonic('x');
        f.run();
        assertThat(f.c.mnemonics().press('x', 0)).isFalse();
        assertThat(f.c.mnemonics().count()).isEqualTo(5);
    }

    @Test
    void aLabelForAFieldFocusesIt() {
        Fixture f = new Fixture();
        Button target = f.c.button("Target");
        Element label = f.c.labelFor("&Name", target.element());
        f.add(label).add(target.element()).settle();
        assertThat(f.ui.text(label).toString()).isEqualTo("Name");
        assertThat(f.ui.store().label(label.slot()).toString()).isEqualTo("Name");
        f.synthetic.mnemonic('n');
        f.run();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(target.element());
        assertThat(target.clicks()).isZero();
    }

    @Test
    void theMenuBarsTitleOpensItsMenuAndTheOpenMenuTakesTheLetters() {
        Fixture f = new Fixture();
        List<String> log = new ArrayList<>();
        MenuBar bar = f.c.menuBar("Main");
        Menu file = bar.menu("&File");
        file.item("&New", () -> log.add("new"));
        file.item("&Open", () -> log.add("open"));
        file.item("Op&tions", () -> log.add("options"));
        file.item("&Other", () -> log.add("other"));
        Menu recent = file.submenu("&Recent");
        recent.item("&Level one", () -> log.add("one"));
        Menu view = bar.menu("&View");
        view.item("&Zoom", () -> log.add("zoom"));
        Button zoomButton = f.c.button("&Zoom").onClick(() -> log.add("button"));
        f.add(bar.element()).add(zoomButton.element()).settle();
        assertThat(file.item(0).element().toString()).doesNotContain("&");
        assertThat(f.ui.store().label(file.item(0).element().slot()).toString()).isEqualTo("New");
        f.synthetic.mnemonic('f');
        f.run().run();
        assertThat(file.isOpen()).isTrue();
        f.synthetic.mnemonic('z');
        f.run();
        assertThat(log).as("the open menu takes every letter").isEmpty();
        f.synthetic.mnemonic('o');
        f.run();
        assertThat(log).as("two items have o: focus moves").isEmpty();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(file.item(1).element());
        f.synthetic.mnemonic('o');
        f.run();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(file.item(3).element());
        f.synthetic.mnemonic('r');
        f.run().run();
        assertThat(recent.isOpen()).as("a submenu opens").isTrue();
        f.synthetic.mnemonic('l');
        f.run().run();
        assertThat(log).containsExactly("one");
        assertThat(file.isOpen()).isFalse();
        f.synthetic.mnemonic('v');
        f.run().run();
        assertThat(view.isOpen()).isTrue();
        f.synthetic.mnemonic('z');
        f.run().run();
        assertThat(log).containsExactly("one", "zoom");
        f.synthetic.mnemonic('z');
        f.run();
        assertThat(log).as("menus closed: the button").containsExactly("one", "zoom", "button");
        f.synthetic.mnemonic('t');
        f.run();
        assertThat(log).as("items of closed menus are out of reach").hasSize(3);
    }

    @Test
    void lettersStayInTheFocusedWindowOrDialog() {
        Fixture f = new Fixture();
        List<String> log = new ArrayList<>();
        Button hud = f.c.button("&Apply").onClick(() -> log.add("hud"));
        Element window = f.ui.panel(null);
        window.semantics(Role.WINDOW, "Tool");
        Button inWindow = f.c.button("&Apply").onClick(() -> log.add("window"));
        Button other = f.c.button("&Keep");
        window.add(inWindow.element()).add(other.element());
        f.add(hud.element()).add(window).settle();
        f.synthetic.mnemonic('a');
        f.run();
        assertThat(log).as("no focus: what is in no window").containsExactly("hud");
        f.focus(other.element());
        f.synthetic.mnemonic('a');
        f.run();
        assertThat(log).as("the focused window's").containsExactly("hud", "window");
        Dialog d = f.c.confirm("Quit?", "Sure?", "&Yes", "&No");
        d.show();
        f.run(30);
        f.synthetic.mnemonic('y');
        f.run(30);
        assertThat(d.result()).isEqualTo(1);
    }

    private static Element part(Fixture f, Element label) {
        UiStore s = f.ui.store();
        for (int c = s.firstChild(label.slot()); c != UiStore.NONE; c = s.nextSibling(c)) {
            if ("span".equals(s.type(c))) {
                return s.element(c);
            }
        }
        throw new AssertionError("no mnemonic part");
    }

    private static int drawn(Fixture f) {
        int n = 0;
        var list = f.ui.layers()[0];
        for (int i = 0; i < list.size(); i++) {
            if (com.truesim.ui.core.draw.Primitive.kind(list.header(i)) != com.truesim.ui.core.draw.Primitive.EMPTY) {
                n++;
            }
        }
        return n;
    }

    static boolean focused(Fixture f, Element e) {
        return e.is(UiState.FOCUSED);
    }
}
