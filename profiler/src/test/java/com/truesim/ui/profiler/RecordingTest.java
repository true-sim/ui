package com.truesim.ui.profiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The recording's rings, seeking, retention, frames and counters, the zone statistics, the flame tree, the files. */
class RecordingTest {

    @Test
    void zonesOpenCloseAndGrowUpToTheirCapacityThenDropTheOldest() {
        Recording r = new Recording(4096, 64, 64);
        int lane = r.lane("main", LaneKind.THREAD);
        assertThat(r.lane("main", LaneKind.THREAD)).isEqualTo(lane);
        assertThat(r.lane("main", LaneKind.GPU)).isNotEqualTo(lane);
        int name = r.name("work");
        assertThat(r.name("work")).isEqualTo(name);
        long z = r.open(lane, 100, name, 0);
        assertThat(r.zoneEnd(lane, z)).isEqualTo(Recording.OPEN);
        r.close(lane, z, 250);
        assertThat(r.zoneEnd(lane, z)).isEqualTo(250);
        for (int i = 0; i < 10_000; i++) {
            r.zone(lane, 1000 + i * 10L, 1005 + i * 10L, name, 0);
        }
        assertThat(r.endZone(lane) - r.firstZone(lane)).isEqualTo(4096);
        assertThat(r.zoneStart(lane, r.firstZone(lane))).isEqualTo(1000 + (10_001 - 4096 - 1) * 10L);
        assertThat(r.endTime()).isEqualTo(1005 + 9_999 * 10L);
        r.close(lane, z, 300); // dropped since: ignored
    }

    @Test
    void seekFindsTheTopLevelZoneRunningAtATime() {
        Recording r = new Recording();
        int lane = r.lane("main", LaneKind.THREAD);
        int a = r.name("a");
        r.zone(lane, 0, 100, a, 0); // 0
        r.zone(lane, 10, 20, a, 1); // 1
        r.zone(lane, 30, 90, a, 1); // 2
        r.zone(lane, 40, 50, a, 2); // 3
        r.zone(lane, 200, 300, a, 0); // 4
        assertThat(r.seek(lane, 45)).isEqualTo(0);
        assertThat(r.seek(lane, 150)).isEqualTo(4);
        assertThat(r.seek(lane, 250)).isEqualTo(4);
        assertThat(r.seek(lane, 400)).isEqualTo(5);
        assertThat(r.seek(lane, -5)).isEqualTo(0);
    }

    @Test
    void retainDropsWhatEndedBeforeTheSpan() {
        Recording r = new Recording();
        int lane = r.lane("main", LaneKind.THREAD);
        int c = r.counter("cpu.load", "%");
        int a = r.name("a");
        for (int i = 0; i < 100; i++) {
            r.zone(lane, i * 1000L, i * 1000L + 500, a, 0);
            r.frame(i, i * 1000L, i * 1000L + 500, 0, false);
            r.sample(c, i * 1000L, i);
        }
        long open = r.open(lane, 100_000, a, 0);
        r.retain(10_000);
        assertThat(r.zoneStart(lane, r.firstZone(lane))).isEqualTo(90_000);
        assertThat(r.firstFrame()).isEqualTo(90);
        assertThat(r.sampleTime(c, r.firstSample(c))).isEqualTo(90_000);
        assertThat(r.endZone(lane)).isEqualTo(open + 1);
    }

    @Test
    void framesTakeTheirGpuSpanLaterAndAreFoundByTime() {
        Recording r = new Recording(64, 16, 16);
        for (int f = 0; f < 40; f++) {
            r.frame(f, f * 100L, f * 100L + 60, 5, f == 30);
            if (f >= 2) {
                r.gpuFrame(f - 2, (f - 2) * 100L + 70, (f - 2) * 100L + 150);
            }
        }
        assertThat(r.endFrame()).isEqualTo(40);
        assertThat(r.firstFrame()).isEqualTo(24);
        assertThat(r.frameGpuEnd(37)).isEqualTo(3850);
        assertThat(r.frameGpuEnd(39)).isZero();
        assertThat(r.frameHitch(30)).isTrue();
        assertThat(r.frameAt(3_050)).isEqualTo(30);
        assertThat(r.frameAt(0)).isEqualTo(-1);
    }

    @Test
    void zoneStatsMatchABruteForceSum() {
        Recording r = new Recording();
        int[] lanes = {r.lane("main", LaneKind.THREAD), r.lane("worker", LaneKind.THREAD)};
        int gpu = r.lane("gpu", LaneKind.GPU);
        String[] names = {"a", "b", "c", "d"};
        SplittableRandom random = new SplittableRandom(3);
        Map<String, List<Long>> expected = new HashMap<>();
        Map<String, Long> children = new HashMap<>();
        for (int lane : lanes) {
            long t = 0;
            for (int i = 0; i < 500; i++) {
                String top = names[random.nextInt(names.length)];
                long topStart = t;
                long z = r.open(lane, t, r.name(top), 0);
                long inner = 0;
                for (int k = 0; k < 3; k++) {
                    String child = names[random.nextInt(names.length)];
                    long d = 10 + random.nextInt(100);
                    r.zone(lane, t + 1, t + 1 + d, r.name(child), 1);
                    expected.computeIfAbsent(child, x -> new ArrayList<>()).add(d);
                    t += d + 2;
                    inner += d;
                }
                r.close(lane, z, t);
                expected.computeIfAbsent(top, x -> new ArrayList<>()).add(t - topStart);
                children.merge(top, inner, Long::sum);
                t += 5;
            }
        }
        r.zone(gpu, 0, 1_000_000, r.name("a"), 0); // not a thread: not counted
        ZoneStats s = new ZoneStats().names(r).compute(r, -1, 0, Long.MAX_VALUE - 1);
        assertThat(s.rows()).isEqualTo(4);
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < s.rows(); i++) {
            String name = r.nameOf(s.name(i));
            List<Long> d = new ArrayList<>(expected.get(name));
            d.sort(null);
            long total = d.stream().mapToLong(Long::longValue).sum();
            assertThat(s.calls(i)).isEqualTo(d.size());
            assertThat(s.total(i)).isEqualTo(total);
            assertThat(s.max(i)).isEqualTo(d.getLast());
            assertThat(s.p99(i)).isEqualTo(d.get((int) Math.ceil(d.size() * 0.99) - 1));
            assertThat(s.self(i)).isEqualTo(total - children.getOrDefault(name, 0L));
            assertThat(s.total(i)).isLessThanOrEqualTo(previous); // sorted by total, largest first
            previous = s.total(i);
        }
        s.sort(ZoneStats.Column.NAME, false);
        assertThat(r.nameOf(s.name(0))).isEqualTo("a");
        assertThat(r.nameOf(s.name(3))).isEqualTo("d");
        s.sort(ZoneStats.Column.CALLS, true);
        assertThat(s.calls(0)).isGreaterThanOrEqualTo(s.calls(3));
        // one lane, a span
        ZoneStats one = new ZoneStats().compute(r, lanes[0], 0, 1000);
        assertThat(one.zones()).isPositive().isLessThan(40);
    }

    @Test
    void theFlameTreeMergesPathsAndPlacesChildrenUnderTheirParents() {
        Recording r = new Recording();
        int lane = r.lane("main", LaneKind.THREAD);
        int frame = r.name("frame");
        int update = r.name("update");
        int render = r.name("render");
        int physics = r.name("physics");
        for (int f = 0; f < 3; f++) {
            long t = f * 100L;
            long z = r.open(lane, t, frame, 0);
            r.zone(lane, t, t + 30, update, 1);
            r.zone(lane, t + 2, t + 12, physics, 2);
            r.zone(lane, t + 40, t + 90, render, 1);
            r.close(lane, z, t + 95);
        }
        FlameTree tree = new FlameTree().build(r, lane, 0, 300, 100);
        assertThat(tree.count()).isEqualTo(5); // span, frame, update, physics, render
        assertThat(tree.time(0)).isEqualTo(300);
        assertThat(tree.name(1)).isEqualTo(frame);
        assertThat(tree.time(1)).isEqualTo(285);
        assertThat(tree.calls(1)).isEqualTo(3);
        assertThat(tree.name(2)).isEqualTo(update);
        assertThat(tree.time(2)).isEqualTo(90);
        assertThat(tree.name(3)).isEqualTo(physics);
        assertThat(tree.parent(3)).isEqualTo(2);
        assertThat(tree.offset(4)).isEqualTo(90); // render after update, within frame
        assertThat(tree.self(1)).isEqualTo(285 - 90 - 150);
        // a cut span counts the parts within it; the cap leaves paths out
        FlameTree cut = new FlameTree().build(r, lane, 50, 150, 100);
        assertThat(cut.time(1)).isEqualTo(45 + 50);
        FlameTree capped = new FlameTree().build(r, lane, 0, 300, 3);
        assertThat(capped.count()).isEqualTo(3);
        assertThat(capped.dropped()).isPositive();
    }

    @Test
    void theOwnFormatRoundTrips() throws Exception {
        Recording r = new Recording(1 << 12, 256, 256);
        FrameTimes times = new FrameTimes(new com.truesim.common.state.ChangeClock(), 240);
        ProfilerGallery.fill(r, times);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProfileFile.write(r, out);
        Recording back = ProfileFile.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(back.lanes()).isEqualTo(r.lanes());
        assertThat(back.names()).isEqualTo(r.names());
        for (int l = 0; l < r.lanes(); l++) {
            assertThat(back.laneName(l)).isEqualTo(r.laneName(l));
            assertThat(back.laneKind(l)).isEqualTo(r.laneKind(l));
            assertThat(back.endZone(l) - back.firstZone(l)).isEqualTo(r.endZone(l) - r.firstZone(l));
            long a = r.firstZone(l);
            long b = back.firstZone(l);
            for (long i = 0; i < r.endZone(l) - r.firstZone(l); i++) {
                assertThat(back.zoneStart(l, b + i)).isEqualTo(r.zoneStart(l, a + i));
                assertThat(back.zoneEnd(l, b + i)).isEqualTo(r.zoneEnd(l, a + i));
                assertThat(back.nameOf(back.zoneName(l, b + i))).isEqualTo(r.nameOf(r.zoneName(l, a + i)));
                assertThat(back.zoneDepth(l, b + i)).isEqualTo(r.zoneDepth(l, a + i));
            }
        }
        assertThat(back.firstFrame()).isEqualTo(r.firstFrame());
        assertThat(back.endFrame()).isEqualTo(r.endFrame());
        assertThat(back.frameGpuEnd(100)).isEqualTo(r.frameGpuEnd(100));
        assertThat(back.frameHitch(200)).isTrue();
        assertThat(back.counters()).isEqualTo(r.counters());
        assertThat(back.latestValue(0)).isEqualTo(r.latestValue(0));
    }

    @Test
    void chromeTraceJsonHasEveryZoneFrameAndSampleAndIsWellFormed() throws Exception {
        Recording r = new Recording();
        FrameTimes times = new FrameTimes(new com.truesim.common.state.ChangeClock(), 240);
        ProfilerGallery.fill(r, times);
        r.name("quote \" and \\ and \n");
        r.zone(0, r.endTime(), r.endTime() + 10, r.name("quote \" and \\ and \n"), 0);
        StringWriter out = new StringWriter();
        ChromeTrace.write(r, out);
        String json = out.toString();
        Object parsed = new Json(json).value();
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) ((Map<String, Object>) parsed).get("traceEvents");
        long zones = 0;
        for (int l = 0; l < r.lanes(); l++) {
            for (long z = r.firstZone(l); z < r.endZone(l); z++) {
                zones += r.zoneEnd(l, z) == Recording.OPEN ? 0 : 1;
            }
        }
        long samples = 0;
        for (int c = 0; c < r.counters(); c++) {
            samples += r.endSample(c) - r.firstSample(c);
        }
        long x = count(events, "X");
        assertThat(x).isEqualTo(zones);
        assertThat(count(events, "C")).isEqualTo(samples);
        assertThat(count(events, "i")).isEqualTo(r.endFrame() - r.firstFrame());
        assertThat(count(events, "M")).isEqualTo(1 + 2L * r.lanes());
        Matcher m = Pattern.compile("\"name\":\"Hitch 200\"").matcher(json);
        assertThat(m.find()).isTrue();
    }

    private static long count(List<Object> events, String phase) {
        return events.stream().filter(e -> phase.equals(((Map<?, ?>) e).get("ph"))).count();
    }

    /** A small strict JSON reader: enough to show the export is well formed. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            Object v = read();
            skip();
            if (i != s.length()) {
                throw new IllegalStateException("trailing text at " + i);
            }
            return v;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object read() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> map = new HashMap<>();
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    skip();
                    String key = string();
                    skip();
                    expect(':');
                    map.put(key, read());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }
            if (c == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(read());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw new IllegalStateException("unexpected " + c + " at " + i);
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c < 0x20) {
                    throw new IllegalStateException("control character in a string at " + i);
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> b.append(e);
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalStateException("bad escape at " + i);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalStateException("expected " + c + " at " + i + ": " + s.substring(i,
                    Math.min(s.length(), i + 40)));
            }
            i++;
        }
    }

    @Test
    void frameTimesStatisticsMatchSorting() {
        FrameTimes times = new FrameTimes(new com.truesim.common.state.ChangeClock(), 300);
        SplittableRandom random = new SplittableRandom(5);
        double[] cpu = new double[1000];
        for (int f = 0; f < 1000; f++) {
            cpu[f] = 2 + random.nextDouble() * 10;
            times.frame(f, cpu[f], 0.5, 16.6, f % 100 == 0);
        }
        double[] kept = Arrays.copyOfRange(cpu, 700, 1000);
        Arrays.sort(kept);
        FrameTimes.Stats s = times.stats(FrameTimes.Kind.CPU, new FrameTimes.Stats());
        assertThat(s.count).isEqualTo(300);
        assertThat(s.min).isEqualTo(kept[0]);
        assertThat(s.max).isEqualTo(kept[299]);
        assertThat(s.p99).isEqualTo(kept[296]);
        assertThat(s.avg).isCloseTo(Arrays.stream(kept).average().orElseThrow(), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(times.hitchesKept()).isEqualTo(3);
        assertThat(times.stats(FrameTimes.Kind.GPU, new FrameTimes.Stats()).count).isZero();
        for (int k = 0; k < 50; k++) {
            double[] a = new double[1 + random.nextInt(50)];
            for (int i = 0; i < a.length; i++) {
                a[i] = random.nextInt(10);
            }
            double[] sorted = a.clone();
            Arrays.sort(sorted);
            int pick = random.nextInt(a.length);
            assertThat(FrameTimes.select(a, a.length, pick)).isEqualTo(sorted[pick]);
        }
    }

    @Test
    void unitsReadAsTheyShould() {
        assertThat(Units.duration(new StringBuilder(), 850).toString()).isEqualTo("850 ns");
        assertThat(Units.duration(new StringBuilder(), 12_400).toString()).isEqualTo("12.4 us");
        assertThat(Units.duration(new StringBuilder(), 3_210_000).toString()).isEqualTo("3.21 ms");
        assertThat(Units.duration(new StringBuilder(), 1_500_000_000).toString()).isEqualTo("1.5 s");
        assertThat(Units.value(new StringBuilder(), 3.5 * 1024 * 1024, "bytes").toString()).isEqualTo("3.5 MiB");
        assertThat(Units.value(new StringBuilder(), 42.25, "%").toString()).isEqualTo("42.2 %");
        assertThat(Units.value(new StringBuilder(), Double.NaN, "%").toString()).isEqualTo("-");
    }
}
