package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.IconFace;
import com.truesim.ui.text.SvgIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * The images styles name ({@code image: "frame"}, {@code nine-slice("frame", 12 12 12 12)}): each one an sRGB
 * {@link UiTexture} of its own, registered by name when loaded (an asset type later, D16). Lookups are by name at
 * style application, never per frame. The toolkit's own chrome icons ({@link BuiltinIcons}) are there without
 * registering anything.
 *
 * <p><b>SVG icons</b> ({@link #addSvg}, {@link #loadSvg}, or an {@code .svg} resource of the name found on the
 * classpath: {@code image: "icons/gear"} finds {@code icons/gear.svg}) are not textures: their filled shapes
 * ({@link SvgIcon}) are glyphs of an {@link IconFace} in the text atlas, MTSDF like text, crisp at any size and tinted
 * by the style's image colour like text. A registered image or a built-in icon of the same name comes first.
 */
public final class UiImages {

    private final UiTextures textures;
    private final Map<String, UiTexture> byName = new HashMap<>();
    private final IconFace icons = new IconFace();
    private final Map<String, Integer> iconByName = new HashMap<>();
    private final Set<String> noIcon = new HashSet<>(); // names whose .svg resource was looked for and not found
    private ClassLoader resources = UiImages.class.getClassLoader();

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

    /**
     * The image named {@code name}, or null: a registered one, else a {@linkplain BuiltinIcons built-in icon} of that
     * name (rendered the first time it is asked for).
     */
    public UiTexture get(String name) {
        if (name == null) {
            return null;
        }
        UiTexture t = byName.get(name);
        if (t == null) {
            int icon = BuiltinIcons.find(name);
            if (icon >= 0) {
                t = builtin(name, icon);
            }
        }
        return t;
    }

    /** Registers the SVG document {@code svg} as icon {@code name}; returns its glyph in {@link #iconFace()}. */
    @AllowAllocation("icons are loaded, not made per frame")
    public int addSvg(String name, String svg) {
        int glyph = icons.add(SvgIcon.parse(svg));
        iconByName.put(name, glyph);
        noIcon.remove(name);
        return glyph;
    }

    /** Loads an SVG file as icon {@code name}. */
    @AllowAllocation("icons are loaded, not made per frame")
    public int loadSvg(String name, Path file) {
        try {
            return addSvg(name, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Where {@code <name>.svg} resources are looked for (this class's loader by default). */
    public void svgResources(ClassLoader loader) {
        resources = loader;
        noIcon.clear();
    }

    /**
     * The glyph of SVG icon {@code name} in {@link #iconFace()}, or -1: a registered one, else the classpath resource
     * {@code name + ".svg"}, loaded the first time (a name without one is remembered, so a miss costs a lookup).
     * A registered image or built-in icon of that name is not an SVG icon.
     */
    public int icon(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        Integer glyph = iconByName.get(name);
        if (glyph != null) {
            return glyph;
        }
        if (byName.containsKey(name) || noIcon.contains(name) || BuiltinIcons.find(name) >= 0) {
            return -1;
        }
        return loadResource(name);
    }

    @AllowAllocation("an SVG resource is read once, the first time a style names it")
    private int loadResource(String name) {
        try (InputStream in = resources == null ? null : resources.getResourceAsStream(name + ".svg")) {
            if (in == null) {
                noIcon.add(name);
                return -1;
            }
            return addSvg(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SVG icon {@code glyph}'s shapes and size. */
    public SvgIcon svg(int glyph) {
        return icons.icon(glyph);
    }

    /** The face whose glyphs the SVG icons are, for the text atlas. */
    public IconFace iconFace() {
        return icons;
    }

    @AllowAllocation("a built-in icon is rendered once, the first time a style names it")
    private UiTexture builtin(String name, int icon) {
        UiTexture texture = textures.create(name, BuiltinIcons.SIZE, BuiltinIcons.SIZE, UiTexture.Format.RGBA8_SRGB);
        System.arraycopy(BuiltinIcons.draw(icon), 0, texture.pixels(), 0, texture.pixels().length);
        texture.markDirty(0, 0, texture.width(), texture.height());
        byName.put(name, texture);
        return texture;
    }
}
