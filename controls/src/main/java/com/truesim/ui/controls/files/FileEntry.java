package com.truesim.ui.controls.files;

import java.util.Objects;

/**
 * A file or folder as a {@link FileModel} lists it: its full path in the model's syntax, its name, whether it is a
 * folder, its size in bytes (folders: 0), when it was last modified (milliseconds since the epoch, UTC; 0 unknown)
 * and whether it is hidden. Equal by path, so selections survive a listing read again.
 *
 * @param path the full path
 * @param name the last step of the path (a root: the path)
 * @param directory whether it is a folder
 * @param size bytes, 0 for folders
 * @param modified last modified, epoch milliseconds
 * @param hidden whether the file system hides it
 */
public record FileEntry(String path, String name, boolean directory, long size, long modified, boolean hidden) {

    public FileEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
    }

    /** The extension without the dot, as written ({@code "png"} of {@code a.b.png}), or empty. */
    public String extension() {
        int dot = name.lastIndexOf('.');
        return directory || dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    /** Whether the name ends with {@code .ext} (ignoring case); folders never. */
    public boolean hasExtension(String ext) {
        int n = name.length();
        int e = ext.length();
        return !directory && n > e + 1 && name.charAt(n - e - 1) == '.' && name.regionMatches(true, n - e, ext, 0, e);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileEntry f && f.path.equals(path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}
