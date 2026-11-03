package com.truesim.ui.core.draw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.testing.AllocationAssertions;
import org.junit.jupiter.api.Test;

class DrawListTest {

    @Test
    void aBoxIsPackedAsTheShaderReadsIt() {
        DrawList list = new DrawList();
        int start = list.allocate(2);
        long fill = Rgba.pack(1, 0.5f, 0.25f, 1);
        long border = Rgba.pack(0, 0, 1, 0.5f);
        list.begin(start, 2);
        list.box(10, 20, 110, 70, fill, 1, 2, 3, 4, 1.5f, border);
        assertThat(list.end()).isTrue();

        int h = list.header(start);
        assertThat(Primitive.kind(h)).isEqualTo(Primitive.BOX);
        assertThat(Primitive.flags(h)).isZero();
        assertThat(Primitive.clip(h)).isEqualTo(DrawList.NO_CLIP);
        assertThat(list.floatAt(start, Primitive.X0)).isEqualTo(10);
        assertThat(list.floatAt(start, Primitive.Y1)).isEqualTo(70);
        assertThat(list.colorAt(start, Primitive.COLOR_A)).isEqualTo(fill);
        assertThat(list.colorAt(start, Primitive.BORDER_COLOR)).isEqualTo(border);
        assertThat(list.floatAt(start, Primitive.WIDTH)).isEqualTo(1.5f);
        int radii = list.intAt(start, Primitive.RADII);
        assertThat(Float.float16ToFloat((short) radii)).isEqualTo(1);
        assertThat(Float.float16ToFloat((short) (radii >>> 16))).isEqualTo(2);
        assertThat(Primitive.kind(list.header(start + 1))).isEqualTo(Primitive.EMPTY);
    }

    @Test
    void everyKindRecords() {
        DrawList list = new DrawList();
        int start = list.allocate(9);
        int clip = list.addClip(0, 0, 50, 50, 4);
        list.begin(start, 9);
        list.clip(clip);
        list.gradientBox(0, 0, 10, 10, Rgba.WHITE, Rgba.BLACK, 1.5f, 1, 1, 1, 1, 0, 0);
        list.shadow(0, 0, 10, 10, Rgba.BLACK, 2, 2, 2, 2, 3);
        list.glyph(0, 0, 8, 8, 0.25f, 0.5f, 0.75f, 1, 7, 2, Rgba.WHITE, 1, Rgba.BLACK, 0.1f);
        list.image(0, 0, 8, 8, 0, 0, 1, 1, 3, Rgba.WHITE);
        list.nineSlice(0, 0, 80, 40, 0, 0, 1, 1, 4, Rgba.WHITE, 8, 8, 8, 8, 12, 12, 12, 255);
        list.line(0, 0, 10, 10, 2, Rgba.WHITE);
        list.rasterGlyph(3, 4, 9, 12, 0.5f, 0, 1, 0.25f, 7, Rgba.WHITE);
        list.colorGlyph(3, 4, 23, 24, 0.25f, 0, 0.5f, 0.5f, 9, Rgba.pack(1, 1, 1, 0.5f));
        assertThat(list.end()).isTrue();

        assertThat(Primitive.flags(list.header(start))).isEqualTo(Primitive.FLAG_GRADIENT);
        assertThat(list.colorAt(start, Primitive.COLOR_B)).isEqualTo(Rgba.BLACK);
        assertThat(list.floatAt(start, Primitive.GRADIENT_ANGLE)).isEqualTo(1.5f);
        assertThat(Primitive.clip(list.header(start))).isEqualTo(clip);
        assertThat(Primitive.kind(list.header(start + 1))).isEqualTo(Primitive.SHADOW);
        assertThat(list.floatAt(start + 1, Primitive.WIDTH)).isEqualTo(3);
        assertThat(Primitive.kind(list.header(start + 2))).isEqualTo(Primitive.GLYPH);
        assertThat(list.intAt(start + 2, Primitive.TEXTURE)).isEqualTo(7);
        assertThat(list.intAt(start + 2, Primitive.UV) & 0xFFFF).isEqualTo(16384); // 0.25
        assertThat(list.intAt(start + 2, Primitive.UV + 1) >>> 16).isEqualTo(65535);
        assertThat(list.floatAt(start + 2, Primitive.WEIGHT)).isEqualTo(0.1f);
        assertThat(Primitive.kind(list.header(start + 3))).isEqualTo(Primitive.IMAGE);
        assertThat(list.intAt(start + 4, Primitive.SOURCE_INSETS) >>> 24).isEqualTo(255);
        assertThat(Primitive.kind(list.header(start + 5))).isEqualTo(Primitive.LINE);
        assertThat(Primitive.kind(list.header(start + 6))).isEqualTo(Primitive.GLYPH);
        assertThat(Primitive.flags(list.header(start + 6))).isEqualTo(Primitive.FLAG_RASTER);
        assertThat(Primitive.flags(list.header(start + 2))).as("an MTSDF glyph").isZero();
        assertThat(list.intAt(start + 6, Primitive.TEXTURE)).isEqualTo(7);
        assertThat(list.intAt(start + 6, Primitive.UV) & 0xFFFF).isEqualTo(32768); // 0.5
        assertThat(list.floatAt(start + 6, Primitive.X1)).isEqualTo(9);
        assertThat(list.floatAt(start + 6, Primitive.PIXEL_RANGE)).isZero();
        assertThat(Primitive.flags(list.header(start + 7))).as("a colour glyph").isEqualTo(Primitive.FLAG_COLOR);
        assertThat(list.intAt(start + 7, Primitive.TEXTURE)).isEqualTo(9);
        assertThat(list.intAt(start + 7, Primitive.UV + 1) & 0xFFFF).isEqualTo(32768); // 0.5
        assertThat(Rgba.a(list.colorAt(start + 7, Primitive.COLOR_A))).isEqualTo(0.5f);
        float[] clips = list.clips();
        assertThat(list.clipCount()).isEqualTo(2);
        assertThat(clips[clip * DrawList.CLIP_FLOATS + 4]).isEqualTo(4);
    }

    @Test
    void aTransformMovesAndScalesEveryKind() {
        DrawList list = new DrawList();
        int start = list.allocate(8);
        list.begin(start, 8);
        list.transform(2, 10, 20);
        list.box(0, 0, 10, 10, Rgba.WHITE, 1, 2, 3, 4, 1, Rgba.BLACK);
        list.shadow(0, 0, 10, 10, Rgba.BLACK, 2, 2, 2, 2, 3);
        list.glyph(0, 0, 8, 8, 0, 0, 1, 1, 7, 2, Rgba.WHITE, 1, Rgba.BLACK, 0.5f);
        list.image(0, 0, 8, 8, 0, 0, 1, 1, 3, Rgba.WHITE);
        list.nineSlice(0, 0, 80, 40, 0, 0, 1, 1, 4, Rgba.WHITE, 8, 8, 8, 8, 12, 12, 12, 12);
        list.line(0, 0, 10, 10, 2, Rgba.WHITE);
        assertThat(list.end()).isTrue();
        assertThat(list.floatAt(start, Primitive.X0)).isEqualTo(10);
        assertThat(list.floatAt(start, Primitive.Y1)).isEqualTo(40);
        assertThat(Float.float16ToFloat((short) list.intAt(start, Primitive.RADII))).isEqualTo(2);
        assertThat(Float.float16ToFloat((short) (list.intAt(start, Primitive.RADII + 1) >>> 16))).isEqualTo(8);
        assertThat(list.floatAt(start, Primitive.WIDTH)).isEqualTo(2);
        assertThat(list.floatAt(start + 1, Primitive.WIDTH)).as("the blur").isEqualTo(6);
        assertThat(list.floatAt(start + 2, Primitive.PIXEL_RANGE)).isEqualTo(4);
        assertThat(list.floatAt(start + 2, Primitive.OUTLINE)).isEqualTo(2);
        assertThat(list.floatAt(start + 2, Primitive.WEIGHT)).isEqualTo(1);
        assertThat(list.floatAt(start + 3, Primitive.X1)).isEqualTo(26);
        assertThat(Float.float16ToFloat((short) list.intAt(start + 4, Primitive.INSETS))).isEqualTo(16);
        assertThat(list.floatAt(start + 5, Primitive.WIDTH)).isEqualTo(4);
        assertThat(list.floatAt(start + 5, Primitive.X1)).isEqualTo(30);
        list.begin(start, 1);
        list.box(0, 0, 10, 10, Rgba.WHITE, 0, 0, 0, 0, 0, 0);
        list.end();
        assertThat(list.floatAt(start, Primitive.X1)).as("a new range starts untransformed").isEqualTo(10);
    }

    @Test
    void reRecordingTheSameDirtiesNothing() {
        DrawList list = new DrawList();
        int a = list.allocate(2);
        int b = list.allocate(2);
        record(list, a, 10);
        record(list, b, 20);
        list.clean();
        long version = list.version();

        record(list, a, 10);
        assertThat(list.dirtyFrom()).isEqualTo(list.dirtyTo());
        assertThat(list.version()).isEqualTo(version);

        record(list, b, 21);
        assertThat(list.dirtyFrom()).isEqualTo(b);
        assertThat(list.dirtyTo()).isEqualTo(b + 1);
        assertThat(list.version()).isGreaterThan(version);
    }

    @Test
    void aRangeThatOverflowsSaysHowManyItNeeded() {
        DrawList list = new DrawList();
        int a = list.allocate(1);
        list.begin(a, 1);
        list.line(0, 0, 1, 1, 1, Rgba.WHITE);
        list.line(0, 0, 1, 1, 1, Rgba.WHITE);
        list.line(0, 0, 1, 1, 1, Rgba.WHITE);
        assertThat(list.end()).isFalse();
        assertThat(list.needed()).isEqualTo(3);
        assertThat(list.size()).isEqualTo(1);
    }

    @Test
    void misuseIsRefused() {
        DrawList list = new DrawList();
        assertThatThrownBy(() -> list.line(0, 0, 1, 1, 1, Rgba.WHITE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> list.begin(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.begin(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.clip(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.clip(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.setClip(0, 0, 0, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.setClip(5, 0, 0, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        int a = list.allocate(1);
        list.begin(a, 1);
        assertThatThrownBy(() -> list.nineSlice(0, 0, 1, 1, 0, 0, 1, 1, 1, Rgba.WHITE, 0, 0, 0, 0, 256, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.nineSlice(0, 0, 1, 1, 0, 0, 1, 1, 1, Rgba.WHITE, 0, 0, 0, 0, -1, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clipsAndGrowthAreTracked() {
        DrawList list = new DrawList();
        for (int i = 0; i < 40; i++) {
            list.addClip(i, i, i + 10, i + 10, 0);
        }
        assertThat(list.clipCount()).isEqualTo(41);
        list.clean();
        assertThat(list.clipsDirty()).isFalse();
        list.setClip(3, 2, 2, 12, 12, 0); // as it was
        assertThat(list.clipsDirty()).isFalse();
        list.setClip(3, 2, 2, 12, 13, 0);
        assertThat(list.clipsDirty()).isTrue();
        for (int field = 0; field < 5; field++) {
            list.clean();
            float[] v = {2, 2, 12, 13, 0};
            v[field] += 1;
            list.setClip(3, v[0], v[1], v[2], v[3], v[4]);
            assertThat(list.clipsDirty()).isTrue();
            list.setClip(3, 2, 2, 12, 13, 0);
        }

        int big = list.allocate(1000);
        assertThat(list.size()).isEqualTo(1000);
        assertThat(big).isZero();
        list.reset();
        assertThat(list.size()).isZero();
        assertThat(list.clipCount()).isEqualTo(1);
    }

    @Test
    void recordingAllocatesNothing() {
        DrawList list = new DrawList();
        int a = list.allocate(4);
        int[] i = {0};
        AllocationAssertions.assertAllocationFree(() -> {
            list.begin(a, 4);
            list.box(0, 0, i[0]++ & 63, 10, Rgba.WHITE, 2, 2, 2, 2, 1, Rgba.BLACK);
            list.glyph(0, 0, 8, 8, 0, 0, 1, 1, 1, 2, Rgba.WHITE, 0, 0, 0);
            list.end();
            list.clean();
        });
    }

    private static void record(DrawList list, int start, float x) {
        list.begin(start, 2);
        list.box(x, 0, x + 10, 10, Rgba.WHITE, 0, 0, 0, 0, 0, 0);
        list.end();
    }
}
