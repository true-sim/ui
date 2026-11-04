package com.truesim.ui.core.bind;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import java.util.Objects;

/**
 * A two-way connection between a model value and a control's value (design 4.2), polled once a frame by
 * {@link Bindings}:
 * <ul>
 * <li>UI to model: when the control's value changed (an edit), it is converted ({@link Converter#from}), validated
 * and written into the model; a value that does not convert or validate is not written, and the bound element (if
 * any) shows {@code :invalid} with {@link #message()} saying why;
 * <li>model to UI: when the model changed (elsewhere: undo, a script, the network), its value is converted
 * ({@link Converter#to}) and shown by the control at once, with no refresh. A model holding more than this value (a
 * record a lens reads a field of) may change without this value changing: the control keeps what it shows (a value
 * typed and refused stays, marked invalid).
 * </ul>
 * Both directions compare versions, so a frame where nothing changed costs two loads and allocates nothing. An edit
 * and a model change in the same frame: the edit is written first, then the model (now holding it) is shown.
 *
 * <p><b>Failures.</b> A getter, wither, converter or control that throws does not stop the frame: the binding is
 * {@linkplain #broken() broken} (its element shows {@code :invalid}, {@link #message()} says why, the failure is
 * logged once), and it tries again when either end changes.
 *
 * <pre>{@code
 * Binding<Double, Double> b = ui.bindings().bind(Bindable.of(slider.model()),
 *     Bindable.through(material, MaterialProperties.ROUGHNESS));
 * }</pre>
 *
 * @param <M> the model's value type
 * @param <C> the control's value type
 */
public final class Binding<M, C> {

    /** Told of every value a binding wrote into its model (undo hooks). */
    @FunctionalInterface
    public interface Listener {
        /** {@code binding} wrote {@code after} over {@code before}. */
        void edited(Binding<?, ?> binding, Object before, Object after);
    }

    private final Bindable<M> model;
    private final Bindable<C> control;
    private final Converter<M, C> converter;
    private Validator<? super M> validator = Validator.valid();
    private Element element;
    private Listener listener;
    private String message;
    private long modelSeen;
    private M shownModel;
    private long controlSeen;
    private RuntimeException broken; // the failure of the last poll, or null
    private long brokenModel = -1;
    private long brokenControl = -1;

    /** Binds {@code control} to {@code model} through {@code converter}; the control shows the model's value now. */
    @AllowAllocation("bindings are made when a UI is built")
    public Binding(Bindable<M> model, Bindable<C> control, Converter<M, C> converter) {
        this.model = Objects.requireNonNull(model, "model");
        this.control = Objects.requireNonNull(control, "control");
        this.converter = Objects.requireNonNull(converter, "converter");
        show();
    }

    /** The model end. */
    public Bindable<M> model() {
        return model;
    }

    /** The control end. */
    public Bindable<C> control() {
        return control;
    }

    /** Checks edits before they are written; returns this. */
    public Binding<M, C> validator(Validator<? super M> value) {
        validator = value == null ? Validator.valid() : value;
        return this;
    }

    /** The element showing {@code :invalid} while the control holds a value that was not written; returns this. */
    public Binding<M, C> element(Element value) {
        element = value;
        return this;
    }

    /** The element showing {@code :invalid}, or null. */
    public Element element() {
        return element;
    }

    /** Told of each value written into the model; returns this. */
    public Binding<M, C> listener(Listener value) {
        listener = value;
        return this;
    }

    /** Why the control's value was not written, or null while it was. */
    public String message() {
        return message;
    }

    /** Whether the control's value was written (it converted and validated). */
    public boolean valid() {
        return message == null;
    }

    /** Shows the model's value in the control now, whether or not it changed (a model end that moved elsewhere). */
    public void resync() {
        show();
    }

    /**
     * Brings both ends up to date: an edit into the model, then a model change into the control. A failure breaks the
     * binding instead of escaping ({@link #broken()}); returns whether anything changed.
     */
    public boolean poll() {
        if (broken != null) {
            if (model.version() == brokenModel && control.version() == brokenControl) {
                return false; // nothing changed since it failed
            }
            broken = null;
        }
        try {
            return pollEnds();
        } catch (RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Breaks the binding on {@code e}: disabled until an end changes, its element invalid, the failure logged. */
    @AllowAllocation("a binding failing: logged and shown, not per frame")
    private void fail(RuntimeException e) {
        brokenModel = model.version();
        brokenControl = control.version();
        modelSeen = brokenModel;
        controlSeen = brokenControl;
        LOG.log(System.Logger.Level.WARNING, "A binding failed and is disabled until an end changes: " + e, e);
        broken = e;
        invalid(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /** The failure that broke it (a getter, wither, converter or control that threw), or null while it works. */
    public RuntimeException broken() {
        return broken;
    }

    private static final System.Logger LOG = System.getLogger(Binding.class.getName());

    private boolean pollEnds() {
        boolean changed = false;
        if (control.version() != controlSeen) {
            controlSeen = control.version();
            write();
            changed = true;
        }
        if (model.version() != modelSeen) {
            modelSeen = model.version();
            M value = model.get();
            if (!Objects.equals(value, shownModel)) { // a model holding other fields changed, not this one
                show();
                changed = true;
            }
        }
        return changed;
    }

    private void write() {
        C shown = control.get();
        M value;
        try {
            value = converter.from(shown);
        } catch (IllegalArgumentException e) {
            invalid(e.getMessage() == null ? "invalid" : e.getMessage());
            return;
        }
        String why = validator.validate(value);
        if (why != null) {
            invalid(why);
            return;
        }
        invalid(null);
        M before = model.get();
        if (model.set(value)) {
            modelSeen = model.version();
            shownModel = value;
            if (listener != null) {
                listener.edited(this, before, value);
            }
        }
    }

    private void show() {
        modelSeen = model.version();
        shownModel = model.get();
        control.set(converter.to(shownModel));
        controlSeen = control.version();
        invalid(null);
    }

    private void invalid(String why) {
        message = why;
        if (element != null && element.alive()) {
            element.state(UiState.INVALID, why != null);
        }
    }
}
