package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedLong;
import com.truesim.common.state.Watch;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.GrowDirection;
import com.truesim.ui.layout.Layout;
import java.util.Arrays;

/**
 * A colour picker (type {@code color-picker}), bound two-way to a {@link VersionedLong} holding a packed colour
 * ({@link Rgba}: linear, straight alpha). Parts, all styled by the theme:
 *
 * <ul>
 *   <li>{@code color-area}: saturation (across) and value (up) of the current hue: its fill is the hue (set by the
 *   control), under a {@code color-area-white} and a {@code color-area-black} layer (the theme's gradients), with a
 *   {@code color-area-cursor};</li>
 *   <li>{@code color-hue}: the hue strip ({@code color-hue-segment}s, six gradients the control colours) and a
 *   {@code color-hue-cursor};</li>
 *   <li>{@code color-alpha}: a {@code color-alpha-checker}, a {@code color-alpha-gradient} from transparent to the
 *   colour, and a {@code color-alpha-cursor};</li>
 *   <li>{@code color-preview} (the colour), {@code color-hex-slot} (holding a {@code color-hex} label with
 *   {@code #RRGGBBAA}; a text field can take its place: see {@link #hexSlot()}) and {@code color-swatches} of
 *   {@code color-swatch} buttons.</li>
 * </ul>
 *
 * <p>Dragging in the area, strip or alpha bar sets the colour (HSV in sRGB-encoded terms, as people pick colours); each
 * takes focus, and arrow keys step it by 1% (page keys by 10%). The hue is kept while saturation or value is 0, so
 * the area does not jump. Dragging allocates nothing.
 *
 * <p><b>HDR</b> ({@link #hdr}): the colour is a displayable base colour (the area, strip and hex) times an intensity
 * of {@code 2^stops}, set by a {@code color-intensity} row (a {@link Slider} of style name {@code intensity} over
 * {@code 0..maxStops} exposure stops and a {@code color-intensity-value} label such as {@code +2.0 EV}); components
 * above 1 are kept. The preview splits into {@code color-preview-clipped} (the colour clipped to 1, as an SDR display
 * shows it) and {@code color-preview-hdr} (the HDR value). The hex shows and takes the base colour only.
 *
 * <p><b>Eyedropper</b>: with a {@linkplain Controls#screenSampler() screen sampler}, a {@code button} of style name
 * {@code eyedropper} (icon {@code eyedropper}, {@code :checked} while picking) in the hex slot starts an
 * {@link Eyedropper}; the pixel picked becomes the colour (its HDR intensity kept in HDR mode, clipped otherwise;
 * the alpha stays).
 */
public final class ColorPicker extends Control {

    private static final float[] HUE_R = {1, 1, 0, 0, 0, 1, 1};
    private static final float[] HUE_G = {0, 1, 1, 1, 0, 0, 0};
    private static final float[] HUE_B = {0, 0, 0, 1, 1, 1, 0};

    private final Element area;
    private final Element areaCursor;
    private final Element hue;
    private final Element hueCursor;
    private final Element alpha;
    private final Element alphaGradient;
    private final Element alphaCursor;
    private final Element preview;
    private final Element hexSlot;
    private final Element hexLabel;
    private final Element swatchRow;
    private Element[] swatches = new Element[0];
    private long[] swatchColors = new long[0];
    private Runnable[] changes = new Runnable[1];
    private int changeCount;

    private VersionedLong model;
    private Watch watch;
    private float h;
    private float s;
    private float v;
    private float a = 1;
    private int dragging = -1; // 0 area, 1 hue, 2 alpha
    private final StringBuilder hex = new StringBuilder(9);
    private final float[] rgb = new float[3];
    // HDR: the colour is the base (h, s, v) times 2^stops
    private float stops;
    private float maxStops;
    private Element intensityRow;
    private Slider intensity;
    private VersionedDouble intensityModel;
    private Watch intensityWatch;
    private Element intensityValue;
    private final StringBuilder intensityText = new StringBuilder(16);
    private Element clipped;
    private Element hdrPreview;
    // the eyedropper
    private Button eyedropButton;
    private Eyedropper eyedropper;

    /** A picker named {@code name} bound to {@code model} (a packed {@link Rgba} colour). */
    @AllowAllocation("controls are built once")
    public ColorPicker(Controls controls, String name, VersionedLong model) {
        super(controls, "color-picker", Role.GROUP, name);
        area = part("color-area");
        area.semantics(Role.SLIDER, "Saturation and brightness");
        Element white = part("color-area-white");
        Element black = part("color-area-black");
        areaCursor = part("color-area-cursor");
        area.add(white).add(black).add(areaCursor);
        full(white);
        full(black);

        hue = part("color-hue");
        hue.semantics(Role.SLIDER, "Hue");
        Element strip = part("color-hue-strip");
        full(strip);
        for (int i = 0; i < 6; i++) {
            Element seg = part("color-hue-segment");
            ui.visuals().background(seg.slot(), Rgba.pack(HUE_R[i], HUE_G[i], HUE_B[i], 1),
                Rgba.pack(HUE_R[i + 1], HUE_G[i + 1], HUE_B[i + 1], 1));
            strip.add(seg);
        }
        hueCursor = part("color-hue-cursor");
        hue.add(strip).add(hueCursor);

        alpha = part("color-alpha");
        alpha.semantics(Role.SLIDER, "Opacity");
        Element checker = part("color-alpha-checker");
        alphaGradient = part("color-alpha-gradient");
        alphaCursor = part("color-alpha-cursor");
        full(checker);
        full(alphaGradient);
        alpha.add(checker).add(alphaGradient).add(alphaCursor);

        preview = part("color-preview");
        hexSlot = part("color-hex-slot");
        hexLabel = label("#00000000");
        hexLabel.semantics(Role.TEXT, null);
        hexSlot.add(hexLabel);
        swatchRow = part("color-swatches");
        root.add(area).add(hue).add(alpha).add(preview).add(hexSlot).add(swatchRow);
        for (Element e : new Element[] {area, hue, alpha}) {
            focusable(e);
            listen(e, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit()
                | EventType.COMMAND.bit());
        }
        grow(areaCursor);
        grow(hueCursor);
        grow(alphaCursor);
        controls.poll(this);
        if (controls.screenSampler() != null) {
            eyedropper();
        }
        bind(model);
    }

    /**
     * Picks colours as HDR values of up to {@code 2^maxStops} times paper white ({@code maxStops} 0: back to LDR,
     * colours clipped to 1): adds the intensity row and splits the preview; returns this.
     */
    @AllowAllocation("controls are configured when built")
    public ColorPicker hdr(float maxStopsValue) {
        maxStops = Math.max(0, maxStopsValue);
        if (maxStops > 0 && intensityRow == null) {
            intensityRow = part("color-intensity");
            intensityModel = controls.clock().doubleValue(0);
            intensity = new Slider(controls, "Intensity", intensityModel, 0, maxStops, false);
            intensity.element().style("intensity");
            intensityValue = label("+0.0 EV");
            intensityValue.style("color-intensity-value");
            intensityRow.add(intensity.element()).add(intensityValue);
            intensityWatch = intensityModel.watch();
            clipped = part("color-preview-clipped");
            hdrPreview = part("color-preview-hdr");
            preview.add(clipped).add(hdrPreview);
            preview.detach();
            hexSlot.detach();
            swatchRow.detach();
            root.add(intensityRow).add(preview).add(hexSlot).add(swatchRow);
        } else if (maxStops > 0) {
            intensity.range(0, maxStops);
        }
        if (intensityRow != null) {
            ui.layout().visible(intensityRow.slot(), maxStops > 0);
            ui.layout().visible(clipped.slot(), maxStops > 0);
            ui.layout().visible(hdrPreview.slot(), maxStops > 0);
        }
        fromModel();
        return this;
    }

    /** Whether it picks HDR colours. */
    public boolean hdr() {
        return maxStops > 0;
    }

    /** The intensity in exposure stops (the colour is its base times {@code 2^stops}). */
    public float stops() {
        return stops;
    }

    /** Sets the intensity in stops (clamped to {@code 0..maxStops}); the base colour stays. */
    public void stops(float value) {
        stops = Math.max(0, Math.min(maxStops, value));
        if (intensityModel != null) {
            intensityModel.set(stops);
            intensityWatch.update();
        }
        toModel();
    }

    /** The intensity slider (null until {@link #hdr}). */
    public Slider intensitySlider() {
        return intensity;
    }

    /** The intensity label (null until {@link #hdr}). */
    public Element intensityLabel() {
        return intensityValue;
    }

    /** The clipped half of the preview (null until {@link #hdr}). */
    public Element clippedPreview() {
        return clipped;
    }

    /** The HDR half of the preview (null until {@link #hdr}). */
    public Element hdrPreview() {
        return hdrPreview;
    }

    /**
     * Adds the eyedropper button (made by the constructor when the controls have a screen sampler); returns this.
     */
    @AllowAllocation("controls are configured when built")
    public ColorPicker eyedropper() {
        if (eyedropButton != null) {
            return this;
        }
        eyedropper = new Eyedropper(controls, new Eyedropper.Listener() {
            @Override
            public void picked(long color) {
                eyedropButton.element().state(com.truesim.ui.core.UiState.CHECKED, false);
                pickedColor(color);
            }

            @Override
            public void cancelled() {
                eyedropButton.element().state(com.truesim.ui.core.UiState.CHECKED, false);
            }
        });
        eyedropButton = new Button(controls, "eyedropper", null, "Pick from screen");
        eyedropButton.element().style("eyedropper");
        eyedropButton.onClick(() -> {
            if (eyedropper.start(0)) {
                eyedropButton.element().state(com.truesim.ui.core.UiState.CHECKED, true);
            }
        });
        Element first = ui.store().element(ui.store().firstChild(hexSlot.slot()));
        hexSlot.add(eyedropButton.element());
        if (first != null) { // the button first, the hex after it
            first.detach();
            hexSlot.add(first);
        }
        return this;
    }

    /** The eyedropper button (null without a screen sampler). */
    public Button eyedropperButton() {
        return eyedropButton;
    }

    /** The eyedropper (null without a screen sampler). */
    public Eyedropper eyedropperTool() {
        return eyedropper;
    }

    /** The eyedropper picked {@code color}: it becomes the colour, its alpha kept. */
    private void pickedColor(long color) {
        float r = Rgba.r(color);
        float g = Rgba.g(color);
        float b = Rgba.b(color);
        if (maxStops <= 0) {
            r = clamp(r);
            g = clamp(g);
            b = clamp(b);
        }
        model.set(Rgba.pack(r, g, b, a));
        fromModel();
    }

    private void full(Element e) {
        ui.visuals().anchors(e.slot(), 0, 0, 1, 1);
    }

    private void grow(Element e) {
        ui.layout().growDirection(e.slot(), GrowDirection.BOTH, GrowDirection.BOTH);
    }

    /** Binds the picker to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public ColorPicker bind(VersionedLong value) {
        model = value;
        watch = value.watch();
        fromModel();
        return this;
    }

    /** The model: a packed colour. */
    public VersionedLong model() {
        return model;
    }

    /** The colour, packed. */
    public long color() {
        return model.get();
    }

    /** Hue 0 to 1. */
    public float hue() {
        return h;
    }

    /** Saturation 0 to 1. */
    public float saturation() {
        return s;
    }

    /** Value (brightness) 0 to 1. */
    public float value() {
        return v;
    }

    /** Alpha 0 to 1. */
    public float alpha() {
        return a;
    }

    /** Runs {@code action} whenever the colour changes (by the picker or the model); returns this. */
    @AllowAllocation("listeners are added when a control is built")
    public ColorPicker onChange(Runnable action) {
        if (changeCount == changes.length) {
            changes = Arrays.copyOf(changes, changeCount * 2);
        }
        changes[changeCount++] = action;
        return this;
    }

    /**
     * Adds swatches ({@code color-swatch} buttons, packed colours) that pick their colour when clicked; returns
     * this.
     */
    @AllowAllocation("swatches are made when a control is configured")
    public ColorPicker swatches(long... colors) {
        int old = swatches.length;
        swatches = Arrays.copyOf(swatches, old + colors.length);
        swatchColors = Arrays.copyOf(swatchColors, old + colors.length);
        for (int i = 0; i < colors.length; i++) {
            Element sw = part("color-swatch");
            sw.semantics(Role.BUTTON, "Swatch " + (old + i + 1));
            ui.visuals().background(sw.slot(), colors[i], colors[i]);
            focusable(sw);
            listen(sw, EventType.CLICK.bit());
            swatchRow.add(sw);
            swatches[old + i] = sw;
            swatchColors[old + i] = colors[i];
        }
        return this;
    }

    /** Swatch {@code index}. */
    public Element swatch(int index) {
        return swatches[index];
    }

    /**
     * Where the hex text shows: a {@code color-hex} label by default. A text field (another control library's) can
     * replace it: detach {@link #hexLabel()}, add the field here, show {@link #hex()} in it {@link #onChange on every
     * change} and give its committed text to {@link #hex(CharSequence)}.
     */
    public Element hexSlot() {
        return hexSlot;
    }

    /** The default hex label. */
    public Element hexLabel() {
        return hexLabel;
    }

    /** The colour as {@code #RRGGBBAA} (sRGB): a reused buffer, read it before the next change. */
    public CharSequence hex() {
        return hex;
    }

    /**
     * Sets the colour from {@code #RGB}, {@code #RGBA}, {@code #RRGGBB} or {@code #RRGGBBAA} (the {@code #} optional);
     * false (and nothing changes) if the text is none of those. Allocates nothing.
     */
    public boolean hex(CharSequence text) {
        int start = text.length() > 0 && text.charAt(0) == '#' ? 1 : 0;
        int n = text.length() - start;
        if (n != 3 && n != 4 && n != 6 && n != 8) {
            return false;
        }
        int wide = n >= 6 ? 2 : 1;
        int channels = n / wide;
        float[] c = rgb;
        float alphaValue = 1;
        for (int i = 0; i < channels; i++) {
            int value = 0;
            for (int d = 0; d < wide; d++) {
                int digit = Character.digit(text.charAt(start + i * wide + d), 16);
                if (digit < 0) {
                    return false;
                }
                value = value * 16 + digit;
            }
            float f = wide == 1 ? value * 17 / 255f : value / 255f;
            if (i < 3) {
                c[i] = f;
            } else {
                alphaValue = f;
            }
        }
        float r = c[0];
        float g = c[1];
        float b = c[2];
        float k = intensityScale();
        model.set(Rgba.pack(Rgba.toLinear(r) * k, Rgba.toLinear(g) * k, Rgba.toLinear(b) * k, alphaValue));
        fromModel();
        return true;
    }

    // ---------------------------------------------------------------- model and display

    /** {@code 2^stops}: what the base colour is multiplied by (1 without HDR). */
    private float intensityScale() {
        return maxStops > 0 ? (float) Math.pow(2, stops) : 1;
    }

    private void fromModel() {
        watch.update();
        long c = model.get();
        float lr = Rgba.r(c);
        float lg = Rgba.g(c);
        float lb = Rgba.b(c);
        if (maxStops > 0) { // the intensity is what lifts the brightest component above 1
            float m = Math.max(lr, Math.max(lg, lb));
            stops = m > 1 ? Math.min(maxStops, (float) (Math.log(m) / Math.log(2))) : 0;
            float k = 1 / intensityScale();
            lr *= k;
            lg *= k;
            lb *= k;
            intensityModel.set(stops);
            intensityWatch.update();
        }
        float r = Rgba.toSrgb(clamp(lr));
        float g = Rgba.toSrgb(clamp(lg));
        float b = Rgba.toSrgb(clamp(lb));
        a = clamp(Rgba.a(c));
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float nv = max;
        float ns = max <= 0 ? 0 : d / max;
        if (d > 0) {
            float nh;
            if (max == r) {
                nh = (g - b) / d;
                if (nh < 0) {
                    nh += 6;
                }
            } else if (max == g) {
                nh = (b - r) / d + 2;
            } else {
                nh = (r - g) / d + 4;
            }
            h = nh / 6;
        }
        s = ns;
        v = nv;
        display();
    }

    private static float clamp(float x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }

    /** Writes h, s, v, a to the model. */
    private void toModel() {
        hsvToRgb(h, s, v, rgb);
        float k = intensityScale();
        model.set(Rgba.pack(Rgba.toLinear(rgb[0]) * k, Rgba.toLinear(rgb[1]) * k, Rgba.toLinear(rgb[2]) * k, a));
        watch.update();
        display();
    }

    /** sRGB-encoded r, g, b of hue {@code hh}, saturation {@code ss}, value {@code vv} into {@code out}. */
    static void hsvToRgb(float hh, float ss, float vv, float[] out) {
        float hue6 = (hh - (float) Math.floor(hh)) * 6;
        int sector = (int) hue6;
        float f = hue6 - sector;
        float p = vv * (1 - ss);
        float q = vv * (1 - ss * f);
        float t = vv * (1 - ss * (1 - f));
        switch (sector) {
            case 0 -> set(out, vv, t, p);
            case 1 -> set(out, q, vv, p);
            case 2 -> set(out, p, vv, t);
            case 3 -> set(out, p, q, vv);
            case 4 -> set(out, t, p, vv);
            default -> set(out, vv, p, q);
        }
    }

    private static void set(float[] out, float r, float g, float b) {
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    private void display() {
        Visuals vis = ui.visuals();
        hsvToRgb(h, 1, 1, rgb);
        long pureHue = Rgba.pack(Rgba.toLinear(rgb[0]), Rgba.toLinear(rgb[1]), Rgba.toLinear(rgb[2]), 1);
        vis.background(area.slot(), pureHue, pureHue);
        vis.anchors(areaCursor.slot(), s, 1 - v, s, 1 - v);
        vis.anchors(hueCursor.slot(), h, 0.5f, h, 0.5f);
        vis.anchors(alphaCursor.slot(), a, 0.5f, a, 0.5f);
        long c = model.get();
        float cr = clamp(Rgba.r(c));
        float cg = clamp(Rgba.g(c));
        float cb = clamp(Rgba.b(c));
        long opaque = Rgba.pack(cr, cg, cb, 1);
        vis.background(alphaGradient.slot(), Rgba.pack(cr, cg, cb, 0), opaque);
        vis.background(preview.slot(), c, c);
        if (maxStops > 0) {
            long clip = Rgba.pack(cr, cg, cb, Rgba.a(c));
            vis.background(clipped.slot(), clip, clip);
            vis.background(hdrPreview.slot(), c, c);
            showStops();
        }
        hsvToRgb(h, s, v, rgb);
        hex.setLength(0);
        hex.append('#');
        byteHex(rgb[0]);
        byteHex(rgb[1]);
        byteHex(rgb[2]);
        byteHex(a);
        ui.text(hexLabel, hex);
        for (int i = 0; i < changeCount; i++) {
            changes[i].run();
        }
    }

    /** The intensity as {@code +2.5 EV}, written without allocating. */
    private void showStops() {
        intensityText.setLength(0);
        int tenths = Math.round(stops * 10);
        intensityText.append('+').append(tenths / 10).append('.').append(tenths % 10).append(" EV");
        ui.text(intensityValue, intensityText);
    }

    private void byteHex(float f) {
        int b = (int) (clamp(f) * 255 + 0.5f);
        hex.append(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
        hex.append(Character.toUpperCase(Character.forDigit(b & 15, 16)));
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            fromModel();
        }
        if (intensityWatch != null && intensityWatch.isChanged()) {
            intensityWatch.update();
            stops = (float) intensityModel.get();
            toModel();
        }
    }

    // ---------------------------------------------------------------- input

    private int part(int slot) {
        if (slot == area.slot()) {
            return 0;
        }
        if (slot == hue.slot()) {
            return 1;
        }
        return slot == alpha.slot() ? 2 : -1;
    }

    private void drag(int which, float x, float y) {
        Layout l = ui.layout();
        Element e = which == 0 ? area : which == 1 ? hue : alpha;
        int slot = e.slot();
        float w = l.width(slot);
        float hgt = l.height(slot);
        float fx = w <= 0 ? 0 : clamp((x - l.x(slot)) / w);
        float fy = hgt <= 0 ? 0 : clamp((y - l.y(slot)) / hgt);
        if (which == 0) {
            s = fx;
            v = 1 - fy;
        } else if (which == 1) {
            h = Math.min(fx, 0.9999f);
        } else {
            a = fx;
        }
        toModel();
    }

    @Override
    public void handle(UiEvent event) {
        int slot = event.currentSlot();
        for (int i = 0; i < swatches.length; i++) {
            if (slot == swatches[i].slot() && event.type() == EventType.CLICK) {
                event.consume();
                model.set(swatchColors[i]);
                fromModel();
                return;
            }
        }
        int which = part(slot);
        if (which < 0) {
            return;
        }
        switch (event.type()) {
            case PRESS -> {
                event.consume();
                dragging = which;
                drag(which, event.x(), event.y());
            }
            case MOVE -> {
                if (dragging == which && event.buttons() != 0) {
                    drag(which, event.x(), event.y());
                }
            }
            case RELEASE -> dragging = -1;
            case COMMAND -> command(which, event);
            default -> {
                // nothing else
            }
        }
    }

    private void command(int which, UiEvent event) {
        UiCommand c = event.command();
        float dx = 0;
        float dy = 0;
        switch (c) {
            case LEFT -> dx = -0.01f;
            case RIGHT -> dx = 0.01f;
            case UP -> dy = 0.01f;
            case DOWN -> dy = -0.01f;
            case PAGE_UP -> dx = 0.1f;
            case PAGE_DOWN -> dx = -0.1f;
            default -> {
                return;
            }
        }
        if (which != 0 && dy != 0) {
            return; // the strips are horizontal: up and down move focus
        }
        event.consume();
        if (which == 0) {
            s = clamp(s + dx);
            v = clamp(v + dy);
        } else if (which == 1) {
            h = clamp(h + dx);
            if (h >= 1) {
                h = 0.9999f;
            }
        } else {
            a = clamp(a + dx);
        }
        toModel();
    }

    /** Whether a drag is in progress. */
    public boolean dragging() {
        return dragging >= 0;
    }

    /** The saturation-value area. */
    public Element area() {
        return area;
    }

    /** The hue strip. */
    public Element hueBar() {
        return hue;
    }

    /** The alpha bar. */
    public Element alphaBar() {
        return alpha;
    }

    /** The preview. */
    public Element preview() {
        return preview;
    }
}
