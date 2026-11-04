package com.truesim.ui.console;

/**
 * Where log records go (design UI28): the engine's SLF4J provider hands every record of its loggers to a sink,
 * preformatted (the message with its arguments filled in), so the console keeps text, never argument objects.
 * {@link LogRing} keeps them for the UI thread; {@link LogInbox} takes them from any thread. Implementations must not
 * allocate in steady state: a log flood is the case this is built for.
 */
@FunctionalInterface
public interface LogSink {

    /**
     * One record: its {@link LogLevel}, the logger's name ({@code com.truesim.render.Bloom}), the formatted message,
     * where it was logged ({@code Bloom.java:42}, or null) and when (milliseconds since the epoch). The character
     * sequences are read during the call only.
     */
    void log(int level, CharSequence logger, CharSequence message, CharSequence source, long epochMillis);

    /** A record at {@code level} without a source position, now. */
    default void log(int level, CharSequence logger, CharSequence message) {
        log(level, logger, message, null, System.currentTimeMillis());
    }
}
