package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import java.util.List;

/**
 * An error in a {@code .tss} sheet: what is wrong, where (file, line, column), in which rule (its selector, or null
 * outside rules) and through which includes and calls ({@link #trace()}, innermost first). The message reads:
 *
 * <pre>
 * theme.tss:12:5: unknown key backgrund (did you mean background?)
 *   in rule button.glass:hover
 *   from @include raised at theme.tss:20:3
 * </pre>
 */
@AllowAllocation("errors are made when a sheet fails to compile")
public final class TssException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String detail;
    private final String file;
    private final int line;
    private final int column;
    private final String selector;
    private final transient List<String> trace;

    /** An error. */
    public TssException(String detail, String file, int line, int column, String selector, List<String> trace) {
        super(format(detail, file, line, column, selector, trace));
        this.detail = detail;
        this.file = file;
        this.line = line;
        this.column = column;
        this.selector = selector;
        this.trace = List.copyOf(trace);
    }

    private static String format(String detail, String file, int line, int column, String selector,
        List<String> trace) {
        StringBuilder sb = new StringBuilder().append(file).append(':').append(line).append(':').append(column)
            .append(": ").append(detail);
        if (selector != null) {
            sb.append("\n  in rule ").append(selector);
        }
        for (String frame : trace) {
            sb.append("\n  from ").append(frame);
        }
        return sb.toString();
    }

    /** What is wrong, without the place. */
    public String detail() {
        return detail;
    }

    /** The source file. */
    public String file() {
        return file;
    }

    /** The 1-based line. */
    public int line() {
        return line;
    }

    /** The 1-based column. */
    public int column() {
        return column;
    }

    /** The selector of the rule the error is in, or null. */
    public String selector() {
        return selector;
    }

    /** The includes and calls that led here, innermost first: {@code "@include raised at theme.tss:20:3"}. */
    public List<String> trace() {
        return trace;
    }
}
