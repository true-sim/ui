package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.Character.UnicodeScript;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontRegistryTest {

    @TempDir
    Path dir;

    private FontRegistry registry(Fakes.Backend backend) {
        FontRegistry r = new FontRegistry(backend);
        r.register(Fakes.source(dir, "latin.ttf", "Latin", 400, false));
        r.register(Fakes.source(dir, "latin-bold.ttf", "Latin", 700, false));
        r.register(Fakes.source(dir, "latin-italic.ttf", "Latin", 400, true));
        r.register(Fakes.source(dir, "hebrew.ttf", "Hebrew", 400, false));
        r.register(Fakes.source(dir, "symbols.ttf", "Symbols", 400, false));
        r.register(Fakes.source(dir, "broken.ttf", "Broken", 400, false));
        return r;
    }

    @Test
    void facesMatchByStyleThenNearestWeight() {
        Fakes.Backend backend = new Fakes.Backend();
        FontRegistry r = registry(backend);
        assertThat(backend.opened.get()).isZero(); // nothing opens until used
        assertThat(r.match("latin", 400, false).source().weight()).isEqualTo(400);
        assertThat(r.match("Latin", 600, false).source().weight()).isEqualTo(700);
        assertThat(r.match("Latin", 550, false).source().weight()).isEqualTo(700); // a tie goes to the heavier
        assertThat(r.match("Latin", 900, true).source().italic()).isTrue();
        assertThat(r.match("Latin", 700, true).source().weight()).isEqualTo(400);
        assertThat(r.match("Missing", 400, false)).isNull();
        assertThat(r.match("Broken", 400, false)).isNull();
        assertThat(r.size()).isEqualTo(6);
        assertThat(r.source(5).family()).isEqualTo("Broken");
        assertThat(r.families()).containsExactly("Latin", "Hebrew", "Symbols", "Broken");
        assertThat(r.backend()).isSameAs(backend);
    }

    @Test
    void aliasesNameFamilyLists() {
        FontRegistry r = registry(new Fakes.Backend());
        r.alias("sans-serif", "Missing", "Hebrew", "Latin");
        r.alias("nothing", "Missing");
        assertThat(r.match("Sans-Serif", 400, false).source().family()).isEqualTo("Hebrew");
        assertThat(r.match("nothing", 400, false)).isNull();
        FontChain chain = r.chain(List.of("sans-serif", "Latin"), 400, false);
        assertThat(chain.size()).isEqualTo(2); // Hebrew, Latin; Latin not twice
        assertThat(chain.face(1).source().family()).isEqualTo("Latin");
    }

    @Test
    void chainsFallBackByScriptThenLastResort() {
        FontRegistry r = registry(new Fakes.Backend());
        r.fallback(UnicodeScript.HEBREW, "Hebrew");
        r.lastResort("Missing", "Symbols");
        FontChain chain = r.chain(List.of("Latin"), 400, false);
        assertThat(chain.primary().source().family()).isEqualTo("Latin");
        assertThat(chain.faceFor('a', UnicodeScript.LATIN).source().family()).isEqualTo("Latin");
        assertThat(chain.faceFor(0x5D0, UnicodeScript.HEBREW).source().family()).isEqualTo("Hebrew");
        assertThat(chain.faceFor(0x5D0, UnicodeScript.HEBREW).source().family()).isEqualTo("Hebrew"); // cached
        assertThat(chain.faceFor(0x4F60, UnicodeScript.HAN).source().family()).isEqualTo("Symbols");
        assertThat(r.chain(List.of("Latin"), 400, false)).isSameAs(chain);

        FontChain nothing = r.chain(List.of("Missing"), 400, false);
        assertThat(nothing.size()).isZero();
        assertThat(nothing.primary().source().family()).isEqualTo("Symbols");

        FontRegistry bare = new FontRegistry(new Fakes.Backend());
        bare.register(Fakes.source(dir, "hebrew.ttf", "Hebrew", 400, false));
        FontChain hebrewOnly = bare.chain(List.of("Hebrew"), 400, false);
        assertThat(hebrewOnly.faceFor('a', UnicodeScript.LATIN)).isSameAs(hebrewOnly.primary()); // nothing covers
        assertThat(bare.chain(List.of("Missing"), 400, false).primary()).isNull();
    }

    @Test
    void changesMakeNewChains() {
        FontRegistry r = registry(new Fakes.Backend());
        FontChain before = r.chain(List.of("Latin"), 400, false);
        long version = r.version();
        r.register(Fakes.source(dir, "more.ttf", "More", 400, false));
        assertThat(r.version()).isGreaterThan(version);
        assertThat(r.chain(List.of("Latin"), 400, false)).isNotSameAs(before);
        assertThatThrownBy(() -> r.register(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sourcesCheckTheirArguments() throws IOException {
        Path file = dir.resolve("f.ttf");
        Files.write(file, new byte[10]);
        FontSource s = FontSource.of(file, 2, "F", 700, true);
        assertThat(s.key()).isEqualTo("f.ttf#2:10");
        assertThat(s.path()).isEqualTo(file);
        assertThat(s.index()).isEqualTo(2);
        assertThat(s.toString()).contains("F 700 italic");
        assertThat(FontSource.of(file, 0, "F", 400, false).toString()).doesNotContain("italic");
        assertThatThrownBy(() -> FontSource.of(file, -1, "F", 400, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FontSource.of(file, 0, "F", 0, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FontSource.of(file, 0, "F", 1001, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FontSource.of(dir.resolve("none.ttf"), 0, "F", 400, false))
            .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void systemFontsAreFoundByFileName() throws IOException {
        Path sub = Files.createDirectories(dir.resolve("truetype/dejavu"));
        Files.write(sub.resolve("DejaVuSans.ttf"), new byte[] {1});
        Files.write(sub.resolve("unknown.ttf"), new byte[] {1});
        List<FontSource> found = SystemFonts.discover(List.of(dir, dir.resolve("missing")));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).family()).isEqualTo("DejaVu Sans");
        assertThat(SystemFonts.discover()).isNotNull();
    }
}
