package com.truesim.ui.text.spell;

import com.truesim.annotations.AllowAllocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of one language: a word list with affix rules (Hunspell's {@code .dic} and {@code .aff} formats, the subset
 * {@link AffixRules} reads), expanded when loaded into every form the rules make, so checking a word is one hash
 * lookup ({@link #contains}) that allocates nothing. Suggestions search a {@link BkTree} of the forms, built on the
 * first request.
 *
 * <p>The data is a replaceable resource: {@link #load} takes any {@code .aff} and {@code .dic} (a full Hunspell
 * dictionary such as SCOWL's en_US loads; its compounding and morphology are ignored), {@link #ofWords} a plain list.
 * The toolkit ships a small self-written English sample ({@link #sampleEnglish()}).
 */
public final class Dictionary {

    private final String locale;
    private final AffixRules rules;
    private final WordSet words = new WordSet();
    private final WordSet forbidden = new WordSet();
    private final WordSet noSuggest = new WordSet();
    private BkTree index;

    @AllowAllocation("dictionaries are loaded once")
    private Dictionary(String locale, AffixRules rules) {
        this.locale = locale;
        this.rules = rules;
    }

    /** A dictionary of {@code locale} (a BCP 47 tag) from an {@code .aff} and a {@code .dic} stream. */
    @AllowAllocation("dictionaries are loaded once")
    public static Dictionary load(String locale, InputStream aff, InputStream dic) {
        byte[] affBytes = read(aff);
        byte[] dicBytes = read(dic);
        Charset charset = charset(affBytes);
        AffixRules rules = AffixRules.parse(new String(affBytes, charset).lines().toList());
        Dictionary d = new Dictionary(locale, rules);
        List<String> lines = new String(dicBytes, charset).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || i == 0 && isNumber(line) || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab >= 0) {
                line = line.substring(0, tab); // morphological fields
            }
            int slash = slash(line);
            String word = slash < 0 ? line : line.substring(0, slash);
            int[] flags = slash < 0 ? new int[0] : rules.flags(line.substring(slash + 1).split("\\s")[0]);
            d.expand(word.replace("\\/", "/"), flags);
        }
        return d;
    }

    /** A dictionary of {@code locale} from a plain list of words (no affixes). */
    @AllowAllocation("dictionaries are loaded once")
    public static Dictionary ofWords(String locale, Iterable<String> list) {
        Dictionary d = new Dictionary(locale, AffixRules.none());
        for (String w : list) {
            if (!w.isBlank()) {
                d.words.add(w.strip());
            }
        }
        return d;
    }

    /**
     * The toolkit's sample English dictionary ({@code en-sample}): about two thousand common words with affix rules,
     * self-written for this project (no third-party word list); replace it with a full one for real use.
     */
    @AllowAllocation("dictionaries are loaded once")
    public static Dictionary sampleEnglish() {
        return resource("en", "/com/truesim/ui/text/spell/en-sample.aff", "/com/truesim/ui/text/spell/en-sample.dic");
    }

    /** A dictionary of {@code locale} from class-path resources. */
    @AllowAllocation("dictionaries are loaded once")
    public static Dictionary resource(String locale, String affPath, String dicPath) {
        try (InputStream aff = Dictionary.class.getResourceAsStream(affPath);
            InputStream dic = Dictionary.class.getResourceAsStream(dicPath)) {
            if (aff == null || dic == null) {
                throw new IllegalArgumentException("no dictionary at " + affPath + " and " + dicPath);
            }
            return load(locale, aff, dic);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AllowAllocation("dictionaries are loaded once")
    private static byte[] read(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AllowAllocation("dictionaries are loaded once")
    private static Charset charset(byte[] aff) {
        for (String line : new String(aff, StandardCharsets.ISO_8859_1).lines().toList()) {
            String l = line.strip();
            if (l.startsWith("SET ")) {
                String name = l.substring(4).strip();
                try {
                    return Charset.forName(name.equalsIgnoreCase("microsoft-cp1251") ? "windows-1251" : name);
                } catch (IllegalArgumentException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isNumber(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The flags' slash, not an escaped one ({@code \/}). */
    private static int slash(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '/' && (i == 0 || line.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }

    private static boolean has(int[] flags, int flag) {
        if (flag < 0) {
            return false;
        }
        for (int f : flags) {
            if (f == flag) {
                return true;
            }
        }
        return false;
    }

    /** Adds {@code root} and the forms its flags make: suffixes, prefixes, their cross products, second suffixes. */
    @AllowAllocation("dictionaries are expanded when loaded")
    private void expand(String root, int[] flags) {
        if (has(flags, rules.forbiddenFlag())) {
            forbidden.add(root);
            return;
        }
        boolean hidden = has(flags, rules.noSuggestFlag());
        if (!has(flags, rules.needAffixFlag())) {
            add(root, hidden);
        }
        List<String> crossSuffixed = new ArrayList<>();
        for (int f : flags) {
            for (AffixRules.Entry e : rules.group(f)) {
                if (e.prefix) {
                    continue;
                }
                String form = e.apply(root);
                if (form == null) {
                    continue;
                }
                add(form, hidden);
                if (e.cross) {
                    crossSuffixed.add(form);
                }
                for (int c : e.continuation) { // a second suffix on the first
                    for (AffixRules.Entry e2 : rules.group(c)) {
                        if (!e2.prefix) {
                            String form2 = e2.apply(form);
                            if (form2 != null) {
                                add(form2, hidden);
                            }
                        }
                    }
                }
            }
        }
        for (int f : flags) {
            for (AffixRules.Entry e : rules.group(f)) {
                if (!e.prefix) {
                    continue;
                }
                String form = e.apply(root);
                if (form != null) {
                    add(form, hidden);
                }
                if (e.cross) {
                    for (String s : crossSuffixed) {
                        String both = e.apply(s);
                        if (both != null) {
                            add(both, hidden);
                        }
                    }
                }
            }
        }
    }

    @AllowAllocation("dictionaries are expanded when loaded")
    private void add(String form, boolean hidden) {
        words.add(form);
        if (hidden) {
            noSuggest.add(form);
        }
    }

    /** The locale (a BCP 47 tag). */
    public String locale() {
        return locale;
    }

    /** The affix rules. */
    public AffixRules rules() {
        return rules;
    }

    /** How many word forms it holds (after expansion). */
    public int size() {
        return words.size();
    }

    /** The word forms. */
    public WordSet words() {
        return words;
    }

    /** Whether {@code [from, to)} of {@code text} is a form of the dictionary, exactly (case and all). */
    public boolean contains(CharSequence text, int from, int to) {
        return words.contains(text, from, to) && !forbidden.contains(text, from, to);
    }

    /** Whether the range is a forbidden word (wrong even if rules would make it). */
    public boolean forbidden(CharSequence text, int from, int to) {
        return forbidden.contains(text, from, to);
    }

    /** Whether {@code word} may be suggested. */
    boolean suggestable(CharSequence word) {
        return !noSuggest.contains(word) && !forbidden.contains(word);
    }

    /** The suggestion index, built on first use. */
    @AllowAllocation("built on the first suggestion")
    BkTree index() {
        if (index == null) {
            index = new BkTree(words, w -> noSuggest.contains(words.word(w)));
        }
        return index;
    }

    /** Builds the suggestion index now (when loading, so the first suggestion does not wait for it). */
    @AllowAllocation("built once per dictionary")
    public Dictionary prepareSuggestions() {
        index();
        return this;
    }

    @Override
    public String toString() {
        return "Dictionary " + locale + " (" + words.size() + " forms)";
    }

    /** {@code text} lower-cased in the dictionary's locale. */
    @AllowAllocation("suggestions are asked for on user action")
    String lower(String text) {
        return text.toLowerCase(Locale.forLanguageTag(locale));
    }
}
