package com.truesim.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.Overlay;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.Styles;

/**
 * A minimap or picture-in-picture view (plan additions: a second camera rendered into a UI image): an element of type
 * {@code camera-view} showing what a {@link CameraViewSource} renders from its own camera ({@link #view()}). Its frame
 * (border, radius, the background behind the picture, shadow) comes from the theme like any element's; the picture
 * fills the content box, tinted by the style's {@code color}.
 *
 * <p>Pointers over it pick through its camera: with a {@link ScenePicking}, a click on the minimap reaches the scene
 * object under it (the view's ray through the pointer) with the same listeners as in the main view, and listeners on
 * the element read the ray with {@link ScenePicking#ray}. Markers (the player's position, objectives) are overlays
 * anchored through the view: {@link #anchor()}, {@link #marker}.
 *
 * <pre>{@code
 * CameraView minimap = new CameraView(ui, renderedMap, picking, "minimap");
 * hud.add(minimap.element());
 * minimap.view().orthographic(400, 1, 2000).pose(px, 500, pz, ...);   // each frame, the engine's map camera
 * minimap.marker(overlays, playerDot).node(scene, player);
 * }</pre>
 *
 * <p>Nothing is recorded again unless a new image arrived or the element moved; a frame allocates nothing.
 */
public final class CameraView implements Painter, Ui.FrameListener {

    private final Ui ui;
    private final CameraViewSource source;
    private final ScenePicking picking;
    private final Element element;
    private final SceneView view = new SceneView();
    private long shownVersion = -1;
    private int requestedWidth;
    private int requestedHeight;
    private boolean placed;

    /**
     * A camera view of {@code ui} showing {@code source}, with style name {@code style} (or null); with a
     * {@code scenePicking} (or null) pointers over it pick through its camera.
     */
    @AllowAllocation("controls are made when a UI is built")
    public CameraView(Ui ui, CameraViewSource source, ScenePicking scenePicking, String style) {
        this.ui = ui;
        this.source = source;
        this.picking = scenePicking;
        this.element = ui.element("camera-view", style);
        ui.paint(element, this);
        if (scenePicking != null) {
            scenePicking.view(element, view);
        }
        ui.addFrameListener(this);
    }

    /** The element: add it where the view shows. */
    public Element element() {
        return element;
    }

    /** The view's camera: the engine poses it each frame; its viewport follows the element. */
    public SceneView view() {
        return view;
    }

    /** The picture's source. */
    public CameraViewSource source() {
        return source;
    }

    /** Whether the element was laid out and recorded once (its viewport is known). */
    public boolean placed() {
        return placed;
    }

    /** A new anchor projecting through this view, for overlays that mark things on it. */
    @AllowAllocation("anchors are made when a UI is built")
    public SceneAnchor anchor() {
        return new SceneAnchor(view);
    }

    /**
     * A marker: {@code content} shown on the {@code overlay} layer at what the returned anchor names (place it with
     * {@link SceneAnchor#point} or {@link SceneAnchor#node}), hidden while that is off this view.
     */
    @AllowAllocation("markers are made when a UI is built")
    public SceneAnchor marker(Overlays overlays, Element content) {
        SceneAnchor a = anchor();
        Overlay o = overlays.create(content, ui.layer(Layers.OVERLAY)).anchor(a);
        o.show();
        return a;
    }

    /** Takes the view off the UI's frame and picking; the element stays for the caller to destroy. */
    public void dispose() {
        ui.removeFrameListener(this);
        if (picking != null) {
            picking.view(element, null);
        }
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void frame(long nanos) {
        if (source.version() != shownVersion) {
            ui.repaint(element);
        }
    }

    @Override
    public void afterLayout() {
        // the size to render at: the content box in pixels, asked for every frame
        if (requestedWidth > 0 && requestedHeight > 0) {
            source.request(requestedWidth, requestedHeight);
        }
    }

    // ---------------------------------------------------------------- the painter

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        // as large as its style or its container makes it: the picture has no size of its own
        return Measure.size(widthMode == Measure.UNDEFINED ? 0 : width, heightMode == Measure.UNDEFINED ? 0 : height);
    }

    @Override
    public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        if (x1 > x0 && y1 > y0) {
            view.viewport(x0, y0, x1 - x0, y1 - y0);
            placed = true;
            requestedWidth = Math.round(x1 - x0);
            requestedHeight = Math.round(y1 - y0);
        }
        shownVersion = source.version();
        UiTexture texture = source.texture();
        if (texture == null || source.width() <= 0 || source.height() <= 0) {
            return;
        }
        long tint = Rgba.fade(ui.styles().styleOf(slot).get(Styles.COLOR).rgba(), opacity);
        float u1 = Math.min(1, source.width() / (float) texture.width());
        float v1 = Math.min(1, source.height() / (float) texture.height());
        list.image(x0, y0, x1, y1, 0, 0, u1, v1, texture.id(), tint);
    }

    @Override
    public int spare(int recorded) {
        return 1;
    }
}
