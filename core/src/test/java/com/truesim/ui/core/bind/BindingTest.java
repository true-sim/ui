package com.truesim.ui.core.bind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedLong;
import com.truesim.common.state.VersionedValue;
import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Binding in both directions, through lenses, converters and validators (stage 2's binding tests). */
class BindingTest {

    record Position(double x, double y) {
        Position withX(double v) {
            return new Position(v, y);
        }
    }

    record Waypoint(String name, Position position) {
        Waypoint withPosition(Position p) {
            return new Waypoint(name, p);
        }
    }

    static final Lens<Waypoint, Position> POSITION = Lens.of(Waypoint::position, Waypoint::withPosition);
    static final Lens<Position, Double> X = Lens.of(Position::x, Position::withX);

    private final ChangeClock clock = new ChangeClock();

    @Test
    void bothDirections() {
        VersionedValue<Waypoint> model = clock.value(new Waypoint("a", new Position(1, 2)));
        VersionedDouble slider = clock.doubleValue(0);
        Bindings bindings = new Bindings();
        Binding<Double, Double> b = bindings.bind(Bindable.of(slider), Bindable.through(model,
            POSITION.then(X)));
        assertThat(slider.get()).isEqualTo(1.0); // shown at once
        assertThat(bindings.poll()).isZero();

        slider.set(5); // an edit
        assertThat(bindings.poll()).isEqualTo(1);
        assertThat(model.get()).isEqualTo(new Waypoint("a", new Position(5, 2)));

        model.set(new Waypoint("a", new Position(7, 2))); // elsewhere: undo, a script
        bindings.poll();
        assertThat(slider.get()).isEqualTo(7.0);
        assertThat(b.valid()).isTrue();
        assertThat(bindings.size()).isEqualTo(1);
        assertThat(bindings.remove(b)).isTrue();
        assertThat(bindings.remove(b)).isFalse();
        assertThat(b.model().get()).isEqualTo(7.0);
        assertThat(b.control().get()).isEqualTo(7.0);
    }

    @Test
    void anEditAndAModelChangeInOneFrameKeepTheEdit() {
        VersionedValue<Position> model = clock.value(new Position(0, 0));
        VersionedValue<Double> field = clock.value(0.0);
        Bindings bindings = new Bindings();
        bindings.bind(Bindable.of(field), Bindable.through(model, X));
        model.set(new Position(3, 0));
        field.set(9.0);
        bindings.poll();
        assertThat(model.get().x()).isEqualTo(9);
        assertThat(field.get()).isEqualTo(9.0);
    }

    @Test
    void validatorsKeepInvalidValuesOutAndMarkTheElement() {
        UiStore store = new UiStore();
        Element e = store.create("numberfield", null);
        VersionedValue<Double> model = clock.value(0.5);
        VersionedValue<Double> field = clock.value(0.0);
        List<Object> edits = new ArrayList<>();
        Binding<Double, Double> b = new Binding<>(Bindable.of(model), Bindable.of(field), Converter.identity())
            .validator(Validator.range(0, 1)).element(e)
            .listener((binding, before, after) -> edits.add(before + "->" + after));
        assertThat(b.element()).isSameAs(e);
        field.set(2.0);
        b.poll();
        assertThat(model.get()).isEqualTo(0.5);
        assertThat(b.valid()).isFalse();
        assertThat(b.message()).contains("between");
        assertThat(e.is(UiState.INVALID)).isTrue();
        field.set(0.25);
        b.poll();
        assertThat(model.get()).isEqualTo(0.25);
        assertThat(e.is(UiState.INVALID)).isFalse();
        assertThat(edits).containsExactly("0.5->0.25");
        b.validator(null); // everything goes
        field.set(4.0);
        b.poll();
        assertThat(model.get()).isEqualTo(4.0);
    }

    @Test
    void convertersAndConversionFailures() {
        VersionedValue<Double> radians = clock.value(Math.PI);
        VersionedValue<String> text = clock.value("");
        Converter<Double, String> toText = Converter.<Double, Double>degrees().then(Converter.of(
            d -> Long.toString(Math.round(d)), s -> {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a number");
                }
            }));
        Binding<Double, String> b = new Binding<>(Bindable.of(radians), Bindable.of(text), toText);
        assertThat(text.get()).isEqualTo("180");
        text.set("90");
        b.poll();
        assertThat(radians.get()).isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(1e-12));
        text.set("ninety");
        b.poll();
        assertThat(b.message()).isEqualTo("not a number");
        Converter<Double, Double> fails = Converter.of(d -> d, d -> {
            throw new IllegalArgumentException();
        });
        VersionedValue<Double> c = clock.value(0.0);
        Binding<Double, Double> silent = new Binding<>(Bindable.of(clock.value(1.0)), Bindable.of(c), fails);
        c.set(2.0);
        silent.poll();
        assertThat(silent.message()).isEqualTo("invalid");
        assertThat(Converter.<Double, String>of(Object::toString, Double::valueOf).inverse().to("2.5"))
            .isEqualTo(2.5);
        assertThat(Converter.identity().from("x")).isEqualTo("x");
        assertThat(Converter.degrees().to(null)).isEqualTo(0.0);
        assertThat(Converter.degrees().from(null)).isEqualTo(0.0);
    }

    @Test
    void primitiveHolders() {
        VersionedInt i = clock.intValue(3);
        VersionedLong l = clock.longValue(4);
        VersionedBoolean z = clock.booleanValue(true);
        VersionedDouble d = clock.doubleValue(1);
        Bindable<Integer> bi = Bindable.of(i);
        Bindable<Long> bl = Bindable.of(l);
        Bindable<Boolean> bz = Bindable.of(z);
        Bindable<Double> bd = Bindable.of(d);
        assertThat(bi.set(5)).isTrue();
        assertThat(bi.set(null)).isTrue();
        assertThat(i.get()).isZero();
        assertThat(bl.set(null)).isTrue();
        assertThat(bl.get()).isZero();
        assertThat(bz.set(null)).isTrue();
        assertThat(bz.get()).isFalse();
        assertThat(bd.set(null)).isTrue();
        assertThat(bd.get()).isZero();
        long before = i.version() + l.version() + z.version() + d.version();
        bi.touch();
        bl.touch();
        bz.touch();
        bd.touch();
        assertThat(bi.version() + bl.version() + bz.version() + bd.version()).isEqualTo(before);
    }

    static final class Mutable {
        double value;
    }

    @Test
    void lensesOfMutableObjectsTouchTheHolder() {
        Mutable m = new Mutable();
        VersionedValue<Mutable> holder = clock.value(m);
        Bindable<Double> field = Bindable.through(holder, Lens.<Mutable, Double>of(o -> o.value, (o, v) -> {
            o.value = v;
            return o;
        }));
        long v0 = holder.version();
        assertThat(field.set(2.0)).isTrue();
        assertThat(holder.version()).isGreaterThan(v0);
        assertThat(field.set(2.0)).isFalse(); // equal: no change
        long v1 = holder.version();
        field.touch();
        assertThat(holder.version()).isGreaterThan(v1);
        VersionedValue<Position> none = clock.value(null);
        Bindable<Double> x = Bindable.through(none, X);
        assertThat(x.get()).isNull();
        assertThat(x.set(1.0)).isFalse();
    }

    @Test
    void collectionLenses() {
        List<String> list = List.of("a", "b", "c");
        Lens<List<String>, String> second = Lens.element(1);
        assertThat(second.get(list)).isEqualTo("b");
        assertThat(second.with(list, "x")).containsExactly("a", "x", "c");
        assertThat(list).containsExactly("a", "b", "c");
        Lens<Map<String, Integer>, Integer> a = Lens.entry("a");
        Map<String, Integer> map = Map.of("a", 1);
        assertThat(a.get(map)).isEqualTo(1);
        assertThat(a.with(map, 2)).containsEntry("a", 2);
        assertThat(Lens.<String>identity().with("a", "b")).isEqualTo("b");
        assertThatThrownBy(() -> Lens.of(null, (r, v) -> r)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void validatorsCombine() {
        Validator<String> notEmpty = Validator.of(s -> !s.isEmpty(), "empty");
        Validator<String> shortText = Validator.of(s -> s.length() < 4, "too long");
        Validator<String> both = notEmpty.and(shortText);
        assertThat(both.validate("")).isEqualTo("empty");
        assertThat(both.validate("abcdef")).isEqualTo("too long");
        assertThat(both.validate("ab")).isNull();
        assertThat(Validator.<Double>range(0, 1).validate(null)).isNotNull();
        assertThat(Validator.valid().validate(null)).isNull();
    }

    @Test
    void manyBindingsGrowAndPollWithoutAllocating() {
        Bindings bindings = new Bindings();
        VersionedValue<Waypoint> model = clock.value(new Waypoint("a", new Position(1, 2)));
        for (int k = 0; k < 40; k++) {
            bindings.bind(Bindable.of(clock.doubleValue(0)), Bindable.through(model, POSITION.then(X)));
        }
        bindings.poll();
        AllocationAssertions.assertAllocationFree(bindings::poll);
    }
}
