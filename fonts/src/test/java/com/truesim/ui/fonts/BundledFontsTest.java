package com.truesim.ui.fonts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.text.FontChain;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.io.UncheckedIOException;
import java.lang.Character.UnicodeScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BundledFontsTest {

    @Test
    void theDefaultChainUsesTheBundledFaces() {
        try (NativeTextBackend backend = new NativeTextBackend()) {
            FontRegistry fonts = BundledFonts.registry(backend);
            assertThat(fonts.match("Inter", 700, false).source().path().getFileName().toString())
                .isEqualTo("Inter-Bold.ttf");
            assertThat(fonts.match("Inter", 400, true).source().italic()).isTrue();
            FontChain chain = fonts.chain(List.of("sans-serif"), 400, false);
            assertThat(chain.primary().source().family()).isEqualTo("Inter");
            assertThat(chain.faceFor('م', UnicodeScript.ARABIC).source().family()).isEqualTo("Noto Sans Arabic");
            assertThat(chain.faceFor('न', UnicodeScript.DEVANAGARI).source().family())
                .isEqualTo("Noto Sans Devanagari");
            assertThat(chain.faceFor('Ж', UnicodeScript.CYRILLIC).source().family()).isEqualTo("Inter");
        }
    }

    @Test
    void filesAreCopiedOnceAndMissingOnesFail() throws Exception {
        Object[][] faces = {{"Inter-Regular.ttf"}};
        Path dir = BundledFonts.extract(getClass().getClassLoader(), "com/truesim/ui/fonts/", "copy-test", faces);
        Path file = dir.resolve("Inter-Regular.ttf");
        long modified = Files.getLastModifiedTime(file).toMillis();
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modified - 10_000));
        BundledFonts.extract(getClass().getClassLoader(), "com/truesim/ui/fonts/", "copy-test", faces);
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modified - 10_000);
        Files.write(file, new byte[] {1, 2, 3}); // damaged: copied again
        BundledFonts.extract(getClass().getClassLoader(), "com/truesim/ui/fonts/", "copy-test", faces);
        assertThat(Files.size(file)).isGreaterThan(100_000);
        assertThatThrownBy(() -> BundledFonts.extract(getClass().getClassLoader(), "nope/", "copy-test",
            new Object[][] {{"x.ttf"}})).isInstanceOf(UncheckedIOException.class);
        Path blocked = dir.getParent().resolve("blocked-set");
        Files.deleteIfExists(blocked);
        Files.writeString(blocked, "a file where a directory goes");
        assertThatThrownBy(() -> BundledFonts.extract(getClass().getClassLoader(), "com/truesim/ui/fonts/",
            "blocked-set", faces)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void creditsAndLicencesShip() {
        for (String name : new String[] {"CREDITS.md", "OFL-Inter.txt", "OFL-NotoSansArabic.txt",
            "OFL-NotoSansDevanagari.txt"}) {
            assertThat(getClass().getClassLoader().getResource("com/truesim/ui/fonts/" + name)).as(name).isNotNull();
        }
    }
}
