package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The remembered places of floating windows: per window id its position and size (in panel units, within its host),
 * its {@link WindowMode} and whether it is open. Serialised as text, one window a line:
 *
 * <pre>
 * window inspector 40 60 320 480 normal open
 * window console 0 400 800 200 minimised closed
 * </pre>
 *
 * Window ids hold no white space. {@link #parse} reads what {@link #toText()} writes ({@code #} starts a comment).
 */
@AllowAllocation("layouts are saved and restored on user action")
public record WindowLayout(List<State> windows) {

    /** One window's remembered state. */
    public record State(String id, float x, float y, float width, float height, WindowMode mode, boolean open) {

        public State {
            checkId(id);
            Objects.requireNonNull(mode, "mode");
        }
    }

    public WindowLayout {
        windows = List.copyOf(windows);
    }

    /** No windows. */
    public static final WindowLayout EMPTY = new WindowLayout(List.of());

    /** The state of window {@code id}, or null. */
    public State find(String id) {
        for (State s : windows) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** The layout as text. */
    public String toText() {
        StringBuilder out = new StringBuilder();
        for (State s : windows) {
            out.append("window ").append(s.id()).append(' ');
            number(out, s.x()).append(' ');
            number(out, s.y()).append(' ');
            number(out, s.width()).append(' ');
            number(out, s.height()).append(' ').append(s.mode().key()).append(s.open() ? " open" : " closed")
                .append('\n');
        }
        return out.toString();
    }

    /** Parses {@link #toText()}'s form; throws {@link IllegalArgumentException} naming the bad line. */
    public static WindowLayout parse(String text) {
        List<State> states = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = strip(lines[i]);
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\\s+");
            try {
                if (f.length != 8 || !f[0].equals("window")) {
                    throw new IllegalArgumentException("expected: window <id> <x> <y> <width> <height> <mode> <open|closed>");
                }
                if (!f[7].equals("open") && !f[7].equals("closed")) {
                    throw new IllegalArgumentException("expected open or closed, not " + f[7]);
                }
                states.add(new State(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]),
                    Float.parseFloat(f[5]), WindowMode.ofKey(f[6]), f[7].equals("open")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("window layout line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return new WindowLayout(states);
    }

    static String strip(String line) {
        int hash = line.indexOf('#');
        return (hash >= 0 ? line.substring(0, hash) : line).strip();
    }

    static StringBuilder number(StringBuilder out, float v) {
        if (v == (int) v) {
            return out.append((int) v);
        }
        return out.append(v);
    }

    static void checkId(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("an id is needed");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isWhitespace(c) || c == '#' || c == ',' || c == '(' || c == ')' || c == ';') {
                throw new IllegalArgumentException("an id holds no white space nor # , ( ) ;: " + id);
            }
        }
    }
}
