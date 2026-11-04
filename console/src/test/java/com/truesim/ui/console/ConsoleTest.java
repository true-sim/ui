package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.testkit.SyntheticInput;
import org.junit.jupiter.api.Test;

/** The console widget: toggling, the command line's keys, completion, history, links, the log's filters, frames. */
class ConsoleTest {

    private final Ui ui = ConsoleGalleryTest.ui("default");
    private final Console console = Console.create(ui, "test", HistoryStore.memory());
    private final FakeVariables vars = new FakeVariables();
    private final SyntheticInput input = new SyntheticInput();
    private final UiInput frame = new UiInput(16);
    private long nanos = 1_000_000_000L;

    ConsoleTest() {
        console.shell().variables(vars).clock(() -> 0L);
        console.shell().register(ConsoleCommand.named("spawn").arg(Arg.integer("count").range(1, 100))
            .arg(Arg.choice("kind", "orc", "elf", "ogre").optional()).action(call -> call.print("spawned")));
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            frame.beginFrame();
            input.poll(frame);
            ui.update(1280, 720, frame, nanos);
            nanos += 16_000_000L;
        }
    }

    private String line() {
        return console.field().text().toString();
    }

    private void click(Element e) {
        Layout l = ui.layout();
        float x = l.x(e.slot()) + l.width(e.slot()) / 2;
        float y = l.y(e.slot()) + l.height(e.slot()) / 2;
        input.mouse(x, y, 0);
        frames(1);
        input.mouse(x, y, Pointer.PRIMARY);
        frames(1);
        input.mouse(x, y, 0);
        frames(1);
    }

    private String lastLog() {
        return console.log().last().message().toString();
    }

    @Test
    void theToggleCommandOpensAndClosesItAndTheFieldTakesTheFocus() {
        frames(2);
        assertThat(ui.layout().shown(console.root().slot())).isFalse();
        input.press(UiCommand.CONSOLE_TOGGLE);
        frames(2);
        assertThat(console.isOpen()).isTrue();
        assertThat(console.root().is(UiState.EXPANDED)).isTrue();
        assertThat(console.field().focused()).isTrue();
        frames(30);
        float top = ui.layout().y(console.root().slot());
        assertThat(top).isZero();
        input.press(UiCommand.CONSOLE_TOGGLE);
        frames(2);
        assertThat(console.isOpen()).isFalse();
        assertThat(console.field().focused()).isFalse();
        frames(40);
        assertThat(ui.layout().shown(console.root().slot())).isFalse(); // hidden once it went up
        console.open(true);
        frames(3);
        input.press(UiCommand.CANCEL); // closes it
        frames(2);
        assertThat(console.isOpen()).isFalse();
    }

    @Test
    void theFocusGoesBackWhereItWas() {
        TextEditor other = TextEditor.field(ui, null, "Other");
        ui.root().add(other.element());
        frames(2);
        other.focus();
        frames(1);
        console.open(true);
        frames(3);
        assertThat(console.field().focused()).isTrue();
        console.open(false);
        assertThat(other.focused()).isTrue();
        console.toggle();
        console.toggle();
        assertThat(console.isOpen()).isFalse();
    }

    @Test
    void enterRunsTheLineAndEchoesIt() {
        console.open(true);
        frames(3);
        input.type("render.bloom OFF");
        frames(1);
        input.press(UiCommand.SUBMIT);
        frames(1);
        assertThat(vars.var("render.bloom").value()).isEqualTo("OFF");
        assertThat(line()).isEmpty();
        assertThat(console.log().get(console.log().size() - 2).message().toString())
            .isEqualTo("> render.bloom OFF");
        assertThat(console.log().get(console.log().size() - 2).level()).isEqualTo(LogLevel.COMMAND);
        assertThat(lastLog()).isEqualTo("render.bloom = OFF");
        assertThat(console.history().lines()).containsExactly("render.bloom OFF");
        input.type("   ");
        frames(1);
        input.press(UiCommand.SUBMIT); // blank: nothing runs
        frames(1);
        assertThat(console.history().lines()).hasSize(1);
    }

    @Test
    void tabCompletesTheOnlyCandidateThenTheCommonStartThenPicks() {
        console.open(true);
        frames(3);
        input.type("render.bl");
        frames(1);
        assertThat(console.popupShown()).isTrue();
        input.press(UiCommand.COMPLETE);
        frames(1);
        assertThat(line()).isEqualTo("render.bloom ");
        assertThat(console.completion().candidates()).extracting(Completion.Candidate::text)
            .containsExactly("OFF", "CHEAP", "PHYSICAL");
        assertThat(console.completion().hint()).startsWith("render.bloom <OFF|CHEAP|PHYSICAL>");
        input.press(UiCommand.COMPLETE); // no common start: picks the first
        frames(1);
        assertThat(console.selected()).isZero();
        input.press(UiCommand.HISTORY_DOWN); // moves the pick
        frames(1);
        assertThat(console.selected()).isEqualTo(1);
        input.press(UiCommand.HISTORY_UP, UiCommand.HISTORY_UP); // wraps around
        frames(1);
        assertThat(console.selected()).isEqualTo(2);
        input.press(UiCommand.COMPLETE);
        frames(1);
        assertThat(line()).isEqualTo("render.bloom PHYSICAL ");
        input.press(UiCommand.CANCEL); // closes the pop-up, not the console
        frames(1);
        assertThat(console.popupShown()).isFalse();
        assertThat(console.isOpen()).isTrue();
    }

    @Test
    void tabExtendsTheCommonStart() {
        console.open(true);
        frames(3);
        input.type("rend");
        frames(1);
        input.press(UiCommand.COMPLETE);
        frames(1);
        assertThat(line()).isEqualTo("render.");
        assertThat(console.field().editable().caret()).isEqualTo(7);
        assertThat(console.selected()).isEqualTo(-1);
        console.field().text("");
        frames(1);
        input.press(UiCommand.COMPLETE); // nothing typed: the pop-up lists every name
        frames(1);
        assertThat(console.completion().candidates().size()).isGreaterThan(10);
    }

    @Test
    void aClickOnACandidateTakesIt() {
        console.open(true);
        frames(3);
        input.type("spawn 3 ");
        frames(3);
        assertThat(console.completion().candidates()).hasSize(3);
        Element popup = console.popup();
        Element second = ui.store().element(ui.store().nextSibling(ui.store().nextSibling(
            ui.store().firstChild(popup.slot()))));
        click(second);
        assertThat(line()).isEqualTo("spawn 3 elf ");
    }

    @Test
    void historyWalksWhileThePopUpListsNothing() {
        console.open(true);
        frames(3);
        console.run("render.scale 1.5");
        console.run("render.vsync off");
        input.type("draft");
        frames(1);
        input.press(UiCommand.CANCEL); // the pop-up (nothing matches "draft": no pop-up) or a hint
        frames(1);
        console.open(true);
        frames(2);
        input.press(UiCommand.HISTORY_UP);
        frames(1);
        assertThat(line()).isEqualTo("render.vsync off");
        input.press(UiCommand.UP);
        frames(1);
        assertThat(line()).isEqualTo("render.scale 1.5");
        input.press(UiCommand.HISTORY_UP);
        frames(1);
        assertThat(line()).isEqualTo("render.scale 1.5");
        input.press(UiCommand.DOWN, UiCommand.HISTORY_DOWN);
        frames(1);
        assertThat(line()).isEqualTo("draft");
        console.run("history");
        assertThat(lastLog()).isEqualTo("history");
    }

    @Test
    void consoleLinksRunCommands() {
        assertThat(console.link("cmd:render.bloom OFF")).isTrue();
        assertThat(vars.var("render.bloom").value()).isEqualTo("OFF");
        assertThat(console.link("https://example.com")).isFalse();
        assertThat(console.link(null)).isFalse();
        TextEditor rich = TextEditor.richText(ui, null, "Help", "[link=cmd:render.bloom CHEAP]cheap[/link]");
        TextEditor.Listener links = console.linkListener();
        rich.listener(links);
        links.link(rich, "cmd:render.bloom CHEAP");
        assertThat(vars.var("render.bloom").value()).isEqualTo("CHEAP");
    }

    @Test
    void theFilterBarFiltersTheLog() {
        LogRing log = console.log();
        log.log(LogLevel.DEBUG, "com.truesim.render.Bloom", "bloom debug", "Bloom.java:3", 0);
        log.log(LogLevel.WARN, "com.truesim.audio.Mixer", "mixer warn", null, 0);
        log.log(LogLevel.ERROR, "com.truesim.render.Bloom", "bloom error", null, 0);
        console.open(true);
        frames(30);
        assertThat(console.rows().size()).isEqualTo(3);
        Element bar = ui.store().element(ui.store().firstChild(console.root().slot()));
        Element debugToggle = ui.store().element(ui.store().nextSibling(ui.store().firstChild(bar.slot())));
        assertThat(debugToggle.is(UiState.CHECKED)).isTrue();
        click(debugToggle);
        assertThat(console.rows().size()).isEqualTo(2);
        assertThat(debugToggle.is(UiState.CHECKED)).isFalse();
        // a click on a row's logger filters by it; the chip clears the filter
        Element row = console.view().rowElement(1);
        Element logger = ui.store().element(ui.store().nextSibling(ui.store().nextSibling(
            ui.store().firstChild(row.slot()))));
        click(logger);
        assertThat(console.rows().logger()).isEqualTo("com.truesim.render.Bloom");
        frames(2);
        assertThat(console.rows().size()).isEqualTo(1);
        console.run("log.logger");
        frames(2);
        assertThat(console.rows().logger()).isNull();
        console.rows().logger("com.truesim.audio");
        frames(2);
        Element chip = ui.store().element(ui.store().previousSibling(ui.store().lastChild(bar.slot())));
        assertThat(ui.layout().shown(chip.slot())).isTrue();
        console.rows().logger(null);
        frames(2);
        assertThat(ui.layout().shown(chip.slot())).isFalse();
        // a click on a source copies it
        console.run("log.level trace");
        frames(3);
        Element first = console.view().rowElement(0);
        Element source = ui.store().element(ui.store().lastChild(first.slot()));
        click(source);
        assertThat(ui.interaction().clipboard().text()).isEqualTo("Bloom.java:3");
        console.run("log.search bloom err");
        frames(2);
        assertThat(console.rows().size()).isEqualTo(2); // the record and the command's echo
        assertThat(console.rows().get(0).message().toString()).isEqualTo("bloom error");
        assertThat(console.searchField().text().toString()).isEqualTo("bloom err");
        console.run("log.search");
        console.run("clear");
        frames(2);
        assertThat(console.rows().size()).isZero();
    }

    @Test
    void theInboxIsDrainedEachFrame() {
        LogInbox inbox = new LogInbox(64);
        console.inbox(inbox);
        assertThat(console.inbox()).isSameAs(inbox);
        inbox.log(LogLevel.INFO, "net", "from another thread", null, 0);
        frames(1);
        assertThat(lastLog()).isEqualTo("from another thread");
    }

    @Test
    void framesAllocateNothingIdleFloodedAndScrolling() {
        LogInbox inbox = new LogInbox(4096);
        console.inbox(inbox);
        String[] messages = new String[32];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = "Frame " + i + ": the renderer did a number of things that take a line of text";
        }
        console.open(true);
        frames(40);
        int[] step = {0};
        Runnable idle = () -> frames(1);
        Runnable flood = () -> {
            for (int k = 0; k < 200; k++) {
                int s = step[0]++;
                inbox.log(s % 5, s % 2 == 0 ? "com.truesim.render.Graph" : "com.truesim.game.World",
                    messages[s % messages.length], s % 3 == 0 ? "Graph.java:120" : null, s);
            }
            frames(1);
        };
        Runnable scrolling = () -> {
            int s = step[0]++;
            input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE, 600, 150 + s % 5, 0)
                .scroll(SyntheticInput.MOUSE, 0, s % 30 < 15 ? 1 : -1);
            frames(1);
        };
        for (int i = 0; i < 300; i++) {
            flood.run();
            scrolling.run();
            idle.run();
        }
        AllocationAssertions.assertAllocationFree(idle);
        // new text every frame: the shaper's native calls allocate until the JIT has inlined them, so warm up longer
        AllocationAssertions.assertAllocationFree(flood, 150_000, 10_000, 10);
        AllocationAssertions.assertAllocationFree(scrolling);
        console.open(false);
        frames(60);
        AllocationAssertions.assertAllocationFree(idle);
        AllocationAssertions.assertAllocationFree(flood);
    }

    @Test
    void theLogFollowsTheNewestRecordUnlessScrolledUp() {
        console.open(true);
        for (int i = 0; i < 200; i++) {
            console.log().log(LogLevel.INFO, "a", "line " + i, null, 0);
        }
        frames(30);
        LogView view = console.view();
        assertThat(view.following()).isTrue();
        assertThat(view.lastBound()).isEqualTo(199);
        view.scrollTo(0, 0);
        frames(2);
        console.log().log(LogLevel.INFO, "a", "more", null, 0);
        frames(2);
        assertThat(view.following()).isFalse();
        assertThat(view.firstBound()).isZero();
        view.scrollToEnd();
        frames(2);
        assertThat(view.following()).isTrue();
        assertThat(view.lastBound()).isEqualTo(200);
        assertThat(view.logRows()).isSameAs(console.rows());
        StringBuilder t = new StringBuilder();
        LogView.time(t, 3_723_004L);
        assertThat(t.toString()).isEqualTo("01:02:03.004");
        LogView.seconds(t, 3_723_004L);
        assertThat(t.toString()).as("a row's time in two labels: seconds").isEqualTo("01:02:03");
        LogView.millis(t, 3_723_004L);
        assertThat(t.toString()).as("and milliseconds").isEqualTo(".004");
    }
}
