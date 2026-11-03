package com.truesim.ui.style.expr;

/**
 * A value that cannot be used where it is: a unit mismatch, a wrong argument, a value a style key does not accept.
 * The {@code .tss} compiler adds the file, line, column, selector and include trace.
 */
public final class ValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** An error with {@code message}. */
    public ValueException(String message) {
        super(message);
    }
}
