package com.truesim.ui.text.spell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Spell checking's costs ({@code TRUESIM_BENCHMARK=true}, JaCoCo off): checking prose (per word, the sample
 * dictionary), and suggestions over a dictionary of 150,000 forms (synthetic, the size of an expanded en_US): the
 * BK-tree's query at distance 2 against scanning every word, with the index's build time.
 */
class SpellBenchmarkTest {

    @Test
    void checkingAndSuggesting() {
        assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")), "benchmark");
        SpellChecker c = new SpellChecker(Dictionary.sampleEnglish());
        StringBuilder prose = new StringBuilder();
        String[] words = {"the", "quick", "brown", "fox", "jumped", "over", "a", "lazy", "dog's", "back", "and",
            "wrod", "Hello", "colour", "https://example.org", "3rd", "well-known", "doesn't"};
        Random random = new Random(1);
        int count = 200_000;
        for (int i = 0; i < count; i++) {
            prose.append(words[random.nextInt(words.length)]).append(i % 12 == 11 ? ".\n" : " ");
        }
        int[] errors = {0};
        SpellChecker.Misspelt sink = (s, e) -> errors[0]++;
        for (int i = 0; i < 5; i++) {
            c.check(prose, 0, prose.length(), sink);
        }
        long t0 = System.nanoTime();
        int runs = 10;
        for (int i = 0; i < runs; i++) {
            c.check(prose, 0, prose.length(), sink);
        }
        double nsPerWord = (System.nanoTime() - t0) / (double) runs / count;

        Dictionary big = Dictionary.ofWords("xx", synthetic(150_000, new Random(2)));
        long b0 = System.nanoTime();
        BkTree tree = new BkTree(big.words(), null);
        double buildMs = (System.nanoTime() - b0) / 1e6;
        List<String> queries = new ArrayList<>();
        Random q = new Random(3);
        for (int i = 0; i < 200; i++) {
            StringBuilder w = new StringBuilder(big.words().word(q.nextInt(big.size())));
            w.setCharAt(q.nextInt(w.length()), (char) ('a' + q.nextInt(26)));
            queries.add(w.toString());
        }
        int[] found = {0};
        for (String s : queries.subList(0, 20)) {
            tree.query(s, 2, (n, d) -> found[0]++);
        }
        double[] treeMs = new double[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            long s0 = System.nanoTime();
            tree.query(queries.get(i), 2, (n, d) -> found[0]++);
            treeMs[i] = (System.nanoTime() - s0) / 1e6;
        }
        double[] scanMs = new double[20];
        for (int i = 0; i < scanMs.length; i++) {
            long s0 = System.nanoTime();
            for (int w = 0; w < big.size(); w++) {
                if (BkTree.distance(queries.get(i), big.words().word(w)) <= 2) {
                    found[0]++;
                }
            }
            scanMs[i] = (System.nanoTime() - s0) / 1e6;
        }
        Arrays.sort(treeMs);
        Arrays.sort(scanMs);
        System.out.printf("Spelling: check %.0f ns a word (%d misspelt a run); 150,000 forms: BK-tree built in %.0f ms,"
            + " a distance-2 query %.2f ms median (%.2f p99), a full scan %.1f ms median%n", nsPerWord,
            errors[0] / (runs + 5), buildMs, treeMs[treeMs.length / 2], treeMs[(int) (treeMs.length * 0.99)],
            scanMs[scanMs.length / 2]);
        assertThat(found[0]).isPositive();
    }

    private static List<String> synthetic(int n, Random r) {
        List<String> out = new ArrayList<>(n);
        String letters = "etaoinshrdlucmfwypvbgkjqxz";
        for (int i = 0; i < n; i++) {
            int len = 3 + Math.min(12, (int) Math.abs(r.nextGaussian() * 3 + 5));
            StringBuilder b = new StringBuilder(len);
            for (int k = 0; k < len; k++) {
                b.append(letters.charAt(Math.min(25, (int) Math.abs(r.nextGaussian() * 7))));
            }
            out.add(b.toString());
        }
        return out;
    }
}
