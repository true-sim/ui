package com.truesim.ui.core.event;

import com.truesim.ui.core.Element;

/**
 * One drag and drop in progress (design 4.7): a typed payload under {@link DataFormat} keys, the source element, the
 * operations the source allows, an optional preview element that follows the pointer, and the drop target found
 * through the same picking as every pointer event. Started by a {@link EventType#DRAG_START} listener with
 * {@link UiEvent#startDrag}; reused per pointer, so a session costs nothing per frame.
 *
 * <pre>{@code
 * interaction.on(slot, EventType.DRAG_START, e -> e.startDrag(DragOperation.MOVE | DragOperation.COPY)
 *     .put(ITEM, item).preview(icon, 8, 8));
 * interaction.on(bag, EventType.DROP_OVER, e -> { if (e.drag().has(ITEM)) e.acceptDrop(DragOperation.MOVE); });
 * interaction.on(bag, EventType.DROP, e -> inventory.move(e.drag().get(ITEM), e.dropOperation()));
 * }</pre>
 */
public final class DragSession {

    /** The most formats one payload carries. */
    public static final int MAX_FORMATS = 8;

    private final DataFormat<?>[] formats = new DataFormat<?>[MAX_FORMATS];
    private final Object[] values = new Object[MAX_FORMATS];
    private int formatCount;
    private boolean active;
    private int pointerId;
    private Element source;
    private int allowed;
    private int operation;
    private Element target;
    private Element preview;
    private float previewX;
    private float previewY;
    private float x;
    private float y;

    void begin(int pointer, Element from, int allowedOperations, float atX, float atY) {
        clear();
        active = true;
        pointerId = pointer;
        source = from;
        allowed = allowedOperations;
        x = atX;
        y = atY;
    }

    void clear() {
        for (int i = 0; i < formatCount; i++) {
            formats[i] = null;
            values[i] = null;
        }
        formatCount = 0;
        active = false;
        source = null;
        target = null;
        preview = null;
        operation = DragOperation.NONE;
        allowed = DragOperation.NONE;
    }

    /** Adds the payload {@code value} in {@code format} (one value per format); returns this. */
    public <T> DragSession put(DataFormat<T> format, T value) {
        for (int i = 0; i < formatCount; i++) {
            if (formats[i] == format) {
                values[i] = value;
                return this;
            }
        }
        if (formatCount == MAX_FORMATS) {
            throw new IllegalStateException("a drag carries at most " + MAX_FORMATS + " formats");
        }
        formats[formatCount] = format;
        values[formatCount++] = value;
        return this;
    }

    /** Whether the payload has {@code format}. */
    public boolean has(DataFormat<?> format) {
        for (int i = 0; i < formatCount; i++) {
            if (formats[i] == format) {
                return true;
            }
        }
        return false;
    }

    /** The payload in {@code format}, or null. */
    public <T> T get(DataFormat<T> format) {
        for (int i = 0; i < formatCount; i++) {
            if (formats[i] == format) {
                return format.type().cast(values[i]);
            }
        }
        return null;
    }

    /**
     * Shows {@code element} under the pointer while dragging, its top-left {@code offsetX, offsetY} UI pixels up and
     * left of the pointer. The UI frame moves it to the {@code drag} layer and positions it each frame; it is taken
     * out of that layer when the drag ends. Returns this.
     */
    public DragSession preview(Element element, float offsetX, float offsetY) {
        preview = element;
        previewX = offsetX;
        previewY = offsetY;
        return this;
    }

    /** Whether a drag is in progress. */
    public boolean active() {
        return active;
    }

    /** The dragging pointer's id. */
    public int pointerId() {
        return pointerId;
    }

    /** The element the drag started on. */
    public Element source() {
        return source;
    }

    /** The operations the source allows ({@link DragOperation} bits). */
    public int allowed() {
        return allowed;
    }

    /** What a drop now would do: one {@link DragOperation}, or NONE when no target accepts the payload. */
    public int operation() {
        return operation;
    }

    void operation(int value) {
        operation = value;
    }

    /** The element that accepted the payload under the pointer, or null. */
    public Element target() {
        return target;
    }

    void target(Element value) {
        target = value;
    }

    /** The preview element, or null. */
    public Element preview() {
        return preview;
    }

    /** The preview's offset left of the pointer. */
    public float previewOffsetX() {
        return previewX;
    }

    /** The preview's offset above the pointer. */
    public float previewOffsetY() {
        return previewY;
    }

    /** The pointer's position in UI pixels. */
    public float x() {
        return x;
    }

    /** The pointer's position in UI pixels. */
    public float y() {
        return y;
    }

    void moveTo(float newX, float newY) {
        x = newX;
        y = newY;
    }
}
