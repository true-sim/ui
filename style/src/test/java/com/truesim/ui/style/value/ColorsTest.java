package com.truesim.ui.style.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.core.draw.Rgba;
import org.junit.jupiter.api.Test;

class ColorsTest {

    @Test
    void colorsParseHexAndNamesAndPackLinear() {
        assertThat(Color.hex("#ff0000")).isEqualTo(Color.rgb(255, 0, 0));
        assertThat(Color.hex("f00")).isEqualTo(Color.rgb(255, 0, 0));
        assertThat(Color.hex("#f008")).isEqualTo(Color.rgba(255, 0, 0, 0x88 / 255f));
        assertThat(Color.hex("#20303a80").a()).isEqualTo(128 / 255f);
        assertThat(Color.named("red")).isEqualTo(Color.rgb(255, 0, 0));
        assertThat(Color.named("transparent")).isEqualTo(Color.TRANSPARENT);
        assertThat(Color.named("chartreuse")).isNull();
        assertThatThrownBy(() -> Color.hex("#12345")).hasMessageContaining("not a hex colour");
        assertThatThrownBy(() -> Color.hex("#xyz")).hasMessageContaining("not a hex colour");
        Color mid = Color.rgb(128, 128, 128);
        assertThat(Rgba.r(mid.rgba())).isCloseTo(Rgba.toLinear(128 / 255f), within(1e-3f));
        assertThat(Rgba.a(Color.srgb(2, 0, -1, 2).rgba())).isEqualTo(1f);
        assertThat(Rgba.r(Color.srgb(2, 0, -1, 2).rgba())).isEqualTo(2f); // HDR above paper white stays linear
        assertThat(Rgba.b(Color.srgb(2, 0, -1, 2).rgba())).isZero();
        Color linear = Color.linear(0.5f, 2f, -1f, -1f);
        assertThat(linear.r()).isCloseTo(Rgba.toSrgb(0.5f), within(1e-6f));
        assertThat(linear.g()).isEqualTo(2f);
        assertThat(linear.b()).isZero();
        assertThat(linear.a()).isZero();
        assertThat(Color.WHITE.withAlpha(0.5f).a()).isEqualTo(0.5f);
    }

    @Test
    void colorsHaveValueEqualityAndReadableText() {
        assertThat(Color.rgb(1, 2, 3)).isEqualTo(Color.rgb(1, 2, 3)).hasSameHashCodeAs(Color.rgb(1, 2, 3));
        assertThat(Color.rgb(1, 2, 3)).isNotEqualTo(Color.rgb(1, 2, 4)).isNotEqualTo(Color.rgb(1, 3, 3))
            .isNotEqualTo(Color.rgb(2, 2, 3)).isNotEqualTo(Color.rgba(1, 2, 3, 0.5f)).isNotEqualTo("x");
        assertThat(Color.rgb(255, 0, 16).toString()).isEqualTo("#ff0010");
        assertThat(Color.rgba(255, 0, 16, 128 / 255f).toString()).isEqualTo("#ff001080");
        assertThat(Color.srgb(0.123f, 0, 0, 1).toString()).startsWith("rgba(");
        assertThat(Color.srgb(2, 0, 0, 1).toString()).startsWith("rgba(");
    }

    @Test
    void hslRoundTripsAndAdjusts() {
        Color c = Color.rgb(32, 48, 58);
        double[] hsl = Colors.toHsl(c);
        Color back = Colors.hsl(hsl[0], hsl[1], hsl[2], 1);
        assertThat(back.r()).isCloseTo(c.r(), within(1e-5f));
        assertThat(back.g()).isCloseTo(c.g(), within(1e-5f));
        assertThat(back.b()).isCloseTo(c.b(), within(1e-5f));
        for (Color x : new Color[] {Color.rgb(255, 0, 0), Color.rgb(0, 255, 0), Color.rgb(0, 0, 255),
            Color.rgb(200, 100, 50), Color.rgb(50, 200, 100), Color.rgb(100, 50, 200), Color.rgb(250, 240, 230)}) {
            double[] h = Colors.toHsl(x);
            Color y = Colors.hsl(h[0], h[1], h[2], 1);
            assertThat(y.r()).isCloseTo(x.r(), within(1e-5f));
            assertThat(y.g()).isCloseTo(x.g(), within(1e-5f));
            assertThat(y.b()).isCloseTo(x.b(), within(1e-5f));
        }
        assertThat(Colors.toHsl(Color.rgb(128, 128, 128))[1]).isZero();
        assertThat(Colors.hsl(-120, 1, 0.5, 1)).isEqualTo(Colors.hsl(240, 1, 0.5, 1));
        assertThat(Colors.toHsl(Colors.lighten(c, 0.1))[2]).isCloseTo(hsl[2] + 0.1, within(1e-5));
        assertThat(Colors.toHsl(Colors.darken(c, 0.1))[2]).isCloseTo(hsl[2] - 0.1, within(1e-5));
        assertThat(Colors.lighten(c, 2)).isEqualTo(Color.WHITE);
        assertThat(Colors.toHsl(Colors.saturate(c, 0.2))[1]).isCloseTo(hsl[1] + 0.2, within(1e-5));
        assertThat(Colors.toHsl(Colors.desaturate(c, 1))[1]).isZero();
    }

    @Test
    void mixFollowsSass() {
        Color red = Color.rgb(255, 0, 0);
        Color blue = Color.rgb(0, 0, 255);
        Color half = Colors.mix(red, blue, 0.5);
        assertThat(half.r()).isEqualTo(0.5f);
        assertThat(half.b()).isEqualTo(0.5f);
        assertThat(Colors.mix(red, blue, 1)).isEqualTo(red);
        Color clear = blue.withAlpha(0);
        Color mixed = Colors.mix(red, clear, 0.5);
        assertThat(mixed.r()).isEqualTo(1f); // a transparent colour does not pull the colour
        assertThat(mixed.a()).isEqualTo(0.5f);
        assertThat(Colors.mix(red.withAlpha(0), blue, 1).a()).isZero();
        assertThat(Colors.alpha(red, 0.3).a()).isEqualTo(0.3f);
    }

    @Test
    void contrastPicksTheMoreReadableColour() {
        assertThat(Colors.contrast(Color.rgb(250, 250, 250))).isEqualTo(Color.BLACK);
        assertThat(Colors.contrast(Color.rgb(20, 20, 30))).isEqualTo(Color.WHITE);
        assertThat(Colors.contrastRatio(Color.BLACK, Color.WHITE)).isCloseTo(21, within(1e-3));
        assertThat(Colors.luminance(Color.srgb(2, 2, 2, 1))).isCloseTo(2, within(1e-6));
    }

    @Test
    void oklchMatchesReferenceColours() {
        // OKLCH(62.8% 0.2577 29.23) is sRGB red; (0, 0, 0) black; (1, 0, 0) white
        Color red = Colors.oklch(0.62796, 0.25768, 29.2339, 1);
        assertThat(red.r()).isCloseTo(1f, within(2e-3f));
        assertThat(red.g()).isCloseTo(0f, within(2e-3f));
        assertThat(red.b()).isCloseTo(0f, within(2e-3f));
        assertThat(Colors.oklch(1, 0, 0, 1).g()).isCloseTo(1f, within(1e-3f));
        assertThat(Colors.oklch(0, 0, 0, 0.5)).isEqualTo(Color.srgb(0, 0, 0, 0.5f));
    }

    @Test
    void valueRecordsHaveFactoriesAndText() {
        assertThat(Length.px(3)).hasToString("3.0px");
        assertThat(Length.em(1)).hasToString("1.0em");
        assertThat(Length.percent(50)).hasToString("50.0%");
        assertThat(Length.AUTO).hasToString("auto");
        assertThat(Length.AUTO.isAuto()).isTrue();
        assertThat(Length.ZERO.isAuto()).isFalse();
        assertThat(Insets.of(1)).isEqualTo(new Insets(1, 1, 1, 1));
        assertThat(Insets.of(1, 2)).isEqualTo(new Insets(1, 2, 1, 2));
        assertThat(Insets.of(1, 2, 3, 4)).isEqualTo(new Insets(1, 2, 3, 4));
        assertThat(Radii.of(2)).isEqualTo(new Radii(2, 2, 2, 2));
        assertThat(Radii.of(1, 2, 3, 4)).isEqualTo(new Radii(1, 2, 3, 4));
        assertThat(Border.of(1, Color.WHITE)).isEqualTo(new Border(1, Color.WHITE));
        assertThat(Shadow.of(0, 2, 6, Color.BLACK)).isEqualTo(new Shadow(0, 2, 6, 0, Color.BLACK));
        assertThat(Transition.of("opacity", 0.1f, Easing.LINEAR).delay()).isZero();
        assertThat(Paint.NONE).hasToString("none");
        assertThat(Track.AUTO).hasToString("auto");
        assertThat(Track.minmax(Track.px(1), Track.fr(1))).isEqualTo(new Track.MinMax(new Track.Fixed(1),
            new Track.Fraction(1)));
        assertThat(Paint.nineSlice("a", 1, 2, 3, 4)).isEqualTo(new Paint.NineSlice("a", 1, 2, 3, 4, null,
            Color.WHITE));
        assertThat(Paint.image("a", Color.WHITE)).isEqualTo(new Paint.Image("a", Color.WHITE));
        assertThatThrownBy(() -> new Border(1, null)).isInstanceOf(NullPointerException.class);
    }
}
