package com.truesim.ui.charts;

import com.truesim.common.state.ChangeClock;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Align;
import com.truesim.ui.style.value.Direction;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Wrap;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The charts' demo page (ImPlot's demo, one tile per feature) for tests and screenshots: two charts with three Y axes
 * each and linked X axes sharing a crosshair; log and symlog axes; a time axis with stairs and a digital signal;
 * scatter with every marker; grouped bars on a category axis; stacked horizontal bars; an area, stacked areas and a
 * shaded band; a histogram; a 2D histogram; a heatmap; a pie and a donut; candlesticks on a time axis; error bars and
 * stems; infinite lines, text, a callout, an image and the drag tools; and 2 by 2 subplots with linked axes. The data
 * is generated from fixed seeds, so the picture is the same each time. Build it in a UI of {@link #WIDTH} by
 * {@link #HEIGHT} pixels and {@link #settle} it.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * ChartsGallery g = ChartsGallery.build(ui);
 * ChartsGallery.settle(ui, ChartsGallery.WIDTH, ChartsGallery.HEIGHT);
 * renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), ChartsGallery.WIDTH, ChartsGallery.HEIGHT);
 * }</pre>
 */
public final class ChartsGallery {

    /** The page's width in pixels. */
    public static final int WIDTH = 1600;
    /** The page's height in pixels. */
    public static final int HEIGHT = 1280;

    private static final float TILE_W = 380;
    private static final float TILE_H = 300;

    public final Element root;
    public final Chart linkedA;
    public final Chart linkedB;
    public final Chart logs;
    public final Chart time;
    public final Chart scatter;
    public final Chart grouped;
    public final Chart stackedBars;
    public final Chart areas;
    public final Chart histogram;
    public final Chart histogram2d;
    public final Chart heatmap;
    public final Chart pies;
    public final Chart candles;
    public final Chart errors;
    public final Chart tools;
    public final Subplots subplots;
    public final LineSeries sine;
    public final ScatterSeries dots;
    public final BarSeries bars;
    public final PieSeries pie;
    public final DragPoint point;
    public final DragLine line;
    public final DragRect rect;

    private ChartsGallery(Ui ui) {
        ChangeClock clock = ui.store().clock();
        root = ui.panel("hud");
        ui.root().add(root);
        ui.styles().inline(root.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, Direction.ROW).set(Styles.WRAP, Wrap.WRAP).set(Styles.GAP, 12f)
            .set(Styles.ALIGN_ITEMS, Align.START).build());
        SplittableRandom random = new SplittableRandom(7);
        Crosshair shared = new Crosshair(clock);

        // 1, 2: linked lines, three Y axes each
        linkedA = tile(ui, new Chart(ui, "Linked A"));
        linkedA.title("Linked charts, three Y axes");
        double[] t = new double[400];
        double[] s1 = new double[400];
        double[] s2 = new double[400];
        double[] s3 = new double[400];
        for (int i = 0; i < t.length; i++) {
            t[i] = i * 0.05;
            s1[i] = Math.sin(t[i]);
            s2[i] = 50 + 30 * Math.cos(t[i] * 0.7);
            s3[i] = 1000 * Math.exp(-t[i] * 0.15);
        }
        Column tc = Columns.of(t);
        sine = linkedA.line("sin", tc, Columns.of(s1));
        Axis y2 = linkedA.addAxis("y2", Axis.Side.LEFT, Scale.LINEAR).title("cos");
        Axis y3 = linkedA.addAxis("y3", Axis.Side.RIGHT, Scale.LINEAR).title("decay");
        linkedA.line("cos", tc, Columns.of(s2)).axes(linkedA.x(), y2);
        linkedA.line("decay", tc, Columns.of(s3)).axes(linkedA.x(), y3);
        linkedA.y().title("sin");
        linkedA.crosshair(shared);
        linkedB = tile(ui, new Chart(ui, "Linked B"));
        linkedB.title("Linked X, shared crosshair");
        double[] b1 = new double[400];
        double[] b2 = new double[400];
        double[] b3 = new double[400];
        for (int i = 0; i < t.length; i++) {
            b1[i] = Math.sin(t[i] * 2) * Math.exp(-t[i] * 0.1);
            b2[i] = random.nextGaussian() * 0.2 + Math.sin(t[i] * 0.5);
            b3[i] = t[i] * t[i];
        }
        linkedB.stairs("steps", tc, Columns.of(b1));
        Axis by2 = linkedB.addAxis("y2", Axis.Side.RIGHT, Scale.LINEAR);
        Axis by3 = linkedB.addAxis("y3", Axis.Side.RIGHT, Scale.LINEAR);
        linkedB.line("noisy", tc, Columns.of(b2)).axes(linkedB.x(), by2);
        linkedB.line("square", tc, Columns.of(b3)).axes(linkedB.x(), by3);
        linkedB.x().link(linkedA.x().range()).title("seconds");
        linkedB.crosshair(shared);

        // 3: log and symlog
        logs = tile(ui, new Chart(ui, "Log axes"));
        logs.title("Log and symlog axes");
        Axis logY = logs.addAxis("log", Axis.Side.LEFT, Scale.LOG).title("log");
        Axis sym = logs.addAxis("symlog", Axis.Side.RIGHT, Scale.SYMLOG).title("symlog");
        logs.y().enabled(false);
        double[] lx = new double[200];
        double[] ly = new double[200];
        double[] sy = new double[200];
        for (int i = 0; i < lx.length; i++) {
            lx[i] = i * 0.05;
            ly[i] = Math.pow(10, lx[i] * 0.6);
            sy[i] = Math.sinh(lx[i] * 1.2 - 6) * 5;
        }
        logs.line("10^x", Columns.of(lx), Columns.of(ly)).axes(logs.x(), logY);
        logs.line("sinh", Columns.of(lx), Columns.of(sy)).axes(logs.x(), sym);

        // 4: time axis: stairs and a digital signal
        time = tile(ui, new Chart(ui, "Time"));
        time.title("Time axis, stairs, digital");
        Axis tx = time.addAxis("time", Axis.Side.BOTTOM, Scale.TIME);
        time.x().enabled(false);
        double start = 1_730_678_400; // 2024-11-04 00:00 UTC
        double[] ts = new double[48];
        double[] level = new double[48];
        double[] bit = new double[48];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = start + i * 1800;
            level[i] = 20 + 5 * Math.sin(i * 0.3) + random.nextDouble() * 2;
            bit[i] = (i / 3) % 2;
        }
        time.stairs("temperature", Columns.of(ts), Columns.of(level)).axes(tx, time.y());
        time.digital("heater", Columns.of(ts), Columns.of(bit)).axes(tx, time.y());

        // 5: scatter with markers
        scatter = tile(ui, new Chart(ui, "Scatter"));
        scatter.title("Scatter and markers");
        String[] markers = {"circle", "square", "diamond", "up", "down", "cross", "plus", "asterisk"};
        ScatterSeries first = null;
        for (int m = 0; m < markers.length; m++) {
            double[] x = new double[12];
            double[] y = new double[12];
            for (int i = 0; i < x.length; i++) {
                x[i] = random.nextDouble() * 10;
                y[i] = m + random.nextDouble() * 0.8;
            }
            ScatterSeries s = scatter.scatter(m < 4 ? markers[m] : "", Columns.of(x), Columns.of(y));
            s.styled(markers[m]);
            if (first == null) {
                first = s;
            }
        }
        dots = first;

        // 6: grouped bars on a category axis
        grouped = tile(ui, new Chart(ui, "Grouped bars"));
        grouped.title("Grouped bars, category axis");
        Axis cat = grouped.addAxis("category", Axis.Side.BOTTOM, Scale.CATEGORY)
            .categories(List.of("Mon", "Tue", "Wed", "Thu", "Fri"));
        grouped.x().enabled(false);
        double[] days = {0, 1, 2, 3, 4};
        bars = grouped.bars(List.of("Alpha", "Beta", "Gamma"), Columns.of(days), Columns.of(4, 6, 3, 7, 5),
            Columns.of(3, 4, 5, 2, 6), Columns.of(1, 3, 2, 4, 3));
        bars.axes(cat, grouped.y());

        // 7: stacked horizontal bars
        stackedBars = tile(ui, new Chart(ui, "Stacked bars"));
        stackedBars.title("Stacked horizontal bars");
        Axis rows = stackedBars.addAxis("rows", Axis.Side.LEFT, Scale.CATEGORY)
            .categories(List.of("North", "East", "South", "West"));
        stackedBars.y().enabled(false);
        stackedBars.bars(List.of("Solar", "Wind", "Hydro"), Columns.of(0, 1, 2, 3), Columns.of(5, 3, 6, 2),
            Columns.of(2, 4, 1, 5), Columns.of(3, -1, 2, 1)).stacked(true).horizontal(true)
            .axes(stackedBars.x(), rows);

        // 8: area, stacked area, shaded band
        areas = tile(ui, new Chart(ui, "Areas"));
        areas.title("Area, stacked, shaded");
        double[] ax = new double[60];
        double[] a1 = new double[60];
        double[] a2 = new double[60];
        double[] lo = new double[60];
        double[] hi = new double[60];
        double[] mid = new double[60];
        for (int i = 0; i < ax.length; i++) {
            ax[i] = i;
            a1[i] = 2 + Math.sin(i * 0.2);
            a2[i] = 1.5 + Math.cos(i * 0.15);
            mid[i] = 9 + Math.sin(i * 0.1) * 2;
            lo[i] = mid[i] - 1 - random.nextDouble() * 0.5;
            hi[i] = mid[i] + 1 + random.nextDouble() * 0.5;
        }
        Column axc = Columns.of(ax);
        areas.stackedArea(List.of("cpu", "gpu"), axc, Columns.of(a1), Columns.of(a2));
        areas.shaded("band", axc, Columns.of(lo), Columns.of(hi));
        areas.line("mean", axc, Columns.of(mid));
        areas.area("baseline", axc, Columns.of(lo), 6.5).styled("thin");

        // 9: histogram
        histogram = tile(ui, new Chart(ui, "Histogram"));
        histogram.title("Histogram");
        double[] normal = new double[2000];
        for (int i = 0; i < normal.length; i++) {
            normal[i] = random.nextGaussian() * 1.5 + 4;
        }
        histogram.histogram("normal", Columns.of(normal), 30);

        // 10: 2D histogram
        histogram2d = tile(ui, new Chart(ui, "Histogram 2D"));
        histogram2d.title("2D histogram");
        double[] hx = new double[5000];
        double[] hy = new double[5000];
        for (int i = 0; i < hx.length; i++) {
            double g = random.nextGaussian();
            hx[i] = g;
            hy[i] = g * 0.6 + random.nextGaussian() * 0.8;
        }
        histogram2d.histogram2d("density", Columns.of(hx), Columns.of(hy), 24, 24);

        // 11: heatmap
        heatmap = tile(ui, new Chart(ui, "Heatmap"));
        heatmap.title("Heatmap");
        double[] cells = new double[12 * 16];
        for (int r = 0; r < 12; r++) {
            for (int c = 0; c < 16; c++) {
                cells[r * 16 + c] = Math.sin(r * 0.5) * Math.cos(c * 0.4) + r * 0.05;
            }
        }
        heatmap.heatmap("field", Columns.of(cells), 12, 16, 0, 0, 16, 12);

        // 12: pie and donut
        pies = tile(ui, new Chart(ui, "Pies"));
        pies.title("Pie and donut");
        pie = pies.pie(List.of("Rock", "Ice", "Gas"), Columns.of(45, 30, 25), 0.2, 0.5, 0.18);
        pies.pie(List.of("Iron", "Nickel", "Silicate", "Carbon"), Columns.of(35, 15, 30, 20), 0.6, 0.5, 0.18)
            .styled("donut");
        pies.x().enabled(false);
        pies.y().enabled(false);
        pies.x().range(0, 1.1).fits(false); // room on the right for the legend
        pies.y().range(0, 1).fits(false);

        // 13: candlesticks on a time axis
        candles = tile(ui, new Chart(ui, "Candles"));
        candles.title("Candlesticks");
        Axis day = candles.addAxis("day", Axis.Side.BOTTOM, Scale.TIME);
        candles.x().enabled(false);
        int n = 30;
        double[] cx = new double[n];
        double[] open = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        double price = 100;
        for (int i = 0; i < n; i++) {
            cx[i] = start + i * 86_400.0;
            open[i] = price;
            close[i] = price + random.nextGaussian() * 3;
            high[i] = Math.max(open[i], close[i]) + random.nextDouble() * 2;
            low[i] = Math.min(open[i], close[i]) - random.nextDouble() * 2;
            price = close[i];
        }
        candles.candlestick("price", Columns.of(cx), Columns.of(open), Columns.of(high), Columns.of(low),
            Columns.of(close)).axes(day, candles.y());

        // 14: error bars and stems
        errors = tile(ui, new Chart(ui, "Errors"));
        errors.title("Error bars and stems");
        double[] ex = new double[10];
        double[] ey = new double[10];
        double[] ee = new double[10];
        double[] sx = new double[10];
        double[] st = new double[10];
        for (int i = 0; i < ex.length; i++) {
            ex[i] = i;
            ey[i] = 3 + Math.sin(i * 0.7) * 2;
            ee[i] = 0.3 + random.nextDouble() * 0.5;
            sx[i] = i + 0.5;
            st[i] = Math.cos(i * 0.9) * 1.5;
        }
        errors.errorBars("measured", Columns.of(ex), Columns.of(ey), Columns.of(ee), null);
        errors.stems("impulse", Columns.of(sx), Columns.of(st), 0);

        // 15: infinite lines, text, a callout, an image, drag tools
        tools = tile(ui, new Chart(ui, "Tools"));
        tools.title("Annotations and drag tools");
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, 0xFF000000 | (x * 16) << 16 | (y * 16) << 8 | 0x80);
            }
        }
        UiTexture texture = ui.images().add("charts/gradient", img);
        tools.image("image", texture, 6, 1, 9, 4);
        double[] curveX = new double[50];
        double[] curveY = new double[50];
        for (int i = 0; i < curveX.length; i++) {
            curveX[i] = i * 0.2;
            curveY[i] = 5 + Math.sin(i * 0.25) * 3;
        }
        tools.line("signal", Columns.of(curveX), Columns.of(curveY));
        tools.infiniteLines("limits", Columns.of(2.0, 8.5), true);
        tools.infiniteLines("", Columns.of(7.5), false);
        tools.text(1.5, 1.5, "text");
        tools.annotate(curveX[20], curveY[20], "peak", 12, -14);
        point = tools.dragPoint(clock.doubleValue(5), clock.doubleValue(2));
        line = tools.dragLine(clock.doubleValue(4.5), true);
        rect = tools.dragRect(clock.doubleValue(0.5), clock.doubleValue(6), clock.doubleValue(3), clock.doubleValue(9));

        // 16: subplots with linked axes
        subplots = new Subplots(ui, "Subplots", 2, 2);
        root.add(sized(ui, subplots.element(), TILE_W, TILE_H));
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                double[] px = new double[80];
                double[] py = new double[80];
                for (int i = 0; i < px.length; i++) {
                    px[i] = i * 0.1;
                    py[i] = Math.sin(i * 0.1 * (r + 1) + c) * (c + 1);
                }
                subplots.chart(r, c).line("", Columns.of(px), Columns.of(py));
            }
        }
        subplots.linkColumns().linkRows();
    }

    private static Element sized(Ui ui, Element e, float w, float h) {
        ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w))
            .set(Styles.HEIGHT, Length.px(h)).build());
        return e;
    }

    private Chart tile(Ui ui, Chart chart) {
        root.add(sized(ui, chart.element(), TILE_W, TILE_H));
        return chart;
    }

    /** Builds the page into {@code ui}'s root. */
    public static ChartsGallery build(Ui ui) {
        return new ChartsGallery(ui);
    }

    /** All the charts, the subplots' included. */
    public List<Chart> charts() {
        java.util.ArrayList<Chart> all = new java.util.ArrayList<>(List.of(linkedA, linkedB, logs, time, scatter,
            grouped, stackedBars, areas, histogram, histogram2d, heatmap, pies, candles, errors, tools));
        for (int r = 0; r < subplots.rows(); r++) {
            for (int c = 0; c < subplots.columns(); c++) {
                all.add(subplots.chart(r, c));
            }
        }
        return all;
    }

    private static long nanos = 1_000_000_000L;

    /** Frames until the atlas has every glyph and nothing records. */
    public static void settle(Ui ui, int width, int height) {
        UiInput input = new UiInput(4);
        for (int i = 0; i < 400; i++) {
            input.beginFrame();
            ui.update(width, height, input, nanos += 16_000_000L);
            if (i > 10 && (ui.textSystem() == null || ui.textSystem().atlas().pending() == 0) && ui.recorded() == 0) {
                return;
            }
        }
    }

    /**
     * Poses the page for a screenshot of its interaction looks: the pointer over the first linked chart (crosshair
     * there and in its linked twin, highlighted points, the tooltip), a legend entry hidden, the grouped chart's X
     * axis selected.
     */
    public void pose(Ui ui, int width, int height) {
        bars.itemEnabled(2, false);
        grouped.axis("category").selected(true);
        UiInput input = new UiInput(4);
        float px = (linkedA.plotLeft() + linkedA.plotRight()) * 0.45f;
        float x = linkedA.contentX() + px;
        float y = linkedA.contentY() + linkedA.y().toPixel(Math.sin(linkedA.x().fromPixel(px))); // on the sine
        for (int i = 0; i < 400; i++) { // the pointer stays: frames until the tooltip's glyphs are in
            input.beginFrame();
            input.pointer(0, com.truesim.ui.core.input.Pointer.Kind.MOUSE).moveTo(x + Math.min(i, 2), y).buttons(0);
            ui.update(width, height, input, nanos += 16_000_000L);
            if (i > 10 && (ui.textSystem() == null || ui.textSystem().atlas().pending() == 0) && ui.recorded() == 0) {
                return;
            }
        }
    }
}
