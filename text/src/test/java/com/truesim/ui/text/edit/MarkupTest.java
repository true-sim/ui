package com.truesim.ui.text.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The BBCode markup, spans, the gap buffer and emoticon replacement. */
class MarkupTest {

    private static String roundTrip(String markup) {
        StringBuilder text = new StringBuilder();
        Spans spans = new Spans();
        Markup.parse(markup, text, spans);
        return Markup.format(text, spans);
    }

    @Test
    void everyTagRoundTrips() {
        String[] samples = {
            "plain", "[b]bold[/b] [i]it[/i] [u]u[/u] [s]s[/s]", "[color=#ff8800]orange[/color]",
            "[size=20]big[/size] [bg=#ffff0080]hi[/bg]", "[class=warning]careful[/class]",
            "[link=say hello]run it[/link]", "an icon [img=icons/heart] here", "[[not a tag] and [[b]",
            "[b]bold [i]both[/i][/b] [i]italic[/i]", "[link=\"a]b\"]odd target[/link]",
            "[link=\"say \"\"hi\"\"\"]quotes[/link]", "[class=a][class=b]two[/class][/class]",
        };
        for (String s : samples) {
            assertThat(roundTrip(s)).as(s).isEqualTo(s);
        }
    }

    @Test
    void parsingKeepsTextAndSpans() {
        StringBuilder text = new StringBuilder();
        Spans spans = new Spans();
        Markup.parse("a [b]b [link=go]c[/b] d[/link] [img=x]", text, spans);
        assertThat(text.toString()).isEqualTo("a b c d \uFFFC");
        assertThat(spans.toString()).isEqualTo("[b 2..5, link=go 4..7, img=x 8..9]");
        assertThat(spans.kind(1)).isEqualTo(Spans.Kind.LINK);
        assertThat(spans.value(1)).isEqualTo("go");
        assertThat(Markup.format(text, spans)).isEqualTo("a [b]b [link=go]c[/b] d[/link] [img=x]");
    }

    @Test
    void whatIsNotATagStaysText() {
        StringBuilder text = new StringBuilder();
        Spans spans = new Spans();
        Markup.parse("[x]y[/x] [/b] [b=1] [color]c[/color] [b]open [ end", text, spans);
        assertThat(text.toString()).isEqualTo("[x]y[/x] [/b] [b=1] [color]c[/color] open [ end");
        assertThat(spans.toString()).as("an open tag closes at the end").isEqualTo("[b 37..47]");
        Markup.parse("[link=a\nb]", text, spans);
        assertThat(text.toString()).isEqualTo("[link=a\nb]");
        assertThat(Markup.escape("a[b]")).isEqualTo("a[[b]");
        assertThat(roundTrip("x\uFFFCy")).as("an object character without an image").isEqualTo("x\uFFFCy");
    }

    @Test
    void kindsHaveTags() {
        assertThat(Spans.Kind.ofTag("bg")).isEqualTo(Spans.Kind.HIGHLIGHT);
        assertThat(Spans.Kind.ofTag("nope")).isNull();
        assertThat(Spans.Kind.BOLD.valued()).isFalse();
        assertThat(Spans.Kind.CLASS.valued()).isTrue();
        Spans spans = new Spans();
        assertThatThrownBy(() -> spans.add(0, 1, Spans.Kind.CLASS, null)).isInstanceOf(
            IllegalArgumentException.class);
        assertThatThrownBy(() -> spans.add(0, 1, Spans.Kind.BOLD, "x")).isInstanceOf(IllegalArgumentException.class);
        spans.add(1, 1, Spans.Kind.BOLD, null);
        assertThat(spans.count()).as("empty spans are ignored").isZero();
    }

    @Test
    void spansWidenShrinkSplitAndFind() {
        Spans s = new Spans();
        for (int i = 0; i < 10; i++) {
            s.add(i * 3, i * 3 + 2, Spans.Kind.CLASS, "c" + i);
        }
        assertThat(s.count()).isEqualTo(10);
        s.clear();
        s.add(0, 4, Spans.Kind.BOLD, null);
        s.add(2, 3, Spans.Kind.IMAGE, "i");
        s.inserted(0, 1);
        assertThat(s.toString()).as("at the very start: the following character's style").isEqualTo(
            "[b 0..5, img=i 3..4]");
        s.inserted(3, 2);
        assertThat(s.toString()).as("an image never widens").isEqualTo("[b 0..7, img=i 5..6]");
        s.inserted(6, 1);
        assertThat(s.toString()).isEqualTo("[b 0..8, img=i 5..6]");
        s.deleted(4, 7);
        assertThat(s.toString()).as("the image is gone").isEqualTo("[b 0..5]");
        s.remove(1, 3, Spans.Kind.BOLD);
        assertThat(s.toString()).isEqualTo("[b 0..1, b 3..5]");
        assertThat(s.find(Spans.Kind.BOLD, 3)).isEqualTo(1);
        assertThat(s.find(Spans.Kind.BOLD, 2)).isEqualTo(-1);
        s.inserted(1, 2);
        assertThat(s.toString()).as("they touch now and merge on normalizing").isEqualTo("[b 0..3, b 5..7]");
        s.deleted(3, 5);
        assertThat(s.toString()).isEqualTo("[b 0..5]");
        s.inserted(2, 0);
        s.deleted(2, 2);
        long v = s.version();
        s.normalize();
        assertThat(s.version()).isGreaterThan(v);
    }

    @Test
    void theGapBufferEditsAnywhere() {
        GapBuffer b = new GapBuffer(4);
        b.insert(0, "hello");
        b.insert(5, " world");
        b.insert(0, ">> ");
        b.delete(3, 4);
        assertThat(b.toString()).isEqualTo(">> ello world");
        assertThat(b.length()).isEqualTo(13);
        assertThat(b.charAt(4)).isEqualTo('l');
        char[] out = new char[13];
        b.getChars(0, 13, out, 0);
        assertThat(new String(out)).isEqualTo(">> ello world");
        b.insert(8, "x");
        b.getChars(2, 10, out, 0); // across the gap
        assertThat(new String(out, 0, 8)).isEqualTo(" ello xw");
        b.getChars(10, 12, out, 0); // after it
        assertThat(new String(out, 0, 2)).isEqualTo("or");
        assertThat(b.subSequence(3, 7).toString()).isEqualTo("ello");
        assertThat(b.contentEquals(">> ello xworld")).isTrue();
        assertThat(b.contentEquals(">> ello xworlD")).isFalse();
        assertThat(b.contentEquals("short")).isFalse();
        b.set("abc");
        b.delete(1, 1);
        b.insert(1, "");
        assertThat(b.toString()).isEqualTo("abc");
        assertThat(b.capacity()).isGreaterThanOrEqualTo(16);
        assertThatThrownBy(() -> b.charAt(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> b.charAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> b.insert(4, "x")).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> b.delete(2, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> b.getChars(0, 4, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            big.append((char) ('a' + i % 26));
        }
        b.insert(1, big);
        assertThat(b.length()).isEqualTo(1003);
        assertThat(b.charAt(1002)).isEqualTo('c');
    }

    @Test
    void emoticonsAndShortcodesBecomeEmoji() throws IOException {
        Emoticons e = Emoticons.builtIn();
        assertThat(Emoticons.builtIn()).isSameAs(e);
        assertThat(e.emoticon(":)")).isEqualTo("\uD83D\uDE42");
        assertThat(e.shortcode("smile")).isEqualTo("\uD83D\uDE04");
        assertThat(e.shortcode("family")).isEqualTo("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66");
        EditableText t = new EditableText();
        t.type("hi :)");
        assertThat(e.apply(t, true, true)).as("not before a space").isFalse();
        t.type(" ");
        assertThat(e.apply(t, true, true)).isTrue();
        assertThat(t.text().toString()).isEqualTo("hi \uD83D\uDE42 ");
        assertThat(t.caret()).isEqualTo(t.length());
        t.type("a:smile");
        assertThat(e.apply(t, true, true)).isFalse();
        t.type(":");
        assertThat(e.apply(t, true, false)).as("shortcodes off").isFalse();
        assertThat(e.apply(t, true, true)).as("glued to a word: not a shortcode").isFalse();
        t.set("go :thumbsup:");
        assertThat(e.apply(t, false, true)).isTrue();
        assertThat(t.text().toString()).isEqualTo("go \uD83D\uDC4D");
        t.undo();
        assertThat(t.text().toString()).as("undo brings the typed text back").isEqualTo("go :thumbsup:");
        t.set(":nope: x:) ");
        assertThat(e.apply(t, true, true)).isFalse();
        t.set(":nope:");
        assertThat(e.apply(t, true, true)).isFalse();
        t.set("::");
        assertThat(e.apply(t, true, true)).isFalse();
        t.set(":a b:");
        assertThat(e.apply(t, true, true)).isFalse();
        t.set(":) ");
        assertThat(e.apply(t, false, true)).as("emoticons off").isFalse();
        t.selectAll();
        assertThat(e.apply(t, true, true)).isFalse();
        t.set("");
        assertThat(e.apply(t, true, true)).isFalse();
        Emoticons custom = Emoticons.of(new ByteArrayInputStream("# c\n\n:x:\t1F600\n=)\t1F642 FE0F\n".getBytes(
            StandardCharsets.UTF_8)));
        assertThat(custom.shortcode("x")).isEqualTo("\uD83D\uDE00");
        assertThat(custom.emoticon("=)")).isEqualTo("\uD83D\uDE42\uFE0F");
        assertThatThrownBy(() -> Emoticons.of(new ByteArrayInputStream("nope".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IOException.class);
    }
}
