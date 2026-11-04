package com.truesim.ui.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.NodeEvent;
import com.truesim.scene.core.RoutedListener;
import com.truesim.scene.core.SceneEventType;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.input.Pointer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Interactive nodes routed by the scene's router (the engine's scene interaction): the UI's listeners hear their part
 * of the one route through a {@link RoutedListener}, and the UI no longer routes scene nodes itself.
 */
class RoutedBySceneTest {

    /** A routed event as the scene's router hands it on. */
    static final class Routed implements NodeEvent {
        SceneEventType type;
        Phase phase = Phase.TARGET;
        long target;
        long current;
        int pointerId;
        boolean consumed;

        Routed(SceneEventType type, long target, long current, int pointerId) {
            this.type = type;
            this.target = target;
            this.current = current;
            this.pointerId = pointerId;
        }

        @Override
        public SceneEventType type() {
            return type;
        }

        @Override
        public Phase phase() {
            return phase;
        }

        @Override
        public long target() {
            return target;
        }

        @Override
        public long currentNode() {
            return current;
        }

        @Override
        public boolean hasPointer() {
            return true;
        }

        @Override
        public int pointerId() {
            return pointerId;
        }

        @Override
        public int player() {
            return 0;
        }

        @Override
        public float x() {
            return 400;
        }

        @Override
        public float y() {
            return 300;
        }

        @Override
        public int button() {
            return type == SceneEventType.PRESS ? Pointer.PRIMARY : 0;
        }

        @Override
        public int buttons() {
            return 0;
        }

        @Override
        public int modifiers() {
            return 0;
        }

        @Override
        public int clickCount() {
            return type == SceneEventType.CLICK ? 2 : 0;
        }

        @Override
        public float scrollX() {
            return 0;
        }

        @Override
        public float scrollY() {
            return 0;
        }

        @Override
        public boolean hitPoint(double[] out) {
            out[2] = -9;
            return true;
        }

        @Override
        public double hitDistance() {
            return 9;
        }

        @Override
        public void consume() {
            consumed = true;
        }

        @Override
        public boolean consumed() {
            return consumed;
        }

        @Override
        public void capturePointer() {
        }

        @Override
        public void releasePointer() {
        }
    }

    @Test
    void theUisListenersHearTheirPartOfTheScenesRoute() {
        SceneRig r = ScenePickingTest.rig();
        long car = r.scene.create();
        long wheel = r.box(0, 0, -10, 1, Mobility.STATIC);
        r.scene.setParent(wheel, car);
        List<String> seen = new ArrayList<>();
        boolean[] take = {false};
        double[] point = new double[3];
        r.picking.routedByScene(true);
        r.picking.add(car, Interactive.of(EventType.CLICK, e -> {
            seen.add("car " + e.clickCount() + " " + (r.picking.targetNode(e) == car) + " " + e.phase() + " "
                + r.picking.hitPoint(e, point) + " " + r.picking.hitDistance(e));
            if (take[0]) {
                e.consume();
            }
        }).onCapture(EventType.PRESS.bit(), e -> seen.add("car capture " + e.phase())).tooltip("a car"));
        assertThat(r.picking.routedByScene()).isTrue();
        RoutedListener routed = r.scene.components().get(car, RoutedListener.TYPE);
        assertThat(routed).isNotNull();
        assertThat(routed.hears(SceneEventType.CLICK, NodeEvent.Phase.BUBBLE)).isTrue();
        assertThat(routed.hears(SceneEventType.PRESS, NodeEvent.Phase.CAPTURE)).isTrue();
        assertThat(routed.hears(SceneEventType.TOOLTIP, NodeEvent.Phase.BUBBLE)).as("its tooltip hook").isTrue();
        // the UI routes the node no more: a click through the UI's own input reaches nothing
        r.frame();
        r.click(400, 300);
        assertThat(seen).isEmpty();
        assertThat(r.picking.claimed(com.truesim.ui.testkit.SyntheticInput.MOUSE)).as("the scene's").isFalse();
        // the scene's router hands it on: the car is the wheel's interactive ancestor
        Routed click = new Routed(SceneEventType.CLICK, wheel, car, com.truesim.ui.testkit.SyntheticInput.MOUSE);
        click.phase = NodeEvent.Phase.BUBBLE;
        routed.listener().handle(click);
        assertThat(seen).containsExactly("car 2 true BUBBLE true 9.0");
        assertThat(click.consumed()).isFalse();
        take[0] = true;
        routed.listener().handle(click);
        assertThat(click.consumed()).as("the UI consumed: the router stops").isTrue();
        Routed press = new Routed(SceneEventType.PRESS, car, car, com.truesim.ui.testkit.SyntheticInput.MOUSE);
        press.phase = NodeEvent.Phase.CAPTURE;
        seen.clear();
        routed.listener().handle(press);
        assertThat(seen).containsExactly("car capture CAPTURE");
        // the proxy keeps hover and pressed
        routed.listener().handle(new Routed(SceneEventType.ENTER, car, car, 1));
        assertThat(r.picking.hovered(car)).isTrue();
        Routed p2 = new Routed(SceneEventType.PRESS, car, car, 1);
        routed.listener().handle(p2);
        assertThat(r.picking.element(car).is(UiState.PRESSED)).isTrue();
        routed.listener().handle(new Routed(SceneEventType.RELEASE, car, car, 1));
        routed.listener().handle(new Routed(SceneEventType.EXIT, car, car, 1));
        assertThat(r.picking.hovered(car)).isFalse();
        assertThat(r.picking.pressed(car)).isFalse();
        routed.listener().handle(new Routed(SceneEventType.CLICK, car, wheel, 1)); // a node not interactive: nothing
        // nodes of other hooks, or none; a target outside every interactive node stands for the current one
        long crate = r.box(5, 0, -10, 1, Mobility.STATIC);
        long bin = r.box(-5, 0, -10, 1, Mobility.STATIC);
        long rock = r.box(8, 0, -10, 1, Mobility.STATIC);
        long loose = r.box(-8, 0, -10, 1, Mobility.STATIC);
        com.truesim.ui.core.event.DataFormat<String> item = com.truesim.ui.core.event.DataFormat.of("item",
            String.class);
        r.picking.add(crate, Interactive.create().draggable(item, "crate", 1));
        r.picking.add(bin, Interactive.create().acceptDrops(1, item));
        r.picking.add(rock, Interactive.of(EventType.CLICK, e -> seen.add("rock " + (r.picking.targetNode(e) == rock))));
        assertThat(r.scene.components().get(crate, RoutedListener.TYPE).hears(SceneEventType.DRAG_START,
            NodeEvent.Phase.TARGET)).isTrue();
        assertThat(r.scene.components().get(rock, RoutedListener.TYPE).hears(SceneEventType.DRAG_START,
            NodeEvent.Phase.TARGET)).isFalse();
        seen.clear();
        r.scene.components().get(rock, RoutedListener.TYPE).listener().handle(new Routed(SceneEventType.CLICK, loose,
            rock, 1));
        assertThat(seen).containsExactly("rock true");
        // taken back: the UI routes the node again
        r.picking.routedByScene(false);
        assertThat(r.scene.components().has(car, RoutedListener.TYPE)).isFalse();
        r.picking.routedByScene(true);
        r.picking.remove(car);
        assertThat(r.scene.components().has(car, RoutedListener.TYPE)).isFalse();
    }
}
