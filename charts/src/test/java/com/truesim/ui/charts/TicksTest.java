package com.truesim.ui.charts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Tick generation and labels: nice numbers, log, symlog, time and categories; the label formats. */
class TicksTest {

    private final Rig rig = new Rig(Rig.DEFAULT, null);

    private Axis axis(Scale scale, double min, double max) {
        Chart c = rig.chart("t");
        Axis a = c.addAxis("a" + System.nanoTime(), Axis.Side.BOTTOM, scale);
        a.range().set(min, max);
        return a;
    }

    private static List<Double> majors(Ticks t) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < t.count(); i++) {
            if (t.major(i)) {
                out.add(t.value(i));
            }
        }
        return out;
    }

    private static List<String> labels(Ticks t) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.count(); i++) {
            if (t.major(i)) {
                out.add(t.label(i).toString());
            }
        }
        return out;
    }

    @Test
    void niceSteps() {
        assertThat(Ticks.niceStep(0.13)).isEqualTo(0.1);
        assertThat(Ticks.niceStep(0.2)).isEqualTo(0.2);
        assertThat(Ticks.niceStep(3)).isEqualTo(2);
        assertThat(Ticks.niceStep(3.3)).isEqualTo(5);
        assertThat(Ticks.niceStep(7.5)).isEqualTo(10);
        assertThat(Ticks.niceStepUp(3)).isEqualTo(5);
        assertThat(Ticks.niceStepUp(0)).isEqualTo(1);
        assertThat(Ticks.niceStep(1000)).isEqualTo(1000);
        assertThat(Ticks.niceStep(0)).isEqualTo(1);
        assertThat(Ticks.niceStep(Double.NaN)).isEqualTo(1);
    }

    @Test
    void linearTicksAreNiceNumbersAboutTheSpacingApart() {
        Axis a = axis(Scale.LINEAR, -0.03, 1.07);
        Ticks t = a.ticks;
        t.generate(a, 400, 80, 40, true);
        assertThat(majors(t)).containsExactly(0.0, 0.2, 0.4, 0.6, 0.8, 1.0);
        assertThat(labels(t)).containsExactly("0", "0.2", "0.4", "0.6", "0.8", "1");
        assertThat(t.step()).isEqualTo(0.2);
        assertThat(t.count()).isGreaterThan(t.majors()); // minors
        for (int i = 0; i < t.count(); i++) {
            assertThat(t.value(i)).isBetween(-0.03, 1.07);
        }
        // fifths of a 0.2 step are... quarters
        long minorsBetween = countBetween(t, 0.2, 0.4);
        assertThat(minorsBetween).isEqualTo(3);
        t.generate(a, 400, 80, 40, false);
        assertThat(t.count()).isEqualTo(t.majors());
        // the limit caps the number of majors
        t.generate(a, 4000, 1, 5, false);
        assertThat(t.majors()).isLessThanOrEqualTo(6);
        // nothing for no room or an empty range
        t.generate(a, 0, 80, 40, true);
        assertThat(t.count()).isZero();
    }

    private static long countBetween(Ticks t, double lo, double hi) {
        long n = 0;
        for (int i = 0; i < t.count(); i++) {
            if (!t.major(i) && t.value(i) > lo + 1e-9 && t.value(i) < hi - 1e-9) {
                n++;
            }
        }
        return n;
    }

    @Test
    void largeAndTinyNumbersUseScientificLabels() {
        Axis a = axis(Scale.LINEAR, 1e8, 5e8);
        a.ticks.generate(a, 400, 80, 40, false);
        assertThat(labels(a.ticks)).contains("1e8", "2e8", "5e8");
        Axis b = axis(Scale.LINEAR, 1e-7, 5e-7);
        b.ticks.generate(b, 400, 80, 40, false);
        assertThat(labels(b.ticks)).contains("1e-7", "2e-7");
        Axis c = axis(Scale.LINEAR, -2.5, 2.5);
        c.ticks.generate(c, 500, 50, 40, false);
        assertThat(labels(c.ticks)).contains("-2", "-1", "0", "1", "2");
    }

    @Test
    void logTicksArePowersOfTenWithMinors() {
        Axis a = axis(Scale.LOG, 1, 1e4);
        a.ticks.generate(a, 400, 60, 40, true);
        assertThat(majors(a.ticks)).containsExactly(1.0, 10.0, 100.0, 1000.0, 10000.0);
        assertThat(labels(a.ticks)).containsExactly("1", "10", "100", "1000", "10000");
        assertThat(a.ticks.count()).isGreaterThan(30); // 2..9 in each decade
        // narrow decades: every other one, the others minor
        Axis b = axis(Scale.LOG, 1e-10, 1e10);
        b.ticks.generate(b, 300, 60, 40, true);
        assertThat(b.ticks.step()).isGreaterThan(1);
        assertThat(labels(b.ticks)).contains("1e-10", "1", "1e10");
        // within about a decade: plain linear ticks
        Axis c = axis(Scale.LOG, 2, 9);
        c.ticks.generate(c, 400, 80, 40, true);
        assertThat(majors(c.ticks)).contains(2.0, 4.0, 6.0, 8.0);
        assertThat(c.ticks.step()).isZero();
    }

    @Test
    void symlogTicksAreZeroAndSignedPowersThinnedToTheSpacing() {
        Axis a = axis(Scale.SYMLOG, -1e4, 1e4);
        a.ticks.generate(a, 600, 40, 40, true);
        List<Double> m = majors(a.ticks);
        assertThat(m).contains(0.0, 10.0, -10.0, 10000.0, -10000.0).doesNotContain(1.0); // 1 is too near 0 here
        assertThat(labels(a.ticks)).contains("0", "10", "-10");
        // pixels between accepted majors stay at least the spacing (in the transformed scale)
        a.map(0, 600);
        List<Float> px = new ArrayList<>();
        for (double v : m) {
            px.add(a.toPixel(v));
        }
        px.sort(Float::compare);
        for (int i = 1; i < px.size(); i++) {
            assertThat(px.get(i) - px.get(i - 1)).isGreaterThanOrEqualTo(39.9f);
        }
        // within the linear part: linear ticks
        Axis b = axis(Scale.SYMLOG, -0.5, 0.5);
        b.ticks.generate(b, 400, 80, 40, true);
        assertThat(majors(b.ticks)).contains(0.0, 0.2, -0.2);
        // all positive
        Axis c = axis(Scale.SYMLOG, 5, 1e6);
        c.ticks.generate(c, 600, 40, 40, false);
        assertThat(majors(c.ticks)).contains(10.0, 1e6).doesNotContain(0.0);
    }

    @Test
    void categoryTicksAreWholeNumbersNamedAndOnlyInTheCategories() {
        Axis a = axis(Scale.CATEGORY, -0.5, 4.5);
        a.categories(List.of("a", "b", "c"));
        a.ticks.generate(a, 500, 50, 40, true);
        assertThat(majors(a.ticks)).containsExactly(0.0, 1.0, 2.0);
        assertThat(labels(a.ticks)).containsExactly("a", "b", "c");
        Axis b = axis(Scale.CATEGORY, -0.5, 100.5);
        b.ticks.generate(b, 200, 50, 40, true);
        assertThat(b.ticks.step()).isGreaterThanOrEqualTo(1);
        assertThat(labels(b.ticks)).contains("0", "100");
        Axis c = axis(Scale.CATEGORY, 5, 9);
        c.categories(List.of("a", "b"));
        c.ticks.generate(c, 200, 50, 40, true);
        assertThat(c.ticks.count()).isZero();
    }

    @Test
    void timeTicksFollowTheCalendar() {
        double day = 1_730_678_400; // 2024-11-04 00:00 UTC
        // hours
        Axis a = axis(Scale.TIME, day, day + 86_400);
        a.ticks.generate(a, 600, 80, 40, false);
        assertThat(labels(a.ticks)).contains("00:00", "06:00", "12:00");
        assertThat(a.ticks.step()).isEqualTo(10_800);
        // seconds
        Axis b = axis(Scale.TIME, day + 3600, day + 3660);
        b.ticks.generate(b, 600, 80, 40, false);
        assertThat(labels(b.ticks)).contains("01:00:00", "01:00:10");
        // sub-second
        Axis c = axis(Scale.TIME, day + 1.5, day + 1.75);
        c.ticks.generate(c, 500, 80, 40, false);
        assertThat(labels(c.ticks)).contains("00:01.50", "00:01.55");
        // days, months and years
        Axis d = axis(Scale.TIME, day, day + 20 * 86_400);
        d.ticks.generate(d, 600, 80, 40, false);
        assertThat(labels(d.ticks)).contains("11-05", "11-07");
        Axis e = axis(Scale.TIME, day, day + 400 * 86_400.0);
        e.ticks.generate(e, 600, 80, 40, false);
        assertThat(labels(e.ticks)).contains("2025-01", "2025-03");
        for (int i = 0; i < e.ticks.count(); i++) {
            LocalDate date = LocalDate.ofInstant(Instant.ofEpochSecond((long) e.ticks.value(i)), ZoneOffset.UTC);
            assertThat(date.getDayOfMonth()).isEqualTo(1);
        }
        Axis f = axis(Scale.TIME, day, day + 50 * 365.25 * 86_400);
        f.ticks.generate(f, 600, 80, 40, false);
        assertThat(labels(f.ticks)).contains("2030", "2040", "2050");
        // a time zone shifts labels and alignment
        Axis g = axis(Scale.TIME, day, day + 86_400);
        g.timeOffset(3600);
        g.ticks.generate(g, 600, 80, 40, false);
        assertThat(labels(g.ticks)).contains("03:00");
        assertThat(majors(g.ticks)).contains(day + 2 * 3600);
    }

    @Test
    void numberFormats() {
        assertThat(TickFormat.decimals(0.25)).isEqualTo(2);
        assertThat(TickFormat.decimals(0.2)).isEqualTo(1);
        assertThat(TickFormat.decimals(2.5)).isEqualTo(1);
        assertThat(TickFormat.decimals(50)).isZero();
        assertThat(TickFormat.decimals(0)).isZero();
        assertThat(TickFormat.number(new StringBuilder(), 0.30000000000000004, 0.1).toString()).isEqualTo("0.3");
        assertThat(TickFormat.number(new StringBuilder(), 1e-20, 0.1).toString()).isEqualTo("0");
        assertThat(TickFormat.number(new StringBuilder(), Double.NaN, 0.1).toString()).isEqualTo("NaN");
        assertThat(TickFormat.scientific(new StringBuilder(), 9.9999e7, 1e6).toString()).isEqualTo("1e8");
        assertThat(TickFormat.scientific(new StringBuilder(), 1.25e8, 1e6).toString()).isEqualTo("1.25e8");
        assertThat(TickFormat.scientific(new StringBuilder(), -2.5e9, 1e8).toString()).isEqualTo("-2.5e9");
        assertThat(TickFormat.scientific(new StringBuilder(), 0, 1).toString()).isEqualTo("0");
        assertThat(TickFormat.scientific(new StringBuilder(), Double.POSITIVE_INFINITY, 1).toString()).isEqualTo("∞");
        assertThat(TickFormat.power(new StringBuilder(), -3).toString()).isEqualTo("0.001");
        assertThat(TickFormat.power(new StringBuilder(), 7).toString()).isEqualTo("1e7");
        assertThat(TickFormat.significant(new StringBuilder(), 1234.5678).toString()).isEqualTo("1235");
        assertThat(TickFormat.significant(new StringBuilder(), 0.012345).toString()).isEqualTo("0.0123");
        assertThat(TickFormat.significant(new StringBuilder(), 1.5e9).toString()).isEqualTo("1.5e9");
        assertThat(TickFormat.significant(new StringBuilder(), 0).toString()).isEqualTo("0");
        assertThat(TickFormat.pow10(-30)).isCloseTo(1e-30, org.assertj.core.data.Percentage.withPercentage(1e-9));
        assertThat(TickFormat.exponent(1000)).isEqualTo(3);
        assertThat(TickFormat.exponent(999.999)).isEqualTo(2);
    }

    @Test
    void civilDatesMatchJavaTime() {
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < 2000; i++) {
            long days = r.nextLong(-1_000_000, 1_000_000);
            LocalDate d = LocalDate.ofEpochDay(days);
            long civil = TickFormat.civilFromDays(days);
            assertThat(TickFormat.year(civil)).isEqualTo(d.getYear());
            assertThat(TickFormat.month(civil)).isEqualTo(d.getMonthValue());
            assertThat(TickFormat.day(civil)).isEqualTo(d.getDayOfMonth());
            assertThat(TickFormat.daysFromCivil(d.getYear(), d.getMonthValue(), d.getDayOfMonth())).isEqualTo(days);
        }
        // time labels against java.time
        DateTimeFormatter hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
        for (int i = 0; i < 200; i++) {
            long s = r.nextLong(0, 4_000_000_000L);
            assertThat(TickFormat.time(new StringBuilder(), s, 5).toString()).isEqualTo(hms.format(
                Instant.ofEpochSecond(s)));
        }
        assertThat(TickFormat.time(new StringBuilder(), 59.9999999, 0.001).toString()).isEqualTo("01:00.000");
        assertThat(TickFormat.time(new StringBuilder(), Double.NaN, 1).toString()).isEqualTo("NaN");
        assertThat(TickFormat.time(new StringBuilder(), 1_730_678_400, 400 * 86_400.0).toString()).isEqualTo("2024");
    }
}
