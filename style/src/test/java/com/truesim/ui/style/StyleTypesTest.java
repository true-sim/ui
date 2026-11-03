package com.truesim.ui.style;

import static com.truesim.ui.style.expr.Value.ident;
import static com.truesim.ui.style.expr.Value.num;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.ValueException;
import com.truesim.ui.style.value.Align;
import com.truesim.ui.style.value.Anchors;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Easing;
import com.truesim.ui.style.value.FontSpec;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.GridPlacement;
import com.truesim.ui.style.value.ImageFit;
import com.truesim.ui.style.value.ImageSpec;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Radii;
import com.truesim.ui.style.value.Shadow;
import com.truesim.ui.style.value.Size;
import com.truesim.ui.style.value.TextOutline;
import com.truesim.ui.style.value.Track;
import com.truesim.ui.style.value.Transition;
import java.util.List;
import org.junit.jupiter.api.Test;

class StyleTypesTest {

    private static final Color RED = Color.rgb(255, 0, 0);
    private static final Value R = Value.color(RED);

    private static Value space(Value... items) {
        return Value.list(List.of(items), false);
    }

    private static Value comma(Value... items) {
        return Value.list(List.of(items), true);
    }

    private static Value fn(String name, Value... args) {
        return Value.fn(name, List.of(args));
    }

    private static void fails(StyleType<?> type, Value v, String message) {
        assertThatThrownBy(() -> type.parse(v)).isInstanceOf(ValueException.class).hasMessageContaining(message);
    }

    @Test
    void numbersAndRanges() {
        StyleType<Float> n = StyleTypes.number(0, 10);
        assertThat(n.parse(num(3))).isEqualTo(3f);
        assertThat(n.parse(num(3, "px"))).isEqualTo(3f);
        fails(n, num(11), "expected a number from 0 to 10, got number 11");
        fails(n, num(1, "em"), "got number 1em");
        assertThat(n.check(5f)).isEqualTo(5f);
        assertThatThrownBy(() -> n.check(11f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> n.check(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(n.description()).isEqualTo("a number from 0 to 10");

        StyleType<Integer> i = StyleTypes.integer(1, 3);
        assertThat(i.parse(num(2))).isEqualTo(2);
        fails(i, num(2.5), "an integer from 1 to 3");
        fails(i, num(4), "an integer");
        fails(i, num(0), "an integer");
        fails(i, num(2, "px"), "an integer");
        fails(i, ident("x"), "an integer");
        assertThatThrownBy(() -> i.check(9)).isInstanceOf(IllegalArgumentException.class);

        StyleType<Float> f = StyleTypes.fraction();
        assertThat(f.parse(num(50, "%"))).isEqualTo(0.5f);
        assertThat(f.parse(num(0.25))).isEqualTo(0.25f);
        fails(f, num(2), "a fraction");
        fails(f, num(1, "px"), "a fraction");
        fails(f, ident("x"), "a fraction");
        assertThatThrownBy(() -> f.check(-1f)).isInstanceOf(IllegalArgumentException.class);

        StyleType<Float> d = StyleTypes.duration();
        assertThat(d.parse(num(120, "ms"))).isEqualTo(0.12f);
        assertThat(d.parse(num(2, "s"))).isEqualTo(2f);
        fails(d, num(2), "a duration");
        fails(d, num(-2, "s"), "a duration");

        StyleType<Integer> w = StyleTypes.fontWeight();
        assertThat(w.parse(ident("bold"))).isEqualTo(700);
        assertThat(w.parse(ident("normal"))).isEqualTo(400);
        assertThat(w.parse(num(600))).isEqualTo(600);
        fails(w, ident("heavy"), "a font weight");
        fails(w, num(5000), "a font weight");
        fails(w, Value.string("bold"), "a font weight");
    }

    @Test
    void stringsColoursBooleansEnumerationsAndLists() {
        assertThat(StyleTypes.string().parse(Value.string("a"))).isEqualTo("a");
        fails(StyleTypes.string(), num(1), "a string");
        assertThat(StyleTypes.color().parse(R)).isEqualTo(RED);
        fails(StyleTypes.color(), num(1), "expected a colour, got number 1");
        assertThat(StyleTypes.bool().parse(Value.TRUE)).isTrue();
        fails(StyleTypes.bool(), num(1), "true or false");

        StyleType<Align> a = StyleTypes.enumeration(Align.class);
        assertThat(a.parse(ident("center"))).isEqualTo(Align.CENTER);
        fails(a, ident("centre"), "(did you mean center?)");
        fails(a, ident("zzzzzz"), "one of auto, start, end, center, stretch, baseline, got zzzzzz");
        fails(a, Value.string("center"), "one of");
        assertThat(StyleTypes.kebab("SPACE_BETWEEN")).isEqualTo("space-between");

        StyleType<List<Float>> list = StyleTypes.list(StyleTypes.number(0, 9));
        assertThat(list.parse(space(num(1), num(2)))).containsExactly(1f, 2f);
        assertThat(list.parse(num(1))).containsExactly(1f);
        assertThat(list.parse(ident("none"))).isEmpty();
        assertThat(list.check(List.of(1f))).containsExactly(1f);
        assertThatThrownBy(() -> list.check(List.of(10f))).isInstanceOf(IllegalArgumentException.class);

        StyleType<List<String>> families = StyleTypes.families();
        assertThat(families.parse(comma(Value.string("Inter"), ident("sans-serif"))))
            .containsExactly("Inter", "sans-serif");
        fails(families, ident("none"), "font family names");
        assertThat(families.check(List.of("a"))).containsExactly("a");
    }

    @Test
    void lengthsInsetsRadiiAndSizes() {
        StyleType<Length> l = StyleTypes.length();
        assertThat(l.parse(num(3))).isEqualTo(Length.px(3));
        assertThat(l.parse(num(3, "px"))).isEqualTo(Length.px(3));
        assertThat(l.parse(num(2, "em"))).isEqualTo(Length.em(2));
        assertThat(l.parse(num(50, "%"))).isEqualTo(Length.percent(50));
        assertThat(l.parse(ident("auto"))).isEqualTo(Length.AUTO);
        fails(l, num(1, "s"), "a length");
        fails(l, ident("big"), "a length");

        StyleType<Insets> i = StyleTypes.insets();
        assertThat(i.parse(num(4))).isEqualTo(Insets.of(4));
        assertThat(i.parse(space(num(4, "px"), num(12, "px")))).isEqualTo(Insets.of(4, 12));
        assertThat(i.parse(space(num(1), num(2), num(3)))).isEqualTo(new Insets(1, 2, 3, 2));
        assertThat(i.parse(space(num(1), num(2), num(3), num(4)))).isEqualTo(new Insets(1, 2, 3, 4));
        fails(i, space(num(1), num(2), num(3), num(4), num(5)), "1 to 4 px values");
        fails(i, comma(num(1), num(2)), "1 to 4 px values");
        fails(i, space(), "1 to 4 px values");
        assertThat(StyleTypes.radii().parse(num(6, "px"))).isEqualTo(Radii.of(6));

        StyleType<Size> s = StyleTypes.size();
        assertThat(s.parse(num(18, "px"))).isEqualTo(new Size(Length.px(18), Length.px(18)));
        assertThat(s.parse(space(num(100, "%"), num(24)))).isEqualTo(new Size(Length.percent(100), Length.px(24)));
        fails(s, space(num(1), num(2), num(3)), "one or two lengths");
    }

    @Test
    void bordersShadowsAndOutlines() {
        StyleType<Border> b = StyleTypes.border();
        assertThat(b.parse(ident("none"))).isEqualTo(Border.NONE);
        assertThat(b.parse(space(num(1, "px"), R))).isEqualTo(Border.of(1, RED));
        assertThat(b.parse(fn("border", num(2), R))).isEqualTo(Border.of(2, RED));
        fails(b, num(1), "a border");
        fails(b, space(num(1), num(2)), "a border");

        StyleType<List<Shadow>> sh = StyleTypes.shadows();
        assertThat(sh.parse(ident("none"))).isEmpty();
        assertThat(sh.parse(space(num(0), num(2, "px"), num(6, "px"), R)))
            .containsExactly(new Shadow(0, 2, 6, 0, RED));
        assertThat(sh.parse(comma(space(num(0), num(1), R), space(num(1), num(2), num(3), num(4), R))))
            .containsExactly(new Shadow(0, 1, 0, 0, RED), new Shadow(1, 2, 3, 4, RED));
        fails(sh, space(num(0), R), "shadows");
        assertThat(sh.check(List.of())).isEmpty();

        StyleType<TextOutline> o = StyleTypes.textOutline();
        assertThat(o.parse(ident("none"))).isEqualTo(TextOutline.NONE);
        assertThat(o.parse(space(num(1, "px"), R))).isEqualTo(new TextOutline(1, RED));
        fails(o, R, "a text outline");
    }

    @Test
    void paintsAndImages() {
        StyleType<Paint> p = StyleTypes.paint();
        assertThat(p.parse(ident("none"))).isEqualTo(Paint.NONE);
        assertThat(p.parse(R)).isEqualTo(Paint.box(RED));
        assertThat(p.parse(fn("box", R))).isEqualTo(Paint.box(RED));
        assertThat(p.parse(fn("linear-gradient", R, Value.color(Color.BLACK))))
            .isEqualTo(Paint.gradient(RED, Color.BLACK, 180));
        assertThat(p.parse(fn("linear-gradient", num(90, "deg"), R, Value.color(Color.BLACK))))
            .isEqualTo(Paint.gradient(RED, Color.BLACK, 90));
        assertThat(p.parse(fn("linear-gradient", R, Value.color(Color.BLACK), num(0.5, "turn"))))
            .isEqualTo(Paint.gradient(RED, Color.BLACK, 180));
        assertThat(p.parse(fn("image", Value.string("bg")))).isEqualTo(Paint.image("bg", Color.WHITE));
        assertThat(p.parse(fn("image", Value.string("bg"), R))).isEqualTo(Paint.image("bg", RED));
        assertThat(p.parse(fn("nine-slice", Value.string("frame"), num(8))))
            .isEqualTo(Paint.nineSlice("frame", 8, 8, 8, 8));
        assertThat(p.parse(fn("nine-slice", Value.string("frame"), space(num(1), num(2), num(3), num(4)),
            space(num(5), num(6), num(7), num(8)), R)))
            .isEqualTo(new Paint.NineSlice("frame", 1, 2, 3, 4, new Insets(6, 7, 8, 5), RED));
        fails(p, num(1), "a paint");
        fails(p, fn("sparkle", R), "a paint");
        fails(p, fn("box"), "box() takes 1 arguments, got 0");
        fails(p, fn("image"), "image() takes 1 to 2 arguments");
        fails(p, fn("box", num(1)), "box(colour)");
        fails(p, fn("image", num(1)), "an image name");
        fails(p, fn("linear-gradient", R, R, num(1, "px")), "an angle");
        fails(p, fn("nine-slice", Value.string("f"), num(1.5)), "source insets in texels");
        fails(p, fn("nine-slice", Value.string("f"), num(-1)), "source insets in texels");

        StyleType<ImageSpec> im = StyleTypes.image();
        assertThat(im.parse(ident("none"))).isEqualTo(ImageSpec.NONE);
        assertThat(im.parse(Value.string("icons/heart"))).isEqualTo(new ImageSpec("icons/heart", Color.WHITE,
            ImageFit.CONTAIN));
        assertThat(im.parse(space(Value.string("i"), R, ident("cover"))))
            .isEqualTo(new ImageSpec("i", RED, ImageFit.COVER));
        fails(im, ident("heart"), "an image");
        fails(im, space(Value.string("i"), R, R, R), "an image");
    }

    @Test
    void fontsAndTransitions() {
        StyleType<FontSpec> f = StyleTypes.font();
        assertThat(f.parse(comma(space(ident("italic"), ident("bold"), num(14, "px"), Value.string("Inter")),
            Value.string("Segoe UI")))).isEqualTo(new FontSpec(List.of("Inter", "Segoe UI"), 14, 700,
            FontStyle.ITALIC));
        assertThat(f.parse(space(num(12), ident("sans")))).isEqualTo(new FontSpec(List.of("sans"), 12, 400,
            FontStyle.NORMAL));
        fails(f, num(12), "a font");
        fails(f, space(ident("a"), ident("b"), ident("c"), ident("d"), ident("e")), "a font");
        fails(f, space(num(12), num(13)), "a font family");

        StyleType<List<Transition>> t = StyleTypes.transitions();
        assertThat(t.parse(ident("none"))).isEmpty();
        assertThat(t.parse(comma(space(ident("background"), num(120, "ms"), ident("ease-out")),
            space(ident("scale"), num(80, "ms"), num(10, "ms"))))).containsExactly(
            new Transition("background", 0.12f, Easing.EASE_OUT, 0),
            new Transition("scale", 0.08f, Easing.EASE, 0.01f));
        assertThat(t.parse(space(ident("all"), num(1, "s")))).containsExactly(Transition.of("all", 1, Easing.EASE));
        fails(t, space(ident("backgrund"), num(1, "s")), "transition of unknown key backgrund (did you mean "
            + "background?)");
        fails(t, space(ident("qqqqqqqq"), num(1, "s")), "unknown key qqqqqqqq");
        fails(t, ident("background"), "transitions");
        assertThat(t.check(List.of())).isEmpty();
    }

    @Test
    void layoutTypes() {
        StyleType<List<Track>> tr = StyleTypes.tracks();
        assertThat(tr.parse(space(num(100, "px"), num(1, "fr"), ident("auto"),
            fn("minmax", num(50), num(2, "fr")), fn("repeat", num(2), num(1, "fr"))))).containsExactly(
            Track.px(100), Track.fr(1), Track.AUTO, Track.minmax(Track.px(50), Track.fr(2)), Track.fr(1), Track.fr(1));
        fails(tr, fn("repeat", num(0), num(1, "fr")), "a repeat count");
        fails(tr, ident("big"), "grid tracks");
        assertThat(tr.check(List.of())).isEmpty();

        StyleType<GridPlacement> g = StyleTypes.gridPlacement();
        assertThat(g.parse(ident("auto"))).isEqualTo(GridPlacement.AUTO);
        assertThat(g.parse(num(2))).isEqualTo(new GridPlacement(2, 1));
        assertThat(g.parse(space(num(2), ident("span"), num(3)))).isEqualTo(new GridPlacement(2, 3));
        assertThat(g.parse(space(ident("span"), num(3)))).isEqualTo(new GridPlacement(0, 3));
        fails(g, space(num(2), ident("over"), num(3)), "a grid placement");
        fails(g, num(0), "a grid placement");
        fails(g, space(ident("span")), "a grid placement");

        StyleType<Anchors> a = StyleTypes.anchors();
        assertThat(a.parse(ident("full"))).isEqualTo(Anchors.FULL);
        assertThat(a.parse(space(num(0), num(100, "%"), num(0.5), num(1)))).isEqualTo(new Anchors(0, 1, 0.5f, 1));
        fails(a, space(num(0), num(1)), "anchors");
        fails(a, space(num(0), num(1), num(1), num(1, "px")), "anchors");

        StyleType<Insets> off = StyleTypes.offsets();
        assertThat(off.parse(space(num(1), num(2), num(3), num(4)))).isEqualTo(new Insets(2, 3, 4, 1));
        fails(off, num(1), "offsets");
    }

    @Test
    void keysRegisterByNameAndSuggestCloseNames() {
        assertThat(StyleKeys.find("background")).isSameAs(Styles.BACKGROUND);
        assertThat(StyleKeys.findShorthand("font")).isSameAs(Styles.FONT);
        assertThat(StyleKeys.key(Styles.COLOR.index())).isSameAs(Styles.COLOR);
        assertThat(StyleKeys.count()).isGreaterThan(Styles.REGION.index());
        assertThat(StyleKeys.suggest("backgruond")).isEqualTo("background");
        assertThat(StyleKeys.suggest("fnot")).isEqualTo("font");
        assertThat(StyleKeys.suggest("xyzzyxyzzy")).isNull();
        assertThatThrownBy(() -> StyleKey.of("color", StyleTypes.color(), Color.WHITE))
            .hasMessage("style key color is declared twice");
        StyleKeys.load(Styles.class);
        assertThat(Styles.FONT.name()).isEqualTo("font");
        assertThat(Styles.FONT.type()).isNotNull();
        assertThat(Styles.FONT).hasToString("font");
        assertThat(Styles.COLOR).hasToString("color");
        assertThat(Styles.COLOR.defaultValue()).isEqualTo(Color.WHITE);
        assertThat(Styles.SIZE.expand(new Size(Length.px(1), Length.px(2)))).containsExactly(
            Declaration.of(Styles.WIDTH, Length.px(1)), Declaration.of(Styles.HEIGHT, Length.px(2)));
    }
}
