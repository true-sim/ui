package com.truesim.ui.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.Clipboard;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandsAndTextTest {

    /** Records what the engine's text input is asked to do. */
    static final class Host implements TextInputHost {
        final List<String> calls = new ArrayList<>();

        @Override
        public void start(float x, float y, float width, float height) {
            calls.add("start " + x + "," + y + " " + width + "x" + height);
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    @Test
    void textInputStartsWithTheElementsKind() {
        Rig r = new Rig();
        Element field = r.box(r.root(), "field", 0, 0, 100, 20);
        r.ia.textInput(field, true);
        r.ia.textInputKind(field, TextInputHost.PASSWORD);
        assertThat(r.ia.textInputKind(field)).isEqualTo(TextInputHost.PASSWORD);
        List<Integer> kinds = new ArrayList<>();
        r.ia.textInputHost(new TextInputHost() {
            @Override
            public void start(float x, float y, float width, float height) {
                kinds.add(-1);
            }

            @Override
            public void start(float x, float y, float width, float height, int kind) {
                kinds.add(kind);
            }

            @Override
            public void stop() {
            }
        });
        r.ia.focus(field);
        r.frame();
        assertThat(kinds).containsExactly(TextInputHost.PASSWORD);
        Host plain = new Host();
        r.ia.textInputHost(plain);
        r.ia.textInputKind(field, TextInputHost.NUMBER);
        r.frame();
        assertThat(plain.calls).as("the default ignores the kind").hasSize(1);
        assertThat(r.ia.textInputKind(field)).isEqualTo(TextInputHost.NUMBER);
    }

    @Test
    void commandsGoToTheFocusedElementAlongItsRouteBeforeMovingFocus() {
        Rig r = new Rig();
        Element list = r.box(r.root(), "list", 0, 0, 100, 200);
        Element a = r.box(list, "a", 0, 0, 100, 40);
        Element b = r.box(list, "b", 0, 50, 100, 40);
        r.ia.focusable(a, true);
        r.ia.focusable(b, true);
        r.logCapture(list);
        List<String> seen = new ArrayList<>();
        r.ia.on(list, EventType.COMMAND, e -> {
            seen.add(e.command() + " " + e.commandFlags() + " " + e.player());
            if (e.command() == UiCommand.PAGE_DOWN) {
                e.consume();
            }
        });
        r.ia.focus(a);
        r.take();
        r.input.command(UiCommand.PAGE_DOWN, 0, UiInput.EXTEND_SELECTION);
        r.command(UiCommand.DOWN).frame();
        assertThat(seen).containsExactly("PAGE_DOWN 1 0", "DOWN 0 0");
        assertThat(r.take(EventType.COMMAND)).containsExactly("COMMAND a capture@list", "COMMAND a capture@list");
        assertThat(r.ia.focused(0)).isEqualTo(b); // DOWN was not consumed: focus moved

        r.ia.on(b, EventType.COMMAND, e -> {
            if (e.command() == UiCommand.UP) {
                e.consume(); // a control using UP itself (a slider, a text area)
            }
        });
        r.command(UiCommand.UP).frame();
        assertThat(r.ia.focused(0)).isEqualTo(b);
    }

    @Test
    void acceptClicksTheFocusedElementContextMenuAsksForAMenuAndCancelClosesModals() {
        Rig r = new Rig();
        Element a = r.box(r.root(), "a", 0, 0, 100, 40);
        r.ia.focusable(a, true);
        r.logAll(a);
        r.command(UiCommand.ACCEPT).frame(); // nothing focused: nothing happens
        assertThat(r.take(EventType.CLICK)).isEmpty();
        r.ia.focus(a);
        r.command(UiCommand.ACCEPT).command(UiCommand.CONTEXT_MENU).command(UiCommand.SELECT_ALL).frame();
        assertThat(r.take(EventType.CLICK, EventType.CONTEXT)).containsExactly("CLICK a", "CONTEXT a");
        List<String> closed = new ArrayList<>();
        Element dialog = r.box(r.root(), "dialog", 200, 0, 100, 100);
        r.ia.pushModal(dialog, () -> closed.add("dialog"));
        assertThat(r.ia.focused(0)).isNull(); // nothing focusable inside
        r.command(UiCommand.CANCEL).frame(); // goes to the modal root, then closes it
        assertThat(closed).containsExactly("dialog");
        r.ia.popModal(dialog);
        assertThat(r.ia.focused(0)).isEqualTo(a); // restored
    }

    @Test
    void textAndCompositionGoToTheFocusedTextFieldAndTheHostFollowsIt() {
        Rig r = new Rig();
        Host host = new Host();
        r.ia.textInputHost(host);
        Element field = r.box(r.root(), "field", 10, 20, 200, 30);
        Element button = r.box(r.root(), "button", 10, 100, 50, 30);
        r.ia.textInput(field, true);
        r.ia.focusable(button, true);
        List<String> got = new ArrayList<>();
        r.ia.on(field, EventType.TEXT.bit() | EventType.COMPOSITION.bit(), e -> got.add(e.type() + " "
            + e.text() + " " + e.compositionCursor()));
        r.input.text("ignored");
        r.frame(); // nothing focused takes text
        assertThat(got).isEmpty();
        assertThat(r.ia.textOwner()).isNull();

        r.ia.focus(field);
        r.frame();
        assertThat(host.calls).containsExactly("start 10.0,20.0 200.0x30.0");
        assertThat(r.ia.textOwner()).isEqualTo(field);
        r.input.text("hé");
        r.input.composition("ka", 1);
        r.frame();
        r.frame(); // the same composition: no event
        r.input.composition("", 0);
        r.frame();
        assertThat(got).containsExactly("TEXT hé 0", "COMPOSITION ka 1", "COMPOSITION  0");

        r.ia.imeArea(field, 50, 20, 2, 30); // the caret moved: the IME follows at once
        r.place(field, 10, 40, 200, 30);
        r.ia.laidOut(); // an IME area of its own: layout moves nothing
        assertThat(host.calls).containsExactly("start 10.0,20.0 200.0x30.0", "start 50.0,20.0 2.0x30.0");

        r.ia.focus(button);
        r.frame();
        assertThat(host.calls).last().isEqualTo("stop");
        assertThat(r.ia.textPlayer()).isZero();
        r.ia.textPlayer(1);
        r.ia.focus(field, 1);
        r.frame();
        assertThat(host.calls).last().isEqualTo("start 50.0,20.0 2.0x30.0");
        r.ia.textInputHost(null); // the old host stops
        assertThat(host.calls).last().isEqualTo("stop");
    }

    @Test
    void aTextFieldWithoutItsOwnAreaMovesTheImeWithLayout() {
        Rig r = new Rig();
        Host host = new Host();
        r.ia.textInputHost(host);
        Element field = r.box(r.root(), "field", 0, 0, 100, 20);
        r.ia.textInput(field, true);
        r.ia.focus(field);
        r.frame();
        r.ia.laidOut();
        r.place(field, 0, 50, 100, 20);
        r.ia.laidOut();
        assertThat(host.calls).containsExactly("start 0.0,0.0 100.0x20.0", "start 0.0,50.0 100.0x20.0");
        r.ia.textInput(field, false);
        r.frame();
        assertThat(host.calls).last().isEqualTo("stop");
    }

    @Test
    void theClipboardIsTheEnginesOrOneInMemory() {
        Rig r = new Rig();
        Clipboard memory = r.ia.clipboard();
        memory.text("copied");
        assertThat(memory.text()).isEqualTo("copied");
        memory.text(null);
        assertThat(memory.text()).isEmpty();
        Clipboard engine = Clipboard.inMemory();
        r.ia.clipboard(engine);
        assertThat(r.ia.clipboard()).isSameAs(engine);
        r.ia.clipboard(null);
        assertThat(r.ia.clipboard()).isNotSameAs(engine);
        assertThat(Interaction.tunables()).hasSize(7);
    }
}
