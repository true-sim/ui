package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.style.ResolvedStyle;

/**
 * Something on a chart the user drags (ImPlot's drag tools) to edit data or set a query range: its values are
 * {@code common-state} {@link com.truesim.common.state.VersionedDouble}s, bound two-way (the game sets them, the user
 * drags them; the chart shows both). Styled by its part, which shows {@code :hover} and {@code :pressed}; grabbed
 * within {@code ui.chart.grabPixels}.
 */
public abstract class DragTool {

    protected final Chart chart;
    protected Axis xAxis;
    protected Axis yAxis;
    private final Element part;
    private final Element hoverPart;
    private final Element pressedPart;
    private boolean enabled = true;
    private boolean hovered;
    private boolean pressed;
    private long seen = Long.MIN_VALUE;

    @AllowAllocation("drag tools are made when a chart is built")
    protected DragTool(Chart chart, String type, Axis xAxis, Axis yAxis) {
        this.chart = chart;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        part = chart.ui().part(chart.element(), type, null);
        hoverPart = chart.ui().part(chart.element(), type, null);
        hoverPart.state(UiState.HOVER, true);
        pressedPart = chart.ui().part(chart.element(), type, null);
        pressedPart.state(UiState.HOVER | UiState.PRESSED, true);
    }

    /** Whether it shows and can be dragged. */
    public boolean enabled() {
        return enabled;
    }

    /** Shows or hides it; returns this. */
    public DragTool enabled(boolean on) {
        if (on != enabled) {
            enabled = on;
            chart.changed();
        }
        return this;
    }

    /** The X axis it lives on. */
    public Axis xAxis() {
        return xAxis;
    }

    /** The Y axis it lives on. */
    public Axis yAxis() {
        return yAxis;
    }

    /** Lives on {@code x} and {@code y} from now on; returns this. */
    public DragTool axes(Axis x, Axis y) {
        xAxis = x;
        yAxis = y;
        chart.changed();
        return this;
    }

    /** Whether the pointer is over it. */
    public boolean hovered() {
        return hovered;
    }

    /** Whether it is being dragged. */
    public boolean pressed() {
        return pressed;
    }

    void hovered(boolean on) {
        hovered = on;
    }

    void pressed(boolean on) {
        pressed = on;
    }

    /** The style it draws with now: pressed, hovered or plain. */
    protected final ResolvedStyle style() {
        return chart.styleOf(pressed ? pressedPart : hovered ? hoverPart : part);
    }

    /** True once after its values changed. */
    boolean poll() {
        long v = versions();
        if (v == seen) {
            return false;
        }
        seen = v;
        return true;
    }

    /** A sum of its values' versions. */
    protected abstract long versions();

    /** The distance in pixels from content-box pixel {@code px, py} to where it can be grabbed. */
    protected abstract float distance(float px, float py);

    /** The user grabbed it at {@code px, py}. */
    protected abstract void grab(float px, float py);

    /** The user dragged it to {@code px, py}. */
    protected abstract void drag(float px, float py);

    /** The user let go. */
    protected void release() {
        // nothing by default
    }

    /** Records it (over the plot; content-box pixels). */
    protected abstract void paint(Canvas canvas);

    /** Its colour: the part's {@code color}. */
    protected final long color(ResolvedStyle s) {
        return s.rgba(com.truesim.ui.style.Styles.COLOR);
    }
}
