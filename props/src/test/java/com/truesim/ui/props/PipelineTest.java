package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.basic.Collapsible;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.props.PropsGallery.Behaviour;
import com.truesim.ui.props.PropsGallery.Position;
import com.truesim.ui.props.PropsGallery.Squad;
import com.truesim.ui.props.annotations.AssetType;
import com.truesim.ui.props.annotations.EnumStyle;
import com.truesim.ui.props.annotations.Multiline;
import com.truesim.ui.props.annotations.Password;
import com.truesim.ui.props.annotations.Range;
import com.truesim.ui.props.annotations.Validate;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.SourceLoader;
import com.truesim.ui.style.tss.Tss;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The builder pipeline (stage 13): live binding, each step replaced, styles choosing, code overriding, edits. */
class PipelineTest {

    /** A shipped theme with {@code extra} rules after it. */
    static StyleSheet themeWith(String theme, String extra) {
        SourceLoader cp = SourceLoader.classpath(Tss.class.getClassLoader(), "");
        String dir = Tss.THEMES + "/";
        String name = dir + "with-extra.tss";
        return Tss.compile(path -> path.equals(name) ? cp.read(dir + theme) + "\n" + extra : cp.read(path), name)
            .sheet();
    }

    /** The first element of {@code type} under {@code root} (depth first), or null. */
    static Element find(Element root, String type) {
        UiStore s = root.store();
        if (type.equals(root.type())) {
            return root;
        }
        for (int c = s.firstChild(root.slot()); c != UiStore.NONE; c = s.nextSibling(c)) {
            Element f = find(s.element(c), type);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <C> Binding<Object, C> binding(PropertyPanel<?> panel, String property) {
        return (Binding<Object, C>) panel.row(property).bindings().getFirst();
    }

    @Test
    void editsAndModelChangesFlowBothWays() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        PropertyPanel<Squad> p = g.panel;
        Binding<Object, Double> roughness = binding(p, "roughness");
        assertThat(roughness.control().get()).isCloseTo(0.35, org.assertj.core.data.Offset.offset(1e-6));
        g.squad.set(withRoughness(g.squad.get(), 0.8f)); // elsewhere: a script, undo
        r.run();
        assertThat(roughness.control().get()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-6));
        roughness.control().set(0.6); // the slider moved
        r.run();
        assertThat(g.squad.get().roughness()).isEqualTo(0.6f);

        Binding<Object, Double> heading = binding(p, "heading"); // degrees over radians
        assertThat(heading.control().get()).isCloseTo(28.6, org.assertj.core.data.Offset.offset(0.05));
        heading.control().set(90.0);
        r.run();
        assertThat(g.squad.get().heading()).isCloseTo(Math.PI / 2, org.assertj.core.data.Offset.offset(1e-3));

        Element name = find(p.row("name").editor().element(), "textfield");
        r.ui.interaction().focus(name);
        r.run().press(UiCommand.END).type(" team").press(UiCommand.SUBMIT);
        assertThat(g.squad.get().name()).isEqualTo("Red team");

        Element outline = p.row("outlineWidth").element();
        assertThat(r.ui.layout().shown(outline.slot())).isTrue();
        r.click(find(p.row("outline").editor().element(), "checkbox"));
        assertThat(g.squad.get().outline()).isFalse();
        r.run();
        assertThat(p.row("outlineWidth").shown()).isFalse(); // @ShowIf("outline")
        assertThat(r.ui.layout().shown(outline.slot())).isFalse();

        Binding<Object, Integer> behaviour = binding(p, "behaviour"); // a segmented control's index
        behaviour.control().set(2);
        r.run();
        assertThat(g.squad.get().behaviour()).isEqualTo(Behaviour.SEARCH);
        assertThat(p.row("behaviour").editorName()).isEqualTo("segmented");

        Binding<Object, Long> tint = binding(p, "tint");
        tint.control().set(com.truesim.ui.core.draw.Rgba.WHITE);
        r.run();
        assertThat(g.squad.get().tint()).isEqualTo(com.truesim.ui.core.draw.Rgba.WHITE);

        assertThat(p.row("id").readOnly()).isTrue();
        assertThat(p.row("id").editor().element().is(UiState.DISABLED)).isTrue();
        assertThat(p.row("id").element().is(UiState.READ_ONLY)).isTrue();

        PropertyPanel<?> home = nested(p, "home");
        Binding<Object, Double> x = binding(home, "x");
        x.control().set(1.5);
        r.run();
        assertThat(g.squad.get().home()).isEqualTo(new Position(1.5, -4)); // a new root through the lens chain
        g.squad.set(withHome(g.squad.get(), new Position(9, 9)));
        r.run();
        assertThat(x.control().get()).isEqualTo(9.0);
        assertThat(home.path()).isEqualTo("home");
        assertThat(home.value()).isEqualTo(new Position(9, 9));
    }

    static PropertyPanel<?> nested(PropertyPanel<?> p, String property) {
        for (PropertyPanel<?> c : List.copyOf(childrenOf(p))) {
            if (c.path().equals(property)) {
                return c;
            }
        }
        throw new AssertionError("no nested panel " + property);
    }

    static List<PropertyPanel<?>> childrenOf(PropertyPanel<?> p) {
        return p.children();
    }

    static Squad withRoughness(Squad s, float v) {
        return PropsGallery_SquadProperties.withRoughness(s, v);
    }

    static Squad withHome(Squad s, Position v) {
        return PropsGallery_SquadProperties.withHome(s, v);
    }

    @Test
    void eachStepCanBeReplaced() {
        Rig r = new Rig();
        VersionedValue<Squad> squad = r.c.clock().value(PropsGallery.sample());
        // FILTER: only the name
        PropertyPanel<Squad> filtered = PropertyBuilder.forType(Squad.class)
            .replace(Steps.FILTER, (row, v) -> row.property().name().equals("name")).build(r.c, squad);
        r.add(filtered.element()).run();
        assertThat(filtered.rows()).filteredOn(PropertyRow::shown).extracting(row -> row.property().name())
            .containsExactly("name");
        // ORDER: by name, descending
        PropertyPanel<Squad> ordered = PropertyBuilder.forType(Squad.class).replace(Steps.ORDER,
            rows -> rows.sort((a, b) -> b.property().name().compareTo(a.property().name()))).build(r.c, squad);
        assertThat(ordered.ordered().getFirst().property().name()).isEqualTo("waypoints");
        // GROUP: everything in one
        PropertyPanel<Squad> grouped = PropertyBuilder.forType(Squad.class).replace(Steps.GROUP, row -> "All")
            .build(r.c, squad);
        assertThat(grouped.rows()).allMatch(row -> row.group().equals("All"));
        // EDITOR: everything read-only text
        PropertyPanel<Squad> plain = PropertyBuilder.forType(Squad.class).replace(Steps.EDITOR, (row, s) -> "readonly")
            .build(r.c, squad);
        r.add(plain.element()).run();
        assertThat(plain.rows()).allMatch(row -> row.editorName().equals("readonly"));
        assertThat(r.ui.text(plain.row("name").editor().element()).toString()).isEqualTo("Red");
        // LAYOUT: a strategy of our own
        List<String> arranged = new ArrayList<>();
        PropertyPanel<Squad> custom = PropertyBuilder.forType(Squad.class).replace(Steps.LAYOUT, c -> {
            arranged.add(c.name());
            for (LayoutStrategy.Section s : c.sections()) {
                for (PropertyRow row : s.rows()) {
                    c.root().add(row.element());
                }
            }
        }).build(r.c, squad);
        assertThat(arranged.getFirst()).isEqualTo("form"); // nested panels use the replaced step too
        assertThat(r.ui.store().parent(custom.row("name").element().slot())).isEqualTo(custom.element().slot());
        // BIND: a binder that counts and binds as the standard one
        int[] bound = {0};
        PropertyPanel<Squad> counted = PropertyBuilder.forType(Squad.class).replace(Steps.BIND,
            new Steps.Binder() {
                @Override
                public <C> Binding<Object, C> bind(PropertyRow row, com.truesim.ui.core.bind.Bindable<C> control,
                    com.truesim.ui.core.bind.Converter<Object, C> converter, Element invalid) {
                    bound[0]++;
                    return Steps.BIND.standard().bind(row, control, converter, invalid);
                }
            }).build(r.c, squad);
        assertThat(bound[0]).isGreaterThan(10);
        assertThat(counted.rows()).isNotEmpty();
        // removed steps: their neutral forms
        PropertyBuilder<Squad> neutral = PropertyBuilder.forType(Squad.class);
        for (Steps.Key<?> k : Steps.all()) {
            neutral.remove(k);
            assertThat(neutral.step(k)).isSameAs(k.neutral());
            assertThat(k.toString()).isEqualTo(k.name());
        }
        PropertyPanel<Squad> n = neutral.build(r.c, squad);
        assertThat(n.rows()).allMatch(row -> row.group().isEmpty());
        assertThat(n.row("roughness").editorName()).isEqualTo("number"); // by type only: annotations unused
        assertThat(n.ordered()).extracting(row -> row.property().index()).isSorted();
    }

    @Test
    void stylesChooseAndCodeWins() {
        String extra = """
            props.panel[model=Squad] { props-layout: inspector; }
            props.row[property=name] { editor: multiline; order: 99; }
            props.row[property=id] { visible: false; }
            props.row[property=roughness] { label-placement: top; }
            props.row[annotation=Range][property=outlineWidth] { editor: number; }
            """;
        Rig r = new Rig(themeWith("default-theme.tss", extra));
        VersionedValue<Squad> squad = r.c.clock().value(PropsGallery.sample());
        PropertyPanel<Squad> p = PropertyBuilder.forType(Squad.class).build(r.c, squad);
        r.add(p.element()).settle();
        assertThat(p.layout()).isEqualTo("inspector");
        assertThat(p.foldouts()).containsKeys("Identity", "Look");
        assertThat(p.row("name").editorName()).isEqualTo("multiline");
        assertThat(p.row("outlineWidth").editorName()).isEqualTo("number");
        assertThat(p.row("id").shown()).isFalse();
        assertThat(p.row("name").styledOrder()).isEqualTo(99);
        assertThat(p.ordered().getLast().property().name()).isEqualTo("name");
        assertThat(r.ui.layout().shown(p.row("roughness").label().slot())).isTrue();

        // code beats styles
        PropertyPanel<Squad> q = PropertyBuilder.forType(Squad.class).layout("compact").editor("name", "text")
            .visible("id", true).build(r.c, squad);
        r.add(q.element()).settle();
        assertThat(q.layout()).isEqualTo("compact");
        assertThat(q.row("name").editorName()).isEqualTo("text");
        assertThat(q.row("id").shown()).isTrue();

        // a theme switch alone rebuilds what changed, in the frame it applies
        r.ui.styles().sheet(Rig.DEFAULT);
        r.run();
        assertThat(p.layout()).isEqualTo("form");
        assertThat(p.row("name").editorName()).isEqualTo("text");
        assertThat(p.row("id").shown()).isTrue();
        p.dispose();
        assertThat(p.disposed()).isTrue();
        p.dispose();
        r.run();
    }

    @Test
    void layoutsArrangeGroups() {
        Rig r = new Rig();
        VersionedValue<Squad> squad = r.c.clock().value(PropsGallery.sample());
        for (String layout : List.of("form", "grid", "inspector", "compact", "row")) {
            PropertyPanel<Squad> p = PropertyBuilder.forType(Squad.class).layout(layout).build(r.c, squad);
            r.add(p.element()).run();
            assertThat(p.layout()).isEqualTo(layout);
            assertThat(r.ui.store().parent(p.row("name").element().slot())).isNotEqualTo(UiStore.NONE);
            p.dispose();
        }
        PropertyPanel<Position> one = PropertyBuilder.forType(Position.class).defaultLayout("inspector")
            .build(r.c, r.c.clock().value(new Position(1, 2)));
        assertThat(one.layout()).isEqualTo("inspector");
        assertThat(r.ui.store().parent(one.row("x").element().slot())).isEqualTo(one.element().slot());
        PropertyPanel<Position> unknown = PropertyBuilder.forType(Position.class).layout("nope")
            .build(r.c, r.c.clock().value(new Position(1, 2)));
        assertThat(unknown.layout()).isEqualTo("form");
        Collapsible look = PropertyBuilder.forType(Squad.class).layout("inspector").build(r.c, squad).foldouts()
            .get("Look");
        assertThat(look.expanded()).isTrue();
    }

    /** Things the shipped editors cover beyond the gallery. */
    public record Profile(
        @Multiline String bio,
        @Password String secret,
        @AssetType("texture") String avatar,
        @EnumStyle(EnumStyle.Kind.RADIO) Behaviour mode,
        @Validate(ModelsTest.NotBlank.class) String handle,
        @com.truesim.ui.props.annotations.Color int rgba,
        Object anything,
        @Range(min = 0, max = 5) long count,
        short small,
        Behaviour plain,
        @Fancy(level = 3) String custom,
        @com.truesim.ui.props.annotations.Slider int noRange,
        @com.truesim.ui.props.annotations.Angle(radians = false) float degrees) {
    }

    /** A custom annotation with a handler. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
    public @interface Fancy {
        /** How fancy. */
        int level();
    }

    @Test
    void shippedEditorsAndHandlers() {
        Rig r = new Rig();
        VersionedValue<Profile> v = r.c.clock().value(new Profile("hi", "pw", "tex/a", Behaviour.GUARD, "me",
            0x11223344, 42, 3, (short) 2, Behaviour.SEARCH, "c", 1, 90f));
        AnnotationHandler<Fancy> fancy = new AnnotationHandler<>() {
            @Override
            public Class<Fancy> type() {
                return Fancy.class;
            }

            @Override
            public void facts(Fancy a, Property<?, ?> property, List<String> facts) {
                facts.add("fancy");
                facts.add(Integer.toString(a.level()));
            }

            @Override
            public String editor(Fancy a, Property<?, ?> property) {
                return "password";
            }

            @Override
            public com.truesim.ui.core.bind.Validator<Object> validator(Fancy a, Property<?, ?> property) {
                return o -> o.toString().length() > a.level() ? "too fancy" : null;
            }
        };
        PropertyPanel<Profile> p = PropertyBuilder.forType(Profile.class).handler(fancy).build(r.c, v);
        r.add(p.element()).settle();
        assertThat(p.row("bio").editorName()).isEqualTo("multiline");
        assertThat(p.row("secret").editorName()).isEqualTo("password");
        assertThat(p.row("avatar").editorName()).isEqualTo("asset");
        assertThat(p.row("mode").editorName()).isEqualTo("radio");
        assertThat(p.row("rgba").editorName()).isEqualTo("color");
        assertThat(p.row("anything").editorName()).isEqualTo("readonly");
        assertThat(p.row("count").editorName()).isEqualTo("slider");
        assertThat(p.row("small").editorName()).isEqualTo("number");
        assertThat(p.row("plain").editorName()).isEqualTo("dropdown");
        assertThat(p.row("custom").editorName()).isEqualTo("password");
        assertThat(p.row("noRange").editorName()).isEqualTo("slider"); // without a range: a number field
        assertThat(p.row("degrees").editorName()).isEqualTo("angle");
        assertThat(r.ui.text(p.row("anything").editor().element()).toString()).isEqualTo("42");

        Binding<Object, Long> rgba = binding(p, "rgba"); // 8-bit sRGB int through the picker's colours
        assertThat(Editors.toInt(rgba.control().get())).isEqualTo(0x11223344);
        rgba.control().set(com.truesim.ui.core.draw.Rgba.WHITE);
        r.run();
        assertThat(v.get().rgba()).isEqualTo(0xffffffff);

        Binding<Object, Integer> mode = binding(p, "mode");
        assertThat(mode.control().get()).isEqualTo(1);
        mode.control().set(0);
        r.run();
        assertThat(v.get().mode()).isEqualTo(Behaviour.PATROL);
        Binding<Object, Integer> plain = binding(p, "plain");
        plain.control().set(1);
        r.run();
        assertThat(v.get().plain()).isEqualTo(Behaviour.GUARD);
        Binding<Object, Double> count = binding(p, "count");
        count.control().set(4.4);
        r.run();
        assertThat(v.get().count()).isEqualTo(4L);
        Binding<Object, Double> small = binding(p, "small");
        small.control().set(7.0);
        r.run();
        assertThat(v.get().small()).isEqualTo((short) 7);
        Binding<Object, Double> degrees = binding(p, "degrees");
        assertThat(degrees.control().get()).isEqualTo(90.0);

        Binding<Object, String> handle = binding(p, "handle");
        handle.control().set(" ");
        handle.control().touch();
        r.run();
        assertThat(v.get().handle()).isEqualTo("me"); // refused by @Validate
        assertThat(handle.message()).isEqualTo("blank");
        Binding<Object, String> custom = binding(p, "custom");
        custom.control().set("much too long");
        custom.control().touch();
        r.run();
        assertThat(v.get().custom()).isEqualTo("c");
        assertThat(custom.message()).isEqualTo("too fancy");
        v.set(new Profile("x", "y", "z", Behaviour.PATROL, "h", 0, "now", 1, (short) 1, Behaviour.PATROL, "d", 2,
            45f));
        r.run();
        assertThat(r.ui.text(p.row("anything").editor().element()).toString()).isEqualTo("now");
        assertThat(handle.control().get()).isEqualTo("h");
        assertThat(PropertyRow.typeName(p.row("small").property())).isEqualTo("short");
        assertThat(PropertyRow.typeName(p.row("anything").property())).isEqualTo("Object");
        assertThat(PropertyRow.kindName(p.row("anything").property())).isEqualTo("value");
        assertThat(p.builder().editors().names()).contains("number", "collection");
        assertThat(p.builder().handlers()).containsKey(Fancy.class);
        assertThat(p.toString()).contains("Profile");
    }

    @Test
    void editsAreCoalescedPerGestureAndUndone() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        List<Edit> edits = new ArrayList<>();
        g.panel.onEdit(edits::add);
        Squad start = g.squad.get();
        Binding<Object, Double> roughness = binding(g.panel, "roughness");
        r.synthetic.mouse(5, 5, Pointer.PRIMARY); // a drag: one gesture
        r.run();
        for (int i = 1; i <= 5; i++) {
            roughness.control().set(i / 10.0);
            r.run();
        }
        assertThat(edits).isEmpty();
        r.mouse(5, 5, 0);
        assertThat(edits).hasSize(1);
        Edit e = edits.getFirst();
        assertThat(e.path()).isEqualTo("roughness");
        assertThat(e.operation()).isEqualTo("set");
        assertThat(e.before()).isEqualTo(start);
        assertThat(((Squad) e.after()).roughness()).isEqualTo(0.5f);
        Binding<Object, Double> x = binding(nested(g.panel, "home"), "x");
        x.control().set(2.0);
        r.run();
        assertThat(edits).hasSize(2);
        assertThat(edits.get(1).path()).isEqualTo("home.x");
        edits.get(1).undo();
        edits.get(0).undo();
        r.run();
        assertThat(g.squad.get()).isEqualTo(start);
        assertThat(x.control().get()).isEqualTo(12.0);
        edits.get(0).redo();
        r.run();
        assertThat(g.squad.get().roughness()).isEqualTo(0.5f);
    }
}
