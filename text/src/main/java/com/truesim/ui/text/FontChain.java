package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.lang.Character.UnicodeScript;

/**
 * The faces a text tries for each character, from {@link FontRegistry#chain}: its requested families, then its
 * script's fallbacks, then the last resort; the first face covering the character wins. Script fallbacks are resolved
 * once per script and kept.
 */
public final class FontChain {

    private final FontRegistry registry;
    private final FontFace[] faces;
    private final int weight;
    private final boolean italic;
    private final FontFace[][] byScript = new FontFace[FontRegistry.scriptCount()][];
    private FontFace[] lastResort;

    @AllowAllocation("one chain per distinct font request")
    FontChain(FontRegistry registry, FontFace[] faces, int weight, boolean italic) {
        this.registry = registry;
        this.faces = faces;
        this.weight = weight;
        this.italic = italic;
    }

    /**
     * The face whose metrics the text's lines use: the first requested family that is installed, else the first
     * last-resort face. Null only if the registry has no usable font at all.
     */
    public FontFace primary() {
        if (faces.length > 0) {
            return faces[0];
        }
        FontFace[] last = lastResort();
        return last.length > 0 ? last[0] : null;
    }

    /** The requested faces that are installed, in order. */
    public int size() {
        return faces.length;
    }

    /** Requested face {@code i}. */
    public FontFace face(int i) {
        return faces[i];
    }

    /** The face for {@code codepoint} of {@code script}: the first covering it; the primary face if none does. */
    public FontFace faceFor(int codepoint, UnicodeScript script) {
        for (FontFace face : faces) {
            if (face.covers(codepoint)) {
                return face;
            }
        }
        FontFace[] scriptFaces = byScript[script.ordinal()];
        if (scriptFaces == null) {
            scriptFaces = registry.scriptFaces(script, weight, italic);
            byScript[script.ordinal()] = scriptFaces;
        }
        for (FontFace face : scriptFaces) {
            if (face.covers(codepoint)) {
                return face;
            }
        }
        for (FontFace face : lastResort()) {
            if (face.covers(codepoint)) {
                return face;
            }
        }
        return primary(); // its missing glyph shows that nothing covers the character
    }

    private FontFace[] lastResort() {
        if (lastResort == null) {
            lastResort = registry.lastResortFaces(weight, italic);
        }
        return lastResort;
    }
}
