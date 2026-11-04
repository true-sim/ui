package com.truesim.ui.controls.files;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * The real file system through {@code java.nio.file} (the default one, or any {@link FileSystem}: a zip file's).
 * Roots are the file system's root directories (drives on Windows); the home folder is {@code user.home}, or the
 * first root. Watches use the file system's {@link WatchService}, polled without blocking.
 */
@AllowAllocation("file system calls run when a player navigates, not per frame")
public final class NioFileModel implements FileModel {

    private final FileSystem fs;
    private final String home;
    private final FilePlaces places = new FilePlaces();

    /** The default file system, home at {@code user.home}. */
    public NioFileModel() {
        this(FileSystems.getDefault(), System.getProperty("user.home"));
    }

    /** {@code fs}, home at {@code home} (null: the first root). */
    public NioFileModel(FileSystem fs, String home) {
        this.fs = fs;
        this.home = home != null ? home : fs.getRootDirectories().iterator().next().toString();
    }

    private Path path(String p) {
        return fs.getPath(p);
    }

    private static FileEntry entry(Path p, BasicFileAttributes a) {
        Path name = p.getFileName();
        boolean hidden;
        try {
            hidden = Files.isHidden(p);
        } catch (IOException e) {
            hidden = false;
        }
        return new FileEntry(p.toString(), name == null ? p.toString() : name.toString(), a.isDirectory(),
            a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis(), hidden);
    }

    @Override
    public List<FileEntry> list(String directory) throws IOException {
        List<FileEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path(directory))) {
            for (Path p : stream) {
                try {
                    out.add(entry(p, Files.readAttributes(p, BasicFileAttributes.class)));
                } catch (IOException e) {
                    // gone or unreadable: not listed
                }
            }
        } catch (InvalidPathException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    @Override
    public FileEntry stat(String p) {
        try {
            Path path = path(p);
            return Files.exists(path) ? entry(path, Files.readAttributes(path, BasicFileAttributes.class)) : null;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    @Override
    public List<FileEntry> roots() {
        List<FileEntry> out = new ArrayList<>();
        for (Path r : fs.getRootDirectories()) {
            out.add(new FileEntry(r.toString(), r.toString(), true, 0, 0, false));
        }
        return out;
    }

    @Override
    public String home() {
        return home;
    }

    @Override
    public String parent(String p) {
        Path parent = path(p).getParent();
        return parent == null ? null : parent.toString();
    }

    @Override
    public String child(String directory, String name) {
        return path(directory).resolve(name).toString();
    }

    @Override
    public String resolve(String base, String p) {
        try {
            Path given = path(p);
            Path full = given.isAbsolute() ? given : path(base).resolve(given);
            return full.normalize().toString();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Override
    public List<String> steps(String p) {
        Path path = path(p);
        List<String> out = new ArrayList<>();
        if (path.getRoot() != null) {
            out.add(path.getRoot().toString());
        }
        for (Path name : path) {
            out.add(name.toString());
        }
        return out;
    }

    @Override
    public void createDirectory(String p) throws IOException {
        Files.createDirectory(path(p));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        Files.move(path(from), path(to));
    }

    @Override
    public FilePlaces places() {
        return places;
    }

    @Override
    public Watch watch(String directory) {
        try {
            WatchService service = fs.newWatchService();
            path(directory).register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
            return new NioWatch(service);
        } catch (IOException | UnsupportedOperationException | InvalidPathException e) {
            return null;
        }
    }

    /** A watch polling a watch service. */
    private record NioWatch(WatchService service) implements Watch {

        @Override
        public boolean changed() {
            WatchKey key = service.poll();
            if (key == null) {
                return false;
            }
            key.pollEvents();
            key.reset();
            return true;
        }

        @Override
        public void close() {
            try {
                service.close();
            } catch (IOException e) {
                // closed anyway
            }
        }
    }
}
