package com.truesim.ui.controls.basic;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedInt;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Wrap;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Every basic and container control in one screen, for goldens, semantics, theme and allocation tests, and for the
 * integrator's GPU screenshots: {@code ControlsGallery.build(controls, parent)} fills {@code parent} (a 1280 x 800
 * screen fits it) and {@link Gallery#openOverlays()} opens a modal drawer, a dialog, a notification, a drop-down and a
 * context menu on their layers. Mnemonics ({@code &File}), a repeat button, a logarithmic slider, an HDR colour picker
 * with an eyedropper (reading a {@link com.truesim.ui.core.input.PatternScreenSampler} unless the controls have a
 * sampler) and a label for a field are among them; {@link Gallery#openEyedropper(float, float)} shows the eyedropper's
 * magnifier.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);          // or Tss.testTheme()
 * Controls controls = new Controls(ui);
 * ControlsGallery.registerIcons(ui);                         // the test theme's checker icon
 * ControlsGallery.Gallery g = ControlsGallery.build(controls, ui.root());
 * // frames until the atlas settled, then render ui.layers() (and again after g.openOverlays())
 * }</pre>
 */
public final class ControlsGallery {

    private ControlsGallery() {
    }

    /** What {@link #build} made. */
    public static final class Gallery {
        public Element root;
        public Button button;
        public Button iconButton;
        public Button disabled;
        public ToggleButton toggle;
        public Checkbox unchecked;
        public Checkbox checked;
        public Checkbox mixed;
        public RadioGroup radios;
        public Switch onSwitch;
        public Switch offSwitch;
        public SegmentedControl segmented;
        public IconLabel iconLabel;
        public Badge badge;
        public ImageView image;
        public ProgressBar progress;
        public ProgressBar sweeping;
        public Spinner spinner;
        public Slider slider;
        public Slider vertical;
        public Slider range;
        /** A logarithmic slider (20 Hz to 20 kHz) with ticks on its decades. */
        public Slider logarithmic;
        /** A button repeating while held. */
        public RepeatButton repeat;
        /** A label whose mnemonic focuses the key-binding field. */
        public Element bindingLabel;
        /** The screen sampler the eyedropper reads (a pattern). */
        public com.truesim.ui.core.input.PatternScreenSampler sampler;
        public ColorPicker color;
        public DatePicker date;
        public TimePicker time;
        public KeyBindingField<String> binding;
        public DropDown<String> dropDown;
        /** A drop-down filtered by a search field of the text controls. */
        public DropDown<String> searchDropDown;
        public com.truesim.ui.controls.text.SearchField search;
        public com.truesim.ui.controls.text.HexField hex;
        public MenuBar menuBar;
        public Menu contextMenu;
        public Toolbar toolbar;
        public StatusBar status;
        public Breadcrumbs crumbs;
        public TabPane tabs;
        public Accordion accordion;
        public Collapsible expander;
        public GroupBox group;
        public Card card;
        public SplitPane split;
        public Carousel carousel;
        public Stepper stepper;
        public Drawer drawer;
        public Drawer modalDrawer;
        public Dialog dialog;
        Controls controls;
        Element contextTarget;

        /** Starts the colour picker's eyedropper with the pointer at {@code x, y} (call frames after it). */
        public void openEyedropper(float x, float y) {
            color.eyedropperTool().start(0);
            color.eyedropperTool().moveTo(x, y);
        }

        /** Opens a confirm dialog, a notification, the drop-down's list and the context menu. */
        public void openOverlays() {
            modalDrawer.open(true);
            dialog = controls.confirm("Quit?", "Unsaved changes will be lost.", "Quit", "Stay");
            dialog.show();
            controls.toasts().show("Saved", "The level was saved.", 0);
            dropDown.open();
            contextMenu.open(contextTarget, true);
        }
    }

    /**
     * Registers {@code test/checker}, the picture the garish test theme gives every icon; the default theme's icons are
     * the toolkit's built-in ones ({@link com.truesim.ui.controls.BuiltinIcons}), registered by nothing.
     */
    public static void registerIcons(Ui ui) {
        BufferedImage checker = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                checker.setRGB(x, y, (x / 4 + y / 4) % 2 == 0 ? 0xFFFFFFFF : 0xFF000000);
            }
        }
        ui.images().add("test/checker", checker);
    }

    private static Element section(Controls c, Element parent, String title) {
        GroupBox g = c.groupBox(title);
        parent.add(g.element());
        return g.content();
    }

    private static Element row(Controls c) {
        Element e = c.ui().panel(null);
        c.ui().styles().inline(e.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, com.truesim.ui.style.value.Direction.ROW).set(Styles.GAP, 8f)
            .set(Styles.ALIGN_ITEMS, com.truesim.ui.style.value.Align.CENTER).build());
        return e;
    }

    /** A panel showing {@code text}, at least {@code w x h} (larger when a theme's text and padding need more). */
    private static Element sized(Controls c, String text, float w, float h) {
        Element e = c.ui().panel(null);
        c.ui().styles().inline(e.slot(), InlineStyle.builder().set(Styles.MIN_WIDTH, Length.px(w))
            .set(Styles.MIN_HEIGHT, Length.px(h)).build());
        e.add(c.ui().label(null, text));
        return e;
    }

    /** Builds the gallery into {@code parent}; returns what it made. */
    public static Gallery build(Controls c, Element parent) {
        Ui ui = c.ui();
        ChangeClock clock = c.clock();
        Gallery g = new Gallery();
        g.controls = c;
        Element root = ui.panel(null);
        ui.styles().inline(root.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, com.truesim.ui.style.value.Direction.ROW).set(Styles.WRAP, Wrap.WRAP)
            .set(Styles.GAP, 10f).set(Styles.PADDING, Insets.of(8))
            .set(Styles.ALIGN_ITEMS, com.truesim.ui.style.value.Align.START).build());
        parent.add(root);
        g.root = root;

        if (c.screenSampler() == null) {
            g.sampler = new com.truesim.ui.core.input.PatternScreenSampler();
            c.screenSampler(g.sampler);
        }
        Element s = section(c, root, "Buttons");
        g.button = c.button("&Save");
        g.iconButton = c.iconButton("close", "Close");
        g.repeat = new RepeatButton(c, "next", "Next", null); // steps while held
        g.disabled = c.button("Disabled");
        g.disabled.enabled(false);
        g.toggle = c.toggle("Bold", clock.booleanValue(true));
        Element r = row(c);
        r.add(g.button.element()).add(g.iconButton.element()).add(g.repeat.element());
        s.add(r);
        r = row(c);
        r.add(g.toggle.element()).add(g.disabled.element());
        s.add(r);

        s = section(c, root, "Choices");
        g.unchecked = c.checkbox("Unchecked");
        g.checked = c.checkbox("Checked", clock.value(CheckState.CHECKED));
        g.mixed = c.checkbox("Mixed", clock.value(CheckState.INDETERMINATE));
        s.add(g.unchecked.element()).add(g.checked.element()).add(g.mixed.element());
        g.radios = c.radioGroup("Difficulty", clock.intValue(1));
        g.radios.add("Easy");
        g.radios.add("Normal");
        g.radios.add("Hard");
        s.add(g.radios.element());
        g.onSwitch = c.toggleSwitch("V-sync", clock.booleanValue(true));
        g.offSwitch = c.toggleSwitch("HDR", clock.booleanValue(false));
        s.add(g.onSwitch.element()).add(g.offSwitch.element());
        g.segmented = c.segmented("Range", clock.intValue(1));
        g.segmented.add("Day");
        g.segmented.add("Week");
        g.segmented.add("Month");
        s.add(g.segmented.element());

        s = section(c, root, "Display");
        g.iconLabel = c.iconLabel("more", "Settings");
        g.badge = c.badge("", "Unread");
        g.badge.count(7);
        r = row(c);
        r.add(g.iconLabel.element()).add(c.separator(true).element()).add(g.badge.element());
        s.add(r).add(c.separator(false).element());
        g.image = c.image("portrait", "Portrait");
        s.add(g.image.element());
        g.progress = c.progress("Loading level", clock.doubleValue(0.6));
        g.sweeping = c.progress("Connecting", clock.doubleValue(0));
        g.sweeping.indeterminate(true);
        g.spinner = c.spinner("Loading");
        s.add(g.progress.element()).add(g.sweeping.element()).add(g.spinner.element());

        s = section(c, root, "Sliders");
        g.slider = c.slider("Volume", clock.doubleValue(0.4), 0, 1).step(0.1).ticks(11);
        g.range = c.rangeSlider("Level range", clock.doubleValue(20), clock.doubleValue(80), 0, 100);
        g.vertical = new Slider(c, "Gain", clock.doubleValue(0.7), 0, 1, true);
        g.logarithmic = c.slider("Frequency", clock.doubleValue(440), 20, 20_000).scale(SliderScale.logarithmic(10))
            .scaleTicks();
        s.add(g.slider.element()).add(g.logarithmic.element()).add(g.range.element()).add(g.vertical.element());

        s = section(c, root, "Colour");
        g.color = c.colorPicker("Tint", clock.longValue(Rgba.srgb(64, 160, 255, 1)));
        g.color.swatches(Rgba.srgb(255, 64, 64, 1), Rgba.srgb(64, 200, 64, 1), Rgba.srgb(255, 200, 0, 1));
        g.color.hdr(4);
        g.color.model().set(Rgba.pack(2.4f, 1.2f, 0.3f, 1)); // an HDR orange: +1.3 EV over its base colour
        g.hex = com.truesim.ui.controls.text.HexField.attach(c.ui(), g.color, "Hex value");
        s.add(g.color.element());

        s = section(c, root, "Date and time");
        g.date = c.datePicker("Date", Locale.UK, clock.value(LocalDate.of(2026, 11, 3)));
        g.date.today(LocalDate.of(2026, 11, 3));
        g.time = c.timePicker("Time", clock.intValue(13 * 3600 + 45 * 60));
        s.add(g.date.element()).add(g.time.element());

        s = section(c, root, "Input");
        c.capture(new FakeCapture());
        g.binding = c.keyBinding("Jump", "Press a key", clock.value("Space"));
        g.dropDown = c.dropDown("Quality", List.of("Low", "Medium", "High", "Ultra", "Custom"), String::toString,
            clock.intValue(2));
        g.search = com.truesim.ui.controls.text.SearchField.create(c.ui(), null, "Filter countries", "Clear");
        g.searchDropDown = c.dropDown("Country", List.of("Austria", "Australia", "Belgium", "Brazil", "Canada",
            "Chile", "Denmark", "Estonia"), String::toString, clock.intValue(0)).filter(g.search);
        g.bindingLabel = c.labelFor("&Jump key", g.binding.element());
        Element bindingRow = row(c);
        bindingRow.add(g.bindingLabel).add(g.binding.element());
        s.add(bindingRow).add(g.dropDown.element()).add(g.searchDropDown.element());

        s = section(c, root, "Menus and bars");
        g.menuBar = c.menuBar("Main menu");
        Menu file = g.menuBar.menu("&File");
        file.item("&New", "new", () -> { });
        file.item("&Open", "open", () -> { });
        file.separator();
        Menu recent = file.submenu("&Recent");
        recent.item("forest.level", () -> { });
        recent.item("desert.level", () -> { });
        file.item("&Quit", () -> { });
        Menu view = g.menuBar.menu("&View");
        view.check("&Grid", clock.booleanValue(true));
        VersionedInt zoom = clock.intValue(1);
        view.radio("50%", zoom, 0);
        view.radio("100%", zoom, 1);
        s.add(g.menuBar.element());
        g.toolbar = c.toolbar("Tools", "More tools");
        for (String t : new String[] {"Cut", "Copy", "Paste"}) {
            g.toolbar.add(c.button(t), t);
        }
        g.toolbar.separator();
        g.toolbar.add(c.button("Undo"), "Undo");
        s.add(g.toolbar.element());
        g.status = c.statusBar("Status");
        g.status.section("Ready");
        g.status.section("Ln 1");
        s.add(g.status.element());
        g.crumbs = c.breadcrumbs("Location");
        g.crumbs.path(List.of("Home", "Levels", "Forest"));
        s.add(g.crumbs.element());
        g.contextMenu = c.menu("Edit");
        g.contextMenu.item("Rename", () -> { });
        g.contextMenu.separator();
        g.contextMenu.item("Delete", () -> { });
        g.contextTarget = g.crumbs.element();
        g.contextMenu.attachContext(g.contextTarget);

        s = section(c, root, "Containers");
        g.tabs = c.tabs("Panels", TabPane.Side.TOP, clock.intValue(0));
        g.tabs.add("Scene", sized(c, "Scene tab", 180, 40), false);
        g.tabs.add("Assets", sized(c, "Assets tab", 180, 40), true);
        g.tabs.add("Log", sized(c, "Log tab", 180, 40), true);
        s.add(g.tabs.element());
        g.accordion = c.accordion("Sections", true);
        g.accordion.add("General", sized(c, "General", 160, 30), true);
        g.accordion.add("Advanced", sized(c, "Advanced", 160, 30), false);
        s.add(g.accordion.element());
        g.expander = c.expander("Details", sized(c, "Details", 160, 30), clock.booleanValue(false));
        s.add(g.expander.element());

        s = section(c, root, "More containers");
        g.card = c.card("Forest", "Level 3");
        g.card.add(ui.label(null, "A quiet wood."));
        g.card.action(c.button("Play"));
        s.add(g.card.element());
        g.split = c.split("Editor split", false, clock.doubleValue(0.4));
        g.split.first().add(ui.label(null, "Left"));
        g.split.second().add(ui.label(null, "Right"));
        ui.styles().inline(g.split.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(240))
            .set(Styles.HEIGHT, Length.px(50)).build());
        s.add(g.split.element());
        g.carousel = c.carousel("Screenshots", clock.intValue(1));
        for (String t : new String[] {"One", "Two", "Three"}) {
            Element slide = ui.panel("card"); // fills its page
            slide.add(ui.label(null, t));
            g.carousel.add(slide);
        }
        ui.styles().inline(g.carousel.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(240))
            .build());
        s.add(g.carousel.element());

        s = section(c, root, "Flows");
        g.stepper = c.stepper("Setup", "Back", "Next", "Finish", clock.intValue(1));
        g.stepper.add("Account", sized(c, "Account", 160, 30));
        g.stepper.add("Profile", sized(c, "Profile", 160, 30));
        g.stepper.add("Done", sized(c, "Done", 160, 30));
        s.add(g.stepper.element());
        Element drawerRow = c.ui().panel(null); // the drawer pushes the content beside it aside
        c.ui().styles().inline(drawerRow.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, com.truesim.ui.style.value.Direction.ROW).set(Styles.GAP, 8f)
            .set(Styles.ALIGN_ITEMS, com.truesim.ui.style.value.Align.STRETCH).set(Styles.MIN_HEIGHT, Length.px(80))
            .build());
        g.drawer = c.drawer("Sidebar", Drawer.Side.LEFT, false, clock.booleanValue(true));
        g.drawer.content().add(ui.label(null, "Sidebar"));
        drawerRow.add(g.drawer.element()).add(sized(c, "Content", 80, 60));
        s.add(drawerRow);
        g.modalDrawer = c.drawer("Settings drawer", Drawer.Side.RIGHT, true, clock.booleanValue(false));
        g.modalDrawer.content().add(ui.label(null, "Drawer settings"));
        g.group = c.groupBox("Fieldset");
        g.group.add(c.checkbox("Inside").element());
        g.group.enabled(false);
        s.add(g.group.element());
        return g;
    }

    /** An engine stand-in for key-binding capture: the test hands it the next "key". */
    static final class FakeCapture implements InputCapture {
        Listener listener;
        int player = -1;

        @Override
        public void begin(int p, Listener l) {
            listener = l;
            player = p;
        }

        @Override
        public void cancel() {
            Listener l = listener;
            listener = null;
            if (l != null) {
                l.cancelled();
            }
        }

        @Override
        public CharSequence label(Object binding) {
            return binding.toString();
        }

        /** The player pressed {@code key}. */
        void press(String key) {
            Listener l = listener;
            listener = null;
            l.captured(key);
        }
    }
}
