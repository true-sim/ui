package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedValue;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.UiCommand;

/**
 * Shows an input binding and captures a new one (type {@code key-binding}, a {@code label} inside), for rebinding
 * screens: bound two-way to a {@link VersionedValue} of the engine's binding object, labelled by the engine's
 * {@link InputCapture}. A click or ACCEPT starts capturing ({@code :editing}, the label shows the prompt): the next
 * key or button the player presses becomes the binding; CANCEL (or a click again) stops without one.
 *
 * @param <B> the engine's binding type
 */
public final class KeyBindingField<B> extends Button implements InputCapture.Listener {

    private final CharSequence prompt;
    private VersionedValue<B> model;
    private Watch watch;
    private boolean capturing;
    private int player;

    /** A field named {@code name} showing {@code model}'s binding, {@code prompt} while capturing. */
    @AllowAllocation("controls are built once")
    public KeyBindingField(Controls controls, String name, CharSequence prompt, VersionedValue<B> model) {
        super(controls, "key-binding", Role.BUTTON, "", null, name);
        this.prompt = prompt;
        bind(model);
    }

    /** Binds the field to {@code value}; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public KeyBindingField<B> bind(VersionedValue<B> value) {
        model = value;
        watch = value.watch();
        show();
        return this;
    }

    /** The model. */
    public VersionedValue<B> model() {
        return model;
    }

    /** Whether it waits for an input now. */
    public boolean capturing() {
        return capturing;
    }

    @Override
    void clicked() {
        InputCapture capture = controls.capture();
        if (capture == null) {
            return;
        }
        if (capturing) {
            capture.cancel();
            return;
        }
        capturing = true;
        root.state(UiState.EDITING, true);
        ui.text(labelElement(), prompt);
        capture.begin(player, this);
    }

    @Override
    public void handle(UiEvent event) {
        if (event.type() == EventType.CLICK || event.type() == EventType.COMMAND) {
            player = event.player();
        }
        if (capturing && is(event, UiCommand.CANCEL)) {
            event.consume();
            controls.capture().cancel();
            return;
        }
        super.handle(event);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void captured(Object binding) {
        end();
        model.set((B) binding);
        show();
    }

    @Override
    public void cancelled() {
        end();
        show();
    }

    private void end() {
        capturing = false;
        root.state(UiState.EDITING, false);
    }

    private void show() {
        watch.update();
        if (capturing) {
            return;
        }
        InputCapture capture = controls.capture();
        B b = model.get();
        ui.text(labelElement(), capture == null || b == null ? "" : capture.label(b));
    }

    @Override
    public void poll(long nanos) {
        super.poll(nanos);
        if (watch.isChanged()) {
            show();
        }
    }
}
