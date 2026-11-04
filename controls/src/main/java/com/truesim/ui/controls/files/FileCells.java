package com.truesim.ui.controls.files;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.data.CellState;
import com.truesim.ui.controls.data.Format;
import com.truesim.ui.controls.data.ValueRenderer;
import com.truesim.ui.controls.data.ViewContext;
import com.truesim.ui.core.Element;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;

/**
 * The file browser's cells, allocation-free when bound: a name with a folder or file icon ({@code file-name}: an
 * {@code image.icon#folder} or {@code #file} and a label), a size ({@code 1.5 KB}), a modification time
 * ({@code 2026-11-04 12:30} in the time zone's offset when the browser was made) and a type ({@code Folder},
 * {@code PNG file}); and their orders.
 */
final class FileCells {

    private FileCells() {
    }

    static final Comparator<FileEntry> FOLDERS_FIRST = (a, b) -> Boolean.compare(b.directory(), a.directory());
    static final Comparator<FileEntry> BY_NAME = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
    static final Comparator<FileEntry> BY_SIZE = Comparator.comparingLong(FileEntry::size);
    static final Comparator<FileEntry> BY_MODIFIED = Comparator.comparingLong(FileEntry::modified);
    static final Comparator<FileEntry> BY_TYPE = (a, b) -> compareExtensions(a.name(), b.name());

    /** Compares the extensions of two names, ignoring case, without making strings. */
    static int compareExtensions(String a, String b) {
        int da = a.lastIndexOf('.');
        int db = b.lastIndexOf('.');
        int sa = da <= 0 ? a.length() : da + 1;
        int sb = db <= 0 ? b.length() : db + 1;
        int la = a.length() - sa;
        int lb = b.length() - sb;
        for (int i = 0; i < Math.min(la, lb); i++) {
            int c = Character.compare(Character.toUpperCase(a.charAt(sa + i)), Character.toUpperCase(b.charAt(sb + i)));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(la, lb);
    }

    /** A name with its icon: kind 0 a file, 1 a folder. */
    static final class Name implements ValueRenderer<FileEntry> {

        @Override
        public int kind(FileEntry value) {
            return value != null && value.directory() ? 1 : 0;
        }

        @Override
        @AllowAllocation("views are made while the visible window fills")
        public Element create(int kind, ViewContext context) {
            Ui ui = context.ui();
            Element view = ui.element("file-name", null);
            Element icon = ui.image("icon");
            icon.id(kind == 1 ? "folder" : "file");
            view.add(icon).add(ui.label(null, "").id("text"));
            return view;
        }

        @Override
        public void bind(Element view, FileEntry value, CellState state) {
            Ui ui = state.ui();
            Element label = view.store().element(view.store().lastChild(view.slot()));
            ui.text(label, value == null ? "" : value.name());
            state.name(ui.text(label));
        }
    }

    /** A text cell written from a scratch buffer. */
    abstract static class Text implements ValueRenderer<FileEntry> {

        final StringBuilder scratch = new StringBuilder();
        private final String id;

        Text(String id) {
            this.id = id;
        }

        @Override
        public Object viewType() {
            return id;
        }

        @Override
        @AllowAllocation("views are made while the visible window fills")
        public Element create(int kind, ViewContext context) {
            return context.ui().label(null, "").id(id);
        }

        @Override
        public void bind(Element view, FileEntry value, CellState state) {
            scratch.setLength(0);
            if (value != null) {
                write(value);
            }
            Ui ui = state.ui();
            ui.text(view, scratch);
            state.name(ui.text(view));
        }

        abstract void write(FileEntry value);
    }

    /** Sizes in bytes, KB, MB, GB (1024 based), folders blank. */
    static final class Size extends Text {

        private static final String[] UNITS = {" bytes", " KB", " MB", " GB", " TB"};

        Size() {
            super("number");
        }

        @Override
        void write(FileEntry value) {
            if (value.directory()) {
                return;
            }
            double v = value.size();
            int u = 0;
            while (v >= 1024 && u < UNITS.length - 1) {
                v /= 1024;
                u++;
            }
            Format.append(scratch, v, u == 0 ? 0 : 1).append(UNITS[u]);
        }
    }

    /** Times as {@code yyyy-mm-dd hh:mm} at a fixed offset. */
    static final class Modified extends Text {

        private final long offsetMillis;

        @AllowAllocation("made once per browser")
        Modified() {
            super("date");
            offsetMillis = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds() * 1000L;
        }

        @Override
        void write(FileEntry value) {
            if (value.modified() == 0) {
                return;
            }
            date(scratch, value.modified() + offsetMillis);
        }
    }

    /** Appends {@code millis} (epoch, already offset) as {@code yyyy-mm-dd hh:mm}: Hinnant's civil from days. */
    static StringBuilder date(StringBuilder out, long millis) {
        long days = Math.floorDiv(millis, 86_400_000L);
        long ms = Math.floorMod(millis, 86_400_000L);
        long z = days + 719_468;
        long era = Math.floorDiv(z, 146_097);
        long doe = z - era * 146_097;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) {
            y++;
        }
        out.append(y).append('-');
        two(out, m).append('-');
        two(two(out, d).append(' '), ms / 3_600_000).append(':');
        return two(out, ms / 60_000 % 60);
    }

    private static StringBuilder two(StringBuilder out, long v) {
        return out.append((char) ('0' + v / 10)).append((char) ('0' + v % 10));
    }

    /** {@code Folder}, or the extension in capitals and {@code file}. */
    static final class Type extends Text {

        private final String folder;
        private final String file;

        Type(String folder, String file) {
            super("text");
            this.folder = folder;
            this.file = file;
        }

        @Override
        void write(FileEntry value) {
            if (value.directory()) {
                scratch.append(folder);
                return;
            }
            String name = value.name();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                for (int i = dot + 1; i < name.length(); i++) {
                    scratch.append(Character.toUpperCase(name.charAt(i)));
                }
                scratch.append(' ');
            }
            scratch.append(file);
        }
    }

    /** A place of the side list: an icon by its kind and its name. */
    static final class PlaceCell implements ValueRenderer<FileBrowser.Place> {

        private static final String[] ICONS = {"home", "drive", "favourite", "recent"};

        @Override
        public int kind(FileBrowser.Place value) {
            return value == null ? 0 : value.kind().ordinal();
        }

        @Override
        @AllowAllocation("views are made while the visible window fills")
        public Element create(int kind, ViewContext context) {
            Ui ui = context.ui();
            Element view = ui.element("file-place", null);
            Element icon = ui.image("icon");
            icon.id(ICONS[Math.min(kind, ICONS.length - 1)]);
            view.add(icon).add(ui.label(null, "").id("text"));
            return view;
        }

        @Override
        public void bind(Element view, FileBrowser.Place value, CellState state) {
            Ui ui = state.ui();
            Element label = view.store().element(view.store().lastChild(view.slot()));
            ui.text(label, value == null ? "" : value.name());
            state.name(ui.text(label));
        }
    }
}
