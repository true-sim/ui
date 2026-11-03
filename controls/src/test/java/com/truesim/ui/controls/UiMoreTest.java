package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.TextSystem;
import java.awt.image.BufferedImage;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiMoreTest {

    @TempDir
    Path dir;

    @Test
    void gridTracksComeFromStyles() {
        StyleSheet sheet = Tss.compileString("grid.tss", """
            panel.grid { layout: grid; grid-template-columns: 100px 1fr auto minmax(20px, 2fr) minmax(auto, 30px);
              grid-template-rows: 40px; width: 500px; height: 40px; }
            panel.cell { height: 10px; }
            label { font-family: sans-serif; font-style: italic; }
            """);
        TextSystem text = UiTest.jdkText();
        Ui ui = new Ui(sheet, text);
        Element grid = ui.panel("grid");
        ui.root().add(grid);
        Element[] cells = new Element[5];
        for (int i = 0; i < 4; i++) {
            cells[i] = ui.panel("cell");
            grid.add(cells[i]);
        }
        cells[4] = ui.label(null, "x");
        grid.add(cells[4]);
        UiTest.settle(ui, 800, 600);
        assertThat(ui.layout().width(cells[0].slot())).isEqualTo(100);
        assertThat(ui.layout().x(cells[1].slot())).isEqualTo(100);

        // destroying a label forgets it; a detached element records nothing
        ui.destroy(cells[4]);
        Element loose = ui.panel("cell");
        loose.markDirty(UiStore.DIRTY_DRAW);
        UiTest.settle(ui, 800, 600);
        assertThat(ui.recorded()).isZero();
    }

    @Test
    void imagesLoadFromFiles() throws Exception {
        Ui ui = new Ui(Tss.defaultTheme(), null);
        Path png = dir.resolve("a.png");
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        assertThat(ui.images().load("a", png).width()).isEqualTo(3);
        Path bad = dir.resolve("b.png");
        Files.writeString(bad, "not an image");
        assertThatThrownBy(() -> ui.images().load("b", bad)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> ui.images().load("c", dir.resolve("missing.png")))
            .isInstanceOf(UncheckedIOException.class);
        assertThat(ui.tunables()).hasSize(17); // 10 of the frame, 7 of tooltips, hover cards and windows
        assertThat(ui.textSystem()).isNull();
    }

    @Test
    void zeroSizedImagesRecordNothing() {
        StyleSheet sheet = Tss.compileString("img.tss", "image { image: \"a\"; size: 0px; }");
        Ui ui = new Ui(sheet, null);
        ui.images().add("a", new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        Element image = ui.image(null);
        ui.root().add(image);
        ui.update(100, 100);
        assertThat(ui.layers()[0].size()).isZero();
    }
}
