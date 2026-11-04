package com.truesim.ui.controls.text;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Align;
import com.truesim.ui.style.value.Direction;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.testkit.SyntheticInput;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The text controls on one screen (1280 x 720): fields in their states (placeholder, selection, IME preedit,
 * password, search, number, invalid, tags, read-only, emoji, mixed directions, a password masked per grapheme
 * cluster), a text area with line numbers, a selection and spell checking (misspelt words' squiggles), and rich text with spans, a link, an inline image and emoji. Styled by the UI's sheet (the default or
 * the test theme); the layout of the screen is inline. For the engine's GPU screenshots.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * TextGallery g = TextGallery.build(ui, 1280, 720);  // runs frames until the glyphs are in the atlas
 * }</pre>
 */
public final class TextGallery {

    /** The UI. */
    public final Ui ui;
    /** The text area. */
    public final TextEditor area;
    /** The rich text. */
    public final TextEditor rich;
    private final UiInput input = new UiInput(16);
    private final SyntheticInput synthetic = new SyntheticInput();
    private final float width;
    private final float height;
    private long nanos = 1_000_000_000L;

    private TextGallery(Ui ui, float width, float height) {
        this.ui = ui;
        this.width = width;
        this.height = height;
        area = TextEditor.area(ui, null, "Notes").lineNumbers(true)
            .spelling(new com.truesim.ui.text.spell.SpellChecker(com.truesim.ui.text.spell.Dictionary.sampleEnglish()),
                null);
        rich = TextEditor.richText(ui, null, "Console", String.join("\n",
            "[class=muted]12:00:01[/class] Server started on port [b]7777[/b]",
            "[class=muted]12:00:04[/class] [class=warning]warning:[/class] texture [class=code]rock_01.ktx2[/class]"
                + " missing, using [i]fallback[/i]",
            "[class=muted]12:00:05[/class] [class=error]error:[/class] shader compile failed — "
                + "[link=open shaders/rock.slang:42]rock.slang:42[/link]",
            "[class=muted]12:00:09[/class] [class=success]player joined[/class] [img=icons/heart] "
                + "[color=#c792ea]Ada[/color] says hi 👋🏽 [bg=#3a3a00]highlighted[/bg] "
                + "[u]under[/u] [s]struck[/s]",
            "[size=22]Larger text[/size] in a line, [class=info]info span[/class] and [class=mark]a mark[/class]"));
    }

    /** Builds the gallery into {@code ui}'s root and runs frames until it settles. */
    public static TextGallery build(Ui ui, float width, float height) {
        icons(ui);
        TextGallery g = new TextGallery(ui, width, height);
        g.fill(true);
        return g;
    }

    /**
     * Builds the gallery into {@code ui}'s root as a live UI shows it: the same controls and texts, but no frames run
     * and nothing posed (no focus, typing or composing), so an application's own frames drive it.
     */
    public static TextGallery live(Ui ui) {
        icons(ui);
        TextGallery g = new TextGallery(ui, 0, 0);
        g.fill(false);
        return g;
    }

    private Element flex(Direction direction, float gap) {
        Element e = ui.panel(null);
        ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, direction).set(Styles.GAP, gap).set(Styles.ALIGN_ITEMS, Align.START).build());
        return e;
    }

    private void sized(Element e, float w, float h) {
        ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w))
            .set(Styles.HEIGHT, Length.px(h)).build());
    }

    private void fill(boolean pose) {
        Element columns = flex(Direction.ROW, 24);
        ui.styles().inline(columns.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, Direction.ROW).set(Styles.GAP, 24f).set(Styles.ALIGN_ITEMS, Align.START)
            .set(Styles.PADDING, new com.truesim.ui.style.value.Insets(16, 16, 16, 16)).build());
        Element fields = flex(Direction.COLUMN, 8);
        Element right = flex(Direction.COLUMN, 12);
        ui.root().add(columns);
        columns.add(fields).add(right);
        TextEditor empty = TextEditor.field(ui, null, "Empty").placeholder("Your name");
        TextEditor selected = TextEditor.field(ui, null, "Selected");
        TextEditor ime = TextEditor.field(ui, null, "Composing");
        PasswordField pw = PasswordField.create(ui, null, "Password", "Show");
        PasswordField shown = PasswordField.create(ui, null, "Password", "Show");
        SearchField search = SearchField.create(ui, null, "Search", "Clear");
        NumberField number = NumberField.create(ui, null, "Volume", "Less", "More").range(0, 100).decimals(1);
        NumberField bad = NumberField.create(ui, null, "Count", "Less", "More").range(0, 10);
        TagInput tags = TagInput.create(ui, null, "Tags", "Remove");
        TextEditor readOnly = TextEditor.field(ui, null, "Read-only").text("cannot change this").readOnly(true);
        TextEditor emoji = TextEditor.field(ui, null, "Emoji").text("Nice 👍🏽 "
            + "🇩🇪 ❤️ 👨‍👩‍👧");
        PasswordField clusters = PasswordField.create(ui, null, "Password with emoji", "Show");
        TextEditor mixed = TextEditor.field(ui, null, "Mixed").text("abc مرحبا def");
        row(fields, "placeholder", empty.element());
        row(fields, "selection", selected.element());
        row(fields, "IME preedit", ime.element());
        row(fields, "password", pw.element());
        row(fields, "revealed", shown.element());
        row(fields, "search", search.element());
        row(fields, "number", number.element());
        row(fields, "invalid", bad.element());
        row(fields, "tags", tags.element());
        row(fields, "read-only", readOnly.element());
        row(fields, "emoji", emoji.element());
        row(fields, "mixed", mixed.element());
        row(fields, "clusters", clusters.element());
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 14; i++) {
            text.append("Line ").append(i).append(i % 3 == 0
                ? ": a longer line that wraps at the area's width so the soft breaks show between its words"
                : i == 13 ? ": a mispelled wrod, checked as you type" : ": short").append('\n');
        }
        area.text(text);
        sized(area.element(), 640, 260);
        sized(rich.element(), 640, 240);
        right.add(area.element()).add(rich.element());
        if (pose) {
            frames(1);
        }
        pw.editor().text("hunter2");
        shown.editor().text("hunter2");
        shown.revealed(true);
        // e and a combining acute, a ZWJ family, a flag, "!": four clusters, four masks
        clusters.editor().text("e\u0301\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\uD83C\uDDE9\uD83C\uDDEA!");
        search.editor().text("dragon");
        number.value(42.5);
        tags.add("ui");
        tags.add("text");
        tags.add("emoji 😀");
        bad.editor().validator(com.truesim.ui.text.edit.TextFilters.range(0, 10, false));
        bad.editor().text("42");
        area.editable().select(area.text().length() - 120, area.text().length() - 30);
        if (!pose) {
            selected.text("select some words");
            return;
        }
        ime.focus();
        frames(1);
        synthetic.type("kana: ");
        frames(1);
        synthetic.compose("かな", 2);
        frames(1);
        selected.text("select some words");
        selected.focus();
        frames(1);
        synthetic.press(UiCommand.WORD_LEFT);
        frames(1);
        synthetic.command(UiCommand.WORD_LEFT, 0, UiInput.EXTEND_SELECTION);
        frames(60); // the glyphs (and colour emoji) reach the atlas
    }

    private void row(Element column, String caption, Element control) {
        Element row = flex(Direction.ROW, 10);
        Element label = ui.label("caption", caption);
        ui.styles().inline(label.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(100)).build());
        row.add(label);
        row.add(control);
        column.add(row);
    }

    /** Runs {@code n} frames of 16 ms with the synthetic input. */
    public TextGallery frames(int n) {
        for (int i = 0; i < n; i++) {
            input.beginFrame();
            synthetic.poll(input);
            ui.update(width, height, input, nanos);
            nanos += 16_000_000L;
        }
        return this;
    }

    private interface Draw {
        void draw(Graphics2D g);
    }

    /** The icons the text controls name (the toolkit ships none). */
    static void icons(Ui ui) {
        icon(ui, "icons/close", g -> {
            g.drawLine(5, 5, 11, 11);
            g.drawLine(11, 5, 5, 11);
        });
        icon(ui, "icons/eye", g -> g.drawOval(3, 5, 10, 6));
        icon(ui, "icons/eye-off", g -> {
            g.drawOval(3, 5, 10, 6);
            g.drawLine(3, 13, 13, 3);
        });
        icon(ui, "icons/minus", g -> g.drawLine(4, 8, 12, 8));
        icon(ui, "icons/plus", g -> {
            g.drawLine(4, 8, 12, 8);
            g.drawLine(8, 4, 8, 12);
        });
        icon(ui, "icons/heart", g -> {
            g.setColor(new Color(255, 80, 90));
            g.fillOval(2, 3, 7, 7);
            g.fillOval(7, 3, 7, 7);
            g.fillPolygon(new int[] {2, 14, 8}, new int[] {7, 7, 14}, 3);
        });
    }

    private static void icon(Ui ui, String name, Draw draw) {
        if (ui.images().get(name) != null) {
            return;
        }
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.6f));
        draw.draw(g);
        g.dispose();
        ui.images().add(name, img);
    }
}
