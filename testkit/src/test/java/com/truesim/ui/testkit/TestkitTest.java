package com.truesim.ui.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestkitTest {

    @TempDir
    Path dir;

    private static DrawList sample() {
        DrawList list = new DrawList();
        int start = list.allocate(8);
        int clip = list.addClip(0, 0, 10, 10, 0);
        list.begin(start, 8);
        list.box(0, 0, 10, 10, Rgba.WHITE, 1, 1, 1, 1, 1, Rgba.BLACK);
        list.box(0, 0, 10, 10, Rgba.WHITE, 1, 1, 1, 1, 0, 0);
        list.clip(clip);
        list.gradientBox(0, 0, 10, 10, Rgba.WHITE, Rgba.BLACK, 0, 0, 0, 0, 0, 0, 0);
        list.shadow(0, 0, 10, 10, Rgba.BLACK, 2, 2, 2, 2, 4);
        list.glyph(0, 0, 5, 5, 0, 0, 1, 1, 2, 3, Rgba.WHITE, 0, 0, 0);
        list.image(0, 0, 5, 5, 0, 0, 1, 1, 3, Rgba.WHITE);
        list.nineSlice(0, 0, 5, 5, 0, 0, 1, 1, 4, Rgba.WHITE, 1, 1, 1, 1, 1, 1, 1, 1);
        list.line(0, 0, 5, 5, 1, Rgba.WHITE);
        list.end();
        return list;
    }

    @Test
    void dumpsEveryKindAndComparesWithGoldens() throws Exception {
        String text = DrawListDump.of(sample());
        assertThat(text.lines()).hasSize(8);
        assertThat(text).contains("box 0.00,0.00 10.00,10.00 a=(1.00 1.00 1.00 1.00) radii=1.00,1.00 1.00,1.00 border=1.00")
            .contains("box clip=1 flags=1").contains("shadow clip=1").contains("sigma=4.00").contains("glyph clip=1")
            .contains("tex=2 range=3.00").contains("image clip=1").contains("nine-slice clip=1").contains("width=1.00");

        Path golden = dir.resolve("sub/golden.txt");
        DrawListDump.assertMatches(sample(), golden); // written
        assertThat(Files.readString(golden)).isEqualTo(text);
        DrawListDump.assertMatches(sample(), golden); // matches
        Files.writeString(golden, "other\r\n");
        assertThatThrownBy(() -> DrawListDump.assertMatches(sample(), golden)).isInstanceOf(AssertionError.class);
        Path blocked = dir.resolve("file");
        Files.writeString(blocked, "x");
        assertThatThrownBy(() -> DrawListDump.assertMatches(sample(), blocked.resolve("golden.txt")))
            .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void syntheticInputArrivesAtThePoll() {
        SyntheticInput synthetic = new SyntheticInput().press(UiCommand.DOWN, UiCommand.ACCEPT).type("hi");
        UiInput input = new UiInput(8);
        synthetic.poll(input);
        assertThat(input.commandCount()).isEqualTo(2);
        assertThat(input.text().toString()).isEqualTo("hi");
        assertThat(input.pointerCount()).isZero();
        input.beginFrame();
        synthetic.mouse(5, 6, Pointer.PRIMARY).poll(input);
        assertThat(input.commandCount()).isZero();
        assertThat(input.pointerAt(0).x()).isEqualTo(5);
        assertThat(input.pointerAt(0).buttons()).isEqualTo(Pointer.PRIMARY);
    }

    @Test
    void syntheticPointersKeepTheirStateUntilLifted() {
        SyntheticInput synthetic = new SyntheticInput()
            .pointer(3, Pointer.Kind.TOUCH, 10, 20, Pointer.PRIMARY).player(3, 1).modifiers(3, Pointer.SHIFT)
            .scroll(3, 0, 2).command(UiCommand.UP, 2, UiInput.EXTEND_SELECTION).compose("ka", 1);
        UiInput input = new UiInput(8);
        synthetic.poll(input);
        Pointer finger = input.pointerAt(0);
        assertThat(finger.id()).isEqualTo(3);
        assertThat(finger.kind()).isEqualTo(Pointer.Kind.TOUCH);
        assertThat(finger.player()).isEqualTo(1);
        assertThat(finger.modifiers()).isEqualTo(Pointer.SHIFT);
        assertThat(finger.scrollY()).isEqualTo(2);
        assertThat(input.player(0)).isEqualTo(2);
        assertThat(input.flags(0)).isEqualTo(UiInput.EXTEND_SELECTION);
        assertThat(input.composition().toString()).isEqualTo("ka");
        assertThat(input.compositionCursor()).isEqualTo(1);
        input.beginFrame();
        synthetic.poll(input);
        assertThat(input.pointerAt(0).scrollY()).isZero(); // scroll is handed over once
        assertThat(input.pointerAt(0).x()).isEqualTo(10);
        synthetic.lift(3).lift(99).poll(input);
        assertThat(input.pointerCount()).isZero();
        for (int i = 0; i < UiInput.MAX_POINTERS; i++) {
            synthetic.pointer(i, Pointer.Kind.PEN, i, i, 0);
        }
        assertThatThrownBy(() -> synthetic.pointer(99, Pointer.Kind.PEN, 0, 0, 0))
            .isInstanceOf(IllegalStateException.class);
    }
}
