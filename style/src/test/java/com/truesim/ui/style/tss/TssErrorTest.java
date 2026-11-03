package com.truesim.ui.style.tss;

import static com.truesim.ui.style.tss.TssTest.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TssErrorTest {

    private static void at(TssException e, String file, int line, int column, String detail) {
        assertThat(e.file()).as(e.getMessage()).isEqualTo(file);
        assertThat(e.line()).as(e.getMessage()).isEqualTo(line);
        assertThat(e.column()).as(e.getMessage()).isEqualTo(column);
        assertThat(e.detail()).as(e.getMessage()).isEqualTo(detail);
    }

    @Test
    void unknownKeysSuggestTheClosestName() {
        TssException e = error("""
            button.glass {
              &:hover {
                backgrund: red;
              }
            }
            """);
        at(e, "main.tss", 3, 5, "unknown key backgrund (did you mean background?)");
        assertThat(e.selector()).isEqualTo("button.glass:hover");
        assertThat(e.trace()).isEmpty();
        assertThat(e.getMessage()).isEqualTo("main.tss:3:5: unknown key backgrund (did you mean background?)\n"
            + "  in rule button.glass:hover");
        at(error("a { qqqqqqqqqqq: 1; }"), "main.tss", 1, 5, "unknown key qqqqqqqqqqq");
    }

    @Test
    void errorsInsideMixinsCarryTheIncludeTrace() {
        TssException e = error("""
            @use "mixins";
            panel.hud {
              @include mixins.outer(2px);
            }
            """,
            "_mixins.tss", """
                @mixin inner($w) {
                  padding: $w + 1em;
                }
                @mixin outer($w) {
                  @include inner($w);
                }
                """);
        at(e, "_mixins.tss", 2, 15, "incompatible units in 2px + 1em");
        assertThat(e.selector()).isEqualTo("panel.hud");
        assertThat(e.trace()).containsExactly("@include inner at _mixins.tss:5:3", "@include outer at main.tss:3:3");
        assertThat(e.getMessage()).isEqualTo("_mixins.tss:2:15: incompatible units in 2px + 1em\n"
            + "  in rule panel.hud\n"
            + "  from @include inner at _mixins.tss:5:3\n"
            + "  from @include outer at main.tss:3:3");

        TssException f = error("""
            @function scale($x) { @return $x * 1px * 1px; }
            label { padding: scale(2); }
            """);
        at(f, "main.tss", 1, 40, "cannot multiply 2px by 1px: the result would be in px*px");
        assertThat(f.trace()).containsExactly("scale() at main.tss:2:18");
    }

    @Test
    void typeErrorsNameTheKey() {
        at(error("label {\n  font-size: 2em;\n}"), "main.tss", 2, 3,
            "font-size: expected a number from 1 to 512, got number 2em");
        at(error("label { text-align: centre; }"), "main.tss", 1, 9,
            "text-align: expected one of start, center, end, got centre (did you mean center?)");
        at(error("label { color: lighten(1px, 2%); }"), "main.tss", 1, 16,
            "lighten(): $color must be a colour, got number 1px");
        at(error("label { font: var(--f); }\n:root { --f: 1; }"), "main.tss", 1, 9,
            "font is a shorthand and cannot take a live value: set its keys");
        at(error("label { size: 1px 2px 3px; }"), "main.tss", 1, 9,
            "size: expected one or two lengths (width height), got list 1px 2px 3px");
    }

    @Test
    void variablesMixinsAndFunctionsMustExist() {
        at(error("$accent: red;\nlabel { color: $acent; }"), "main.tss", 2, 16,
            "undefined variable $acent (did you mean $accent?)");
        at(error("label { color: $zzzzzzzz; }"), "main.tss", 1, 16, "undefined variable $zzzzzzzz");
        at(error("@mixin raised { }\nlabel { @include rased; }"), "main.tss", 2, 9,
            "undefined mixin rased (did you mean raised?)");
        at(error("@mixin m($a) { }\nlabel { @include m; }"), "main.tss", 2, 9, "@include m is missing $a");
        at(error("@mixin m($a) { }\nlabel { @include m(1, 2); }"), "main.tss", 2, 9,
            "@include m takes 1 arguments, got 2");
        at(error("@mixin m($size) { }\nlabel { @include m($sise: 1); }"), "main.tss", 2, 9,
            "@include m has no parameter $sise (did you mean $size?)");
        at(error("@mixin m($a) { }\nlabel { @include m(1, $a: 2); }"), "main.tss", 2, 9,
            "@include m: $a is given twice");
        at(error("@function f() { $x: 1; }\nlabel { font-size: f(); }"), "main.tss", 2, 20,
            "@function f ended without @return");
        at(error("@use \"a\";\nlabel { color: a.$fg; }", "a.tss", "$bg: red;"), "main.tss", 2, 16,
            "no variable $fg in module a (did you mean $bg?)");
        at(error("@use \"a\";\nlabel { color: zzzz.$fg; }", "a.tss", "$bg: red;"), "main.tss", 2, 16,
            "no module zzzz (@use it first)");
        at(error("@use \"abc\";\nlabel { color: abd.$fg; }", "abc.tss", "$bg: red;"), "main.tss", 2, 16,
            "no module abd (@use it first; did you mean abc)");
        at(error("@use \"a\";\nlabel { @include a.m; }", "a.tss", ""), "main.tss", 2, 9, "no mixin m in module a");
        at(error("@use \"a\";\nlabel { color: a.f(); }", "a.tss", ""), "main.tss", 2, 16,
            "no function f in module a");
        at(error("label { color: mix(red, blue, $weigth: 1); }"), "main.tss", 1, 16,
            "mix() has no parameter $weigth (did you mean $weight?)");
        at(error("label { color: mix(red, $color1: blue); }"), "main.tss", 1, 16,
            "mix(): $color1 is given twice");
        at(error("label { color: mix($color1: red, blue); }"), "main.tss", 1, 16,
            "mix(): positional arguments come before named ones");
        at(error("label { background: box($c: red); }"), "main.tss", 1, 21,
            "box() takes no named arguments: it is not a function this language knows, so it stays a value");
    }

    @Test
    void syntaxErrorsPointAtTheToken() {
        at(error("label {\n  color: red\n  padding: 1px;\n}"), "main.tss", 3, 10, "expected ';', got ':'");
        at(error("label { color: red; }\n}"), "main.tss", 2, 1, "unexpected '}'");
        at(error("label { color: \"red; }"), "main.tss", 1, 16, "unterminated string");
        at(error("@mixn x { }"), "main.tss", 1, 1, "unknown at-rule @mixn (did you mean @mixin?)");
        at(error("@qqqqqqq x;"), "main.tss", 1, 1, "unknown at-rule @qqqqqqq");
        at(error("label { color: #12; }"), "main.tss", 1, 16, "not a colour: #12");
        at(error("label { color: ; }"), "main.tss", 1, 16, "expected a value for color");
        at(error("label { color: red"), "main.tss", 1, 19, "expected '}', got end of file");
        at(error("label { 12: red; }"), "main.tss", 1, 9, "expected a declaration or a rule, got 12");
        at(error("label { color: ^; }"), "main.tss", 1, 16, "unexpected character '^'");
        at(error("label { color: ); }"), "main.tss", 1, 16, "expected a value, got ')'");
        at(error("label { color: $; }"), "main.tss", 1, 16, "expected a variable name after $");
        at(error("@ x"), "main.tss", 1, 1, "expected an at-rule name after @");
        at(error("/* open"), "main.tss", 1, 1, "unterminated comment");
        at(error("$x: 1 !sometimes;"), "main.tss", 1, 7, "unknown flag !sometimes (expected !default, !global or "
            + "!live)");
        at(error("@use tokens;"), "main.tss", 1, 6, "expected a path in quotes, got 'tokens'");
        at(error("@function f { }"), "main.tss", 1, 13, "expected '(' after @function f");
        at(error("@mixin m(a) { }"), "main.tss", 1, 10, "expected a parameter ($name), got 'a'");
        at(error("@each x in a { }"), "main.tss", 1, 7, "expected a variable, got 'x'");
        at(error("@each $x on a { }"), "main.tss", 1, 10, "expected 'in', got 'on'");
        at(error("@for x from 1 to 2 { }"), "main.tss", 1, 6, "expected a variable, got 'x'");
        at(error("@for $i in 1 to 2 { }"), "main.tss", 1, 9, "expected 'from', got 'in'");
        at(error("@for $i from 1 till 2 { }"), "main.tss", 1, 16, "expected 'through' or 'to', got 'till'");
        at(error("@else { }"), "main.tss", 1, 1, "@else without @if");
        at(error("label { color: a.b; }"), "main.tss", 1, 18, "expected a variable or a function of a, got 'b'");
        at(error("label { color: (a: 1 b: 2); }"), "main.tss", 1, 23, "expected ')', got ':'");
        at(error("label { color: (a: 1, b 2); }"), "main.tss", 1, 26, "expected ':', got ')'");
        at(error("@if true { } @else if { }"), "main.tss", 1, 23, "expected a value, got '{'");
        at(error("@tokens { }"), "main.tss", 1, 9, "expected a token set name, got '{'");
    }

    @Test
    void misplacedStatements() {
        at(error("color: red;"), "main.tss", 1, 1, "declaration of color outside a rule");
        at(error("@return 1;"), "main.tss", 1, 1, "@return outside @function");
        at(error("@content;"), "main.tss", 1, 1, "@content outside a mixin");
        at(error("@extend %x;"), "main.tss", 1, 1, "@extend belongs in a rule");
        at(error("label { @use \"a\"; }", "a.tss", ""), "main.tss", 1, 9, "@use belongs at the top level of a file");
        at(error("label { @tokens dark { } }"), "main.tss", 1, 9, "@tokens belongs at the top level");
        at(error("label { --x: 1; }"), "main.tss", 1, 9, "--x: tokens are declared in :root or @tokens blocks");
        at(error(":root { color: red; }"), "main.tss", 1, 9, "color: only --tokens are declared here");
        at(error(":root { a { } }"), "main.tss", 1, 9, "rules cannot nest in :root or @tokens");
        at(error("label { :root { } }"), "main.tss", 1, 9, ":root belongs at the top level");
        at(error("&.x { }"), "main.tss", 1, 1, "'&' outside a rule: &.x");
        at(error("a, { color: red; }"), "main.tss", 1, 1, "empty selector in 'a,'");
        at(error("label { $x: 1 !live; }"), "main.tss", 1, 9, "$x !live: live variables are declared at the "
            + "top level");
        at(error("@error \"stop: \" + 2;"), "main.tss", 1, 1, "stop: 2");
        at(error("@error 2px;"), "main.tss", 1, 1, "2px");
    }

    @Test
    void liveTokensAreCheckedAtLoad() {
        at(error(":root { --a: red; }\nlabel { color: var(--qqqqqq); }"), "main.tss", 2, 9,
            "color: unknown token --qqqqqq");
        at(error(":root { --accent: red; }\nlabel { color: var(--acent); }"), "main.tss", 2, 9,
            "color: unknown token --acent (did you mean --accent?)");
        TssException e = error(":root { --a: red; }\n@tokens big { --a: 2px; }\nlabel { color: var(--a); }");
        at(e, "main.tss", 3, 9, "color: expected a colour, got number 2px (with token set big)");
        assertThat(e.selector()).isEqualTo("label");
        at(error(":root {\n  --a: 1px;\n  --b: lighten(var(--a), 1%);\n}"), "main.tss", 3, 3,
            "token --b: lighten(): $color must be a colour, got number 1px");
        at(error(":root { --a: var(--a); }"), "main.tss", 1, 9, "token --a depends on itself");
        at(error("@tokens t { --b: var(--qqqq); }"), "main.tss", 1, 13,
            "in @tokens t: token --b: unknown token --qqqq");
        at(error(":root { --a: 1; }\n@if var(--a) { }"), "main.tss", 2, 1,
            "@if cannot test a live token: it is evaluated at load");
        at(error(":root { --a: 1; }\n@each $x in var(--a) { }"), "main.tss", 2, 1,
            "@each cannot loop over a live token: it is evaluated at load");
        at(error(":root { --a: x; }\nlabel.#{var(--a)} { }"), "main.tss", 2, 9,
            "a live token cannot be interpolated: it is evaluated at load");
        at(error(":root { --a: 1; }\n$m: (k: var(--a));"), "main.tss", 2, 6, "maps cannot hold live tokens");
        at(error(":root { --a: 1; }\n@function f($x) { @return $x; }\nlabel { scale: f(var(--a)); }"),
            "main.tss", 3, 16, "a live token cannot be passed to @function f: live values go through built-in "
                + "functions only");
        at(error("label { color: var(accent); }"), "main.tss", 1, 16,
            "var() takes a token name such as --accent, got accent");
        at(error("label { color: var(--a, red); }"), "main.tss", 1, 16, "var() takes one token name: var(--accent)");
    }

    @Test
    void selectorsModulesAndExtendErrors() {
        TssException e = error("@mixin m { a:hvr { color: red; } }\nlabel { @include m; }");
        at(e, "main.tss", 1, 12, "bad selector 'label a:hvr': unknown state :hvr (did you mean :hover?) at column 9");
        assertThat(e.selector()).isEqualTo("label a:hvr");
        assertThat(e.trace()).containsExactly("@include m at main.tss:2:9");
        at(error("label { @extend %nope; }"), "main.tss", 1, 9, "@extend %nope matched no rule");
        at(error("label { @extend a b; }"), "main.tss", 1, 9,
            "@extend takes one simple selector such as %pill or .card, got a b");
        at(error("label { @extend *; }"), "main.tss", 1, 9,
            "@extend takes one simple selector such as %pill or .card, got *");
        at(error("label { @extend a:hvr; }"), "main.tss", 1, 9,
            "bad selector 'a:hvr': unknown state :hvr (did you mean :hover?) at column 3");
        at(error("@use \"nope\";"), "main.tss", 1, 1, "cannot find nope (tried nope.tss and _nope.tss)");
        at(error("@import \"a\";", "a.tss", "@import \"main\";"), "a.tss", 1, 1,
            "@import cycle: main.tss imports itself");
        at(error("@use \"a\";", "a.tss", "@use \"main\";"), "a.tss", 1, 1, "@use cycle: main.tss uses itself");
        at(error("@for $i from 1px to 2 { }"), "main.tss", 1, 14, "@for needs unitless integers, got 1px");
        assertThatThrownBy(() -> Tss.compile(SourceLoader.memory(Map.of()), "none.tss"))
            .isInstanceOf(TssException.class).hasMessage("none.tss:1:1: no such sheet: none.tss");
        assertThatThrownBy(() -> Tss.compileFile(Path.of("does-not-exist.tss")))
            .hasMessageContaining("no such sheet: does-not-exist.tss");
        TssException direct = new TssException("x", "f", 1, 2, null, List.of("a"));
        assertThat(direct.getMessage()).isEqualTo("f:1:2: x\n  from a");
    }
}
