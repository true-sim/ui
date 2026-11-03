package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.value.Align;
import com.truesim.ui.style.value.Anchors;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.BorderRegion;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Direction;
import com.truesim.ui.style.value.FontSpec;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.GridPlacement;
import com.truesim.ui.style.value.ImageSpec;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Justify;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Radii;
import com.truesim.ui.style.value.Shadow;
import com.truesim.ui.style.value.Size;
import com.truesim.ui.style.value.TextAlign;
import com.truesim.ui.style.value.TextOutline;
import com.truesim.ui.style.value.Track;
import com.truesim.ui.style.value.Transition;
import com.truesim.ui.style.value.Wrap;
import java.util.List;

/**
 * The standard keys every element reads: its look (the layer stack of design 4.5), its text, and the layout inputs
 * of design 4.6 (ui-layout reads them; their enumerations and records live here because ui-style cannot depend on
 * ui-layout). Defaults are neutral, so a missing theme shows plain white text and no decoration rather than failing.
 */
@AllowAllocation("shorthands expand when sheets are built")
public final class Styles {

    private Styles() {
    }

    // ---------------------------------------------------------------- look

    /** The fill: {@code none}, a colour, {@code box()}, {@code linear-gradient()}, {@code image()}, nine-slice. */
    public static final StyleKey<Paint> BACKGROUND = StyleKey.of("background", StyleTypes.paint(), Paint.NONE);
    /** The border: {@code 1px #fff}. */
    public static final StyleKey<Border> BORDER = StyleKey.of("border", StyleTypes.border(), Border.NONE);
    /** Corner radii: {@code 6px} or {@code 6px 6px 0 0}. */
    public static final StyleKey<Radii> RADIUS = StyleKey.of("radius", StyleTypes.radii(), Radii.NONE);
    /** Drop shadows: {@code 0 2px 6px rgba(black, 0.35), ...}. */
    public static final StyleKey<List<Shadow>> SHADOW = StyleKey.of("shadow", StyleTypes.shadows(), List.of());
    /** Space inside the border. */
    public static final StyleKey<Insets> PADDING = StyleKey.of("padding", StyleTypes.insets(), Insets.NONE);
    /** Space outside the border. */
    public static final StyleKey<Insets> MARGIN = StyleKey.of("margin", StyleTypes.insets(), Insets.NONE);
    /** Opacity of the element and its subtree, 0 to 1 or a percentage. */
    public static final StyleKey<Float> OPACITY = StyleKey.of("opacity", StyleTypes.fraction(), 1f);
    /** Scale of the element about its centre (pressed buttons shrink a little). */
    public static final StyleKey<Float> SCALE = StyleKey.number("scale", 1, 0, 100);
    /** Transitions of other keys: {@code background 120ms ease-out, scale 80ms}. */
    public static final StyleKey<List<Transition>> TRANSITION =
        StyleKey.of("transition", StyleTypes.transitions(), List.of());
    /** What an image element shows: {@code "icons/heart" #ff4040 contain}. */
    public static final StyleKey<ImageSpec> IMAGE = StyleKey.of("image", StyleTypes.image(), ImageSpec.NONE);

    // ---------------------------------------------------------------- text

    /** The text colour. */
    public static final StyleKey<Color> COLOR = StyleKey.of("color", StyleTypes.color(), Color.WHITE);
    /** The font family fallback chain: {@code "Inter", "Segoe UI", "DejaVu Sans"}. */
    public static final StyleKey<List<String>> FONT_FAMILY =
        StyleKey.of("font-family", StyleTypes.families(), List.of("sans-serif"));
    /** The font size in panel units. */
    public static final StyleKey<Float> FONT_SIZE = StyleKey.number("font-size", 14, 1, 512);
    /** The font weight: 100 to 900, {@code normal} or {@code bold}. */
    public static final StyleKey<Integer> FONT_WEIGHT = StyleKey.of("font-weight", StyleTypes.fontWeight(), 400);
    /** Upright or italic. */
    public static final StyleKey<FontStyle> FONT_STYLE = StyleKey.of("font-style", FontStyle.class, FontStyle.NORMAL);
    /** Shorthand for the four font keys: {@code font: bold 14px "Inter", "Segoe UI"}. */
    public static final Shorthand<FontSpec> FONT = Shorthand.of("font", StyleTypes.font(), f -> List.of(
        Declaration.of(FONT_FAMILY, f.families()), Declaration.of(FONT_SIZE, f.size()),
        Declaration.of(FONT_WEIGHT, f.weight()), Declaration.of(FONT_STYLE, f.style())));
    /** An outline around glyphs: {@code 1px rgba(black, 0.6)}. */
    public static final StyleKey<TextOutline> TEXT_OUTLINE =
        StyleKey.of("text-outline", StyleTypes.textOutline(), TextOutline.NONE);
    /** Horizontal text alignment. */
    public static final StyleKey<TextAlign> TEXT_ALIGN = StyleKey.of("text-align", TextAlign.class, TextAlign.START);
    /** Line height as a multiple of the font size. */
    public static final StyleKey<Float> LINE_HEIGHT = StyleKey.number("line-height", 1.25f, 0.1f, 10);

    // ---------------------------------------------------------------- layout of the children

    /** Which layout arranges the children. */
    public static final StyleKey<LayoutKind> LAYOUT = StyleKey.of("layout", LayoutKind.class, LayoutKind.STACK);
    /** The main axis of a stack or flex layout. */
    public static final StyleKey<Direction> DIRECTION = StyleKey.of("direction", Direction.class, Direction.COLUMN);
    /** Whether flex items wrap. */
    public static final StyleKey<Wrap> WRAP = StyleKey.of("wrap", Wrap.class, Wrap.NO_WRAP);
    /** Space between children, in panel units. */
    public static final StyleKey<Float> GAP = StyleKey.number("gap", 0, 0, 100_000);
    /** Free space along the main axis. */
    public static final StyleKey<Justify> JUSTIFY_CONTENT =
        StyleKey.of("justify-content", Justify.class, Justify.START);
    /** Children on the cross axis. */
    public static final StyleKey<Align> ALIGN_ITEMS = StyleKey.of("align-items", Align.class, Align.STRETCH);
    /** Grid columns: {@code 120px 1fr auto minmax(40px, 2fr)}. */
    public static final StyleKey<List<Track>> GRID_TEMPLATE_COLUMNS =
        StyleKey.of("grid-template-columns", StyleTypes.tracks(), List.of());
    /** Grid rows. */
    public static final StyleKey<List<Track>> GRID_TEMPLATE_ROWS =
        StyleKey.of("grid-template-rows", StyleTypes.tracks(), List.of());

    // ---------------------------------------------------------------- the element in its parent's layout

    /** This element on the cross axis, overriding the parent's {@code align-items}. */
    public static final StyleKey<Align> ALIGN_SELF = StyleKey.of("align-self", Align.class, Align.AUTO);
    /** Share of free space a flex item grows by. */
    public static final StyleKey<Float> GROW = StyleKey.number("grow", 0, 0, 1_000_000);
    /** Share of missing space a flex item shrinks by. */
    public static final StyleKey<Float> SHRINK = StyleKey.number("shrink", 1, 0, 1_000_000);
    /** A flex item's size before growing and shrinking. */
    public static final StyleKey<Length> BASIS = StyleKey.of("basis", StyleTypes.length(), Length.AUTO);
    /** The width. */
    public static final StyleKey<Length> WIDTH = StyleKey.of("width", StyleTypes.length(), Length.AUTO);
    /** The height. */
    public static final StyleKey<Length> HEIGHT = StyleKey.of("height", StyleTypes.length(), Length.AUTO);
    /** Shorthand for width and height: {@code size: 18px} or {@code size: 100% 24px}. */
    public static final Shorthand<Size> SIZE = Shorthand.of("size", StyleTypes.size(),
        s -> List.of(Declaration.of(WIDTH, s.width()), Declaration.of(HEIGHT, s.height())));
    /** The minimum width ({@code auto}: none). */
    public static final StyleKey<Length> MIN_WIDTH = StyleKey.of("min-width", StyleTypes.length(), Length.AUTO);
    /** The minimum height. */
    public static final StyleKey<Length> MIN_HEIGHT = StyleKey.of("min-height", StyleTypes.length(), Length.AUTO);
    /** The maximum width ({@code auto}: none). */
    public static final StyleKey<Length> MAX_WIDTH = StyleKey.of("max-width", StyleTypes.length(), Length.AUTO);
    /** The maximum height. */
    public static final StyleKey<Length> MAX_HEIGHT = StyleKey.of("max-height", StyleTypes.length(), Length.AUTO);
    /** Width over height to keep; 0 keeps none ({@code aspect-ratio: 16 / 9}). */
    public static final StyleKey<Float> ASPECT_RATIO = StyleKey.number("aspect-ratio", 0, 0, 10_000);
    /** Anchors in an anchors layout: {@code 0 1 0 1} (bottom left), {@code full}. */
    public static final StyleKey<Anchors> ANCHORS = StyleKey.of("anchors", StyleTypes.anchors(), Anchors.TOP_LEFT);
    /** Offsets of the edges from their anchors, left top right bottom: {@code 16px -80px 216px -16px}. */
    public static final StyleKey<Insets> OFFSETS = StyleKey.of("offsets", StyleTypes.offsets(), Insets.NONE);
    /** Grid column placement: {@code 2}, {@code 1 span 2}, {@code span 3}. */
    public static final StyleKey<GridPlacement> GRID_COLUMN =
        StyleKey.of("grid-column", StyleTypes.gridPlacement(), GridPlacement.AUTO);
    /** Grid row placement. */
    public static final StyleKey<GridPlacement> GRID_ROW =
        StyleKey.of("grid-row", StyleTypes.gridPlacement(), GridPlacement.AUTO);
    /** The region of a border layout. */
    public static final StyleKey<BorderRegion> REGION = StyleKey.of("region", BorderRegion.class, BorderRegion.CENTER);
}
