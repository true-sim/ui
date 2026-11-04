package com.truesim.ui.props;

import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.core.Element;
import com.truesim.ui.props.annotations.Angle;
import com.truesim.ui.props.annotations.Color;
import com.truesim.ui.props.annotations.ElementLabel;
import com.truesim.ui.props.annotations.EnumStyle;
import com.truesim.ui.props.annotations.Field;
import com.truesim.ui.props.annotations.Foldout;
import com.truesim.ui.props.annotations.Group;
import com.truesim.ui.props.annotations.Label;
import com.truesim.ui.props.annotations.ListStyle;
import com.truesim.ui.props.annotations.MaxSize;
import com.truesim.ui.props.annotations.MinSize;
import com.truesim.ui.props.annotations.Order;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.ReadOnly;
import com.truesim.ui.props.annotations.ShowIf;
import com.truesim.ui.props.annotations.Slider;
import com.truesim.ui.props.annotations.Step;
import com.truesim.ui.props.annotations.Tooltip;
import com.truesim.ui.props.annotations.Unique;
import com.truesim.ui.props.annotations.Unit;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Direction;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Length;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A gallery of generated property panels for screenshots and theme checks (design 5.11): a squad record with groups,
 * every shipped editor (numbers with ranges, units and steps, a slider, an angle, a colour, enums as a drop-down and
 * as a segmented control, text, a read-only value, a conditional field), a nested record, and collections in each
 * kind: waypoints (records) as a table, orders (a sealed type) as foldouts, patrols as master-detail, tags (a set of
 * enums) as chips, notes compact, and stats (a map). Its records are compiled with the annotation processor
 * ({@code SquadProperties} and friends). Build it in a UI of about 1280 x 960 and let it settle.
 *
 * <pre>{@code
 * PropsGallery g = PropsGallery.build(new Controls(ui), ui.root());
 * }</pre>
 */
public final class PropsGallery {

    /** How a squad behaves. */
    public enum Behaviour {
        /** Walks its waypoints. */
        PATROL,
        /** Holds its place. */
        GUARD,
        /** Hunts. */
        @Label("Search")
        SEARCH
    }

    /** A place on the map. */
    public record Position(@Step(0.5) @Unit("m") double x, @Step(0.5) @Unit("m") double y) {
    }

    /** A point of a route. */
    public record Waypoint(@Order(-1) String name, @Range(min = 0, max = 20) @Slider double speed, boolean pause) {
        /** The waypoint a new row starts with. */
        public static final Waypoint DEFAULTS = new Waypoint("New", 5, false);
    }

    /** An order: one of the permitted kinds. */
    public sealed interface Command permits Move, Hold {
    }

    /** Go somewhere. */
    @Label("Move")
    public record Move(@Label("To") String target, @Range(min = 0, max = 10) @Field double speed) implements Command {
    }

    /** Wait a while. */
    @Label("Hold")
    public record Hold(@Range(min = 0, max = 120) @Unit("s") double seconds) implements Command {
    }

    /** A patrol route. */
    public record Patrol(String name, @Range(min = 1, max = 9) int guards, Behaviour mode) {
    }

    /** The edited record. */
    public record Squad(
        @Group("Identity") @Order(0) String name,
        @Group("Identity") @EnumStyle(EnumStyle.Kind.SEGMENTED) Behaviour behaviour,
        @Group("Identity") @ReadOnly int id,
        @Group("Look") @Color long tint,
        @Group("Look") @Range(min = 0, max = 1) float roughness,
        @Group("Look") @Angle @Tooltip("Facing, clockwise from north") double heading,
        @Group("Look") boolean outline,
        @Group("Look") @ShowIf("outline") @Range(min = 0, max = 8) @Unit("px") int outlineWidth,
        @Group("Place") @Foldout Position home,
        @Group("Routes") @MinSize(1) @MaxSize(6) @ElementLabel("name") List<Waypoint> waypoints,
        @Group("Routes") @ListStyle(ListStyle.FOLDOUTS) List<Command> orders,
        @Group("Routes") @ListStyle(ListStyle.MASTER_DETAIL) @ElementLabel("name") List<Patrol> patrols,
        @Group("Tags") Set<Behaviour> tags,
        @Group("Tags") @Unique @ListStyle(ListStyle.COMPACT) List<String> notes,
        @Group("Tags") Map<String, Integer> stats) {
    }

    /** A squad with something in every field. */
    public static Squad sample() {
        return new Squad("Red", Behaviour.PATROL, 17, com.truesim.ui.core.draw.Rgba.srgb(255, 92, 92, 1), 0.35f, 0.5, true, 2, new Position(12, -4),
            List.of(new Waypoint("Gate", 4, false), new Waypoint("Tower", 7.5, true), new Waypoint("Well", 3, false)),
            List.of(new Move("Gate", 4), new Hold(30)),
            List.of(new Patrol("North", 3, Behaviour.PATROL), new Patrol("East", 2, Behaviour.GUARD)),
            Set.of(Behaviour.GUARD), List.of("Night shift", "Radio"), Map.of("kills", 3));
    }

    /** The holder of the edited squad. */
    public final VersionedValue<Squad> squad;
    /** The squad's panel. */
    public final PropertyPanel<Squad> panel;
    /** A second panel of the same squad laid out as a grid of tiles (a few properties). */
    public final PropertyPanel<Position> home;

    private PropsGallery(VersionedValue<Squad> squad, PropertyPanel<Squad> panel, PropertyPanel<Position> home) {
        this.squad = squad;
        this.panel = panel;
        this.home = home;
    }

    /** Builds the gallery into {@code parent}. */
    public static PropsGallery build(Controls controls, Element parent) {
        Ui ui = controls.ui();
        Element root = ui.panel("hud");
        ui.styles().inline(root.slot(), InlineStyle.builder().set(Styles.LAYOUT, LayoutKind.FLEX)
            .set(Styles.DIRECTION, Direction.ROW).set(Styles.GAP, 16f).set(Styles.ALIGN_ITEMS,
                com.truesim.ui.style.value.Align.START).build());
        parent.add(root);
        VersionedValue<Squad> squad = controls.clock().value(sample());
        PropertyPanel<Squad> panel = PropertyBuilder.forType(Squad.class).build(controls, squad);
        Element left = ui.panel(null);
        ui.styles().inline(left.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(700)).build());
        left.add(panel.element());
        root.add(left);
        VersionedValue<Position> where = controls.clock().value(new Position(3, 4));
        PropertyPanel<Position> home = PropertyBuilder.forType(Position.class).layout(Layouts.GRID)
            .build(controls, where);
        root.add(home.element());
        return new PropsGallery(squad, panel, home);
    }
}
