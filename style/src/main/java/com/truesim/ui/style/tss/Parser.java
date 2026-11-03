package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.tss.Ast.Arg;
import com.truesim.ui.style.tss.Ast.Expr;
import com.truesim.ui.style.tss.Ast.Param;
import com.truesim.ui.style.tss.Ast.Pos;
import com.truesim.ui.style.tss.Ast.Stmt;
import com.truesim.ui.style.tss.Ast.Template;
import com.truesim.ui.style.tss.Lexer.Kind;
import com.truesim.ui.style.tss.Lexer.Token;
import com.truesim.ui.style.value.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code .tss} into an {@link Ast}: Sass's syntax for the subset the design names (variables, maps and lists,
 * nesting with {@code &}, mixins with arguments and defaults, {@code @use}/{@code @import}, {@code @extend} and
 * placeholders, {@code @if}/{@code @else}, {@code @each}, {@code @for}, functions, arithmetic) plus {@code @tokens}
 * blocks for token sets. A statement is a rule if a {@code {} comes before the next {@code ;} or {@code }}, so
 * selectors such as {@code a:hover} are told from declarations such as {@code font: bold}; rule selectors are kept as
 * text with interpolations and parsed once evaluated.
 */
@AllowAllocation("sheets are compiled at load")
final class Parser {

    private static final String[] AT_RULES = {"use", "import", "mixin", "include", "content", "function", "return",
        "if", "else", "each", "for", "extend", "tokens", "error", "warn", "debug"};

    private final Lexer lex;
    private final String src;
    private int pos;

    Parser(Lexer lex) {
        this.lex = lex;
        this.src = lex.src;
    }

    /** The statements of the whole file. */
    List<Stmt> parseFile() {
        List<Stmt> out = new ArrayList<>();
        while (peek().kind() != Kind.EOF) {
            if (peek().is("}")) {
                throw error(peek(), "unexpected '}'");
            }
            statement(out);
        }
        return out;
    }

    // ---------------------------------------------------------------- tokens

    private Token peek() {
        return lex.token(pos);
    }

    private Token peekAfter(Token t) {
        return lex.token(t.end());
    }

    private Token next() {
        Token t = peek();
        pos = t.end();
        return t;
    }

    private Pos at(Token t) {
        return lex.pos(t.start());
    }

    private TssException error(Token t, String message) {
        return lex.error(t.start(), message);
    }

    private Token expect(String punct) {
        Token t = peek();
        if (!t.is(punct)) {
            throw error(t, "expected '" + punct + "', got " + t.show());
        }
        return next();
    }

    private boolean accept(String punct) {
        if (peek().is(punct)) {
            next();
            return true;
        }
        return false;
    }

    private String ident(String what) {
        Token t = peek();
        if (t.kind() != Kind.IDENT) {
            throw error(t, "expected " + what + ", got " + t.show());
        }
        return next().text();
    }

    private void endStatement() {
        if (!accept(";") && !peek().is("}") && peek().kind() != Kind.EOF) {
            throw error(peek(), "expected ';', got " + peek().show());
        }
    }

    // ---------------------------------------------------------------- statements

    private List<Stmt> block() {
        expect("{");
        List<Stmt> out = new ArrayList<>();
        while (!peek().is("}")) {
            if (peek().kind() == Kind.EOF) {
                throw error(peek(), "expected '}', got end of file");
            }
            statement(out);
        }
        next();
        return out;
    }

    private void statement(List<Stmt> out) {
        Token t = peek();
        if (t.is(";")) {
            next();
            return;
        }
        if (t.kind() == Kind.AT) {
            out.add(atRule());
            return;
        }
        if (t.kind() == Kind.VAR && peekAfter(t).is(":")) {
            out.add(variable());
            return;
        }
        int end = scan(t.start());
        if (end < src.length() && src.charAt(end) == '{') {
            Template selector = template(t.start(), end);
            pos = end;
            out.add(new Ast.RuleStmt(at(t), selector, block()));
            return;
        }
        out.add(declaration());
    }

    /** The offset of the first {@code {}, {@code ;} or {@code }} at depth 0 from {@code from}, or the end. */
    private int scan(int from) {
        int depth = 0;
        int i = from;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                i = lex.token(i).end();
                continue;
            }
            if (c == '/' && i + 1 < src.length() && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*')) {
                i = lex.skip(i);
                continue;
            }
            if (c == '#' && i + 1 < src.length() && src.charAt(i + 1) == '{') {
                int close = src.indexOf('}', i);
                i = close < 0 ? src.length() : close + 1;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (depth <= 0 && (c == '{' || c == ';' || c == '}')) {
                return i;
            }
            i++;
        }
        return i;
    }

    /** Raw text from {@code from} to {@code end} with {@code #{...}} interpolations parsed. */
    private Template template(int from, int end) {
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = from;
        while (i < end) {
            char c = src.charAt(i);
            if (c == '#' && i + 1 < end && src.charAt(i + 1) == '{') {
                if (literal.length() > 0) {
                    parts.add(literal.toString());
                    literal.setLength(0);
                }
                pos = i + 2;
                parts.add(commaList());
                expect("}");
                i = pos;
            } else if (c == '/' && i + 1 < end && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*')) {
                i = lex.skip(i);
                literal.append(' ');
            } else {
                literal.append(c);
                i++;
            }
        }
        if (literal.length() > 0) {
            parts.add(literal.toString());
        }
        return new Template(lex.pos(from), parts);
    }

    private Stmt variable() {
        Token t = next();
        expect(":");
        Expr value = commaList();
        boolean isDefault = false;
        boolean global = false;
        boolean live = false;
        while (peek().is("!")) {
            Token bang = next();
            Token flag = peek();
            switch (flag.kind() == Kind.IDENT ? flag.text() : "") {
                case "default" -> isDefault = true;
                case "global" -> global = true;
                case "live" -> live = true;
                default -> throw error(bang, "unknown flag !" + flag.text() + " (expected !default, !global or !live)");
            }
            next();
        }
        endStatement();
        return new Ast.VarDecl(at(t), t.text(), value, isDefault, global, live);
    }

    private Stmt declaration() {
        Token t = peek();
        if (t.kind() != Kind.IDENT) {
            throw error(t, "expected a declaration or a rule, got " + t.show());
        }
        next();
        expect(":");
        if (peek().is(";") || peek().is("}")) {
            throw error(peek(), "expected a value for " + t.text());
        }
        Expr value = commaList();
        endStatement();
        return new Ast.Decl(at(t), t.text(), value);
    }

    private Stmt atRule() {
        Token t = next();
        Pos p = at(t);
        switch (t.text()) {
            case "use" -> {
                String path = string("a path");
                String ns = null;
                if (peek().isIdent("as")) {
                    next();
                    ns = accept("*") ? "*" : ident("a namespace");
                }
                endStatement();
                return new Ast.Use(p, path, ns);
            }
            case "import" -> {
                List<String> paths = new ArrayList<>();
                do {
                    paths.add(string("a path"));
                } while (accept(","));
                endStatement();
                return new Ast.Import(p, paths);
            }
            case "mixin" -> {
                String name = ident("a mixin name");
                List<Param> params = peek().is("(") ? params() : List.of();
                return new Ast.MixinDef(p, name, params, block());
            }
            case "include" -> {
                String ns = null;
                String name = ident("a mixin name");
                if (peek().is(".") && !peek().spaceBefore()) {
                    next();
                    ns = name;
                    name = ident("a mixin name");
                }
                List<Arg> args = peek().is("(") ? args() : List.of();
                List<Stmt> content = null;
                if (peek().is("{")) {
                    content = block();
                } else {
                    endStatement();
                }
                return new Ast.Include(p, ns, name, args, content);
            }
            case "content" -> {
                endStatement();
                return new Ast.Content(p);
            }
            case "function" -> {
                String name = ident("a function name");
                if (!peek().is("(")) {
                    throw error(peek(), "expected '(' after @function " + name);
                }
                return new Ast.FunctionDef(p, name, params(), block());
            }
            case "return" -> {
                Expr value = commaList();
                endStatement();
                return new Ast.Return(p, value);
            }
            case "if" -> {
                return ifRule(p);
            }
            case "else" -> throw error(t, "@else without @if");
            case "each" -> {
                List<String> vars = new ArrayList<>();
                do {
                    Token v = next();
                    if (v.kind() != Kind.VAR) {
                        throw error(v, "expected a variable, got " + v.show());
                    }
                    vars.add(v.text());
                } while (accept(","));
                if (!peek().isIdent("in")) {
                    throw error(peek(), "expected 'in', got " + peek().show());
                }
                next();
                return new Ast.Each(p, vars, commaList(), block());
            }
            case "for" -> {
                Token v = next();
                if (v.kind() != Kind.VAR) {
                    throw error(v, "expected a variable, got " + v.show());
                }
                if (!peek().isIdent("from")) {
                    throw error(peek(), "expected 'from', got " + peek().show());
                }
                next();
                Expr from = or();
                boolean inclusive;
                if (peek().isIdent("through")) {
                    inclusive = true;
                } else if (peek().isIdent("to")) {
                    inclusive = false;
                } else {
                    throw error(peek(), "expected 'through' or 'to', got " + peek().show());
                }
                next();
                return new Ast.For(p, v.text(), from, or(), inclusive, block());
            }
            case "extend" -> {
                int start = peek().start();
                int end = scan(start);
                Template target = template(start, end);
                pos = end;
                endStatement();
                return new Ast.Extend(p, target);
            }
            case "tokens" -> {
                String name = ident("a token set name");
                return new Ast.TokenSet(p, name, block());
            }
            case "error", "warn", "debug" -> {
                Expr value = commaList();
                endStatement();
                return new Ast.Message(p, t.text(), value);
            }
            default -> {
                String close = StyleKeys.closest(t.text(), List.of(AT_RULES));
                throw error(t, "unknown at-rule @" + t.text() + (close == null ? "" : " (did you mean @" + close
                    + "?)"));
            }
        }
    }

    private Stmt ifRule(Pos p) {
        Expr condition = commaList();
        List<Stmt> then = block();
        List<Stmt> otherwise = null;
        if (peek().kind() == Kind.AT && peek().text().equals("else")) {
            Token e = next();
            if (peek().isIdent("if")) {
                next();
                otherwise = List.of(ifRule(at(e)));
            } else {
                otherwise = block();
            }
        }
        return new Ast.If(p, condition, then, otherwise);
    }

    private String string(String what) {
        Token t = peek();
        if (t.kind() != Kind.STRING) {
            throw error(t, "expected " + what + " in quotes, got " + t.show());
        }
        return next().text();
    }

    private List<Param> params() {
        expect("(");
        List<Param> out = new ArrayList<>();
        while (!peek().is(")")) {
            Token v = next();
            if (v.kind() != Kind.VAR) {
                throw error(v, "expected a parameter ($name), got " + v.show());
            }
            Expr fallback = null;
            if (accept(":")) {
                fallback = spaceList();
            }
            out.add(new Param(v.text(), fallback));
            if (!accept(",")) {
                break;
            }
        }
        expect(")");
        return out;
    }

    private List<Arg> args() {
        expect("(");
        List<Arg> out = new ArrayList<>();
        while (!peek().is(")")) {
            Token t = peek();
            if (t.kind() == Kind.VAR && peekAfter(t).is(":")) {
                next();
                next();
                out.add(new Arg(t.text(), spaceList()));
            } else {
                out.add(new Arg(null, spaceList()));
            }
            if (!accept(",")) {
                break;
            }
        }
        expect(")");
        return out;
    }

    // ---------------------------------------------------------------- expressions

    /** A comma-separated list (or a single space list). */
    Expr commaList() {
        Token first = peek();
        Expr e = spaceList();
        if (!peek().is(",")) {
            return e;
        }
        List<Expr> items = new ArrayList<>();
        items.add(e);
        while (accept(",")) {
            if (!startsExpression(peek())) {
                break; // a trailing comma
            }
            items.add(spaceList());
        }
        return new Ast.ListExpr(at(first), items, true);
    }

    private Expr spaceList() {
        Token first = peek();
        Expr e = or();
        if (!startsExpression(peek())) {
            return e;
        }
        List<Expr> items = new ArrayList<>();
        items.add(e);
        while (startsExpression(peek())) {
            items.add(or());
        }
        return new Ast.ListExpr(at(first), items, false);
    }

    private boolean startsExpression(Token t) {
        return switch (t.kind()) {
            case NUMBER, STRING, HASH, VAR -> true;
            case IDENT -> !t.text().equals("and") && !t.text().equals("or");
            case PUNCT -> t.is("(") || (t.is("-") || t.is("+")) && t.spaceBefore() && !peekAfter(t).spaceBefore()
                && peekAfter(t).kind() != Kind.EOF;
            default -> false;
        };
    }

    private Expr or() {
        Expr left = and();
        while (peek().isIdent("or")) {
            Token op = next();
            left = new Ast.Binary(at(op), "or", left, and());
        }
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (peek().isIdent("and")) {
            Token op = next();
            left = new Ast.Binary(at(op), "and", left, not());
        }
        return left;
    }

    private Expr not() {
        if (peek().isIdent("not")) {
            Token op = next();
            return new Ast.Unary(at(op), "not", not());
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        Token t = peek();
        if (t.is("==") || t.is("!=") || t.is("<") || t.is("<=") || t.is(">") || t.is(">=")) {
            next();
            return new Ast.Binary(at(t), t.text(), left, additive());
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            Token t = peek();
            if (!(t.is("+") || t.is("-"))) {
                return left;
            }
            if (t.spaceBefore() && !peekAfter(t).spaceBefore()) {
                return left; // "1px -2px": a list of two numbers, not a subtraction
            }
            next();
            left = new Ast.Binary(at(t), t.text(), left, multiplicative());
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            Token t = next();
            left = new Ast.Binary(at(t), t.text(), left, unary());
        }
        return left;
    }

    private Expr unary() {
        Token t = peek();
        if (t.is("-") || t.is("+")) {
            next();
            return new Ast.Unary(at(t), t.text(), unary());
        }
        return primary();
    }

    private Expr primary() {
        Token t = next();
        Pos p = at(t);
        switch (t.kind()) {
            case NUMBER -> {
                return new Ast.Literal(p, Value.num(t.number(), t.unit()));
            }
            case STRING -> {
                return new Ast.Literal(p, Value.string(t.text()));
            }
            case HASH -> {
                try {
                    return new Ast.Literal(p, Value.color(Color.hex(t.text())));
                } catch (IllegalArgumentException e) {
                    throw error(t, "not a colour: #" + t.text());
                }
            }
            case VAR -> {
                return new Ast.Var(p, null, t.text());
            }
            case IDENT -> {
                Token after = peek();
                if (after.is("(") && !after.spaceBefore()) {
                    return new Ast.Call(p, null, t.text(), args());
                }
                if (after.is(".") && !after.spaceBefore()) {
                    next();
                    Token member = next();
                    if (member.kind() == Kind.VAR) {
                        return new Ast.Var(p, t.text(), member.text());
                    }
                    if (member.kind() == Kind.IDENT && peek().is("(")) {
                        return new Ast.Call(p, t.text(), member.text(), args());
                    }
                    throw error(member, "expected a variable or a function of " + t.text() + ", got "
                        + member.show());
                }
                return new Ast.Ident(p, t.text());
            }
            case PUNCT -> {
                if (t.is("(")) {
                    return parenthesized(p);
                }
                throw error(t, "expected a value, got " + t.show());
            }
            default -> throw error(t, "expected a value, got " + t.show());
        }
    }

    private Expr parenthesized(Pos p) {
        if (accept(")")) {
            return new Ast.ListExpr(p, List.of(), false);
        }
        Expr first = spaceList();
        if (accept(":")) {
            List<Expr> keys = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            keys.add(first);
            values.add(spaceList());
            while (accept(",")) {
                if (peek().is(")")) {
                    break;
                }
                keys.add(spaceList());
                expect(":");
                values.add(spaceList());
            }
            expect(")");
            return new Ast.MapExpr(p, keys, values);
        }
        if (peek().is(",")) {
            List<Expr> items = new ArrayList<>();
            items.add(first);
            while (accept(",")) {
                if (peek().is(")")) {
                    break;
                }
                items.add(spaceList());
            }
            expect(")");
            return new Ast.ListExpr(p, items, true);
        }
        expect(")");
        return first;
    }
}
