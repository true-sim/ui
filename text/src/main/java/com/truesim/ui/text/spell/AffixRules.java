package com.truesim.ui.text.spell;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The affix rules of a dictionary: the subset of Hunspell's {@code .aff} format that spell checking without compounds
 * needs. Read: {@code SET} (the files' encoding), {@code FLAG} ({@code UTF-8}, {@code long}, {@code num}; one character
 * by default), {@code TRY} (the characters suggestions try first), {@code REP} (common misspellings: a replacement
 * table suggestions try), {@code KEY} (neighbouring keys), {@code NOSUGGEST}, {@code FORBIDDENWORD},
 * {@code NEEDAFFIX}, and {@code PFX}/{@code SFX} groups (strip, add, continuation flags for a second suffix, condition
 * with {@code .}, {@code [abc]} and {@code [^abc]}, cross product). Everything else (compounding, morphology,
 * conversions) is skipped, so a full Hunspell dictionary loads and checks its simple words.
 */
public final class AffixRules {

    /** One affix entry: strip what the condition matched, add the affix. */
    static final class Entry {
        final boolean prefix;
        final int flag;
        final boolean cross;
        final String strip;
        final String add;
        final int[] continuation;
        final Condition condition;

        Entry(boolean prefix, int flag, boolean cross, String strip, String add, int[] continuation,
            Condition condition) {
            this.prefix = prefix;
            this.flag = flag;
            this.cross = cross;
            this.strip = strip;
            this.add = add;
            this.continuation = continuation;
            this.condition = condition;
        }

        /** The form of {@code root} with this affix, or null if it does not apply. */
        @AllowAllocation("dictionaries are expanded when loaded")
        String apply(String root) {
            if (root.length() < strip.length() || root.length() == strip.length() && add.isEmpty()) {
                return null;
            }
            if (prefix) {
                if (!root.startsWith(strip) || !condition.matchesStart(root)) {
                    return null;
                }
                return add + root.substring(strip.length());
            }
            if (!root.endsWith(strip) || !condition.matchesEnd(root)) {
                return null;
            }
            return root.substring(0, root.length() - strip.length()) + add;
        }

        boolean has(int f) {
            for (int c : continuation) {
                if (c == f) {
                    return true;
                }
            }
            return false;
        }
    }

    /** An affix condition: a sequence of characters, {@code .} (any) and bracket sets. */
    static final class Condition {
        private final String[] sets; // null: any character
        private final boolean[] negated;

        @AllowAllocation("dictionaries are loaded once")
        Condition(String pattern) {
            List<String> s = new ArrayList<>();
            List<Boolean> neg = new ArrayList<>();
            int i = 0;
            while (i < pattern.length()) {
                char c = pattern.charAt(i);
                if (c == '.') {
                    s.add(null);
                    neg.add(false);
                    i++;
                } else if (c == '[') {
                    int end = pattern.indexOf(']', i);
                    if (end < 0) {
                        end = pattern.length();
                    }
                    boolean n = i + 1 < end && pattern.charAt(i + 1) == '^';
                    s.add(pattern.substring(i + (n ? 2 : 1), end));
                    neg.add(n);
                    i = end + 1;
                } else {
                    int cp = pattern.codePointAt(i);
                    s.add(new String(Character.toChars(cp)));
                    neg.add(false);
                    i += Character.charCount(cp);
                }
            }
            sets = s.toArray(new String[0]);
            negated = new boolean[neg.size()];
            for (int k = 0; k < negated.length; k++) {
                negated[k] = neg.get(k);
            }
        }

        private boolean matches(int position, char c) {
            String set = sets[position];
            if (set == null) {
                return true;
            }
            return set.indexOf(c) >= 0 != negated[position];
        }

        boolean matchesEnd(String word) {
            int n = sets.length;
            if (word.length() < n) {
                return false;
            }
            for (int k = 0; k < n; k++) {
                if (!matches(k, word.charAt(word.length() - n + k))) {
                    return false;
                }
            }
            return true;
        }

        boolean matchesStart(String word) {
            int n = sets.length;
            if (word.length() < n) {
                return false;
            }
            for (int k = 0; k < n; k++) {
                if (!matches(k, word.charAt(k))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** How flags are written. */
    enum FlagType {
        CHAR, LONG, NUM
    }

    private FlagType flagType = FlagType.CHAR;
    private final Map<Integer, List<Entry>> groups = new HashMap<>();
    private String tryChars = "";
    private final List<String[]> replacements = new ArrayList<>();
    private final List<String> keyRows = new ArrayList<>();
    private int noSuggest = -1;
    private int forbidden = -1;
    private int needAffix = -1;

    @AllowAllocation("dictionaries are loaded once")
    private AffixRules() {
    }

    /** Rules with no affixes (a plain word list). */
    @AllowAllocation("dictionaries are loaded once")
    public static AffixRules none() {
        return new AffixRules();
    }

    /** Parses the lines of an {@code .aff} file. */
    @AllowAllocation("dictionaries are loaded once")
    public static AffixRules parse(List<String> lines) {
        AffixRules r = new AffixRules();
        int i = 0;
        while (i < lines.size()) {
            String[] f = fields(lines.get(i));
            i++;
            if (f.length == 0) {
                continue;
            }
            switch (f[0]) {
                case "FLAG" -> r.flagType = f.length < 2 ? FlagType.CHAR : switch (f[1]) {
                    case "long" -> FlagType.LONG;
                    case "num" -> FlagType.NUM;
                    default -> FlagType.CHAR;
                };
                case "TRY" -> r.tryChars = f.length > 1 ? f[1] : "";
                case "KEY" -> {
                    if (f.length > 1) {
                        r.keyRows.addAll(List.of(f[1].split("\\|")));
                    }
                }
                case "NOSUGGEST" -> r.noSuggest = r.single(f);
                case "FORBIDDENWORD" -> r.forbidden = r.single(f);
                case "NEEDAFFIX" -> r.needAffix = r.single(f);
                case "REP" -> {
                    if (f.length >= 3) {
                        r.replacements.add(new String[] {f[1].replace('_', ' '), f[2].replace('_', ' ')});
                    }
                }
                case "PFX", "SFX" -> {
                    if (f.length >= 4 && isCount(f[3])) {
                        boolean prefix = f[0].equals("PFX");
                        int flag = r.single(f);
                        boolean cross = f[2].equals("Y");
                        int n = Integer.parseInt(f[3]);
                        List<Entry> group = r.groups.computeIfAbsent(flag, k -> new ArrayList<>());
                        for (int k = 0; k < n && i < lines.size(); k++, i++) {
                            String[] e = fields(lines.get(i));
                            if (e.length < 4 || !e[0].equals(f[0])) {
                                continue;
                            }
                            String strip = e[2].equals("0") ? "" : e[2];
                            String add = e[3];
                            int[] cont = new int[0];
                            int slash = add.indexOf('/');
                            if (slash >= 0) {
                                cont = r.flags(add.substring(slash + 1));
                                add = add.substring(0, slash);
                            }
                            if (add.equals("0")) {
                                add = "";
                            }
                            String cond = e.length > 4 ? e[4] : ".";
                            group.add(new Entry(prefix, flag, cross, strip, add, cont, new Condition(cond)));
                        }
                    }
                }
                default -> {
                    // not used here
                }
            }
        }
        return r;
    }

    private static boolean isCount(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    @AllowAllocation("dictionaries are loaded once")
    private static String[] fields(String line) {
        int hash = line.indexOf('#');
        String l = (hash == 0 ? "" : line).strip();
        return l.isEmpty() ? new String[0] : l.split("\\s+");
    }

    private int single(String[] f) {
        if (f.length < 2) {
            return -1;
        }
        int[] fl = flags(f[1]);
        return fl.length == 0 ? -1 : fl[0];
    }

    /** The flags written in {@code text} (after a dictionary word's slash). */
    @AllowAllocation("dictionaries are loaded once")
    int[] flags(String text) {
        switch (flagType) {
            case NUM -> {
                String[] parts = text.split(",");
                int[] out = new int[parts.length];
                int n = 0;
                for (String p : parts) {
                    if (isCount(p.strip())) {
                        out[n++] = Integer.parseInt(p.strip());
                    }
                }
                return java.util.Arrays.copyOf(out, n);
            }
            case LONG -> {
                int[] out = new int[text.length() / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = text.charAt(i * 2) << 16 | text.charAt(i * 2 + 1);
                }
                return out;
            }
            default -> {
                return text.codePoints().toArray();
            }
        }
    }

    /** The entries of flag {@code flag} (empty if none). */
    @AllowAllocation("dictionaries are expanded when loaded")
    List<Entry> group(int flag) {
        List<Entry> g = groups.get(flag);
        return g == null ? List.of() : g;
    }

    /** The characters suggestions try first ({@code TRY}). */
    public String tryChars() {
        return tryChars;
    }

    /** The replacement table ({@code REP}): pairs of from and to. */
    public List<String[]> replacements() {
        return replacements;
    }

    /** The keyboard rows ({@code KEY}). */
    public List<String> keyRows() {
        return keyRows;
    }

    int noSuggestFlag() {
        return noSuggest;
    }

    int forbiddenFlag() {
        return forbidden;
    }

    int needAffixFlag() {
        return needAffix;
    }

    /** How many affix groups were read. */
    public int groupCount() {
        return groups.size();
    }
}
