package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.UiState;
import com.truesim.ui.style.Selector.Combinator;
import com.truesim.ui.style.Selector.Compound;
import com.truesim.ui.style.Selector.Fact;
import com.truesim.ui.style.expr.ValueException;
import java.util.ArrayList;
import java.util.List;

/** Parses selector text: {@code type.style#id.path[name=value]:state%placeholder}, joined by space or {@code >}. */
@AllowAllocation("selectors are parsed when a sheet is built")
final class SelectorParser {

    private static final String[] STATES = {"hover", "pressed", "focused", "disabled", "selected", "checked",
        "expanded", "editing"};

    private final String text;
    private int pos;

    private SelectorParser(String text) {
        this.text = text;
    }

    static List<Selector> parseList(String text) {
        SelectorParser p = new SelectorParser(text);
        List<Selector> list = new ArrayList<>();
        while (true) {
            list.add(p.selector());
            p.skipSpace();
            if (p.pos == text.length()) {
                return list;
            }
            p.expect(',');
        }
    }

    private ValueException error(String message) {
        return new ValueException("bad selector '" + text.strip() + "': " + message + " at column " + (pos + 1));
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private void expect(char c) {
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private Selector selector() {
        skipSpace();
        List<Compound> compounds = new ArrayList<>();
        List<Combinator> combinators = new ArrayList<>();
        compounds.add(compound());
        while (true) {
            int before = pos;
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) == ',') {
                return new Selector(compounds, combinators);
            }
            if (text.charAt(pos) == '>') {
                pos++;
                skipSpace();
                combinators.add(Combinator.CHILD);
            } else if (pos > before) {
                combinators.add(Combinator.DESCENDANT);
            } else {
                throw error("unexpected '" + text.charAt(pos) + "'");
            }
            compounds.add(compound());
        }
    }

    private Compound compound() {
        int start = pos;
        Compound c = Compound.ANY;
        if (pos < text.length() && text.charAt(pos) == '*') {
            pos++;
        } else if (pos < text.length() && isNameChar(text.charAt(pos))) {
            c = c.withType(name("a type"));
        }
        while (pos < text.length()) {
            char ch = text.charAt(pos);
            if (ch == '.') {
                pos++;
                if (c.style() != null) {
                    throw error("an element has one style name; ." + c.style() + " is set already");
                }
                c = c.withStyle(name("a style name"));
            } else if (ch == '#') {
                pos++;
                if (!c.id().isEmpty()) {
                    throw error("one id path per compound");
                }
                List<String> segments = new ArrayList<>();
                segments.add(name("an id"));
                while (pos < text.length() && text.charAt(pos) == '.') {
                    pos++;
                    segments.add(name("an id segment"));
                }
                c = c.withId(segments);
            } else if (ch == '[') {
                pos++;
                skipSpace();
                String name = name("a fact name");
                skipSpace();
                String value = null;
                if (pos < text.length() && text.charAt(pos) == '=') {
                    pos++;
                    skipSpace();
                    value = factValue();
                    skipSpace();
                }
                expect(']');
                c = c.withFact(new Fact(name, value));
            } else if (ch == ':') {
                pos++;
                int at = pos;
                String state = name("a state");
                int bit = UiState.of(state);
                if (bit == 0) {
                    pos = at;
                    String close = StyleKeys.closest(state, List.of(STATES));
                    throw error("unknown state :" + state + (close == null ? "" : " (did you mean :" + close + "?)"));
                }
                c = c.withStates(c.states() | bit);
            } else if (ch == '%') {
                pos++;
                c = c.withPlaceholder(name("a placeholder name"));
            } else {
                break;
            }
        }
        if (pos == start) {
            throw error(pos < text.length() ? "unexpected '" + text.charAt(pos) + "'" : "expected a selector");
        }
        return c;
    }

    private String factValue() {
        if (pos < text.length() && (text.charAt(pos) == '"' || text.charAt(pos) == '\'')) {
            char quote = text.charAt(pos++);
            int start = pos;
            while (pos < text.length() && text.charAt(pos) != quote) {
                pos++;
            }
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            return text.substring(start, pos++);
        }
        int start = pos;
        while (pos < text.length() && (isNameChar(text.charAt(pos)) || text.charAt(pos) == '.')) {
            pos++;
        }
        if (pos == start) {
            throw error("expected a fact value");
        }
        return text.substring(start, pos);
    }

    private String name(String what) {
        int start = pos;
        while (pos < text.length() && isNameChar(text.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw error("expected " + what);
        }
        return text.substring(start, pos);
    }

    static boolean isNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_';
    }
}
