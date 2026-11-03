package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedBoolean;
import com.truesim.common.state.VersionedDouble;
import com.truesim.common.state.VersionedInt;
import com.truesim.common.state.VersionedLong;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.layout.Layout;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The factory and frame of the basic and container controls of one {@link Ui}: makes controls, polls their models
 * once a frame (a {@link Ui.FrameListener}, after input and before styles, so a model change shows in the frame it
 * happened), places pop-ups, and holds what the engine provides: the shortcut glyphs menus show
 * ({@link ShortcutGlyphs}) and the input capture of key-binding fields ({@link InputCapture}).
 *
 * <pre>{@code
 * Controls c = new Controls(ui);
 * Button ok = c.button("OK").onClick(this::save);
 * Checkbox vsync = c.checkbox("V-sync", settings.vsync());   // a VersionedValue<CheckState>, two-way
 * Slider volume = c.slider(audio.volume(), 0, 1).step(0.05);
 * ui.root().add(ok.element()).add(vsync.element()).add(volume.element());
 * }</pre>
 */
public final class Controls implements Ui.FrameListener {

    /** Seconds the pointer rests on a menu item before its submenu opens or another closes (hover intent). */
    public static final Tunable SUBMENU_DELAY = Tunable.of("ui.menu.submenuDelay", 0.25f, 0, 2,
        "Seconds the pointer rests on a menu item before its submenu opens");
    /** Seconds between keys that still extend a drop-down's or menu's type-ahead search. */
    public static final Tunable TYPE_AHEAD_TIMEOUT = Tunable.of("ui.dropdown.typeAheadTimeout", 1, 0.1f, 5,
        "Seconds between typed letters that still extend a list's type-ahead search");
    /** Seconds a notification shows before it goes. */
    public static final Tunable TOAST_DURATION = Tunable.of("ui.toast.duration", 4, 0.5f, 60,
        "Seconds a notification shows before it goes");
    /** How many notifications show at once (the oldest goes first). */
    public static final Tunable TOAST_LIMIT = Tunable.of("ui.toast.limit", 4, 1, 20,
        "How many notifications show at once");
    /** The share of a page a swipe must cover to turn a carousel's page (a fast flick turns it too). */
    public static final Tunable SWIPE_THRESHOLD = Tunable.of("ui.carousel.swipeThreshold", 0.25f, 0.05f, 0.9f,
        "Share of a page a swipe must cover to turn it");
    /** Panel units a split pane's splitter moves per arrow key. */
    public static final Tunable SPLIT_STEP = Tunable.of("ui.split.keyboardStep", 16, 1, 200,
        "Panel units a splitter moves per arrow key");
    /** Panel units a menu, list or tab strip scrolls per wheel notch. */
    public static final Tunable WHEEL_STEP = Tunable.of("ui.controls.wheelStep", 40, 1, 400,
        "Panel units menus, drop-down lists and tab strips scroll per wheel notch");
    /** A slider's steps per page (PAGE_UP, PAGE_DOWN) when it has no step: a tenth of its range. */
    public static final Tunable SLIDER_PAGE = Tunable.of("ui.slider.pageFraction", 0.1f, 0.01f, 1,
        "Share of a slider's range one page key moves");

    private final Ui ui;
    /** Something polled once a frame. */
    interface Polled {
        /** The frame at {@code nanos} on the UI clock (0 without one). */
        void poll(long nanos);
    }

    private Polled[] polled = new Polled[16];
    private int polledCount;
    private ShortcutGlyphs glyphs = ShortcutGlyphs.NONE;
    private InputCapture capture;
    private Element notificationsRoot;
    private Toasts toasts;
    private Element pendingFocus;
    private int pendingPlayer;
    private int pendingFrames;
    private long now;

    /** The controls of {@code ui}: registers its frame listener. */
    @AllowAllocation("one per UI")
    public Controls(Ui ui) {
        this.ui = ui;
        ui.addFrameListener(this);
    }

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    /** The clock models made by the controls are stamped on (the UI store's). */
    public ChangeClock clock() {
        return ui.store().clock();
    }

    /** The tunables of the controls, for an engine to register as settings. */
    public static List<Tunable> tunables() {
        return List.of(SUBMENU_DELAY, TYPE_AHEAD_TIMEOUT, TOAST_DURATION, TOAST_LIMIT, SWIPE_THRESHOLD, SPLIT_STEP,
            WHEEL_STEP, SLIDER_PAGE);
    }

    /** Where menus get the labels of their shortcuts (the engine: the bound glyph of the last device used). */
    public Controls glyphs(ShortcutGlyphs value) {
        glyphs = value == null ? ShortcutGlyphs.NONE : value;
        return this;
    }

    /** The shortcut labels. */
    public ShortcutGlyphs glyphs() {
        return glyphs;
    }

    /** The engine's input capture for key-binding fields (null: they cannot capture). */
    public Controls capture(InputCapture value) {
        capture = value;
        return this;
    }

    /** The input capture, or null. */
    public InputCapture capture() {
        return capture;
    }

    /** The time of the current frame on the UI clock (0 without one). */
    public long now() {
        return now;
    }

    /**
     * A new icon: an image element of style name {@code icon} whose element id is {@code name}, so a theme picks the
     * picture by id ({@code image#save { image: "icons/save"; }}) and the size by the context
     * ({@code button image.icon { size: 16px; }}).
     */
    @AllowAllocation("controls are built once")
    public Element icon(String name) {
        Element e = ui.image("icon");
        e.id(name);
        return e;
    }

    // ---------------------------------------------------------------- factories (each control also has constructors)

    /** A text button. */
    @AllowAllocation("controls are built once")
    public Button button(CharSequence text) {
        return new Button(this, text);
    }

    /** An icon-only button named {@code name}. */
    @AllowAllocation("controls are built once")
    public Button iconButton(String iconName, String name) {
        return new Button(this, iconName, null, name);
    }

    /** A toggle button bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public ToggleButton toggle(CharSequence text, VersionedBoolean model) {
        return new ToggleButton(this, null, text, null, model);
    }

    /** A checkbox bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public Checkbox checkbox(CharSequence text, VersionedValue<CheckState> model) {
        return new Checkbox(this, text, model);
    }

    /** A checkbox with a model of its own, unchecked. */
    @AllowAllocation("controls are built once")
    public Checkbox checkbox(CharSequence text) {
        return new Checkbox(this, text, clock().value(CheckState.UNCHECKED));
    }

    /** A switch bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public Switch toggleSwitch(CharSequence text, VersionedBoolean model) {
        return new Switch(this, text, null, model);
    }

    /** A radio group bound to {@code model}; add its options. */
    @AllowAllocation("controls are built once")
    public RadioGroup radioGroup(String name, VersionedInt model) {
        return new RadioGroup(this, name, model);
    }

    /** A segmented control bound to {@code model}; add its segments. */
    @AllowAllocation("controls are built once")
    public SegmentedControl segmented(String name, VersionedInt model) {
        return new SegmentedControl(this, name, model);
    }

    /** A horizontal slider over {@code min..max} bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public Slider slider(String name, VersionedDouble model, double min, double max) {
        return new Slider(this, name, model, min, max, false);
    }

    /** A horizontal range slider over {@code min..max} bound to {@code low} and {@code high}. */
    @AllowAllocation("controls are built once")
    public Slider rangeSlider(String name, VersionedDouble low, VersionedDouble high, double min, double max) {
        return new Slider(this, name, name + " minimum", low, name + " maximum", high, min, max, false);
    }

    /** A progress bar bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public ProgressBar progress(String name, VersionedDouble model) {
        return new ProgressBar(this, name, model);
    }

    /** A loading spinner. */
    @AllowAllocation("controls are built once")
    public Spinner spinner(String name) {
        return new Spinner(this, name);
    }

    /** A separator. */
    @AllowAllocation("controls are built once")
    public Separator separator(boolean vertical) {
        return new Separator(this, vertical);
    }

    /** A badge. */
    @AllowAllocation("controls are built once")
    public Badge badge(CharSequence text, String name) {
        return new Badge(this, text, name);
    }

    /** Text with an icon. */
    @AllowAllocation("controls are built once")
    public IconLabel iconLabel(String iconName, CharSequence text) {
        return new IconLabel(this, iconName, text);
    }

    /** An image of style name {@code style} described as {@code description}. */
    @AllowAllocation("controls are built once")
    public ImageView image(String style, String description) {
        return new ImageView(this, style, description);
    }

    /** A colour picker bound to {@code model} (a packed colour). */
    @AllowAllocation("controls are built once")
    public ColorPicker colorPicker(String name, VersionedLong model) {
        return new ColorPicker(this, name, model);
    }

    /** A date picker in {@code locale} bound to {@code model}. */
    @AllowAllocation("controls are built once")
    public DatePicker datePicker(String name, Locale locale, VersionedValue<LocalDate> model) {
        return new DatePicker(this, name, locale, model);
    }

    /** A 24-hour time picker (hours and minutes) bound to {@code model} (seconds since midnight). */
    @AllowAllocation("controls are built once")
    public TimePicker timePicker(String name, VersionedInt model) {
        return new TimePicker(this, name, model, false, false, "AM", "PM");
    }

    /** A key-binding field bound to {@code model}, showing {@code prompt} while it captures. */
    @AllowAllocation("controls are built once")
    public <B> KeyBindingField<B> keyBinding(String name, CharSequence prompt, VersionedValue<B> model) {
        return new KeyBindingField<>(this, name, prompt, model);
    }

    /** A drop-down over {@code items} bound to {@code model} (the chosen index). */
    @AllowAllocation("controls are built once")
    public <T> DropDown<T> dropDown(String name, List<T> items, DropDown.ItemText<T> text, VersionedInt model) {
        return new DropDown<>(this, name, items, text, model);
    }

    /** A pop-up or context menu; fill it with items. */
    @AllowAllocation("controls are built once")
    public Menu menu(String name) {
        return new Menu(this, name);
    }

    /** A menu bar; add its menus. */
    @AllowAllocation("controls are built once")
    public MenuBar menuBar(String name) {
        return new MenuBar(this, name);
    }

    /** A toolbar; {@code more} names its overflow button. */
    @AllowAllocation("controls are built once")
    public Toolbar toolbar(String name, String more) {
        return new Toolbar(this, name, more);
    }

    /** A status bar. */
    @AllowAllocation("controls are built once")
    public StatusBar statusBar(String name) {
        return new StatusBar(this, name);
    }

    /** Breadcrumbs. */
    @AllowAllocation("controls are built once")
    public Breadcrumbs breadcrumbs(String name) {
        return new Breadcrumbs(this, name);
    }

    /** A message dialog (call {@link Dialog#show()}). */
    @AllowAllocation("dialogs are built when shown")
    public Dialog message(CharSequence title, CharSequence message, CharSequence ok) {
        return Dialog.message(this, title, message, ok);
    }

    /** A confirm dialog: ok is 1, cancel 0 (call {@link Dialog#show()}). */
    @AllowAllocation("dialogs are built when shown")
    public Dialog confirm(CharSequence title, CharSequence message, CharSequence ok, CharSequence cancel) {
        return Dialog.confirm(this, title, message, ok, cancel);
    }

    /** The notifications of this UI. */
    @AllowAllocation("made once")
    public Toasts toasts() {
        if (toasts == null) {
            toasts = new Toasts(this);
        }
        return toasts;
    }

    /** A tabbed pane with its strip on {@code side}, bound to {@code model} (the selected tab). */
    @AllowAllocation("controls are built once")
    public TabPane tabs(String name, TabPane.Side side, VersionedInt model) {
        return new TabPane(this, name, side, model);
    }

    /** A collapsible section. */
    @AllowAllocation("controls are built once")
    public Collapsible collapsible(CharSequence title, Element content, VersionedBoolean expanded) {
        return new Collapsible(this, title, content, expanded);
    }

    /** An expander (a collapsible of type {@code expander}). */
    @AllowAllocation("controls are built once")
    public Collapsible expander(CharSequence title, Element content, VersionedBoolean expanded) {
        return new Collapsible(this, "expander", title, content, expanded);
    }

    /** An accordion: one section open at a time when {@code single}. */
    @AllowAllocation("controls are built once")
    public Accordion accordion(String name, boolean single) {
        return new Accordion(this, name, single);
    }

    /** A titled group box (fieldset). */
    @AllowAllocation("controls are built once")
    public GroupBox groupBox(CharSequence title) {
        return new GroupBox(this, title);
    }

    /** A card. */
    @AllowAllocation("controls are built once")
    public Card card(CharSequence title, CharSequence subtitle) {
        return new Card(this, title, subtitle);
    }

    /** A split pane bound to {@code share} (the first pane's). */
    @AllowAllocation("controls are built once")
    public SplitPane split(String name, boolean vertical, VersionedDouble share) {
        return new SplitPane(this, name, vertical, share);
    }

    /** A carousel bound to {@code page}. */
    @AllowAllocation("controls are built once")
    public Carousel carousel(String name, VersionedInt page) {
        return new Carousel(this, name, page);
    }

    /** A stepper (wizard) bound to {@code step}. */
    @AllowAllocation("controls are built once")
    public Stepper stepper(String name, CharSequence back, CharSequence next, CharSequence finish,
        VersionedInt step) {
        return new Stepper(this, name, back, next, finish, step);
    }

    /** A drawer from {@code side}, modal or pushing, bound to {@code open}. */
    @AllowAllocation("controls are built once")
    public Drawer drawer(String name, Drawer.Side side, boolean modal, VersionedBoolean open) {
        return new Drawer(this, name, side, modal, open);
    }

    // ---------------------------------------------------------------- the frame

    /** Polls {@code control} once a frame from now on. */
    @AllowAllocation("controls are built once")
    void poll(Polled control) {
        if (polledCount == polled.length) {
            polled = Arrays.copyOf(polled, polledCount * 2);
        }
        polled[polledCount++] = control;
    }

    /** Stops polling {@code control}. */
    void unpoll(Polled control) {
        for (int i = 0; i < polledCount; i++) {
            if (polled[i] == control) {
                System.arraycopy(polled, i + 1, polled, i, polledCount - i - 1);
                polled[--polledCount] = null;
                return;
            }
        }
    }

    /**
     * Focuses {@code element} for {@code player} now, or, if it cannot take focus yet (a pop-up or dialog opened this
     * frame is laid out at its end), as soon as it is laid out: at the end of this frame, so the next frame's input
     * already goes to it (retried for a few frames).
     */
    public void focusSoon(Element element, int player) {
        if (ui.interaction().focus(element, player)) {
            pendingFocus = null;
            return;
        }
        pendingFocus = element;
        pendingPlayer = player;
        pendingFrames = 4;
    }

    private boolean counted; // pendingFrames counted down in this frame

    @Override
    public void afterLayout() {
        if (pendingFocus != null) {
            boolean last = !counted && --pendingFrames <= 0; // frames, not layout passes
            counted = true;
            if (!pendingFocus.alive() || ui.interaction().focus(pendingFocus, pendingPlayer) || last) {
                pendingFocus = null;
            }
        }
    }

    @Override
    public void frame(long nanos) {
        now = nanos;
        counted = false;
        for (int i = 0; i < polledCount; i++) {
            polled[i].poll(nanos);
        }
    }

    // ---------------------------------------------------------------- layers and placing

    /** The pop-up layer (menus, drop-downs). */
    Layer popupLayer() {
        return ui.layer(Layers.POPUP);
    }

    /** The modal layer (dialogs, modal drawers). */
    Layer modalLayer() {
        return ui.layer(Layers.MODAL);
    }

    /** The layer notifications show on: {@code notifications}, between pop-ups and tooltips. */
    @AllowAllocation("made once")
    Element notificationsLayer() {
        if (notificationsRoot == null) {
            Layer layer = ui.layerStack().find("notifications");
            if (layer == null) {
                layer = ui.layerStack().add("notifications", 350);
            }
            ui.layer("notifications");
            notificationsRoot = layer.root();
        }
        return notificationsRoot;
    }

    /** The layout. */
    Layout layout() {
        return ui.layout();
    }

    /** Converts UI pixels to panel units (what offsets are in). */
    float units(float pixels) {
        float s = ui.layout().scale();
        return s > 0 ? pixels / s : pixels;
    }

    /**
     * Places {@code popup} (a child of a layer root whose layout is {@code none}) next to the rectangle
     * {@code x, y, w, h} (UI pixels): below it ({@code below}) or to its right, flipped above or to the left when
     * it does not fit on the screen, and kept on it. Uses the pop-up's last laid-out size (none before its first
     * frame: it is placed below or right, and corrected the frame after).
     */
    void place(Element popup, float x, float y, float w, float h, boolean below) {
        Layout l = ui.layout();
        int s = popup.slot();
        float pw = l.shown(s) ? l.width(s) : 0;
        float ph = l.shown(s) ? l.height(s) : 0;
        int r = ui.store().rootSlot();
        float sw = l.width(r);
        float sh = l.height(r);
        float px;
        float py;
        if (below) {
            px = x;
            py = y + h;
            if (py + ph > sh && y - ph >= 0) {
                py = y - ph;
            }
        } else {
            px = x + w;
            py = y;
            if (px + pw > sw && x - pw >= 0) {
                px = x - pw;
            }
        }
        px = Math.max(0, Math.min(px, sw - pw));
        py = Math.max(0, Math.min(py, sh - ph));
        ui.visuals().offsets(s, units(px), units(py), 0, 0);
    }

    /** Whether {@code slot} is {@code ancestor} or under it. */
    boolean inside(int ancestor, int slot) {
        UiStore store = ui.store();
        for (int s = slot; s != UiStore.NONE; s = store.parent(s)) {
            if (s == ancestor) {
                return true;
            }
        }
        return false;
    }
}
