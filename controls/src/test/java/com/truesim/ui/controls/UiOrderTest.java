package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

class UiOrderTest {

    /** Children made before their parent: resolving a child resolves the parent, whose style must still apply. */
    @Test
    void elementsMadeBeforeTheirParentsStillApplyTheParentsStyle() {
        Ui ui = new Ui(Tss.compileString("order.tss", """
            panel.card { layout: stack; direction: column; width: 380px; padding: 8px; }
            panel.row { layout: flex; direction: row; }
            label { font-size: 14px; font-family: sans-serif; }
            """), UiTest.jdkText());
        Element card = ui.panel("card");
        ui.root().add(card);
        Element value = ui.label(null, "87.5 %");
        Element row = ui.panel("row");
        Element caption = ui.label(null, "Hull integrity");
        row.add(caption).add(value);
        card.add(row);
        UiTest.settle(ui, 800, 600);
        assertThat(ui.layout().y(value.slot())).isEqualTo(ui.layout().y(caption.slot()));
        assertThat(ui.layout().x(value.slot())).isGreaterThan(ui.layout().x(caption.slot()));

        // a slot reused by a new element applies its style again
        row.destroy();
        Element again = ui.panel("row");
        card.add(again);
        Element a = ui.panel(null);
        Element b = ui.panel(null);
        again.add(a).add(b);
        ui.styles().inline(a.slot(), com.truesim.ui.style.InlineStyle.builder()
            .set(com.truesim.ui.style.Styles.WIDTH, com.truesim.ui.style.value.Length.px(10)).build());
        UiTest.settle(ui, 800, 600);
        assertThat(ui.layout().y(b.slot())).isEqualTo(ui.layout().y(a.slot()));
    }
}
