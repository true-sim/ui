package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.state.VersionedList;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.style.value.Color;
import java.io.Serializable;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The renderer registry's resolution order, the built-in renderers and number formatting. */
class RenderersTest {

    interface Shape {
    }

    interface Round extends Shape {
    }

    static class Circle implements Round, Serializable {
        private static final long serialVersionUID = 1;
    }

    static final class Disc extends Circle {
        private static final long serialVersionUID = 1;
    }

    record Point(int x, int y) {
    }

    record Named(String name) implements Shape {
    }

    static ValueRenderer<Object> marker(String id) {
        return new Renderers.TextRenderer<>(id);
    }

    @Test
    void typesResolveInTheDesignsOrder() {
        ValueRenderers r = new ValueRenderers();
        ValueRenderer<Object> shapes = marker("shape");
        ValueRenderer<Object> circles = marker("circle");
        ValueRenderer<Object> records = marker("record");
        ValueRenderer<Object> exactDisc = marker("disc");
        r.register(Shape.class, shapes);
        assertThat(r.find(Disc.class)).isSameAs(shapes); // through Round's parent interface
        r.register(Circle.class, circles);
        assertThat(r.find(Disc.class)).isSameAs(circles); // a superclass before interfaces
        r.register(Disc.class, exactDisc);
        assertThat(r.find(Disc.class)).isSameAs(exactDisc); // the exact class first
        assertThat(r.find(Point.class).viewType()).isEqualTo("text"); // no records renderer: text
        r.records(records);
        assertThat(r.find(Point.class)).isSameAs(records);
        assertThat(r.find(Named.class)).isSameAs(records); // records before interfaces
        assertThat(r.find(Integer.class).viewType()).isEqualTo("number");
        assertThat(r.find(StringBuilder.class).viewType()).isEqualTo("text");
        assertThat(r.find(Boolean.class).viewType()).isEqualTo("value-check");
        assertThat(r.find(Thread.State.class).viewType()).isEqualTo("enum");
        assertThat(r.find(Vec3.class).viewType()).isEqualTo(r.find(Vec2.class).viewType());
        assertThat(r.find(Color.class)).isNotNull();
        assertThat(r.find(Object.class).viewType()).isEqualTo("text");
        r.name("shapes", shapes);
        assertThat(r.named("shapes")).isSameAs(shapes);
        assertThat(r.named("none")).isNull();
    }

    @Test
    void theDynamicRendererChoosesPerValueAndRecyclesPerRendererAndKind() {
        DataFrame f = new DataFrame();
        VersionedList<Object> items = f.ui.store().clock().list();
        items.add("text");
        items.add(42);
        items.add(2.5);
        items.add(true);
        items.add(Thread.State.RUNNABLE);
        items.add(new Vec2(1, 2));
        items.add(new Vec3(1, 2.5, -3));
        items.add(new Vec4(0.125, 0, 0, 1));
        items.add(Color.hex("#ff8000"));
        items.add(null);
        items.add(new Point(1, 2));
        ListView<Object> list = ListView.of(f.ui, "Mixed", items);
        f.ui.root().add(list.root());
        f.run();
        assertThat(label(f, list, 0)).isEqualTo("text");
        assertThat(label(f, list, 1)).isEqualTo("42");
        assertThat(label(f, list, 2)).isEqualTo("2.5");
        Element check = content(f, list, 3);
        assertThat(check.is(UiState.CHECKED)).isTrue();
        assertThat(label(f, list, 4)).isEqualTo("RUNNABLE");
        assertThat(label(f, list, 5)).isEqualTo("1, 2");
        assertThat(label(f, list, 6)).isEqualTo("1, 2.5, -3");
        assertThat(label(f, list, 7)).isEqualTo("0.125, 0, 0, 1");
        assertThat(label(f, list, 8)).isEqualTo("#ff8000ff");
        assertThat(label(f, list, 9)).isEmpty();
        assertThat(label(f, list, 10)).isEqualTo("Point[x=1, y=2]");
        // a number becoming text changes kind: another row element
        int created = list.created();
        items.set(1, "now text");
        f.run();
        assertThat(label(f, list, 1)).isEqualTo("now text");
        assertThat(list.created()).isLessThanOrEqualTo(created + 1);
        items.set(3, false);
        f.run();
        assertThat(content(f, list, 3).is(UiState.CHECKED)).isFalse();
    }

    static Element content(DataFrame f, ListView<?> list, int index) {
        return f.ui.store().element(f.ui.store().firstChild(list.rowElement(index).slot()));
    }

    static String label(DataFrame f, ListView<?> list, int index) {
        return f.ui.text(content(f, list, index)).toString();
    }

    @Test
    void numbersFormatWithoutTheJdk() {
        assertThat(format(0, 3)).isEqualTo("0");
        assertThat(format(-0.0004, 3)).isEqualTo("0");
        assertThat(format(1.5, 3)).isEqualTo("1.5");
        assertThat(format(-1.05, 2)).isEqualTo("-1.05");
        assertThat(format(0.007, 3)).isEqualTo("0.007");
        assertThat(format(12.3456789, 9)).isEqualTo("12.3456789");
        assertThat(format(3.999, 2)).isEqualTo("4");
        assertThat(format(1e300, 2)).startsWith("1").contains("e");
        assertThat(format(-1e300, 2)).startsWith("-1").contains("e");
        assertThat(format(Double.NaN, 2)).isEqualTo("NaN");
        assertThat(format(Double.POSITIVE_INFINITY, 2)).isEqualTo("∞");
        assertThat(format(Double.NEGATIVE_INFINITY, 2)).isEqualTo("-∞");
        Random random = new Random(3);
        for (int i = 0; i < 1000; i++) {
            double v = (random.nextDouble() - 0.5) * 1e6;
            assertThat(Double.parseDouble(format(v, 4))).isCloseTo(v, org.assertj.core.api.Assertions.within(1e-4));
        }
        assertThat(Format.hex(new StringBuilder(), 1, 0, 0.5f, 2).toString()).isEqualTo("#ff0080ff");
    }

    static String format(double v, int decimals) {
        return Format.append(new StringBuilder(), v, decimals).toString();
    }
}
