package com.truesim.ui.core.draw;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * The textures of one UI, by id: atlas pages and images. Ids start at 1; 0 means "no texture". The renderer walks
 * ids 1 to {@link #count()} each frame and uploads those {@link UiTexture#dirty() dirty}.
 */
public final class UiTextures {

    private final List<UiTexture> textures = new ArrayList<>();

    /** No textures yet. */
    @AllowAllocation("one per UI")
    public UiTextures() {
        textures.add(null); // id 0: none
    }

    /** A new texture, all transparent black and dirty. */
    @AllowAllocation("textures are made when loaded")
    public synchronized UiTexture create(String name, int width, int height, UiTexture.Format format) {
        UiTexture texture = new UiTexture(textures.size(), name, width, height, format);
        textures.add(texture);
        return texture;
    }

    /** The texture {@code id}. */
    public synchronized UiTexture get(int id) {
        if (id <= 0 || id >= textures.size()) {
            throw new IllegalArgumentException("no texture " + id);
        }
        return textures.get(id);
    }

    /** How many ids are taken, 0 included: ids are below this. */
    public synchronized int count() {
        return textures.size();
    }
}
