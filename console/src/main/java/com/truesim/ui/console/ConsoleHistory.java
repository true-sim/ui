package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * The command lines run in a console, per app id (design 5.10), loaded from and saved to a {@link HistoryStore}: the
 * last {@link ConsoleTunables#HISTORY_LIMIT} lines, a line repeating the one before it kept once. {@link #older} and
 * {@link #newer} walk it from the newest line, keeping the line being typed to come back to.
 */
@AllowAllocation("history changes on events: a line run, a key pressed")
public final class ConsoleHistory {

    private final String appId;
    private final HistoryStore store;
    private final List<String> lines;
    private int at; // lines.size(): the line being typed
    private String draft = "";

    /** The history of {@code appId}, loaded from {@code store}. */
    public ConsoleHistory(String appId, HistoryStore store) {
        this.appId = appId;
        this.store = store;
        this.lines = new ArrayList<>(store.load(appId));
        trim();
        at = lines.size();
    }

    /** The app id it is kept under. */
    public String appId() {
        return appId;
    }

    /** The lines, oldest first. */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** Adds a line that was run and saves the history; back to the line being typed. */
    public void add(String line) {
        String l = line.strip();
        if (!l.isEmpty() && (lines.isEmpty() || !lines.getLast().equals(l))) {
            lines.add(l);
            trim();
            store.save(appId, lines);
        }
        at = lines.size();
        draft = "";
    }

    private void trim() {
        int limit = (int) ConsoleTunables.HISTORY_LIMIT.get();
        while (lines.size() > limit) {
            lines.removeFirst();
        }
    }

    /** The line before the one shown ({@code current} is kept when leaving the line being typed), or null. */
    public String older(CharSequence current) {
        if (at == 0) {
            return null;
        }
        if (at == lines.size()) {
            draft = current.toString();
        }
        return lines.get(--at);
    }

    /** The line after the one shown (the line being typed after the newest), or null when already there. */
    public String newer() {
        if (at >= lines.size()) {
            return null;
        }
        at++;
        return at == lines.size() ? draft : lines.get(at);
    }

    /** Back to the line being typed (the walk starts at the newest line again). */
    public void reset() {
        at = lines.size();
    }
}
