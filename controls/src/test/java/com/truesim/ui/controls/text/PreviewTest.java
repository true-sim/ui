package com.truesim.ui.controls.text;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.tss.Tss;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of the text controls in the default theme ({@code TRUESIM_SCREENSHOTS=true}, into
 * {@code TRUESIM_SCREENSHOTS_DIR}): fields in their states, a text area, rich text and emoji. Skipped otherwise.
 */
class PreviewTest {

    /** Simple icons for the theme's icon names (the toolkit ships none). */
    private static void icons(Ui ui) {
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

    private interface Draw {
        void draw(Graphics2D g);
    }

    private static void icon(Ui ui, String name, Draw draw) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.6f));
        draw.draw(g);
        g.dispose();
        ui.images().add(name, img);
    }

    private static final String LAYOUT = """
        root { layout: flex; direction: column; gap: 10px; padding: 16px; align-items: start; }
        panel.row { layout: flex; direction: row; gap: 10px; align-items: center; }
        label { color: #8b949e; font-family: "Inter"; font-size: 12px; width: 110px; }
        """;

    /** The default theme with the preview's layout rules after it. */
    private static com.truesim.ui.style.StyleSheet sheet(String more) {
        com.truesim.ui.style.tss.SourceLoader classpath = com.truesim.ui.style.tss.SourceLoader.classpath(
            PreviewTest.class.getClassLoader(), "");
        String path = Tss.THEMES + "/preview.tss";
        return Tss.compile(p -> p.equals(path) ? "@use \"default-theme\";\n" + LAYOUT + more
            : classpath.read(p), path).sheet();
    }

    private static TextRig rig(int w, int h) {
        TextRig r = new TextRig(sheet(""));
        r.width = w;
        r.height = h;
        icons(r.ui);
        return r;
    }

    private static Element row(TextRig r, String caption, Element control) {
        Element row = r.ui.panel("row");
        row.add(r.ui.label(null, caption));
        row.add(control);
        r.add(row);
        return row;
    }

    private static void shoot(TextRig r, String name) throws Exception {
        Path dir = CpuPreview.dir();
        r.settle();
        new CpuPreview((int) r.width, (int) r.height, Rgba.srgb(0x0d, 0x11, 0x17, 1))
            .draw(r.ui.layers(), r.ui.layerCount(), r.ui.textures()).save(dir.resolve(name));
    }

    @Test
    void fields() throws Exception {
        if (CpuPreview.dir() == null) {
            return;
        }
        TextRig r = rig(520, 520);
        TextEditor empty = TextEditor.field(r.ui, null, "Empty").placeholder("Your name");
        TextEditor selected = TextEditor.field(r.ui, null, "Selected");
        TextEditor ime = TextEditor.field(r.ui, null, "Composing");
        PasswordField pw = PasswordField.create(r.ui, null, "Password", "Show");
        PasswordField shown = PasswordField.create(r.ui, null, "Password", "Show");
        SearchField search = SearchField.create(r.ui, null, "Search", "Clear");
        NumberField number = NumberField.create(r.ui, null, "Volume", "Less", "More").range(0, 100).decimals(1);
        NumberField bad = NumberField.create(r.ui, null, "Count", "Less", "More").range(0, 10);
        TagInput tags = TagInput.create(r.ui, null, "Tags", "Remove");
        TextEditor readOnly = TextEditor.field(r.ui, null, "Read-only").text("cannot change this").readOnly(true);
        TextEditor emoji = TextEditor.field(r.ui, null, "Emoji").text("Nice \uD83D\uDC4D\uD83C\uDFFD \uD83C\uDDE9\uD83C\uDDEA \u2764\uFE0F");
        TextEditor mixed = TextEditor.field(r.ui, null, "Mixed").text("abc \u0645\u0631\u062D\u0628\u0627 def");
        row(r, "placeholder", empty.element());
        row(r, "selection", selected.element());
        row(r, "IME preedit", ime.element());
        row(r, "password", pw.element());
        row(r, "revealed", shown.element());
        row(r, "search", search.element());
        row(r, "number", number.element());
        row(r, "invalid", bad.element());
        row(r, "tags", tags.element());
        row(r, "read-only", readOnly.element());
        row(r, "emoji", emoji.element());
        row(r, "mixed", mixed.element());
        r.frame();
        pw.editor().text("hunter2");
        shown.editor().text("hunter2");
        shown.revealed(true);
        search.editor().text("dragon");
        number.value(42.5);
        tags.add("ui");
        tags.add("text");
        tags.add("emoji \uD83D\uDE00");
        bad.editor().text("42");
        bad.editor().validator(com.truesim.ui.text.edit.TextFilters.range(0, 10, false));
        ime.focus();
        r.type("kana: ");
        r.compose("\u304B\u306A", 2);
        selected.focus();
        r.frame();
        selected.text("select some words");
        r.press(UiCommand.WORD_LEFT);
        r.extend(UiCommand.WORD_LEFT);
        shoot(r, "text-fields.png");
    }

    @Test
    void areaAndRichText() throws Exception {
        if (CpuPreview.dir() == null) {
            return;
        }
        TextRig r = rig(620, 560);
        TextEditor area = TextEditor.area(r.ui, null, "Notes").lineNumbers(true);
        area.element().style("notes");
        r.ui.styles().sheet(sheet("textarea.notes { width: 560px; height: 200px; }\n"
            + "richtext { width: 560px; height: 220px; }\n"));
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 14; i++) {
            text.append("Line ").append(i).append(i % 3 == 0
                ? ": a longer line that wraps at the area's width so the soft breaks show between its words"
                : ": short").append('\n');
        }
        area.text(text);
        TextEditor rich = TextEditor.richText(r.ui, null, "Console", """
            [class=muted]12:00:01[/class] Server started on port [b]7777[/b]
            [class=muted]12:00:04[/class] [class=warning]warning:[/class] texture [class=code]rock_01.ktx2[/class] \
            missing, using [i]fallback[/i]
            [class=muted]12:00:05[/class] [class=error]error:[/class] shader compile failed \u2014 \
            [link=open shaders/rock.slang:42]rock.slang:42[/link]
            [class=muted]12:00:09[/class] [class=success]player joined[/class] [img=icons/heart] \
            [color=#c792ea]Ada[/color] says hi \uD83D\uDC4B\uD83C\uDFFD [bg=#3a3a00]highlighted[/bg] [u]under[/u] [s]struck[/s]
            [size=22]Larger text[/size] in a line, [class=info]info span[/class] and [class=mark]a mark[/class]""");
        r.add(area.element()).add(rich.element());
        r.frame();
        area.focus();
        r.frame();
        area.editable().select(area.text().length() - 120, area.text().length() - 30);
        r.frame();
        shoot(r, "text-area-and-rich-text.png");
    }
}
