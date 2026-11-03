package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Generated MTSDF glyphs by font content and glyph id, in memory and on disk: the one store that joins the build-time
 * atlas and the run-time one. The build-time baker fills a cache and saves it; at run time the {@link GlyphAtlas}
 * takes glyphs from it instead of generating them, and adds the ones it generates (save it again to keep them). A
 * cache holds glyphs of one em size and pixel range.
 *
 * <p>The file is deterministic: glyphs in the order they were added, numbers big-endian.
 */
@AllowAllocation("used once per new glyph and when loading or saving, never per frame")
public final class GlyphCache {

    private static final int MAGIC = 0x54534743; // "TSGC"
    private static final int VERSION = 1;

    /**
     * A cached glyph: where its outline lies in its bitmap and the MTSDF bytes ({@link MsdfGenerator#generate}); no
     * geometry and no bytes for a glyph without ink.
     *
     * @param geometry its bitmap's geometry, or null if it has no ink
     * @param pixels the bitmap, or null
     */
    public record Glyph(MsdfGeometry geometry, byte[] pixels) {
    }

    private record Key(String face, int glyph) {
    }

    private final float emSize;
    private final float pixelRange;
    private final Map<Key, Glyph> glyphs = new LinkedHashMap<>();
    private boolean dirty;

    /** An empty cache for glyphs of {@code emSize} texels per em and a range of {@code pixelRange} texels. */
    public GlyphCache(float emSize, float pixelRange) {
        this.emSize = emSize;
        this.pixelRange = pixelRange;
    }

    /** Whether it holds glyphs made with these values. */
    public boolean matches(float emSize, float pixelRange) {
        return this.emSize == emSize && this.pixelRange == pixelRange;
    }

    /** Texels per em of its glyphs. */
    public float emSize() {
        return emSize;
    }

    /** The distance range of its glyphs, texels. */
    public float pixelRange() {
        return pixelRange;
    }

    /** Glyph {@code glyph} of the face whose {@link FontSource#key()} is {@code face}, or null. */
    public Glyph get(String face, int glyph) {
        return glyphs.get(new Key(face, glyph));
    }

    /** Adds a glyph (null geometry and pixels for one without ink). */
    public void put(String face, int glyph, MsdfGeometry geometry, byte[] pixels) {
        if ((geometry == null) != (pixels == null)
            || geometry != null && pixels.length != geometry.width() * geometry.height() * 4) {
            throw new IllegalArgumentException("pixels do not match the geometry");
        }
        if (glyphs.putIfAbsent(new Key(Objects.requireNonNull(face, "face"), glyph), new Glyph(geometry, pixels))
            == null) {
            dirty = true;
        }
    }

    /** Glyphs held. */
    public int size() {
        return glyphs.size();
    }

    /** Whether glyphs were added since it was loaded or saved. */
    public boolean dirty() {
        return dirty;
    }

    /** Writes it to {@code file}. */
    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeFloat(emSize);
            out.writeFloat(pixelRange);
            out.writeInt(glyphs.size());
            for (Map.Entry<Key, Glyph> e : glyphs.entrySet()) {
                out.writeUTF(e.getKey().face());
                out.writeInt(e.getKey().glyph());
                MsdfGeometry g = e.getValue().geometry();
                out.writeBoolean(g != null);
                if (g != null) {
                    out.writeInt(g.width());
                    out.writeInt(g.height());
                    out.writeDouble(g.scale());
                    out.writeDouble(g.translateX());
                    out.writeDouble(g.translateY());
                    out.writeDouble(g.range());
                    out.write(e.getValue().pixels());
                }
            }
        }
        dirty = false;
    }

    /** Reads a cache {@link #save saved} to {@code file}. */
    public static GlyphCache load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException(file + " is not a glyph cache of version " + VERSION);
            }
            GlyphCache cache = new GlyphCache(in.readFloat(), in.readFloat());
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String face = in.readUTF();
                int glyph = in.readInt();
                if (in.readBoolean()) {
                    MsdfGeometry g = new MsdfGeometry(in.readInt(), in.readInt(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble());
                    byte[] pixels = new byte[g.width() * g.height() * 4];
                    in.readFully(pixels);
                    cache.put(face, glyph, g, pixels);
                } else {
                    cache.put(face, glyph, null, null);
                }
            }
            cache.dirty = false;
            return cache;
        }
    }
}
