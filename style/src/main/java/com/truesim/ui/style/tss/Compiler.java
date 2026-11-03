package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.Declaration;
import com.truesim.ui.style.Rule;
import com.truesim.ui.style.Selector;
import com.truesim.ui.style.Selector.Compound;
import com.truesim.ui.style.Shorthand;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Tokens;
import com.truesim.ui.style.expr.Builtins;
import com.truesim.ui.style.expr.Operators;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.ValueException;
import com.truesim.ui.style.tss.Ast.Arg;
import com.truesim.ui.style.tss.Ast.Expr;
import com.truesim.ui.style.tss.Ast.Param;
import com.truesim.ui.style.tss.Ast.Pos;
import com.truesim.ui.style.tss.Ast.Stmt;
import com.truesim.ui.style.tss.Ast.Template;
import com.truesim.ui.style.value.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates parsed {@code .tss} into a typed {@link StyleSheet}, all at load time: variables and scopes, mixins with
 * {@code @content}, functions, modules ({@code @use} with namespaces, {@code @import}), nesting with {@code &},
 * {@code @extend} of placeholders and simple selectors, control flow, live tokens, and typed declarations checked
 * by each key's type. Errors carry the file, line, column, the rule's selector and the include and call trace.
 */
@AllowAllocation("sheets are compiled at load")
final class Compiler {

    /** A module: one file's global scope and the modules it uses. */
    private static final class Module {
        final String path;
        final Scope globals;
        final Map<String, Module> namespaces = new HashMap<>();
        final List<Module> star = new ArrayList<>();

        Module(String path) {
            this.path = path;
            this.globals = new Scope(null, this, false);
        }
    }

    /** Variables, mixins and functions of one block; flow scopes ({@code @if}, {@code @each}) pass assignments up. */
    private static final class Scope {
        final Scope parent;
        final Module module;
        final boolean flow;
        final Map<String, Value> vars = new HashMap<>();
        final Map<String, Callable> mixins = new HashMap<>();
        final Map<String, Callable> functions = new HashMap<>();

        Scope(Scope parent, Module module, boolean flow) {
            this.parent = parent;
            this.module = module;
            this.flow = flow;
        }

        Scope child(boolean flowScope) {
            return new Scope(this, module, flowScope);
        }
    }

    private record Callable(String name, List<Param> params, List<Stmt> body, Scope closure) {
    }

    /** A {@code @content} block: the caller's statements, scope and own content. */
    private record Content(List<Stmt> body, Scope scope, Content outer) {
    }

    /** Where statements run: the rule (selectors and output), {@code :root}, a token set, the content block. */
    private record Ctx(List<String> selectors, OutRule out, boolean root, String tokenSet, Content content,
        String selector) {
        static final Ctx NONE = new Ctx(null, null, false, null, null, null);

        Ctx withContent(Content c) {
            return new Ctx(selectors, out, root, tokenSet, c, selector);
        }
    }

    /** A rule being built: its selectors as text, then parsed. */
    private static final class OutRule {
        final List<String> selectors;
        final Pos pos;
        final String text;
        final List<String> trace;
        final List<Declaration> declarations = new ArrayList<>();
        List<Selector> parsed;
        final List<Selector> extended = new ArrayList<>();

        OutRule(List<String> selectors, Pos pos, List<String> trace) {
            this.selectors = selectors;
            this.pos = pos;
            this.text = String.join(", ", selectors);
            this.trace = trace;
        }
    }

    private record ExtendRequest(List<String> extenders, String target, Pos pos, String selector,
        List<String> trace) {
    }

    private record LiveCheck(StyleKey<?> key, TokenExpr expression, Pos pos, String selector, List<String> trace) {
    }

    /** Thrown by {@code @return} to leave a function body. */
    private static final class Returned extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Value value;

        Returned(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private final SourceLoader loader;
    private final List<String> sources = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Module> modules = new HashMap<>();
    private final Deque<String> loading = new ArrayDeque<>();
    private final Map<String, List<Stmt>> parsed = new HashMap<>();
    private final List<OutRule> rules = new ArrayList<>();
    private final Map<String, Value> tokens = new LinkedHashMap<>();
    private final Map<String, Pos> tokenPos = new HashMap<>();
    private final Map<String, Map<String, Value>> sets = new LinkedHashMap<>();
    private final List<ExtendRequest> extendRequests = new ArrayList<>();
    private final List<LiveCheck> lives = new ArrayList<>();
    private final Deque<String> trace = new ArrayDeque<>();
    private Ctx ctx = Ctx.NONE;
    private int functionDepth;
    private int mixinDepth;

    Compiler(SourceLoader loader) {
        this.loader = loader;
    }

    // ---------------------------------------------------------------- entry

    Compilation compile(String path) {
        String normalized = normalize(path);
        String text = read(normalized, null);
        Module main = new Module(normalized);
        modules.put(normalized, main);
        loading.push(normalized);
        exec(parse(normalized, text), main.globals);
        loading.pop();
        return new Compilation(emit(), sources, warnings);
    }

    private String read(String path, Pos from) {
        String text = loader.read(path);
        if (text == null) {
            if (from == null) {
                throw new TssException("no such sheet: " + path, path, 1, 1, null, List.of());
            }
            throw fail(from, "cannot find " + path);
        }
        sources.add(path);
        return text;
    }

    private List<Stmt> parse(String path, String text) {
        return parsed.computeIfAbsent(path, p -> new Parser(new Lexer(p, text)).parseFile());
    }

    // ---------------------------------------------------------------- errors

    private TssException fail(Pos p, String message) {
        return new TssException(message, p.file(), p.line(), p.column(), ctx.selector, List.copyOf(trace));
    }

    // ---------------------------------------------------------------- statements

    private void exec(List<Stmt> body, Scope scope) {
        for (Stmt s : body) {
            exec(s, scope);
        }
    }

    private void exec(Stmt stmt, Scope scope) {
        switch (stmt) {
            case Ast.VarDecl v -> variable(v, scope);
            case Ast.Decl d -> declaration(d, scope);
            case Ast.RuleStmt r -> rule(r, scope);
            case Ast.MixinDef m -> scope.mixins.put(m.name(), new Callable(m.name(), m.params(), m.body(), scope));
            case Ast.FunctionDef f -> scope.functions.put(f.name(), new Callable(f.name(), f.params(), f.body(), scope));
            case Ast.Include i -> include(i, scope);
            case Ast.Content c -> content(c);
            case Ast.Return r -> {
                if (functionDepth == 0) {
                    throw fail(r.pos(), "@return outside @function");
                }
                throw new Returned(eval(r.value(), scope));
            }
            case Ast.If i -> {
                Value condition = eval(i.condition(), scope);
                if (condition instanceof Value.Live) {
                    throw fail(i.pos(), "@if cannot test a live token: it is evaluated at load");
                }
                List<Stmt> branch = condition.truthy() ? i.then() : i.otherwise();
                if (branch != null) {
                    exec(branch, scope.child(true));
                }
            }
            case Ast.Each e -> each(e, scope);
            case Ast.For f -> forLoop(f, scope);
            case Ast.Use u -> use(u, scope);
            case Ast.Import i -> {
                for (String path : i.paths()) {
                    String resolved = locate(i.pos(), path);
                    if (loading.contains(resolved)) {
                        throw fail(i.pos(), "@import cycle: " + resolved + " imports itself");
                    }
                    loading.push(resolved);
                    exec(parse(resolved, read(resolved, i.pos())), scope);
                    loading.pop();
                }
            }
            case Ast.Extend e -> {
                if (ctx.selectors == null) {
                    throw fail(e.pos(), "@extend belongs in a rule");
                }
                extendRequests.add(new ExtendRequest(ctx.selectors, interpolate(e.selector(), scope).strip(), e.pos(),
                    ctx.selector, List.copyOf(trace)));
            }
            case Ast.TokenSet t -> {
                if (ctx.selectors != null || ctx.root || ctx.tokenSet != null) {
                    throw fail(t.pos(), "@tokens belongs at the top level");
                }
                sets.computeIfAbsent(t.name(), k -> new LinkedHashMap<>());
                Ctx saved = ctx;
                ctx = new Ctx(null, null, false, t.name(), ctx.content, null);
                exec(t.body(), scope.child(false));
                ctx = saved;
            }
            case Ast.Message m -> {
                Value v = eval(m.value(), scope);
                String text = v instanceof Value.Str s ? s.text() : v.show();
                if (m.kind().equals("error")) {
                    throw fail(m.pos(), text);
                }
                warnings.add(m.pos() + ": @" + m.kind() + " " + text);
            }
        }
    }

    private void variable(Ast.VarDecl v, Scope scope) {
        Value value = eval(v.value(), scope);
        if (v.live()) {
            if (!atTop(scope) || ctx.selectors != null) {
                throw fail(v.pos(), "$" + v.name() + " !live: live variables are declared at the top level");
            }
            tokens.put(v.name(), value);
            tokenPos.put(v.name(), v.pos());
            value = new Value.Live(TokenExpr.token(v.name()));
        }
        Scope target;
        if (v.global()) {
            target = scope.module.globals;
        } else {
            Scope found = find(scope, v.name());
            target = found != null && (found != scope.module.globals || atTop(scope)) ? found : scope;
        }
        Value old = target.vars.get(v.name());
        if (v.isDefault() && old != null && !(old instanceof Value.Null)) {
            return;
        }
        target.vars.put(v.name(), value);
    }

    /** Whether {@code scope} is the module's global scope, or flow scopes directly in it. */
    private static boolean atTop(Scope scope) {
        Scope s = scope;
        while (s.flow) {
            s = s.parent;
        }
        return s == s.module.globals;
    }

    private static Scope find(Scope scope, String name) {
        for (Scope s = scope; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                return s;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void declaration(Ast.Decl d, Scope scope) {
        if (ctx.out == null && !ctx.root && ctx.tokenSet == null) {
            throw fail(d.pos(), "declaration of " + d.property() + " outside a rule");
        }
        Value value = eval(d.value(), scope);
        String property = d.property();
        if (property.startsWith("--")) {
            String name = property.substring(2);
            if (ctx.tokenSet != null) {
                sets.get(ctx.tokenSet).put(name, value);
                tokenPos.put(ctx.tokenSet + "/" + name, d.pos());
            } else if (ctx.root) {
                tokens.put(name, value);
                tokenPos.put(name, d.pos());
            } else {
                throw fail(d.pos(), property + ": tokens are declared in :root or @tokens blocks");
            }
            return;
        }
        if (ctx.root || ctx.tokenSet != null) {
            throw fail(d.pos(), property + ": only --tokens are declared here");
        }
        if (value instanceof Value.Null) {
            return; // a null value declares nothing, as in Sass: mixins leave optional keys out this way
        }
        StyleKey<?> key = StyleKeys.find(property);
        Shorthand<?> shorthand = key == null ? StyleKeys.findShorthand(property) : null;
        if (key == null && shorthand == null) {
            String close = StyleKeys.suggest(property);
            throw fail(d.pos(), "unknown key " + property + (close == null ? "" : " (did you mean " + close + "?)"));
        }
        if (value instanceof Value.Live live) {
            if (key == null) {
                throw fail(d.pos(), property + " is a shorthand and cannot take a live value: set its keys");
            }
            ctx.out.declarations.add(Declaration.live(key, live.expression()));
            lives.add(new LiveCheck(key, live.expression(), d.pos(), ctx.selector, List.copyOf(trace)));
            return;
        }
        try {
            if (key != null) {
                StyleKey<Object> k = (StyleKey<Object>) key;
                ctx.out.declarations.add(Declaration.of(k, k.type().parse(value)));
            } else {
                Shorthand<Object> s = (Shorthand<Object>) shorthand;
                ctx.out.declarations.addAll(s.expand(s.type().parse(value)));
            }
        } catch (ValueException | IllegalArgumentException e) {
            throw fail(d.pos(), property + ": " + e.getMessage());
        }
    }

    private void rule(Ast.RuleStmt r, Scope scope) {
        if (ctx.root || ctx.tokenSet != null) {
            throw fail(r.pos(), "rules cannot nest in :root or @tokens");
        }
        String text = interpolate(r.selector(), scope).strip();
        Ctx saved = ctx;
        if (text.equals(":root")) {
            if (ctx.selectors != null) {
                throw fail(r.pos(), ":root belongs at the top level");
            }
            ctx = new Ctx(null, null, true, null, ctx.content, ":root");
            exec(r.body(), scope.child(false));
            ctx = saved;
            return;
        }
        List<String> own = split(text);
        List<String> full = new ArrayList<>();
        for (String child : own) {
            if (child.isEmpty()) {
                throw fail(r.pos(), "empty selector in '" + text + "'");
            }
            if (ctx.selectors == null) {
                if (child.contains("&")) {
                    throw fail(r.pos(), "'&' outside a rule: " + child);
                }
                full.add(child);
            } else {
                for (String parent : ctx.selectors) {
                    full.add(child.contains("&") ? child.replace("&", parent) : parent + " " + child);
                }
            }
        }
        OutRule out = new OutRule(full, r.pos(), List.copyOf(trace));
        rules.add(out);
        ctx = new Ctx(full, out, false, null, ctx.content, out.text);
        exec(r.body(), scope.child(false));
        ctx = saved;
    }

    /** Splits a selector list at commas outside brackets and quotes. */
    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, i).strip());
                start = i + 1;
            }
        }
        out.add(text.substring(start).strip());
        return out;
    }

    private String interpolate(Template t, Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (Object part : t.parts()) {
            if (part instanceof String s) {
                sb.append(s);
            } else {
                Expr e = (Expr) part;
                Value v = eval(e, scope);
                if (v instanceof Value.Live) {
                    throw fail(e.pos(), "a live token cannot be interpolated: it is evaluated at load");
                }
                sb.append(v instanceof Value.Str s ? s.text() : v.show());
            }
        }
        return sb.toString();
    }

    private void include(Ast.Include i, Scope scope) {
        Callable mixin = callable(scope, i.namespace(), i.name(), true, i.pos());
        Scope body = bind(mixin, i.args(), scope, i.pos(), "@include " + i.name());
        Content content = i.content() == null ? null : new Content(i.content(), scope, ctx.content);
        trace.push("@include " + i.name() + " at " + i.pos());
        Ctx saved = ctx;
        ctx = ctx.withContent(content);
        mixinDepth++;
        try {
            exec(mixin.body(), body);
        } finally {
            mixinDepth--;
            ctx = saved;
            trace.pop();
        }
    }

    private void content(Ast.Content c) {
        Content content = ctx.content;
        if (content == null) {
            if (mixinDepth > 0) {
                return; // included without a block: nothing to put here, as in Sass
            }
            throw fail(c.pos(), "@content outside a mixin");
        }
        Ctx saved = ctx;
        ctx = ctx.withContent(content.outer());
        exec(content.body(), content.scope().child(false));
        ctx = saved;
    }

    private void each(Ast.Each e, Scope scope) {
        Value list = eval(e.list(), scope);
        if (list instanceof Value.Live) {
            throw fail(e.pos(), "@each cannot loop over a live token: it is evaluated at load");
        }
        if (list instanceof Value.MapV map) {
            for (Map.Entry<Value, Value> entry : map.entries().entrySet()) {
                Scope s = scope.child(true);
                if (e.vars().size() == 1) {
                    s.vars.put(e.vars().get(0), Value.list(List.of(entry.getKey(), entry.getValue()), false));
                } else {
                    s.vars.put(e.vars().get(0), entry.getKey());
                    s.vars.put(e.vars().get(1), entry.getValue());
                    for (int k = 2; k < e.vars().size(); k++) {
                        s.vars.put(e.vars().get(k), Value.NULL);
                    }
                }
                exec(e.body(), s);
            }
            return;
        }
        for (Value item : list.items()) {
            Scope s = scope.child(true);
            if (e.vars().size() == 1) {
                s.vars.put(e.vars().get(0), item);
            } else {
                List<Value> parts = item.items();
                for (int k = 0; k < e.vars().size(); k++) {
                    s.vars.put(e.vars().get(k), k < parts.size() ? parts.get(k) : Value.NULL);
                }
            }
            exec(e.body(), s);
        }
    }

    private void forLoop(Ast.For f, Scope scope) {
        int from = integer(eval(f.from(), scope), f.from().pos());
        int to = integer(eval(f.to(), scope), f.to().pos());
        int step = from <= to ? 1 : -1;
        int end = f.inclusive() ? to + step : to;
        for (int i = from; i != end; i += step) {
            Scope s = scope.child(true);
            s.vars.put(f.var(), Value.num(i));
            exec(f.body(), s);
        }
    }

    private int integer(Value v, Pos p) {
        if (v instanceof Value.Num n && n.unit().isEmpty() && n.value() == (int) n.value()) {
            return (int) n.value();
        }
        throw fail(p, "@for needs unitless integers, got " + v.show());
    }

    private void use(Ast.Use u, Scope scope) {
        if (scope != scope.module.globals || ctx.selectors != null) {
            throw fail(u.pos(), "@use belongs at the top level of a file");
        }
        String resolved = locate(u.pos(), u.path());
        if (loading.contains(resolved)) {
            throw fail(u.pos(), "@use cycle: " + resolved + " uses itself");
        }
        Module m = modules.get(resolved);
        if (m == null) {
            String text = read(resolved, u.pos());
            m = new Module(resolved);
            modules.put(resolved, m);
            loading.push(resolved);
            Ctx saved = ctx;
            ctx = Ctx.NONE;
            exec(parse(resolved, text), m.globals);
            ctx = saved;
            loading.pop();
        }
        String ns = u.namespace() != null ? u.namespace() : namespace(u.path());
        if (ns.equals("*")) {
            scope.module.star.add(m);
        } else {
            scope.module.namespaces.put(ns, m);
        }
    }

    private static String namespace(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        if (base.startsWith("_")) {
            base = base.substring(1);
        }
        return base.endsWith(".tss") ? base.substring(0, base.length() - 4) : base;
    }

    /** The path of {@code target} as written in the file at {@code from}: {@code name.tss} or the partial. */
    private String locate(Pos from, String target) {
        String dir = from.file().contains("/") ? from.file().substring(0, from.file().lastIndexOf('/') + 1) : "";
        String path = normalize(dir + target);
        String file = path.endsWith(".tss") ? path : path + ".tss";
        int slash = file.lastIndexOf('/');
        String partial = file.substring(0, slash + 1) + "_" + file.substring(slash + 1);
        if (loader.read(file) != null) {
            return file;
        }
        if (loader.read(partial) != null) {
            return partial;
        }
        throw fail(from, "cannot find " + target + " (tried " + file + " and " + partial + ")");
    }

    static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") && !parts.isEmpty() && !parts.peekLast().equals("..")) {
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    // ---------------------------------------------------------------- callables

    private Callable callable(Scope scope, String ns, String name, boolean mixin, Pos p) {
        String what = mixin ? "mixin " : "function ";
        if (ns != null) {
            Module m = module(scope, ns, p);
            Callable c = (mixin ? m.globals.mixins : m.globals.functions).get(name);
            if (c == null) {
                throw fail(p, "no " + what + name + " in module " + ns);
            }
            return c;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Scope s = scope; s != null; s = s.parent) {
            Map<String, Callable> map = mixin ? s.mixins : s.functions;
            Callable c = map.get(name);
            if (c != null) {
                return c;
            }
            seen.addAll(map.keySet());
        }
        for (Module m : scope.module.star) {
            Callable c = (mixin ? m.globals.mixins : m.globals.functions).get(name);
            if (c != null) {
                return c;
            }
            seen.addAll((mixin ? m.globals.mixins : m.globals.functions).keySet());
        }
        if (!mixin) {
            return null;
        }
        String close = StyleKeys.closest(name, seen);
        throw fail(p, "undefined mixin " + name + (close == null ? "" : " (did you mean " + close + "?)"));
    }

    private Module module(Scope scope, String ns, Pos p) {
        Module m = scope.module.namespaces.get(ns);
        if (m == null) {
            String close = StyleKeys.closest(ns, scope.module.namespaces.keySet());
            throw fail(p, "no module " + ns + " (@use it first" + (close == null ? "" : "; did you mean " + close)
                + ")");
        }
        return m;
    }

    /** A new scope for {@code callable}'s body with {@code args} bound to its parameters. */
    private Scope bind(Callable callable, List<Arg> args, Scope caller, Pos p, String what) {
        Scope s = callable.closure().child(false);
        List<Value> positional = new ArrayList<>();
        Map<String, Value> named = new LinkedHashMap<>();
        for (Arg a : args) {
            Value v = eval(a.value(), caller);
            if (a.name() == null) {
                positional.add(v);
            } else {
                named.put(a.name(), v);
            }
        }
        List<Param> params = callable.params();
        if (positional.size() > params.size()) {
            throw fail(p, what + " takes " + params.size() + " arguments, got " + positional.size());
        }
        List<String> names = new ArrayList<>();
        for (Param param : params) {
            names.add(param.name());
        }
        for (String name : named.keySet()) {
            if (!names.contains(name)) {
                String close = StyleKeys.closest(name, names);
                throw fail(p, what + " has no parameter $" + name + (close == null ? "" : " (did you mean $"
                    + close + "?)"));
            }
        }
        for (int i = 0; i < params.size(); i++) {
            Param param = params.get(i);
            Value v;
            if (i < positional.size()) {
                v = positional.get(i);
                if (named.containsKey(param.name())) {
                    throw fail(p, what + ": $" + param.name() + " is given twice");
                }
            } else if (named.containsKey(param.name())) {
                v = named.remove(param.name());
            } else if (param.fallback() != null) {
                v = eval(param.fallback(), s);
            } else {
                throw fail(p, what + " is missing $" + param.name());
            }
            s.vars.put(param.name(), v);
        }
        return s;
    }

    // ---------------------------------------------------------------- expressions

    private Value eval(Expr e, Scope scope) {
        try {
            return evalUnchecked(e, scope);
        } catch (ValueException x) {
            throw fail(e.pos(), x.getMessage());
        }
    }

    private Value evalUnchecked(Expr e, Scope scope) {
        return switch (e) {
            case Ast.Literal l -> l.value();
            case Ast.Ident i -> ident(i.name());
            case Ast.Var v -> variable(scope, v);
            case Ast.Call c -> call(c, scope);
            case Ast.Binary b -> binary(b, scope);
            case Ast.Unary u -> Operators.unary(u.op(), eval(u.operand(), scope));
            case Ast.ListExpr l -> {
                List<Value> items = new ArrayList<>(l.items().size());
                for (Expr item : l.items()) {
                    items.add(eval(item, scope));
                }
                yield Value.list(items, l.comma());
            }
            case Ast.MapExpr m -> {
                Map<Value, Value> entries = new LinkedHashMap<>();
                for (int i = 0; i < m.keys().size(); i++) {
                    Value k = eval(m.keys().get(i), scope);
                    Value v = eval(m.values().get(i), scope);
                    if (k instanceof Value.Live || v instanceof Value.Live) {
                        throw fail(m.keys().get(i).pos(), "maps cannot hold live tokens");
                    }
                    entries.put(k, v);
                }
                yield new Value.MapV(entries);
            }
        };
    }

    private static Value ident(String name) {
        switch (name) {
            case "true":
                return Value.TRUE;
            case "false":
                return Value.FALSE;
            case "null":
                return Value.NULL;
            default:
                Color c = Color.named(name);
                return c != null ? Value.color(c) : Value.ident(name);
        }
    }

    private Value variable(Scope scope, Ast.Var v) {
        if (v.namespace() != null) {
            Module m = module(scope, v.namespace(), v.pos());
            Value value = m.globals.vars.get(v.name());
            if (value == null) {
                String close = StyleKeys.closest(v.name(), m.globals.vars.keySet());
                throw fail(v.pos(), "no variable $" + v.name() + " in module " + v.namespace()
                    + (close == null ? "" : " (did you mean $" + close + "?)"));
            }
            return value;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Scope s = scope; s != null; s = s.parent) {
            Value value = s.vars.get(v.name());
            if (value != null) {
                return value;
            }
            seen.addAll(s.vars.keySet());
        }
        for (Module m : scope.module.star) {
            Value value = m.globals.vars.get(v.name());
            if (value != null) {
                return value;
            }
            seen.addAll(m.globals.vars.keySet());
        }
        String close = StyleKeys.closest(v.name(), seen);
        throw fail(v.pos(), "undefined variable $" + v.name() + (close == null ? "" : " (did you mean $" + close
            + "?)"));
    }

    private Value binary(Ast.Binary b, Scope scope) {
        Value left = eval(b.left(), scope);
        if (!(left instanceof Value.Live)) {
            if (b.op().equals("and") && !left.truthy()) {
                return left;
            }
            if (b.op().equals("or") && left.truthy()) {
                return left;
            }
        }
        Value right = eval(b.right(), scope);
        try {
            return Operators.binary(b.op(), left, right);
        } catch (ValueException x) {
            throw fail(b.pos(), x.getMessage());
        }
    }

    private Value call(Ast.Call c, Scope scope) {
        if (c.namespace() == null && c.name().equals("var")) {
            return token(c, scope);
        }
        Callable function = callable(scope, c.namespace(), c.name(), false, c.pos());
        if (function != null) {
            return callFunction(function, c, scope);
        }
        if (c.namespace() != null) {
            throw fail(c.pos(), "no function " + c.name() + " in module " + c.namespace());
        }
        String[] params = Builtins.parameters(c.name());
        List<Value> args = new ArrayList<>();
        boolean named = false;
        for (Arg a : c.args()) {
            if (a.name() != null) {
                named = true;
            } else if (named) {
                throw fail(c.pos(), c.name() + "(): positional arguments come before named ones");
            } else {
                args.add(eval(a.value(), scope));
            }
        }
        for (Arg a : c.args()) {
            if (a.name() != null) {
                if (params == null) {
                    throw fail(c.pos(), c.name() + "() takes no named arguments: it is not a function this "
                        + "language knows, so it stays a value");
                }
                int index = indexOf(params, a.name());
                if (index < 0) {
                    String close = StyleKeys.closest(a.name(), List.of(params));
                    throw fail(c.pos(), c.name() + "() has no parameter $" + a.name()
                        + (close == null ? "" : " (did you mean $" + close + "?)"));
                }
                while (args.size() <= index) {
                    args.add(null);
                }
                if (args.get(index) != null) {
                    throw fail(c.pos(), c.name() + "(): $" + a.name() + " is given twice");
                }
                args.set(index, eval(a.value(), scope));
            }
        }
        try {
            return Builtins.call(c.name(), args);
        } catch (ValueException x) {
            throw fail(c.pos(), x.getMessage());
        }
    }

    private static int indexOf(String[] params, String name) {
        for (int i = 0; i < params.length; i++) {
            if (params[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private Value token(Ast.Call c, Scope scope) {
        if (c.args().size() != 1 || c.args().get(0).name() != null) {
            throw fail(c.pos(), "var() takes one token name: var(--accent)");
        }
        Value name = eval(c.args().get(0).value(), scope);
        if (!(name instanceof Value.Str s) || s.quoted() || !s.text().startsWith("--") || s.text().length() < 3) {
            throw fail(c.pos(), "var() takes a token name such as --accent, got " + name.show());
        }
        return new Value.Live(TokenExpr.token(s.text().substring(2)));
    }

    private Value callFunction(Callable function, Ast.Call c, Scope scope) {
        Scope s = bind(function, c.args(), scope, c.pos(), function.name() + "()");
        for (Value v : s.vars.values()) {
            if (v instanceof Value.Live) {
                throw fail(c.pos(), "a live token cannot be passed to @function " + function.name()
                    + ": live values go through built-in functions only");
            }
        }
        trace.push(function.name() + "() at " + c.pos());
        functionDepth++;
        try {
            exec(function.body(), s);
        } catch (Returned r) {
            return r.value;
        } finally {
            functionDepth--;
            trace.pop();
        }
        throw fail(c.pos(), "@function " + function.name() + " ended without @return");
    }

    // ---------------------------------------------------------------- output

    private StyleSheet emit() {
        for (OutRule r : rules) {
            r.parsed = new ArrayList<>();
            for (String text : r.selectors) {
                try {
                    r.parsed.addAll(Selector.parseList(text));
                } catch (ValueException e) {
                    throw new TssException(e.getMessage(), r.pos.file(), r.pos.line(), r.pos.column(), r.text,
                        r.trace);
                }
            }
        }
        for (ExtendRequest e : extendRequests) {
            extend(e);
        }
        List<Rule> out = new ArrayList<>();
        for (OutRule r : rules) {
            if (r.declarations.isEmpty()) {
                continue;
            }
            for (Selector s : r.parsed) {
                if (!s.hasPlaceholder()) {
                    out.add(new Rule(s, r.declarations));
                }
            }
            for (Selector s : r.extended) {
                if (!s.hasPlaceholder()) {
                    out.add(new Rule(s, r.declarations));
                }
            }
        }
        StyleSheet sheet = new StyleSheet(out, tokens, sets);
        check(sheet);
        return sheet;
    }

    private TssException fail(ExtendRequest e, String message) {
        return new TssException(message, e.pos().file(), e.pos().line(), e.pos().column(), e.selector(), e.trace());
    }

    private void extend(ExtendRequest e) {
        Selector target;
        List<Selector> extenders = new ArrayList<>();
        try {
            target = Selector.parse(e.target());
            for (String text : e.extenders()) {
                extenders.addAll(Selector.parseList(text));
            }
        } catch (ValueException x) {
            throw fail(e, x.getMessage());
        }
        if (target.compounds().size() != 1 || target.subject().equals(Compound.ANY)) {
            throw fail(e, "@extend takes one simple selector such as %pill or .card, got " + e.target());
        }
        Compound t = target.subject();
        boolean matched = false;
        for (OutRule r : rules) {
            for (Selector s : r.parsed) {
                for (int i = 0; i < s.compounds().size(); i++) {
                    if (!contains(s.compounds().get(i), t)) {
                        continue;
                    }
                    matched = true; // found; an extender that cannot unify with it is skipped, as in Sass
                    for (Selector x : extenders) {
                        Selector n = substitute(s, i, t, x);
                        if (n != null) {
                            r.extended.add(n);
                        }
                    }
                }
            }
        }
        if (!matched) {
            throw fail(e, "@extend " + e.target() + " matched no rule");
        }
    }

    private static boolean contains(Compound c, Compound t) {
        return (t.type() == null || t.type().equals(c.type())) && (t.style() == null || t.style().equals(c.style()))
            && (t.id().isEmpty() || t.id().equals(c.id())) && (c.states() & t.states()) == t.states()
            && c.facts().containsAll(t.facts()) && (t.placeholder() == null || t.placeholder().equals(c.placeholder()));
    }

    /** {@code s} with its compound {@code i} (which contains {@code t}) replaced by extender {@code x}, or null. */
    private static Selector substitute(Selector s, int i, Compound t, Selector x) {
        Compound c = s.compounds().get(i);
        Compound e = x.subject();
        // what the rule's compound tests beyond the target, merged with the extender's own tests
        String restType = t.type() != null ? null : c.type();
        String restStyle = t.style() != null ? null : c.style();
        List<String> restId = t.id().isEmpty() ? c.id() : List.of();
        if (differ(restType, e.type()) || differ(restStyle, e.style())
            || !restId.isEmpty() && !e.id().isEmpty() && !restId.equals(e.id())) {
            return null; // an element cannot be both
        }
        String type = restType != null ? restType : e.type();
        String style = restStyle != null ? restStyle : e.style();
        List<Selector.Fact> facts = new ArrayList<>(e.facts());
        for (Selector.Fact f : c.facts()) {
            if (!t.facts().contains(f) && !facts.contains(f)) {
                facts.add(f);
            }
        }
        String placeholder = t.placeholder() != null ? e.placeholder() : c.placeholder();
        Compound merged = new Compound(type, style, restId.isEmpty() ? e.id() : restId,
            (c.states() & ~t.states()) | e.states(), facts, placeholder);
        List<Compound> compounds = new ArrayList<>(s.compounds().subList(0, i));
        compounds.addAll(x.compounds().subList(0, x.compounds().size() - 1));
        compounds.add(merged);
        compounds.addAll(s.compounds().subList(i + 1, s.compounds().size()));
        List<Selector.Combinator> combinators = new ArrayList<>(s.combinators().subList(0, i));
        combinators.addAll(x.combinators());
        combinators.addAll(s.combinators().subList(i, s.combinators().size()));
        return new Selector(compounds, combinators);
    }

    private static boolean differ(String a, String b) {
        return a != null && b != null && !a.equals(b);
    }

    /** A token's error placed at its declaration ({@code set} null: a default). */
    private TssException tokenError(ValueException e, String set) {
        String message = e.getMessage();
        String name = message.startsWith("token --") ? message.substring(8).split("[: ]", 2)[0] : "";
        Pos p = set == null ? null : tokenPos.get(set + "/" + name);
        if (p == null) {
            p = tokenPos.get(name);
        }
        if (p == null) {
            p = new Pos(sources.get(0), 1, 1);
        }
        return new TssException(set == null ? message : "in @tokens " + set + ": " + message, p.file(), p.line(),
            p.column(), null, List.of());
    }

    /** Evaluates every live value with the token defaults and with each token set, so errors show at load. */
    private void check(StyleSheet sheet) {
        Tokens t;
        try {
            t = Tokens.of(sheet);
        } catch (ValueException e) {
            throw tokenError(e, null);
        }
        List<String> setNames = new ArrayList<>();
        setNames.add(null);
        setNames.addAll(sheet.tokenSets().keySet());
        for (String set : setNames) {
            try {
                t.use(set);
            } catch (ValueException e) {
                throw tokenError(e, set);
            }
            for (LiveCheck c : lives) {
                try {
                    c.key().type().parse(c.expression().evaluate(t));
                } catch (ValueException e) {
                    throw new TssException(c.key().name() + ": " + e.getMessage()
                        + (set == null ? "" : " (with token set " + set + ")"), c.pos().file(), c.pos().line(),
                        c.pos().column(), c.selector(), c.trace());
                }
            }
        }
    }
}
