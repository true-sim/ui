package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The toolkit's own icons: every one draws, they are found by name only, and a game's image replaces one. */
class BuiltinIconsTest {

    private static int coverage(byte[] rgba) {
        int sum = 0;
        for (int i = 3; i < rgba.length; i += 4) {
            sum += rgba[i] & 0xFF;
        }
        return sum;
    }

    @Test
    void everyIconDrawsSomethingInsideItsSquare() {
        for (int i = 0; i < BuiltinIcons.count(); i++) {
            byte[] rgba = BuiltinIcons.draw(i);
            assertThat(rgba).hasSize(BuiltinIcons.SIZE * BuiltinIcons.SIZE * 4);
            int ink = coverage(rgba);
            assertThat(ink).as(BuiltinIcons.name(i)).isPositive();
            assertThat(ink).as(BuiltinIcons.name(i) + " is a shape, not a fill")
                .isLessThan(BuiltinIcons.SIZE * BuiltinIcons.SIZE * 255 / 2);
            for (int x = 0; x < BuiltinIcons.SIZE; x++) { // a texel of margin on every side
                assertThat(rgba[x * 4 + 3]).as(BuiltinIcons.name(i) + " top row").isZero();
                assertThat(rgba[(x * BuiltinIcons.SIZE) * 4 + 3]).as(BuiltinIcons.name(i) + " left column").isZero();
            }
        }
    }

    @Test
    void filledArrowsAreFilledAndPointTheirWay() {
        byte[] down = BuiltinIcons.draw(BuiltinIcons.find("icons/down"));
        int s = BuiltinIcons.SIZE;
        assertThat(down[((s / 2 - 4) * s + s / 2) * 4 + 3] & 0xFF).as("inside").isEqualTo(255);
        byte[] next = BuiltinIcons.draw(BuiltinIcons.find("icons/next"));
        int left = 0;
        int right = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s / 2; x++) {
                left += next[(y * s + x) * 4 + 3] & 0xFF;
                right += next[(y * s + s - 1 - x) * 4 + 3] & 0xFF;
            }
        }
        assertThat(left).as("a right-pointing arrow is wide on the left").isGreaterThan(right);
        assertThat(BuiltinIcons.distance("unknown", 8, 8)).isEqualTo(Float.MAX_VALUE);
    }

    @Test
    void iconsAreFoundByTheirFullNameOnly() {
        assertThat(BuiltinIcons.find("icons/close")).isNotNegative();
        assertThat(BuiltinIcons.find("icons/clos")).isNegative();
        assertThat(BuiltinIcons.find("icons/closer")).isNegative();
        assertThat(BuiltinIcons.find("close")).isNegative();
        assertThat(BuiltinIcons.find(null)).isNegative();
        assertThat(BuiltinIcons.name(BuiltinIcons.find("icons/eye-off"))).isEqualTo("eye-off");
    }

    @Test
    void imagesFallBackToThemOnceAndAGamesImageWins() {
        UiImages images = new UiImages(new UiTextures());
        UiTexture close = images.get("icons/close");
        assertThat(close).isNotNull();
        assertThat(close.width()).isEqualTo(BuiltinIcons.SIZE);
        assertThat(images.get("icons/close")).as("rendered once").isSameAs(close);
        assertThat(images.get("icons/unknown")).isNull();
        assertThat(images.get(null)).isNull();
        UiTexture own = images.add("icons/more", new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        assertThat(images.get("icons/more")).isSameAs(own);
    }
}
