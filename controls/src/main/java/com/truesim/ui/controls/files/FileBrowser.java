package com.truesim.ui.controls.files;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedList;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Breadcrumbs;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.DropDown;
import com.truesim.ui.controls.basic.SegmentedControl;
import com.truesim.ui.controls.data.Column;
import com.truesim.ui.controls.data.DataView;
import com.truesim.ui.controls.data.ListView;
import com.truesim.ui.controls.data.RowSource;
import com.truesim.ui.controls.data.Selection;
import com.truesim.ui.controls.data.TableView;
import com.truesim.ui.controls.text.TextCellEditor;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A file browser (the plan additions of design 13): a folder of a {@link FileModel} shown as a details table (name,
 * size, modified, type; sorted by clicking a header, folders first) or a list, with a bar (back, forward, up, a path of
 * {@link Breadcrumbs} that an edit button turns into a path field, the view choice, new folder), a side list of places
 * (home, roots, favourites, recent), a name field and a filter list. Built from the toolkit's own controls:
 * {@link TableView}, {@link ListView}, {@link Breadcrumbs}, {@link TextEditor}, {@link DropDown},
 * {@link SegmentedControl}, {@link Button}; {@link FileDialog} puts it in a {@code Dialog}.
 *
 * <ul>
 *   <li><b>Modes.</b> {@link Mode#OPEN} chooses existing files (one, or several with {@link #multiple}), {@link Mode#SAVE}
 *       names a file (typed, or a file chosen), {@link Mode#FOLDERS} chooses folders (only folders show).</li>
 *   <li><b>Keys and gamepad.</b> Everything is reachable by focus navigation; in the views the arrows move, typing jumps
 *       to a name (type-ahead), ACCEPT or a double click opens a folder or accepts a file, Backspace goes up. In the
 *       name field Enter accepts; in the path field Enter goes there (or accepts the file it names).</li>
 *   <li><b>Changes.</b> The folder is listed when entered; a model's {@linkplain FileModel#watch watch} lists it again
 *       when it changes (polled once a frame).</li>
 * </ul>
 *
 * <p><b>Elements and styles.</b> {@code file-browser} holds {@code file-bar} (buttons, a {@code file-path} holding the
 * breadcrumbs or a {@code textfield.file-path-field}), {@code file-main} (a {@code list-view.file-places} and the
 * {@code table-view.file-table} or {@code list-view.file-list}), {@code file-footer} (a label, a
 * {@code textfield.file-name-field}, the filter drop-down) and a {@code label.file-error} shown when a folder cannot be
 * read. Cells: {@code file-name} (an {@code image.icon#folder} or {@code #file} and a label), {@code file-place}.
 * Icons by id: {@code back}, {@code forward}, {@code up}, {@code edit}, {@code new-folder}, {@code home},
 * {@code drive}, {@code favourite}, {@code recent}.
 */
public final class FileBrowser implements Ui.FrameListener, UiListener {

    /** What the browser chooses. */
    public enum Mode {
        /** Existing files. */
        OPEN,
        /** A file name to write. */
        SAVE,
        /** Folders. */
        FOLDERS
    }

    /** What a place stands for. */
    public enum PlaceKind {
        HOME, ROOT, FAVOURITE, RECENT
    }

    /** A place of the side list. */
    public record Place(String name, String path, PlaceKind kind) {
    }

    /** Told that the player accepted (a file activated, Enter in the name field). */
    @FunctionalInterface
    public interface Accepted {
        void accepted();
    }

    private final Controls controls;
    private final Ui ui;
    private final FileModel model;
    private final Element root;
    private final Element bar;
    private final Element pathBox;
    private final Element main;
    private final Element views;
    private final Element footer;
    private final Element error;
    private final Breadcrumbs crumbs;
    private final TextEditor pathField;
    private final TextEditor nameField;
    private final Button back;
    private final Button forward;
    private final Button up;
    private final Button editPath;
    private final Button newFolder;
    private final SegmentedControl viewChoice;
    private final VersionedInt viewModel;
    private final VersionedInt filterModel;
    private DropDown<FileFilter> filterList;
    private final VersionedList<FileEntry> entries;
    private final DataView<FileEntry> data;
    private final TableView<FileEntry> table;
    private final ListView<FileEntry> list;
    private final VersionedList<Place> placeItems;
    private final ListView<Place> places;
    private final Column<FileEntry> nameColumn;

    private Mode mode = Mode.OPEN;
    private boolean multiple;
    private boolean showHidden;
    private List<FileFilter> filters = List.of();
    private String current;
    private final List<String> history = new ArrayList<>();
    private final List<String> future = new ArrayList<>();
    private FileModel.Watch watch;
    private int seenPlaces = -1;
    private int seenView = -1;
    private int seenFilter = -1;
    private long seenSelection = -1;
    private boolean reload;
    private boolean editingPath;
    private String renaming;
    private Accepted accepted;
    private final Predicate<FileEntry> shows = this::shows;

    /** A browser of {@code model} in {@code controls}' UI, opening files, at the model's home. */
    @AllowAllocation("controls are built once")
    public FileBrowser(Controls controls, FileModel model) {
        this.controls = controls;
        this.ui = controls.ui();
        this.model = model;
        ChangeClock clock = controls.clock();
        root = ui.element("file-browser", null);
        root.semantics(Role.GROUP, "Files");
        bar = ui.element("file-bar", null);
        back = controls.iconButton("back", "Back");
        forward = controls.iconButton("forward", "Forward");
        up = controls.iconButton("up", "Up");
        pathBox = ui.element("file-path", null);
        crumbs = controls.breadcrumbs("Path");
        pathField = TextEditor.field(ui, "file-path-field", "Path");
        editPath = controls.iconButton("edit", "Edit path");
        viewModel = clock.intValue(1);
        viewChoice = controls.segmented("View", viewModel);
        viewChoice.add("List");
        viewChoice.add("Details");
        newFolder = controls.iconButton("new-folder", "New folder");
        pathBox.add(crumbs.element()).add(pathField.element());
        bar.add(back.element()).add(forward.element()).add(up.element()).add(pathBox).add(editPath.element())
            .add(viewChoice.element()).add(newFolder.element());

        entries = clock.list();
        data = new DataView<>(clock, entries).filter(shows);
        table = new TableView<>(ui, "Files", data, TableView.SelectionMode.ROW);
        table.root().style("file-table");
        nameColumn = Column.ofRow("Name", new FileCells.Name(), FileCells.BY_NAME).width(220);
        nameColumn.editorInPlace(TextCellEditor.create("Name"), FileEntry::name, this::rename);
        table.add(nameColumn);
        table.add(Column.ofRow("Size", new FileCells.Size(), FileCells.BY_SIZE).width(90));
        table.add(Column.ofRow("Modified", new FileCells.Modified(), FileCells.BY_MODIFIED).width(140));
        table.add(Column.ofRow("Type", new FileCells.Type("Folder", "file"), FileCells.BY_TYPE).width(110));
        table.baseOrder(FileCells.FOLDERS_FIRST);
        table.sort(nameColumn, true, false);
        table.typeAhead(FileEntry::name);
        table.onActivate(this::activate);
        list = new ListView<>(ui, "Files", data, new FileCells.Name());
        list.root().style("file-list");
        list.textOf(FileEntry::name);
        list.onActivate(this::activate);
        views = ui.element("file-views", null);
        views.add(table.root()).add(list.root());

        placeItems = clock.list();
        places = new ListView<>(ui, "Places", RowSource.of(placeItems), new FileCells.PlaceCell());
        places.root().style("file-places");
        places.onActivate(i -> navigate(placeItems.get(i).path()));
        main = ui.element("file-main", null);
        main.add(places.root()).add(views);

        footer = ui.element("file-footer", null);
        nameField = TextEditor.field(ui, "file-name-field", "File name");
        filterModel = clock.intValue(0);
        footer.add(ui.label("file-name-label", "Name")).add(nameField.element());
        error = ui.label("file-error", "");
        root.add(bar).add(main).add(error).add(footer);

        back.onClick(this::back);
        forward.onClick(this::forward);
        up.onClick(this::up);
        editPath.onClick(() -> editPath(!editingPath));
        newFolder.onClick(this::newFolder);
        crumbs.onChosen(this::crumb);
        pathField.listener(new TextEditor.Listener() {
            @Override
            public void submitted(TextEditor editor) {
                goToTyped();
            }
        });
        nameField.listener(new TextEditor.Listener() {
            @Override
            public void submitted(TextEditor editor) {
                if (accepted != null) {
                    accepted.accepted();
                }
            }
        });
        ui.interaction().on(root, EventType.COMMAND.bit(), this);
        ui.addFrameListener(this);
        editPath(false);
        showView(1);
        ui.layout().visible(error.slot(), false);
        navigate(model.home());
    }

    // ---------------------------------------------------------------- settings

    /** The root element: add it where the browser goes. */
    public Element element() {
        return root;
    }

    /** The model. */
    public FileModel model() {
        return model;
    }

    /** What it chooses; returns this. */
    public FileBrowser mode(Mode value) {
        mode = value;
        ui.layout().visible(newFolder.element().slot(), true);
        data.invalidate();
        selectionMode();
        return this;
    }

    /** What it chooses. */
    public Mode mode() {
        return mode;
    }

    /** Whether several files can be chosen (open mode); returns this. */
    public FileBrowser multiple(boolean on) {
        multiple = on;
        selectionMode();
        return this;
    }

    private void selectionMode() {
        Selection.Mode m = multiple && mode == Mode.OPEN ? Selection.Mode.MULTIPLE : Selection.Mode.SINGLE;
        table.selection().mode(m);
        list.selection().mode(m);
    }

    /** Whether hidden files show; returns this. */
    public FileBrowser showHidden(boolean on) {
        showHidden = on;
        data.invalidate();
        return this;
    }

    /** The filter choices (the first chosen; empty: every file, no list); returns this. */
    @AllowAllocation("the browser is configured")
    public FileBrowser filters(List<FileFilter> choices) {
        filters = List.copyOf(choices);
        if (filterList != null) {
            ui.destroy(filterList.element());
            filterList = null;
        }
        if (!filters.isEmpty()) {
            filterModel.set(0);
            filterList = new DropDown<>(controls, "File type", filters, FileFilter::name, filterModel);
            footer.add(filterList.element());
        }
        seenFilter = -1;
        data.invalidate();
        return this;
    }

    /** The filter chosen, or null. */
    public FileFilter filter() {
        return filters.isEmpty() ? null : filters.get(Math.max(0, Math.min(filters.size() - 1, filterModel.get())));
    }

    /** Runs {@code listener} when the player accepts; returns this. */
    public FileBrowser onAccept(Accepted listener) {
        accepted = listener;
        return this;
    }

    // ---------------------------------------------------------------- parts, for tests and dialogs

    /** The details table. */
    public TableView<FileEntry> table() {
        return table;
    }

    /** The list view. */
    public ListView<FileEntry> list() {
        return list;
    }

    /** The places list. */
    public ListView<Place> places() {
        return places;
    }

    /** The breadcrumbs. */
    public Breadcrumbs breadcrumbs() {
        return crumbs;
    }

    /** The path field (shown while editing the path). */
    public TextEditor pathField() {
        return pathField;
    }

    /** The name field. */
    public TextEditor nameField() {
        return nameField;
    }

    /** The filter list, or null without filters. */
    public DropDown<FileFilter> filterList() {
        return filterList;
    }

    /** The view choice (0: list, 1: details). */
    public VersionedInt view() {
        return viewModel;
    }

    /** The folder shown. */
    public String directory() {
        return current;
    }

    /** The error shown (a folder could not be read), or empty. */
    public CharSequence error() {
        return ui.text(error);
    }

    /** The rows shown (sorted, filtered). */
    public DataView<FileEntry> rows() {
        return data;
    }

    /** The selection of the view shown. */
    public Selection<FileEntry> selection() {
        return viewModel.get() == 0 ? list.selection() : table.selection();
    }

    // ---------------------------------------------------------------- navigation

    /** Shows folder {@code directory}, remembered for back; returns whether it could be read. */
    @AllowAllocation("navigation")
    public boolean navigate(String directory) {
        String before = current;
        if (!load(directory)) {
            return false;
        }
        if (before != null && !before.equals(current)) {
            history.add(before);
            future.clear();
        }
        buttons();
        return true;
    }

    /** Goes back to the folder shown before. */
    @AllowAllocation("navigation")
    public void back() {
        if (history.isEmpty()) {
            return;
        }
        String to = history.remove(history.size() - 1);
        String from = current;
        if (load(to)) {
            future.add(from);
        }
        buttons();
    }

    /** Goes forward again after {@link #back}. */
    @AllowAllocation("navigation")
    public void forward() {
        if (future.isEmpty()) {
            return;
        }
        String to = future.remove(future.size() - 1);
        String from = current;
        if (load(to)) {
            history.add(from);
        }
        buttons();
    }

    /** Goes to the folder holding the one shown. */
    @AllowAllocation("navigation")
    public void up() {
        String parent = current == null ? null : model.parent(current);
        if (parent != null) {
            String from = current;
            if (navigate(parent)) {
                select(from); // where we came from
            }
        }
    }

    private void buttons() {
        back.enabled(!history.isEmpty());
        forward.enabled(!future.isEmpty());
        up.enabled(current != null && model.parent(current) != null);
    }

    /** Lists {@code directory} into the views; on failure shows why and keeps the folder shown. */
    @AllowAllocation("navigation")
    private boolean load(String directory) {
        List<FileEntry> listed;
        try {
            listed = model.list(directory);
        } catch (IOException e) {
            ui.text(error, e.getMessage() == null ? "Cannot read " + directory : e.getMessage());
            ui.layout().visible(error.slot(), true);
            return false;
        }
        ui.layout().visible(error.slot(), false);
        ui.text(error, "");
        boolean same = directory.equals(current);
        current = directory;
        entries.clear();
        entries.addAll(listed);
        if (!same) {
            table.selection().clear();
            list.selection().clear();
            table.scrollTo(0, 0);
            list.scrollTo(0, 0);
            if (watch != null) {
                watch.close();
            }
            watch = model.watch(directory);
            List<String> steps = model.steps(directory);
            crumbs.path(steps);
            pathField.text(directory);
        }
        return true;
    }

    private void crumb(int index) {
        List<String> steps = model.steps(current);
        String path = steps.get(0);
        for (int i = 1; i <= index && i < steps.size(); i++) {
            path = model.child(path, steps.get(i));
        }
        navigate(path);
    }

    /** Shows the path field instead of the breadcrumbs ({@code on}) or the breadcrumbs again. */
    public void editPath(boolean on) {
        editingPath = on;
        ui.layout().visible(crumbs.element().slot(), !on);
        ui.layout().visible(pathField.element().slot(), on);
        if (on) {
            pathField.text(current == null ? "" : current);
            controls.focusSoon(pathField.element(), 0); // once it is laid out
        }
    }

    /** Whether the path field shows. */
    public boolean editingPath() {
        return editingPath;
    }

    /** Goes where the path field says: a folder opens; in open mode a file is accepted. */
    @AllowAllocation("navigation")
    private void goToTyped() {
        String typed = pathField.text().toString().strip();
        if (typed.isEmpty()) {
            return;
        }
        String path;
        try {
            path = model.resolve(current, typed);
        } catch (IllegalArgumentException e) {
            showError("Not a path: " + typed);
            return;
        }
        FileEntry e = model.stat(path);
        if (e == null) {
            showError("No such folder: " + typed);
        } else if (e.directory()) {
            if (navigate(path)) {
                editPath(false);
            }
        } else if (navigate(model.parent(path))) {
            editPath(false);
            select(path);
            nameField.text(e.name());
            if (accepted != null && mode != Mode.FOLDERS) {
                accepted.accepted();
            }
        }
    }

    private void showError(String message) {
        ui.text(error, message);
        ui.layout().visible(error.slot(), true);
    }

    /** Selects the entry at {@code path} in the views and reveals it. */
    @AllowAllocation("navigation")
    public void select(String path) {
        data.refresh(); // the listing may be newer than the view
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).path().equals(path)) {
                table.selection().select(i, data.get(i));
                list.selection().select(i, data.get(i));
                table.current(i);
                list.current(i);
                return;
            }
        }
    }

    /** A row was activated (double click, ACCEPT): a folder opens, a file is accepted. */
    @AllowAllocation("navigation")
    private void activate(int index) {
        FileEntry e = data.get(index);
        if (e.directory()) {
            navigate(e.path());
        } else if (accepted != null) {
            nameField.text(e.name());
            accepted.accepted();
        }
    }

    /** Makes a new folder ("New folder", numbered when taken), selects it and starts renaming it (details). */
    @AllowAllocation("a user action")
    public String newFolder() {
        table.cancel();
        String name = "New folder";
        for (int n = 2; model.stat(model.child(current, name)) != null; n++) {
            name = "New folder " + n;
        }
        String path = model.child(current, name);
        try {
            model.createDirectory(path);
        } catch (IOException e) {
            showError(e.getMessage() == null ? "Cannot make " + name : e.getMessage());
            return null;
        }
        load(current);
        data.refresh();
        select(path);
        if (viewModel.get() == 1) {
            renaming = path; // edited once its row is bound, after this frame's layout
        }
        return path;
    }

    /** Renames {@code entry} to {@code name} (the name column's editor). */
    @AllowAllocation("a user action")
    private void rename(FileEntry entry, String name) {
        String to = model.child(current, name.strip());
        if (name.isBlank() || to.equals(entry.path())) {
            return;
        }
        try {
            model.rename(entry.path(), to);
        } catch (IOException e) {
            showError(e.getMessage() == null ? "Cannot rename " + entry.name() : e.getMessage());
        }
        reload = true; // after the table wrote its row: the listing is read again next frame
    }

    // ---------------------------------------------------------------- choosing

    private boolean shows(FileEntry e) {
        if (e.hidden() && !showHidden) {
            return false;
        }
        if (mode == Mode.FOLDERS) {
            return e.directory();
        }
        FileFilter f = filter();
        return f == null || f.accepts(e);
    }

    /**
     * What the player chose, as full paths: the selected files (open), a name typed (open: an existing file; save: in
     * the folder shown, with the filter's extension when it has none), the selected or shown folder (folders). Null
     * when nothing is chosen yet: a folder typed in the name field is opened instead, a missing file is shown as an
     * error.
     */
    @AllowAllocation("a user action")
    public List<String> chosen() {
        String typed = nameField.text().toString().strip();
        List<String> out = new ArrayList<>();
        Selection<FileEntry> sel = selection();
        switch (mode) {
            case OPEN -> {
                List<String> names = names(typed);
                boolean fromSelection = !sel.items().isEmpty() && names.equals(selectedNames(sel));
                if (fromSelection) {
                    for (FileEntry e : sel.items()) {
                        if (!e.directory()) {
                            out.add(e.path());
                        }
                    }
                    out.sort(String::compareTo);
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
                for (String n : names) {
                    String path = resolve(n);
                    FileEntry e = path == null ? null : model.stat(path);
                    if (e == null) {
                        showError("No such file: " + n);
                        return null;
                    }
                    if (e.directory()) {
                        navigate(path);
                        nameField.text("");
                        return null;
                    }
                    out.add(path);
                }
            }
            case SAVE -> {
                if (typed.isEmpty()) {
                    return null;
                }
                String path = resolve(typed);
                if (path == null) {
                    showError("Not a file name: " + typed);
                    return null;
                }
                FileEntry e = model.stat(path);
                if (e != null && e.directory()) {
                    navigate(path);
                    nameField.text("");
                    return null;
                }
                FileFilter f = filter();
                if (e == null && f != null && !f.defaultExtension().isEmpty() && typed.indexOf('.') < 0) {
                    path = path + "." + f.defaultExtension();
                }
                out.add(path);
            }
            case FOLDERS -> {
                if (!typed.isEmpty()) {
                    String path = resolve(typed);
                    FileEntry e = path == null ? null : model.stat(path);
                    if (e == null || !e.directory()) {
                        showError("No such folder: " + typed);
                        return null;
                    }
                    out.add(path);
                } else if (!sel.items().isEmpty()) {
                    out.add(sel.items().iterator().next().path());
                } else {
                    out.add(current);
                }
            }
            default -> {
            }
        }
        return out.isEmpty() ? null : out;
    }

    private String resolve(String name) {
        try {
            return model.resolve(current, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The names in the name field: one, or several in quotes ({@code "a.png" "b.png"}). */
    @AllowAllocation("a user action")
    static List<String> names(String typed) {
        List<String> out = new ArrayList<>();
        if (typed.indexOf('"') < 0) {
            if (!typed.isEmpty()) {
                out.add(typed);
            }
            return out;
        }
        int i = 0;
        while (i < typed.length()) {
            int a = typed.indexOf('"', i);
            if (a < 0) {
                break;
            }
            int b = typed.indexOf('"', a + 1);
            if (b < 0) {
                b = typed.length();
            }
            String n = typed.substring(a + 1, b).strip();
            if (!n.isEmpty()) {
                out.add(n);
            }
            i = b + 1;
        }
        return out;
    }

    @AllowAllocation("a user action")
    private List<String> selectedNames(Selection<FileEntry> sel) {
        List<String> out = new ArrayList<>();
        for (FileEntry e : sel.items()) {
            if (!e.directory() || mode == Mode.FOLDERS) {
                out.add(e.name());
            }
        }
        out.sort(String::compareTo);
        List<String> typed = names(nameField.text().toString().strip());
        List<String> sortedTyped = new ArrayList<>(typed);
        sortedTyped.sort(String::compareTo);
        return sortedTyped.equals(out) ? typed : out;
    }

    /** Puts the selected names into the name field (a selection changed). */
    @AllowAllocation("the selection changed: a user action")
    private void selectionToName() {
        Selection<FileEntry> sel = selection();
        List<String> names = new ArrayList<>();
        for (FileEntry e : sel.items()) {
            if (mode == Mode.FOLDERS ? e.directory() : !e.directory()) {
                names.add(e.name());
            }
        }
        if (names.isEmpty()) {
            return; // a folder selected: the name typed stays
        }
        names.sort(String::compareTo);
        if (names.size() == 1) {
            nameField.text(names.get(0));
        } else {
            StringBuilder b = new StringBuilder();
            for (String n : names) {
                if (!b.isEmpty()) {
                    b.append(' ');
                }
                b.append('"').append(n).append('"');
            }
            nameField.text(b);
        }
    }

    // ---------------------------------------------------------------- the frame

    private void showView(int which) {
        ui.layout().visible(list.root().slot(), which == 0);
        ui.layout().visible(table.root().slot(), which != 0);
    }

    @Override
    public void afterLayout() {
        if (renaming != null) {
            int i = table.current();
            boolean bound = i >= 0 && i < data.size() && table.cell(i, nameColumn) != null;
            if (bound && data.get(i).path().equals(renaming)) {
                renaming = null;
                table.edit(i, nameColumn);
            }
        }
    }

    @Override
    @AllowAllocation("only when something changed: a user action or the folder")
    public void frame(long nanos) {
        if (reload || watch != null && watch.changed()) {
            reload = false;
            load(current);
        }
        int view = viewModel.get();
        if (view != seenView) {
            Selection<FileEntry> from = view == 0 ? table.selection() : list.selection();
            Selection<FileEntry> to = view == 0 ? list.selection() : table.selection();
            if (seenView >= 0) {
                to.clear();
                for (FileEntry e : from.items()) {
                    to.items().add(e);
                }
            }
            seenView = view;
            showView(view);
            seenSelection = -1;
        }
        if (filterList != null && filterModel.get() != seenFilter) {
            seenFilter = filterModel.get();
            data.invalidate();
        }
        long sv = selection().version();
        if (sv != seenSelection) {
            seenSelection = sv;
            selectionToName();
        }
        FilePlaces p = model.places();
        if (p.version() != seenPlaces) {
            seenPlaces = p.version();
            fillPlaces();
        }
    }

    @AllowAllocation("the places changed")
    private void fillPlaces() {
        placeItems.clear();
        String home = model.home();
        List<String> homeSteps = model.steps(home);
        placeItems.add(new Place(homeSteps.get(homeSteps.size() - 1), home, PlaceKind.HOME));
        for (FileEntry r : model.roots()) {
            placeItems.add(new Place(r.name(), r.path(), PlaceKind.ROOT));
        }
        FilePlaces p = model.places();
        for (String f : p.favourites()) {
            List<String> s = model.steps(f);
            placeItems.add(new Place(s.get(s.size() - 1), f, PlaceKind.FAVOURITE));
        }
        for (String r : p.recent()) {
            List<String> s = model.steps(r);
            placeItems.add(new Place(s.get(s.size() - 1), r, PlaceKind.RECENT));
        }
    }

    /** Backspace goes up; CANCEL in the path field shows the breadcrumbs again. */
    @Override
    public void handle(UiEvent e) {
        if (e.type() != EventType.COMMAND || e.consumed()) {
            return;
        }
        int target = e.targetSlot();
        boolean inText = within(target, pathField.element().slot()) || within(target, nameField.element().slot());
        if (e.command() == UiCommand.CANCEL && editingPath && within(target, pathField.element().slot())) {
            editPath(false);
            e.consume();
        } else if (e.command() == UiCommand.DELETE_BACK && !inText) {
            up();
            e.consume();
        }
    }

    private boolean within(int slot, int ancestor) {
        for (int s = slot; s != com.truesim.ui.core.UiStore.NONE; s = ui.store().parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Stops watching the folder. */
    public void close() {
        if (watch != null) {
            watch.close();
            watch = null;
        }
    }
}
