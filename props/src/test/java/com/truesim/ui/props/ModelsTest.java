package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.bind.Validator;
import com.truesim.ui.props.PropsGallery.Behaviour;
import com.truesim.ui.props.PropsGallery.Hold;
import com.truesim.ui.props.PropsGallery.Move;
import com.truesim.ui.props.PropsGallery.Position;
import com.truesim.ui.props.PropsGallery.Squad;
import com.truesim.ui.props.PropsGallery.Waypoint;
import com.truesim.ui.props.annotations.Addable;
import com.truesim.ui.props.annotations.EnumStyle;
import com.truesim.ui.props.annotations.Group;
import com.truesim.ui.props.annotations.Label;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.Step;
import com.truesim.ui.props.annotations.Tooltip;
import com.truesim.ui.props.annotations.Unit;
import com.truesim.ui.props.annotations.Validate;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Property metadata: generated and reflected models agree, sources merge by priority, hints, defaults, copies. */
class ModelsTest {

    /** A mutable class: bean properties, a public field, a read-only one, inherited ones. */
    public static class Base {
        @Label("Base value")
        private int base = 1;

        public int getBase() {
            return base;
        }

        public void setBase(int value) {
            base = value;
        }
    }

    /** The mutable class edited in place. */
    public static class Light extends Base {
        @Range(min = 0, max = 10)
        private double intensity = 2;
        private boolean on = true;
        public String name = "lamp";
        public final int id = 7;
        private int hidden = 3;
        public List<String> tags = new ArrayList<>();

        public double getIntensity() {
            return intensity;
        }

        public void setIntensity(double value) {
            intensity = value;
        }

        public boolean isOn() {
            return on;
        }

        public void setOn(boolean value) {
            on = value;
        }

        int hidden() {
            return hidden;
        }
    }

    record Plain(String a, int[] numbers, Map<String, List<Integer>> map, Set<Behaviour> set) {
    }

    @Test
    void generatedAndReflectedModelsAgree() {
        PropertyModel<Squad> g = PropertyModels.generated().model(Squad.class);
        PropertyModel<Squad> r = PropertyModels.reflection().model(Squad.class);
        assertThat(g.source()).isEqualTo("generated");
        assertThat(r.source()).isEqualTo("reflection");
        assertThat(g.properties()).hasSameSizeAs(r.properties());
        for (int i = 0; i < g.properties().size(); i++) {
            Property<Squad, ?> a = g.properties().get(i);
            Property<Squad, ?> b = r.properties().get(i);
            assertThat(a.name()).isEqualTo(b.name());
            assertThat(a.type()).isEqualTo(b.type());
            assertThat(a.elementType()).isEqualTo(b.elementType());
            assertThat(a.keyType()).isEqualTo(b.keyType());
            assertThat(a.index()).isEqualTo(i);
            assertThat(a.annotations().size()).as(a.name()).isEqualTo(b.annotations().size());
            for (Annotation x : a.annotations().list()) {
                assertThat(x).isEqualTo(b.annotation(x.annotationType()));
                assertThat(x.hashCode()).isEqualTo(b.annotation(x.annotationType()).hashCode());
            }
        }
        Squad s = PropsGallery.sample();
        for (int i = 0; i < g.properties().size(); i++) {
            @SuppressWarnings("unchecked")
            Property<Squad, Object> a = (Property<Squad, Object>) g.properties().get(i);
            @SuppressWarnings("unchecked")
            Property<Squad, Object> b = (Property<Squad, Object>) r.properties().get(i);
            assertThat(a.get(s)).isEqualTo(b.get(s));
            assertThat(a.with(s, a.get(s))).isEqualTo(b.with(s, b.get(s))).isEqualTo(s);
        }
        assertThat(g.property("waypoints").elementType()).isEqualTo(Waypoint.class);
        assertThat(g.property("stats").keyType()).isEqualTo(String.class);
        assertThat(g.property("stats").elementType()).isEqualTo(Integer.class);
        assertThat(PropertyModels.generated().model(PropsGallery.Command.class).subtypes())
            .containsExactly(Move.class, Hold.class);
        assertThat(PropertyModels.reflection().model(PropsGallery.Command.class).subtypes())
            .containsExactly(Move.class, Hold.class);
        assertThat(PropertyModels.generated().model(Waypoint.class).factory().get()).isEqualTo(Waypoint.DEFAULTS);
        assertThat(PropertyModels.generated().model(Position.class).factory().get()).isEqualTo(new Position(0, 0));
        assertThat(PropertyModels.generated().model(Move.class).annotation(Label.class).value()).isEqualTo("Move");
        assertThat(PropertyModels.generated().model(Move.class).label()).isEqualTo("Move");
        assertThat(PropertyModels.generated().model(Plain.class)).isNull(); // no annotations: not generated
        assertThat(PropertyModels.generated().model(int.class)).isNull();
        assertThat(new PropertyModels().of(Squad.class).source()).isEqualTo("generated");
        assertThat(new PropertyModels().of(Plain.class).source()).isEqualTo("reflection");
    }

    @Test
    void generatedAccessorsAreDirect() {
        Squad s = PropsGallery.sample();
        assertThat(PropsGallery_SquadProperties.roughness(s)).isEqualTo(0.35f);
        assertThat(PropsGallery_SquadProperties.withRoughness(s, 0.5f).roughness()).isEqualTo(0.5f);
        assertThat(PropsGallery_SquadProperties.ROUGHNESS.with(s, null).roughness()).isZero();
        assertThat(PropsGallery_SquadProperties.OUTLINE.with(s, null).outline()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reflectionReadsRecordsAndBeans() {
        PropertyModel<Plain> p = PropertyModels.reflection().model(Plain.class);
        assertThat(p.property("numbers").elementType()).isEqualTo(int.class);
        assertThat(p.property("map").elementType()).isEqualTo(List.class);
        assertThat(p.property("set").elementType()).isEqualTo(Behaviour.class);
        Plain made = p.factory().get();
        assertThat(made.a()).isEmpty();
        assertThat(made.numbers()).isEmpty();
        @SuppressWarnings("unchecked")
        Property<Plain, Object> a = (Property<Plain, Object>) p.property("a");
        assertThat(a.with(made, "x").a()).isEqualTo("x");

        PropertyModel<Light> m = PropertyModels.reflection().model(Light.class);
        assertThat(m.properties()).extracting(Property::name).containsExactly("base", "intensity", "on", "name", "id",
            "tags");
        assertThat(m.property("base").label()).isEqualTo("Base value");
        assertThat(m.property("intensity").annotation(Range.class).max()).isEqualTo(10);
        assertThat(m.property("id").writable()).isFalse();
        assertThat(m.property("tags").elementType()).isEqualTo(String.class);
        Light l = new Light();
        @SuppressWarnings("unchecked")
        Property<Light, Object> intensity = (Property<Light, Object>) m.property("intensity");
        assertThat(intensity.inPlace()).isTrue();
        assertThat(intensity.with(l, 5.0)).isSameAs(l);
        assertThat(l.getIntensity()).isEqualTo(5);
        intensity.with(l, null);
        assertThat(l.getIntensity()).isZero();
        @SuppressWarnings("unchecked")
        Property<Light, Object> name = (Property<Light, Object>) m.property("name");
        name.with(l, "torch");
        assertThat(l.name).isEqualTo("torch");
        assertThat(name.get(l)).isEqualTo("torch");
        assertThatThrownBy(() -> ((Property<Light, Object>) (Property<?, ?>) m.property("id")).with(l, 1))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(m.factory().get()).isInstanceOf(Light.class);
        assertThat(l.hidden()).isEqualTo(3);
        assertThat(PropertyModels.reflection().model(String.class)).isNull();
        assertThat(PropertyModels.reflection().model(Behaviour.class)).isNull();
        assertThat(PropertyModels.reflection().model(int[].class)).isNull();
    }

    @Test
    void sourcesMergeByPriority() {
        PropertySource settings = new PropertySource() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> PropertyModel<T> model(Class<T> type) {
                if (type != Position.class) {
                    return null;
                }
                PropertyModel.Builder<Position> b = PropertyModel.builder(Position.class).source("settings")
                    .annotations(Hints.label("Where"));
                b.property(Property.<Position, Double>builder("x", double.class).getter(Position::x)
                    .annotations(Hints.label("East"), Hints.unit("km")).build());
                b.property(Property.<Position, Double>builder("z", double.class).getter(p -> 0.0).build());
                return (PropertyModel<T>) b.build();
            }
        };
        PropertyModels models = new PropertyModels().register(settings);
        PropertyModel<Position> m = models.of(Position.class);
        assertThat(m.source()).isEqualTo("settings+generated");
        assertThat(m.label()).isEqualTo("Where");
        assertThat(m.properties()).extracting(Property::name).containsExactly("x", "z", "y");
        Property<Position, ?> x = m.property("x");
        assertThat(x.label()).isEqualTo("East");
        assertThat(x.annotation(Unit.class).value()).isEqualTo("km"); // the higher source's
        assertThat(x.annotation(Step.class).value()).isEqualTo(0.5); // the lower source's
        assertThat(x.writable()).isTrue(); // accessors from the source that can write
        assertThat(m.factory()).isNotNull();
        assertThat(models.of(Position.class)).isSameAs(m);
        models.invalidate();
        assertThat(models.of(Position.class)).isNotSameAs(m);
        assertThat(new PropertyModels(PropertyModels.reflection()).of(Integer.class).source()).isEqualTo("none");
        assertThat(PropertyModels.shared()).isSameAs(PropertyModels.shared());
        assertThat(PropertyModels.generatedName(Squad.class)).isEqualTo("com.truesim.ui.props.PropsGallery_SquadProperties");
    }

    @Test
    void hintsAreAnnotations() {
        Range r = Hints.range(0, 1);
        assertThat(r.min()).isZero();
        assertThat(r.annotationType()).isEqualTo(Range.class);
        assertThat(r).isEqualTo(Hints.range(0, 1)).isNotEqualTo(Hints.range(0, 2)).isNotEqualTo("x");
        assertThat(r.toString()).contains("Range");
        assertThat(Hints.addable(false).value()).isFalse();
        assertThat(Hints.enumStyle(EnumStyle.Kind.RADIO).value()).isEqualTo(EnumStyle.Kind.RADIO);
        assertThat(Hints.validate(NotBlank.class).value()).containsExactly(NotBlank.class);
        assertThat(Hints.validate(NotBlank.class)).isEqualTo(Hints.validate(NotBlank.class));
        assertThat(Hints.validate(NotBlank.class).hashCode()).isEqualTo(Hints.validate(NotBlank.class).hashCode());
        List<Annotation> all = List.of(Hints.label("a"), Hints.tooltip("t"), Hints.step(1), Hints.color(true),
            Hints.angle(false), Hints.slider(), Hints.field(), Hints.multiline(4), Hints.password(), Hints.hidden(),
            Hints.readOnly(), Hints.group("g"), Hints.order(2), Hints.foldout(false), Hints.showIf("a", "b"),
            Hints.assetType("mesh"), Hints.listStyle("chips"), Hints.removable(true), Hints.reorderable(true),
            Hints.minSize(1), Hints.maxSize(2), Hints.unique(), Hints.elementLabel("name"), Hints.unit("m"));
        assertThat(Annotations.of(all.toArray(new Annotation[0])).size()).isEqualTo(all.size());
        assertThatThrownBy(() -> Hints.annotation(Range.class, "min", 0.0)).hasMessageContaining("max");
        assertThatThrownBy(() -> Hints.annotation(Label.class, "value", "a", "nope", 1))
            .hasMessageContaining("nope");
        assertThatThrownBy(() -> Hints.annotation(Label.class, "value")).hasMessageContaining("pairs");
    }

    /** A validator for {@code @Validate}. */
    public static final class NotBlank implements Validator<Object> {
        @Override
        public String validate(Object value) {
            return value == null || value.toString().isBlank() ? "blank" : null;
        }
    }

    @Test
    void annotationsAndProperties() {
        Annotations a = Annotations.of(Hints.label("a"), Hints.label("b"), null, Hints.group("g"));
        assertThat(a.size()).isEqualTo(2);
        assertThat(a.get(Label.class).value()).isEqualTo("a");
        assertThat(a.has(Group.class)).isTrue();
        assertThat(a.has(Tooltip.class)).isFalse();
        assertThat(a.with(Hints.label("c")).get(Label.class).value()).isEqualTo("c");
        assertThat(a.over(Annotations.NONE)).isSameAs(a);
        assertThat(Annotations.NONE.over(a)).isSameAs(a);
        assertThat(a.over(Annotations.of(Hints.tooltip("t"), Hints.label("z"))).list()).hasSize(3);
        assertThat(a.toString()).isEqualTo("[@Label, @Group]");
        assertThat(Annotations.of()).isSameAs(Annotations.NONE);
        assertThat(Property.words("baseColor")).isEqualTo("Base color");
        assertThat(Property.words("HDR")).isEqualTo("HDR");
        assertThat(Property.words("snake_case")).isEqualTo("Snake case");
        Property<Position, Double> x = Property.<Position, Double>builder("x", double.class).getter(Position::x)
            .annotations(a).build();
        assertThat(x.toString()).contains("x: double");
        assertThat(x.writable()).isFalse();
        assertThatThrownBy(() -> x.with(new Position(0, 0), 1.0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Property.builder("y", double.class).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PropertyModel.builder(Position.class).property(x).property(x))
            .isInstanceOf(IllegalArgumentException.class);
        PropertyModel<Position> m = PropertyModel.builder(Position.class).property(x).annotations(Hints.label("P"))
            .build();
        assertThat(m.toString()).contains("Position");
        assertThat(m.property("nope")).isNull();
        assertThat(m.annotation(Label.class).value()).isEqualTo("P");
        assertThat(Property.<Squad, Object>builder("w", List.class).getter(Squad::waypoints).element(Waypoint.class)
            .annotations((Annotations) null).build().toString()).contains("<Waypoint>");
        assertThat(PropertyModels.generated().model(Squad.class).property("waypoints").annotation(Addable.class))
            .isNull();
    }

    @Test
    void defaults() {
        assertThat(Defaults.of(int.class)).isEqualTo(0);
        assertThat(Defaults.of(boolean.class)).isEqualTo(false);
        assertThat(Defaults.of(Double.class)).isEqualTo(0.0);
        assertThat(Defaults.of(Integer.class)).isEqualTo(0);
        assertThat(Defaults.of(Boolean.class)).isEqualTo(false);
        assertThat(Defaults.of(Character.class)).isEqualTo('\0');
        assertThat(Defaults.of(String.class)).isEqualTo("");
        assertThat(Defaults.of(Behaviour.class)).isEqualTo(Behaviour.PATROL);
        assertThat(Defaults.of(Set.class)).isEqualTo(Set.of());
        assertThat(Defaults.of(Map.class)).isEqualTo(Map.of());
        assertThat(Defaults.of(Object.class)).isNull();
        assertThat(Defaults.of(Squad.class)).isInstanceOf(Squad.class);
        assertThat(((Squad) Defaults.of(Squad.class)).home()).isEqualTo(new Position(0, 0));
        assertThat(Defaults.factory(Waypoint.class).get()).isSameAs(Waypoint.DEFAULTS);
        assertThat(Defaults.factory(Light.class).get()).isInstanceOf(Light.class);
        assertThat(Defaults.factory(PropsGallery.Command.class)).isNull();
        assertThat(Defaults.factory(int.class)).isNull();
        assertThat(Defaults.subtypes(Position.class)).isEmpty();
    }

    @Test
    void collectionOps() {
        assertThat(CollectionOps.size(List.of(1, 2))).isEqualTo(2);
        assertThat(CollectionOps.size(Map.of(1, 2))).isEqualTo(1);
        assertThat(CollectionOps.size(new int[3])).isEqualTo(3);
        assertThat(CollectionOps.size("x")).isZero();
        assertThat(CollectionOps.unique(List.of(1, 2, 1))).isFalse();
        assertThat(CollectionOps.unique(List.of(1, 2))).isTrue();
        assertThat(CollectionOps.unique(Set.of(1))).isTrue();
        assertThat(CollectionOps.unique(null)).isTrue();
        List<Object> items = CollectionOps.items(List.of("a", "b", "c"));
        assertThat(CollectionOps.move(items, 0, 3)).containsExactly("b", "c", "a");
        assertThat(CollectionOps.move(items, 2, 0)).containsExactly("c", "a", "b");
        assertThat(CollectionOps.remove(items, 1)).containsExactly("a", "c");
        assertThat(CollectionOps.add(items, 1, "x")).containsExactly("a", "x", "b", "c");
        assertThat(CollectionOps.rebuild(Set.class, String.class, items)).isInstanceOf(Set.class);
        assertThat((int[]) CollectionOps.rebuild(int[].class, int.class, List.of(1, 2))).containsExactly(1, 2);
        assertThat(CollectionOps.items(new int[] {4, 5})).containsExactly(4, 5);
        Object map = CollectionOps.rebuild(Map.class, Integer.class, CollectionOps.items(Map.of("k", 1)));
        assertThat(map).isEqualTo(Map.of("k", 1));
        assertThat(CollectionOps.uniqueKeys(CollectionOps.items(Map.of("k", 1, "j", 2)))).isTrue();
        List<Object> twice = new ArrayList<>(CollectionOps.items(Map.of("k", 1)));
        twice.addAll(CollectionOps.items(Map.of("k", 2)));
        assertThat(CollectionOps.uniqueKeys(twice)).isFalse();
        var lens = CollectionOps.item(Set.class, String.class, 1);
        Object set = CollectionOps.rebuild(Set.class, String.class, List.of("a", "b"));
        assertThat(lens.get(set)).isEqualTo("b");
        assertThat(lens.with(set, "z")).isEqualTo(Set.of("a", "z"));
        assertThat(CollectionOps.item(List.class, String.class, 5).get(List.of("a"))).isNull();
        assertThat(lens.get(Set.of())).isNull();

        PropertyModels models = new PropertyModels();
        Squad s = PropsGallery.sample();
        Squad c = (Squad) CollectionOps.copy(s, models);
        assertThat(c).isEqualTo(s).isNotSameAs(s);
        assertThat(c.waypoints().get(0)).isEqualTo(s.waypoints().get(0)).isNotSameAs(s.waypoints().get(0));
        assertThat(CollectionOps.copy(new int[] {1}, models)).isEqualTo(new int[] {1});
        Light l = new Light();
        l.setIntensity(4);
        Light lc = (Light) CollectionOps.copy(l, models);
        assertThat(lc).isNotSameAs(l);
        assertThat(lc.getIntensity()).isEqualTo(4);
        assertThat(CollectionOps.copy(Behaviour.GUARD, models)).isEqualTo(Behaviour.GUARD);
        assertThat(CollectionOps.copy(new Object(), models)).isNotNull();
        assertThat(CollectionOps.copy(null, models)).isNull();
    }
}
