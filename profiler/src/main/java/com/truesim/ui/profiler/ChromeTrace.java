package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.Writer;

/**
 * A {@link Recording} as Chrome's trace event JSON (the "JSON Object Format" that {@code chrome://tracing},
 * Perfetto's UI and Speedscope open): each lane a thread of process 1 named by a {@code thread_name} metadata event
 * (GPU queues and marker lanes too, sorted after the threads), each finished zone a complete event ({@code "ph":"X"})
 * with its start and duration in microseconds from the recording's start, each frame an instant event on the first
 * lane ({@code "Frame n"}, hitches named {@code "Hitch"}), each counter sample a counter event ({@code "ph":"C"}).
 */
public final class ChromeTrace {

    private ChromeTrace() {
    }

    /** Writes {@code recording} to {@code out} (not closed). */
    @AllowAllocation("exporting, on request")
    public static void write(Recording recording, Writer out) throws IOException {
        long origin = recording.startTime();
        if (origin == Long.MAX_VALUE) {
            origin = 0;
        }
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("{\"displayTimeUnit\":\"ns\",\"otherData\":{\"producer\":\"true-sim profiler\"},\"traceEvents\":[\n");
        boolean first = true;
        b.append("{\"ph\":\"M\",\"pid\":1,\"name\":\"process_name\",\"args\":{\"name\":\"true-sim\"}}");
        first = false;
        for (int l = 0; l < recording.lanes(); l++) {
            b.append(",\n{\"ph\":\"M\",\"pid\":1,\"tid\":").append(l + 1)
                .append(",\"name\":\"thread_name\",\"args\":{\"name\":");
            string(b, recording.laneName(l));
            b.append("}},\n{\"ph\":\"M\",\"pid\":1,\"tid\":").append(l + 1)
                .append(",\"name\":\"thread_sort_index\",\"args\":{\"sort_index\":")
                .append(recording.laneKind(l).ordinal() * 1000 + l).append("}}");
        }
        for (int l = 0; l < recording.lanes(); l++) {
            String category = switch (recording.laneKind(l)) {
                case THREAD -> "cpu";
                case GPU -> "gpu";
                case MARKERS -> "runtime";
            };
            for (long z = recording.firstZone(l), end = recording.endZone(l); z < end; z++) {
                long s = recording.zoneStart(l, z);
                long e = recording.zoneEnd(l, z);
                if (e == Recording.OPEN) {
                    continue;
                }
                b.append(first ? "" : ",\n").append("{\"ph\":\"X\",\"pid\":1,\"tid\":").append(l + 1)
                    .append(",\"cat\":\"").append(category).append("\",\"name\":");
                string(b, recording.nameOf(recording.zoneName(l, z)));
                b.append(",\"ts\":");
                micros(b, s - origin);
                b.append(",\"dur\":");
                micros(b, Math.max(0, e - s));
                b.append('}');
                first = false;
                if (b.length() > 1 << 20) {
                    out.append(b);
                    b.setLength(0);
                }
            }
        }
        for (long f = recording.firstFrame(), end = recording.endFrame(); f < end; f++) {
            b.append(",\n{\"ph\":\"i\",\"s\":\"g\",\"pid\":1,\"tid\":1,\"cat\":\"frame\",\"name\":\"")
                .append(recording.frameHitch(f) ? "Hitch " : "Frame ").append(f).append("\",\"ts\":");
            micros(b, recording.frameStart(f) - origin);
            b.append('}');
        }
        for (int c = 0; c < recording.counters(); c++) {
            for (long i = recording.firstSample(c), end = recording.endSample(c); i < end; i++) {
                double v = recording.sampleValue(c, i);
                if (v != v || Double.isInfinite(v)) {
                    continue;
                }
                b.append(",\n{\"ph\":\"C\",\"pid\":1,\"name\":");
                string(b, recording.counterName(c));
                b.append(",\"ts\":");
                micros(b, recording.sampleTime(c, i) - origin);
                b.append(",\"args\":{");
                string(b, recording.counterUnit(c).isEmpty() ? "value" : recording.counterUnit(c));
                b.append(':').append(v).append("}}");
                if (b.length() > 1 << 20) {
                    out.append(b);
                    b.setLength(0);
                }
            }
        }
        b.append("\n]}\n");
        out.append(b);
        out.flush();
    }

    private static void micros(StringBuilder b, long nanos) {
        b.append(nanos / 1000).append('.');
        long frac = Math.abs(nanos % 1000);
        if (frac < 100) {
            b.append('0');
        }
        if (frac < 10) {
            b.append('0');
        }
        b.append(frac);
    }

    /** Appends {@code s} as a JSON string. */
    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append("\\u00");
                        b.append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
