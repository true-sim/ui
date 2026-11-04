package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.Tunable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Running commands: typed arguments, permissions, settings as variables (round trips), aliases, scripts. */
class ConsoleShellTest {

    private final LogRing log = new LogRing(256);
    private final ConsoleShell shell = new ConsoleShell(log);
    private final FakeVariables vars = new FakeVariables();
    private final List<String> spawned = new ArrayList<>();

    ConsoleShellTest() {
        shell.variables(vars);
        shell.register(ConsoleCommand.named("spawn").describe("Spawns creatures")
            .arg(Arg.integer("count").range(1, 100))
            .arg(Arg.choice("kind", "orc", "elf").optional())
            .developer().cheat()
            .action(call -> spawned.add(call.integer("count") + " " + call.text("kind", "orc"))));
        shell.register(ConsoleCommand.named("scale")
            .arg(Arg.decimal("factor").range(0.25, 4))
            .arg(Arg.bool("animate").optional())
            .action(call -> spawned.add(call.decimal("factor") + " " + (call.has("animate") && call.bool("animate")))));
        shell.register(ConsoleCommand.named("say").arg(Arg.text("text").rest())
            .action(call -> call.print("said " + call.text("text"))));
        shell.register(ConsoleCommand.named("boom").action(call -> {
            throw new IllegalStateException("kaboom");
        }));
    }

    private String last() {
        return log.last().message().toString();
    }

    private int lastLevel() {
        return log.last().level();
    }

    @Test
    void typedArgumentsAreCheckedBeforeTheActionRuns() {
        assertThat(shell.execute("spawn 3 ELF")).isTrue();
        assertThat(shell.execute("spawn 7")).isTrue();
        assertThat(spawned).containsExactly("3 elf", "7 orc");
        assertThat(shell.execute("spawn 0")).isFalse();
        assertThat(last()).isEqualTo("spawn: count: 0 is not in 1..100; usage: spawn <count: 1..100> [kind: orc|elf]");
        assertThat(lastLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(shell.execute("spawn x")).isFalse();
        assertThat(last()).contains("x is not a whole number");
        assertThat(shell.execute("spawn 99999999999")).isFalse();
        assertThat(shell.execute("spawn 2 dwarf")).isFalse();
        assertThat(last()).contains("dwarf is not one of orc, elf");
        assertThat(shell.execute("spawn")).isFalse();
        assertThat(last()).contains("count is missing");
        assertThat(shell.execute("spawn 1 orc extra")).isFalse();
        assertThat(last()).contains("too many arguments");
        assertThat(shell.execute("scale 1.5 on; scale 2")).isTrue();
        assertThat(spawned).endsWith("1.5 true", "2.0 false");
        assertThat(shell.execute("scale nope")).isFalse();
        assertThat(shell.execute("scale NaN")).isFalse();
        assertThat(shell.execute("scale 9")).isFalse();
        assertThat(shell.execute("scale 1 maybe")).isFalse();
        assertThat(last()).contains("maybe is not on or off");
        assertThat(shell.execute("say hello   \"big world\"")).isTrue();
        assertThat(last()).isEqualTo("said hello big world");
        assertThat(shell.execute("boom")).isFalse();
        assertThat(last()).startsWith("boom failed: java.lang.IllegalStateException: kaboom");
    }

    @Test
    void permissionsSayWhyACommandMayNotRun() {
        shell.permissions(new ConsolePermissions() {
            @Override
            public boolean developer() {
                return true;
            }

            @Override
            public boolean cheats() {
                return false;
            }
        });
        assertThat(shell.execute("spawn 1")).isFalse();
        assertThat(last()).isEqualTo("spawn is a cheat and cheats are off");
        shell.permissions(ConsolePermissions.PLAYER);
        assertThat(shell.execute("spawn 1")).isFalse();
        assertThat(last()).isEqualTo("spawn is a developer command");
        assertThat(shell.complete("sp", 2).candidates()).isEmpty(); // not offered either
        shell.execute("help spawn");
        assertThat(last()).isEqualTo("spawn is a developer command");
        shell.permissions(null);
        assertThat(shell.permissions()).isSameAs(ConsolePermissions.ALL);
        assertThat(ConsolePermissions.ALL.cheats()).isTrue();
        assertThat(spawned).isEmpty();
    }

    @Test
    void settingsRoundTripAsVariables() {
        assertThat(shell.execute("render.bloom")).isTrue();
        assertThat(last()).isEqualTo("render.bloom = CHEAP (default CHEAP; one of OFF, CHEAP, PHYSICAL): Bloom technique");
        assertThat(shell.execute("render.bloom off")).isTrue();
        assertThat(vars.var("render.bloom").value()).isEqualTo("OFF");
        assertThat(last()).isEqualTo("render.bloom = OFF");
        // the constraint overlay refuses a value and says why
        assertThat(shell.execute("render.bloom PHYSICAL")).isFalse();
        assertThat(last()).isEqualTo("render.bloom: PHYSICAL needs compute shaders this GPU lacks");
        assertThat(lastLevel()).isEqualTo(LogLevel.WARN);
        assertThat(vars.var("render.bloom").value()).isEqualTo("OFF");
        vars.var("render.bloom").refused = null;
        assertThat(shell.execute("render.bloom PHYSICAL")).isTrue();
        assertThat(vars.var("render.bloom").value()).isEqualTo("PHYSICAL");
        assertThat(shell.execute("render.bloom ULTRA")).isFalse();
        assertThat(last()).isEqualTo("render.bloom: ULTRA is not one of OFF, CHEAP, PHYSICAL");
        assertThat(shell.execute("reset render.bloom")).isTrue();
        assertThat(vars.var("render.bloom").value()).isEqualTo("CHEAP");
        // numbers: range and kind
        assertThat(shell.execute("render.scale 1.5")).isTrue();
        assertThat(shell.execute("render.scale 3")).isFalse();
        assertThat(last()).isEqualTo("render.scale: 3 is not in 0.5..2");
        assertThat(shell.execute("render.scale abc")).isFalse();
        assertThat(shell.execute("render.scale")).isTrue();
        assertThat(last()).isEqualTo("render.scale = 1.5 (default 1; range 0.5..2): Resolution scale");
        // booleans, toggled
        assertThat(shell.execute("render.vsync off")).isTrue();
        assertThat(vars.var("render.vsync").value()).isEqualTo("false");
        assertThat(shell.execute("toggle render.vsync")).isTrue();
        assertThat(vars.var("render.vsync").value()).isEqualTo("true");
        assertThat(shell.execute("render.vsync sometimes")).isFalse();
        assertThat(shell.execute("toggle render.scale")).isFalse();
        // locked: availability says why, nothing changes
        assertThat(shell.execute("render.shadows 1024")).isFalse();
        assertThat(last()).isEqualTo("render.shadows: locked by the Low preset");
        assertThat(shell.execute("render.shadows 1024.5")).isFalse();
        assertThat(last()).contains("not a whole number");
        assertThat(shell.execute("render.shadows")).isTrue();
        assertThat(last()).endsWith("[unavailable: locked by the Low preset]");
        assertThat(vars.var("render.shadows").sets).isZero();
        // text, one value
        assertThat(shell.execute("game.name \"Ada L\"")).isTrue();
        assertThat(vars.var("game.name").value()).isEqualTo("Ada L");
        assertThat(shell.execute("game.name a b")).isFalse();
        assertThat(shell.execute("reset nothing")).isFalse();
        assertThat(last()).contains("no setting nothing");
    }

    @Test
    void tunablesAreVariables() {
        Tunable speed = Tunable.of("test.speed", 1.5f, 0, 4, "Speed");
        Tunable count = Tunable.of("test.count", 3, 1, 10, null);
        Tunable mode = Tunable.choice("test.mode", List.of("fast", "slow"), "fast", "Mode");
        shell.variables(ConsoleVariables.of(vars, new TunableVariables(List.of(speed, count, mode))));
        assertThat(shell.variables().names()).contains("render.bloom", "test.speed", "test.mode");
        assertThat(shell.execute("test.speed 2.5; test.mode SLOW; test.count 7")).isTrue();
        assertThat(speed.get()).isEqualTo(2.5f);
        assertThat(mode.option()).isEqualTo("slow");
        assertThat(count.get()).isEqualTo(7f);
        assertThat(shell.execute("test.count 2.5")).isFalse();
        assertThat(shell.execute("test.speed 5")).isFalse();
        assertThat(shell.execute("test.count")).isTrue();
        assertThat(last()).isEqualTo("test.count = 7 (default 3; range 1..10)");
        assertThat(shell.execute("test.mode")).isTrue();
        assertThat(last()).isEqualTo("test.mode = slow (default fast; one of fast, slow): Mode");
        ConsoleVariable v = shell.variables().find("test.speed");
        assertThat(v.kind()).isEqualTo(ConsoleVariable.Kind.DECIMAL);
        assertThat(v.set("9")).isEqualTo("9 is not in 0..4");
        assertThat(v.defaultValue()).isEqualTo("1.5");
        assertThat(shell.variables().find("test.mode").min()).isNaN();
        assertThat(shell.variables().find("nothing")).isNull();
        assertThat(ConsoleVariables.NONE.names()).isEmpty();
    }

    @Test
    void unknownCommandsSuggestTheNearestName() {
        assertThat(shell.execute("spwan 3")).isFalse();
        assertThat(last()).isEqualTo("Unknown command spwan; did you mean spawn?");
        assertThat(shell.execute("render.blom")).isFalse();
        assertThat(last()).endsWith("did you mean render.bloom?");
        assertThat(shell.execute("qqqqqqq")).isFalse();
        assertThat(last()).isEqualTo("Unknown command qqqqqqq");
    }

    @Test
    void aliasesRunTheirCommandsWithTheWordsAppended() {
        assertThat(shell.execute("alias low \"render.bloom OFF; render.scale\"")).isTrue();
        assertThat(shell.alias("low")).isEqualTo("render.bloom OFF; render.scale");
        assertThat(shell.execute("low 0.75")).isTrue();
        assertThat(vars.var("render.bloom").value()).isEqualTo("OFF");
        assertThat(vars.var("render.scale").value()).isEqualTo("0.75");
        shell.execute("alias");
        assertThat(last()).isEqualTo("low = render.bloom OFF; render.scale");
        shell.execute("alias low");
        assertThat(last()).isEqualTo("low = render.bloom OFF; render.scale");
        shell.execute("help low");
        assertThat(last()).isEqualTo("low is an alias of: render.bloom OFF; render.scale");
        assertThat(shell.execute("alias spawn x")).isFalse();
        assertThat(shell.execute("alias loop loop")).isTrue();
        assertThat(shell.execute("loop")).isFalse();
        assertThat(last()).contains("nest too deeply");
        assertThat(shell.execute("unalias low; unalias loop")).isTrue();
        assertThat(shell.aliases()).isEmpty();
        shell.execute("alias");
        assertThat(last()).isEqualTo("No aliases");
        shell.execute("alias nope");
        assertThat(last()).isEqualTo("nope is not an alias");
        assertThat(shell.execute("unalias spawn")).isFalse();
    }

    @Test
    void scriptsRunLineByLine() {
        Map<String, String> files = Map.of("autoexec.cfg", "// settings\nrender.bloom OFF\n\nrender.scale 0.5 // low",
            "broken.cfg", "render.scale 9\nrender.bloom OFF", "self.cfg", "exec self.cfg");
        shell.scripts(new ScriptSource() {
            @Override
            public String read(String path) {
                return files.get(path);
            }

            @Override
            public void list(String prefix, List<String> out) {
                files.keySet().stream().filter(f -> f.startsWith(prefix)).sorted().forEach(out::add);
            }
        });
        assertThat(shell.execute("exec autoexec.cfg")).isTrue();
        assertThat(vars.var("render.bloom").value()).isEqualTo("OFF");
        assertThat(vars.var("render.scale").value()).isEqualTo("0.5");
        vars.var("render.bloom").set("CHEAP");
        shell.execute("exec broken.cfg"); // stops at the failing line
        assertThat(vars.var("render.bloom").value()).isEqualTo("CHEAP");
        shell.execute("exec self.cfg");
        assertThat(last()).contains("nest too deeply");
        shell.execute("exec missing.cfg");
        assertThat(last()).isEqualTo("exec: no script missing.cfg");
        assertThat(shell.complete("exec a", 6).candidates()).extracting(Completion.Candidate::text)
            .containsExactly("autoexec.cfg");
        shell.scripts(null);
        shell.execute("exec autoexec.cfg");
        assertThat(last()).isEqualTo("exec: no script autoexec.cfg");
    }

    @Test
    void builtInsHelpAndFind() {
        shell.execute("help");
        assertThat(log.size()).isGreaterThan(8);
        assertThat(last()).startsWith("Settings are variables");
        shell.execute("help spawn");
        assertThat(log.get(log.size() - 1).message().toString())
            .isEqualTo("spawn <count: 1..100> [kind: orc|elf] - Spawns creatures");
        shell.execute("help help");
        assertThat(last()).isEqualTo("  [name: command] a command, alias or setting");
        shell.execute("help render.scale");
        assertThat(last()).startsWith("render.scale = 1");
        shell.execute("help zzz");
        assertThat(last()).contains("no command zzz");
        shell.execute("find bloom");
        assertThat(last()).startsWith("render.bloom = ");
        shell.execute("find spawns");
        assertThat(last()).startsWith("spawn <count");
        shell.execute("find xyzzy");
        assertThat(last()).isEqualTo("Nothing found");
        shell.execute("echo");
        assertThat(last()).isEmpty();
        shell.unregister("echo");
        assertThat(shell.command("echo")).isNull();
    }

    @Test
    void declarationsAreChecked() {
        assertThatThrownBy(() -> ConsoleCommand.named("two words")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsoleCommand.named("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsoleCommand.named("x").arg(Arg.text("a").rest()).arg(Arg.text("b")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsoleCommand.named("x").arg(Arg.text("a").optional()).arg(Arg.text("b")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Arg.text("t").range(0, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Arg.integer("n").rest()).isInstanceOf(IllegalStateException.class);
        assertThat(Arg.choice("mode", Thread.State.class).options()).contains("RUNNABLE", "BLOCKED");
        assertThat(Arg.decimal("x").signature()).isEqualTo("<x: number>");
        assertThat(Arg.integer("x").signature()).isEqualTo("<x: int>");
        assertThat(Arg.asset("t", "texture").optional().signature()).isEqualTo("[t: texture]");
        assertThat(Arg.node("n").signature()).isEqualTo("<n: node>");
        assertThat(Arg.text("t").signature()).isEqualTo("<t>");
        assertThat(Arg.decimal("x").range(0.5, 1.25).toString()).isEqualTo("<x: 0.5..1.25>");
        ConsoleCommand c = shell.command("spawn");
        assertThat(c.toString()).isEqualTo(c.usage());
        assertThat(c.tier()).isEqualTo(ConsoleCommand.Tier.DEVELOPER);
        assertThatThrownBy(() -> new LogRing(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invocationsReadArgumentsByName() {
        Invocation[] seen = new Invocation[1];
        shell.register(ConsoleCommand.named("probe").arg(Arg.integer("n").optional())
            .action(call -> seen[0] = call));
        shell.execute("probe");
        assertThat(seen[0].has("n")).isFalse();
        assertThat(seen[0].integer("n", 4)).isEqualTo(4);
        assertThat(seen[0].line()).isEqualTo("probe");
        assertThat(seen[0].command().name()).isEqualTo("probe");
        assertThatThrownBy(() -> seen[0].integer("n")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seen[0].has("m")).isInstanceOf(IllegalArgumentException.class);
        seen[0].warn("careful");
        assertThat(lastLevel()).isEqualTo(LogLevel.WARN);
        seen[0].error("bad");
        assertThat(lastLevel()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void assetsAndNodesAreCheckedThroughTheirProviders() {
        List<String> assets = List.of("textures/rock.ktx2", "textures/rock_normal.ktx2", "textures/sand.ktx2");
        shell.assets(new AssetProvider() {
            @Override
            public void complete(String type, String prefix, List<String> out) {
                assets.stream().filter(a -> a.startsWith(prefix)).forEach(out::add);
            }

            @Override
            public boolean exists(String type, String path) {
                return assets.contains(path);
            }
        });
        shell.nodes(new NodeProvider() {
            @Override
            public void complete(String prefix, List<String> out) {
                List.of("Player", "Plane").stream().filter(n -> n.startsWith(prefix)).forEach(out::add);
            }

            @Override
            public boolean exists(String name) {
                return name.equals("Player") || name.equals("Plane");
            }
        });
        List<String> seen = new ArrayList<>();
        shell.register(ConsoleCommand.named("paint").arg(Arg.node("target")).arg(Arg.asset("texture", "texture"))
            .action(call -> seen.add(call.text("target") + ":" + call.text("texture"))));
        assertThat(shell.execute("paint Player textures/sand.ktx2")).isTrue();
        assertThat(shell.execute("paint Nobody textures/sand.ktx2")).isFalse();
        assertThat(last()).contains("no node Nobody");
        assertThat(shell.execute("paint Player textures/none.ktx2")).isFalse();
        assertThat(last()).contains("no texture textures/none.ktx2");
        assertThat(seen).containsExactly("Player:textures/sand.ktx2");
        assertThat(shell.complete("paint Pl", 8).candidates()).extracting(Completion.Candidate::text)
            .containsExactly("Player", "Plane");
        assertThat(shell.complete("paint Player textures/r", 23).candidates()).hasSize(2);
        shell.assets(null).nodes(null);
        assertThat(shell.complete("paint Pl", 8).candidates()).isEmpty();
        assertThat(shell.execute("paint Anything any")).isTrue();
    }
}
