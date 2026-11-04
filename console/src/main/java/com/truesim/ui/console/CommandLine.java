package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * The console's command line syntax: words separated by blanks, statements by {@code ;}.
 *
 * <ul>
 *   <li>{@code "double quotes"} keep blanks and {@code ;}, with escapes {@code \"}, {@code \\}, {@code \n},
 *       {@code \t} and {@code \;};</li>
 *   <li>{@code 'single quotes'} keep everything as written;</li>
 *   <li>outside quotes a backslash keeps the next character ({@code a\ b} is one word);</li>
 *   <li>{@code //} at the start of a word comments out the rest of the line (scripts).</li>
 * </ul>
 *
 * Tokens keep where they were written ({@link Token#start()}, {@link Token#end()}), so completion knows the word
 * under the caret; an unclosed quote runs to the end of the line ({@link Token#closed()} says so) rather than failing,
 * since the line is being typed. Parsing runs on events (a command run, a key typed), not per frame.
 */
@AllowAllocation("lines are parsed on events: a command run, a key typed")
public final class CommandLine {

    /** A word of a statement: its text (quotes and escapes resolved) and where it was written, end exclusive. */
    public record Token(String text, int start, int end, boolean quoted, boolean closed) {
    }

    /** A statement: its words, and where it starts and ends in the line. */
    public record Statement(List<Token> tokens, int start, int end) {

        /** The statement's first word (its command), or null when it has none. */
        public String name() {
            return tokens.isEmpty() ? null : tokens.getFirst().text();
        }
    }

    private CommandLine() {
    }

    /** The statements of {@code line}, empty ones left out. */
    public static List<Statement> parse(CharSequence line) {
        List<Statement> out = new ArrayList<>();
        List<Token> tokens = new ArrayList<>();
        int n = line.length();
        int i = 0;
        int statementStart = 0;
        StringBuilder word = new StringBuilder();
        while (i < n) {
            char c = line.charAt(i);
            if (c == ';') {
                add(out, tokens, statementStart, i);
                tokens = new ArrayList<>();
                statementStart = ++i;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                break; // a comment: the rest of the line
            }
            int start = i;
            word.setLength(0);
            boolean quoted = false;
            boolean closed = true;
            while (i < n) {
                c = line.charAt(i);
                if (c == '"') {
                    quoted = true;
                    i++;
                    closed = false;
                    while (i < n) {
                        char d = line.charAt(i);
                        if (d == '"') {
                            closed = true;
                            i++;
                            break;
                        }
                        if (d == '\\' && i + 1 < n) {
                            word.append(escape(line.charAt(i + 1)));
                            i += 2;
                        } else {
                            word.append(d);
                            i++;
                        }
                    }
                } else if (c == '\'') {
                    quoted = true;
                    i++;
                    closed = false;
                    while (i < n) {
                        char d = line.charAt(i++);
                        if (d == '\'') {
                            closed = true;
                            break;
                        }
                        word.append(d);
                    }
                } else if (c == '\\' && i + 1 < n) {
                    word.append(line.charAt(i + 1));
                    i += 2;
                } else if (Character.isWhitespace(c) || c == ';') {
                    break;
                } else {
                    word.append(c);
                    i++;
                }
            }
            tokens.add(new Token(word.toString(), start, i, quoted, closed));
        }
        add(out, tokens, statementStart, n);
        return out;
    }

    private static void add(List<Statement> out, List<Token> tokens, int start, int end) {
        if (!tokens.isEmpty()) {
            out.add(new Statement(List.copyOf(tokens), start, end));
        }
    }

    private static char escape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            default -> c;
        };
    }

    /** The statement the caret at {@code caret} is in (an empty one when it is in none), for completion. */
    public static Statement at(CharSequence line, int caret) {
        // statements end at the separators the parser saw: find the separator before the caret outside quotes
        int start = 0;
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < caret && i < line.length(); i++) {
            char c = line.charAt(i);
            if (inDouble) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (inSingle) {
                inSingle = c != '\'';
            } else if (c == '\\') {
                i++;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == ';') {
                start = i + 1;
            }
        }
        CharSequence rest = line.subSequence(start, line.length());
        List<Statement> parsed = parse(rest);
        List<Token> shifted = new ArrayList<>();
        int end = line.length();
        if (!parsed.isEmpty() && parsed.getFirst().start() == 0) {
            Statement first = parsed.getFirst();
            for (Token t : first.tokens()) {
                shifted.add(new Token(t.text(), t.start() + start, t.end() + start, t.quoted(), t.closed()));
            }
            end = first.end() + start;
        }
        return new Statement(shifted, start, end);
    }

    /**
     * {@code text} as one word: as it is when it needs no quotes, else in double quotes with {@code "} and
     * {@code \} escaped.
     */
    public static String quote(String text) {
        boolean plain = !text.isEmpty();
        for (int i = 0; i < text.length() && plain; i++) {
            char c = text.charAt(i);
            plain = !Character.isWhitespace(c) && c != '"' && c != '\'' && c != ';' && c != '\\';
        }
        if (plain && !text.startsWith("//")) {
            return text;
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }
}
