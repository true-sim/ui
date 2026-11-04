package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.DrawListDump;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The frame in two phases, as an engine runs it around the game's update: {@link Ui#input} at the frame's start (game
 * code then sees this frame's clicks), {@link Ui#update(float, float)} after it (what game code changed shows in this
 * frame), with the frame's input still there for listeners in the second phase.
 */
class UiPhasesTest {

    static final StyleSheet SHEET = Tss.compileString("phases.tss", """
        root { layout: none; }
        panel { width: 80px; height: 30px; offsets: 10px 10px 0 0; anchors: 0 0 0 0; }
        label { offsets: 10px 60px 0 0; anchors: 0 0 0 0; font-size: 14px; }
        """);

    private static long glyphs(Ui ui) {
        return DrawListDump.of(ui.layers()[0]).lines().filter(l -> l.startsWith("glyph")).count();
    }

    @Test
    void gameCodeSeesThisFramesClickAndItsChangesShowInThisFrame() {
        Ui ui = new Ui(SHEET, UiTestAccess.jdkText());
        Element button = ui.panel(null);
        Element score = ui.label(null, "0");
        ui.root().add(button).add(score);
        int[] clicks = {0};
        ui.on(button, EventType.CLICK, e -> clicks[0]++);
        UiInput input = new UiInput(4);
        SyntheticInput synthetic = new SyntheticInput();
        long nanos = 1_000_000_000L;
        for (int i = 0; i < 10; i++) { // glyphs in the atlas, the layout drawn
            input.beginFrame();
            synthetic.poll(input);
            ui.update(400, 200, input, nanos += 16_000_000L);
        }
        long before = glyphs(ui);
        synthetic.mouse(30, 20, Pointer.PRIMARY);
        input.beginFrame();
        synthetic.poll(input);
        ui.update(400, 200, input, nanos += 16_000_000L);
        synthetic.mouse(30, 20, 0);

        // the frame: input first
        input.beginFrame();
        synthetic.poll(input);
        ui.input(input, nanos += 16_000_000L);
        assertThat(clicks[0]).as("the game's update sees the click").isEqualTo(1);
        // the game's update reacts to it
        ui.text(score, "1000");
        // the UI's update after it: the change is drawn in this frame
        ui.update(400, 200);
        assertThat(ui.text(score).toString()).isEqualTo("1000");
        assertThat(glyphs(ui)).as("four digits drawn this frame").isEqualTo(before + 3);
    }

    @Test
    void listenersOfTheSecondPhaseSeeTheFramesInputAndTime() {
        Ui ui = new Ui(SHEET, null);
        List<String> seen = new ArrayList<>();
        ui.addFrameListener(nanos -> seen.add(nanos + " " + (ui.input() != null)));
        UiInput input = new UiInput(4);
        input.beginFrame();
        ui.input(input, 42);
        assertThat(seen).isEmpty();
        ui.update(100, 100);
        assertThat(seen).containsExactly("42 true");
        ui.update(100, 100); // a frame without input
        assertThat(seen).containsExactly("42 true", "0 false");
        ui.input(null, 7);
        ui.update(100, 100);
        assertThat(seen).containsExactly("42 true", "0 false", "7 false");
        assertThat(ui.frames()).isEqualTo(3);
    }
}
