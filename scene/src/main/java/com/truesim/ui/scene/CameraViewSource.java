package com.truesim.ui.scene;

import com.truesim.ui.core.draw.UiTexture;

/**
 * Where a {@link CameraView} gets its picture: the engine renders a second camera (a minimap, a rear-view mirror, a
 * security camera) into a texture the UI draws. The control asks for its pixel size each frame ({@link #request}); the
 * engine renders that view in the same frame graph as the main view, so the main view waits for nothing, and hands
 * over each finished image ({@link #texture}, {@link #version}). With frames in flight the image shown is the newest
 * finished one, possibly of an earlier size while the control resizes; the control stretches it over its content box.
 * The texture's {@link UiTexture#id() id} is what the draw list names: the engine's renderer binds its render target
 * for it instead of uploading pixels.
 */
public interface CameraViewSource {

    /** The control's content size in pixels this frame: render at this size from now on. Called every frame. */
    void request(int widthPx, int heightPx);

    /** The texture holding the newest finished image, or null before the first one. */
    UiTexture texture();

    /** The finished image's width in texels (its region of the texture starts at the top-left). */
    int width();

    /** The finished image's height in texels. */
    int height();

    /** Grows with every finished image: the control records again. */
    long version();
}
