package com.truesim.ui.core.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.testing.AllocationAssertions;
import org.junit.jupiter.api.Test;

class UiInputTest {

    @Test
    void commandsArriveInOrderAndAreForgottenNextFrame() {
        UiInput input = new UiInput(2);
        assertThat(input.command(UiCommand.DOWN, 0, 0)).isTrue();
        assertThat(input.command(UiCommand.ACCEPT, 1, UiInput.EXTEND_SELECTION)).isTrue();
        assertThat(input.command(UiCommand.CANCEL, 0, 0)).isFalse();
        assertThat(input.commandCount()).isEqualTo(2);
        assertThat(input.command(1)).isEqualTo(UiCommand.ACCEPT);
        assertThat(input.player(1)).isEqualTo(1);
        assertThat(input.flags(1)).isEqualTo(UiInput.EXTEND_SELECTION);
        assertThat(input.has(UiCommand.DOWN)).isTrue();
        assertThat(input.has(UiCommand.CANCEL)).isFalse();
        input.beginFrame();
        assertThat(input.commandCount()).isZero();
        assertThat(UiCommand.of(UiCommand.UP.ordinal())).isEqualTo(UiCommand.UP);
        assertThat(UiCommand.count()).isEqualTo(UiCommand.values().length);
    }

    @Test
    void pointersAreReused() {
        UiInput input = new UiInput(4);
        Pointer mouse = input.pointer(1, Pointer.Kind.MOUSE).moveTo(10, 20).buttons(Pointer.PRIMARY).scroll(0, 1)
            .player(2);
        assertThat(input.pointer(1, Pointer.Kind.MOUSE)).isSameAs(mouse);
        assertThat(mouse.x()).isEqualTo(10);
        assertThat(mouse.y()).isEqualTo(20);
        assertThat(mouse.buttons()).isEqualTo(Pointer.PRIMARY);
        assertThat(mouse.scrollX()).isZero();
        assertThat(mouse.scrollY()).isEqualTo(1);
        assertThat(mouse.player()).isEqualTo(2);
        assertThat(mouse.id()).isEqualTo(1);
        assertThat(mouse.kind()).isEqualTo(Pointer.Kind.MOUSE);
        Pointer finger = input.pointer(7, Pointer.Kind.TOUCH);
        assertThat(input.pointerCount()).isEqualTo(2);
        assertThat(input.pointerAt(1)).isSameAs(finger);
        input.removePointer(1);
        input.removePointer(99);
        assertThat(input.pointerCount()).isEqualTo(1);
        assertThat(input.pointerAt(0).id()).isEqualTo(7);
        for (int i = 0; i < UiInput.MAX_POINTERS - 1; i++) {
            input.pointer(100 + i, Pointer.Kind.PEN);
        }
        assertThatThrownBy(() -> input.pointer(1000, Pointer.Kind.PEN)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void textAndCompositionAreKept() {
        UiInput input = new UiInput(1);
        input.text("ab");
        input.text("c");
        input.composition("にほ", 1);
        assertThat(input.text().toString()).isEqualTo("abc");
        assertThat(input.composition().toString()).isEqualTo("にほ");
        assertThat(input.compositionCursor()).isEqualTo(1);
        input.beginFrame();
        assertThat(input.text()).isEmpty();
        assertThat(input.composition().toString()).isEqualTo("にほ");
    }

    @Test
    void aFrameOfInputAllocatesNothing() {
        UiInput input = new UiInput(8);
        AllocationAssertions.assertAllocationFree(() -> {
            input.beginFrame();
            input.command(UiCommand.DOWN, 0, 0);
            input.pointer(1, Pointer.Kind.MOUSE).moveTo(1, 2);
            input.has(UiCommand.ACCEPT);
        });
    }
}
