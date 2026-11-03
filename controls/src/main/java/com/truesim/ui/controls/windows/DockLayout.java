package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * A dock space's layout as data: a tree of splits (a row or a column of children with their size ratios) and tab
 * groups (panel ids and the active tab), each with an optional style name (its element's, so a saved layout can say
 * how a pane looks), and the panels floating in windows with their places. Serialised as text and parsed back
 * ({@link #parse}, round trip exact):
 *
 * <pre>
 * dock split(row; 0.25 0.75; tabs(0; scene hierarchy) split.editor(column; 0.7 0.3; tabs(1; viewport game) tabs(0; console)))
 * floating inspector 40 60 320 480
 * </pre>
 *
 * Grammar: {@code dock <node>} or {@code dock none}; a node is {@code split[.style](row|column; ratio...; node...)} or
 * {@code tabs[.style](active; id...)}; then {@code floating <id> <x> <y> <width> <height>} lines (panel units). Ids and
 * style names hold no white space nor {@code # , ( ) ;}; {@code #} starts a comment.
 */
@AllowAllocation("layouts are saved and restored on user action")
public record DockLayout(Node root, List<Floating> floating) {

    /** A node of the tree. */
    public sealed interface Node permits Split, Tabs {
        /** The style name of the node's element, or null. */
        String style();
    }

    /** A row ({@code row}) or column of {@code children}, sized by {@code ratios} (one each, summing to 1). */
    public record Split(boolean row, List<Float> ratios, List<Node> children, String style) implements Node {
        public Split {
            ratios = List.copyOf(ratios);
            children = List.copyOf(children);
            if (children.size() < 2 || ratios.size() != children.size()) {
                throw new IllegalArgumentException("a split has two or more children, a ratio each");
            }
            if (style != null) {
                WindowLayout.checkId(style);
            }
        }
    }

    /** A tab group of panels by id, {@code active} the shown one. */
    public record Tabs(List<String> panels, int active, String style) implements Node {
        public Tabs {
            panels = List.copyOf(panels);
            if (panels.isEmpty() || active < 0 || active >= panels.size()) {
                throw new IllegalArgumentException("a tab group has panels and an active one among them");
            }
            for (String id : panels) {
                WindowLayout.checkId(id);
            }
            if (style != null) {
                WindowLayout.checkId(style);
            }
        }
    }

    /** A panel floating in a window at {@code x, y}, {@code width x height} (panel units). */
    public record Floating(String panel, float x, float y, float width, float height) {
        public Floating {
            WindowLayout.checkId(panel);
        }
    }

    public DockLayout {
        floating = List.copyOf(floating);
    }

    /** No panels. */
    public static final DockLayout EMPTY = new DockLayout(null, List.of());

    /** The layout as text. */
    public String toText() {
        StringBuilder out = new StringBuilder("dock ");
        if (root == null) {
            out.append("none");
        } else {
            write(out, root);
        }
        out.append('\n');
        for (Floating f : floating) {
            out.append("floating ").append(f.panel()).append(' ');
            WindowLayout.number(out, f.x()).append(' ');
            WindowLayout.number(out, f.y()).append(' ');
            WindowLayout.number(out, f.width()).append(' ');
            WindowLayout.number(out, f.height()).append('\n');
        }
        return out.toString();
    }

    private static void write(StringBuilder out, Node node) {
        switch (node) {
            case Split s -> {
                out.append("split");
                style(out, s.style());
                out.append(s.row() ? "(row;" : "(column;");
                for (Float r : s.ratios()) {
                    out.append(' ');
                    ratio(out, r);
                }
                out.append(';');
                for (Node c : s.children()) {
                    out.append(' ');
                    write(out, c);
                }
                out.append(')');
            }
            case Tabs t -> {
                out.append("tabs");
                style(out, t.style());
                out.append('(').append(t.active()).append(';');
                for (String id : t.panels()) {
                    out.append(' ').append(id);
                }
                out.append(')');
            }
        }
    }

    private static void style(StringBuilder out, String style) {
        if (style != null) {
            out.append('.').append(style);
        }
    }

    /** Ratios at four decimals: what a saved layout needs, and stable through a round trip. */
    private static void ratio(StringBuilder out, float r) {
        out.append(round(r));
    }

    static float round(float r) {
        return Math.round(r * 10_000f) / 10_000f;
    }

    /** All panel ids in the tree and floating, in order. */
    public List<String> panels() {
        List<String> ids = new ArrayList<>();
        collect(root, ids);
        for (Floating f : floating) {
            ids.add(f.panel());
        }
        return ids;
    }

    private static void collect(Node node, List<String> ids) {
        if (node instanceof Split s) {
            for (Node c : s.children()) {
                collect(c, ids);
            }
        } else if (node instanceof Tabs t) {
            ids.addAll(t.panels());
        }
    }

    /** Parses {@link #toText()}'s form; throws {@link IllegalArgumentException} with the line and column. */
    public static DockLayout parse(String text) {
        Node root = null;
        boolean seen = false;
        List<Floating> floating = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = WindowLayout.strip(lines[i]);
            if (line.isEmpty()) {
                continue;
            }
            try {
                if (line.startsWith("dock ") || line.equals("dock")) {
                    if (seen) {
                        throw new IllegalArgumentException("a second dock line");
                    }
                    seen = true;
                    Reader r = new Reader(line, 4);
                    if (line.substring(4).strip().equals("none")) {
                        root = null;
                    } else {
                        root = r.node();
                        r.end();
                    }
                } else if (line.startsWith("floating ")) {
                    String[] f = line.split("\\s+");
                    if (f.length != 6) {
                        throw new IllegalArgumentException("expected: floating <id> <x> <y> <width> <height>");
                    }
                    floating.add(new Floating(f[1], Float.parseFloat(f[2]), Float.parseFloat(f[3]),
                        Float.parseFloat(f[4]), Float.parseFloat(f[5])));
                } else {
                    throw new IllegalArgumentException("expected dock or floating");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("dock layout line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return new DockLayout(root, floating);
    }

    /** A recursive-descent reader of one dock line. */
    private static final class Reader {
        private final String text;
        int pos;

        Reader(String text, int pos) {
            this.text = text;
            this.pos = pos;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at column " + (pos + 1));
        }

        void space() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        void end() {
            space();
            if (pos != text.length()) {
                throw error("unexpected " + text.substring(pos));
            }
        }

        void expect(char c) {
            space();
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        boolean peek(char c) {
            space();
            return pos < text.length() && text.charAt(pos) == c;
        }

        String word() {
            return word(false);
        }

        /** A word up to white space or {@code ( ) ;} (and {@code .} with {@code dotEnds}: a node's style). */
        String word(boolean dotEnds) {
            space();
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ';' || dotEnds && c == '.') {
                    break;
                }
                pos++;
            }
            if (start == pos) {
                throw error("expected a word");
            }
            return text.substring(start, pos);
        }

        Node node() {
            String kind = word(true);
            String style = null;
            if (peek('.')) {
                pos++;
                style = word(true);
            }
            expect('(');
            Node node;
            if (kind.equals("split")) {
                String orientation = word();
                if (!orientation.equals("row") && !orientation.equals("column")) {
                    throw error("expected row or column");
                }
                expect(';');
                List<Float> ratios = new ArrayList<>();
                while (!peek(';')) {
                    ratios.add(number(word()));
                }
                expect(';');
                List<Node> children = new ArrayList<>();
                while (!peek(')')) {
                    children.add(node());
                }
                node = new Split(orientation.equals("row"), ratios, children, style);
            } else if (kind.equals("tabs")) {
                int active = (int) number(word());
                expect(';');
                List<String> ids = new ArrayList<>();
                while (!peek(')')) {
                    ids.add(word());
                }
                node = new Tabs(ids, active, style);
            } else {
                throw error("expected split or tabs, not " + kind);
            }
            expect(')');
            return node;
        }

        private float number(String word) {
            try {
                return Float.parseFloat(word);
            } catch (NumberFormatException e) {
                throw error("expected a number, not " + word);
            }
        }
    }
}
