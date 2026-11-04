package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.props.PropsGallery.Squad;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * UI19: the annotation processor's generated models against reflection's (TRUESIM_BENCHMARK=true). First builds run
 * in a fresh JVM per technique ({@link #main}), so neither inherits the other's warmed JDK caches (class loading,
 * method handles, lambdas); per-access costs (a property's get and with) run here after warming. Prints the numbers.
 */
class ModelBenchmarkTest {

    /** Types with generated classes. */
    static final Class<?>[] TYPES = {Squad.class, PropsGallery.Waypoint.class, PropsGallery.Position.class,
        PropsGallery.Move.class, PropsGallery.Hold.class, PropsGallery.Patrol.class, PropsGallery.Command.class,
        PipelineTest.Profile.class, CollectionsTest.Route.class, ModelsTest.Light.class};

    /** In a fresh JVM: builds each type's model once by {@code args[0]} (generated or reflection), prints µs each. */
    public static void main(String[] args) throws Exception {
        PropertySource source = args[0].equals("generated") ? PropertyModels.generated() : PropertyModels.reflection();
        for (Class<?> t : TYPES) {
            Class.forName(t.getName(), true, t.getClassLoader()); // the type itself is loaded either way
        }
        StringBuilder out = new StringBuilder(args[0]);
        long total = 0;
        for (Class<?> t : TYPES) {
            long t0 = System.nanoTime();
            PropertyModel<?> m = source.model(t);
            long dt = System.nanoTime() - t0;
            total += dt;
            out.append(String.format(Locale.ROOT, " %s=%.0f", t.getSimpleName(), dt / 1e3));
            if (m == null) {
                throw new IllegalStateException("no model of " + t);
            }
        }
        out.append(String.format(Locale.ROOT, " total=%.0f", total / 1e3));
        long t0 = System.nanoTime();
        int n = 200;
        for (int i = 0; i < n; i++) {
            for (Class<?> t : TYPES) {
                source.model(t);
            }
        }
        out.append(String.format(Locale.ROOT, " again=%.1f", (System.nanoTime() - t0) / 1e3 / n / TYPES.length));
        System.out.println(out);
    }

    private static String fresh(String technique) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            ModelBenchmarkTest.class.getName(), technique).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
            StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                out.append(line);
            }
        }
        assertThat(p.waitFor()).as(out.toString()).isZero();
        return out.toString();
    }

    @Test
    void firstBuildsAndAccess() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRUESIM_BENCHMARK")));
        List<String> lines = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            lines.add(fresh("generated"));
            lines.add(fresh("reflection"));
        }
        lines.forEach(l -> System.out.println("UI19 first build (µs): " + l));

        PropertyModel<Squad> g = PropertyModels.generated().model(Squad.class);
        PropertyModel<Squad> r = PropertyModels.reflection().model(Squad.class);
        Squad s = PropsGallery.sample();
        for (String technique : new String[] {"generated", "reflection", "generated", "reflection"}) {
            PropertyModel<Squad> m = technique.equals("generated") ? g : r;
            @SuppressWarnings("unchecked")
            Property<Squad, Object> roughness = (Property<Squad, Object>) m.property("roughness");
            @SuppressWarnings("unchecked")
            Property<Squad, Object> name = (Property<Squad, Object>) m.property("name");
            long[] gets = new long[15];
            long[] withs = new long[15];
            long sink = 0;
            for (int round = 0; round < gets.length; round++) {
                int n = 1_000_000;
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    sink += ((Float) roughness.get(s)).hashCode() + name.get(s).hashCode();
                }
                gets[round] = (System.nanoTime() - t0) / (2L * n / 1000); // ps per get
                int k = 200_000;
                Squad x = s;
                t0 = System.nanoTime();
                for (int i = 0; i < k; i++) {
                    x = roughness.with(x, (float) (i & 7));
                }
                withs[round] = (System.nanoTime() - t0) / (k / 1000);
                sink += x.hashCode();
            }
            Arrays.sort(gets);
            Arrays.sort(withs);
            System.out.printf(Locale.ROOT, "UI19 access %s: get %.2f ns, with %.1f ns (median of %d)%s%n", technique,
                gets[gets.length / 2] / 1e3, withs[withs.length / 2] / 1e3, gets.length, sink == 42 ? "!" : "");
        }
    }
}
