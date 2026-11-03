package com.truesim.ui.text.edit;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * The inline markup rich text is written in: a small BBCode.
 *
 * <pre>
 * [b]bold[/b] [i]italic[/i] [u]underlined[/u] [s]struck[/s]
 * [color=#ff8800]orange[/color] [size=20]large[/size] [bg=#ffff0080]highlighted[/bg]
 * [class=warning]styled by span.warning in the theme[/class]
 * [link=say hello]a link: its target goes to the link listener[/link]
 * an icon [img=icons/heart] inline          [[ is a literal [
 * </pre>
 *
 * <p>Tags nest and may close in any order (a closing tag closes the latest open tag of its name); a value with
 * {@code ]} in it is quoted ({@code [link="a]b"]}, a quote inside doubled). The parser is forgiving: an unknown tag, a
 * closing tag with nothing open and a {@code [} that starts no tag stay text, and tags left open close at the end.
 * {@link #format} writes what {@link #parse} reads: {@code parse(format(t, s))} gives back {@code t} and {@code s}
 * (normalized).
 *
 * <p>Why BBCode and not a markdown-like syntax: class spans and links with arbitrary targets (console commands with
 * spaces and brackets) need named, valued, explicitly closed spans, which BBCode has and markdown does not (it would
 * need an extension such as {@code [text]{.class}}); markdown's emphasis by {@code *} and {@code _} follows
 * context-dependent delimiter rules and misreads ordinary game text ({@code 2*3*4}, {@code snake_case}, file names),
 * while BBCode reserves one character, {@code [}, escaped by doubling. Every span kind is written the same way, so new
 * ones need no new syntax.
 */
@AllowAllocation("markup is parsed and written when text is set or saved, not per frame")
public final class Markup {

    private Markup() {
    }

    /** Parses {@code markup} into {@code text} (cleared) and {@code spans} (cleared). */
    public static void parse(CharSequence markup, StringBuilder text, Spans spans) {
        text.setLength(0);
        spans.clear();
        List<Open> open = new ArrayList<>();
        List<Open> closed = new ArrayList<>();
        int n = markup.length();
        int i = 0;
        while (i < n) {
            char c = markup.charAt(i);
            if (c != '[') {
                text.append(c);
                i++;
                continue;
            }
            if (i + 1 < n && markup.charAt(i + 1) == '[') {
                text.append('[');
                i += 2;
                continue;
            }
            int tagEnd = tagEnd(markup, i);
            if (tagEnd < 0) {
                text.append(c);
                i++;
                continue;
            }
            String body = markup.subSequence(i + 1, tagEnd).toString();
            if (!tag(body, text, open, closed)) {
                text.append(markup, i, tagEnd + 1); // not a tag of ours: text
            }
            i = tagEnd + 1;
        }
        for (Open o : open) {
            closed.add(o.close(text.length()));
        }
        closed.sort((a, b) -> Integer.compare(a.order, b.order)); // in opening order: outer spans first
        for (Open o : closed) {
            spans.add(o.start, o.end, o.kind, o.value);
        }
        spans.normalize();
    }

    private record Open(Spans.Kind kind, String value, int start, int end, int order) {
        Open close(int at) {
            return new Open(kind, value, start, at, order);
        }
    }

    /** The index of the {@code ]} closing the tag that starts at {@code from}, or -1. */
    private static int tagEnd(CharSequence s, int from) {
        boolean quoted = false;
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted; // a doubled quote toggles twice
            } else if (c == ']' && !quoted) {
                return i;
            } else if (c == '\n' || c == '[' && !quoted) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean tag(String body, StringBuilder text, List<Open> open, List<Open> closed) {
        if (body.startsWith("/")) {
            Spans.Kind k = Spans.Kind.ofTag(body.substring(1).strip());
            if (k == null) {
                return false;
            }
            for (int j = open.size() - 1; j >= 0; j--) {
                if (open.get(j).kind == k) {
                    closed.add(open.remove(j).close(text.length()));
                    return true;
                }
            }
            return false;
        }
        int eq = body.indexOf('=');
        String name = (eq < 0 ? body : body.substring(0, eq)).strip();
        Spans.Kind k = Spans.Kind.ofTag(name);
        if (k == null || k.valued() != (eq >= 0)) {
            return false;
        }
        String value = eq < 0 ? null : unquote(body.substring(eq + 1).strip());
        if (k == Spans.Kind.IMAGE) {
            int at = text.length();
            text.append(Spans.OBJECT);
            closed.add(new Open(k, value, at, at + 1, open.size() + closed.size()));
            return true;
        }
        open.add(new Open(k, value, text.length(), -1, open.size() + closed.size()));
        return true;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\"\"", "\"");
        }
        return v;
    }

    /** Writes {@code text} with {@code spans} as markup. */
    public static String format(CharSequence text, Spans spans) {
        StringBuilder out = new StringBuilder(text.length() + spans.count() * 12);
        List<Integer> open = new ArrayList<>(); // span indices, in opening order
        int n = text.length();
        for (int at = 0; at <= n; at++) {
            for (int j = open.size() - 1; j >= 0; j--) { // closings, innermost first
                int sp = open.get(j);
                if (spans.end(sp) == at) {
                    out.append("[/").append(spans.kind(sp).tag()).append(']');
                    open.remove(j);
                }
            }
            if (at == n) {
                break;
            }
            boolean image = false;
            for (int sp = 0; sp < spans.count(); sp++) {
                if (spans.start(sp) != at) {
                    continue;
                }
                Spans.Kind k = spans.kind(sp);
                if (k == Spans.Kind.IMAGE) {
                    image = text.charAt(at) == Spans.OBJECT;
                    if (image) {
                        out.append("[img=").append(quote(spans.value(sp))).append(']');
                    }
                    continue;
                }
                out.append('[').append(k.tag());
                if (k.valued()) {
                    out.append('=').append(quote(spans.value(sp)));
                }
                out.append(']');
                open.add(sp);
            }
            char c = text.charAt(at);
            if (c == '[') {
                out.append("[[");
            } else if (!image) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String quote(String v) {
        if (v.indexOf(']') < 0 && v.indexOf('"') < 0 && v.indexOf('[') < 0 && v.equals(v.strip())) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    /** {@code text} with {@code [} escaped, for putting plain text into markup. */
    public static String escape(CharSequence text) {
        return text.toString().replace("[", "[[");
    }
}
