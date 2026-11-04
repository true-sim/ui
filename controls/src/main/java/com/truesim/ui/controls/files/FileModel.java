package com.truesim.ui.controls.files;

import java.io.IOException;
import java.util.List;

/**
 * An engine-agnostic file system for the file browser (design 13, plan additions): what a folder holds, what a path
 * is, the roots (drives), the home folder, the places a player keeps ({@link #places()}: favourites and recent
 * folders) and, optionally, a watch telling when a folder changed. Paths are strings in the model's own syntax; the
 * model joins, splits and normalizes them. {@link NioFileModel} is the real file system ({@code java.nio}),
 * {@link MemoryFileModel} one in memory for tests and galleries; a game may offer its virtual file system or a
 * remote one the same way.
 *
 * <p>Calls run on the UI thread when a player navigates (never per frame), so a listing should be quick; a slow
 * file system lists in the background and offers its watch.
 */
public interface FileModel {

    /** What folder {@code directory} holds (not recursive), in any order. */
    List<FileEntry> list(String directory) throws IOException;

    /** The entry at {@code path}, or null when nothing is there. */
    FileEntry stat(String path);

    /** The roots: drives, volumes, the file system's root. */
    List<FileEntry> roots();

    /** The player's home folder. */
    String home();

    /** The folder holding {@code path}, or null at a root. */
    String parent(String path);

    /** The path of {@code name} in folder {@code directory}. */
    String child(String directory, String name);

    /** {@code path} made absolute and normal ({@code ..} and {@code .} resolved, against {@code base} if relative). */
    String resolve(String base, String path);

    /** The steps of {@code path} from its root: the root's path, then each name. */
    List<String> steps(String path);

    /** Makes the folder {@code path}. */
    void createDirectory(String path) throws IOException;

    /** Renames or moves {@code from} to {@code to}. */
    void rename(String from, String to) throws IOException;

    /** The favourite and recent places of this model. */
    FilePlaces places();

    /** A watch over folder {@code directory}, or null when the model cannot watch: the browser then lists on demand. */
    default Watch watch(String directory) {
        return null;
    }

    /** Tells when a watched folder changed; polled once a frame by the browser showing it. */
    interface Watch extends AutoCloseable {

        /** Whether the folder changed since the last call (consumes the change); must not block. */
        boolean changed();

        /** Stops watching. */
        @Override
        void close();
    }
}
