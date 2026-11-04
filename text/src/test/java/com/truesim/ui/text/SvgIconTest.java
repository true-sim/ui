package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.text.jdk.JdkSdfGenerator;
import org.junit.jupiter.api.Test;

/** SVG icons parsed into outlines in ems: shapes, path commands, transforms, sizes, errors, and their distance field. */
class SvgIconTest {

    private static float[] bounds(SvgIcon icon) {
        float[] b = new float[4];
        icon.outline().bounds(b);
        return b;
    }

    @Test
    void theViewBoxIsOneEmHighYUp() {
        SvgIcon icon = SvgIcon.parse("""
            <?xml version="1.0"?>
            <!-- a test icon -->
            <svg xmlns="http://www.w3.org/2000/svg" width="48" height="24" viewBox="0 0 20 10">
              <rect x="0" y="0" width="10" height="5"/>
            </svg>""");
        assertThat(icon.aspect()).isEqualTo(2);
        assertThat(icon.width()).isEqualTo(48);
        assertThat(icon.height()).isEqualTo(24);
        // the rectangle covers the view box's top left quarter: x 0 to 1 em, y 0.5 to 1 em (y up)
        assertThat(bounds(icon)).containsExactly(new float[] {0, 0.5f, 1, 1}, within(1e-6f));
        assertThat(icon.outline().verbs()[0]).isEqualTo(GlyphOutline.MOVE);
    }

    @Test
    void everyPathCommandRelativeAndAbsolute() {
        SvgIcon icon = SvgIcon.parse("<svg viewBox='0 0 100 100'><path d='M10 10 L90 10 H80 V20 h-5 v5 l-5,5"
            + " C70 40 60 40 50 30 S30 20 20 30 Q15 35 10 30 T10 20 A5 5 0 0 1 10 10 Z"
            + " m20 50 c0 10 10 10 10 0 s10-10 10 0 q5 5 10 0 t10 0 a10 10 0 1 0 -40 0 z M1.5.5 1-1e1z'/></svg>");
        GlyphOutline o = icon.outline();
        int moves = 0;
        int cubics = 0;
        int quads = 0;
        for (int i = 0; i < o.verbCount(); i++) {
            moves += o.verbs()[i] == GlyphOutline.MOVE ? 1 : 0;
            cubics += o.verbs()[i] == GlyphOutline.CUBIC ? 1 : 0;
            quads += o.verbs()[i] == GlyphOutline.QUAD ? 1 : 0;
        }
        assertThat(moves).isEqualTo(3);
        assertThat(quads).isEqualTo(4);
        assertThat(cubics).isGreaterThanOrEqualTo(4 + 3); // c, s, C, S and the arcs, a quarter turn a curve at most
        float[] p = o.points();
        assertThat(p[0]).isEqualTo(0.1f, within(1e-6f));
        assertThat(p[1]).isEqualTo(0.9f, within(1e-6f));
        assertThat(p[2]).as("L90 10").isEqualTo(0.9f, within(1e-6f));
    }

    @Test
    void shapesAndTransforms() {
        SvgIcon icon = SvgIcon.parse("""
            <svg viewBox="0 0 100 100">
              <g transform="translate(50 50)">
                <circle cx="0" cy="0" r="10"/>
                <g transform="scale(2)"><ellipse cx="0" cy="0" rx="5" ry="2"/></g>
              </g>
              <rect x="0" y="0" width="20" height="10" rx="3"/>
              <rect x="0" y="0" width="20" height="10" fill="none"/>
              <polygon points="0,100 10,90 20,100"/>
              <polyline points="80 100 90 90 100 100"/>
              <path d="M50 50" transform="rotate(45 50 50) skewX(10) skewY(10) matrix(1 0 0 1 0 0)"/>
              <line x1="0" y1="0" x2="10" y2="10"/>
              <text>ignored</text>
            </svg>""");
        float[] b = bounds(icon);
        assertThat(b[0]).isEqualTo(0, within(1e-6f));
        assertThat(b[2]).isEqualTo(1, within(1e-6f));
        assertThat(b[1]).isEqualTo(0, within(1e-6f));
        assertThat(b[3]).isEqualTo(1, within(1e-6f));
        GlyphOutline circleOnly = SvgIcon.parse("<svg viewBox='0 0 100 100'><g transform='translate(50 50)'>"
            + "<circle r='10'/></g></svg>").outline();
        float[] c = new float[4];
        circleOnly.bounds(c);
        assertThat(c[0]).as("the circle's left: centre 0.5 em, radius 0.1").isEqualTo(0.4f, within(1e-3f));
        assertThat(c[3]).isEqualTo(0.6f, within(0.01f)); // control points may pass the circle slightly
    }

    @Test
    void aWidthAndHeightWithoutAViewBoxAndBadDocumentsAreRefused() {
        SvgIcon icon = SvgIcon.parse("<svg width='16px' height='8'><rect width='16' height='8'/></svg>");
        assertThat(icon.aspect()).isEqualTo(2);
        assertThat(icon.width()).isEqualTo(16);
        SvgIcon relative = SvgIcon.parse("<svg width='100%' viewBox='0 0 4 4'><circle cx='2' cy='2' r='2'/></svg>");
        assertThat(relative.width()).isEqualTo(4);
        assertThatThrownBy(() -> SvgIcon.parse("<html/>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg/>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 0 1'/>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 1 1'><path d='10 10'/></svg>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 1 1'><path d='M1 x'/></svg>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 1 1'><path d='M1 1 K2 2'/></svg>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 1 1'><path d='M0 0 A1 1 0 2 1 1 1'/></svg>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 1 1'><g transform='matrix(1 2)'/></svg>"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SvgIcon.parse("<svg viewBox='0 0 1 1'><rect width='x1'/></svg>"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIconsDistanceFieldIsInsideItsShapesAndOutsideItsHoles() {
        // a ring: the outer circle clockwise, the hole counter-clockwise
        SvgIcon ring = SvgIcon.parse("<svg viewBox='0 0 32 32'><path d='M16 2 A14 14 0 1 1 15.99 2 Z"
            + " M16 10 A6 6 0 1 0 16.01 10 Z'/></svg>");
        MsdfGeometry g = MsdfGeometry.fit(bounds(ring), 40, 6);
        byte[] pixels = new JdkSdfGenerator().generate(ring.outline(), g);
        // the ring's band at 0.8 em right of its left edge's... sample the texel at the centre (a hole) and in the band
        int cx = Math.round((0.5f - g.planeLeft()) / (g.planeRight() - g.planeLeft()) * g.width());
        int cy = Math.round((g.planeTop() - 0.5f) / (g.planeTop() - g.planeBottom()) * g.height());
        int band = Math.round((0.5f + 10 / 32f - g.planeLeft()) / (g.planeRight() - g.planeLeft()) * g.width());
        int centre = pixels[(cy * g.width() + cx) * 4 + 3] & 0xFF;
        int inBand = pixels[(cy * g.width() + band) * 4 + 3] & 0xFF;
        assertThat(centre).as("the hole").isLessThan(128);
        assertThat(inBand).as("the ring").isGreaterThan(128);
    }
}
