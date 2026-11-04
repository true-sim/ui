package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The profiler's own file format ({@code .tsprof}): a {@link Recording} as it is, so a capture opens again in the
 * profiler view with every lane, zone, frame and counter. Big-endian, as {@link DataOutputStream} writes:
 *
 * <pre>
 * "TSPROF" u16 version (1)
 * u32 names,   each: UTF name
 * u32 lanes,   each: UTF name, u8 kind, u64 zones, each: i64 start, i64 end (Long.MAX_VALUE: open), u32 name, u16 depth
 * u64 frames,  each: i64 number, i64 start, i64 end, i64 wait, i64 gpu start, i64 gpu end, u8 hitch
 * u32 counters, each: UTF name, UTF unit, u64 samples, each: i64 time, f64 value
 * </pre>
 */
public final class ProfileFile {

    private static final byte[] MAGIC = {'T', 'S', 'P', 'R', 'O', 'F'};
    private static final int VERSION = 1;

    private ProfileFile() {
    }

    /** Writes {@code recording} to {@code out} (not closed). */
    @AllowAllocation("saving, on request")
    public static void write(Recording recording, OutputStream out) throws IOException {
        DataOutputStream d = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
        d.write(MAGIC);
        d.writeShort(VERSION);
        d.writeInt(recording.names());
        for (int i = 0; i < recording.names(); i++) {
            d.writeUTF(recording.nameOf(i));
        }
        d.writeInt(recording.lanes());
        for (int l = 0; l < recording.lanes(); l++) {
            d.writeUTF(recording.laneName(l));
            d.writeByte(recording.laneKind(l).ordinal());
            long first = recording.firstZone(l);
            long end = recording.endZone(l);
            d.writeLong(end - first);
            for (long z = first; z < end; z++) {
                d.writeLong(recording.zoneStart(l, z));
                d.writeLong(recording.zoneEnd(l, z));
                d.writeInt(recording.zoneName(l, z));
                d.writeShort(recording.zoneDepth(l, z));
            }
        }
        long first = recording.firstFrame();
        long end = recording.endFrame();
        d.writeLong(end - first);
        for (long f = first; f < end; f++) {
            d.writeLong(f);
            d.writeLong(recording.frameStart(f));
            d.writeLong(recording.frameEnd(f));
            d.writeLong(recording.frameWait(f));
            d.writeLong(recording.frameGpuStart(f));
            d.writeLong(recording.frameGpuEnd(f));
            d.writeByte(recording.frameHitch(f) ? 1 : 0);
        }
        d.writeInt(recording.counters());
        for (int c = 0; c < recording.counters(); c++) {
            d.writeUTF(recording.counterName(c));
            d.writeUTF(recording.counterUnit(c));
            long s0 = recording.firstSample(c);
            long s1 = recording.endSample(c);
            d.writeLong(s1 - s0);
            for (long i = s0; i < s1; i++) {
                d.writeLong(recording.sampleTime(c, i));
                d.writeDouble(recording.sampleValue(c, i));
            }
        }
        d.flush();
    }

    /** Reads a recording written by {@link #write}. */
    @AllowAllocation("loading, on request")
    public static Recording read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[MAGIC.length];
        d.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("Not a true-sim profile");
        }
        int version = d.readUnsignedShort();
        if (version != VERSION) {
            throw new IOException("Unknown profile version " + version);
        }
        int names = d.readInt();
        String[] text = new String[names];
        for (int i = 0; i < names; i++) {
            text[i] = d.readUTF();
        }
        // lanes are read before their sizes are known: size the rings to hold them
        java.util.List<Object[]> lanes = new java.util.ArrayList<>();
        int laneCount = d.readInt();
        long most = 16;
        for (int l = 0; l < laneCount; l++) {
            String name = d.readUTF();
            LaneKind kind = LaneKind.of(d.readUnsignedByte());
            long n = d.readLong();
            long[] zones = new long[(int) n * 4];
            for (int z = 0; z < n; z++) {
                zones[4 * z] = d.readLong();
                zones[4 * z + 1] = d.readLong();
                zones[4 * z + 2] = d.readInt();
                zones[4 * z + 3] = d.readUnsignedShort();
            }
            most = Math.max(most, n);
            lanes.add(new Object[] {name, kind, zones});
        }
        long frames = d.readLong();
        long[][] frameData = new long[(int) frames][];
        boolean[] hitches = new boolean[(int) frames];
        for (int f = 0; f < frames; f++) {
            frameData[f] = new long[] {d.readLong(), d.readLong(), d.readLong(), d.readLong(), d.readLong(),
                d.readLong()};
            hitches[f] = d.readUnsignedByte() != 0;
        }
        int counters = d.readInt();
        String[] counterNames = new String[counters];
        String[] units = new String[counters];
        long[][] times = new long[counters][];
        double[][] values = new double[counters][];
        long samples = 16;
        for (int c = 0; c < counters; c++) {
            counterNames[c] = d.readUTF();
            units[c] = d.readUTF();
            int n = (int) d.readLong();
            times[c] = new long[n];
            values[c] = new double[n];
            for (int i = 0; i < n; i++) {
                times[c][i] = d.readLong();
                values[c][i] = d.readDouble();
            }
            samples = Math.max(samples, n);
        }
        Recording r = new Recording((int) Math.min(1 << 30, most), (int) Math.max(16, frames), (int) samples);
        for (String t : text) {
            r.name(t);
        }
        for (Object[] lane : lanes) {
            int l = r.lane((String) lane[0], (LaneKind) lane[1]);
            long[] zones = (long[]) lane[2];
            for (int z = 0; z < zones.length / 4; z++) {
                long start = zones[4 * z];
                long end = zones[4 * z + 1];
                long index = r.open(l, start, (int) zones[4 * z + 2], (int) zones[4 * z + 3]);
                if (end != Recording.OPEN) {
                    r.close(l, index, end);
                }
            }
        }
        for (int f = 0; f < frames; f++) {
            long[] fd = frameData[f];
            r.frame(fd[0], fd[1], fd[2], fd[3], hitches[f]);
            if (fd[5] != 0) {
                r.gpuFrame(fd[0], fd[4], fd[5]);
            }
        }
        for (int c = 0; c < counters; c++) {
            int id = r.counter(counterNames[c], units[c]);
            for (int i = 0; i < times[c].length; i++) {
                r.sample(id, times[c][i], values[c][i]);
            }
        }
        r.commit();
        return r;
    }
}
