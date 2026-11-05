package com.truesim.ui.spell.en;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.spell.Dictionary;
import com.truesim.ui.text.spell.SpellChecker;

/**
 * The optional English spelling dictionaries: SCOWL's Hunspell dictionaries (wordlist.aspell.net, release 2026.02.25,
 * SCOWL size 60, one spelling variant per word), unmodified, under the SCOWL licence and Geoff Kuenning's BSD licence
 * for the affix files (the {@code README_*.txt} files beside them; {@code CREDITS.md}). About 48,000 entries each,
 * expanded by their affix rules when loaded.
 *
 * <pre>{@code
 * SpellChecker spelling = EnglishDictionaries.checker(); // en-US, with en-GB to switch to
 * spelling.locale("en-GB");
 * }</pre>
 *
 * <p>Loading one takes some tens of milliseconds and a few megabytes: load it at start-up or off the UI thread, never
 * per frame. Checking allocates nothing; the suggestion index is built on the first suggestion (or by
 * {@link Dictionary#prepareSuggestions()}).
 */
public final class EnglishDictionaries {

    /** American English's locale tag. */
    public static final String US = "en-US";
    /** British English's locale tag (-ise spellings: "organise", "colour"). */
    public static final String GB = "en-GB";

    private static final String ROOT = "/com/truesim/ui/spell/en/";

    private EnglishDictionaries() {
    }

    /** American English ({@code en_US}). */
    @AllowAllocation("dictionaries are loaded once")
    public static Dictionary american() {
        return Dictionary.resource(US, ROOT + "en_US.aff", ROOT + "en_US.dic");
    }

    /** British English with -ise spellings ({@code en_GB-ise}). */
    @AllowAllocation("dictionaries are loaded once")
    public static Dictionary british() {
        return Dictionary.resource(GB, ROOT + "en_GB-ise.aff", ROOT + "en_GB-ise.dic");
    }

    /** A checker holding both, American selected. */
    @AllowAllocation("dictionaries are loaded once")
    public static SpellChecker checker() {
        return new SpellChecker(american()).add(british());
    }
}
