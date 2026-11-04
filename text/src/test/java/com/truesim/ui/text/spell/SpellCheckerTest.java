package com.truesim.ui.text.spell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The spell checker: affix rules, case, what is skipped, suggestions, the user dictionary, the index. */
class SpellCheckerTest {

    static final Dictionary SAMPLE = Dictionary.sampleEnglish();

    @AfterEach
    void settingsBack() {
        SpellChecker.IGNORE_URLS.select("on");
        SpellChecker.IGNORE_NUMBERS.select("on");
        SpellChecker.IGNORE_MIXED_CASE.select("on");
        SpellChecker.IGNORE_CAPITALS.select("off");
    }

    private static Dictionary dictionary(String aff, String dic) {
        return Dictionary.load("en", new ByteArrayInputStream(aff.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayInputStream(dic.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean right(SpellChecker c, String word) {
        return c.correct(word, 0, word.length());
    }

    private static List<String> misspelt(SpellChecker c, String text) {
        List<String> out = new ArrayList<>();
        c.check(text, 0, text.length(), (s, e) -> out.add(text.substring(s, e)));
        return out;
    }

    @Test
    void affixRulesMakeTheForms() {
        Dictionary d = dictionary("""
            SET UTF-8
            FLAG long
            NOSUGGEST !!
            FORBIDDENWORD Xx
            NEEDAFFIX Nn
            SFX Aa Y 2
            SFX Aa y ies [^aeiou]y
            SFX Aa 0 s [^y]
            SFX Bb Y 1
            SFX Bb 0 ing/Cc .
            SFX Cc Y 1
            SFX Cc 0 s .
            PFX Uu Y 1
            PFX Uu 0 un .
            """, """
            5
            fly/AaUu
            cat/AaUu
            jump/BbNn
            catz/Xx
            darn/!!
            """);
        assertThat(d.rules().groupCount()).isEqualTo(4);
        SpellChecker c = new SpellChecker(d);
        for (String w : new String[] {"fly", "flies", "unfly", "unflies", "cats", "uncats", "jumping", "jumpings",
            "darn"}) {
            assertThat(right(c, w)).as(w).isTrue();
        }
        for (String w : new String[] {"flys", "jump", "catz", "unjump", "catss"}) {
            assertThat(right(c, w)).as(w).isFalse();
        }
        assertThat(d.forbidden("catz", 0, 4)).isTrue();
        assertThat(c.suggest("darm")).as("no suggesting a NOSUGGEST word").doesNotContain("darn");
        assertThat(d.toString()).contains("en");
        Dictionary num = dictionary("FLAG num\nSFX 7 Y 1\nSFX 7 0 s .\n", "1\nbook/7,9\n");
        assertThat(num.contains("books", 0, 5)).isTrue();
        Dictionary plain = Dictionary.ofWords("de", List.of("Haus", "Maus", " "));
        assertThat(plain.size()).isEqualTo(2);
        assertThat(plain.locale()).isEqualTo("de");
    }

    @Test
    void theSampleChecksEnglishWithCaseAndSkipsWhatIsNotProse() {
        SpellChecker c = new SpellChecker(SAMPLE);
        assertThat(SAMPLE.size()).as("expanded forms").isGreaterThan(3000);
        assertThat(misspelt(c, "The quick brown fox jumped over the lazy dog's back.")).containsExactly("fox", "lazy");
        assertThat(misspelt(c, "Ths sentnce has errors, doesn't it? It's fine; we're done.")).containsExactly("Ths",
            "sentnce");
        assertThat(right(c, "Hello")).as("capitalised").isTrue();
        assertThat(right(c, "HELLO")).as("in capitals").isTrue();
        assertThat(right(c, "London")).isTrue();
        assertThat(right(c, "LONDON")).isTrue();
        assertThat(right(c, "london")).as("a name in lower case").isFalse();
        assertThat(right(c, "don’t")).as("a typographic apostrophe").isTrue();
        assertThat(right(c, "well-known")).as("each part right").isTrue();
        assertThat(right(c, "well-knwn")).isFalse();
        assertThat(misspelt(c, "see https://exmple.org/pth or www.exmple.com or me@exmple.org")).isEmpty();
        assertThat(misspelt(c, "item 3rd mp3 iPhone")).isEmpty();
        assertThat(misspelt(c, "a b c I")).as("single letters").isEmpty();
        SpellChecker.IGNORE_URLS.select("off");
        assertThat(misspelt(c, "see https://exmple.org")).contains("exmple");
        SpellChecker.IGNORE_NUMBERS.select("off");
        assertThat(misspelt(c, "the 3rd one")).containsExactly("3rd");
        SpellChecker.IGNORE_MIXED_CASE.select("off");
        assertThat(misspelt(c, "iPhone")).containsExactly("iPhone");
        SpellChecker.IGNORE_CAPITALS.select("on");
        assertThat(misspelt(c, "NASA")).isEmpty();
        assertThat(misspelt(c, "été naïve")).as("accented words are words").hasSize(2);
        assertThat(misspelt(c, "𝐚𝐛 ok")).as("letters beyond the BMP").hasSize(2);
    }

    @Test
    void suggestionsComeByEditDistanceTheReplacementTableAndSplitting() {
        SpellChecker c = new SpellChecker(SAMPLE);
        assertThat(c.suggest("wrod")).as("a swap is one edit").startsWith("word");
        assertThat(c.suggest("teh")).as("from the table").startsWith("the");
        assertThat(c.suggest("recieve")).contains("receive");
        assertThat(c.suggest("thecat")).contains("the cat");
        assertThat(c.suggest("Wrod")).as("in the word's case").startsWith("Word");
        assertThat(c.suggest("WROD")).startsWith("WORD");
        assertThat(c.suggest("mispelled")).isNotEmpty();
        assertThat(c.suggest("qqqqqqqqqqqqqq")).isEmpty();
        assertThat(c.suggest("gxrdxn")).as("two edits away: offered for words of 6 letters and more").contains("garden");
        assertThat(c.suggest("hxusx")).as("two edits away: not for shorter words, where they are mostly noise")
            .doesNotContain("house");
        assertThat(c.suggest("hxuse")).as("one edit away: offered whatever the length").contains("house");
        assertThat(c.suggest("hous").size()).isLessThanOrEqualTo(Math.round(SpellChecker.SUGGESTIONS.get()));
        SpellChecker.SUGGESTIONS.set(2);
        assertThat(c.suggest("hous")).hasSizeLessThanOrEqualTo(2);
        SpellChecker.SUGGESTIONS.set(6);
    }

    @Test
    void theUserDictionaryAndIgnoredWordsChangeTheVersion() {
        List<List<String>> saved = new ArrayList<>();
        SpellChecker c = new SpellChecker(SAMPLE).userWords(new SpellChecker.UserWords() {
            @Override
            public List<String> load() {
                return List.of("truesim");
            }

            @Override
            public void save(List<String> words) {
                saved.add(words);
            }
        });
        assertThat(right(c, "truesim")).isTrue();
        long v = c.version();
        assertThat(right(c, "glTF")).isTrue(); // mixed case: skipped
        assertThat(right(c, "vulkan")).isFalse();
        assertThat(c.addToDictionary("vulkan")).isTrue();
        assertThat(c.addToDictionary("vulkan")).isFalse();
        assertThat(c.version()).isGreaterThan(v);
        assertThat(right(c, "vulkan")).isTrue();
        assertThat(right(c, "Vulkan")).isTrue();
        assertThat(saved.get(saved.size() - 1)).containsExactly("truesim", "vulkan");
        assertThat(c.suggest("vulkam")).as("user words are suggested").contains("vulkan");
        assertThat(c.removeFromDictionary("vulkan")).isTrue();
        assertThat(c.removeFromDictionary("vulkan")).isFalse();
        assertThat(right(c, "vulkan")).isFalse();
        assertThat(c.userDictionary()).containsExactly("truesim");
        v = c.version();
        assertThat(c.ignore("slang")).isTrue();
        assertThat(c.ignore("slang")).isFalse();
        assertThat(right(c, "slang")).isTrue();
        assertThat(c.version()).isGreaterThan(v);
        v = c.version();
        SpellChecker.IGNORE_CAPITALS.select("on");
        assertThat(c.version()).as("settings count").isGreaterThan(v);
    }

    @Test
    void localesSwitchTheDictionary() {
        Dictionary de = Dictionary.ofWords("de", List.of("Haus", "und"));
        SpellChecker c = new SpellChecker(SAMPLE).add(de);
        assertThat(c.locale()).isEqualTo("en");
        long v = c.version();
        c.locale("de");
        assertThat(c.dictionary()).isSameAs(de);
        assertThat(c.version()).isGreaterThan(v);
        assertThat(right(c, "Haus")).isTrue();
        assertThat(right(c, "house")).isFalse();
        assertThatThrownBy(() -> c.locale("fr")).isInstanceOf(IllegalArgumentException.class);
        Dictionary de2 = Dictionary.ofWords("de", List.of("Maus"));
        c.add(de2);
        assertThat(c.dictionary()).as("the active locale's replaced").isSameAs(de2);
        assertThatThrownBy(() -> Dictionary.resource("en", "/nope.aff", "/nope.dic"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIndexFindsWhatABruteForceSearchFinds() {
        WordSet words = SAMPLE.words();
        BkTree tree = new BkTree(words, null);
        assertThat(tree.size()).isEqualTo(words.size());
        Random random = new Random(7);
        for (int q = 0; q < 40; q++) {
            String w = words.word(random.nextInt(words.size()));
            StringBuilder b = new StringBuilder(w);
            if (b.length() > 2) {
                b.setCharAt(random.nextInt(b.length()), (char) ('a' + random.nextInt(26)));
            }
            String query = b.toString();
            List<String> found = new ArrayList<>();
            tree.query(query, 2, (n, d) -> found.add(words.word(n)));
            List<String> brute = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                if (BkTree.distance(query, words.word(i)) <= 2) {
                    brute.add(words.word(i));
                }
            }
            assertThat(found).as(query).containsExactlyInAnyOrderElementsOf(brute);
        }
        assertThat(BkTree.distance("abcd", "acbd")).as("a swap").isEqualTo(1);
        assertThat(BkTree.distance("", "abc")).isEqualTo(3);
        assertThat(BkTree.distance("kitten", "sitting")).isEqualTo(3);
    }

    @Test
    void aWordSetLooksUpRangesWithoutStrings() {
        WordSet s = new WordSet();
        for (int i = 0; i < 1000; i++) {
            assertThat(s.add("w" + i)).isTrue();
        }
        assertThat(s.add("w5")).isFalse();
        assertThat(s.add("", 0, 0)).isFalse();
        assertThat(s.contains("xx w999 yy", 3, 7)).isTrue();
        assertThat(s.contains("w1000")).isFalse();
        assertThat(s.find("w7", 0, 2)).isEqualTo(7);
        assertThat(s.remove("w7")).isTrue();
        assertThat(s.contains("w7")).isFalse();
        assertThat(s.contains("w999")).isTrue();
        assertThat(s.size()).isEqualTo(999);
        assertThat(s.length(0)).isEqualTo(2);
        assertThat(s.charAt(0, 0)).isEqualTo('w');
        long v = s.version();
        s.clear();
        assertThat(s.size()).isZero();
        assertThat(s.contains("w1")).isFalse();
        assertThat(s.version()).isGreaterThan(v);
        assertThat(SpellChecker.url("a@b.c", 0, 5)).isTrue();
        assertThat(SpellChecker.url("@home.", 0, 6)).isFalse();
        assertThat(AffixRules.none().tryChars()).isEmpty();
        assertThat(SAMPLE.rules().tryChars()).startsWith("esianrt");
        assertThat(SAMPLE.rules().keyRows()).hasSize(3);
        assertThat(SAMPLE.rules().replacements()).isNotEmpty();
    }
}
