package com.truesim.ui.controls.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file models: the one in memory, and the real file system inside a temporary folder only. */
class FileModelTest {

    @Test
    void theMemoryModelListsStatsJoinsAndSplitsPaths() throws IOException {
        MemoryFileModel m = new MemoryFileModel("/home/a").root("D:/");
        m.file("/home/a/x.png", 10, 5).dir("/home/a/sub").file("D:/z.txt", 3, 1).hidden("/home/a/x.png");
        assertThat(m.list("/home/a")).extracting(FileEntry::name).containsExactlyInAnyOrder("x.png", "sub");
        FileEntry x = m.stat("/home/a/x.png");
        assertThat(x.size()).isEqualTo(10);
        assertThat(x.hidden()).isTrue();
        assertThat(x.extension()).isEqualTo("png");
        assertThat(x.hasExtension("PNG")).isTrue();
        assertThat(m.stat("/home/a/nope")).isNull();
        assertThat(m.roots()).extracting(FileEntry::path).containsExactly("/", "D:/");
        assertThat(m.parent("/home/a")).isEqualTo("/home");
        assertThat(m.parent("/home")).isEqualTo("/");
        assertThat(m.parent("/")).isNull();
        assertThat(m.parent("D:/z.txt")).isEqualTo("D:/");
        assertThat(m.child("/", "home")).isEqualTo("/home");
        assertThat(m.resolve("/home/a", "../b/./c")).isEqualTo("/home/b/c");
        assertThat(m.resolve("/home/a", "D:/z.txt")).isEqualTo("D:/z.txt");
        assertThat(m.steps("/home/a/sub")).containsExactly("/", "home", "a", "sub");
        m.createDirectory("/home/a/new");
        assertThatThrownBy(() -> m.createDirectory("/home/a/new")).isInstanceOf(IOException.class);
        FileModel.Watch watch = m.watch("/home/a");
        assertThat(watch.changed()).isFalse();
        m.rename("/home/a/sub", "/home/a/renamed");
        assertThat(watch.changed()).isTrue();
        assertThat(watch.changed()).isFalse();
        assertThat(m.stat("/home/a/renamed").directory()).isTrue();
        assertThatThrownBy(() -> m.list("/home/a/x.png")).isInstanceOf(IOException.class);
        m.delete("/home/a/renamed");
        assertThat(m.stat("/home/a/renamed")).isNull();
    }

    @Test
    void placesKeepFavouritesAndRecentFoldersAndRoundTrip() {
        FilePlaces p = new FilePlaces();
        int v = p.version();
        p.favourite("/a").favourite("/b").favourite("/a");
        assertThat(p.favourites()).containsExactly("/a", "/b");
        float limit = FilePlaces.RECENT_LIMIT.get();
        try {
            FilePlaces.RECENT_LIMIT.set(2);
            p.used("/x").used("/y").used("/z").used("/y");
            assertThat(p.recent()).containsExactly("/y", "/z");
        } finally {
            FilePlaces.RECENT_LIMIT.set(limit);
        }
        assertThat(p.version()).isGreaterThan(v);
        FilePlaces q = new FilePlaces();
        q.restore(p.describe());
        assertThat(q.favourites()).containsExactly("/a", "/b");
        assertThat(q.recent()).containsExactly("/y", "/z");
        p.unfavourite("/a");
        assertThat(p.isFavourite("/a")).isFalse();
    }

    @Test
    void theNioModelWorksInATemporaryFolder(@TempDir Path dir) throws IOException {
        NioFileModel m = new NioFileModel(dir.getFileSystem(), dir.toString());
        Files.writeString(dir.resolve("a.txt"), "hello");
        Files.createDirectory(dir.resolve("sub"));
        List<FileEntry> listed = m.list(dir.toString());
        assertThat(listed).extracting(FileEntry::name).containsExactlyInAnyOrder("a.txt", "sub");
        FileEntry a = m.stat(dir.resolve("a.txt").toString());
        assertThat(a.size()).isEqualTo(5);
        assertThat(a.directory()).isFalse();
        assertThat(a.modified()).isPositive();
        assertThat(m.stat(dir.resolve("missing").toString())).isNull();
        assertThat(m.home()).isEqualTo(dir.toString());
        assertThat(m.parent(dir.resolve("a.txt").toString())).isEqualTo(dir.toString());
        assertThat(m.child(dir.toString(), "a.txt")).isEqualTo(dir.resolve("a.txt").toString());
        assertThat(m.resolve(dir.toString(), "sub/../a.txt")).isEqualTo(dir.resolve("a.txt").toString());
        List<String> steps = m.steps(dir.resolve("sub").toString());
        assertThat(steps.get(steps.size() - 1)).isEqualTo("sub");
        assertThat(m.roots()).isNotEmpty();
        m.createDirectory(dir.resolve("made").toString());
        assertThat(Files.isDirectory(dir.resolve("made"))).isTrue();
        m.rename(dir.resolve("made").toString(), dir.resolve("moved").toString());
        assertThat(Files.isDirectory(dir.resolve("moved"))).isTrue();
        assertThatThrownBy(() -> m.list(dir.resolve("a.txt").toString())).isInstanceOf(IOException.class);
        FileModel.Watch watch = m.watch(dir.toString());
        if (watch != null) {
            watch.changed();
            watch.close();
        }
        assertThat(m.places().favourites()).isEmpty();
    }
}
