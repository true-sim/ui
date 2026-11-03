package com.truesim.ui.text.edit;

import com.truesim.annotations.AllowAllocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Emoticon and shortcode replacement, an optional input filter: after typing, {@code :)} followed by a space becomes
 * 🙂 and a completed {@code :smile:} becomes 😄, as an ordinary undoable edit (undo brings the typed text back). The
 * table is a data file ({@code emoticons.txt} beside this class: the text, then code points); {@link #of} reads
 * another.
 */
@AllowAllocation("the table is read once; a replacement is an edit, made when text is typed")
public final class Emoticons {

    /** The longest shortcode looked for. */
    private static final int MAX_SHORTCODE = 40;

    private static Emoticons builtIn;

    private final Map<String, String> emoticons = new HashMap<>();
    private final Map<String, String> shortcodes = new HashMap<>();
    private int longestEmoticon;

    private Emoticons() {
    }

    /** The built-in table. */
    public static synchronized Emoticons builtIn() {
        if (builtIn == null) {
            try (InputStream in = Emoticons.class.getResourceAsStream("emoticons.txt")) {
                builtIn = of(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return builtIn;
    }

    /** A table read from {@code in} (the built-in file's format). */
    public static Emoticons of(InputStream in) throws IOException {
        Emoticons e = new Emoticons();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                throw new IOException("no tab in emoticon line: " + line);
            }
            String key = line.substring(0, tab);
            StringBuilder emoji = new StringBuilder();
            for (String hex : line.substring(tab + 1).trim().split("\\s+")) {
                emoji.appendCodePoint(Integer.parseInt(hex, 16));
            }
            if (key.length() > 2 && key.startsWith(":") && key.endsWith(":")) {
                e.shortcodes.put(key.substring(1, key.length() - 1), emoji.toString());
            } else {
                e.emoticons.put(key, emoji.toString());
                e.longestEmoticon = Math.max(e.longestEmoticon, key.length());
            }
        }
        return e;
    }

    /** The emoji for {@code shortcode} (without colons), or null. */
    public String shortcode(String shortcode) {
        return shortcodes.get(shortcode);
    }

    /** The emoji for {@code emoticon}, or null. */
    public String emoticon(String emoticon) {
        return emoticons.get(emoticon);
    }

    /**
     * Replaces what was just typed before {@code text}'s caret: a completed shortcode (the caret after its closing
     * colon), if {@code shortcodesOn}; an emoticon standing alone before a just-typed space or line break, if
     * {@code emoticonsOn}. Returns whether it replaced anything.
     */
    public boolean apply(EditableText text, boolean emoticonsOn, boolean shortcodesOn) {
        if (text.hasSelection() || text.composing()) {
            return false;
        }
        CharSequence s = text.text();
        int caret = text.caret();
        if (caret == 0) {
            return false;
        }
        char last = s.charAt(caret - 1);
        if (shortcodesOn && last == ':') {
            for (int i = caret - 2; i >= 0 && caret - i <= MAX_SHORTCODE; i--) {
                char c = s.charAt(i);
                if (c == ':') {
                    if (i + 1 < caret - 1 && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                        String emoji = shortcodes.get(s.subSequence(i + 1, caret - 1).toString());
                        if (emoji != null) {
                            return text.replace(i, caret, emoji);
                        }
                    }
                    break;
                }
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '+' || c == '-')) {
                    break;
                }
            }
        }
        if (emoticonsOn && Character.isWhitespace(last)) {
            int end = caret - 1;
            int start = end;
            while (start > 0 && end - start < longestEmoticon && !Character.isWhitespace(s.charAt(start - 1))) {
                start--;
            }
            if (start < end && (start == 0 || Character.isWhitespace(s.charAt(start - 1)))) {
                String emoji = emoticons.get(s.subSequence(start, end).toString());
                if (emoji != null) {
                    boolean ok = text.replace(start, end, emoji);
                    if (ok) {
                        text.moveTo(text.caret() + 1, false); // after the space again
                    }
                    return ok;
                }
            }
        }
        return false;
    }
}
