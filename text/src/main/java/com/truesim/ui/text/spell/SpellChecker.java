package com.truesim.ui.text.spell;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spell checking without external libraries: a {@link Dictionary} per locale (the active one checks), a user
 * dictionary of words added by the player ({@link #addToDictionary}, saved through a {@link UserWords} store), and words
 * ignored for the session ({@link #ignore}). It finds the misspelt words of a range of text ({@link #check}: allocates
 * nothing) and suggests corrections ({@link #suggest}: by edit distance, the replacement table and split words).
 *
 * <p><b>Words.</b> A word is a run of letters, combining marks and digits, with apostrophes ({@code '} and {@code ’})
 * and hyphens inside it; a hyphenated word is right when it is, or each part is. Settings skip what is not prose:
 * URLs and e-mail addresses ({@code ui.spell.ignoreUrls}), words with digits ({@code ui.spell.ignoreNumbers}), words
 * in mixed case such as {@code iPhone} ({@code ui.spell.ignoreMixedCase}) and words in capitals
 * ({@code ui.spell.ignoreCapitals}).
 *
 * <p><b>Case.</b> A word in the dictionary in lower case is right in lower case, capitalised (at a sentence's start)
 * and in capitals; a capitalised entry (a name) also in capitals, but not in lower case; anything else only as written.
 * Suggestions take the word's case.
 */
public final class SpellChecker {

    /** Skips URLs and e-mail addresses. */
    public static final Tunable IGNORE_URLS = Tunable.choice("ui.spell.ignoreUrls", List.of("on", "off"), "on",
        "Spell checking skips URLs and e-mail addresses");
    /** Skips words with digits. */
    public static final Tunable IGNORE_NUMBERS = Tunable.choice("ui.spell.ignoreNumbers", List.of("on", "off"), "on",
        "Spell checking skips words with digits (A4, 3rd, mp3)");
    /** Skips words in mixed case. */
    public static final Tunable IGNORE_MIXED_CASE = Tunable.choice("ui.spell.ignoreMixedCase", List.of("on", "off"),
        "on", "Spell checking skips words in mixed case (iPhone, McLaren, camelCase)");
    /** Skips words in capitals. */
    public static final Tunable IGNORE_CAPITALS = Tunable.choice("ui.spell.ignoreCapitals", List.of("on", "off"),
        "off", "Spell checking skips words in capitals (acronyms: NASA, GPU)");
    /** The most suggestions offered for a word. */
    public static final Tunable SUGGESTIONS = Tunable.of("ui.spell.suggestions", 6, 1, 20,
        "The most corrections a misspelt word's menu offers");

    /** Where the user dictionary is kept (the engine: a file in the player's profile). */
    public interface UserWords {
        /** The words saved before. */
        List<String> load();

        /** Saves {@code words} (all of them, after one was added or removed). */
        void save(List<String> words);
    }

    /** What {@link #check} hands each misspelt word. */
    @FunctionalInterface
    public interface Misspelt {
        /** The word {@code [start, end)} is misspelt. */
        void misspelt(int start, int end);
    }

    private final Map<String, Dictionary> dictionaries = new HashMap<>();
    private Dictionary active;
    private final WordSet user = new WordSet();
    private final WordSet ignored = new WordSet();
    private UserWords store;
    private long version;
    private long settingsSeen;
    private final StringBuilder scratch = new StringBuilder(64);

    /** A checker using {@code dictionary} (more can be added per locale). */
    @AllowAllocation("one per UI")
    public SpellChecker(Dictionary dictionary) {
        add(dictionary);
        active = dictionary;
    }

    /** The settings spell checking reads. */
    public static List<Tunable> tunables() {
        return List.of(IGNORE_URLS, IGNORE_NUMBERS, IGNORE_MIXED_CASE, IGNORE_CAPITALS, SUGGESTIONS);
    }

    /** Adds {@code dictionary} for its locale (replacing one of the same locale); returns this. */
    @AllowAllocation("dictionaries are added when loaded")
    public SpellChecker add(Dictionary dictionary) {
        dictionaries.put(dictionary.locale(), dictionary);
        if (active != null && active.locale().equals(dictionary.locale())) {
            active = dictionary;
            version++;
        }
        return this;
    }

    /** Checks in {@code locale} from now on (a dictionary of it must have been added); returns this. */
    public SpellChecker locale(String locale) {
        Dictionary d = dictionaries.get(locale);
        if (d == null) {
            throw new IllegalArgumentException("no dictionary for " + locale);
        }
        if (d != active) {
            active = d;
            version++;
        }
        return this;
    }

    /** The locale checked. */
    public String locale() {
        return active.locale();
    }

    /** The active dictionary. */
    public Dictionary dictionary() {
        return active;
    }

    /** Keeps the user dictionary in {@code value}: loads its words now and saves on every change; returns this. */
    @AllowAllocation("set up once")
    public SpellChecker userWords(UserWords value) {
        store = value;
        if (value != null) {
            for (String w : value.load()) {
                user.add(w);
            }
            version++;
        }
        return this;
    }

    /**
     * A number that changes whenever a word's verdict may have: a dictionary, the locale, the user dictionary, the
     * ignored words or the settings changed; editors recheck everything then.
     */
    public long version() {
        long settings = IGNORE_URLS.version() + IGNORE_NUMBERS.version() + IGNORE_MIXED_CASE.version()
            + IGNORE_CAPITALS.version();
        if (settings != settingsSeen) {
            settingsSeen = settings;
            version++;
        }
        return version;
    }

    /** Adds {@code word} to the user dictionary (saved through the store); true if it was not there. */
    @AllowAllocation("a user action")
    public boolean addToDictionary(CharSequence word) {
        if (!user.add(word)) {
            return false;
        }
        version++;
        save();
        return true;
    }

    /** Removes {@code word} from the user dictionary; true if it was there. */
    @AllowAllocation("a user action")
    public boolean removeFromDictionary(CharSequence word) {
        if (!user.remove(word)) {
            return false;
        }
        version++;
        save();
        return true;
    }

    /** The user dictionary's words. */
    @AllowAllocation("read on user action")
    public List<String> userDictionary() {
        List<String> out = new ArrayList<>(user.size());
        for (int i = 0; i < user.size(); i++) {
            out.add(user.word(i));
        }
        return out;
    }

    private void save() {
        if (store != null) {
            store.save(userDictionary());
        }
    }

    /** Ignores {@code word} everywhere for this session ("Ignore all"); true if it was not ignored already. */
    @AllowAllocation("a user action")
    public boolean ignore(CharSequence word) {
        if (!ignored.add(word)) {
            return false;
        }
        version++;
        return true;
    }

    // ---------------------------------------------------------------- checking

    /**
     * Hands every misspelt word of {@code [from, to)} of {@code text} to {@code out}; allocates nothing. Words start and
     * end at the range's ends (a paragraph).
     */
    public void check(CharSequence text, int from, int to, Misspelt out) {
        boolean urls = IGNORE_URLS.index() == 0;
        int i = from;
        while (i < to) {
            // a chunk between white space: a URL or an address is skipped whole
            while (i < to && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            int chunkEnd = i;
            while (chunkEnd < to && !Character.isWhitespace(text.charAt(chunkEnd))) {
                chunkEnd++;
            }
            if (urls && url(text, i, chunkEnd)) {
                i = chunkEnd;
                continue;
            }
            while (i < chunkEnd) {
                int start = i;
                while (start < chunkEnd && !wordChar(text, start)) {
                    start++;
                }
                int end = start;
                while (end < chunkEnd && (wordChar(text, end)
                    || joiner(text.charAt(end)) && end + 1 < chunkEnd && wordChar(text, end + 1) && end > start)) {
                    end++;
                }
                if (end > start && !correct(text, start, end)) {
                    out.misspelt(start, end);
                }
                i = end == start ? chunkEnd : end;
            }
        }
    }

    /** Whether the word {@code [from, to)} of {@code text} is right (or skipped by the settings). */
    public boolean correct(CharSequence text, int from, int to) {
        if (to - from <= 1) {
            return true; // single letters: "a", "I", initials
        }
        int letters = 0;
        int upper = 0;
        boolean digits = false;
        for (int i = from; i < to; ) {
            int cp = Character.codePointAt(text, i);
            if (Character.isDigit(cp)) {
                digits = true;
            } else if (Character.isLetter(cp)) {
                letters++;
                if (Character.isUpperCase(cp)) {
                    upper++;
                }
            }
            i += Character.charCount(cp);
        }
        if (digits && IGNORE_NUMBERS.index() == 0 || letters == 0) {
            return true;
        }
        boolean capitals = upper == letters && letters > 1;
        boolean capitalised = upper == 1 && Character.isUpperCase(firstLetter(text, from, to));
        if (capitals && IGNORE_CAPITALS.index() == 0) {
            return true;
        }
        if (upper > 0 && !capitals && !capitalised && IGNORE_MIXED_CASE.index() == 0) {
            return true;
        }
        if (known(text, from, to)) {
            return true;
        }
        if (upper > 0) {
            // capitalised: its lower-case entry; in capitals: its lower-case or capitalised entry
            caseForm(text, from, to, false);
            if (known(scratch, 0, scratch.length())) {
                return true;
            }
            if (capitals) {
                caseForm(text, from, to, true);
                if (known(scratch, 0, scratch.length())) {
                    return true;
                }
            }
        }
        return hyphenated(text, from, to);
    }

    /** A hyphenated word whose parts are each right. */
    private boolean hyphenated(CharSequence text, int from, int to) {
        int start = from;
        boolean any = false;
        for (int i = from; i <= to; i++) {
            if (i == to || text.charAt(i) == '-') {
                if (i == to && !any) {
                    return false;
                }
                if (i < to) {
                    any = true;
                }
                if (i > start && !correct(text, start, i)) {
                    return false;
                }
                start = i + 1;
            }
        }
        return any;
    }

    private boolean known(CharSequence text, int from, int to) {
        if (active.forbidden(text, from, to)) {
            return false;
        }
        if (active.contains(text, from, to) || user.contains(text, from, to) || ignored.contains(text, from, to)) {
            return true;
        }
        // apostrophes: the typographic one checks as the straight one
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '’') {
                scratch.setLength(0);
                for (int k = from; k < to; k++) {
                    char c = text.charAt(k);
                    scratch.append(c == '’' ? '\'' : c);
                }
                return active.contains(scratch, 0, scratch.length()) || user.contains(scratch, 0, scratch.length());
            }
        }
        return false;
    }

    /** The word in lower case into the scratch buffer, its first letter capitalised if {@code capitalise}. */
    private void caseForm(CharSequence text, int from, int to, boolean capitalise) {
        scratch.setLength(0);
        boolean first = true;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && first) {
                scratch.append(capitalise ? Character.toUpperCase(c) : Character.toLowerCase(c));
                first = false;
            } else {
                scratch.append(Character.toLowerCase(c));
            }
        }
    }

    private static char firstLetter(CharSequence text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.isLetter(text.charAt(i))) {
                return text.charAt(i);
            }
        }
        return ' ';
    }

    private static boolean wordChar(CharSequence text, int i) {
        char c = text.charAt(i);
        if (Character.isLetterOrDigit(c)) {
            return true;
        }
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK) {
            return true;
        }
        if (Character.isSurrogate(c)) {
            int cp = Character.codePointAt(text, Character.isLowSurrogate(c) && i > 0 ? i - 1 : i);
            return Character.isLetterOrDigit(cp);
        }
        return false;
    }

    private static boolean joiner(char c) {
        return c == '\'' || c == '’' || c == '-';
    }

    /** Whether the chunk is a URL ({@code scheme://}, {@code www.}) or an e-mail address. */
    static boolean url(CharSequence text, int from, int to) {
        boolean at = false;
        boolean dotAfterAt = false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == ':' && i + 2 < to && text.charAt(i + 1) == '/' && text.charAt(i + 2) == '/') {
                return true;
            }
            if (c == '@' && i > from) {
                at = true;
            } else if (c == '.' && at && i + 1 < to) {
                dotAfterAt = true;
            }
        }
        if (to - from > 4 && regionIs(text, from, "www.")) {
            return true;
        }
        return at && dotAfterAt;
    }

    private static boolean regionIs(CharSequence text, int from, String prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.toLowerCase(text.charAt(from + i)) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- suggestions

    /**
     * Corrections of {@code word}, best first, at most {@code ui.spell.suggestions}: replacements from the dictionary's
     * table, words one edit away (then two), and the word split in two; in the word's case.
     */
    @AllowAllocation("suggestions are asked for on user action")
    public List<String> suggest(CharSequence word) {
        String w = word.toString();
        int max = Math.round(SUGGESTIONS.get());
        boolean capitals = w.length() > 1 && w.equals(w.toUpperCase(java.util.Locale.ROOT))
            && !w.equals(w.toLowerCase(java.util.Locale.ROOT));
        boolean capitalised = !capitals && !w.isEmpty() && Character.isUpperCase(w.charAt(0));
        String query = capitals || capitalised ? active.lower(w) : w;
        Set<String> out = new LinkedHashSet<>();
        // the replacement table: common misspellings
        for (String[] rep : active.rules().replacements()) {
            int at = query.indexOf(rep[0]);
            while (at >= 0) {
                String candidate = query.substring(0, at) + rep[1] + query.substring(at + rep[0].length());
                if (candidate.indexOf(' ') < 0 ? isSuggestable(candidate) : allRight(candidate)) {
                    out.add(candidate);
                }
                at = query.indexOf(rep[0], at + 1);
            }
        }
        // edit distance: one, then two edits
        List<String> near = new ArrayList<>();
        Map<String, Integer> found = new HashMap<>();
        active.index().query(query, 2, (number, distance) -> found.merge(active.words().word(number), distance,
            Math::min));
        for (int i = 0; i < user.size(); i++) {
            String u = user.word(i);
            int d = BkTree.distance(query, u);
            if (d <= 2) {
                found.merge(u, d, Math::min);
            }
        }
        if (!query.equals(w)) {
            active.index().query(w, 2, (number, distance) -> found.merge(active.words().word(number), distance,
                Math::min));
        }
        near.addAll(found.keySet());
        char first = query.isEmpty() ? ' ' : query.charAt(0);
        near.sort(Comparator.<String>comparingInt(found::get)
            .thenComparingInt(s -> sameLetters(s, query) ? 0 : 1) // a swap of neighbours: the commonest slip
            .thenComparingInt(s -> s.isEmpty() || Character.toLowerCase(s.charAt(0)) != first ? 1 : 0)
            .thenComparingInt(s -> Math.abs(s.length() - query.length()))
            .thenComparing(Comparator.naturalOrder()));
        for (String s : near) {
            if (found.get(s) == 1 && active.suggestable(s)) {
                out.add(s);
            }
        }
        // split in two: "thecat" to "the cat"
        for (int i = 1; i < query.length(); i++) {
            String a = query.substring(0, i);
            String b = query.substring(i);
            if (a.length() > 1 && b.length() > 1 && isSuggestable(a) && isSuggestable(b)) {
                out.add(a + " " + b);
            }
        }
        for (String s : near) {
            if (found.get(s) == 2 && active.suggestable(s)) {
                out.add(s);
            }
        }
        List<String> result = new ArrayList<>();
        for (String s : out) {
            if (result.size() == max) {
                break;
            }
            String cased = capitals ? s.toUpperCase(java.util.Locale.ROOT)
                : capitalised && !s.isEmpty() ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
            if (!cased.equals(w) && !result.contains(cased)) {
                result.add(cased);
            }
        }
        return result;
    }

    private static boolean sameLetters(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        char[] x = a.toCharArray();
        char[] y = b.toCharArray();
        java.util.Arrays.sort(x);
        java.util.Arrays.sort(y);
        return java.util.Arrays.equals(x, y);
    }

    private boolean isSuggestable(String s) {
        return (active.contains(s, 0, s.length()) || user.contains(s)) && active.suggestable(s);
    }

    @AllowAllocation("suggestions are asked for on user action")
    private boolean allRight(String s) {
        for (String part : s.split(" ")) {
            if (!isSuggestable(part)) {
                return false;
            }
        }
        return true;
    }
}
