package com.truesim.ui.console;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.testkit.SyntheticInput;

/**
 * The developer console on one screen (1280 x 720): open over a HUD with a help text whose links run console
 * commands ({@code cmd:}), its log filled with records of every level (a coalesced repeat, source positions, a filtered
 * logger chip), and {@code spawn 3 } typed on the command line with the completion pop-up, its first candidate
 * picked, and the command's signature with the argument at the caret marked.
 * Styled by the UI's sheet (the default or the test theme). For the console's tests and the engine's GPU screenshots.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * ConsoleGallery g = ConsoleGallery.build(ui, 1280, 720);  // runs frames until it settles
 * }</pre>
 */
public final class ConsoleGallery {

    /** The UI. */
    public final Ui ui;
    /** The console. */
    public final Console console;
    /** The settings standing in for the engine's. */
    public final FakeVariables variables = new FakeVariables();
    /** The help text with command links. */
    public final TextEditor help;
    /** The synthetic input the frames use. */
    public final SyntheticInput input = new SyntheticInput();
    private final UiInput frameInput = new UiInput(16);
    private final float width;
    private final float height;
    private long nanos = 1_000_000_000L;

    private ConsoleGallery(Ui ui, float width, float height) {
        this.ui = ui;
        this.width = width;
        this.height = height;
        console = Console.create(ui, "gallery", HistoryStore.memory());
        console.shell().variables(variables).clock(() -> 1_700_000_005_000L);
        console.shell().register(ConsoleCommand.named("spawn").describe("Spawns creatures in front of the camera")
            .arg(Arg.integer("count").range(1, 100).describe("how many"))
            .arg(Arg.choice("kind", "orc", "elf", "ogre").optional())
            .developer().cheat()
            .action(call -> call.print("spawned " + call.integer("count") + " " + call.text("kind", "orc"))));
        help = TextEditor.richText(ui, "console-help", "Console help", String.join("\n",
            "[b]Console[/b]: press [class=code]`[/class] to open it.",
            "Try [link=cmd:render.bloom OFF]bloom off[/link], [link=cmd:render.bloom CHEAP]bloom cheap[/link] or "
                + "[link=cmd:help]help[/link]."));
        help.listener(console.linkListener());
    }

    /** Builds the gallery into {@code ui} and runs frames until it settles. */
    public static ConsoleGallery build(Ui ui, float width, float height) {
        ConsoleGallery g = new ConsoleGallery(ui, width, height);
        g.fill();
        return g;
    }

    private void fill() {
        Element hud = ui.panel(null);
        ui.styles().inline(hud.slot(), InlineStyle.builder()
            .set(Styles.PADDING, new Insets(520, 24, 24, 24)).build());
        ui.styles().inline(help.element().slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(560))
            .set(Styles.HEIGHT, Length.px(80)).build());
        hud.add(help.element());
        ui.root().add(hud);
        LogRing log = console.log();
        long t = 1_700_000_000_000L;
        log.log(LogLevel.INFO, "com.truesim.engine.Engine", "Engine started: Vulkan 1.4, RX 9070 XT", "Engine.java:88",
            t);
        log.log(LogLevel.DEBUG, "com.truesim.render.Graph", "Frame graph compiled: 42 passes, 3 culled", null, t + 12);
        log.log(LogLevel.TRACE, "com.truesim.render.Graph", "pass ssao: 1920x1080 R8", null, t + 13);
        for (int i = 0; i < 3; i++) {
            log.log(LogLevel.WARN, "com.truesim.assets.Textures", "Texture rock_01.ktx2 missing, using the fallback",
                "Textures.java:211", t + 40);
        }
        log.log(LogLevel.ERROR, "com.truesim.render.Bloom", "Bloom PHYSICAL needs compute shaders this GPU lacks",
            "Bloom.java:57", t + 61);
        log.log(LogLevel.INFO, "com.truesim.net.Session", "Player Ada joined from 10.0.0.7", null, t + 900);
        frames(2);
        console.run("render.bloom");
        console.run("spawn 3 elf");
        console.open(true);
        frames(40); // the drop animation ends; the command line has the focus
        input.type("spawn 3 ");
        frames(1);
        input.press(UiCommand.HISTORY_DOWN); // picks the first candidate
        frames(60); // glyphs reach the atlas
    }

    /** Runs {@code n} frames of 16 ms with the synthetic input. */
    public ConsoleGallery frames(int n) {
        for (int i = 0; i < n; i++) {
            frameInput.beginFrame();
            input.poll(frameInput);
            ui.update(width, height, frameInput, nanos);
            nanos += 16_000_000L;
        }
        return this;
    }
}
