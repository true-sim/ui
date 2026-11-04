package com.truesim.ui.core.input;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;

/**
 * A {@link ScreenSampler} for tests and galleries: the "screen" is a pattern (a function of the pixel), and each
 * request is answered {@link #latency} polls after it was made, as a GPU read-back's would (a poll hands over the newest
 * answer due). The default pattern is an HDR
 * test card: hue across, intensity down (0 at the top to 4 times paper white at 1000 pixels).
 */
public final class PatternScreenSampler implements ScreenSampler {

    /** The colour of the screen at a pixel: packed linear {@link Rgba}. */
    @FunctionalInterface
    public interface Pattern {
        /** The colour at UI pixel {@code x, y}. */
        long color(int x, int y);
    }

    private final Pattern pattern;
    private final ScreenSample answer = new ScreenSample();
    private int latency = 1;
    private boolean available = true;
    private static final int QUEUE = 8;
    private final float[] qx = new float[QUEUE];
    private final float[] qy = new float[QUEUE];
    private final int[] qsize = new int[QUEUE];
    private final long[] due = new long[QUEUE];
    private int queued;
    private long polls;
    private long sequence;
    private int requests;

    /** The default HDR test card. */
    @AllowAllocation("one per test")
    public PatternScreenSampler() {
        this(PatternScreenSampler::card);
    }

    /** Samples {@code pattern}. */
    @AllowAllocation("one per test")
    public PatternScreenSampler(Pattern pattern) {
        this.pattern = pattern;
    }

    /** Polls after the next one an answer takes to arrive (0: the next poll has it); returns this. */
    public PatternScreenSampler latency(int polls) {
        latency = Math.max(0, polls);
        return this;
    }

    /** Whether sampling is available (false: every request fails); returns this. */
    public PatternScreenSampler available(boolean on) {
        available = on;
        return this;
    }

    /** Requests so far. */
    public int requests() {
        return requests;
    }

    /**
     * The default pattern: hue across (a turn per 600 pixels), intensity down (4 at 1000 pixels), every other pixel a
     * fifth darker (a checker, so a magnifier shows its pixels).
     */
    public static long card(int x, int y) {
        float h = Math.floorMod(x, 600) / 600f * 6;
        int sector = (int) h;
        float f = h - sector;
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = 1; g = f; b = 0; }
            case 1 -> { r = 1 - f; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = f; }
            case 3 -> { r = 0; g = 1 - f; b = 1; }
            case 4 -> { r = f; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = 1 - f; }
        }
        float k = Math.max(0, y) / 250f * ((x + y & 1) == 0 ? 1 : 0.8f); // a fine checker: a magnifier shows pixels
        return Rgba.pack(r * k, g * k, b * k, 1);
    }

    @Override
    public boolean request(float x, float y, int blockSize) {
        if (!available) {
            return false;
        }
        requests++;
        if (queued == QUEUE) { // the oldest goes: a real read-back answers only the newest anyway
            System.arraycopy(qx, 1, qx, 0, QUEUE - 1);
            System.arraycopy(qy, 1, qy, 0, QUEUE - 1);
            System.arraycopy(qsize, 1, qsize, 0, QUEUE - 1);
            System.arraycopy(due, 1, due, 0, QUEUE - 1);
            queued--;
        }
        qx[queued] = x;
        qy[queued] = y;
        qsize[queued] = Math.max(1, Math.min(MAX_SIZE, blockSize | 1));
        due[queued] = polls + 1 + latency;
        queued++;
        return true;
    }

    @Override
    public boolean poll(ScreenSample out) {
        polls++;
        int ready = -1;
        for (int i = 0; i < queued; i++) {
            if (due[i] <= polls) {
                ready = i; // the newest answer due: older ones are superseded
            }
        }
        if (ready >= 0) {
            float px = qx[ready];
            float py = qy[ready];
            int size = qsize[ready];
            int left = queued - ready - 1;
            System.arraycopy(qx, ready + 1, qx, 0, left);
            System.arraycopy(qy, ready + 1, qy, 0, left);
            System.arraycopy(qsize, ready + 1, qsize, 0, left);
            System.arraycopy(due, ready + 1, due, 0, left);
            queued = left;
            answer.set(++sequence, px, py, size);
            int half = size / 2;
            int cx = (int) Math.floor(px);
            int cy = (int) Math.floor(py);
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    long c = pattern.color(cx - half + col, cy - half + row);
                    answer.pixel(col, row, Rgba.r(c), Rgba.g(c), Rgba.b(c));
                }
            }
        }
        if (answer.sequence() > out.sequence()) {
            out.copy(answer);
            return true;
        }
        return false;
    }

    @Override
    public void stop() {
        queued = 0;
    }
}
