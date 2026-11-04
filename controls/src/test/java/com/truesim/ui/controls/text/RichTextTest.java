package com.truesim.ui.controls.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.testkit.DrawListDump;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rich text styled by span classes, links, inline images, editing that keeps spans; emoji; BiDi; themes. */
class RichTextTest {

    private static final String SHEET = """
        root { layout: stack; padding: 0px; }
        textarea, richtext {
          width: 400px; height: 100px; padding: 0px; clip: true; font-family: "Inter"; font-size: 16px;
          color: #ffffff;
          caret { width: 2px; background: box(#ff0000); }
          selection { background: box(#0000ff); }
        }
        textfield {
          width: 300px; padding: 0px; font-family: "Inter"; font-size: 16px; color: #ffffff; clip: true;
          caret { width: 2px; background: box(#ff0000); }
          selection { background: box(#0000ff); }
          composition { border: 2px #00ff00; }
        }
        span.warning { color: #ffaa00; }
        span.big { font-size: 32px; }
        link { color: #00aaff; text-decoration: underline; }
        link:hover { color: #ff00ff; }
        img { height: 20px; }
        """;

    private static List<Long> glyphColours(TextRig r) {
        DrawList list = r.ui.layers()[0];
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH) {
                out.add(list.colorAt(i, Primitive.COLOR_A));
            }
        }
        return out;
    }

    @Test
    void spanClassesColourTheirTextAndLinksAreClickable() {
        TextRig r = new TextRig(Tss.compileString("rich.tss", SHEET));
        r.ui.images().add("icons/heart", new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB));
        List<String> links = new ArrayList<>();
        TextEditor t = TextEditor.richText(r.ui, null, "Log", "ok [class=warning]warn[/class] [link=say hi]go[/link]"
            + " [img=icons/heart] [b]B[/b][i]I[/i][u]U[/u][s]S[/s][color=#00ff00]G[/color][size=24]Z[/size]"
            + "[bg=#333333]H[/bg][class=big]X[/class]");
        t.listener(new TextEditor.Listener() {
            @Override
            public void link(TextEditor editor, String target) {
                links.add(target);
            }
        });
        r.add(t.element()).settle();
        List<Long> colours = glyphColours(r);
        long warning = Color.hex("#ffaa00").rgba();
        long link = Color.hex("#00aaff").rgba();
        assertThat(colours.subList(0, 2)).containsOnly(Rgba.WHITE);
        assertThat(colours.subList(2, 6)).containsOnly(warning);
        assertThat(colours.subList(6, 8)).containsOnly(link);
        assertThat(colours).contains(Color.hex("#00ff00").rgba());
        String dump = DrawListDump.of(r.ui.layers()[0]);
        assertThat(dump).as("the inline image").contains("image ");
        assertThat(t.readOnly()).isTrue();
        assertThat(t.markup()).contains("[link=say hi]go[/link]");
        // hover and click the link
        float[] b = r.box(t.element());
        DrawList list = r.ui.layers()[0];
        int g = -1;
        for (int i = 0, n = 0; i < list.size(); i++) {
            if (Primitive.kind(list.header(i)) == Primitive.GLYPH && n++ == 6) {
                g = i;
            }
        }
        float lx = list.floatAt(g, Primitive.X0) + 2;
        float ly = list.floatAt(g, Primitive.Y1) - 3;
        r.mouse(lx - 1, ly, 0);
        r.mouse(lx, ly, 0);
        assertThat(t.hoveredLink()).isNotNegative();
        r.settle();
        assertThat(glyphColours(r).subList(6, 8)).as("link:hover").containsOnly(Color.hex("#ff00ff").rgba());
        r.click(lx, ly);
        assertThat(links).containsExactly("say hi");
        r.mouse(b[0] + 1, b[3] - 2, 0);
        assertThat(t.hoveredLink()).isEqualTo(-1);
        r.press(UiCommand.SELECT_ALL, UiCommand.COPY);
        assertThat(r.clipboard.text()).startsWith("ok warn go");
        assertThat(Semantics.unnamed(Semantics.of(r.ui.store(), r.ui.interaction()))).isEmpty();
    }

    @Test
    void editingKeepsSpansAndPastesThemBack() {
        TextRig r = new TextRig(Tss.compileString("rich.tss", SHEET));
        TextEditor a = TextEditor.area(r.ui, null, "Doc").markup("say [class=warning]careful[/class] now");
        r.add(a.element()).frame();
        a.focus();
        a.editable().moveTo(8, false);
        r.type("XX");
        assertThat(a.markup()).isEqualTo("say [class=warning]careXXful[/class] now");
        a.editable().select(4, 13);
        r.press(UiCommand.COPY);
        r.press(UiCommand.DOC_END);
        r.type(" ");
        r.press(UiCommand.PASTE);
        assertThat(a.markup()).as("a copy within the UI keeps its spans").isEqualTo(
            "say [class=warning]careXXful[/class] now [class=warning]careXXful[/class]");
        r.clipboard.text("plain");
        r.press(UiCommand.PASTE);
        assertThat(a.markup()).as("pasted right after a span: the preceding character's style")
            .endsWith("careXXfulplain[/class]");
        r.settle();
        assertThat(glyphColours(r)).contains(Color.hex("#ffaa00").rgba());
        a.rich(false);
        r.settle();
        assertThat(glyphColours(r)).containsOnly(Rgba.WHITE);
    }

    @Test
    void emojiDrawInColourAndEmoticonsBecomeEmoji() {
        TextRig r = new TextRig(Tss.compileString("rich.tss", SHEET));
        TextEditor f = TextEditor.field(r.ui, null, "Chat");
        r.add(f.element()).frame();
        f.focus();
        r.type("hi \uD83D\uDC4B\uD83C\uDFFD ");
        r.settle();
        DrawList list = r.ui.layers()[0];
        int colour = 0;
        for (int i = 0; i < list.size(); i++) {
            colour += (Primitive.flags(list.header(i)) & Primitive.FLAG_COLOR) != 0 ? 1 : 0;
        }
        assertThat(colour).as("the waving hand with its skin tone: one colour glyph").isEqualTo(1);
        r.press(UiCommand.DELETE_BACK, UiCommand.DELETE_BACK);
        assertThat(f.text().toString()).as("backspace takes the whole emoji").isEqualTo("hi ");
        TextEditor.EMOJI_REPLACEMENT.select("both");
        try {
            r.type(":)");
            r.type(" ");
            assertThat(f.text().toString()).isEqualTo("hi \uD83D\uDE42 ");
            r.type(":fire:");
            assertThat(f.text().toString()).isEqualTo("hi \uD83D\uDE42 \uD83D\uDD25");
            r.press(UiCommand.UNDO);
            assertThat(f.text().toString()).isEqualTo("hi \uD83D\uDE42 :fire:");
        } finally {
            TextEditor.EMOJI_REPLACEMENT.select("off");
        }
    }

    @Test
    void theCaretMovesVisuallyOrLogicallyThroughArabic() {
        TextRig r = new TextRig(Tss.compileString("rich.tss", SHEET));
        TextEditor f = TextEditor.field(r.ui, null, "Mixed");
        r.add(f.element()).frame();
        f.focus();
        f.text("ab \u0645\u0631\u062D\u0628\u0627 cd");
        r.settle();
        r.press(UiCommand.DOC_START);
        List<Integer> visual = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            r.press(UiCommand.RIGHT);
            visual.add(f.editable().caret());
        }
        // right through "ab", into the Arabic word at its visual left (its logical end), through it leftwards in
        // logical order, out at its visual right, on through "cd"; the same x reached twice counts once
        assertThat(visual).containsExactly(1, 2, 7, 6, 5, 4, 3, 9, 10, 11, 11);
        TextEditor.CARET_MOVEMENT.select("logical");
        try {
            r.press(UiCommand.DOC_START);
            List<Integer> logical = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                r.press(UiCommand.RIGHT);
                logical.add(f.editable().caret());
            }
            assertThat(logical).containsExactly(1, 2, 3, 4);
        } finally {
            TextEditor.CARET_MOVEMENT.select("visual");
        }
    }

    @Test
    void compositionAndSelectionDrawAsGoldens() {
        TextRig r = new TextRig(Tss.compileString("rich.tss", SHEET));
        TextEditor f = TextEditor.field(r.ui, null, "Golden");
        r.add(f.element()).frame();
        f.focus();
        r.type("golden text");
        r.press(UiCommand.WORD_LEFT);
        r.extend(UiCommand.WORD_RIGHT);
        r.settle();
        DrawListDump.assertMatches(r.ui.layers()[0], Path.of("src/test/resources/goldens/text-field-selection.txt"));
        r.press(UiCommand.DOC_END);
        r.compose("\u304B\u306A", 2);
        r.settle();
        DrawListDump.assertMatches(r.ui.layers()[0], Path.of("src/test/resources/goldens/text-field-composition.txt"));
    }

    @Test
    void theTestThemeRestylesEveryTextControlAndPart() {
        TextRig r = new TextRig();
        TextEditor field = TextEditor.field(r.ui, null, "F");
        TextEditor area = TextEditor.area(r.ui, null, "A").lineNumbers(true);
        TextEditor rich = TextEditor.richText(r.ui, null, "R",
            "[class=warning]w[/class][class=error]e[/class][class=success]s[/class][class=info]i[/class]"
                + "[class=muted]m[/class][class=code]c[/class][class=mark]k[/class][link=x]l[/link][img=y]");
        PasswordField pw = PasswordField.create(r.ui, null, "P", "Show");
        SearchField search = SearchField.create(r.ui, null, "S", "Clear");
        NumberField number = NumberField.create(r.ui, null, "N", "Less", "More");
        TagInput tags = TagInput.create(r.ui, null, "T", "Remove");
        tags.add("chip");
        for (Element e : new Element[] {field.element(), area.element(), rich.element(), pw.element(), search.element(),
            number.element(), tags.element()}) {
            r.add(e);
        }
        r.settle();
        StyleResolver garish = new StyleResolver(r.ui.store(), Tss.testTheme());
        List<Element> all = new ArrayList<>();
        collect(r.ui.store(), r.ui.root(), all);
        int checked = 0;
        for (Element e : all) {
            String type = e.type();
            String parent = r.ui.store().parent(e.slot()) < 0 ? "" : r.ui.store().type(r.ui.store().parent(e.slot()));
            boolean notDrawn = parent.equals("richtext") && (type.equals("caret") || type.equals("composition"));
            if (type.equals("root") || notDrawn) {
                continue;
            }
            ResolvedStyle normal = r.ui.styles().styleOf(e.slot());
            ResolvedStyle test = garish.styleOf(e.slot());
            assertThat(differs(normal, test)).as("the test theme restyles %s", e).isTrue();
            checked++;
        }
        assertThat(checked).isGreaterThan(30);
        // states
        for (int state : new int[] {UiState.INVALID, UiState.READ_ONLY, UiState.FOCUSED}) {
            ResolvedStyle before = r.ui.styles().styleOf(field.element().slot());
            field.element().state(state, true);
            r.frame();
            assertThat(differs(before, r.ui.styles().styleOf(field.element().slot()))).as(UiState.name(state))
                .isTrue();
            field.element().state(state, false);
        }
        assertThat(Semantics.unnamed(Semantics.of(r.ui.store(), r.ui.interaction()))).isEmpty();
        Semantics.Node tree = Semantics.of(r.ui.store(), r.ui.interaction());
        assertThat(tree.toString()).contains(Role.TEXT_FIELD.name()).contains(Role.SPIN_BUTTON.name())
            .contains(Role.TOGGLE_BUTTON.name());
    }

    private static void collect(com.truesim.ui.core.UiStore store, Element e, List<Element> out) {
        out.add(e);
        for (int c = store.firstChild(e.slot()); c != com.truesim.ui.core.UiStore.NONE; c = store.nextSibling(c)) {
            collect(store, store.element(c), out);
        }
    }

    private static boolean differs(ResolvedStyle a, ResolvedStyle b) {
        for (int i = 0; i < StyleKeys.count(); i++) {
            StyleKey<?> key = StyleKeys.key(i);
            if (!a.get(key).equals(b.get(key))) {
                return true;
            }
        }
        return false;
    }
}
