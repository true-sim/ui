package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.Value;
import java.util.List;

/** The syntax tree of a {@code .tss} file: statements and expressions, each with its position for messages. */
@AllowAllocation("sheets are compiled at load")
final class Ast {

    private Ast() {
    }

    /** A place in a source file: an offset, turned into a line and column only when a message needs it. */
    record Pos(Lexer source, int offset) {
        String file() {
            return source.file;
        }

        int line() {
            return source.line(offset);
        }

        int column() {
            return source.column(offset);
        }

        @Override
        public String toString() {
            return file() + ":" + line() + ":" + column();
        }
    }

    /**
     * Text with interpolations ({@code button.#{$name}}): parts are strings and expressions, in order.
     */
    record Template(Pos pos, List<Object> parts) {
    }

    /** A parameter of a mixin or function, with its default (or null). */
    record Param(String name, Expr fallback) {
    }

    /** An argument of a call: positional ({@code name} null) or named. */
    record Arg(String name, Expr value) {
    }

    // ---------------------------------------------------------------- statements

    sealed interface Stmt {
        Pos pos();
    }

    record VarDecl(Pos pos, String name, Expr value, boolean isDefault, boolean global, boolean live)
        implements Stmt {
    }

    record Decl(Pos pos, String property, Expr value) implements Stmt {
    }

    record RuleStmt(Pos pos, Template selector, List<Stmt> body) implements Stmt {
    }

    record MixinDef(Pos pos, String name, List<Param> params, List<Stmt> body) implements Stmt {
    }

    record Include(Pos pos, String namespace, String name, List<Arg> args, List<Stmt> content) implements Stmt {
    }

    record Content(Pos pos) implements Stmt {
    }

    record FunctionDef(Pos pos, String name, List<Param> params, List<Stmt> body) implements Stmt {
    }

    record Return(Pos pos, Expr value) implements Stmt {
    }

    record If(Pos pos, Expr condition, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
    }

    record Each(Pos pos, List<String> vars, Expr list, List<Stmt> body) implements Stmt {
    }

    record For(Pos pos, String var, Expr from, Expr to, boolean inclusive, List<Stmt> body) implements Stmt {
    }

    record Use(Pos pos, String path, String namespace) implements Stmt {
    }

    record Import(Pos pos, List<String> paths) implements Stmt {
    }

    record Extend(Pos pos, Template selector) implements Stmt {
    }

    record TokenSet(Pos pos, String name, List<Stmt> body) implements Stmt {
    }

    /** {@code @error} (fails), {@code @warn} and {@code @debug} (collected as warnings). */
    record Message(Pos pos, String kind, Expr value) implements Stmt {
    }

    // ---------------------------------------------------------------- expressions

    sealed interface Expr {
        Pos pos();
    }

    record Literal(Pos pos, Value value) implements Expr {
    }

    record Ident(Pos pos, String name) implements Expr {
    }

    record Var(Pos pos, String namespace, String name) implements Expr {
    }

    record Call(Pos pos, String namespace, String name, List<Arg> args) implements Expr {
    }

    record Binary(Pos pos, String op, Expr left, Expr right) implements Expr {
    }

    record Unary(Pos pos, String op, Expr operand) implements Expr {
    }

    record ListExpr(Pos pos, List<Expr> items, boolean comma) implements Expr {
    }

    record MapExpr(Pos pos, List<Expr> keys, List<Expr> values) implements Expr {
    }
}
