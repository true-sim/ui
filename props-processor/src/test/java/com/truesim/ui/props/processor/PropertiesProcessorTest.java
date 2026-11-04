package com.truesim.ui.props.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.props.Property;
import com.truesim.ui.props.PropertyModel;
import com.truesim.ui.props.PropertyModels;
import com.truesim.ui.props.annotations.Label;
import com.truesim.ui.props.annotations.Range;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The processor on sources compiled in process: what it writes, and that the models it writes load and work. */
class PropertiesProcessorTest {

    @TempDir
    Path out;

    private static final String TYPES = """
        package p;

        import com.truesim.ui.props.annotations.*;
        import java.lang.annotation.*;
        import java.util.*;

        public class Types {

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
            public @interface Custom {
                int i() default 1;
                long l() default 2;
                float f() default 3;
                double d() default 4;
                char c() default 'x';
                byte b() default 5;
                short s() default 6;
                boolean z() default false;
                String str() default "a";
                Class<?> type() default Object.class;
                RetentionPolicy e() default RetentionPolicy.CLASS;
                String[] arr() default {};
                Label nested() default @Label("n");
            }

            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.RECORD_COMPONENT)
            public @interface Compiled {
            }

            public enum Mode { A, B }

            public record Rec(
                @Custom(i = 7, l = 8L, f = Float.POSITIVE_INFINITY, d = Double.NaN, c = '\\'', b = 1, s = 2, z = true,
                    str = "q\\"\\\\\\n\\t\\r\\u00e9", type = String.class, e = RetentionPolicy.RUNTIME,
                    arr = {"x", "y"}, nested = @Label("deep")) @Compiled @Range(min = 0, max = 1) double value,
                @Custom(f = Float.NaN, d = Double.POSITIVE_INFINITY) @Label("Count") int count,
                @Custom(f = Float.NEGATIVE_INFINITY, d = Double.NEGATIVE_INFINITY) long big,
                float ratio, boolean flag, char letter, short small, byte tiny,
                List<String> names, Map<String, Integer> scores, int[] numbers, Mode mode, String model) {
                public static final Rec DEFAULTS = new Rec(0.5, 1, 2, 3f, true, 'a', (short) 4, (byte) 5, List.of(),
                    Map.of(), new int[0], Mode.A, "m");
            }

            public sealed interface Shape permits Circle, Square, Shape.Poly {
                sealed interface Poly extends Shape permits Tri {
                }
            }

            @Label("Circle")
            public record Circle(@Range(min = 0, max = 9) double radius) implements Shape {
            }

            public record Square(@Label("Side") double side) implements Shape {
            }

            public record Tri(@Label("Base") double base) implements Shape.Poly {
            }

            public static class Bean {
                @Label("Level")
                private int level;
                private boolean on;
                @Label("Shown")
                public String text = "t";
                public final int fixed = 3;
                @Label("Hidden")
                private int secret;
                private List<String> tags = new ArrayList<>();

                public int getLevel() { return level; }
                public void setLevel(int v) { level = v; }
                public boolean isOn() { return on; }
                public void setOn(boolean v) { on = v; }
                public List<String> getTags() { return tags; }
            }

            public static class Child extends Bean {
                @Label("Extra")
                public double extra;
            }

            private record Hidden(@Label("x") int x) {
            }

            public record Box<T>(@Label("item") T item) {
            }

            @Inspectable
            public record Plain(int a) {
            }

            @Inspectable
            public static abstract class Abstract {
                @Label("A")
                public int a;
            }
        }
        """;

    private static final String UNNAMED = """
        import com.truesim.ui.props.annotations.*;

        public record Loose(@Label("L") int value) {
        }
        """;

    private record Source(String name, String text) {
    }

    private List<String> compile(Source... sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringWriter log = new StringWriter();
        List<JavaFileObject> files = new ArrayList<>();
        for (Source s : sources) {
            files.add(new SimpleJavaFileObject(java.net.URI.create("string:///" + s.name() + ".java"),
                JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return s.text();
                }
            });
        }
        List<String> notes = new ArrayList<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            boolean ok = javac.getTask(log, fm, d -> notes.add(d.getKind() + " " + d.getMessage(null)),
                List.of("-classpath", System.getProperty("java.class.path"), "-d", out.toString(), "-s",
                    out.toString(), "-processor", PropertiesProcessor.class.getName(), "-Xlint:all", "-Xlint:-processing", "-Werror"),
                null, files).call();
            assertThat(ok).as(notes + log.toString()).isTrue();
        }
        return notes;
    }

    @Test
    void writesWorkingModels() throws Exception {
        List<String> notes = compile(new Source("p/Types", TYPES), new Source("Loose", UNNAMED));
        assertThat(notes).anyMatch(n -> n.contains("p.Types.Hidden is not visible"));
        assertThat(notes).anyMatch(n -> n.contains("p.Types.Box is generic"));
        String rec = Files.readString(out.resolve("p/Types_RecProperties.java"));
        assertThat(rec).contains("MODEL_PROPERTY").contains("Double.NaN").contains("Float.POSITIVE_INFINITY")
            .contains("'\\''").contains("\\u00e9").doesNotContain("Compiled");
        assertThat(Files.exists(out.resolve("p/Types_HiddenProperties.java"))).isFalse();
        assertThat(Files.exists(out.resolve("Loose" + "Properties.java"))).isTrue();

        try (URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()},
            getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("p.Types$Rec");
            PropertyModel<?> m = (PropertyModel<?>) loader.loadClass("p.Types_RecProperties").getField("MODEL")
                .get(null);
            assertThat(m.source()).isEqualTo("generated");
            assertThat(m.properties()).extracting(Property::name).containsExactly("value", "count", "big", "ratio",
                "flag", "letter", "small", "tiny", "names", "scores", "numbers", "mode", "model");
            assertThat(m.property("names").elementType()).isEqualTo(String.class);
            assertThat(m.property("scores").keyType()).isEqualTo(String.class);
            assertThat(m.property("numbers").elementType()).isEqualTo(int.class);
            assertThat(m.property("count").label()).isEqualTo("Count");
            assertThat(m.property("value").annotation(Range.class).max()).isEqualTo(1);
            Object defaults = type.getField("DEFAULTS").get(null);
            assertThat(m.factory().get()).isSameAs(defaults);
            Class<? extends Annotation> custom = loader.loadClass("p.Types$Custom").asSubclass(Annotation.class);
            Annotation generated = m.property("value").annotation(custom);
            Annotation reflected = PropertyModels.reflection().model(type).property("value").annotation(custom);
            assertThat(generated).isEqualTo(reflected);
            assertThat(m.property("count").annotation(custom)).isEqualTo(PropertyModels.reflection().model(type)
                .property("count").annotation(custom));
            for (String n : List.of("count", "big")) {
                assertThat(m.property(n).annotation(custom)).isEqualTo(PropertyModels.reflection().model(type)
                    .property(n).annotation(custom));
            }
            Method str = custom.getMethod("str");
            assertThat(str.invoke(generated)).isEqualTo("q\"\\\n\t\ré");
            assertThat(((Label) custom.getMethod("nested").invoke(generated)).value()).isEqualTo("deep");

            @SuppressWarnings("unchecked")
            Property<Object, Object> count = (Property<Object, Object>) m.property("count");
            Object next = count.with(defaults, 9);
            assertThat(count.get(next)).isEqualTo(9);
            for (Property<?, ?> p : m.properties()) {
                @SuppressWarnings("unchecked")
                Property<Object, Object> q = (Property<Object, Object>) p;
                Object v = q.get(defaults);
                assertThat(q.get(q.with(defaults, v))).isEqualTo(v);
                if (p.type().isPrimitive()) {
                    assertThat(q.with(defaults, null)).isNotNull(); // null writes zero
                }
            }
            Method direct = loader.loadClass("p.Types_RecProperties").getMethod("withCount", type, int.class);
            assertThat(count.get(direct.invoke(null, defaults, 5))).isEqualTo(5);

            PropertyModel<?> shape = (PropertyModel<?>) loader.loadClass("p.Types_ShapeProperties").getField("MODEL")
                .get(null);
            assertThat(shape.subtypes()).extracting(Class::getSimpleName).containsExactly("Circle", "Square", "Tri");
            assertThat(shape.factory()).isNull();

            PropertyModel<?> bean = (PropertyModel<?>) loader.loadClass("p.Types_ChildProperties").getField("MODEL")
                .get(null);
            assertThat(bean.properties()).extracting(Property::name).containsExactly("level", "on", "text", "fixed",
                "tags", "extra");
            assertThat(bean.property("fixed").writable()).isFalse();
            assertThat(bean.property("tags").writable()).isFalse();
            assertThat(bean.property("tags").elementType()).isEqualTo(String.class);
            Object child = bean.factory().get();
            @SuppressWarnings("unchecked")
            Property<Object, Object> level = (Property<Object, Object>) bean.property("level");
            assertThat(level.with(child, 4)).isSameAs(child);
            assertThat(level.get(child)).isEqualTo(4);
            @SuppressWarnings("unchecked")
            Property<Object, Object> on = (Property<Object, Object>) bean.property("on");
            on.with(child, null);
            assertThat(on.get(child)).isEqualTo(false);
            @SuppressWarnings("unchecked")
            Property<Object, Object> text = (Property<Object, Object>) bean.property("text");
            text.with(child, "u");
            assertThat(text.get(child)).isEqualTo("u");

            PropertyModel<?> plain = (PropertyModel<?>) loader.loadClass("p.Types_PlainProperties").getField("MODEL")
                .get(null);
            assertThat(plain.factory().get()).isNotNull();
            PropertyModel<?> abs = (PropertyModel<?>) loader.loadClass("p.Types_AbstractProperties").getField("MODEL")
                .get(null);
            assertThat(abs.factory()).isNull();
            PropertyModel<?> loose = (PropertyModel<?>) loader.loadClass("LooseProperties").getField("MODEL").get(null);
            assertThat(loose.property("value").label()).isEqualTo("L");
            assertThat(new PropertyModels().of(type).source()).isEqualTo("generated");
            assertThat(PropertyModels.generatedName(loader.loadClass("Loose"))).isEqualTo("LooseProperties");
        }
        assertThat(PropertiesProcessor.escape("\u0001", '"')).isEqualTo("\\u0001");
        assertThat(new PropertiesProcessor().getSupportedSourceVersion()).isNotNull();
        assertThat(Map.of()).isEmpty();
    }
}
