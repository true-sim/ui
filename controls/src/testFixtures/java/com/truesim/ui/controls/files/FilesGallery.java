package com.truesim.ui.controls.files;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A gallery of the file browser for screenshots and theme checks: a browser over a {@link MemoryFileModel} (folders,
 * images, saves, a hidden file, favourites and recent places) in the details view, sorted and with a selection, and on
 * demand ({@link #openDialog}) a save dialog over it asking whether to replace a file. Build it in a UI of about
 * 1280 x 720 pixels and update a few frames; every state is set by code.
 *
 * <pre>{@code
 * FilesGallery.registerIcons(ui);
 * FilesGallery g = FilesGallery.build(ui);
 * }</pre>
 */
public final class FilesGallery {

    /** The time of every file: 2026-11-04 10:30 UTC, then a minute more per file. */
    static final long TIME = 1_793_788_200_000L;

    public final Controls controls;
    public final MemoryFileModel files;
    public final FileBrowser browser;
    public FileDialog dialog;

    private FilesGallery(Controls controls, MemoryFileModel files, FileBrowser browser) {
        this.controls = controls;
        this.files = files;
        this.browser = browser;
    }

    /** The sample files: a home with folders, images, saves, a hidden file; a second root; places. */
    public static MemoryFileModel sample() {
        MemoryFileModel m = new MemoryFileModel("/home/player").root("D:/");
        String home = "/home/player";
        m.dir(home + "/Documents").dir(home + "/Pictures").dir(home + "/Saves").dir(home + "/Mods");
        String[] images = {"sunset.png", "crater.jpg", "ship.png", "station.png", "nebula.jpg", "map.png"};
        for (int i = 0; i < images.length; i++) {
            m.file(home + "/" + images[i], 120_000L + i * 734_517L, TIME + i * 60_000L);
        }
        m.file(home + "/notes.txt", 1_843, TIME + 10 * 60_000L);
        m.file(home + "/settings.cfg", 512, TIME + 11 * 60_000L);
        m.file(home + "/.cache", 4_096, TIME).hidden(home + "/.cache");
        for (int i = 1; i <= 3; i++) {
            m.file(home + "/Saves/slot" + i + ".sav", 2_048L * i, TIME + i * 3_600_000L);
        }
        m.file("D:/backup.zip", 3_221_225_472L, TIME);
        m.places().favourite(home + "/Saves").favourite(home + "/Pictures");
        m.places().used(home + "/Mods");
        return m;
    }

    /** Builds the gallery into {@code ui}'s root. */
    public static FilesGallery build(Ui ui) {
        Controls controls = new Controls(ui);
        MemoryFileModel files = sample();
        FileBrowser browser = new FileBrowser(controls, files);
        browser.filters(List.of(FileFilter.of("Images (*.png, *.jpg)", "png", "jpg"),
            FileFilter.all("All files")));
        browser.element().style("gallery");
        ui.styles().inline(browser.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(900))
            .set(Styles.HEIGHT, Length.px(460)).build());
        ui.root().add(browser.element());
        browser.select("/home/player/ship.png");
        return new FilesGallery(controls, files, browser);
    }

    /** Opens a save dialog naming an existing file and asks to replace it (settle frames after). */
    public void openDialog() {
        dialog = FileDialog.save(controls, files, "Save picture")
            .filters(List.of(FileFilter.of("PNG image (*.png)", "png")))
            .name("map.png");
        dialog.show(paths -> {
        });
    }

    /** Asks to replace the file the save dialog names (the confirmation shows over it). */
    public void confirmOverwrite() {
        dialog.accept();
    }

    /** Simple icons for the theme's icon names (the toolkit ships none): folders, files, arrows, places. */
    public static void registerIcons(Ui ui) {
        icon(ui, "back", g -> {
            g.drawLine(11, 3, 5, 8);
            g.drawLine(5, 8, 11, 13);
        });
        icon(ui, "forward", g -> {
            g.drawLine(5, 3, 11, 8);
            g.drawLine(11, 8, 5, 13);
        });
        icon(ui, "up", g -> {
            g.drawLine(3, 11, 8, 5);
            g.drawLine(8, 5, 13, 11);
        });
        icon(ui, "edit", g -> {
            g.drawLine(3, 13, 12, 4);
            g.drawLine(3, 13, 6, 13);
        });
        icon(ui, "new-folder", g -> {
            g.drawRect(2, 5, 12, 8);
            g.drawLine(8, 7, 8, 11);
            g.drawLine(6, 9, 10, 9);
        });
        icon(ui, "folder", g -> {
            g.fillRect(2, 4, 5, 2);
            g.fillRect(2, 6, 12, 7);
        });
        icon(ui, "file", g -> {
            g.drawRect(4, 2, 8, 12);
            g.drawLine(6, 6, 10, 6);
            g.drawLine(6, 9, 10, 9);
        });
        icon(ui, "home", g -> {
            g.drawLine(2, 8, 8, 2);
            g.drawLine(8, 2, 14, 8);
            g.drawRect(4, 8, 8, 6);
        });
        icon(ui, "drive", g -> {
            g.drawRect(2, 5, 12, 6);
            g.fillRect(11, 7, 2, 2);
        });
        icon(ui, "favourite", g -> g.fillPolygon(new int[] {8, 10, 15, 11, 12, 8, 4, 5, 1, 6},
            new int[] {1, 6, 6, 9, 15, 11, 15, 9, 6, 6}, 10));
        icon(ui, "recent", g -> {
            g.drawOval(2, 2, 12, 12);
            g.drawLine(8, 8, 8, 4);
            g.drawLine(8, 8, 11, 9);
        });
        icon(ui, "crumb-separator", g -> {
            g.drawLine(6, 4, 10, 8);
            g.drawLine(10, 8, 6, 12);
        });
        icon(ui, "dropdown-arrow", g -> {
            g.drawLine(4, 6, 8, 10);
            g.drawLine(8, 10, 12, 6);
        });
        icon(ui, "dismiss", g -> {
            g.drawLine(4, 4, 12, 12);
            g.drawLine(12, 4, 4, 12);
        });
        icon(ui, "down", g -> {
            g.drawLine(3, 5, 8, 11);
            g.drawLine(8, 11, 13, 5);
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
        ui.images().add("icons/" + name, img);
    }
}
