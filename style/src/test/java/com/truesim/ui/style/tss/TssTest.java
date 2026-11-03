package com.truesim.ui.style.tss;

import static com.truesim.ui.style.Selector.sel;
import static com.truesim.ui.style.Styles.BACKGROUND;
import static com.truesim.ui.style.Styles.BORDER;
import static com.truesim.ui.style.Styles.COLOR;
import static com.truesim.ui.style.Styles.FONT_SIZE;
import static com.truesim.ui.style.Styles.PADDING;
import static com.truesim.ui.style.Styles.RADIUS;
import static com.truesim.ui.style.Styles.SCALE;
import static com.truesim.ui.style.Styles.SHADOW;
import static com.truesim.ui.style.Styles.TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.UiState;
import com.truesim.ui.style.Declaration;
import com.truesim.ui.style.Rule;
import com.truesim.ui.style.Selector;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Colors;
import com.truesim.ui.style.value.Easing;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Radii;
import com.truesim.ui.style.value.Shadow;
import com.truesim.ui.style.value.Size;
import com.truesim.ui.style.value.Transition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TssTest {

    static Compilation compile(String main, String... partials) {
        Map<String, String> files = new HashMap<>();
        files.put("main.tss", main);
        for (int i = 0; i < partials.length; i += 2) {
            files.put(partials[i], partials[i + 1]);
        }
        return Tss.compile(SourceLoader.memory(files), "main.tss");
    }

    static StyleSheet sheet(String main, String... partials) {
        return compile(main, partials).sheet();
    }

    /** The value of {@code key} in the only rule of {@code selector}. */
    static Object value(StyleSheet sheet, String selector, com.truesim.ui.style.StyleKey<?> key) {
        Selector s = Selector.parse(selector);
        for (Rule r : sheet.rules()) {
            if (r.selector().equals(s)) {
                for (Declaration d : r.declarations()) {
                    if (d.key() == key) {
                        return d.isLive() ? d.live() : d.value();
                    }
                }
            }
        }
        throw new AssertionError("no " + key + " in " + selector + ":\n" + sheet);
    }

    static TssException error(String main, String... partials) {
        try {
            compile(main, partials);
        } catch (TssException e) {
            return e;
        }
        throw new AssertionError("compiled");
    }

    @Test
    void tssAndJavaBuildEqualSheets() {
        String main = """
            @use "tokens";
            $radius-m: 6px;
            $glass: rgba(#20303a, 0.85);
            $sizes: (small: 12px, large: 18px);

            @mixin raised($depth: 1) {
              shadow: 0 ($depth * 2px) ($depth * 6px) rgba(black, 0.35);
            }
            @function double($x) { @return $x * 2; }
            %pill { radius: 999px; padding: 4px 12px; }

            :root { --accent: #3fa7ff; }

            button {
              font-size: 14px;
              background: box($glass);
              radius: $radius-m;
              @include raised(1);
              transition: background 120ms ease-out, scale 80ms;
              &:hover { background: box(lighten($glass, 8%)); }
              &:pressed { scale: 0.97; }
              &.danger { @extend %pill; background: box(mix(tokens.$accent, red, 30%)); }
              &.primary { background: box(var(--accent)); }
            }
            @each $name, $size in $sizes {
              label.#{$name} { font-size: double($size); }
            }
            slider > thumb { size: 18px; border: 1px tokens.$edge; }
            """;
        String tokens = """
            $accent: #ff8800;
            $edge: rgba(white, 0.2);
            """;
        StyleSheet tss = sheet(main, "_tokens.tss", tokens);

        Color glass = Colors.alpha(Color.hex("#20303a"), 0.85);
        StyleSheet java = StyleSheet.builder()
            .token("accent", Color.hex("#3fa7ff"))
            .rule(sel("button").style("danger")).set(RADIUS, Radii.of(999)).set(PADDING, Insets.of(4, 12))
            .rule(sel("button")).set(FONT_SIZE, 14f).set(BACKGROUND, Paint.box(glass)).set(RADIUS, Radii.of(6))
            .set(SHADOW, List.of(new Shadow(0, 2, 6, 0, Colors.alpha(Color.BLACK, 0.35))))
            .set(TRANSITION, List.of(new Transition("background", 0.12f, Easing.EASE_OUT, 0),
                new Transition("scale", 0.08f, Easing.EASE, 0)))
            .rule(sel("button").state(UiState.HOVER)).set(BACKGROUND, Paint.box(Colors.lighten(glass, 0.08)))
            .rule(sel("button").state(UiState.PRESSED)).set(SCALE, 0.97f)
            .rule(sel("button").style("danger"))
            .set(BACKGROUND, Paint.box(Colors.mix(Color.hex("#ff8800"), Color.rgb(255, 0, 0), 0.3)))
            .rule(sel("button").style("primary")).live(BACKGROUND, TokenExpr.call("box", TokenExpr.token("accent")))
            .rule(sel("label").style("small")).set(FONT_SIZE, 24f)
            .rule(sel("label").style("large")).set(FONT_SIZE, 36f)
            .rule(sel("slider").child(sel("thumb"))).set(Styles.SIZE, new Size(Length.px(18), Length.px(18)))
            .set(BORDER, Border.of(1, Colors.alpha(Color.WHITE, 0.2)))
            .build();
        assertThat(tss).isEqualTo(java);
    }

    @Test
    void variablesScopesAndFlags() {
        StyleSheet s = sheet("""
            $a: 1px;
            $a: 2px !default;      // set already: kept
            $b: null;
            $b: 3px !default;      // null counts as unset
            $flag: false;
            @if true { $a: 4px; $flag: true; }   // flow control assigns the global
            label {
              $a: 10px;            // a local, the global stays
              $c: 1px;
              @if true { $c: 2px; }
              padding: $c;
              $g: 5px !global;
            }
            panel { padding: $a $b; font-size: if($flag, 9, 1); }
            image { padding: $g; }
            """);
        assertThat(value(s, "label", PADDING)).isEqualTo(Insets.of(2));
        assertThat(value(s, "panel", PADDING)).isEqualTo(Insets.of(4, 3));
        assertThat(value(s, "panel", FONT_SIZE)).isEqualTo(9f);
        assertThat(value(s, "image", PADDING)).isEqualTo(Insets.of(5));
    }

    @Test
    void nestingWithAmpersandsAndSelectorLists() {
        StyleSheet s = sheet("""
            panel, label {
              &.hud, &.card { font-size: 1; }
              > image { font-size: 2; }
              &:hover:focused image { font-size: 3; }
              #{"button"} & { font-size: 4; }
            }
            """);
        List<String> selectors = s.rules().stream().map(r -> r.selector().toString()).toList();
        assertThat(selectors).containsExactly("panel.hud", "label.hud", "panel.card", "label.card", "panel > image",
            "label > image", "panel:hover:focused image", "label:hover:focused image", "button panel", "button label");
    }

    @Test
    void mixinsWithDefaultsNamedArgumentsAndContent() {
        StyleSheet s = sheet("""
            @mixin box($pad: 2px, $size: $pad * 2) {
              padding: $pad;
              font-size: $size;
              &:hover { @content; }
            }
            @mixin plain { scale: 2; }
            label { @include box($size: 7px) { scale: 3; } }
            panel { @include box(4px); @include plain; }
            """);
        assertThat(value(s, "label", PADDING)).isEqualTo(Insets.of(2));
        assertThat(value(s, "label", FONT_SIZE)).isEqualTo(7f);
        assertThat(value(s, "label:hover", SCALE)).isEqualTo(3f);
        assertThat(value(s, "panel", FONT_SIZE)).isEqualTo(8f);
        assertThat(value(s, "panel", SCALE)).isEqualTo(2f);
    }

    @Test
    void modulesImportsAndPartials() {
        StyleSheet s = sheet("""
            @use "lib/colors" as c;
            @use "lib/sizes" as *;
            @import "shared";
            label { color: c.$fg; font-size: $big; padding: c.half(8px); @include c.tight; }
            """,
            "lib/_colors.tss", """
                @use "../base";
                $fg: base.$white;
                @function half($x) { @return $x / 2; }
                @mixin tight { scale: 0.5; }
                """,
            "base.tss", "$white: #fff; panel { font-size: 1; }",
            "lib/sizes.tss", "$big: 30px;",
            "_shared.tss", "image { font-size: 2; }");
        assertThat(value(s, "label", COLOR)).isEqualTo(Color.WHITE);
        assertThat(value(s, "label", FONT_SIZE)).isEqualTo(30f);
        assertThat(value(s, "label", PADDING)).isEqualTo(Insets.of(4));
        assertThat(value(s, "label", SCALE)).isEqualTo(0.5f);
        assertThat(value(s, "panel", FONT_SIZE)).isEqualTo(1f); // a used module's rules are emitted once
        assertThat(value(s, "image", FONT_SIZE)).isEqualTo(2f);
        assertThat(compile("@use \"a\"; @use \"b\";", "a.tss", "@use \"b\";", "b.tss", "x { font-size: 1; }")
            .sheet().rules()).hasSize(1);
        assertThat(compile("@import \"a\";", "a.tss", "$x: 1;").sources()).containsExactly("main.tss", "a.tss");
    }

    @Test
    void extendCopiesTheExtendersIntoTheExtendedRules() {
        StyleSheet s = sheet("""
            %pill { radius: 9px; &:hover { scale: 2; } }
            .card { padding: 3px; }
            panel %pill { font-size: 5; }
            button { @extend %pill; }
            label.big { @extend .card; }
            image.x { @extend %pill; }
            """);
        List<String> selectors = s.rules().stream().map(r -> r.selector().toString()).toList();
        assertThat(selectors).containsExactly("button", "image.x", "button:hover", "image.x:hover", ".card",
            "label.big", "panel button", "panel image.x");
        assertThat(value(s, "button:hover", SCALE)).isEqualTo(2f);
        StyleSheet conflict = sheet("""
            panel%pill { radius: 1px; }
            label.a { @extend %pill; }
            panel.b { @extend %pill; }
            """);
        assertThat(conflict.rules()).hasSize(1); // label cannot also be a panel
        assertThat(conflict.rules().get(0).selector()).isEqualTo(Selector.parse("panel.b"));
    }

    @Test
    void controlFlowAndFunctions() {
        StyleSheet s = sheet("""
            $themes: (primary: #3fa7ff, danger: #ff4040);
            @function pick($x) {
              @if $x > 10 { @return 10; } @else if $x > 5 { @return 5; } @else { @return 1; }
            }
            @each $name, $c in $themes { button.#{$name} { color: $c; } }
            @each $pair in $themes { label.#{nth($pair, 1)} { color: nth($pair, 2); } }
            @each $x, $y in (1 2, 3 4) { panel.p#{$x} { font-size: $y; } }
            @each $s in small, large { image.#{$s} { font-size: length($s); } }
            @for $i from 1 through 3 { panel.t#{$i} { font-size: pick($i * 4); } }
            @for $i from 3 to 1 { panel.d#{$i} { font-size: $i; } }
            @each $a, $b, $c in (k: v) { label.e { font-size: if($c == null, 1, 2); } }
            """);
        assertThat(value(s, "button.danger", COLOR)).isEqualTo(Color.hex("#ff4040"));
        assertThat(value(s, "label.primary", COLOR)).isEqualTo(Color.hex("#3fa7ff"));
        assertThat(value(s, "panel.p3", FONT_SIZE)).isEqualTo(4f);
        assertThat(value(s, "image.large", FONT_SIZE)).isEqualTo(1f);
        assertThat(value(s, "panel.t1", FONT_SIZE)).isEqualTo(1f);
        assertThat(value(s, "panel.t2", FONT_SIZE)).isEqualTo(5f);
        assertThat(value(s, "panel.t3", FONT_SIZE)).isEqualTo(10f);
        assertThat(value(s, "panel.d3", FONT_SIZE)).isEqualTo(3f);
        assertThat(value(s, "panel.d2", FONT_SIZE)).isEqualTo(2f);
        assertThat(s.rules().stream().filter(r -> r.selector().toString().equals("panel.d1")).count()).isZero();
        assertThat(value(s, "label.e", FONT_SIZE)).isEqualTo(1f);
    }

    @Test
    void arithmeticUnitsColoursAndValues() {
        StyleSheet s = sheet("""
            label {
              padding: 1px + 2 (10px - 4px) 0 -2px;
              scale: (1s + 500ms) / 100ms;
              opacity: percentage(0.5);
              color: hsl(120deg, 100%, 25%);
              background: linear-gradient(90deg, oklch(70% 0.1 250), contrast(#eee));
              transition: all 0.25s ease-in-out 50ms;
              radius: 10px % 4px 1px * 2 6px/2 7px;
              shadow: none;
              grid-template-columns: 100px repeat(2, 1fr) minmax(40px, auto);
              grid-column: 2 span 3;
              anchors: 0 1 0 100%;
              offsets: 8px -48px 200px -8px;
              aspect-ratio: 16 / 9;
              font: italic bold 13px "Inter", "Segoe UI", sans-serif;
              image: "icons/heart" #ff4040 cover;
              text-outline: 1px rgba(0, 0, 0, 50%);
              line-height: 1.4;
              layout: flex; direction: row-reverse; wrap: wrap; justify-content: space-between;
              align-items: center; align-self: end; region: north; text-align: center;
            }
            """);
        assertThat(value(s, "label", PADDING)).isEqualTo(new Insets(3, 6, 0, -2));
        assertThat(value(s, "label", SCALE)).isEqualTo(15f);
        assertThat(value(s, "label", FONT_SIZE)).isEqualTo(13f);
        assertThat(value(s, "label", Styles.OPACITY)).isEqualTo(0.5f);
        assertThat(value(s, "label", COLOR)).isEqualTo(Colors.hsl(120, 1, 0.25, 1));
        assertThat(value(s, "label", BACKGROUND)).isEqualTo(Paint.gradient(Colors.oklch(0.7, 0.1, 250, 1),
            Color.BLACK, 90));
        assertThat(value(s, "label", TRANSITION)).isEqualTo(List.of(new Transition("all", 0.25f,
            Easing.EASE_IN_OUT, 0.05f)));
        assertThat(value(s, "label", RADIUS)).isEqualTo(Radii.of(2, 2, 3, 7));
        assertThat(value(s, "label", SHADOW)).isEqualTo(List.of());
        assertThat(value(s, "label", Styles.ASPECT_RATIO)).isEqualTo((float) (16.0 / 9));
        assertThat(value(s, "label", Styles.FONT_FAMILY)).isEqualTo(List.of("Inter", "Segoe UI", "sans-serif"));
        assertThat(value(s, "label", Styles.FONT_WEIGHT)).isEqualTo(700);
        assertThat(value(s, "label", Styles.DIRECTION)).isEqualTo(com.truesim.ui.style.value.Direction.ROW_REVERSE);
        assertThat((List<?>) value(s, "label", Styles.GRID_TEMPLATE_COLUMNS)).hasSize(4);
    }

    @Test
    void liveTokensAndTokenSets() {
        Compilation c = compile("""
            $accent: #3fa7ff !live;
            :root { --bg: #101418; --bg-raised: lighten(var(--bg), 6%); --pad: 4px; }
            @tokens light { --bg: #f4f4f4; }
            @tokens high-contrast { --bg: black; --accent: yellow; }
            button { background: box(lighten($accent, 8%)); border: 1px var(--accent); padding: var(--pad) 2px; }
            panel { background: var(--bg-raised); }
            @debug "built";
            @warn 1px + 1px;
            """);
        StyleSheet s = c.sheet();
        assertThat(s.tokens().keySet()).containsExactly("accent", "bg", "bg-raised", "pad");
        assertThat(s.tokens().get("bg-raised")).isEqualTo(new Value.Live(TokenExpr.call("lighten",
            TokenExpr.token("bg"), TokenExpr.literal(Value.num(6, "%")))));
        assertThat(s.tokenSets()).containsOnlyKeys("light", "high-contrast");
        assertThat(s.tokenSets().get("high-contrast").get("accent")).isEqualTo(Value.color(Color.rgb(255, 255, 0)));
        assertThat(value(s, "button", BACKGROUND)).isEqualTo(TokenExpr.call("box", TokenExpr.call("lighten",
            TokenExpr.token("accent"), TokenExpr.literal(Value.num(8, "%")))));
        assertThat(value(s, "button", BORDER)).isEqualTo(new TokenExpr.Items(List.of(
            TokenExpr.literal(Value.num(1, "px")), TokenExpr.token("accent")), false));
        assertThat(c.warnings()).containsExactly("main.tss:7:1: @debug built", "main.tss:8:1: @warn 2px");
    }

    @Test
    void commentsStringsAndEdgeSyntax() {
        StyleSheet s = sheet("""
            /* a block
               comment */
            label /* in a selector */ { // a line comment
              font-family: 'Segoe UI', "Dejavu \\"Sans\\"";;
              font-size: .5 * 0 + 12;
              image: "a" + "/" + b;
            }
            panel{font-size:1}
            """);
        assertThat(value(s, "label", Styles.FONT_FAMILY)).isEqualTo(List.of("Segoe UI", "Dejavu \"Sans\""));
        assertThat(value(s, "label", FONT_SIZE)).isEqualTo(12f);
        assertThat(value(s, "panel", FONT_SIZE)).isEqualTo(1f);
        assertThat(((com.truesim.ui.style.value.ImageSpec) value(s, "label", Styles.IMAGE)).name()).isEqualTo("a/b");
        assertThat(Tss.compileString("x.tss", "label { scale: 2 }").rules()).hasSize(1);
        assertThat(sheet("")).isEqualTo(StyleSheet.empty());
        assertThat(sheet("label { $x: null; padding: $x; }").rules()).isEmpty(); // null declares nothing
        assertThat(sheet("$m: (a: 1, b: 2,); $l: (1, 2,); label { font-size: map-get($m, b) + length($l) + "
            + "length(()); }").rules().get(0).declarations().get(0).value()).isEqualTo(4f);
        assertThat(Compiler.normalize("a/./b/../../../c")).isEqualTo("../c");
    }
}
