package com.truesim.ui.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Font faces and backends made up in code: atlas and registry tests that need no installed font. */
final class Fakes {

    private Fakes() {
    }

    /** A temporary file standing in for a font file (its size is part of the cache key). */
    static FontSource source(Path dir, String name, String family, int weight, boolean italic) {
        try {
            Path file = dir.resolve(name);
            if (!Files.exists(file)) {
                Files.write(file, name.getBytes());
            }
            return FontSource.of(file, 0, family, weight, italic);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A face covering codepoints {@code from} to {@code to}; glyph {@code g}'s outline is a square of {@code g}
     * hundredths of an em (glyph 0 and 32 have none: missing and space).
     */
    static final class Face implements FontFace {
        final int id;
        final FontSource source;
        final int from;
        final int to;

        Face(int id, FontSource source, int from, int to) {
            this.id = id;
            this.source = source;
            this.from = from;
            this.to = to;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public FontSource source() {
            return source;
        }

        @Override
        public float ascender() {
            return 0.8f;
        }

        @Override
        public float descender() {
            return 0.2f;
        }

        @Override
        public float lineGap() {
            return 0.1f;
        }

        @Override
        public boolean covers(int codepoint) {
            return codepoint >= from && codepoint <= to;
        }

        @Override
        public boolean outline(int glyph, GlyphOutline out) {
            out.reset();
            if (glyph == 0 || glyph == 32) {
                return false;
            }
            float s = glyph / 100f;
            out.moveTo(0, 0);
            out.lineTo(s, 0);
            out.lineTo(s, s);
            out.lineTo(0, s);
            out.lineTo(0, 0);
            return true;
        }
    }

    /** One glyph per character, glyph id = codepoint, advance half the size; marks have no advance. */
    static final class Shaper implements TextShaper {
        @Override
        public void shape(char[] text, int length, int start, int end, FontFace face, float sizePx,
            boolean rightToLeft, String language, ShapedGlyphs out) {
            java.util.List<int[]> glyphs = new java.util.ArrayList<>();
            for (int i = start; i < end; ) {
                int cp = Character.codePointAt(text, i, end);
                glyphs.add(new int[] {cp, i});
                i += Character.charCount(cp);
            }
            if (rightToLeft) { // visual order
                java.util.Collections.reverse(glyphs);
            }
            for (int[] g : glyphs) {
                boolean mark = Character.getType(g[0]) == Character.NON_SPACING_MARK;
                out.add(g[0], g[1], mark ? 0 : sizePx / 2, 0, 0);
            }
        }
    }

    /** Faces covering ranges by family name: "Latin" 0-0x24F, "Hebrew" 0x590-0x5FF plus ASCII, "Symbols" all. */
    static final class Backend implements TextBackend {
        final AtomicInteger opened = new AtomicInteger();

        @Override
        public FontFace open(FontSource source, int id) throws IOException {
            opened.incrementAndGet();
            return switch (source.family()) {
                case "Latin" -> new Face(id, source, 0, 0x24F);
                case "Hebrew" -> new Face(id, source, 0x590, 0x5FF);
                case "Broken" -> throw new IOException("broken");
                default -> new Face(id, source, 0, 0x10FFFF);
            };
        }

        @Override
        public TextShaper newShaper() {
            return new Shaper();
        }

        @Override
        public MsdfGenerator msdfGenerator() {
            return new com.truesim.ui.text.jdk.JdkSdfGenerator();
        }

        @Override
        public GlyphRasterizer newRasterizer() {
            return new com.truesim.ui.text.jdk.JdkRasterizer();
        }
    }
}
