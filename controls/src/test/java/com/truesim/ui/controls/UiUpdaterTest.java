package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Updaters run before and after layout; code-owned layout inputs survive restyling; labels name live text. */
class UiUpdaterTest {

    static final StyleSheet SHEET = Tss.compileString("updater.tss", """
        root { layout: none; }
        panel { layout: stack; width: 50px; height: 20px; offsets: 5px 5px 0 0; anchors: 0 0 0 0; }
        panel:selected { width: 60px; height: 30px; offsets: 7px 7px 0 0; layout: flex; }
        """);

    @Test
    void updatersRunAroundLayoutAndMayAskForASecondPass() {
        Ui ui = new Ui(SHEET, null);
        Element box = ui.panel(null);
        ui.root().add(box);
        List<String> calls = new ArrayList<>();
        boolean[] again = {false};
        Ui.Updater first = new Ui.Updater() {
            @Override
            public void beforeLayout(long nanos) {
                calls.add("before " + nanos);
            }

            @Override
            public boolean afterLayout() {
                calls.add("after " + ui.layout().width(box.slot()));
                if (again[0]) {
                    again[0] = false;
                    ui.layout().width(box.slot(), 80, Unit.PX);
                    ui.codeLayout(box, Ui.CODE_WIDTH);
                    return true;
                }
                return false;
            }
        };
        Ui.Updater second = nanos -> calls.add("second");
        ui.addUpdater(first);
        ui.addUpdater(second);
        ui.update(200, 100);
        assertThat(calls).containsExactly("before 0", "second", "after 50.0");
        calls.clear();
        again[0] = true;
        ui.update(200, 100);
        assertThat(calls).containsExactly("before 0", "second", "after 50.0");
        assertThat(ui.layout().width(box.slot())).isEqualTo(80); // laid out again in the same frame
        ui.removeUpdater(second);
        ui.removeUpdater(second);
        calls.clear();
        ui.update(200, 100);
        assertThat(calls).containsExactly("before 0", "after 80.0");
        for (int i = 0; i < 4; i++) {
            ui.addUpdater(nanos -> { });
        }
        ui.update(200, 100);
    }

    @Test
    void codeOwnedInputsStayWhenTheStyleResolvesAgain() {
        Ui ui = new Ui(SHEET, null);
        Element box = ui.panel(null);
        ui.root().add(box);
        ui.update(200, 100);
        assertThat(ui.layout().x(box.slot())).isEqualTo(5);
        ui.codeLayout(box, Ui.CODE_KIND | Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        assertThat(ui.codeLayout(box)).isEqualTo(15);
        ui.layout().kind(box.slot(), LayoutKind.OVERLAY).offsets(box.slot(), 30, 40, 0, 0)
            .width(box.slot(), 11, Unit.PX).height(box.slot(), 12, Unit.PX);
        box.state(UiState.SELECTED, true);
        ui.update(200, 100);
        assertThat(ui.layout().x(box.slot())).isEqualTo(30);
        assertThat(ui.layout().width(box.slot())).isEqualTo(11);
        assertThat(ui.layout().height(box.slot())).isEqualTo(12);
        ui.codeLayout(box, 0);
        box.state(UiState.SELECTED, false);
        ui.update(200, 100);
        assertThat(ui.layout().x(box.slot())).isEqualTo(5);
        assertThat(ui.layout().width(box.slot())).isEqualTo(50);
    }

    @Test
    void aLabelsAccessibleNameIsItsCurrentText() {
        Ui ui = new Ui(SHEET, UiTest.jdkText());
        Element label = ui.label(null, "one");
        ui.root().add(label);
        ui.text(label, "two");
        Semantics.Node tree = Semantics.of(ui.store());
        assertThat(tree.toString()).contains("TEXT \"two\"");
        assertThat(ui.store().role(label.slot())).isEqualTo(Role.TEXT);
    }
}
