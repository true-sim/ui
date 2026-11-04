package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.List;

/**
 * What completing a console line at a caret found (design 5.10): the words that could replace the one under the
 * caret ({@link #start()} to {@link #end()}), each with a detail (a description, a current value), and the hint: the
 * signature of the command being typed with the argument at the caret marked ({@link #hintStart()} to
 * {@link #hintEnd()}, -1 for none).
 */
@AllowAllocation("completion runs on keys typed")
public record Completion(int start, int end, List<Candidate> candidates, String hint, int hintStart, int hintEnd) {

    /** A word and what it is. */
    public record Candidate(String text, String detail) {
    }

    /** Nothing to complete, no hint. */
    public static final Completion NONE = new Completion(0, 0, List.of(), "", -1, -1);

    /** Whether there is nothing to offer. */
    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    /**
     * {@code line} with candidate {@code index} in place of the word under the caret (quoted when it needs quotes),
     * followed by a blank when the word was at the end of the line.
     */
    public String apply(CharSequence line, int index) {
        String word = CommandLine.quote(candidates.get(index).text());
        String before = line.subSequence(0, start).toString();
        String after = line.subSequence(Math.min(end, line.length()), line.length()).toString();
        return before + word + (after.isEmpty() ? " " : after);
    }

    /** Where the caret goes after {@link #apply}: after the word (and its blank). */
    public int caretAfter(CharSequence line, int index) {
        String word = CommandLine.quote(candidates.get(index).text());
        return start + word.length() + (end >= line.length() ? 1 : 0);
    }

    /** The longest start all candidates share (ignoring case), at least as long as nothing. */
    public String commonPrefix() {
        if (candidates.isEmpty()) {
            return "";
        }
        String first = candidates.getFirst().text();
        int n = first.length();
        for (Candidate c : candidates) {
            String t = c.text();
            int k = 0;
            while (k < n && k < t.length() && Character.toLowerCase(t.charAt(k)) == Character.toLowerCase(first.charAt(k))) {
                k++;
            }
            n = k;
        }
        return first.substring(0, n);
    }
}
