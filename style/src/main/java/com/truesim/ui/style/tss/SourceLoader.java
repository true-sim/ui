package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where {@code .tss} sources come from, by path ({@code themes/default-theme.tss}, always with {@code /}): class path
 * resources, a directory, or strings in memory (tests). {@code @use} and {@code @import} resolve relative to the file
 * they are in, trying {@code name.tss} and the partial {@code _name.tss}.
 */
public interface SourceLoader {

    /**
     * The text of {@code path}, or null if there is none.
     *
     * @throws UncheckedIOException if it exists but cannot be read
     */
    String read(String path);

    /** The file behind {@code path} if it is one on disk (for watching), else null. */
    default Path file(String path) {
        return null;
    }

    /** Resources of {@code loader} under {@code root} ({@code ""} or {@code "com/truesim/ui/style"}). */
    @AllowAllocation("made once")
    static SourceLoader classpath(ClassLoader loader, String root) {
        String prefix = root.isEmpty() || root.endsWith("/") ? root : root + "/";
        return new SourceLoader() {
            @Override
            @AllowAllocation("sheets load at start-up and on reload")
            public String read(String path) {
                try (InputStream in = loader.getResourceAsStream(prefix + path)) {
                    return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /** Files under directory {@code root}. */
    @AllowAllocation("made once")
    static SourceLoader files(Path root) {
        return new SourceLoader() {
            @Override
            @AllowAllocation("sheets load at start-up and on reload")
            public String read(String path) {
                Path file = file(path);
                if (!Files.isRegularFile(file)) {
                    return null;
                }
                try {
                    return Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public Path file(String path) {
                return root.resolve(path).normalize();
            }
        };
    }

    /** Sources held in memory, by path. */
    @AllowAllocation("made once")
    static SourceLoader memory(Map<String, String> sources) {
        Map<String, String> copy = Map.copyOf(sources);
        return copy::get;
    }
}
