package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Completion fixtures: names, argument values, setting values, hints with the argument at the caret marked. */
class CompletionTest {

    private final ConsoleShell shell = new ConsoleShell(new LogRing(64));

    CompletionTest() {
        shell.variables(new FakeVariables());
        shell.register(ConsoleCommand.named("spawn").describe("Spawns creatures")
            .arg(Arg.integer("count").range(1, 100).describe("how many"))
            .arg(Arg.choice("kind", "orc", "elf", "ogre").optional())
            .action(call -> { }));
        shell.register(ConsoleCommand.named("say").arg(Arg.text("text").rest()).action(call -> { }));
        shell.alias("low", "render.bloom OFF");
    }

    private List<String> words(String line) {
        int caret = line.indexOf('|');
        String text = caret < 0 ? line : line.replace("|", "");
        return shell.complete(text, caret < 0 ? text.length() : caret).candidates().stream()
            .map(Completion.Candidate::text).toList();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = "=>", quoteCharacter = '`', value = {
        "sp=>spawn",
        "SP=>spawn",
        "render.=>render.bloom,render.scale,render.shadows,render.vsync",
        "bloom=>render.bloom",
        "lo=>low,render.bloom",
        "spawn 3 o=>orc,ogre",
        "`spawn 3 `=>orc,elf,ogre",
        "spawn 3 E=>elf",
        "`spawn `=>``",
        "`render.bloom `=>OFF,CHEAP,PHYSICAL",
        "render.bloom p=>PHYSICAL",
        "`render.vsync `=>on,off",
        "`render.scale `=>``",
        "`render.bloom OFF `=>``",
        "reset render.b=>render.bloom",
        "help sp=>spawn",
        "echo x; render.bloom c=>CHEAP",
        "spawn 3 \"o=>orc,ogre",
        "`nothing `=>``",
        "`low `=>``",
        "say a b c=>``",
    })
    void candidates(String line, String expected) {
        assertThat(String.join(",", words(line))).isEqualTo(expected == null ? "" : expected);
    }

    @Test
    void theWordUnderTheCaretIsReplaced() {
        Completion c = shell.complete("render.bl PHYSICAL", 4);
        assertThat(c.start()).isZero();
        assertThat(c.end()).isEqualTo(9);
        assertThat(c.candidates().getFirst().text()).isEqualTo("render.bloom");
        assertThat(c.apply("render.bl PHYSICAL", 0)).isEqualTo("render.bloom PHYSICAL");
        assertThat(c.caretAfter("render.bl PHYSICAL", 0)).isEqualTo(12);
        Completion end = shell.complete("render.blo", 10);
        assertThat(end.apply("render.blo", 0)).isEqualTo("render.bloom ");
        assertThat(end.caretAfter("render.blo", 0)).isEqualTo(13);
        Completion at = shell.complete("spawn 3 ", 8);
        assertThat(at.start()).isEqualTo(8);
        assertThat(at.apply("spawn 3 ", 1)).isEqualTo("spawn 3 elf ");
    }

    @Test
    void candidatesThatNeedQuotesAreQuoted() {
        shell.register(ConsoleCommand.named("go").arg(Arg.choice("place", "New York", "Rome")).action(call -> { }));
        Completion c = shell.complete("go N", 4);
        assertThat(c.apply("go N", 0)).isEqualTo("go \"New York\" ");
    }

    @Test
    void hintsShowTheSignatureWithTheArgumentAtTheCaret() {
        Completion c = shell.complete("spawn 3", 7);
        assertThat(c.hint()).isEqualTo("spawn <count: 1..100> [kind: orc|elf|ogre]   how many");
        assertThat(c.hint().substring(c.hintStart(), c.hintEnd())).isEqualTo("<count: 1..100>");
        c = shell.complete("spawn 3 o", 9);
        assertThat(c.hint().substring(c.hintStart(), c.hintEnd())).isEqualTo("[kind: orc|elf|ogre]");
        assertThat(c.hint()).endsWith("Spawns creatures");
        c = shell.complete("spawn 3 orc ", 12);
        assertThat(c.hintStart()).isEqualTo(-1); // past the last argument
        c = shell.complete("spawn", 5);
        assertThat(c.hint()).startsWith("spawn <count");
        assertThat(c.hint().substring(c.hintStart(), c.hintEnd())).isEqualTo("spawn");
        c = shell.complete("render.bloom ", 13);
        assertThat(c.hint()).isEqualTo("render.bloom <OFF|CHEAP|PHYSICAL>   = CHEAP   Bloom technique");
        assertThat(c.hint().substring(c.hintStart(), c.hintEnd())).isEqualTo("<OFF|CHEAP|PHYSICAL>");
        assertThat(c.candidates()).extracting(Completion.Candidate::detail).containsExactly("", "current", "");
        assertThat(shell.complete("render.scale ", 13).hint()).startsWith("render.scale <0.5..2>");
        assertThat(shell.complete("render.vsync ", 13).hint()).startsWith("render.vsync <on|off>");
        assertThat(shell.complete("game.name ", 10).hint()).startsWith("game.name <text>");
        assertThat(shell.complete("render.shadows ", 15).hint()).startsWith("render.shadows <256..8192>");
        assertThat(shell.complete("low x", 5).hint()).isEqualTo("low = render.bloom OFF");
        assertThat(shell.complete("say a", 5).hint().substring(4, 15)).isEqualTo("<text: text");
        assertThat(shell.complete("zzz ", 4)).isEqualTo(new Completion(4, 4, List.of(), "", -1, -1));
    }

    @Test
    void namesCarryWhatTheyAre() {
        Completion c = shell.complete("render.sc", 9);
        assertThat(c.candidates()).containsExactly(new Completion.Candidate("render.scale", "= 1"));
        c = shell.complete("lo", 2);
        assertThat(c.candidates().getFirst()).isEqualTo(new Completion.Candidate("low", "alias: render.bloom OFF"));
        c = shell.complete("reset render.s", 14);
        assertThat(c.candidates().getFirst().detail()).isEqualTo("1");
        c = shell.complete("help l", 6);
        assertThat(c.candidates()).containsExactly(new Completion.Candidate("low", "alias"));
        c = shell.complete("help sp", 7);
        assertThat(c.candidates().getFirst().detail()).isEqualTo("Spawns creatures");
    }

    @Test
    void theCommonPrefixOfTheCandidates() {
        assertThat(shell.complete("render.s", 8).commonPrefix()).isEqualTo("render.s");
        assertThat(shell.complete("rend", 4).commonPrefix()).isEqualTo("render.");
        assertThat(Completion.NONE.commonPrefix()).isEmpty();
        assertThat(Completion.NONE.isEmpty()).isTrue();
        assertThat(shell.complete("", 0).candidates().size()).isGreaterThan(10);
        assertThat(shell.complete("x", 99).start()).isZero();
    }
}
