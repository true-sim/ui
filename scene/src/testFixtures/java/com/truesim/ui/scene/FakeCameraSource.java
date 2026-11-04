package com.truesim.ui.scene;

import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;

/**
 * A {@link CameraViewSource} without an engine, for tests and galleries: "renders" a map-like picture (terrain bands,
 * a grid, a road and a lake) into a CPU texture at the requested size, one frame after the request as an engine with a
 * frame in flight would, so the newest finished image lags a resize by a frame.
 */
public final class FakeCameraSource implements CameraViewSource {

    private final UiTexture texture;
    private int width;
    private int height;
    private int wantedWidth;
    private int wantedHeight;
    private long version;
    private int requests;

    /** A source rendering into a texture of {@code maxWidth x maxHeight} made in {@code textures}. */
    public FakeCameraSource(UiTextures textures, int maxWidth, int maxHeight) {
        texture = textures.create("fake camera view", maxWidth, maxHeight, UiTexture.Format.RGBA8_SRGB);
    }

    @Override
    public void request(int widthPx, int heightPx) {
        requests++;
        // the image asked for last frame finishes now
        if (wantedWidth > 0 && (wantedWidth != width || wantedHeight != height)) {
            render(wantedWidth, wantedHeight);
        }
        wantedWidth = Math.min(widthPx, texture.width());
        wantedHeight = Math.min(heightPx, texture.height());
    }

    /** How often the size was asked for. */
    public int requests() {
        return requests;
    }

    private void render(int w, int h) {
        byte[] p = texture.pixels();
        int stride = texture.width() * 4;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float u = x / (float) w;
                float v = y / (float) h;
                int r = (int) (60 + 50 * v);
                int g = (int) (110 + 60 * (1 - v) * u);
                int b = 70;
                float lake = (u - 0.7f) * (u - 0.7f) + (v - 0.3f) * (v - 0.3f) * 1.6f;
                if (lake < 0.02f) {
                    r = 40;
                    g = 90;
                    b = 170;
                }
                if (Math.abs(v - 0.62f - 0.12f * (float) Math.sin(u * 7)) < 0.012f) {
                    r = 200;
                    g = 180;
                    b = 120;
                }
                if (x % 32 == 0 || y % 32 == 0) {
                    r = r * 3 / 4;
                    g = g * 3 / 4;
                    b = b * 3 / 4;
                }
                int o = y * stride + x * 4;
                p[o] = (byte) r;
                p[o + 1] = (byte) g;
                p[o + 2] = (byte) b;
                p[o + 3] = (byte) 255;
            }
        }
        texture.markDirty(0, 0, w, h);
        width = w;
        height = h;
        version++;
    }

    @Override
    public UiTexture texture() {
        return version == 0 ? null : texture;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public long version() {
        return version;
    }
}
