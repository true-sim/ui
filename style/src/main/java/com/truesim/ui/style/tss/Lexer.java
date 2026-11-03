package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.List;

/**
 * Splits {@code .tss} source into tokens, once ({@link #tokenize()}); tokens keep their offsets, so the parser can also
 * take raw text (selectors) between them. Comments ({@code //} and {@code /* *}{@code /}) and whitespace are skipped;
 * a token records whether whitespace came before it, which space-separated lists need. Lines and columns are worked
 * out only when a message needs them.
 */
@AllowAllocation("sheets are compiled at load")
final class Lexer {

    /** Token kinds. */
    enum Kind {
        IDENT, VAR, NUMBER, STRING, HASH, AT, PUNCT, EOF
    }

    /** One token: its text (without {@code $}, {@code @}, {@code #} or quotes), number and unit, and where it is. */
    record Token(Kind kind, String text, double number, String unit, int start, int end, boolean spaceBefore) {
        boolean is(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }

        boolean isIdent(String name) {
            return kind == Kind.IDENT && text.equals(name);
        }

        String show() {
            return switch (kind) {
                case EOF -> "end of file";
                case VAR -> "$" + text;
                case AT -> "@" + text;
                case HASH -> "#" + text;
                case STRING -> "\"" + text + "\"";
                case NUMBER -> text;
                default -> "'" + text + "'";
            };
        }
    }

    /** One-character punctuation as shared strings, so a token of it allocates no text. */
    private static final String[] PUNCT = new String[128];
    private static final double[] POWERS = {1, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
        1e14, 1e15, 1e16, 1e17, 1e18};

    static {
        for (char c : "{}()[]:;,.&><=!+-*/%~".toCharArray()) {
            PUNCT[c] = String.valueOf(c);
        }
    }

    final String file;
    final String src;
    private int[] lineStarts;

    Lexer(String file, String src) {
        this.file = file;
        this.src = src;
    }

    private int[] lineStarts() {
        if (lineStarts == null) {
            int[] starts = new int[16];
            int n = 1;
            for (int i = 0; i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    if (n == starts.length) {
                        starts = Arrays.copyOf(starts, n * 2);
                    }
                    starts[n++] = i + 1;
                }
            }
            lineStarts = Arrays.copyOf(starts, n);
        }
        return lineStarts;
    }

    /** The 1-based line of {@code offset}. */
    int line(int offset) {
        int[] starts = lineStarts();
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    /** The 1-based column of {@code offset}. */
    int column(int offset) {
        return offset - lineStarts()[line(offset) - 1] + 1;
    }

    /** A position for messages. */
    Ast.Pos pos(int offset) {
        return new Ast.Pos(this, offset);
    }

    TssException error(int offset, String message) {
        return new TssException(message, file, line(offset), column(offset), null, List.of());
    }

    /** The offset of the first character that is not whitespace or a comment, at or after {@code from}. */
    int skip(int from) {
        int i = from;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) {
                    throw error(i, "unterminated comment");
                }
                i = end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    /** Every token of the source, the last one {@link Kind#EOF}: lexed once, read by index. */
    Token[] tokenize() {
        Token[] out = new Token[Math.max(16, src.length() / 3)];
        int n = 0;
        int from = 0;
        while (true) {
            Token t = read(from);
            if (n == out.length) {
                out = Arrays.copyOf(out, n * 2);
            }
            out[n++] = t;
            if (t.kind() == Kind.EOF) {
                return Arrays.copyOf(out, n);
            }
            from = t.end();
        }
    }

    static boolean isNameStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c > 127;
    }

    static boolean isName(char c) {
        return isNameStart(c) || c >= '0' && c <= '9' || c == '-';
    }

    private int name(int i) {
        int n = src.length();
        while (i < n && isName(src.charAt(i))) {
            i++;
        }
        return i;
    }

    private char at(int i) {
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private Token read(int from) {
        int start = skip(from);
        boolean space = start > from || start == 0;
        if (start >= src.length()) {
            return new Token(Kind.EOF, "", 0, "", start, start, space);
        }
        char c = src.charAt(start);
        char c1 = at(start + 1);
        switch (c) {
            case '$' -> {
                int end = name(start + 1);
                if (end == start + 1) {
                    throw error(start, "expected a variable name after $");
                }
                return new Token(Kind.VAR, src.substring(start + 1, end), 0, "", start, end, space);
            }
            case '@' -> {
                int end = name(start + 1);
                if (end == start + 1) {
                    throw error(start, "expected an at-rule name after @");
                }
                return new Token(Kind.AT, src.substring(start + 1, end), 0, "", start, end, space);
            }
            case '#' -> {
                if (c1 == '{') {
                    return new Token(Kind.PUNCT, "#{", 0, "", start, start + 2, space);
                }
                int end = name(start + 1);
                return new Token(Kind.HASH, src.substring(start + 1, end), 0, "", start, end, space);
            }
            case '"', '\'' -> {
                return string(start, c, space);
            }
            case '=', '!', '<', '>' -> {
                if (c1 == '=') {
                    return new Token(Kind.PUNCT, src.substring(start, start + 2), 0, "", start, start + 2, space);
                }
                return punct(c, start, space);
            }
            case '.' -> {
                if (digit(c1)) {
                    return number(start, space);
                }
                if (c1 == '.' && at(start + 2) == '.') {
                    return new Token(Kind.PUNCT, "...", 0, "", start, start + 3, space);
                }
                return punct(c, start, space);
            }
            case '-' -> {
                if (isNameStart(c1) || c1 == '-') {
                    int end = name(start + 1);
                    return new Token(Kind.IDENT, src.substring(start, end), 0, "", start, end, space);
                }
                return punct(c, start, space);
            }
            default -> {
                if (digit(c)) {
                    return number(start, space);
                }
                if (isNameStart(c)) {
                    int end = name(start + 1);
                    return new Token(Kind.IDENT, src.substring(start, end), 0, "", start, end, space);
                }
                return punct(c, start, space);
            }
        }
    }

    private Token punct(char c, int start, boolean space) {
        String text = c < 128 ? PUNCT[c] : null;
        if (text == null) {
            throw error(start, "unexpected character '" + c + "'");
        }
        return new Token(Kind.PUNCT, text, 0, "", start, start + 1, space);
    }

    private Token string(int start, char quote, boolean space) {
        StringBuilder sb = null;
        int from = start + 1;
        int i = from;
        while (true) {
            if (i >= src.length() || src.charAt(i) == '\n') {
                throw error(start, "unterminated string");
            }
            char c = src.charAt(i);
            if (c == quote) {
                String text = sb == null ? src.substring(from, i) : sb.append(src, from, i).toString();
                return new Token(Kind.STRING, text, 0, "", start, i + 1, space);
            }
            if (c == '\\' && i + 1 < src.length()) {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(src, from, i);
                char e = src.charAt(i + 1);
                sb.append(e == 'n' ? '\n' : e);
                i += 2;
                from = i;
            } else {
                i++;
            }
        }
    }

    private Token number(int start, boolean space) {
        int i = start;
        long mantissa = 0;
        int digits = 0;
        int decimals = 0;
        while (digit(at(i))) {
            mantissa = mantissa * 10 + (at(i) - '0');
            digits++;
            i++;
        }
        if (at(i) == '.' && digit(at(i + 1))) {
            i++;
            while (digit(at(i))) {
                mantissa = mantissa * 10 + (at(i) - '0');
                digits++;
                decimals++;
                i++;
            }
        }
        // exact digits divided by an exact power of ten round once, as parseDouble would; else parse the text
        double value = digits <= 15 && decimals < POWERS.length ? mantissa / POWERS[decimals]
            : Double.parseDouble(src.substring(start, i));
        int unitStart = i;
        if (at(i) == '%') {
            i++;
        } else {
            while (at(i) >= 'a' && at(i) <= 'z' || at(i) >= 'A' && at(i) <= 'Z') {
                i++;
            }
        }
        return new Token(Kind.NUMBER, src.substring(start, i), value, unit(unitStart, i), start, i, space);
    }

    /** The unit from {@code start} to {@code end}: the common ones as shared strings. */
    private String unit(int start, int end) {
        int n = end - start;
        if (n == 0) {
            return "";
        }
        if (n == 1 && src.charAt(start) == '%') {
            return "%";
        }
        if (n == 2 && src.charAt(start) == 'p' && src.charAt(start + 1) == 'x') {
            return "px";
        }
        return src.substring(start, end);
    }
}
