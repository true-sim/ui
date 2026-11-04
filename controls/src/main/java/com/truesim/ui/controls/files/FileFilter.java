package com.truesim.ui.controls.files;

import java.util.List;

/**
 * A choice of a file browser's filter list: a name shown to players ({@code "Images (*.png, *.jpg)"}) and the
 * extensions it lets through, without dots, ignoring case; none lets every file through. Folders always show.
 *
 * @param name what the filter list shows
 * @param extensions the extensions shown; empty: all
 */
public record FileFilter(String name, List<String> extensions) {

    /** Every file. */
    public static FileFilter all(String name) {
        return new FileFilter(name, List.of());
    }

    /** Files with one of {@code extensions}. */
    public static FileFilter of(String name, String... extensions) {
        return new FileFilter(name, List.of(extensions));
    }

    public FileFilter {
        extensions = List.copyOf(extensions);
    }

    /** Whether {@code entry} shows under this filter. */
    public boolean accepts(FileEntry entry) {
        if (entry.directory() || extensions.isEmpty()) {
            return true;
        }
        for (int i = 0; i < extensions.size(); i++) {
            if (entry.hasExtension(extensions.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** The extension a saved name without one gets (the first), or empty. */
    public String defaultExtension() {
        return extensions.isEmpty() ? "" : extensions.get(0);
    }
}
