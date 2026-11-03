package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a font face comes from: a font file (TrueType, OpenType, a collection with its face {@code index}) and the
 * family, weight and style the registry files it under. Plain data: a {@link TextBackend} opens it into a
 * {@link FontFace}.
 *
 * <p>{@link #key()} names the file's content for the {@link GlyphCache}: file name, face index and size, so a cache
 * baked at build time is found again at run time on another machine with the same font file.
 */
public final class FontSource {

    private final Path path;
    private final int index;
    private final String family;
    private final int weight;
    private final boolean italic;
    private final String key;

    private FontSource(Path path, int index, String family, int weight, boolean italic, String key) {
        this.path = path;
        this.index = index;
        this.family = family;
        this.weight = weight;
        this.italic = italic;
        this.key = key;
    }

    /**
     * Face {@code index} of the font file at {@code path} (0 unless it is a collection), filed as {@code family} with
     * {@code weight} (100 to 900, 400 regular, 700 bold) and {@code italic}.
     */
    @AllowAllocation("fonts are registered when loaded")
    public static FontSource of(Path path, int index, String family, int weight, boolean italic) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(family, "family");
        if (index < 0) {
            throw new IllegalArgumentException("face index " + index);
        }
        if (weight < 1 || weight > 1000) {
            throw new IllegalArgumentException("weight " + weight + " is not in 1 to 1000");
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new FontSource(path, index, family, weight, italic, path.getFileName() + "#" + index + ":" + size);
    }

    /** The font file. */
    public Path path() {
        return path;
    }

    /** The face's index in its file: 0 unless the file is a collection ({@code .ttc}). */
    public int index() {
        return index;
    }

    /** The family it is filed under. */
    public String family() {
        return family;
    }

    /** 100 to 900: 400 regular, 700 bold. */
    public int weight() {
        return weight;
    }

    /** Whether it is the italic (or oblique) style. */
    public boolean italic() {
        return italic;
    }

    /** Names the face's content: file name, index and file size; the glyph cache's key. */
    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return family + " " + weight + (italic ? " italic" : "") + " (" + key + ")";
    }
}
