package com.truesim.ui.core.draw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class TexturesAndColorsTest {

    @Test
    void srgbConversionsMatchTheStandard() {
        for (int i = 0; i <= 255; i++) {
            float encoded = i / 255f;
            double expected = encoded <= 0.04045 ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4);
            assertThat((double) Rgba.toLinear(encoded)).isCloseTo(expected, within(1e-6));
            assertThat((double) Rgba.toSrgb((float) expected)).isCloseTo(encoded, within(1e-5));
        }
        assertThat(Rgba.toSrgb(0)).isZero();
        assertThat(Rgba.pow24(0)).isZero();
        assertThat(Rgba.root24(0)).isZero();
    }

    @Test
    void colorsPackIntoHalves() {
        long c = Rgba.pack(2.5f, 0.5f, 0.25f, 0.75f);
        assertThat(Rgba.r(c)).isEqualTo(2.5f);
        assertThat(Rgba.g(c)).isEqualTo(0.5f);
        assertThat(Rgba.b(c)).isEqualTo(0.25f);
        assertThat(Rgba.a(c)).isEqualTo(0.75f);
        assertThat(Rgba.a(Rgba.fade(c, 0.5f))).isEqualTo(0.375f);
        assertThat(Rgba.r(Rgba.srgb(255, 0, 0, 1))).isEqualTo(1);
        assertThat(Rgba.a(Rgba.TRANSPARENT)).isZero();
    }

    @Test
    void texturesTrackTheirDirtyRegion() {
        UiTextures textures = new UiTextures();
        UiTexture page = textures.create("atlas 0", 64, 32, UiTexture.Format.RGBA8_UNORM);
        assertThat(page.id()).isEqualTo(1);
        assertThat(textures.get(1)).isSameAs(page);
        assertThat(textures.count()).isEqualTo(2);
        assertThat(page.pixels()).hasSize(64 * 32 * 4);
        assertThat(page.dirty()).isTrue();
        assertThat(page.dirtyWidth()).isEqualTo(64);
        page.clean();
        assertThat(page.dirty()).isFalse();
        long v = page.version();
        page.markDirty(10, 4, 5, 5);
        page.markDirty(2, 8, 4, 100);
        assertThat(page.dirtyX()).isEqualTo(2);
        assertThat(page.dirtyY()).isEqualTo(4);
        assertThat(page.dirtyWidth()).isEqualTo(13);
        assertThat(page.dirtyHeight()).isEqualTo(28);
        assertThat(page.version()).isGreaterThan(v);
        page.clean();
        page.markDirty(100, 100, 4, 4); // outside
        assertThat(page.dirty()).isFalse();
        assertThat(page.name()).isEqualTo("atlas 0");
        assertThat(page.format()).isEqualTo(UiTexture.Format.RGBA8_UNORM);
        assertThat(page.width()).isEqualTo(64);
        assertThat(page.height()).isEqualTo(32);
        assertThat(page.toString()).contains("atlas 0");
        assertThatThrownBy(() -> textures.get(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> textures.get(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> textures.create("bad", 0, 1, UiTexture.Format.RGBA8_SRGB))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> textures.create("bad", 1, 0, UiTexture.Format.RGBA8_SRGB))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void primitiveHelpersPack() {
        assertThat(Primitive.unorm16(-1, 2)).isEqualTo(65535 << 16);
        int h = Primitive.header(Primitive.GLYPH, 3, 1000);
        assertThat(Primitive.kind(h)).isEqualTo(Primitive.GLYPH);
        assertThat(Primitive.flags(h)).isEqualTo(3);
        assertThat(Primitive.clip(h)).isEqualTo(1000);
        assertThat(Primitive.BYTES).isEqualTo(64);
    }
}
