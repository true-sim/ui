package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.event.DataFormat;
import com.truesim.ui.core.event.DragOperation;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.testkit.DrawListDump;
import com.truesim.ui.testkit.SyntheticInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The frame with input: picking against real layout and styles, states to styles in the same frame, layers. */
class InteractionUiTest {

    static final StyleSheet SHEET = Tss.compileString("interaction.tss", """
        root { layout: flex; direction: row; gap: 10px; padding: 10px; align-items: start; }
        layer { layout: flex; padding: 50px; align-items: start; }
        layer#drag { layout: none; padding: 0px; }
        panel { layout: none; }
        panel.button {
          width: 100px; height: 40px; radius: 8px; cursor: pointer; background: box(#202020);
          &:hover { background: box(#404040); }
          &:pressed { background: box(#606060); }
          &:focus-visible { focus-ring: 2px #ffcc00; focus-ring-offset: 1px; }
          &:disabled { background: box(#101010); }
          &:drop-target { background: box(#00ff00); }
        }
        panel.view { width: 100px; height: 100px; clip: true; radius: 10px; offsets: 0px 0px 0px 0px; }
        panel.inside { width: 100px; height: 300px; offsets: 0px 0px 0px 0px; background: box(#ff0000); }
        panel.icon { width: 16px; height: 16px; background: box(#0000ff); }
        panel.wall { width: 2000px; height: 2000px; pointer-events: all; }
        """);

    /** A UI, its input and a clock, one frame at a time. */
    static final class Frame {
        final Ui ui = new Ui(SHEET, null);
        final UiInput input = new UiInput(32);
        final SyntheticInput synthetic = new SyntheticInput();
        long now = 1_000_000_000L;

        Element button(Element parent) {
            Element b = ui.panel("button");
            parent.add(b);
            return b;
        }

        Frame run() {
            input.beginFrame();
            synthetic.poll(input);
            ui.update(800, 600, input, now);
            now += 16_000_000L;
            return this;
        }

        Frame mouse(float x, float y, int buttons) {
            synthetic.mouse(x, y, buttons);
            return run();
        }
    }

    private static Paint background(Ui ui, Element e) {
        ResolvedStyle s = ui.styles().styleOf(e.slot());
        return s.get(Styles.BACKGROUND);
    }

    @Test
    void hoverAndPressShowInTheFrameTheyHappen() {
        Frame f = new Frame();
        Element a = f.button(f.ui.root());
        Element b = f.button(f.ui.root());
        List<String> clicks = new ArrayList<>();
        f.ui.on(a, EventType.CLICK, e -> clicks.add("a"));
        f.ui.interaction().focusable(b, true);
        f.run(); // first layout: nothing to pick yet
        assertThat(f.ui.layout().x(b.slot())).isEqualTo(120);
        f.mouse(20, 20, 0);
        assertThat(a.is(UiState.HOVER)).isTrue();
        assertThat(DrawListDump.of(f.ui.layers()[0])).contains("a=(0.05 0.05 0.05 1.00)"); // #404040, this frame
        assertThat(f.ui.interaction().cursor(SyntheticInput.MOUSE)).isEqualTo(Cursor.POINTER);
        f.mouse(20, 20, Pointer.PRIMARY);
        assertThat(a.is(UiState.PRESSED)).isTrue();
        assertThat(background(f.ui, a)).isEqualTo(Paint.box(Color.hex("#606060")));
        f.mouse(20, 20, 0);
        assertThat(clicks).containsExactly("a");
        assertThat(a.is(UiState.PRESSED)).isFalse();
        f.mouse(130, 20, Pointer.PRIMARY); // b is focusable: no listeners, still picked, focused, no ring
        f.mouse(130, 20, 0);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(b);
        assertThat(DrawListDump.of(f.ui.layers()[0])).doesNotContain("border=2.00");
        f.synthetic.press(UiCommand.LEFT);
        f.run();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(b); // a is not focusable: nothing that way
        assertThat(b.is(UiState.FOCUS_VISIBLE)).isTrue(); // but the key shows the ring
        f.synthetic.press(UiCommand.NEXT);
        f.run();
        assertThat(b.is(UiState.FOCUS_VISIBLE)).isTrue();
        String drawn = DrawListDump.of(f.ui.layers()[0]);
        assertThat(drawn).contains("box 117.00,7.00 223.00,53.00 a=(0.00 0.00 0.00 0.00) radii=11.00,11.00 11.00,11.00"
            + " border=2.00"); // the ring: 1px off the box plus its 2px width, its radii following
        a.state(UiState.DISABLED, true);
        f.mouse(20, 20, Pointer.PRIMARY);
        f.mouse(20, 20, 0);
        assertThat(clicks).containsExactly("a");
        assertThat(background(f.ui, a)).isEqualTo(Paint.box(Color.hex("#101010")));
    }

    @Test
    void layersDrawIntoTheirOwnListsAndAModalLayerBlocksTheHud() {
        Frame f = new Frame();
        Element a = f.button(f.ui.root());
        List<String> clicks = new ArrayList<>();
        f.ui.on(a, EventType.CLICK, e -> clicks.add("hud"));
        f.run();
        assertThat(f.ui.layerCount()).isEqualTo(1);
        DrawList hud = f.ui.layers()[0];
        Layer popup = f.ui.layer(Layers.POPUP);
        Element menu = f.button(popup.root());
        f.ui.on(menu, EventType.CLICK, e -> clicks.add("menu"));
        f.run();
        assertThat(f.ui.layerCount()).isEqualTo(2);
        assertThat(f.ui.layers()[1]).isSameAs(popup.drawList());
        assertThat(DrawListDump.of(popup.drawList())).startsWith("box 50.00,50.00 150.00,90.00");
        assertThat(f.ui.layers()[0]).isSameAs(hud);
        f.mouse(60, 60, Pointer.PRIMARY).mouse(60, 60, 0);
        f.mouse(20, 20, Pointer.PRIMARY).mouse(20, 20, 0);
        assertThat(clicks).containsExactly("menu", "hud");
        assertThat(f.ui.interaction().overUi(SyntheticInput.MOUSE)).isTrue();
        f.mouse(700, 500, 0);
        assertThat(f.ui.interaction().overUi(SyntheticInput.MOUSE)).isFalse(); // the game gets this pointer

        menu.detach(); // the pop-up closes: its layer records nothing, the HUD is not touched
        Layer modal = f.ui.layer(Layers.MODAL);
        Element dialog = f.button(modal.root());
        f.ui.interaction().focusable(dialog, true);
        f.run().run(); // laid out in the first, focused in the second
        assertThat(f.ui.interaction().focused(0)).isEqualTo(dialog); // trapped in the modal layer
        f.mouse(20, 20, Pointer.PRIMARY).mouse(20, 20, 0);
        assertThat(clicks).containsExactly("menu", "hud"); // the HUD is blocked
        assertThat(f.ui.interaction().overUi(SyntheticInput.MOUSE)).isTrue();
        assertThat(Semantics.of(f.ui.store(), f.ui.interaction()).toString()).contains("NONE +activate", "+focus");
        dialog.semantics(Role.DIALOG, "Quit?");
        assertThat(Semantics.unnamed(Semantics.of(f.ui.store(), f.ui.interaction()))).hasSize(1); // the HUD button
    }

    @Test
    void clippingContainersCutTheirContentInDrawingAndPicking() {
        Frame f = new Frame();
        Element view = f.ui.panel("view");
        f.ui.root().add(view);
        Element inside = f.ui.panel("inside");
        view.add(inside);
        List<String> hits = new ArrayList<>();
        f.ui.on(inside, EventType.PRESS, e -> hits.add(e.localX() + "," + e.localY()));
        f.run();
        assertThat(DrawListDump.of(f.ui.layers()[0])).contains("box clip=1 10.00,10.00 110.00,310.00");
        assertThat(f.ui.layers()[0].clipCount()).isEqualTo(2);
        f.mouse(50, 50, Pointer.PRIMARY).mouse(50, 50, 0);
        f.mouse(50, 200, Pointer.PRIMARY).mouse(50, 200, 0); // drawn there, but clipped away
        f.mouse(11, 108, Pointer.PRIMARY).mouse(11, 108, 0); // in the view's rounded corner
        assertThat(hits).containsExactly("40.0,40.0");
        // the clip follows its owner
        f.ui.layout().padding(f.ui.store().rootSlot(), 30, 0, 0, 30);
        f.run();
        float[] clips = f.ui.layers()[0].clips();
        assertThat(clips[DrawList.CLIP_FLOATS]).isEqualTo(30);
        assertThat(clips[DrawList.CLIP_FLOATS + 3]).isEqualTo(130);
    }

    @Test
    void dragPreviewsFollowThePointerOnTheDragLayerAndDropsChangeStyles() {
        Frame f = new Frame();
        DataFormat<String> name = DataFormat.of("ui-test.name", String.class);
        Element source = f.button(f.ui.root());
        Element target = f.button(f.ui.root());
        Element icon = f.ui.panel("icon");
        List<String> dropped = new ArrayList<>();
        f.ui.on(source, EventType.DRAG_START, e -> e.startDrag(DragOperation.COPY).put(name, "x").preview(icon, 8, 8));
        f.ui.on(target, EventType.DROP_OVER, e -> e.acceptDrop(DragOperation.ALL));
        f.ui.on(target, EventType.DROP, e -> dropped.add(e.drag().get(name) + " " + e.dropOperation()));
        f.run();
        f.mouse(20, 20, Pointer.PRIMARY).mouse(40, 20, Pointer.PRIMARY);
        Layer drag = f.ui.layerStack().find(Layers.DRAG);
        assertThat(icon.parent()).isEqualTo(drag.root());
        assertThat(f.ui.layout().x(icon.slot())).isEqualTo(32);
        f.mouse(150, 30, Pointer.PRIMARY);
        assertThat(f.ui.layout().x(icon.slot())).isEqualTo(142);
        assertThat(f.ui.layout().y(icon.slot())).isEqualTo(22);
        assertThat(target.is(UiState.DROP_TARGET)).isTrue();
        assertThat(background(f.ui, target)).isEqualTo(Paint.box(Color.hex("#00ff00")));
        assertThat(f.ui.interaction().pickable(icon.slot())).isFalse(); // previews are never under the pointer
        f.mouse(150, 30, 0);
        assertThat(dropped).containsExactly("x 2");
        f.run();
        assertThat(icon.parent()).isNull();
        assertThat(target.is(UiState.DROP_TARGET)).isFalse();
    }

    @Test
    void theImeAreaIsTheFocusedFieldsRectangleAndFollowsLayout() {
        Frame f = new Frame();
        Element spacer = f.button(f.ui.root());
        Element field = f.button(f.ui.root());
        List<String> calls = new ArrayList<>();
        f.ui.interaction().textInputHost(new TextInputHost() {
            @Override
            public void start(float x, float y, float width, float height) {
                calls.add(x + "," + y + " " + width + "x" + height);
            }

            @Override
            public void stop() {
                calls.add("stop");
            }
        });
        f.ui.interaction().textInput(field, true);
        f.run();
        f.ui.interaction().focus(field);
        f.run();
        f.ui.layout().width(spacer.slot(), 50, com.truesim.ui.layout.Unit.PX);
        f.run();
        assertThat(calls).containsExactly("120.0,10.0 100.0x40.0", "70.0,10.0 100.0x40.0");
    }

    @Test
    void framesWithPointersMovingAndCommandsArrivingAllocateNothing() {
        Frame f = new Frame();
        Element[] buttons = new Element[6];
        int[] clicks = new int[1];
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = f.button(f.ui.root());
            f.ui.interaction().focusable(buttons[i], true);
            f.ui.on(buttons[i], EventType.CLICK, e -> clicks[0]++);
        }
        Layer popup = f.ui.layer(Layers.POPUP);
        Element menu = f.button(popup.root());
        f.ui.on(menu, EventType.WHEEL, e -> e.consume());
        f.run();
        int[] step = new int[1];
        Runnable frame = () -> {
            int s = step[0]++;
            float x = 20 + (s % 7) * 110;
            f.input.beginFrame();
            f.input.pointer(SyntheticInput.MOUSE, Pointer.Kind.MOUSE).moveTo(x, 20 + s % 3)
                .buttons((s & 4) != 0 ? Pointer.PRIMARY : 0).scroll(0, s % 5 == 0 ? 1 : 0);
            f.input.pointer(1, Pointer.Kind.TOUCH).moveTo(800 - x, 30).buttons(s % 2 == 0 ? Pointer.PRIMARY : 0);
            f.input.command(s % 3 == 0 ? UiCommand.NEXT : UiCommand.RIGHT, 0, 0);
            if (s % 4 == 0) {
                f.input.command(UiCommand.ACCEPT, 0, 0);
            }
            f.ui.update(800, 600, f.input, f.now);
            f.now += 16_000_000L;
        };
        for (int i = 0; i < 2_000; i++) {
            frame.run(); // every state and style variant seen once
        }
        AllocationAssertions.assertAllocationFree(frame);
        assertThat(clicks[0]).isPositive();
        assertThat(f.ui.tunables()).containsAll(Interaction.tunables());
    }
}
