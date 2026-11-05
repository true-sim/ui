package com.truesim.ui.spell.en;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.text.spell.Dictionary;
import com.truesim.ui.text.spell.SpellChecker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SCOWL's en_US and en_GB through ui-text's affix reader and checker: real words, real misspellings, both locales. */
class EnglishDictionariesTest {

    private static SpellChecker checker;
    private static long loadMs;

    @BeforeAll
    static void load() {
        long t0 = System.nanoTime();
        checker = EnglishDictionaries.checker();
        loadMs = (System.nanoTime() - t0) / 1_000_000;
    }

    private static boolean ok(String word) {
        return checker.correct(word, 0, word.length());
    }

    @Test
    void bothLoadExpandedByTheirAffixes() {
        Dictionary us = EnglishDictionaries.american();
        Dictionary gb = EnglishDictionaries.british();
        assertThat(us.locale()).isEqualTo(EnglishDictionaries.US);
        assertThat(gb.locale()).isEqualTo(EnglishDictionaries.GB);
        // about 48,000 entries, many with suffix and prefix flags: the expanded forms are several times that
        assertThat(us.size()).isGreaterThan(100_000);
        assertThat(gb.size()).isGreaterThan(100_000);
        System.out.printf("en_US %,d forms, en_GB %,d forms; both loaded in %d ms%n", us.size(), gb.size(), loadMs);
    }

    @Test
    void americanAcceptsRealWordsAndTheirForms() {
        checker.locale(EnglishDictionaries.US);
        for (String w : List.of("the", "color", "colors", "colored", "organize", "organization", "running",
            "unhappy", "happiness", "redo", "children's", "can't", "can’t", "doesn't", "Kyiv", "codebase",
            "tokenize", "London", "The", "THE", "well-known", "naive")) {
            assertThat(ok(w)).as(w).isTrue();
        }
    }

    @Test
    void americanFlagsMisspellingsAndBritishSpellings() {
        checker.locale(EnglishDictionaries.US);
        for (String w : List.of("teh", "recieve", "seperate", "wrod", "colour", "organise", "london")) {
            assertThat(ok(w)).as(w).isFalse();
        }
    }

    @Test
    void britishTakesItsOwnSpellings() {
        checker.locale(EnglishDictionaries.GB);
        for (String w : List.of("colour", "colours", "organise", "organisation", "centre", "travelled")) {
            assertThat(ok(w)).as(w).isTrue();
        }
        for (String w : List.of("color", "organize", "center", "recieve")) {
            assertThat(ok(w)).as(w).isFalse();
        }
        checker.locale(EnglishDictionaries.US);
    }

    @Test
    void suggestionsComeFromTheFullList() {
        checker.locale(EnglishDictionaries.US);
        assertThat(checker.suggest("recieve")).contains("receive");
        assertThat(checker.suggest("seperate")).contains("separate");
        assertThat(checker.suggest("wrod")).contains("word");
        assertThat(checker.suggest("Thier")).contains("Their");
        assertThat(checker.suggest("definately")).contains("definitely");
    }

    @Test
    void checkingProseFindsTheMisspeltWordsWithoutAllocating() {
        checker.locale(EnglishDictionaries.US);
        String prose = "The quick brown fox jumped over the lazy dog's back; it wasn't recieved well, "
            + "and the 21st attempt at https://example.org was definately seperate.";
        List<String> found = new ArrayList<>();
        checker.check(prose, 0, prose.length(), (s, e) -> found.add(prose.substring(s, e)));
        assertThat(found).containsExactly("recieved", "definately", "seperate");
        int[] count = {0};
        SpellChecker.Misspelt sink = (s, e) -> count[0]++;
        assertAllocationFree(() -> checker.check(prose, 0, prose.length(), sink));
    }

    @Test
    void theLicenceTravelsWithTheFiles() throws IOException {
        for (String name : List.of("README_en_US.txt", "README_en_GB-ise.txt", "CREDITS.md")) {
            try (InputStream in = EnglishDictionaries.class.getResourceAsStream("/com/truesim/ui/spell/en/" + name)) {
                assertThat(in).as(name).isNotNull();
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (name.startsWith("README")) {
                    assertThat(text).contains("Copyright 2000-2026 by Kevin Atkinson")
                        .contains("Permission to use, copy, modify, distribute, and sell")
                        .contains("Copyright 1993, Geoff Kuenning");
                }
            }
        }
    }
}
