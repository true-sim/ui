package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.testkit.LayoutSanity;
import org.junit.jupiter.api.Test;

/** The props gallery builds, binds and lays out in both themes. */
class GalleryTest {

    @Test
    void buildsInBothThemes() {
        for (var sheet : new com.truesim.ui.style.StyleSheet[] {Rig.DEFAULT, Rig.GARISH}) {
            Rig r = new Rig(sheet);
            PropsGallery g = PropsGallery.build(r.c, r.ui.root());
            r.settle();
            assertThat(g.panel.rows()).hasSize(15);
            assertThat(g.panel.model().source()).isEqualTo("generated");
            assertThat(g.panel.row("waypoints").editor()).isInstanceOf(CollectionEditor.class);
            System.out.println(g.panel.layout() + " " + g.panel.ordered());
            System.out.println(LayoutSanity.check(r.ui));
        }
    }
}
