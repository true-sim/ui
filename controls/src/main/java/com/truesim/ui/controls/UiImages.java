package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The images styles name ({@code image: "frame"}, {@code nine-slice("frame", 12 12 12 12)}): each one an sRGB
 * {@link UiTexture} of its own, registered by name when loaded (an asset type later, D16). Lookups are by name at
 * style application, never per frame.
 */
public final class UiImages {

    private final UiTextures textures;
    private final Map<String, UiTexture> byName = new HashMap<>();

    /** Images made in {@code textures}. */
    @AllowAllocation("one per UI")
    public UiImages(UiTextures textures) {
        this.textures = textures;
    }

    /** Registers {@code image} as {@code name}; returns its texture. */
    @AllowAllocation("images are loaded, not made per frame")
    public UiTexture add(String name, BufferedImage image) {
        UiTexture texture = textures.create(name, image.getWidth(), image.getHeight(), UiTexture.Format.RGBA8_SRGB);
        byte[] pixels = texture.pixels();
        int w = image.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int o = (y * w + x) * 4;
                pixels[o] = (byte) (argb >>> 16);
                pixels[o + 1] = (byte) (argb >>> 8);
                pixels[o + 2] = (byte) argb;
                pixels[o + 3] = (byte) (argb >>> 24);
            }
        }
        texture.markDirty(0, 0, texture.width(), texture.height());
        byName.put(name, texture);
        return texture;
    }

    /** Loads a PNG (or any format ImageIO reads) as {@code name}. */
    @AllowAllocation("images are loaded, not made per frame")
    public UiTexture load(String name, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("not an image: " + file);
            }
            return add(name, image);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The image named {@code name}, or null. */
    public UiTexture get(String name) {
        return name == null ? null : byName.get(name);
    }
}
