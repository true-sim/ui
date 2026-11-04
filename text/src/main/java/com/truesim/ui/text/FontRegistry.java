package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The fonts a UI may use, by family name, and the fallback rules that pick a face for every character:
 * <ol>
 * <li>the families a text asks for, in order (a family may be an {@link #alias}: {@code sans-serif} names a list);</li>
 * <li>the {@link #fallback(UnicodeScript, String...) fallback families of the character's script};</li>
 * <li>the {@link #lastResort(String...) last-resort families} (symbols, emoji, CJK);</li>
 * </ol>
 * Characters shown as emoji (emoji presentation by default or asked for with U+FE0F, keycaps, flags, skin tones)
 * try the {@link #emoji(String...) emoji families} first, so a text font that also has a plain glyph for them (a
 * digit of a keycap, a heart) does not take them.
 * the first face that covers the character wins, matched by weight and style. Faces open on first use, so registering
 * every system font costs a directory scan, not a font load. Used from the UI thread.
 */
@AllowAllocation("registration and chain resolution happen when fonts load or a text's font changes, not per frame")
public final class FontRegistry {

    private static final UnicodeScript[] SCRIPTS = UnicodeScript.values();

    private final TextBackend backend;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, String[]> aliases = new HashMap<>();
    private final Map<UnicodeScript, String[]> scriptFallbacks = new EnumMap<>(UnicodeScript.class);
    private String[] lastResort = new String[0];
    private String[] emoji = new String[0];
    private final Map<ChainKey, FontChain> chains = new HashMap<>();
    private long version;

    private static final class Entry {
        final FontSource source;
        FontFace face;
        boolean failed;

        Entry(FontSource source) {
            this.source = source;
        }
    }

    /** A chain's arguments; one probe is reused for lookups, so a hit allocates nothing whatever the JIT inlines. */
    private static final class ChainKey {
        List<String> families;
        int weight;
        boolean italic;

        ChainKey set(List<String> newFamilies, int newWeight, boolean newItalic) {
            families = newFamilies;
            weight = newWeight;
            italic = newItalic;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ChainKey k && weight == k.weight && italic == k.italic && families.equals(k.families);
        }

        @Override
        public int hashCode() {
            return (families.hashCode() * 31 + weight) * 2 + (italic ? 1 : 0);
        }
    }

    private final ChainKey probe = new ChainKey();

    /** An empty registry whose faces {@code backend} opens. */
    public FontRegistry(TextBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * A registry of the fonts installed on this machine that {@link SystemFonts} knows (Windows, common Linux and
     * macOS families), with the generic aliases ({@code sans-serif}, {@code serif}, {@code monospace}) and script
     * fallbacks for Arabic, Hebrew, Devanagari, Thai, CJK and emoji. Nothing is bundled: a missing family is skipped.
     */
    public static FontRegistry system(TextBackend backend) {
        FontRegistry registry = new FontRegistry(backend);
        for (FontSource source : SystemFonts.discover()) {
            registry.register(source);
        }
        SystemFonts.defaults(registry);
        return registry;
    }

    /** The backend that opens the faces. */
    public TextBackend backend() {
        return backend;
    }

    /** Adds a face (opened on first use); returns its {@link FontFace#id()}. */
    public int register(FontSource source) {
        entries.add(new Entry(Objects.requireNonNull(source, "source")));
        changed();
        return entries.size() - 1;
    }

    /** Makes {@code name} (case-insensitive) stand for {@code families}, tried in order. */
    public void alias(String name, String... families) {
        aliases.put(name.toLowerCase(Locale.ROOT), families.clone());
        changed();
    }

    /** The families tried for characters of {@code script} that the requested families do not cover. */
    public void fallback(UnicodeScript script, String... families) {
        scriptFallbacks.put(script, families.clone());
        changed();
    }

    /** The families tried first for characters shown as emoji (a colour emoji font, then installed ones). */
    public void emoji(String... families) {
        emoji = families.clone();
        changed();
    }

    /** The families tried last, for any character nothing else covers. */
    public void lastResort(String... families) {
        lastResort = families.clone();
        changed();
    }

    private void changed() {
        chains.clear();
        version++;
    }

    /** Grows when faces, aliases or fallbacks change: text resolved against an older version resolves again. */
    public long version() {
        return version;
    }

    /** Registered faces; ids are below this. */
    public int size() {
        return entries.size();
    }

    /** The source of face {@code id}. */
    public FontSource source(int id) {
        return entries.get(id).source;
    }

    /** Face {@code id}, opened now if it was not yet; null if it fails to open. */
    public FontFace face(int id) {
        Entry e = entries.get(id);
        if (e.face == null && !e.failed) {
            try {
                e.face = backend.open(e.source, id);
            } catch (IOException | RuntimeException ex) {
                e.failed = true; // a broken font file is skipped, not fatal
            }
        }
        return e.face;
    }

    /** The registered family names, each once, in registration order. */
    public List<String> families() {
        Set<String> names = new LinkedHashSet<>();
        for (Entry e : entries) {
            names.add(e.source.family());
        }
        return List.copyOf(names);
    }

    /**
     * The face of {@code family} (an alias: its first family that is installed) closest to {@code weight} and
     * {@code italic}; null if the family is not installed.
     */
    public FontFace match(String family, int weight, boolean italic) {
        String[] alias = aliases.get(family.toLowerCase(Locale.ROOT));
        if (alias != null) {
            for (String name : alias) {
                FontFace face = matchFamily(name, weight, italic);
                if (face != null) {
                    return face;
                }
            }
            return null;
        }
        return matchFamily(family, weight, italic);
    }

    private FontFace matchFamily(String family, int weight, boolean italic) {
        // the CSS idea, simplified: style first, then the nearest weight (ties to the heavier)
        FontFace best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int id = 0; id < entries.size(); id++) {
            FontSource s = entries.get(id).source;
            if (!s.family().equalsIgnoreCase(family)) {
                continue;
            }
            int score = Math.abs(s.weight() - weight) * 2 + (s.weight() < weight ? 1 : 0)
                + (s.italic() != italic ? 10_000 : 0);
            if (score < bestScore) {
                FontFace face = face(id);
                if (face != null) {
                    best = face;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /**
     * The faces for text that asks for {@code families} (first choice first) at {@code weight} and {@code italic},
     * with this registry's fallbacks behind them. Cached: the same arguments return the same chain until the registry
     * changes. {@code families} is kept, so pass an immutable list.
     */
    public FontChain chain(List<String> families, int weight, boolean italic) {
        FontChain chain = chains.get(probe.set(families, weight, italic));
        if (chain == null) {
            List<FontFace> faces = new ArrayList<>();
            for (String family : families) {
                addMatch(faces, family, weight, italic);
            }
            chain = new FontChain(this, faces.toArray(new FontFace[0]), weight, italic);
            chains.put(new ChainKey().set(families, weight, italic), chain);
        }
        return chain;
    }

    private void addMatch(List<FontFace> faces, String family, int weight, boolean italic) {
        String[] alias = aliases.get(family.toLowerCase(Locale.ROOT));
        if (alias != null) {
            for (String name : alias) {
                addOnce(faces, matchFamily(name, weight, italic));
            }
        } else {
            addOnce(faces, matchFamily(family, weight, italic));
        }
    }

    private static void addOnce(List<FontFace> faces, FontFace face) {
        if (face != null && !faces.contains(face)) {
            faces.add(face);
        }
    }

    /** The fallback faces for {@code script} at {@code weight} and {@code italic}. */
    FontFace[] scriptFaces(UnicodeScript script, int weight, boolean italic) {
        return resolve(scriptFallbacks.get(script), weight, italic);
    }

    /** The emoji faces at {@code weight} and {@code italic}. */
    FontFace[] emojiFaces(int weight, boolean italic) {
        return resolve(emoji, weight, italic);
    }

    /** The last-resort faces at {@code weight} and {@code italic}. */
    FontFace[] lastResortFaces(int weight, boolean italic) {
        return resolve(lastResort, weight, italic);
    }

    private FontFace[] resolve(String[] families, int weight, boolean italic) {
        if (families == null) {
            return new FontFace[0];
        }
        List<FontFace> faces = new ArrayList<>();
        for (String family : families) {
            addMatch(faces, family, weight, italic);
        }
        return faces.toArray(new FontFace[0]);
    }

    /** How many scripts there are: {@link FontChain} sizes its per-script cache by it. */
    static int scriptCount() {
        return SCRIPTS.length;
    }
}
