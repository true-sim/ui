package com.truesim.ui.console;

/**
 * The levels of console log records, as ints so a record is a few primitives: SLF4J's five levels, and
 * {@link #COMMAND} for the lines the console echoes when it runs one. A level's bit ({@link #bit}) is what level
 * filters hold.
 */
public final class LogLevel {

    public static final int TRACE = 0;
    public static final int DEBUG = 1;
    public static final int INFO = 2;
    public static final int WARN = 3;
    public static final int ERROR = 4;
    /** A command line the console ran, echoed into the log. */
    public static final int COMMAND = 5;
    /** How many levels exist. */
    public static final int COUNT = 6;
    /** Every level's bit. */
    public static final int ALL = (1 << COUNT) - 1;

    private static final String[] NAMES = {"trace", "debug", "info", "warn", "error", "command"};

    private LogLevel() {
    }

    /** The bit of {@code level} in a level mask. */
    public static int bit(int level) {
        return 1 << level;
    }

    /** The bits of {@code level} and every more severe level (not {@link #COMMAND}), plus {@link #COMMAND}'s. */
    public static int atLeast(int level) {
        return (bit(ERROR + 1) - bit(level)) | bit(COMMAND);
    }

    /** The lower-case name of {@code level} ({@code warn}): the {@code level} fact of log rows. */
    public static String name(int level) {
        return NAMES[level];
    }

    /** The level named {@code name} (any case; {@code warning} too), or -1. */
    public static int of(String name) {
        for (int i = 0; i < COUNT; i++) {
            if (NAMES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return "warning".equalsIgnoreCase(name) ? WARN : -1;
    }
}
