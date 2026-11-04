package com.truesim.ui.controls.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file browser and its dialogs over the sample files in memory (and once over a temporary folder): listing,
 * sorting folders first, filters, hidden files, navigation (activate, back, forward, up, breadcrumbs, the path
 * field, Backspace), places, choosing in the three modes, the save dialog's overwrite confirmation, new folders and
 * renaming, the folder's watch, type-ahead.
 */
class FileBrowserTest {

    static final String HOME = "/home/player";

    /** A UI in the default theme with input, one frame at a time. */
    static final class Rig {
        final Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.jdkText());
        final UiInput input = new UiInput(32);
        final SyntheticInput synthetic = new SyntheticInput();
        long now = 1_000_000_000L;

        Rig run(int frames) {
            for (int i = 0; i < frames; i++) {
                input.beginFrame();
                synthetic.poll(input);
                ui.update(1280, 720, input, now);
                now += 16_000_000L;
            }
            return this;
        }

        Rig keys(UiCommand... commands) {
            synthetic.press(commands);
            return run(2);
        }
    }

    static List<String> names(FileBrowser b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.rows().size(); i++) {
            out.add(b.rows().get(i).name());
        }
        return out;
    }

    @Test
    void theHomeFolderListsFoldersFirstAndFiltersFiles() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(3);
        FileBrowser b = g.browser;
        assertThat(b.directory()).isEqualTo(HOME);
        // images only (the first filter), folders first, both by name
        assertThat(names(b)).containsExactly("Documents", "Mods", "Pictures", "Saves", "crater.jpg", "map.png",
            "nebula.jpg", "ship.png", "station.png", "sunset.png");
        b.filterList().select(1); // all files
        r.run(2);
        assertThat(names(b)).contains("notes.txt", "settings.cfg").doesNotContain(".cache");
        b.showHidden(true);
        r.run(2);
        assertThat(names(b)).contains(".cache");
        // sorting by size: folders still first
        b.table().sort(b.table().column(1), false);
        r.run(1);
        assertThat(names(b).subList(0, 4)).containsExactlyInAnyOrder("Documents", "Mods", "Pictures", "Saves");
        assertThat(names(b).get(4)).isEqualTo("settings.cfg");
        assertThat(b.table().cell(4, b.table().column(1))).isNotNull();
        // the gallery's selection named the file
        assertThat(b.nameField().text().toString()).isEqualTo("ship.png");
        Semantics.Node tree = Semantics.of(r.ui.store(), r.ui.interaction());
        assertThat(Semantics.unnamed(tree)).isEmpty();
    }

    @Test
    void navigationGoesIntoFoldersBackForwardUpAndByThePath() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(3);
        FileBrowser b = g.browser;
        assertThat(b.breadcrumbs().steps()).isEqualTo(3); // /, home, player
        int saves = names(b).indexOf("Saves");
        b.table().activate(saves);
        r.run(2);
        assertThat(b.directory()).isEqualTo(HOME + "/Saves");
        assertThat(names(b)).isEmpty(); // .sav files are no images
        b.filterList().select(1);
        r.run(2);
        assertThat(names(b)).containsExactly("slot1.sav", "slot2.sav", "slot3.sav");
        b.back();
        assertThat(b.directory()).isEqualTo(HOME);
        b.forward();
        assertThat(b.directory()).isEqualTo(HOME + "/Saves");
        b.up();
        r.run(2);
        assertThat(b.directory()).isEqualTo(HOME);
        assertThat(b.selection().items()).extracting(FileEntry::name).containsExactly("Saves"); // came from there
        b.breadcrumbs().crumb(1).click(); // "home"
        r.run(2);
        assertThat(b.directory()).isEqualTo("/home");
        // the path field
        b.editPath(true);
        r.run(1);
        assertThat(b.editingPath()).isTrue();
        b.pathField().text(HOME + "/Pictures");
        r.ui.interaction().focus(b.pathField().element(), 0);
        r.keys(UiCommand.SUBMIT);
        assertThat(b.directory()).isEqualTo(HOME + "/Pictures");
        assertThat(b.editingPath()).isFalse();
        b.editPath(true);
        r.run(1);
        b.pathField().text("/nowhere");
        r.ui.interaction().focus(b.pathField().element(), 0);
        r.keys(UiCommand.SUBMIT);
        assertThat(b.error().toString()).contains("No such folder");
        r.keys(UiCommand.CANCEL);
        assertThat(b.editingPath()).isFalse();
        // Backspace in the table goes up
        r.ui.interaction().focus(b.table().root(), 0);
        r.keys(UiCommand.DELETE_BACK);
        assertThat(b.directory()).isEqualTo(HOME);
        // a folder that cannot be read keeps the one shown
        assertThat(b.navigate("/missing")).isFalse();
        assertThat(b.directory()).isEqualTo(HOME);
        assertThat(b.error().toString()).contains("No such folder");
    }

    @Test
    void placesListHomeRootsFavouritesAndRecentFolders() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(3);
        List<String> places = new ArrayList<>();
        for (int i = 0; i < g.browser.places().rows().size(); i++) {
            FileBrowser.Place p = g.browser.places().rows().get(i);
            places.add(p.kind() + " " + p.name());
        }
        assertThat(places).containsExactly("HOME player", "ROOT /", "ROOT D:/", "FAVOURITE Saves",
            "FAVOURITE Pictures", "RECENT Mods");
        g.browser.places().activate(2);
        assertThat(g.browser.directory()).isEqualTo("D:/");
        g.files.places().favourite(HOME + "/Documents");
        r.run(2);
        assertThat(g.browser.places().rows().size()).isEqualTo(7);
    }

    @Test
    void openingChoosesSelectedOrTypedFiles() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        FileBrowser b = g.browser.multiple(true);
        r.run(3);
        b.select(HOME + "/map.png");
        b.table().selection().items().add(b.model().stat(HOME + "/crater.jpg"));
        r.run(2);
        assertThat(b.nameField().text().toString()).isEqualTo("\"crater.jpg\" \"map.png\"");
        assertThat(b.chosen()).containsExactly(HOME + "/crater.jpg", HOME + "/map.png");
        b.table().selection().clear();
        b.nameField().text("notes.txt");
        assertThat(b.chosen()).containsExactly(HOME + "/notes.txt");
        b.nameField().text("nope.png");
        assertThat(b.chosen()).isNull();
        assertThat(b.error().toString()).contains("No such file");
        b.nameField().text("Saves"); // a folder typed: entered
        assertThat(b.chosen()).isNull();
        assertThat(b.directory()).isEqualTo(HOME + "/Saves");
        assertThat(FileBrowser.names("\"a b.png\" \"c.png\"")).containsExactly("a b.png", "c.png");
    }

    @Test
    void theOpenDialogAcceptsAndCancels() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(2);
        List<List<String>> told = new ArrayList<>();
        FileDialog d = FileDialog.open(g.controls, g.files, "Open").show(told::add);
        r.run(3);
        assertThat(d.dialog().showing()).isTrue();
        d.browser().nameField().text("ship.png");
        d.acceptButton().click();
        r.run(30);
        assertThat(told).containsExactly(List.of(HOME + "/ship.png"));
        assertThat(g.files.places().recent()).first().isEqualTo(HOME);
        FileDialog cancelled = FileDialog.folder(g.controls, g.files, "Folder").show(told::add);
        r.run(3);
        assertThat(names(cancelled.browser())).containsExactly("Documents", "Mods", "Pictures", "Saves");
        cancelled.dialog().close(0);
        r.run(30);
        assertThat(told.get(1)).isEmpty();
        FileDialog folder = FileDialog.folder(g.controls, g.files, "Folder").show(told::add);
        r.run(3);
        folder.accept(); // nothing selected: the folder shown
        r.run(30);
        assertThat(told.get(2)).containsExactly(HOME);
    }

    @Test
    void savingOverAFileAsksFirstAndANewNameGetsTheFiltersExtension() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(2);
        g.openDialog();
        r.run(3);
        g.confirmOverwrite();
        r.run(3);
        assertThat(g.dialog.confirmation()).isNotNull();
        assertThat(g.dialog.result()).isEmpty();
        g.dialog.confirmation().button(1).click(); // Replace
        r.run(30);
        assertThat(g.dialog.result()).containsExactly(HOME + "/map.png");
        List<List<String>> told = new ArrayList<>();
        FileDialog save = FileDialog.save(g.controls, g.files, "Save").filters(List.of(FileFilter.of("PNG", "png")))
            .name("fresh").show(told::add);
        r.run(3);
        save.accept();
        r.run(30);
        assertThat(told).containsExactly(List.of(HOME + "/fresh.png"));
    }

    @Test
    void newFoldersAreRenamedInPlaceAndTheWatchListsChanges() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(3);
        FileBrowser b = g.browser;
        String made = b.newFolder();
        r.run(2);
        assertThat(made).isEqualTo(HOME + "/New folder");
        assertThat(b.table().editing()).isTrue();
        assertThat(b.newFolder()).isEqualTo(HOME + "/New folder 2");
        r.run(2);
        b.table().cancel();
        // a change on the model shows by the folder's watch
        g.files.dir(HOME + "/Screenshots");
        r.run(2);
        assertThat(names(b)).contains("Screenshots");
        // renaming through the name column's editor
        int at = names(b).indexOf("Screenshots");
        assertThat(b.table().edit(at, b.table().column(0))).isTrue();
        r.run(2);
        r.synthetic.press(UiCommand.SELECT_ALL);
        r.run(1);
        r.synthetic.type("Shots");
        r.run(1);
        r.keys(UiCommand.SUBMIT);
        r.run(2);
        assertThat(g.files.stat(HOME + "/Shots")).isNotNull();
        assertThat(names(b)).contains("Shots").doesNotContain("Screenshots");
    }

    @Test
    void typingJumpsToAName() {
        Rig r = new Rig();
        FilesGallery g = FilesGallery.build(r.ui);
        r.run(3);
        FileBrowser b = g.browser;
        r.ui.interaction().focus(b.table().root(), 0);
        r.synthetic.type("st");
        r.run(2);
        assertThat(b.rows().get(b.table().current()).name()).isEqualTo("station.png");
        b.view().set(0); // the list view, the selection carried over
        r.run(2);
        assertThat(b.list().selection().items()).extracting(FileEntry::name).containsExactly("station.png");
    }

    @Test
    void theRealFileSystemInATemporaryFolder(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.txt"), "x");
        Files.createDirectory(dir.resolve("a"));
        Rig r = new Rig();
        FileBrowser b = new FileBrowser(new Controls(r.ui), new NioFileModel(dir.getFileSystem(), dir.toString()));
        r.ui.root().add(b.element());
        r.run(3);
        assertThat(names(b)).containsExactly("a", "b.txt");
        b.newFolder();
        assertThat(Files.isDirectory(dir.resolve("New folder"))).isTrue();
        b.close();
    }
}
