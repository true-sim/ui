package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Layout;
import java.util.Arrays;

/**
 * The developer console widget (design 5.10): a {@link LogView} of the console's {@link LogRing}, a filter bar (level
 * toggles, the logger filter, an incremental search), a command line with a completion pop-up showing the candidates
 * and the signature of the command being typed, over a {@link ConsoleShell}. It drops down from the top of the screen
 * on its own layer ({@value #LAYER}): the theme's {@code console} rule places it closed and
 * {@code console:expanded} open, and its {@code transition} on {@code offsets} animates the drop.
 *
 * <ul>
 *   <li>{@link UiCommand#CONSOLE_TOGGLE} opens and closes it, from anywhere (the engine's {@code ui.console.toggle}:
 *       the grave key, both sticks clicked, the XR menu button held); {@link UiCommand#CANCEL} closes the pop-up,
 *       then the console.</li>
 *   <li>While the command line has the focus: {@link UiCommand#COMPLETE} completes (the only candidate, else the
 *       candidates' common start, else the first candidate is picked and the next press takes it);
 *       {@link UiCommand#HISTORY_UP} and {@link UiCommand#HISTORY_DOWN} (and UP, DOWN) walk the pop-up while it
 *       lists candidates, the history otherwise; Enter runs the line: echoed into the log, added to the history,
 *       run by the shell.</li>
 *   <li>Rich text links with the {@value #LINK_SCHEME} scheme run console commands ({@link #linkListener()},
 *       {@link #link}).</li>
 *   <li>The log takes records from any thread through a {@link LogInbox} ({@link #inbox}), drained once a frame.</li>
 * </ul>
 *
 * <p><b>Elements and styles.</b> {@code console} (role GROUP, {@code :expanded} while open) holds
 * {@code console-bar} (the {@code console-level} toggles, {@code :checked} for shown levels, the
 * {@code console-logger} chip, hidden without a logger filter, and a {@code textfield.console-search}), the
 * {@code log-view} and {@code console-input} ({@code label.console-prompt} and {@code textfield.console}). The pop-up
 * {@code console-completions} sits on the same layer under the command line: a {@code console-hint} (labels
 * {@code hint-before}, {@code hint-current}, {@code hint-after}) and up to {@link ConsoleTunables#COMPLETIONS}
 * {@code console-completion} rows ({@code :selected}; labels {@code completion-text}, {@code completion-detail}).
 * Steady frames, open or closed, idle or flooded with log records, allocate nothing.
 */
public final class Console implements Ui.FrameListener {

    /** The name of the layer the console is on. */
    public static final String LAYER = "console";
    /** Its z: above pop-ups, tooltips and drags, under the debug layer. */
    public static final int LAYER_Z = 600;
    /** The link scheme of rich text links that run console commands: {@code [link=cmd:render.bloom OFF]}. */
    public static final String LINK_SCHEME = "cmd:";
    /** The logger the console echoes command lines with. */
    public static final String INPUT_LOGGER = "console.input";

    private static final int[] TOGGLED_LEVELS = {LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN,
        LogLevel.ERROR};

    private final Ui ui;
    private final Interaction interaction;
    private final ConsoleShell shell;
    private final LogRing log;
    private final LogRows rows;
    private final LogView view;
    private final ConsoleHistory history;
    private LogInbox inbox;
    private final Element root;
    private final Element bar;
    private final Element[] levelToggles = new Element[TOGGLED_LEVELS.length];
    private final Element loggerChip;
    private final TextEditor search;
    private final Element inputRow;
    private final TextEditor field;
    private final Element popup;
    private final Element hint;
    private final Element hintBefore;
    private final Element hintCurrent;
    private final Element hintAfter;
    private Element[] completionRows = new Element[0];
    private Element[] completionTexts = new Element[0];
    private Element[] completionDetails = new Element[0];
    private final Events events = new Events();
    private final StringBuilder echo = new StringBuilder();

    private boolean open;
    private boolean shown;
    private int closedFrames;
    private boolean focusPending;
    private Element previousFocus;
    private Completion completion = Completion.NONE;
    private int selected = -1;
    private int firstShown;
    private boolean popupShown;
    private boolean applying;
    private int shownLevels = -1;
    private String shownLogger;

    /**
     * A console for {@code ui} running {@code shell}, showing {@code log} (the ring the shell prints to), keeping
     * {@code history}. It adds itself to its layer, closed, and to the UI's frame listeners.
     */
    @AllowAllocation("controls are made when a UI is built")
    public Console(Ui ui, ConsoleShell shell, LogRing log, ConsoleHistory history) {
        this.ui = ui;
        this.interaction = ui.interaction();
        this.shell = shell;
        this.log = log;
        this.history = history;
        this.rows = new LogRows(log);
        Layer layer = ui.layerStack().find(LAYER);
        if (layer == null) {
            layer = ui.layerStack().add(LAYER, LAYER_Z);
        }
        root = ui.element("console", null);
        root.semantics(Role.GROUP, "Console");
        bar = ui.element("console-bar", null);
        for (int i = 0; i < TOGGLED_LEVELS.length; i++) {
            Element t = ui.element("console-level", null);
            String name = LogLevel.name(TOGGLED_LEVELS[i]);
            t.add(ui.label(null, name));
            t.semantics(Role.TOGGLE_BUTTON, name);
            interaction.pickable(t, PointerEvents.ALL);
            interaction.on(t, EventType.CLICK, events);
            levelToggles[i] = t;
            bar.add(t);
        }
        loggerChip = ui.label("console-logger", "");
        loggerChip.semantics(Role.BUTTON, "Clear the logger filter");
        interaction.pickable(loggerChip, PointerEvents.ALL);
        interaction.on(loggerChip, EventType.CLICK, events);
        ui.layout().visible(loggerChip.slot(), false);
        bar.add(loggerChip);
        search = TextEditor.field(ui, "console-search", "Search the log").placeholder("Search");
        search.listener(new TextEditor.Listener() {
            @Override
            public void changed(TextEditor editor) {
                rows.search(editor.text());
            }
        });
        bar.add(search.element());
        view = new LogView(ui, "Log", rows);
        inputRow = ui.element("console-input", null);
        field = TextEditor.field(ui, "console", "Console command").placeholder("Type a command; Tab completes");
        field.listener(new TextEditor.Listener() {
            @Override
            public void changed(TextEditor editor) {
                if (!applying) {
                    updateCompletion();
                }
            }

            @Override
            public void submitted(TextEditor editor) {
                submit();
            }
        });
        inputRow.add(ui.label("console-prompt", ">")).add(field.element());
        root.add(bar).add(view.root()).add(inputRow);
        popup = ui.element("console-completions", null);
        popup.semantics(Role.LIST, "Completions");
        ui.codeLayout(popup, Ui.CODE_PLACEMENT);
        hint = ui.element("console-hint", null);
        hintBefore = ui.label("hint-before", "");
        hintCurrent = ui.label("hint-current", "");
        hintAfter = ui.label("hint-after", "");
        hint.add(hintBefore).add(hintCurrent).add(hintAfter);
        popup.add(hint);
        layer.root().add(root).add(popup);
        ui.layout().visible(root.slot(), false);
        ui.layout().visible(popup.slot(), false);
        interaction.on(root, EventType.COMMAND, events);
        interaction.on(popup, EventType.CLICK, events);
        registerCommands();
        ui.addFrameListener(this);
    }

    /** A console with a new ring of {@link ConsoleTunables#LOG_CAPACITY}, a shell printing to it, and a history. */
    @AllowAllocation("controls are made when a UI is built")
    public static Console create(Ui ui, String appId, HistoryStore store) {
        LogRing ring = new LogRing();
        return new Console(ui, new ConsoleShell(ring), ring, new ConsoleHistory(appId, store));
    }

    @AllowAllocation("controls are made when a UI is built")
    private void registerCommands() {
        shell.register(ConsoleCommand.named("clear").describe("Clears the log").action(call -> log.clear()));
        shell.register(ConsoleCommand.named("log.level")
            .describe("Shows log records of this level and more severe ones")
            .arg(Arg.choice("level", "trace", "debug", "info", "warn", "error"))
            .action(call -> rows.levels(LogLevel.atLeast(LogLevel.of(call.text("level"))))));
        shell.register(ConsoleCommand.named("log.logger")
            .describe("Shows only the records of a logger and the loggers under it; no name shows all")
            .arg(Arg.text("name").optional())
            .action(call -> rows.logger(call.text("name", null))));
        shell.register(ConsoleCommand.named("log.search")
            .describe("Shows only the records containing the text; no text shows all")
            .arg(Arg.text("text").optional().rest())
            .action(call -> {
                search.text(call.text("text", ""));
                rows.search(search.text());
            }));
        shell.register(ConsoleCommand.named("history").describe("Lists the command history").action(call -> {
            for (String line : history.lines()) {
                call.print(line);
            }
        }));
    }

    // ---------------------------------------------------------------- API

    /** The console's root element. */
    public Element root() {
        return root;
    }

    /** The shell it runs. */
    public ConsoleShell shell() {
        return shell;
    }

    /** The log ring it shows. */
    public LogRing log() {
        return log;
    }

    /** The rows of the log it shows (its filters). */
    public LogRows rows() {
        return rows;
    }

    /** The log view. */
    public LogView view() {
        return view;
    }

    /** The command line. */
    public TextEditor field() {
        return field;
    }

    /** The log search field. */
    public TextEditor searchField() {
        return search;
    }

    /** The history. */
    public ConsoleHistory history() {
        return history;
    }

    /** The completion pop-up's root. */
    public Element popup() {
        return popup;
    }

    /** The completion the pop-up shows. */
    public Completion completion() {
        return completion;
    }

    /** The candidate picked in the pop-up, or -1. */
    public int selected() {
        return selected;
    }

    /** Whether the pop-up shows. */
    public boolean popupShown() {
        return popupShown;
    }

    /** Drains {@code value} into the log once a frame (records from other threads; null: none); returns this. */
    public Console inbox(LogInbox value) {
        inbox = value;
        return this;
    }

    /** The inbox drained into the log, or null. */
    public LogInbox inbox() {
        return inbox;
    }

    /** Whether it is open (or opening). */
    public boolean isOpen() {
        return open;
    }

    /** Opens or closes it; open, the command line takes the focus. */
    public void open(boolean on) {
        if (on == open) {
            return;
        }
        open = on;
        root.state(UiState.EXPANDED, on);
        if (on) {
            shown = true;
            ui.layout().visible(root.slot(), true);
            Element focused = interaction.focused(0);
            previousFocus = focused != null && !inside(focused.slot()) ? focused : null;
            focusPending = true;
        } else {
            closedFrames = 0;
            hidePopup();
            focusPending = false;
            Element focused = interaction.focused(0);
            if (focused != null && inside(focused.slot())) {
                if (previousFocus != null && previousFocus.alive()) {
                    interaction.focus(previousFocus);
                } else {
                    interaction.blur(0);
                }
            }
            previousFocus = null;
        }
    }

    /** Opens it when closed, closes it when open. */
    public void toggle() {
        open(!open);
    }

    private boolean inside(int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == root.slot() || s == popup.slot()) {
                return true;
            }
        }
        return false;
    }

    /** Runs {@code line} as if typed: echoed into the log, added to the history, run by the shell. */
    public boolean run(CharSequence line) {
        echo.setLength(0);
        echo.append("> ").append(line);
        log.log(LogLevel.COMMAND, INPUT_LOGGER, echo, null, shell.now());
        history.add(line.toString());
        boolean ok = shell.execute(line);
        view.scrollToEnd();
        return ok;
    }

    /**
     * Runs the command of a rich text link target with the {@value #LINK_SCHEME} scheme (true), or does nothing for
     * other targets (false).
     */
    public boolean link(String target) {
        if (target == null || !target.startsWith(LINK_SCHEME)) {
            return false;
        }
        run(target.substring(LINK_SCHEME.length()));
        return true;
    }

    /** A text editor listener that runs the console commands of clicked {@value #LINK_SCHEME} links. */
    @AllowAllocation("made once per rich text")
    public TextEditor.Listener linkListener() {
        return new TextEditor.Listener() {
            @Override
            public void link(TextEditor editor, String target) {
                Console.this.link(target);
            }
        };
    }

    // ---------------------------------------------------------------- the command line

    private void submit() {
        String line = field.text().toString();
        applying = true;
        field.text("");
        applying = false;
        hidePopup();
        if (!line.isBlank()) {
            run(line);
        }
    }

    @AllowAllocation("completion runs on keys typed")
    private void updateCompletion() {
        CharSequence text = field.text();
        if (text.isEmpty()) {
            hidePopup();
            return;
        }
        completion = shell.complete(text, field.editable().caret());
        selected = -1;
        firstShown = 0;
        showPopup();
    }

    /** Tab: the only candidate, else the common start, else picks the first; a picked candidate is taken. */
    @AllowAllocation("completion runs on keys typed")
    private void complete() {
        CharSequence text = field.text();
        if (selected < 0) { // what is typed may have changed since the pop-up was filled
            completion = shell.complete(text, field.editable().caret());
            selected = -1;
            firstShown = 0;
        }
        int n = completion.candidates().size();
        if (selected >= 0 && selected < n) {
            take(selected);
            return;
        }
        if (n == 1) {
            take(0);
            return;
        }
        if (n > 1) {
            String prefix = completion.commonPrefix();
            int typed = Math.min(field.editable().caret(), text.length()) - completion.start();
            if (prefix.length() > typed) {
                String line = text.subSequence(0, completion.start()) + prefix
                    + text.subSequence(Math.min(completion.end(), text.length()), text.length());
                set(line, completion.start() + prefix.length());
            } else {
                selected = 0;
            }
        }
        showPopup();
    }

    @AllowAllocation("completion runs on keys typed")
    private void take(int index) {
        CharSequence text = field.text();
        set(completion.apply(text, index), completion.caretAfter(text, index));
        updateCompletion();
    }

    @AllowAllocation("completion runs on keys typed")
    private void set(String line, int caret) {
        applying = true;
        field.text(line);
        field.editable().moveTo(Math.min(caret, line.length()), false);
        applying = false;
        ui.repaint(field.element());
        completion = shell.complete(line, field.editable().caret());
        selected = -1;
        firstShown = 0;
    }

    @AllowAllocation("history walks on keys pressed")
    private void historyStep(boolean older) {
        String line = older ? history.older(field.text()) : history.newer();
        if (line != null) {
            applying = true;
            field.text(line);
            applying = false;
            hidePopup();
        }
    }

    private void move(int delta) {
        int n = completion.candidates().size();
        selected = selected < 0 ? (delta > 0 ? 0 : n - 1) : Math.floorMod(selected + delta, n);
        showPopup();
    }

    // ---------------------------------------------------------------- the pop-up

    @AllowAllocation("completion runs on keys typed: rows are made as needed")
    private void showPopup() {
        int max = (int) ConsoleTunables.COMPLETIONS.get();
        int n = completion.candidates().size();
        boolean hasHint = !completion.hint().isEmpty();
        if (n == 0 && !hasHint) {
            hidePopup();
            return;
        }
        popupShown = true;
        ui.layout().visible(popup.slot(), true);
        ui.layout().visible(hint.slot(), hasHint);
        if (hasHint) {
            String h = completion.hint();
            int hs = completion.hintStart();
            int he = completion.hintEnd();
            // the pieces without their blanks: the theme's gap separates them (trailing blanks do not take space)
            if (hs < 0) {
                ui.text(hintBefore, h.strip());
                ui.text(hintCurrent, "");
                ui.text(hintAfter, "");
            } else {
                ui.text(hintBefore, h.substring(0, hs).strip());
                ui.text(hintCurrent, h.substring(hs, he));
                ui.text(hintAfter, h.substring(he).strip());
            }
            ui.layout().visible(hintBefore.slot(), hs != 0);
            ui.layout().visible(hintCurrent.slot(), hs >= 0);
            ui.layout().visible(hintAfter.slot(), hs >= 0 && he < h.length());
        }
        int shownCount = Math.min(n, max);
        if (selected >= 0) {
            if (selected < firstShown) {
                firstShown = selected;
            } else if (selected >= firstShown + shownCount) {
                firstShown = selected - shownCount + 1;
            }
        }
        firstShown = Math.max(0, Math.min(firstShown, n - shownCount));
        ensureRows(shownCount);
        for (int i = 0; i < completionRows.length; i++) {
            boolean on = i < shownCount;
            ui.layout().visible(completionRows[i].slot(), on);
            if (on) {
                Completion.Candidate c = completion.candidates().get(firstShown + i);
                ui.text(completionTexts[i], c.text());
                ui.text(completionDetails[i], c.detail());
                ui.layout().visible(completionDetails[i].slot(), !c.detail().isEmpty());
                completionRows[i].semantics(Role.LIST_ITEM, c.text());
                completionRows[i].state(UiState.SELECTED, firstShown + i == selected);
            }
        }
    }

    @AllowAllocation("completion rows are made as needed")
    private void ensureRows(int n) {
        int old = completionRows.length;
        if (old >= n) {
            return;
        }
        completionRows = Arrays.copyOf(completionRows, n);
        completionTexts = Arrays.copyOf(completionTexts, n);
        completionDetails = Arrays.copyOf(completionDetails, n);
        for (int i = old; i < n; i++) {
            Element row = ui.element("console-completion", null);
            completionTexts[i] = ui.label("completion-text", "");
            completionDetails[i] = ui.label("completion-detail", "");
            row.add(completionTexts[i]).add(completionDetails[i]);
            interaction.pickable(row, PointerEvents.ALL);
            completionRows[i] = row;
            popup.add(row);
        }
    }

    private void hidePopup() {
        popupShown = false;
        selected = -1;
        ui.layout().visible(popup.slot(), false);
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void frame(long nanos) {
        if (inbox != null) {
            inbox.drainTo(log);
        }
        UiInput input = ui.input();
        if (input != null && input.has(UiCommand.CONSOLE_TOGGLE)) {
            toggle();
        }
        if (!open && shown && ++closedFrames > 1 && !ui.animator().running(root.slot(), Animator.OFFSETS)) {
            shown = false;
            ui.layout().visible(root.slot(), false);
        }
        if (rows.levels() != shownLevels) {
            shownLevels = rows.levels();
            for (int i = 0; i < levelToggles.length; i++) {
                levelToggles[i].state(UiState.CHECKED, (shownLevels & LogLevel.bit(TOGGLED_LEVELS[i])) != 0);
            }
        }
        if (rows.logger() != shownLogger) {
            shownLogger = rows.logger();
            ui.layout().visible(loggerChip.slot(), shownLogger != null);
            if (shownLogger != null) {
                ui.text(loggerChip, shownLogger);
            }
        }
    }

    @Override
    public void afterLayout() {
        Layout layout = ui.layout();
        if (focusPending && layout.shown(field.element().slot())) {
            focusPending = false;
            field.focus();
        }
        if (popupShown) {
            int f = field.element().slot();
            float s = layout.scale() > 0 ? layout.scale() : 1;
            layout.offsets(popup.slot(), layout.x(f) / s, (layout.y(f) + layout.height(f)) / s, 0, 0);
        }
    }

    /** Clicks and commands. */
    private final class Events implements UiListener {

        @Override
        public void handle(UiEvent e) {
            if (e.type() == EventType.COMMAND) {
                command(e);
            } else if (e.currentSlot() == loggerChip.slot()) {
                e.consume();
                rows.logger(null);
            } else if (e.currentSlot() == popup.slot()) {
                for (int i = 0; i < completionRows.length; i++) {
                    if (completionRows[i].slot() == e.targetSlot() || ui.store().parent(e.targetSlot())
                        == completionRows[i].slot()) {
                        e.consume();
                        take(firstShown + i);
                        field.focus();
                        return;
                    }
                }
            } else {
                for (int i = 0; i < levelToggles.length; i++) {
                    if (e.currentSlot() == levelToggles[i].slot()) {
                        e.consume();
                        rows.levels(rows.levels() ^ LogLevel.bit(TOGGLED_LEVELS[i]));
                    }
                }
            }
        }

        private void command(UiEvent e) {
            UiCommand c = e.command();
            boolean fromField = e.targetSlot() == field.element().slot();
            boolean listing = popupShown && !completion.candidates().isEmpty();
            switch (c) {
                case COMPLETE -> {
                    if (fromField) {
                        e.consume();
                        complete();
                    }
                }
                case HISTORY_UP, UP -> {
                    if (fromField) {
                        e.consume();
                        if (listing) {
                            move(-1);
                        } else {
                            historyStep(true);
                        }
                    }
                }
                case HISTORY_DOWN, DOWN -> {
                    if (fromField) {
                        e.consume();
                        if (listing) {
                            move(1);
                        } else {
                            historyStep(false);
                        }
                    }
                }
                case CANCEL -> {
                    e.consume();
                    if (popupShown) {
                        hidePopup();
                    } else {
                        open(false);
                    }
                }
                case CONSOLE_TOGGLE -> e.consume(); // the frame toggles it, wherever the focus is
                default -> {
                }
            }
        }
    }
}
