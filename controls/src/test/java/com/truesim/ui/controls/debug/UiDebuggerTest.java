package com.truesim.ui.controls.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.FrameStats;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.Tss;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The UI debugger: inspector, picking, resolved styles with their sources, timings, overlays. */
class UiDebuggerTest {

    private final DebuggerGallery g = DebuggerGallery.build(new Ui(Tss.defaultTheme(), UiTestAccess.nativeText()),
        1280, 800);
    private final Ui ui = g.ui;
    private final UiDebugger debugger = g.debugger;
    private final UiStore store = ui.store();

    private int boxes(String layer) {
        DrawList list = ui.layer(layer).drawList();
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.BOX) {
                n++;
            }
        }
        return n;
    }

    @Test
    void theInspectorShowsTheSelectedElement() {
        Element button = debugger.selected();
        assertThat(button.type()).isEqualTo("button");
        Element inspector = debugger.imm().window("UI inspector");
        assertThat(inspector).isNotNull();
        assertThat(debugger.imm().window("UI frame")).isNotNull();
        assertThat(text(inspector)).contains("root#root > panel", " > button").contains("Rect");
        // the resolved values with their rules and file:line
        assertThat(text(inspector)).contains("_basic.tss:");
        assertThat(debugger.shown()).isTrue();
        assertThat(debugger.imm().ui()).isSameAs(ui);
    }

    /** Every label's text under {@code e}, joined. */
    private String text(Element e) {
        StringBuilder out = new StringBuilder();
        collect(e.slot(), out);
        return out.toString();
    }

    private void collect(int slot, StringBuilder out) {
        if ("label".equals(store.type(slot))) {
            out.append(ui.text(store.element(slot))).append('\n');
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            collect(c, out);
        }
    }

    @Test
    void explainNamesTheRuleAndLineOfEachValue() {
        Element button = debugger.selected();
        List<StyleResolver.Origin> origins = ui.styles().explain(button.slot());
        StyleResolver.Origin background = origins.stream().filter(o -> o.key() == Styles.BACKGROUND).findFirst()
            .orElseThrow();
        assertThat(background.source()).matches("com/truesim/ui/style/_basic\\.tss:\\d+");
        assertThat(background.value()).isEqualTo(ui.styles().styleOf(button.slot()).get(Styles.BACKGROUND));
        assertThat(origins).extracting(StyleResolver.Origin::key).doesNotHaveDuplicates();
    }

    @Test
    void pickingSelectsTheElementUnderThePointer() {
        Element target = g.first("checkbox");
        float x = ui.layout().x(target.slot()) + 3;
        float y = ui.layout().y(target.slot()) + ui.layout().height(target.slot()) / 2;
        debugger.pick(true);
        assertThat(debugger.picking()).isTrue();
        g.input.mouse(x, y, 0);
        g.frames(2);
        assertThat(debugger.hovered()).isNotNull();
        assertThat(isInside(debugger.hovered().slot(), target.slot())).isTrue();
        boolean checked = target.is(com.truesim.ui.core.UiState.CHECKED);
        g.input.mouse(x, y, Pointer.PRIMARY);
        g.frames(1);
        assertThat(debugger.picking()).isFalse();
        assertThat(isInside(debugger.selected().slot(), target.slot())).isTrue();
        g.input.mouse(x, y, 0);
        g.frames(2);
        assertThat(target.is(com.truesim.ui.core.UiState.CHECKED)).as("the press went to the debugger")
            .isEqualTo(checked);
    }

    private boolean isInside(int slot, int ancestor) {
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }

    @Test
    void timingsAreMeasuredWhileItShows() {
        FrameStats stats = ui.stats();
        assertThat(stats.enabled()).isTrue();
        g.frames(2);
        assertThat(stats.totalNanos()).isPositive();
        assertThat(stats.totalNanos()).isGreaterThanOrEqualTo(stats.styleNanos() + stats.layoutNanos());
        assertThat(stats.passes()).isPositive();
        assertThat(stats.uploadBytes()).isPositive();
        assertThat(stats.inputNanos()).isGreaterThanOrEqualTo(0);
        assertThat(stats.listenerNanos()).isPositive();
        assertThat(stats.recordNanos()).isPositive();
        assertThat(stats.recorded()).isEqualTo(stats.recordedCount());
        debugger.show(false);
        debugger.overlay(UiDebugger.BOUNDS, false);
        debugger.overlay(UiDebugger.CLIPS, false);
        assertThat(stats.enabled()).isFalse();
        g.frames(2);
        assertThat(debugger.imm().window("UI inspector")).isNull(); // collected
        debugger.toggle();
        g.frames(1);
        assertThat(debugger.imm().window("UI inspector")).isNotNull();
    }

    @Test
    void overlaysDrawBoxesOnTheDebugLayer() {
        int both = boxes(Layers.DEBUG);
        assertThat(both).isGreaterThan(200); // every element's bounds, the clips
        assertThat(debugger.overlays()).isEqualTo(UiDebugger.BOUNDS | UiDebugger.CLIPS);
        debugger.overlay(UiDebugger.BOUNDS, false);
        g.frames(1);
        int clips = boxes(Layers.DEBUG);
        assertThat(clips).isLessThan(both).isPositive();
        debugger.overlay(UiDebugger.CLIPS, false);
        debugger.overlay(UiDebugger.PICKING, true);
        g.frames(1);
        assertThat(boxes(Layers.DEBUG)).isPositive(); // the occupied cells
        debugger.overlay(UiDebugger.PICKING, false);
        debugger.overlay(UiDebugger.DIRTY, true);
        g.input.mouse(600, 400, 0);
        g.frames(2);
        assertThat(ui.stats().recordedCount()).isPositive();
        debugger.overlay(UiDebugger.DIRTY, false);
        debugger.select(null);
        debugger.show(false);
        g.frames(2);
        assertThat(ui.layout().shown(store.firstChild(ui.layer(Layers.DEBUG).rootSlot()))).isFalse();
    }

    @Test
    void theTreeWalksExpandedElements() {
        debugger.depthLimit(3);
        g.frames(2);
        Element inspector = debugger.imm().window("UI inspector");
        assertThat(text(inspector)).contains("root#root");
        assertThat(debugger.selected()).isNotNull();
    }
}
