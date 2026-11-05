package com.truesim.ui.a11y;

import com.truesim.common.state.VersionedDouble;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Checkbox;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ProgressBar;
import com.truesim.ui.controls.basic.Slider;
import com.truesim.ui.controls.text.PasswordField;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.fonts.cjk.BundledCjkFonts;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.jdk.JdkTextBackend;

/**
 * A UI of named controls for the accessibility bridges' tests: a text, a button, a disabled button, a checkbox, a text
 * field, a password, a slider and a progress bar in a column, built on the calling thread (the UI thread) and driven
 * one frame at a time.
 */
public final class AccessibilityRig {

    public final Ui ui;
    public final Controls controls;
    public final UiInput input = new UiInput(8);
    public final Element panel;
    public final Element hello;
    public final Button ok;
    public final Button off;
    public final Checkbox agree;
    public final TextEditor name;
    public final PasswordField secret;
    public final VersionedDouble volumeModel;
    public final Slider volume;
    public final ProgressBar loading;
    public final AccessibilityBridge bridge;
    private long now = 1_000_000_000L;

    public AccessibilityRig() {
        JdkTextBackend backend = new JdkTextBackend();
        GlyphAtlas atlas = new GlyphAtlas(new UiTextures(), backend.msdfGenerator(), backend.newRasterizer(), null,
            null, new AtlasSettings());
        ui = new Ui(Tss.defaultTheme(), new TextSystem(BundledCjkFonts.registry(backend), backend.newShaper(), atlas));
        controls = new Controls(ui);
        panel = ui.panel("column");
        panel.id("form");
        hello = ui.label(null, "Hello");
        ok = controls.button("OK");
        ok.element().id("ok");
        off = controls.button("Off");
        off.enabled(false);
        agree = controls.checkbox("Agree");
        name = TextEditor.field(ui, null, "Name");
        name.text("abc");
        secret = PasswordField.create(ui, null, "Secret", "Show");
        secret.editor().text("hunter2");
        volumeModel = ui.store().clock().doubleValue(0.4);
        volume = controls.slider("Volume", volumeModel, 0, 1).step(0.1);
        loading = controls.progress("Loading", ui.store().clock().doubleValue(0.25));
        panel.add(hello).add(ok.element()).add(off.element()).add(agree.element()).add(name.element())
            .add(secret.element()).add(volume.element()).add(loading.element());
        ui.root().add(panel);
        bridge = new AccessibilityBridge(ui.store(), ui.interaction());
        frame();
    }

    /** One frame 16 ms after the last: the UI's update, then the bridge's. */
    public AccessibilityRig frame() {
        input.beginFrame();
        ui.update(800, 600, input, now);
        now += 16_000_000L;
        bridge.update();
        return this;
    }
}
