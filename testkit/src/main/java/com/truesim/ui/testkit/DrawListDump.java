package com.truesim.ui.testkit;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Draw-list goldens (design section 12): a list written as text, one primitive a line, empty slots left out, numbers
 * rounded so goldens survive harmless float noise. {@link #assertMatches} compares against a golden file and, with
 * {@code -Dtruesim.updateGoldens=true}, writes it instead. Primitives are written as they draw
 * ({@link DrawList#baked()}): positions with their clip's offset and transform applied, sizes scaled, colours faded by
 * their transform's opacity, so a golden does not change with how a transform is stored.
 */
public final class DrawListDump {

    private static final String[] KINDS = {"empty", "box", "shadow", "glyph", "image", "nine-slice", "line",
        "triangle", "path", "columns"};

    private DrawListDump() {
    }

    /** The list as text. */
    @AllowAllocation("test helper")
    public static String of(DrawList recorded) {
        DrawList list = recorded.baked(); // where and how it draws: transforms and clip offsets applied
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            int header = list.header(i);
            int kind = Primitive.kind(header);
            if (kind == Primitive.EMPTY) {
                continue;
            }
            out.append(KINDS[kind]);
            if (Primitive.clip(header) != 0) {
                out.append(" clip=").append(Primitive.clip(header));
            }
            if (Primitive.flags(header) != 0) {
                out.append(" flags=").append(Primitive.flags(header));
            }
            out.append(' ').append(n(list.drawnX(i, Primitive.X0))).append(',').append(n(list.drawnY(i, Primitive.Y0)))
                .append(' ').append(n(list.drawnX(i, Primitive.X1))).append(',').append(n(list.drawnY(i, Primitive.Y1)))
                .append(" a=").append(color(list.colorAt(i, Primitive.COLOR_A)));
            boolean overVertices = kind == Primitive.PATH || kind == Primitive.COLUMNS;
            long b = overVertices ? 0 : list.colorAt(i, Primitive.COLOR_B);
            if (b != 0) {
                out.append(" b=").append(color(b));
            }
            switch (kind) {
                case Primitive.BOX -> {
                    out.append(" radii=").append(halves(list.intAt(i, Primitive.RADII))).append(' ')
                        .append(halves(list.intAt(i, Primitive.RADII + 1)));
                    float border = list.floatAt(i, Primitive.WIDTH);
                    if (border != 0) {
                        out.append(" border=").append(n(border)).append(' ')
                            .append(color(list.colorAt(i, Primitive.BORDER_COLOR)));
                    }
                }
                case Primitive.SHADOW -> out.append(" radii=").append(halves(list.intAt(i, Primitive.RADII))).append(' ')
                    .append(halves(list.intAt(i, Primitive.RADII + 1))).append(" sigma=")
                    .append(n(list.floatAt(i, Primitive.WIDTH)));
                case Primitive.GLYPH -> out.append(" tex=").append(list.intAt(i, Primitive.TEXTURE)).append(" range=")
                    .append(n(list.floatAt(i, Primitive.PIXEL_RANGE)));
                case Primitive.IMAGE, Primitive.NINE_SLICE -> out.append(" tex=").append(list.intAt(i, Primitive.TEXTURE));
                case Primitive.LINE -> out.append(" width=").append(n(list.floatAt(i, Primitive.WIDTH)));
                case Primitive.TRIANGLE -> out.append(" corners=").append(n(list.drawnX(i, Primitive.XA))).append(',')
                    .append(n(list.drawnY(i, Primitive.YA))).append(' ').append(n(list.drawnX(i, Primitive.XB)))
                    .append(',').append(n(list.drawnY(i, Primitive.YB))).append(' ')
                    .append(n(list.drawnX(i, Primitive.XC))).append(',').append(n(list.drawnY(i, Primitive.YC)));
                case Primitive.PATH -> out.append(" width=").append(n(list.floatAt(i, Primitive.WIDTH)))
                    .append(" stroke=").append(list.intAt(i, Primitive.STROKE)).append(" miter=")
                    .append(n(list.floatAt(i, Primitive.MITER_LIMIT))).append(" dashes=")
                    .append(halves(list.intAt(i, Primitive.DASHES))).append(' ')
                    .append(halves(list.intAt(i, Primitive.DASHES + 1))).append(" phase=")
                    .append(n(list.floatAt(i, Primitive.DASH_PHASE)));
                case Primitive.COLUMNS -> out.append(" x=").append(n(list.floatAt(i, Primitive.COLUMN_X)))
                    .append(" step=").append(n(list.floatAt(i, Primitive.COLUMN_STEP))).append(" width=")
                    .append(n(list.floatAt(i, Primitive.WIDTH))).append(" baseline=")
                    .append(n(list.floatAt(i, Primitive.BASELINE))).append(" flags=")
                    .append(list.intAt(i, Primitive.STROKE));
                default -> {
                }
            }
            if (overVertices) { // the vertices, as the primitive reads them
                int first = list.intAt(i, Primitive.FIRST_VERTEX);
                int count = list.intAt(i, Primitive.VERTEX_COUNT);
                out.append(" vertices=").append(count).append(':');
                for (int v = first; v < first + count; v++) {
                    out.append(' ').append(n(list.vertexAt(v, 0))).append(',').append(n(list.vertexAt(v, 1)));
                    if (kind == Primitive.COLUMNS) {
                        out.append(',').append(n(list.vertexAt(v, 2))).append(',').append(n(list.vertexAt(v, 3)));
                    }
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Fails unless {@code list} dumps to the golden {@code file}; writes the file instead when it is missing or the
     * system property {@code truesim.updateGoldens} is true.
     */
    @AllowAllocation("test helper")
    public static void assertMatches(DrawList list, Path file) {
        String actual = of(list);
        try {
            if (Boolean.getBoolean("truesim.updateGoldens") || !Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                return;
            }
            String expected = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!expected.equals(actual)) {
                throw new AssertionError("draw list differs from golden " + file + "\nexpected:\n" + expected
                    + "\nactual:\n" + actual);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AllowAllocation("test helper")
    private static String n(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    @AllowAllocation("test helper")
    private static String halves(int word) {
        return n(Float.float16ToFloat((short) word)) + "," + n(Float.float16ToFloat((short) (word >>> 16)));
    }

    @AllowAllocation("test helper")
    private static String color(long rgba) {
        return "(" + n(Rgba.r(rgba)) + " " + n(Rgba.g(rgba)) + " " + n(Rgba.b(rgba)) + " " + n(Rgba.a(rgba)) + ")";
    }
}
