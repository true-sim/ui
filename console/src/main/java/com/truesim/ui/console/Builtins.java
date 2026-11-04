package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.Locale;
import java.util.Map;

/** The commands every {@link ConsoleShell} has. */
@AllowAllocation("commands run on events")
final class Builtins {

    private Builtins() {
    }

    static void register(ConsoleShell shell) {
        shell.register(ConsoleCommand.named("help")
            .describe("Lists the commands, or shows how to use one command or setting")
            .arg(Arg.command("name").optional().describe("a command, alias or setting"))
            .action(Builtins::help));
        shell.register(ConsoleCommand.named("alias")
            .describe("Lists the aliases, shows one, or makes name run the commands")
            .arg(Arg.text("name").optional())
            .arg(Arg.text("commands").optional().rest().describe("statements separated by ;"))
            .action(Builtins::alias));
        shell.register(ConsoleCommand.named("unalias")
            .describe("Removes an alias")
            .arg(Arg.command("name"))
            .action(call -> {
                if (call.shell().alias(call.text("name")) == null) {
                    call.error(call.text("name") + " is not an alias");
                } else {
                    call.shell().alias(call.text("name"), null);
                }
            }));
        shell.register(ConsoleCommand.named("exec")
            .describe("Runs the commands of a script file, one line at a time")
            .arg(Arg.script("file"))
            .action(Builtins::exec));
        shell.register(ConsoleCommand.named("echo")
            .describe("Prints its words")
            .arg(Arg.text("text").optional().rest())
            .action(call -> call.print(call.text("text", ""))));
        shell.register(ConsoleCommand.named("reset")
            .describe("Sets a setting back to its default")
            .arg(Arg.setting("setting"))
            .action(call -> {
                ConsoleVariable v = call.shell().variables().find(call.text("setting"));
                if (!call.shell().set(v, v.defaultValue())) {
                    call.fail();
                }
            }));
        shell.register(ConsoleCommand.named("toggle")
            .describe("Flips an on/off setting")
            .arg(Arg.setting("setting"))
            .action(call -> {
                ConsoleVariable v = call.shell().variables().find(call.text("setting"));
                if (v.kind() != ConsoleVariable.Kind.BOOLEAN) {
                    call.error(v.name() + " is not an on/off setting");
                    return;
                }
                if (!call.shell().set(v, Boolean.TRUE.equals(Arg.parseBoolean(v.value())) ? "off" : "on")) {
                    call.fail();
                }
            }));
        shell.register(ConsoleCommand.named("find")
            .describe("Lists the commands and settings whose name or description contains the text")
            .arg(Arg.text("text"))
            .action(Builtins::find));
    }

    private static void help(Invocation call) {
        ConsoleShell shell = call.shell();
        if (!call.has("name")) {
            for (String name : shell.commandNames()) {
                ConsoleCommand c = shell.command(name);
                if (shell.refusal(c) == null) {
                    call.print(c.usage() + (c.description().isEmpty() ? "" : " - " + c.description()));
                }
            }
            call.print("Settings are variables: type a name to see it, a name and a value to set it.");
            return;
        }
        String name = call.text("name");
        ConsoleCommand c = shell.command(name);
        if (c != null) {
            call.print(c.usage() + (c.description().isEmpty() ? "" : " - " + c.description()));
            for (Arg a : c.args()) {
                if (!a.description().isEmpty()) {
                    call.print("  " + a.signature() + " " + a.description());
                }
            }
            String refusal = shell.refusal(c);
            if (refusal != null) {
                call.warn(refusal);
            }
            return;
        }
        String alias = shell.alias(name);
        if (alias != null) {
            call.print(name + " is an alias of: " + alias);
            return;
        }
        call.print(ConsoleShell.describe(shell.variables().find(name)));
    }

    private static void alias(Invocation call) {
        ConsoleShell shell = call.shell();
        if (!call.has("name")) {
            Map<String, String> all = shell.aliases();
            if (all.isEmpty()) {
                call.print("No aliases");
            }
            for (String name : new java.util.TreeSet<>(all.keySet())) {
                call.print(name + " = " + all.get(name));
            }
            return;
        }
        String name = call.text("name");
        if (!call.has("commands")) {
            String alias = shell.alias(name);
            call.print(alias == null ? name + " is not an alias" : name + " = " + alias);
            return;
        }
        if (shell.command(name) != null) {
            call.error(name + " is a command");
            return;
        }
        shell.alias(name, call.text("commands"));
    }

    private static void exec(Invocation call) {
        ConsoleShell shell = call.shell();
        String file = call.text("file");
        String text = shell.scripts() == null ? null : shell.scripts().read(file);
        if (text == null) {
            call.error("exec: no script " + file);
            return;
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank() && !shell.nested(line, "exec " + file)) {
                return;
            }
        }
    }

    private static void find(Invocation call) {
        ConsoleShell shell = call.shell();
        String text = call.text("text").toLowerCase(Locale.ROOT);
        int found = 0;
        for (String name : shell.commandNames()) {
            ConsoleCommand c = shell.command(name);
            if (contains(name, text) || contains(c.description(), text)) {
                call.print(c.usage() + " - " + c.description());
                found++;
            }
        }
        for (String name : shell.variables().names()) {
            ConsoleVariable v = shell.variables().find(name);
            if (contains(name, text) || contains(v.description(), text)) {
                call.print(ConsoleShell.describe(v));
                found++;
            }
        }
        if (found == 0) {
            call.print("Nothing found");
        }
    }

    private static boolean contains(String s, String lowerText) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(lowerText);
    }
}
