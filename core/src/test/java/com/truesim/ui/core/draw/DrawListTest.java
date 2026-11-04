package com.truesim.ui.core.draw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.testing.AllocationAssertions;
import org.assertj.core.data.Offset;
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
    void aClipsOffsetMovesItsPrimitivesWhereTheyDrawAndDirtiesOnlyTheClipTable() {
        DrawList list = new DrawList();
        int clip = list.addClip(0, 0, 100, 100, 0);
        int a = list.allocate(1);
        list.begin(a, 1);
        list.clip(clip);
        list.box(10, 20, 30, 40, Rgba.WHITE, 0, 0, 0, 0, 0, 0);
        list.end();
        list.clean();
        long version = list.version();
        list.setClipOffset(clip, 0, 0); // as it is
        assertThat(list.clipsDirty()).isFalse();
        list.setClipOffset(clip, -5, -15);
        assertThat(list.clipsDirty()).isTrue();
        assertThat(list.version()).isGreaterThan(version);
        assertThat(list.dirtyTo()).isEqualTo(list.dirtyFrom()); // no primitive changed
        assertThat(list.clipOffsetX(clip)).isEqualTo(-5);
        assertThat(list.clipOffsetY(clip)).isEqualTo(-15);
        assertThat(list.floatAt(a, Primitive.X0)).isEqualTo(10);
        assertThat(list.drawnX(a, Primitive.X0)).isEqualTo(5);
        assertThat(list.drawnY(a, Primitive.Y1)).isEqualTo(25);
        assertThat(list.clips()[clip * DrawList.CLIP_FLOATS + 5]).isEqualTo(-5);
        assertThatThrownBy(() -> list.setClipOffset(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.setClipOffset(9, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        list.reset();
        assertThat(list.clipOffsetX(0)).isZero();
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

    @Test
    void primitivesDrawByTheirTransformEntryWhichAnAnimationRewritesAlone() {
        DrawList list = new DrawList();
        int t = list.addTransform();
        int clip = list.addClip(0, 0, 50, 50, 4);
        list.setClipTransform(clip, t);
        list.setClipOffset(clip, 0, -10);
        int a = list.allocate(3);
        list.begin(a, 3);
        list.clip(clip);
        list.useTransform(t);
        list.box(10, 20, 30, 40, Rgba.pack(1, 1, 1, 0.8f), 2, 2, 2, 2, 1, Rgba.BLACK);
        list.glyph(0, 10, 8, 18, 0, 0, 1, 1, 7, 2, Rgba.WHITE, 1, Rgba.BLACK, 0.5f);
        list.useTransform(DrawList.NO_TRANSFORM);
        list.line(0, 10, 10, 10, 2, Rgba.WHITE);
        assertThat(list.end()).isTrue();
        assertThat(list.transformOf(a)).isEqualTo(t);
        assertThat(list.intAt(a, Primitive.TRANSFORM)).isEqualTo(t);
        assertThat(list.transformOf(a + 2)).isEqualTo(DrawList.NO_TRANSFORM);
        assertThat(list.drawnX(a, Primitive.X0)).as("an identity entry: the clip's offset alone").isEqualTo(10);
        assertThat(list.drawnY(a, Primitive.Y0)).isEqualTo(10);
        list.clean();

        list.setTransform(t, 2, 100, 50, 0.5f); // an animation: scale, offset and opacity
        assertThat(list.dirtyRanges().isEmpty()).as("no primitive changed").isTrue();
        assertThat(list.transformRanges().count()).isEqualTo(1);
        assertThat(list.transformRanges().from(0)).isEqualTo(t);
        assertThat(list.clipsDirty()).isFalse();
        assertThat(list.drawnX(a, Primitive.X0)).isEqualTo(120); // 2 * 10 + 100
        assertThat(list.drawnY(a, Primitive.Y1)).isEqualTo(110); // 2 * (40 - 10) + 50
        assertThat(list.drawnScale(a)).isEqualTo(2);
        assertThat(list.drawnOpacity(a)).isEqualTo(0.5f);
        assertThat(list.drawnX(a + 2, Primitive.X1)).as("the identity").isEqualTo(10);
        float[] c = new float[5];
        list.drawnClip(clip, c);
        assertThat(c).as("the clip cuts where its owner draws").containsExactly(100, 50, 200, 150, 8);

        DrawList b = list.baked();
        assertThat(b.floatAt(a, Primitive.X0)).isEqualTo(120);
        assertThat(b.floatAt(a, Primitive.WIDTH)).as("the border scaled").isEqualTo(2);
        assertThat(Float.float16ToFloat((short) b.intAt(a, Primitive.RADII))).isEqualTo(4);
        assertThat(Rgba.a(b.colorAt(a, Primitive.COLOR_A))).isCloseTo(0.4f, Offset.offset(1e-3f));
        assertThat(Rgba.a(b.colorAt(a, Primitive.BORDER_COLOR))).isEqualTo(0.5f);
        assertThat(b.floatAt(a + 1, Primitive.PIXEL_RANGE)).isEqualTo(4);
        assertThat(Rgba.a(b.colorAt(a + 1, Primitive.COLOR_B))).as("the outline faded too").isEqualTo(0.5f);
        assertThat(b.transformOf(a)).isZero();
        assertThat(b.clips()[clip * DrawList.CLIP_FLOATS + 2]).isEqualTo(200);
        assertThat(b.clipOffsetY(clip)).isZero();
        assertThat(b.drawnX(a, Primitive.X0)).isEqualTo(120);

        list.clean();
        list.setTransform(t, 2, 100, 50, 0.5f); // as it is
        assertThat(list.transformRanges().isEmpty()).isTrue();
        assertThatThrownBy(() -> list.setTransform(0, 1, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.setTransform(9, 1, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.setClipTransform(clip, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.setClipTransform(0, t)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.useTransform(9)).isInstanceOf(IllegalArgumentException.class);
        long generation = list.generation();
        list.reset();
        assertThat(list.generation()).isGreaterThan(generation);
        assertThat(list.transformCount()).isEqualTo(1);
        assertThat(list.transformScale(0)).isEqualTo(1);
        assertThat(list.transformOpacity(0)).isEqualTo(1);
    }

    @Test
    void theTransformTableGrows() {
        DrawList list = new DrawList();
        for (int i = 1; i < 100; i++) {
            assertThat(list.addTransform()).isEqualTo(i);
        }
        list.setTransform(99, 3, 0, 0, 1);
        assertThat(list.transformScale(99)).isEqualTo(3);
        assertThat(list.transforms().length).isGreaterThanOrEqualTo(100 * DrawList.TRANSFORM_FLOATS);
    }

    @Test
    void changesFarApartAreSeparateDirtyRanges() {
        DrawList list = new DrawList();
        int a = list.allocate(1000);
        list.clean();
        record(list, a + 10, 1);
        record(list, a + 900, 2);
        record(list, a + 905, 3); // close to the last: merged with it
        DirtyRanges r = list.dirtyRanges();
        assertThat(r.count()).isEqualTo(2);
        assertThat(r.from(0)).isEqualTo(a + 10);
        assertThat(r.to(0)).isEqualTo(a + 11);
        assertThat(r.from(1)).isEqualTo(a + 900);
        assertThat(r.to(1)).isEqualTo(a + 906);
        assertThat(list.dirtyFrom()).isEqualTo(a + 10);
        assertThat(list.dirtyTo()).isEqualTo(a + 906);
        list.clean();
        assertThat(r.isEmpty()).isTrue();
    }

    private static void record(DrawList list, int start, float x) {
        list.begin(start, 2);
        list.box(x, 0, x + 10, 10, Rgba.WHITE, 0, 0, 0, 0, 0, 0);
        list.end();
    }
    @Test
    void pathsAndColumnsDrawVerticesOfTheVertexTable() {
        DrawList list = new DrawList();
        int start = list.allocate(3);
        int at = list.allocateVertices(3);
        assertThat(at).isZero();
        assertThat(list.vertexCount()).isEqualTo(3);
        assertThat(list.vertexRanges().count()).isEqualTo(1);
        list.begin(start, 3);
        list.transform(2, 10, 20); // a range's baked transform applies to its vertices too
        list.pathVertex(at, 0, 0, 0);
        list.pathVertex(at + 1, 10, 0, 10);
        list.pathVertex(at + 2, Float.NaN, 5, 0); // a break: left out of the box
        list.path(at, 3, 1.5f, Rgba.WHITE, Primitive.JOIN_MITER | Primitive.CAP_ROUND, 4, 3, 2, 0, 0, 1);
        int columns = list.allocateVertices(2);
        list.columnVertex(columns, 1, 2, 0, 3);
        list.columnVertex(columns + 1, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        list.columns(columns, 2, 5.5f, -1, 1, Rgba.WHITE, 0, true);
        list.columns(columns, 2, 5.5f, -1, 0, Rgba.WHITE, 8, false);
        assertThat(list.end()).isTrue();
        assertThat(list.vertexAt(at + 1, 0)).isEqualTo(30); // 2 * 10 + 10
        assertThat(list.vertexAt(at + 1, 1)).isEqualTo(20);
        assertThat(list.vertexAt(at + 1, 2)).as("lengths scale").isEqualTo(20);
        assertThat(list.vertexAt(columns, 3)).isEqualTo(26);
        assertThat(Primitive.kind(list.header(start))).isEqualTo(Primitive.PATH);
        assertThat(list.intAt(start, Primitive.FIRST_VERTEX)).isEqualTo(at);
        assertThat(list.intAt(start, Primitive.VERTEX_COUNT)).isEqualTo(3);
        assertThat(list.intAt(start, Primitive.STROKE)).isEqualTo(Primitive.JOIN_MITER | Primitive.CAP_ROUND);
        assertThat(list.floatAt(start, Primitive.WIDTH)).as("sizes scale").isEqualTo(3);
        assertThat(list.floatAt(start, Primitive.DASH_PHASE)).isEqualTo(2);
        assertThat(list.intAt(start, Primitive.DASHES)).isEqualTo(Primitive.halves(6, 4));
        assertThat(list.intAt(start, Primitive.DASHES + 1)).as("one pair repeats").isEqualTo(Primitive.halves(6, 4));
        assertThat(list.floatAt(start, Primitive.X0)).as("the box: the points and the miters' reach")
            .isLessThan(10 - 1.5f * 4);
        assertThat(list.floatAt(start, Primitive.X1)).isGreaterThan(30 + 1.5f * 4);
        int stroke = start + 1;
        assertThat(Primitive.kind(list.header(stroke))).isEqualTo(Primitive.COLUMNS);
        assertThat(list.floatAt(stroke, Primitive.COLUMN_X)).isEqualTo(21);
        assertThat(list.floatAt(stroke, Primitive.COLUMN_STEP)).isEqualTo(-2);
        assertThat(list.intAt(stroke, Primitive.STROKE)).isEqualTo(Primitive.COLUMNS_STEPS);
        assertThat(list.floatAt(stroke, Primitive.X0)).as("leftwards").isLessThan(19);
        assertThat(list.floatAt(stroke, Primitive.X1)).isGreaterThan(21);
        assertThat(list.floatAt(stroke, Primitive.Y0)).isLessThan(20);
        assertThat(list.floatAt(stroke, Primitive.Y1)).isGreaterThan(26);
        int fill = start + 2;
        assertThat(list.intAt(fill, Primitive.STROKE)).isEqualTo(Primitive.COLUMNS_FILL);
        assertThat(list.floatAt(fill, Primitive.BASELINE)).isEqualTo(36);
        assertThat(list.floatAt(fill, Primitive.WIDTH)).isZero();
        assertThat(list.floatAt(fill, Primitive.Y1)).as("down to the baseline").isGreaterThan(36);
        // written again unchanged: nothing dirty; a vertex changed: that vertex
        list.clean();
        long version = list.version();
        list.columnVertex(columns + 1, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        list.pathVertex(at, 10, 20, 0); // outside a range: as given (where the range's transform put it)
        assertThat(list.version()).isEqualTo(version);
        assertThat(list.vertexRanges().isEmpty()).isTrue();
        list.pathVertex(at + 2, 1, 1, 1);
        assertThat(list.vertexRanges().from(0)).isEqualTo(at + 2);
        assertThat(list.vertexRanges().to(0)).isEqualTo(at + 3);
        // baked: the clip offset and the transform entry applied, the colours words 7-8 left alone
        int t = list.addTransform();
        list.setTransform(t, 0.5f, 100, 0, 0.5f);
        list.begin(start, 3);
        list.useTransform(t);
        list.path(at, 2, 2, Rgba.WHITE, 0, 4, 4, 4, 2, 2, 0);
        list.columns(columns, 1, 0, 1, 2, Rgba.WHITE, 10, false);
        list.end();
        DrawList baked = list.baked();
        assertThat(baked.vertexAt(at + 1, 0)).isEqualTo(30 * 0.5f + 100);
        assertThat(baked.vertexAt(at + 1, 2)).isEqualTo(10);
        assertThat(baked.vertexAt(columns, 3)).isEqualTo(13);
        assertThat(baked.floatAt(start, Primitive.WIDTH)).isEqualTo(1);
        assertThat(baked.intAt(start, Primitive.DASHES + 1)).isEqualTo(Primitive.halves(1, 1));
        assertThat(baked.intAt(start, Primitive.VERTEX_COUNT)).as("not faded as a colour").isEqualTo(2);
        assertThat(baked.floatAt(start + 1, Primitive.COLUMN_X)).isEqualTo(100);
        assertThat(baked.floatAt(start + 1, Primitive.BASELINE)).isEqualTo(5);
        assertThat(baked.floatAt(start + 1, Primitive.COLUMN_STEP)).isEqualTo(0.5f);
        // misuse; a reset forgets the table
        assertThatThrownBy(() -> list.pathVertex(99, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.allocateVertices(-1)).isInstanceOf(IllegalArgumentException.class);
        list.begin(start, 3);
        assertThatThrownBy(() -> list.path(4, 10, 1, Rgba.WHITE, 0, 4, 0, 0, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> list.columns(4, 10, 0, 1, 1, Rgba.WHITE, 0, false))
            .isInstanceOf(IllegalArgumentException.class);
        list.end();
        long generation = list.generation();
        list.reset();
        assertThat(list.generation()).isGreaterThan(generation);
        assertThat(list.vertexCount()).isZero();
        int big = list.allocateVertices(1000); // grows
        assertThat(list.vertices().length).isGreaterThanOrEqualTo(1000 * DrawList.VERTEX_FLOATS);
        assertThat(big).isZero();
        int empty = list.allocate(2);
        list.begin(empty, 2);
        list.path(0, 0, 1, Rgba.WHITE, 0, 4, 0, 0, 0, 0, 0); // no points: an empty box
        list.columns(0, 0, 0, 1, 1, Rgba.WHITE, 0, false);
        list.end();
        assertThat(list.floatAt(empty, Primitive.X1)).isZero();
    }

    @Test
    void writingVerticesAllocatesNothing() {
        DrawList list = new DrawList();
        int start = list.allocate(2);
        int at = list.allocateVertices(100);
        int[] n = {0};
        Runnable frame = () -> {
            int k = n[0]++;
            list.begin(start, 2);
            for (int i = 0; i < 100; i++) {
                list.columnVertex(at + i, i + k % 3, i, i - 1, i + 1);
            }
            list.columns(at, 100, 0.5f, 1, 1, Rgba.WHITE, 0, false);
            list.path(at, 50, 1, Rgba.WHITE, Primitive.JOIN_ROUND, 4, 0, 0, 0, 0, 0);
            list.end();
            list.clean();
        };
        for (int i = 0; i < 2000; i++) {
            frame.run();
        }
        AllocationAssertions.assertAllocationFree(frame);
    }
}
