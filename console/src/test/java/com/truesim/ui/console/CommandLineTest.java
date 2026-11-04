package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Parser fixtures: words, quotes, escapes, statements, comments, positions. */
class CommandLineTest {

    private static List<String> words(String line) {
        return CommandLine.parse(line).stream().flatMap(s -> s.tokens().stream()).map(CommandLine.Token::text)
            .toList();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "spawn 3 orc|spawn,3,orc",
        "  spawn   3\t orc  |spawn,3,orc",
        "say \"hello world\"|say,hello world",
        "say 'it''s'|say,its",
        "say 'a \"b\" c'|say,a \"b\" c",
        "say \"a \\\"b\\\" c\"|say,a \"b\" c",
        "say a\\ b|say,a b",
        "say \"tab\\there\"|say,tab\there",
        "say \"semi;colon\"|say,semi;colon",
        "say a\\;b|say,a;b",
        "say x // a comment|say,x",
        "say http://x|say,http://x",
        "say \"open|say,open",
        "a;b ; c|a,b,c",
    })
    void words(String line, String expected) {
        assertThat(String.join(",", words(line))).isEqualTo(expected.replace("\\t", "\t"));
    }

    @Test
    void statementsKeepWherePositionsAre() {
        List<CommandLine.Statement> s = CommandLine.parse("echo a; render.bloom  PHYSICAL ;; ");
        assertThat(s).hasSize(2);
        assertThat(s.get(0).name()).isEqualTo("echo");
        assertThat(s.get(1).tokens().get(1).start()).isEqualTo(22);
        assertThat(s.get(1).tokens().get(1).end()).isEqualTo(30);
        assertThat(s.get(1).start()).isEqualTo(7);
        assertThat(CommandLine.parse("   ")).isEmpty();
        assertThat(CommandLine.parse("// only a comment")).isEmpty();
    }

    @Test
    void anUnclosedQuoteRunsToTheEnd() {
        CommandLine.Token t = CommandLine.parse("say \"half open").getFirst().tokens().get(1);
        assertThat(t.text()).isEqualTo("half open");
        assertThat(t.quoted()).isTrue();
        assertThat(t.closed()).isFalse();
        assertThat(CommandLine.parse("say 'x").getFirst().tokens().get(1).closed()).isFalse();
    }

    @Test
    void theStatementAtTheCaret() {
        String line = "echo \"a;b\"; render.bl";
        CommandLine.Statement s = CommandLine.at(line, line.length());
        assertThat(s.start()).isEqualTo(11);
        assertThat(s.name()).isEqualTo("render.bl");
        assertThat(s.tokens().getFirst().start()).isEqualTo(12);
        assertThat(CommandLine.at(line, 3).name()).isEqualTo("echo");
        assertThat(CommandLine.at("a; ", 3).tokens()).isEmpty();
        assertThat(CommandLine.at("'x;y' z; w", 7).name()).isEqualTo("x;y");
        assertThat(CommandLine.at("a\\;b c", 6).name()).isEqualTo("a;b");
        assertThat(CommandLine.at("\"a\\\"; b\" c", 10).tokens()).hasSize(2);
    }

    @Test
    void quotingRoundTrips() {
        for (String s : List.of("plain", "two words", "with \"quotes\"", "back\\slash", "semi;colon", "", "//x",
            "it's")) {
            String quoted = CommandLine.quote(s);
            assertThat(CommandLine.parse("x " + quoted).getFirst().tokens().get(1).text()).isEqualTo(s);
        }
        assertThat(CommandLine.quote("plain")).isEqualTo("plain");
        assertThat(CommandLine.quote("two words")).isEqualTo("\"two words\"");
    }
}
