package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.DrawListDump;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The gallery under both themes: everything named for assistive technology, drawn, and (with
 * {@code -Dtruesim.gallery.dir=<dir>}) its draw lists written there for the integrator's GPU screenshots.
 */
class DataGalleryTest {

    @Test
    void theGalleryBuildsUnderBothThemes() throws IOException {
        String dir = System.getProperty("truesim.gallery.dir");
        String[] names = {"default", "test"};
        StyleSheet[] sheets = {Tss.defaultTheme(), Tss.testTheme()};
        for (int t = 0; t < 2; t++) {
            Ui ui = new Ui(sheets[t], UiTestAccess.jdkText());
            DataGallery gallery = DataGallery.build(ui);
            DataGallery.settle(ui, 1600, 1000);
            gallery.pose(ui, 1600, 1000);
            assertThat(gallery.table.editing()).isTrue();
            assertThat(gallery.list.selection().size()).isEqualTo(3);
            assertThat(gallery.tree.rows().size()).isEqualTo(12);
            Semantics.Node tree = Semantics.of(ui.store(), ui.interaction());
            assertThat(Semantics.unnamed(tree)).isEmpty();
            String drawn = DrawListDump.of(ui.layers()[0]);
            assertThat(drawn).contains("glyph");
            if (dir != null) {
                Path out = Path.of(dir);
                Files.createDirectories(out);
                Files.writeString(out.resolve("data-gallery-" + names[t] + ".drawlist.txt"), drawn);
                Files.writeString(out.resolve("data-gallery-" + names[t] + ".semantics.txt"), tree.toString());
            }
        }
    }
}
