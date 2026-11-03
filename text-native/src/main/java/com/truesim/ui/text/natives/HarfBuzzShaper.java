package com.truesim.ui.text.natives;

import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_DIRECTION_LTR;
import static org.lwjgl.util.harfbuzz.HarfBuzz.HB_DIRECTION_RTL;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_clear_contents;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_get_length;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_guess_segment_properties;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_set_direction;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_buffer_set_language;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_feature_from_string;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_language_from_string;
import static org.lwjgl.util.harfbuzz.HarfBuzz.nhb_buffer_add_utf16;
import static org.lwjgl.util.harfbuzz.HarfBuzz.nhb_buffer_get_glyph_infos;
import static org.lwjgl.util.harfbuzz.HarfBuzz.nhb_buffer_get_glyph_positions;
import static org.lwjgl.util.harfbuzz.HarfBuzz.nhb_shape;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.ShapedGlyphs;
import com.truesim.ui.text.TextShaper;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.harfbuzz.hb_feature_t;
import org.lwjgl.util.harfbuzz.hb_glyph_info_t;
import org.lwjgl.util.harfbuzz.hb_glyph_position_t;

/**
 * Shaping by HarfBuzz (UI9): one {@code hb_buffer} reused for every run. The run's characters go to HarfBuzz with up to
 * {@link #CONTEXT} characters of context on each side (Arabic joining across a font change), direction from the BiDi
 * level, script guessed from the run (runs are one script), language if given; the font's default features (kerning,
 * ligatures, contextual forms, marks) plus any given at construction. Shaping happens when text changes, not per
 * frame; it allocates nothing once its native text buffer has grown.
 */
public final class HarfBuzzShaper implements TextShaper, AutoCloseable {

    /** Characters of context passed on each side of a run: more than HarfBuzz's own limit of five. */
    static final int CONTEXT = 8;

    private final long buffer = hb_buffer_create();
    private final hb_feature_t.Buffer features;
    private long text;
    private int textCapacity;
    private String language;
    private long hbLanguage;
    private boolean closed;

    /** A shaper with the fonts' default features. */
    @AllowAllocation("a shaper per thread, once")
    public HarfBuzzShaper() {
        this.features = null;
        this.text = MemoryUtil.nmemAllocChecked(2L * 256);
        this.textCapacity = 256;
    }

    /**
     * A shaper with {@code features} on top of the defaults, in HarfBuzz's syntax: {@code "kern=0"} turns kerning
     * off, {@code "liga=0"} ligatures, {@code "tnum"} turns tabular figures on.
     */
    @AllowAllocation("a shaper per thread, once")
    public HarfBuzzShaper(String... features) {
        this.features = hb_feature_t.calloc(features.length);
        for (int i = 0; i < features.length; i++) {
            if (!hb_feature_from_string(features[i], this.features.get(i))) {
                throw new IllegalArgumentException("not a HarfBuzz feature: " + features[i]);
            }
        }
        this.text = MemoryUtil.nmemAllocChecked(2L * 256);
        this.textCapacity = 256;
    }

    @Override
    public void shape(char[] chars, int length, int start, int end, FontFace face, float sizePx,
        boolean rightToLeft, String language, ShapedGlyphs out) {
        NativeFontFace f = (NativeFontFace) face;
        int from = Math.max(0, start - CONTEXT);
        int to = Math.min(length, end + CONTEXT);
        ensure(to - from);
        for (int i = from; i < to; i++) {
            MemoryUtil.memPutShort(text + 2L * (i - from), (short) chars[i]);
        }
        hb_buffer_clear_contents(buffer);
        nhb_buffer_add_utf16(buffer, text, to - from, start - from, end - start);
        hb_buffer_set_direction(buffer, rightToLeft ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
        if (language != null) {
            hb_buffer_set_language(buffer, language(language));
        }
        hb_buffer_guess_segment_properties(buffer); // the script, from the run's characters
        nhb_shape(f.hbFont(), buffer, features == null ? 0 : features.address(),
            features == null ? 0 : features.remaining());
        int n = hb_buffer_get_length(buffer);
        long infos = nhb_buffer_get_glyph_infos(buffer, 0);
        long positions = nhb_buffer_get_glyph_positions(buffer, 0);
        float scale = sizePx / f.unitsPerEm();
        for (int i = 0; i < n; i++) {
            long info = infos + (long) i * hb_glyph_info_t.SIZEOF;
            long pos = positions + (long) i * hb_glyph_position_t.SIZEOF;
            out.add(MemoryUtil.memGetInt(info + hb_glyph_info_t.CODEPOINT),
                from + MemoryUtil.memGetInt(info + hb_glyph_info_t.CLUSTER),
                MemoryUtil.memGetInt(pos + hb_glyph_position_t.X_ADVANCE) * scale,
                MemoryUtil.memGetInt(pos + hb_glyph_position_t.X_OFFSET) * scale,
                MemoryUtil.memGetInt(pos + hb_glyph_position_t.Y_OFFSET) * scale);
        }
    }

    private void ensure(int chars) {
        if (chars > textCapacity) {
            grow(chars);
        }
    }

    @AllowAllocation("growing, rarely")
    private void grow(int chars) {
        textCapacity = Math.max(chars, textCapacity * 2);
        text = MemoryUtil.nmemReallocChecked(text, 2L * textCapacity);
    }

    @AllowAllocation("a language tag is looked up when it changes")
    private long language(String tag) {
        if (!tag.equals(language)) {
            hbLanguage = hb_language_from_string(tag);
            language = tag;
        }
        return hbLanguage;
    }

    /** Frees the buffer. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            hb_buffer_destroy(buffer);
            MemoryUtil.nmemFree(text);
            if (features != null) {
                features.free();
            }
        }
    }
}
