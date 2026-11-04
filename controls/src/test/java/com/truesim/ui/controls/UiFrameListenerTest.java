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

/** Frame listeners run before and after layout; code-owned layout inputs survive restyling; labels name live text. */
class UiFrameListenerTest {

    static final StyleSheet SHEET = Tss.compileString("updater.tss", """
        root { layout: none; }
        panel { layout: stack; width: 50px; height: 20px; offsets: 5px 5px 0 0; anchors: 0 0 0 0; }
        panel:selected { width: 60px; height: 30px; offsets: 7px 7px 0 0; layout: flex; }
        """);

    @Test
    void listenersRunAroundLayoutAndChangesAfterLayoutAreLaidOutAgain() {
        Ui ui = new Ui(SHEET, null);
        Element box = ui.panel(null);
        ui.root().add(box);
        List<String> calls = new ArrayList<>();
        boolean[] again = {false};
        Ui.FrameListener first = new Ui.FrameListener() {
            @Override
            public void frame(long nanos) {
                calls.add("before " + nanos);
            }

            @Override
            public void afterLayout() {
                calls.add("after " + ui.layout().width(box.slot()));
                if (again[0]) {
                    again[0] = false;
                    ui.codeLayout(box, Ui.CODE_WIDTH);
                    ui.layout().width(box.slot(), 80, Unit.PX);
                }
            }
        };
        Ui.FrameListener second = nanos -> calls.add("second");
        ui.addFrameListener(first);
        ui.addFrameListener(second);
        ui.update(200, 100);
        assertThat(calls).containsExactly("before 0", "second", "after 50.0");
        calls.clear();
        again[0] = true;
        ui.update(200, 100);
        // laid out again in the same frame, and the listeners see it
        assertThat(calls).containsExactly("before 0", "second", "after 50.0", "after 80.0");
        assertThat(ui.layout().width(box.slot())).isEqualTo(80);
        ui.removeFrameListener(second);
        ui.removeFrameListener(second);
        calls.clear();
        ui.update(200, 100);
        assertThat(calls).containsExactly("before 0", "after 80.0");
        for (int i = 0; i < 4; i++) {
            ui.addFrameListener(nanos -> { });
        }
        ui.update(200, 100);
        assertThat(ui.input()).isNull();
    }

    @Test
    void aListenerThatNeverSettlesStopsAfterTheMostPasses() {
        Ui ui = new Ui(SHEET, null);
        Element box = ui.panel(null);
        ui.root().add(box);
        ui.codeLayout(box, Ui.CODE_WIDTH);
        int[] passes = {0};
        ui.addFrameListener(new Ui.FrameListener() {
            @Override
            public void frame(long nanos) {
                passes[0] = 0;
            }

            @Override
            public void afterLayout() {
                passes[0]++;
                ui.layout().width(box.slot(), 10 + passes[0], Unit.PX);
            }
        });
        ui.update(200, 100);
        assertThat(passes[0]).isEqualTo(Ui.MAX_PASSES);
    }

    @Test
    void codeOwnedFlexInputsStayWhenTheStyleResolvesAgain() {
        Ui ui = new Ui(Tss.compileString("flex.tss", """
            root { layout: flex; direction: row; }
            panel { grow: 1; basis: 0; height: 10px; }
            panel:selected { grow: 3; }
            """), null);
        Element a = ui.panel(null);
        Element b = ui.panel(null);
        ui.root().add(a).add(b);
        ui.update(200, 100);
        assertThat(ui.layout().width(a.slot())).isEqualTo(100);
        ui.codeLayout(a, Ui.CODE_FLEX);
        ui.layout().grow(a.slot(), 1).shrink(a.slot(), 1).basis(a.slot(), 0, Unit.PX);
        a.state(UiState.SELECTED, true);
        ui.update(200, 100);
        assertThat(ui.layout().width(a.slot())).isEqualTo(100);
        ui.codeLayout(a, 0);
        a.state(UiState.SELECTED, false);
        ui.update(200, 100);
        a.state(UiState.SELECTED, true);
        ui.update(200, 100);
        assertThat(ui.layout().width(a.slot())).isEqualTo(150);
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
