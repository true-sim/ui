package com.truesim.ui.a11y.windows;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.a11y.AccessibilityTunables;
import com.truesim.ui.controls.basic.CheckState;
import com.truesim.ui.core.Role;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The provider as a screen reader sees it: a hidden window with the rig's controls, its messages pumped and the UI
 * updated on a UI thread, and a UI Automation client ({@code IUIAutomation}, as Narrator uses) on the test's thread
 * reading names, control types, values, toggle states, focus, runtime ids and boxes, and invoking, toggling, focusing
 * and setting values. Windows only (UI Automation): skipped elsewhere.
 */
class WindowsAccessibilityTest extends UiThreadTest {

    private static final int NAME = UiaElement.PROPERTY_NAME;
    private static final int CONTROL_TYPE = UiaElement.PROPERTY_CONTROL_TYPE;

    private static Map<String, MemorySegment> byName(UiaClient client, List<MemorySegment> elements) {
        Map<String, MemorySegment> named = new HashMap<>();
        for (MemorySegment e : elements) {
            String n = client.name(e);
            if (n != null) {
                named.putIfAbsent(n, e);
            }
        }
        return named;
    }

    @Test
    void aClientReadsAndDrivesTheControls() throws Exception {
        try (UiaClient client = new UiaClient()) {
            MemorySegment window = client.fromWindow(this.window.hwnd);
            assertEquals("true-sim UIA test", client.name(window), "the host provider names the window");
            Map<String, MemorySegment> named = byName(client, client.descendants(window));
            assertTrue(named.keySet().containsAll(List.of("Hello", "OK", "Off", "Agree", "Name", "Secret", "Volume",
                "Loading")), named.keySet().toString());

            // roles as control types
            assertEquals(ControlTypes.TEXT, client.property(named.get("Hello"), CONTROL_TYPE));
            assertEquals(ControlTypes.BUTTON, client.property(named.get("OK"), CONTROL_TYPE));
            assertEquals(ControlTypes.CHECK_BOX, client.property(named.get("Agree"), CONTROL_TYPE));
            assertEquals(ControlTypes.EDIT, client.property(named.get("Name"), CONTROL_TYPE));
            assertEquals(ControlTypes.SLIDER, client.property(named.get("Volume"), CONTROL_TYPE));
            assertEquals(ControlTypes.PROGRESS_BAR, client.property(named.get("Loading"), CONTROL_TYPE));
            assertEquals("ok", client.property(named.get("OK"), UiaElement.PROPERTY_AUTOMATION_ID));
            assertEquals(UiaElement.FRAMEWORK, client.property(named.get("OK"), UiaElement.PROPERTY_FRAMEWORK_ID));
            assertEquals(false, client.property(named.get("Off"), UiaElement.PROPERTY_IS_ENABLED));
            assertEquals(true, client.property(named.get("OK"), UiaElement.PROPERTY_IS_ENABLED));
            assertEquals(true, client.property(named.get("OK"), UiaElement.PROPERTY_IS_KEYBOARD_FOCUSABLE));
            assertEquals(true, client.property(named.get("Secret"), UiaElement.PROPERTY_IS_PASSWORD));
            assertEquals(false, client.property(named.get("Name"), UiaElement.PROPERTY_IS_PASSWORD));

            // values through the Value pattern
            assertEquals("abc", client.property(named.get("Name"), UiaElement.PROPERTY_VALUE_VALUE));
            assertEquals("0.4", client.property(named.get("Volume"), UiaElement.PROPERTY_VALUE_VALUE));
            assertEquals("0.25", client.property(named.get("Loading"), UiaElement.PROPERTY_VALUE_VALUE));
            assertEquals("", client.property(named.get("Secret"), UiaElement.PROPERTY_VALUE_VALUE));
            assertEquals(0, client.property(named.get("Agree"), UiaElement.PROPERTY_TOGGLE_STATE));

            // patterns where they belong
            assertEquals(0, client.pattern(named.get("Hello"), UiaElement.PATTERN_INVOKE,
                UiaClient.IID_INVOKE_PATTERN).address(), "a text is not invoked");
            assertEquals(0, client.pattern(named.get("Agree"), UiaElement.PATTERN_INVOKE,
                UiaClient.IID_INVOKE_PATTERN).address(), "a checkbox toggles");
            assertEquals(0, client.pattern(named.get("OK"), UiaElement.PATTERN_VALUE,
                UiaClient.IID_VALUE_PATTERN).address(), "a button has no value");

            // identity: the runtime id ends in the element's slot and generation (UI Automation puts the window's
            // id before them, for UiaAppendRuntimeId)
            int[] id = client.runtimeId(named.get("OK"));
            int slot = rig.ok.element().slot();
            int generation = onUi(() -> rig.ui.store().generation(slot));
            assertArrayEquals(new int[] {slot, generation}, java.util.Arrays.copyOfRange(id, id.length - 2,
                id.length), java.util.Arrays.toString(id));

            // boxes on the screen: inside the window's client area, where the layout put them
            double[] box = client.bounds(named.get("OK"));
            float width = onUi(() -> rig.ui.layout().width(slot));
            assertEquals(Math.round(width), box[2] - box[0], 1.0);
            assertTrue(box[3] > box[1]);

            // invoke a button: the UI thread runs it
            client.invoke(named.get("OK"));
            await(() -> ui(() -> rig.ok.clicks() == 1), "the click");

            // toggle the checkbox
            client.toggle(named.get("Agree"));
            await(() -> ui(() -> rig.agree.state() == CheckState.CHECKED), "the toggle");
            await(() -> Integer.valueOf(1).equals(client.property(named.get("Agree"),
                UiaElement.PROPERTY_TOGGLE_STATE)), "the toggle state read back");

            // set values
            assertEquals(0, client.setValue(named.get("Name"), "typed"));
            await(() -> ui(() -> rig.name.text().toString().equals("typed")), "the field's text");
            await(() -> "typed".equals(client.property(named.get("Name"), UiaElement.PROPERTY_VALUE_VALUE)),
                "the value read back");
            assertEquals(0, client.setValue(named.get("Volume"), "0.8"));
            await(() -> ui(() -> Math.abs(rig.volumeModel.get() - 0.8) < 1e-9), "the slider");
            assertNotEquals(0, client.setValue(named.get("Loading"), "1"), "a progress bar's value is read only");

            // focus: set by the client, followed by the UI and read back
            client.focus(named.get("Name"));
            await(() -> ui(() -> rig.ui.interaction().focused(0) == rig.name.element()), "the focus");
            await(() -> Boolean.TRUE.equals(client.property(named.get("Name"),
                UiaElement.PROPERTY_HAS_KEYBOARD_FOCUS)), "keyboard focus read back");
            assertEquals(false, client.property(named.get("OK"), UiaElement.PROPERTY_HAS_KEYBOARD_FOCUS));

            // a name that changes is read live
            onUi(() -> rig.ok.text("Okay"));
            await(() -> "Okay".equals(client.name(named.get("OK"))), "the new name");

            // an element that goes is no longer available
            onUi(() -> {
                rig.ui.destroy(rig.hello);
                return null;
            });
            MemorySegment hello = named.get("Hello");
            await(() -> {
                try {
                    client.name(hello);
                    return false;
                } catch (IllegalStateException gone) {
                    return gone.getMessage().contains("80040201");
                }
            }, "UIA_E_ELEMENTNOTAVAILABLE");
        }
    }

    @Test
    void theTunableHidesTheUi() throws Exception {
        try {
            AccessibilityTunables.BRIDGE.select("off");
            onUi(() -> null);
            onUi(() -> null); // a frame has seen it
            try (UiaClient client = new UiaClient()) {
                MemorySegment window = client.fromWindow(this.window.hwnd);
                Map<String, MemorySegment> named = byName(client, client.descendants(window));
                assertFalse(named.containsKey("OK"), named.keySet().toString());
            }
            AccessibilityTunables.BRIDGE.select("on");
            onUi(() -> null);
            onUi(() -> null);
            try (UiaClient client = new UiaClient()) {
                MemorySegment window = client.fromWindow(this.window.hwnd);
                assertTrue(byName(client, client.descendants(window)).containsKey("OK"));
            }
        } finally {
            AccessibilityTunables.BRIDGE.select("on");
        }
    }

    @Test
    void anIdleFrameAllocatesNothing() throws Exception {
        onUi(() -> {
            access.update();
            AllocationAssertions.assertAllocationFree(access::update);
            return null;
        });
    }

    @Test
    void theApi() throws Exception {
        assertEquals(this.window.hwnd, access.hwnd());
        assertEquals(1, access.scale());
        assertThrows(IllegalArgumentException.class, () -> access.scale(0));
        assertEquals(2, access.scale(2).scale());
        access.scale(1);
        assertTrue(WindowsAccessibility.liveObjects() > 0);
        onUi(() -> {
            assertThrows(IllegalStateException.class, () -> WindowsAccessibility.attach(window.hwnd, rig.bridge));
            return null;
        });
        assertEquals(ControlTypes.CUSTOM, ControlTypes.of(Role.NONE));
        assertEquals("cell", ControlTypes.localized(Role.CELL));
        assertNull(ControlTypes.localized(Role.BUTTON));
        for (Role r : Role.values()) {
            assertTrue(ControlTypes.of(r) >= 50000);
        }
    }
}
