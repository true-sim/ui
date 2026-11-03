package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.layout.Direction;
import com.truesim.ui.layout.LayoutKind;
import java.util.Arrays;

/**
 * A scroll view over any content (design 5.5): put elements into {@link #content()} ({@code scroll-content}); the pane
 * scrolls it on both axes with everything {@link ScrollView} offers. By default the content is as wide as the
 * viewport and scrolls vertically ({@link #fitWidth}); without fitting it keeps its own width and scrolls both ways.
 *
 * <p><b>Sticky headers.</b> {@link #section(Element, Element)} makes a {@code scroll-section} of a header and a body:
 * while the section crosses the viewport's top, its header stays there, pushed up by the next section's header.
 * Headers move by a scroll offset, without layout; they draw over their own body.
 *
 * <pre>{@code
 * ScrollPane pane = new ScrollPane(ui, "Settings");
 * parent.add(pane.root());
 * pane.content().add(pane.section(ui.label("title", "Audio"), audioRows));
 * }</pre>
 */
public final class ScrollPane extends ScrollView {

    private final Element content;
    private Element[] stickies = new Element[4];
    private Element[] sections = new Element[4];
    private int sectionCount;
    private boolean fitWidth;

    /** A pane named {@code name} for assistive technology (null: unnamed). */
    @AllowAllocation("controls are made when a UI is built")
    public ScrollPane(Ui ui, String name) {
        super(ui, "scroll-pane", name);
        content = ui.element("scroll-content", null);
        viewport.add(content);
        ui.codeLayout(content, Ui.CODE_PLACEMENT);
        ui.scrollRegion(viewport, true); // scrolling moves the viewport's clip offset: content is not recorded again
        fitWidth(true);
    }

    /** The element whose children scroll. */
    public Element content() {
        return content;
    }

    /**
     * Whether the content is as wide as the viewport (default; it scrolls vertically) or keeps its own width (it
     * scrolls both ways); returns this.
     */
    public ScrollPane fitWidth(boolean on) {
        fitWidth = on;
        layout.anchors(content.slot(), 0, 0, on ? 1 : 0, 0);
        invalidate();
        return this;
    }

    /** Whether the content fits the viewport's width. */
    public boolean fitsWidth() {
        return fitWidth;
    }

    /**
     * A new section of {@code header} over {@code body} whose header sticks to the viewport's top while the section
     * crosses it; add the returned {@code scroll-section} into the content (at any depth).
     */
    @AllowAllocation("controls are made when a UI is built")
    public Element section(Element header, Element body) {
        Element section = ui.element("scroll-section", null);
        Element sticky = ui.element("scroll-sticky", null);
        ui.codeLayout(section, Ui.CODE_KIND);
        // the header goes after the body in tree order, so it draws over it, but shows above it
        layout.kind(section.slot(), LayoutKind.STACK).direction(section.slot(), Direction.COLUMN_REVERSE);
        section.add(body);
        section.add(sticky);
        sticky.add(header);
        if (sectionCount == sections.length) {
            sections = Arrays.copyOf(sections, sectionCount * 2);
            stickies = Arrays.copyOf(stickies, sectionCount * 2);
        }
        sections[sectionCount] = section;
        stickies[sectionCount++] = sticky;
        invalidate();
        return section;
    }

    /** How far section {@code index}'s header is pushed down to stick, in panel units. */
    public float stickyShift(int index) {
        return -layout.scrollY(stickies[index].slot());
    }

    @Override
    protected double contentWidth() {
        return fitWidth ? 0 : layout.width(content.slot()) / scale(); // fitted content never overflows sideways
    }

    @Override
    protected double contentHeight() {
        return layout.height(content.slot()) / scale();
    }

    @Override
    protected void scrolled(double x, double y) {
        layout.scroll(viewport.slot(), (float) x, (float) y);
        stick(y);
    }

    @Override
    protected boolean afterLayout() {
        return stick(vertical().position());
    }

    /** Pushes each section's header down to the viewport's top while it crosses it; returns whether any moved. */
    private boolean stick(double y) {
        float s = scale();
        int c = content.slot();
        boolean moved = false;
        for (int i = 0; i < sectionCount; i++) {
            Element section = sections[i];
            if (!section.alive() || !layout.shown(section.slot())) {
                continue;
            }
            int sec = section.slot();
            int st = stickies[i].slot();
            // where the section starts in the content, independent of the current scroll
            double top = (layout.y(sec) - layout.y(c)) / s;
            double room = (layout.height(sec) - layout.height(st)) / s;
            double shift = Math.max(0, Math.min(room, y - top));
            float value = (float) -shift;
            if (layout.scrollY(st) != value) {
                layout.scroll(st, 0, value);
                moved = true;
            }
        }
        return moved;
    }
}
