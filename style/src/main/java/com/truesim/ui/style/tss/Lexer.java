package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits {@code .tss} source into tokens on demand: {@link #token(int)} reads the token at or after an offset, so the
 * parser can also scan raw text (selectors) between tokens. Comments ({@code //} and {@code /* *}{@code /}) and
 * whitespace are skipped; a token records whether whitespace came before it, which space-separated lists need.
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

    private static final String[] TWO_CHAR = {"==", "!=", "<=", ">=", "#{"};

    final String file;
    final String src;
    private final int[] lineStarts;
    private int cachedAt = -1;
    private Token cached;

    Lexer(String file, String src) {
        this.file = file;
        this.src = src;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        lineStarts = new int[starts.size()];
        for (int i = 0; i < lineStarts.length; i++) {
            lineStarts[i] = starts.get(i);
        }
    }

    /** The 1-based line of {@code offset}. */
    int line(int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    /** The 1-based column of {@code offset}. */
    int column(int offset) {
        return offset - lineStarts[line(offset) - 1] + 1;
    }

    /** A position for messages. */
    Ast.Pos pos(int offset) {
        return new Ast.Pos(file, line(offset), column(offset));
    }

    TssException error(int offset, String message) {
        return new TssException(message, file, line(offset), column(offset), null, List.of());
    }

    /** The offset of the first character that is not whitespace or a comment, at or after {@code from}. */
    int skip(int from) {
        int i = from;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
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

    /** The token at or after {@code from}. */
    Token token(int from) {
        if (from == cachedAt) {
            return cached;
        }
        Token t = read(from);
        cachedAt = from;
        cached = t;
        return t;
    }

    static boolean isNameStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c > 127;
    }

    static boolean isName(char c) {
        return isNameStart(c) || c >= '0' && c <= '9' || c == '-';
    }

    private int name(int i) {
        while (i < src.length() && isName(src.charAt(i))) {
            i++;
        }
        return i;
    }

    private char at(int i) {
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private Token read(int from) {
        int start = skip(from);
        boolean space = start > from || start == 0;
        if (start >= src.length()) {
            return new Token(Kind.EOF, "", 0, "", start, start, space);
        }
        char c = src.charAt(start);
        if (c == '$') {
            int end = name(start + 1);
            if (end == start + 1) {
                throw error(start, "expected a variable name after $");
            }
            return new Token(Kind.VAR, src.substring(start + 1, end), 0, "", start, end, space);
        }
        if (c == '@') {
            int end = name(start + 1);
            if (end == start + 1) {
                throw error(start, "expected an at-rule name after @");
            }
            return new Token(Kind.AT, src.substring(start + 1, end), 0, "", start, end, space);
        }
        if (c == '#' && at(start + 1) != '{') {
            int end = name(start + 1);
            return new Token(Kind.HASH, src.substring(start + 1, end), 0, "", start, end, space);
        }
        if (c == '"' || c == '\'') {
            return string(start, c, space);
        }
        if (c >= '0' && c <= '9' || c == '.' && at(start + 1) >= '0' && at(start + 1) <= '9') {
            return number(start, space);
        }
        if (isNameStart(c) || c == '-' && (isNameStart(at(start + 1)) || at(start + 1) == '-')) {
            int end = name(start + 1);
            return new Token(Kind.IDENT, src.substring(start, end), 0, "", start, end, space);
        }
        if (src.startsWith("...", start)) {
            return new Token(Kind.PUNCT, "...", 0, "", start, start + 3, space);
        }
        for (String two : TWO_CHAR) {
            if (src.startsWith(two, start)) {
                return new Token(Kind.PUNCT, two, 0, "", start, start + 2, space);
            }
        }
        if ("{}()[]:;,.&><=!+-*/%~".indexOf(c) >= 0) {
            return new Token(Kind.PUNCT, String.valueOf(c), 0, "", start, start + 1, space);
        }
        throw error(start, "unexpected character '" + c + "'");
    }

    private Token string(int start, char quote, boolean space) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (true) {
            if (i >= src.length() || src.charAt(i) == '\n') {
                throw error(start, "unterminated string");
            }
            char c = src.charAt(i);
            if (c == quote) {
                return new Token(Kind.STRING, sb.toString(), 0, "", start, i + 1, space);
            }
            if (c == '\\' && i + 1 < src.length()) {
                char e = src.charAt(++i);
                sb.append(e == 'n' ? '\n' : e);
            } else {
                sb.append(c);
            }
            i++;
        }
    }

    private Token number(int start, boolean space) {
        int i = start;
        while (at(i) >= '0' && at(i) <= '9') {
            i++;
        }
        if (at(i) == '.' && at(i + 1) >= '0' && at(i + 1) <= '9') {
            i++;
            while (at(i) >= '0' && at(i) <= '9') {
                i++;
            }
        }
        double value = Double.parseDouble(src.substring(start, i));
        int unitStart = i;
        if (at(i) == '%') {
            i++;
        } else {
            while (at(i) >= 'a' && at(i) <= 'z' || at(i) >= 'A' && at(i) <= 'Z') {
                i++;
            }
        }
        String unit = src.substring(unitStart, i);
        return new Token(Kind.NUMBER, src.substring(start, i), value, unit, start, i, space);
    }
}
