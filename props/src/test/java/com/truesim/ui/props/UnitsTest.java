package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedValue;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.bind.Converter;
import com.truesim.ui.props.PropsGallery.Behaviour;
import com.truesim.ui.props.annotations.Angle;
import com.truesim.ui.props.annotations.Hidden;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.ReadOnly;
import com.truesim.ui.props.annotations.ShowIf;
import com.truesim.ui.props.annotations.Step;
import com.truesim.ui.props.annotations.Unit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The small rules: conditions, number types and conversions, registries, editor fallbacks. */
class UnitsTest {

    @Test
    void conditionsHold() {
        assertThat(Steps.holds(true, "")).isTrue();
        assertThat(Steps.holds(false, "")).isFalse();
        assertThat(Steps.holds("", "")).isFalse();
        assertThat(Steps.holds("x", "")).isTrue();
        assertThat(Steps.holds(List.of(), "")).isFalse();
        assertThat(Steps.holds(List.of(1), "")).isTrue();
        assertThat(Steps.holds(null, "")).isFalse();
        assertThat(Steps.holds(3, "")).isTrue();
        assertThat(Steps.holds(Behaviour.GUARD, "GUARD")).isTrue();
        assertThat(Steps.holds(Behaviour.GUARD, "PATROL")).isFalse();
        assertThat(Steps.holds(5, "5")).isTrue();
        assertThat(Steps.holds(null, "5")).isFalse();
    }

    @Test
    void numberTypes() {
        for (Class<?> t : List.of(double.class, Double.class, float.class, Float.class, int.class, Integer.class,
            long.class, Long.class, short.class, Short.class, byte.class, Byte.class)) {
            assertThat(Editors.isNumber(t)).as(t.getName()).isTrue();
            Converter<Object, Double> c = Editors.numbers(t);
            Object back = c.from(2.6);
            assertThat(((Number) back).doubleValue()).isCloseTo(Editors.integral(t) ? 3 : 2.6,
                org.assertj.core.data.Offset.offset(1e-6));
            assertThat(c.to(back)).isCloseTo(((Number) back).doubleValue(), org.assertj.core.data.Offset.offset(1e-6));
            assertThat(((Number) c.from(-2.6)).doubleValue()).isCloseTo(Editors.integral(t) ? -3 : -2.6,
                org.assertj.core.data.Offset.offset(1e-6));
        }
        assertThat(Editors.numbers(int.class).to("x")).isZero();
        assertThat(Editors.numbers(int.class).from(null)).isEqualTo(0);
        assertThat(Editors.isNumber(boolean.class)).isFalse();
        assertThat(Editors.isNumber(Character.class)).isFalse();
        assertThat(Editors.isNumber(String.class)).isFalse();
        assertThat(Editors.unboxed(Boolean.class)).isEqualTo(boolean.class);
        assertThat(Editors.unboxed(Character.class)).isEqualTo(char.class);
        assertThat(Editors.toInt(Editors.fromInt(0x80402010))).isEqualTo(0x80402010);
        assertThat(Editors.enumLabel(Behaviour.SEARCH)).isEqualTo("Search");
        assertThat(Editors.enumLabel(Behaviour.GUARD)).isEqualTo("Guard");
    }

    @Test
    void registries() {
        Editors e = Editors.standard().copy().forType(Behaviour.class, "radio").forAnnotation(Unit.class, "readonly");
        Property<Object, Object> mode = Property.builder("mode", Behaviour.class).getter(o -> o).build();
        assertThat(e.byType(mode)).isEqualTo("radio");
        Property<Object, Object> meters = Property.builder("m", double.class).getter(o -> o)
            .annotations(Hints.unit("m")).build();
        assertThat(e.byAnnotations(meters, Map.of())).isEqualTo("readonly");
        assertThat(e.get("nope")).isSameAs(e.get("readonly"));
        assertThat(Editors.standard().byType(Property.builder("c", CharSequence.class).getter(o -> o).build()))
            .isEqualTo("text");
        assertThat(Editors.standard().byType(Property.builder("c", Map.class).getter(o -> o).build()))
            .isEqualTo("collection");
        assertThat(Editors.standard().byType(Property.builder("c", Character.class).getter(o -> o).build()))
            .isEqualTo("readonly");
        assertThat(Editors.standard().byAnnotations(Property.builder("e", Behaviour.class).getter(o -> o)
            .annotations(Hints.enumStyle(com.truesim.ui.props.annotations.EnumStyle.Kind.DROPDOWN)).build(),
            Map.of())).isEqualTo("dropdown");
        assertThat(Editors.standard().byAnnotations(Property.builder("e", String.class).getter(o -> o)
            .annotations(Hints.color(false)).build(), Map.of())).isNull();
    }

    /** Annotation corners. */
    public record Corners(
        @Angle @Range(min = -3.14159, max = 3.14159) double yaw,
        @Step(0.25) double quarter,
        @Hidden int gone,
        @ShowIf(value = "missing") int orphan,
        @ShowIf(value = "mode", is = "GUARD") int guarded,
        Behaviour mode,
        @ReadOnly String fixed,
        @com.truesim.ui.props.annotations.EnumStyle(com.truesim.ui.props.annotations.EnumStyle.Kind.SEGMENTED)
        String notAnEnum,
        @Range(min = 0, max = 9) byte tiny,
        Character letter) {
    }

    @Test
    void editorCorners() {
        Rig r = new Rig();
        VersionedValue<Corners> v = r.c.clock().value(new Corners(0.5, 0.5, 1, 2, 3, Behaviour.PATROL, "f", "x",
            (byte) 2, 'c'));
        PropertyPanel<Corners> p = PropertyBuilder.forType(Corners.class).build(r.c, v);
        r.add(p.element()).settle();
        assertThat(p.row("gone").shown()).isFalse();
        assertThat(p.row("orphan").shown()).isTrue();
        assertThat(p.row("guarded").shown()).isFalse();
        v.set(new Corners(0.5, 0.5, 1, 2, 3, Behaviour.GUARD, "f", "x", (byte) 2, 'c'));
        r.run();
        assertThat(p.row("guarded").shown()).isTrue();
        Binding<Object, Double> yaw = PipelineTest.binding(p, "yaw");
        assertThat(yaw.control().get()).isCloseTo(28.6, org.assertj.core.data.Offset.offset(0.05));
        Binding<Object, Double> quarter = PipelineTest.binding(p, "quarter");
        quarter.control().set(0.75);
        r.run();
        assertThat(v.get().quarter()).isEqualTo(0.75);
        Binding<Object, Double> tiny = PipelineTest.binding(p, "tiny");
        tiny.control().set(4.0);
        r.run();
        assertThat(v.get().tiny()).isEqualTo((byte) 4);
        assertThat(p.row("fixed").readOnly()).isTrue();
        assertThat(p.row("notAnEnum").editorName()).isEqualTo("text");
        assertThat(p.row("letter").editorName()).isEqualTo("readonly");
        p.builder().editors().forType(Character.class, "nope");
        assertThat(p.builder().editors().byType(p.row("letter").property())).isEqualTo("readonly");
        Edit e = new Edit("p", "set", 1, 2, com.truesim.ui.core.bind.Bindable.of(r.c.clock().value((Object) 0)));
        e.redo();
        assertThat(e.target().get()).isEqualTo(2);
        AnnotationHandler<Unit> plain = () -> Unit.class;
        assertThat(plain.editor(null, null)).isNull();
        assertThat(plain.validator(null, null)).isNull();
        plain.facts(null, null, new java.util.ArrayList<>());
        assertThat(PropertyEditor.of(p.element()).element()).isSameAs(p.element());
    }
}
