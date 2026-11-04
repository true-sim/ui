package com.truesim.ui.text.spell;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A BK-tree over the words of a {@link WordSet} by edit distance ({@link #distance}: insertions, deletions,
 * substitutions and swaps of neighbours, each 1): the index suggestions search. A query for the words within distance
 * {@code d} of a word visits only the subtrees whose edge distance lies within {@code d} of the word's distance to
 * their parent (the triangle inequality), a small part of the tree for {@code d} of 1 or 2. Built once (a dictionary's
 * words do not change; the few user words are searched directly); arrays, no node objects.
 */
public final class BkTree {

    private static final int MAX_LENGTH = 64; // longer words are not indexed (nothing suggests for them)

    private final WordSet words;
    private int[] node = new int[16]; // word number per node
    private int[] firstChild = new int[16];
    private int[] nextSibling = new int[16];
    private int[] edge = new int[16]; // distance to the parent
    private int count;
    private final int[] row0 = new int[MAX_LENGTH + 2];
    private final int[] row1 = new int[MAX_LENGTH + 2];
    private final int[] row2 = new int[MAX_LENGTH + 2];
    private int[] stack = new int[64];

    /** Indexes every word of {@code words} (except those longer than 64 characters) and those {@code skip} rejects. */
    @AllowAllocation("built once per dictionary")
    public BkTree(WordSet words, java.util.function.IntPredicate skip) {
        this.words = words;
        for (int w = 0; w < words.size(); w++) {
            if (words.length(w) <= MAX_LENGTH && (skip == null || !skip.test(w))) {
                insert(w);
            }
        }
    }

    /** How many words the tree holds. */
    public int size() {
        return count;
    }

    @AllowAllocation("built once per dictionary")
    private void insert(int w) {
        if (count == node.length) {
            int n = count * 2;
            node = Arrays.copyOf(node, n);
            firstChild = Arrays.copyOf(firstChild, n);
            nextSibling = Arrays.copyOf(nextSibling, n);
            edge = Arrays.copyOf(edge, n);
        }
        int me = count++;
        node[me] = w;
        firstChild[me] = -1;
        nextSibling[me] = -1;
        if (me == 0) {
            return;
        }
        int at = 0;
        while (true) {
            int d = distance(w, node[at]);
            if (d == 0) {
                count--; // the same word (cannot happen in a set; kept safe)
                return;
            }
            int c = firstChild[at];
            while (c >= 0 && edge[c] != d) {
                c = nextSibling[c];
            }
            if (c < 0) {
                edge[me] = d;
                nextSibling[me] = firstChild[at];
                firstChild[at] = me;
                return;
            }
            at = c;
        }
    }

    /** What a query hands each word it finds. */
    @FunctionalInterface
    public interface Found {
        /** Word number {@code word} lies {@code distance} edits from the query. */
        void found(int word, int distance);
    }

    /** Hands every word within {@code max} edits of {@code query} to {@code out}. */
    @AllowAllocation("suggestions are asked for on user action")
    public void query(CharSequence query, int max, Found out) {
        if (count == 0 || query.length() > MAX_LENGTH) {
            return;
        }
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int at = stack[--top];
            int d = distance(query, node[at]);
            if (d <= max) {
                out.found(node[at], d);
            }
            for (int c = firstChild[at]; c >= 0; c = nextSibling[c]) {
                if (edge[c] >= d - max && edge[c] <= d + max) {
                    if (top == stack.length) {
                        stack = Arrays.copyOf(stack, top * 2);
                    }
                    stack[top++] = c;
                }
            }
        }
    }

    private int distance(int a, int b) {
        int la = words.length(a);
        int lb = words.length(b);
        int[] prev2 = row0;
        int[] prev = row1;
        int[] cur = row2;
        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            char ca = words.charAt(a, i - 1);
            for (int j = 1; j <= lb; j++) {
                char cb = words.charAt(b, j - 1);
                int cost = ca == cb ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && ca == words.charAt(b, j - 2) && words.charAt(a, i - 2) == cb) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
            }
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[lb];
    }

    /** The distance from {@code q} to word {@code b} (exact: the triangle inequality prunes with it). */
    private int distance(CharSequence q, int b) {
        int la = q.length();
        int lb = words.length(b);
        int[] prev2 = row0;
        int[] prev = row1;
        int[] cur = row2;
        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            char ca = q.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                char cb = words.charAt(b, j - 1);
                int cost = ca == cb ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && ca == words.charAt(b, j - 2) && q.charAt(i - 2) == cb) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
            }
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[lb];
    }

    /** The edit distance of two strings (optimal string alignment: a swap of neighbours is one edit). */
    @AllowAllocation("a helper for tests and ranking")
    public static int distance(CharSequence a, CharSequence b) {
        int la = a.length();
        int lb = b.length();
        int[][] d = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= lb; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[la][lb];
    }
}
