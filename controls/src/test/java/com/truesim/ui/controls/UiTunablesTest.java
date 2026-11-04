package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Tunable;
import com.truesim.ui.style.tss.Tss;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** {@link Ui#tunables()} lists every area's tunables once: an engine registers all of them as settings from it. */
class UiTunablesTest {

    @Test
    void everyStaticTunableOfTheUiModulesIsListedOnce() throws Exception {
        Ui ui = new Ui(Tss.defaultTheme(), UiTest.jdkText());
        List<Tunable> listed = ui.tunables();
        Set<String> names = new HashSet<>();
        for (Tunable t : listed) {
            assertThat(names.add(t.name())).as("%s listed once", t.name()).isTrue();
        }
        List<String> declared = new ArrayList<>();
        for (Class<?> anchor : new Class<?>[] {Ui.class, Tunable.class, com.truesim.ui.layout.Layout.class,
            com.truesim.ui.style.StyleResolver.class, com.truesim.ui.text.TextSystem.class,
            com.truesim.ui.charts.ChartTunables.class}) {
            for (Tunable t : staticTunables(anchor)) {
                declared.add(t.name());
                assertThat(listed).as("Ui.tunables() lists %s", t.name()).contains(t);
            }
        }
        assertThat(declared).contains("ui.animation.speed", "ui.menu.submenuDelay", "ui.scroll.friction",
            "ui.list.typeAheadTimeout", "ui.text.caretBlink", "ui.input.numberDragPixels", "ui.window.snapDistance",
            "ui.tooltip.warmTime", "ui.hover.openDelay", "ui.input.tooltipDelay", "ui.scale", "ui.chart.pyramidBlock");
    }

    /** The static {@link Tunable} fields of every class in the module (class directory) of {@code anchor}. */
    private static List<Tunable> staticTunables(Class<?> anchor) throws IOException, URISyntaxException,
        ClassNotFoundException, IllegalAccessException {
        Path root = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Tunable> out = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.toString().endsWith(".class"))
                    .forEach(f -> classes.add(root.relativize(f).toString().replace(java.io.File.separatorChar, '/')));
            }
        } else {
            try (JarFile jar = new JarFile(root.toFile())) {
                jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class") && !n.contains("module-info"))
                    .forEach(classes::add);
            }
        }
        for (String file : classes) {
            String name = file.replace('/', '.');
            Class<?> c = Class.forName(name.substring(0, name.length() - ".class".length()), false,
                anchor.getClassLoader());
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == Tunable.class) {
                    field.setAccessible(true);
                    out.add((Tunable) field.get(null));
                }
            }
        }
        return out;
    }
}
