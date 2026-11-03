package com.truesim.ui.style.tss;

import static com.truesim.ui.style.Styles.COLOR;
import static com.truesim.ui.style.Styles.FONT_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.value.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StyleWatcherTest {

    @TempDir
    Path dir;

    private Path write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void aBadReloadKeepsTheLastGoodSheet() throws IOException {
        Path main = write("hud.tss", "@use \"tokens\";\nlabel { color: tokens.$fg; font-size: 12; }");
        write("_tokens.tss", "$fg: red;");
        List<StyleSheet> reloaded = new ArrayList<>();
        List<TssException> failed = new ArrayList<>();
        StyleWatcher watcher = StyleWatcher.file(main).listener(new StyleWatcher.Listener() {
            @Override
            public void reloaded(StyleSheet sheet) {
                reloaded.add(sheet);
            }

            @Override
            public void failed(TssException error) {
                failed.add(error);
            }
        });
        StyleSheet first = watcher.sheet();
        assertThat(watcher.sources()).containsExactly("hud.tss", "_tokens.tss");
        assertThat(watcher.poll()).isNull();
        UiStore store = new UiStore();
        Element label = store.create("label", null);
        store.root().add(label);
        StyleResolver resolver = new StyleResolver(store, first);
        assertThat(resolver.styleOf(label.slot()).get(COLOR)).isEqualTo(Color.rgb(255, 0, 0));

        write("_tokens.tss", "$fg: rde;\n$x: 1px + 1em;");
        assertThat(watcher.reload()).isFalse();
        assertThat(failed).hasSize(1);
        assertThat(watcher.lastError()).isSameAs(failed.get(0));
        assertThat(watcher.lastError().file()).isEqualTo("_tokens.tss");
        assertThat(watcher.lastError().line()).isEqualTo(2);
        assertThat(watcher.sheet()).isSameAs(first);
        assertThat(watcher.poll()).isNull();
        assertThat(watcher.apply(resolver)).isFalse();
        assertThat(watcher.version()).isZero();

        write("_tokens.tss", "$fg: blue;");
        assertThat(watcher.reload()).isTrue();
        assertThat(watcher.lastError()).isNull();
        assertThat(reloaded).hasSize(1);
        assertThat(watcher.version()).isEqualTo(1);
        assertThat(watcher.sheet()).isNotEqualTo(first);
        long version = resolver.version();
        assertThat(watcher.apply(resolver)).isTrue();
        assertThat(resolver.version()).isGreaterThan(version);
        assertThat(resolver.styleOf(label.slot()).get(COLOR)).isEqualTo(Color.rgb(0, 0, 255));
        assertThat(watcher.poll()).isNull(); // handed over once

        Files.delete(dir.resolve("_tokens.tss"));
        assertThat(watcher.reload()).isFalse();
        assertThat(watcher.lastError().detail()).startsWith("cannot find tokens");
        watcher.close();
    }

    @Test
    void theWatchThreadReloadsWhenAPartialChanges() throws Exception {
        Path main = write("hud.tss", "@import \"parts/sizes\";\nlabel { font-size: $size; }");
        write("parts/_sizes.tss", "$size: 12;");
        StyleWatcher watcher = StyleWatcher.file(main).start();
        assertThat(watcher.start()).isSameAs(watcher); // twice is once
        try {
            write("unrelated.txt", "x"); // not a source: no reload
            write("parts/_sizes.tss", "$size: 20;");
            StyleSheet next = null;
            long until = System.nanoTime() + 20_000_000_000L;
            while (next == null && System.nanoTime() < until) {
                Thread.sleep(20);
                next = watcher.poll();
            }
            assertThat(next).as("reloaded within 20 s").isNotNull();
            assertThat(next.rules().get(0).declarations().get(0).value()).isEqualTo(20f);
            assertThat(watcher.version()).isGreaterThanOrEqualTo(1);
        } finally {
            watcher.close();
            watcher.close(); // twice is nothing
        }
    }

    @Test
    void pollingAllocatesNothing() throws IOException {
        StyleWatcher watcher = StyleWatcher.file(write("a.tss", "label { font-size: 3; }"));
        UiStore store = new UiStore();
        StyleResolver resolver = new StyleResolver(store, watcher.sheet());
        AllocationAssertions.assertAllocationFree(() -> {
            watcher.poll();
            watcher.apply(resolver);
        });
        assertThat(watcher.sheet().rules().get(0).declarations().get(0).key()).isSameAs(FONT_SIZE);
    }

    @Test
    void onlyFilesCanBeWatchedAndTheFirstCompileMustSucceed() {
        StyleWatcher memory = new StyleWatcher(SourceLoader.memory(Map.of("a.tss", "")), "a.tss");
        assertThatThrownBy(memory::start).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a file");
        assertThatThrownBy(() -> new StyleWatcher(SourceLoader.memory(Map.of("a.tss", "x")), "a.tss"))
            .isInstanceOf(TssException.class);
        SourceLoader broken = p -> {
            throw new java.io.UncheckedIOException(new IOException("disk on fire"));
        };
        StyleWatcher ok = new StyleWatcher(SourceLoader.memory(Map.of("a.tss", "")), "a.tss");
        assertThat(ok.reload()).isTrue();
        assertThat(ok.poll()).isEqualTo(StyleSheet.empty());
        StyleWatcher failing = new StyleWatcher(new SourceLoader() {
            private int reads;

            @Override
            public String read(String path) {
                return reads++ == 0 ? "" : broken.read(path);
            }
        }, "a.tss");
        assertThat(failing.reload()).isFalse();
        assertThat(failing.lastError().detail()).isEqualTo("cannot read: disk on fire");
    }
}
