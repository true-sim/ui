package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackingAndStorageTest {

    @Test
    void theSkylinePackerPlacesRectanglesWithoutOverlapUntilFull() {
        SkylinePacker packer = new SkylinePacker(256, 256);
        Random random = new Random(7);
        List<int[]> placed = new ArrayList<>();
        while (true) {
            int w = 8 + random.nextInt(40);
            int h = 8 + random.nextInt(40);
            if (!packer.pack(w, h)) {
                break;
            }
            int[] r = {packer.x(), packer.y(), w, h};
            assertThat(r[0]).isBetween(0, 256 - w);
            assertThat(r[1]).isBetween(0, 256 - h);
            for (int[] o : placed) {
                boolean apart = r[0] + r[2] <= o[0] || o[0] + o[2] <= r[0] || r[1] + r[3] <= o[1]
                    || o[1] + o[3] <= r[1];
                assertThat(apart).as("%s overlaps %s", List.of(r[0], r[1]), List.of(o[0], o[1])).isTrue();
            }
            placed.add(r);
        }
        assertThat(placed.size()).isGreaterThan(40);
        assertThat(packer.occupancy()).isBetween(0.6f, 1f);
        assertThat(packer.pack(300, 10)).isFalse();
        packer.reset(64, 64);
        assertThat(packer.pack(64, 64)).isTrue();
        assertThat(packer.x()).isZero();
        assertThat(packer.pack(1, 1)).isFalse();
        assertThatThrownBy(() -> packer.pack(0, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> packer.reset(0, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSkylinePackerFillsTheLowestGapFirst() {
        SkylinePacker packer = new SkylinePacker(100, 100);
        assertThat(packer.pack(60, 30)).isTrue();
        assertThat(packer.pack(40, 10)).isTrue();
        assertThat(packer.x()).isEqualTo(60);
        assertThat(packer.y()).isZero();
        assertThat(packer.pack(40, 10)).isTrue(); // on top of the 10-high one, still above the 30-high one
        assertThat(packer.y()).isEqualTo(10);
        assertThat(packer.pack(100, 10)).isTrue(); // spans both: lands below the higher one
        assertThat(packer.y()).isEqualTo(30);
        for (int i = 0; i < 200; i++) { // many small ones grow the node arrays
            packer.pack(1, 1);
        }
    }

    @Test
    void theLongMapStoresAndGrows() {
        LongIntMap map = new LongIntMap(2);
        for (int i = 0; i < 1000; i++) {
            map.put((long) i << 32 | i * 7, i);
        }
        assertThat(map.size()).isEqualTo(1000);
        for (int i = 0; i < 1000; i++) {
            assertThat(map.get((long) i << 32 | i * 7)).isEqualTo(i);
        }
        map.put(5L << 32 | 35, 99);
        assertThat(map.get(5L << 32 | 35)).isEqualTo(99);
        assertThat(map.get(12345)).isEqualTo(-1);
        map.clear();
        assertThat(map.get(0)).isEqualTo(-1);
        assertThat(map.size()).isZero();
    }

    @Test
    void anOutlineKeepsItsCommandsAndBounds() {
        GlyphOutline o = new GlyphOutline();
        float[] b = new float[4];
        o.bounds(b);
        assertThat(b).containsExactly(0, 0, 0, 0);
        o.moveTo(0, 0);
        assertThat(o.isEmpty()).isTrue();
        o.lineTo(1, 0);
        o.quadTo(1, 1, 0.5f, 1);
        o.cubicTo(0.2f, 1.2f, -0.1f, 0.5f, 0, 0);
        assertThat(o.isEmpty()).isFalse();
        assertThat(o.verbCount()).isEqualTo(4);
        assertThat(o.pointFloats()).isEqualTo(2 + 2 + 4 + 6);
        o.bounds(b);
        assertThat(b).containsExactly(-0.1f, 0, 1, 1.2f);
        GlyphOutline c = o.copy();
        assertThat(c.verbCount()).isEqualTo(4);
        assertThat(c.points()[4]).isEqualTo(1);
        for (int i = 0; i < 200; i++) { // grows
            o.lineTo(i, i);
        }
        assertThat(o.verbCount()).isEqualTo(204);
        o.reset();
        assertThat(o.verbCount()).isZero();
        assertThat(c.verbCount()).isEqualTo(4);
    }

    @Test
    void geometryPadsTheOutlineByHalfTheRange() {
        MsdfGeometry g = MsdfGeometry.fit(new float[] {0, 0, 0.5f, 0.7f}, 40, 6);
        // 0..20 x 0..28 texels, padded by 3.5 each side and rounded out
        assertThat(g.width()).isEqualTo(28);
        assertThat(g.height()).isEqualTo(36);
        assertThat(g.planeLeft()).isEqualTo(-4 / 40f);
        assertThat(g.planeBottom()).isEqualTo(-4 / 40f);
        assertThat(g.planeRight()).isCloseTo(24 / 40f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(g.planeTop()).isCloseTo(32 / 40f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(g.range()).isEqualTo(6 / 40.0);
    }

    @Test
    void theCacheRoundTripsThroughAFile(@TempDir Path dir) throws IOException {
        GlyphCache cache = new GlyphCache(40, 6);
        assertThat(cache.matches(40, 6)).isTrue();
        assertThat(cache.matches(32, 6)).isFalse();
        MsdfGeometry g = new MsdfGeometry(2, 3, 40, 0.1, 0.2, 0.15);
        byte[] pixels = new byte[2 * 3 * 4];
        pixels[5] = 42;
        cache.put("a.ttf#0:10", 7, g, pixels);
        cache.put("a.ttf#0:10", 3, null, null);
        cache.put("a.ttf#0:10", 7, g, new byte[24]); // the first one stays
        assertThat(cache.dirty()).isTrue();
        assertThat(cache.size()).isEqualTo(2);
        assertThatThrownBy(() -> cache.put("x", 1, g, new byte[3])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.put("x", 1, null, new byte[3])).isInstanceOf(IllegalArgumentException.class);

        Path file = dir.resolve("sub/glyphs.tsgc");
        cache.save(file);
        assertThat(cache.dirty()).isFalse();
        GlyphCache loaded = GlyphCache.load(file);
        assertThat(loaded.emSize()).isEqualTo(40);
        assertThat(loaded.pixelRange()).isEqualTo(6);
        assertThat(loaded.dirty()).isFalse();
        assertThat(loaded.get("a.ttf#0:10", 7).geometry()).isEqualTo(g);
        assertThat(loaded.get("a.ttf#0:10", 7).pixels()[5]).isEqualTo((byte) 42);
        assertThat(loaded.get("a.ttf#0:10", 3).geometry()).isNull();
        assertThat(loaded.get("b", 7)).isNull();

        Path junk = dir.resolve("junk");
        Files.write(junk, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThatThrownBy(() -> GlyphCache.load(junk)).isInstanceOf(IOException.class);
    }
}
