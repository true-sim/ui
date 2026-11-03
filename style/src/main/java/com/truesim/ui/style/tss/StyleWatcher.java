package com.truesim.ui.style.tss;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.Versioned;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.StyleSheet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot reload of a {@code .tss} sheet during development (until the asset system's folder mounts, D25, take over): a
 * {@link WatchService} over the directories of the sheet and every partial it reads recompiles it when one of them
 * changes. A good compile is handed to the UI thread through {@link #poll()} (or {@link #apply(StyleResolver)}); a bad
 * one keeps the last good sheet and reports the error to the {@link Listener}, so a typo never blanks the UI.
 *
 * <pre>{@code
 * StyleWatcher watcher = StyleWatcher.file(Path.of("ui/hud.tss")).start();
 * StyleResolver resolver = new StyleResolver(store, watcher.sheet());
 * // each frame, on the UI thread: one atomic read unless a reload is waiting
 * watcher.apply(resolver);
 * }</pre>
 */
public final class StyleWatcher implements AutoCloseable, Versioned {

    /**
     * How long to wait after a change before compiling, in milliseconds, so an editor's several writes of one save
     * compile once. A setting, as every tunable.
     */
    public static final Tunable DELAY = Tunable.of("ui.style.reloadDelayMs", 50, 0, 2000,
        "Milliseconds to wait after a style sheet changes before reloading it");

    /** Hears about reloads, on the thread that compiled (the watch thread, or the caller of {@link #reload()}). */
    public interface Listener {
        /** A new sheet compiled. */
        default void reloaded(StyleSheet sheet) {
        }

        /** A change did not compile; the last good sheet stays. */
        default void failed(TssException error) {
        }
    }

    private final SourceLoader loader;
    private final String path;
    private final Class<?>[] keyHolders;
    private final AtomicReference<StyleSheet> pending = new AtomicReference<>();
    private volatile StyleSheet sheet;
    private volatile TssException lastError;
    private volatile List<String> sources;
    private volatile Listener listener = new Listener() {
    };
    private volatile long version;
    private WatchService watch;
    private Thread thread;

    /**
     * A watcher of {@code path} from {@code loader} (whose {@link SourceLoader#file} names the files to watch); it
     * compiles the sheet now.
     *
     * @throws TssException if the sheet does not compile at first
     */
    @AllowAllocation("made once, in development")
    public StyleWatcher(SourceLoader loader, String path, Class<?>... keyHolders) {
        this.loader = loader;
        this.path = path;
        this.keyHolders = keyHolders.clone();
        Compilation c = Tss.compile(loader, path, keyHolders);
        this.sheet = c.sheet();
        this.sources = c.sources();
    }

    /** A watcher of the file {@code file} and the partials beside it. */
    @AllowAllocation("made once, in development")
    public static StyleWatcher file(Path file, Class<?>... keyHolders) {
        Path absolute = file.toAbsolutePath().normalize();
        return new StyleWatcher(SourceLoader.files(absolute.getParent()), absolute.getFileName().toString(),
            keyHolders);
    }

    /** Sets the listener. */
    public StyleWatcher listener(Listener newListener) {
        this.listener = newListener;
        return this;
    }

    /** The last sheet that compiled. */
    public StyleSheet sheet() {
        return sheet;
    }

    /** The error of the last reload if it failed, else null. */
    public TssException lastError() {
        return lastError;
    }

    /** The source paths of the last good compile: the sheet and its partials. */
    public List<String> sources() {
        return sources;
    }

    /** Grows with every good reload. */
    @Override
    public long version() {
        return version;
    }

    /**
     * Compiles the sheet now. On success the new sheet is {@link #sheet()}, waits for {@link #poll()} and goes to the
     * listener; on failure the last good sheet stays and the error goes to the listener.
     *
     * @return whether it compiled
     */
    @AllowAllocation("a reload, in development")
    public synchronized boolean reload() {
        Compilation c;
        try {
            c = Tss.compile(loader, path, keyHolders);
        } catch (TssException e) {
            lastError = e;
            listener.failed(e);
            return false;
        } catch (UncheckedIOException e) {
            TssException error = new TssException("cannot read: " + e.getCause().getMessage(), path, 1, 1, null,
                List.of());
            lastError = error;
            listener.failed(error);
            return false;
        }
        lastError = null;
        sheet = c.sheet();
        boolean moreFiles = !c.sources().equals(sources);
        sources = c.sources();
        version++;
        pending.set(c.sheet());
        if (moreFiles && watch != null) {
            register();
        }
        listener.reloaded(c.sheet());
        return true;
    }

    /** The sheet reloaded since the last poll, or null: one atomic read, for the UI thread each frame. */
    public StyleSheet poll() {
        return pending.get() == null ? null : pending.getAndSet(null);
    }

    /** Hands a reloaded sheet to {@code resolver} if there is one; returns whether there was. */
    public boolean apply(StyleResolver resolver) {
        StyleSheet next = poll();
        if (next == null) {
            return false;
        }
        resolver.sheet(next);
        return true;
    }

    /**
     * Starts watching the directories of the sheet and its partials on a daemon thread.
     *
     * @throws IllegalStateException if the loader has no files to watch
     * @throws UncheckedIOException if the file system cannot be watched
     */
    @AllowAllocation("made once, in development")
    public synchronized StyleWatcher start() {
        if (thread != null) {
            return this;
        }
        if (loader.file(path) == null) {
            throw new IllegalStateException("the sheet " + path + " is not a file, so it cannot be watched");
        }
        try {
            watch = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        register();
        thread = new Thread(this::run, "tss-watch " + path);
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    @AllowAllocation("registering directories, in development")
    private void register() {
        Set<Path> dirs = new HashSet<>();
        for (String source : sources) {
            Path file = loader.file(source);
            if (file != null && file.getParent() != null) {
                dirs.add(file.getParent());
            }
        }
        for (Path dir : dirs) {
            try {
                dir.register(watch, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            } catch (IOException e) {
                lastError = new TssException("cannot watch " + dir + ": " + e.getMessage(), path, 1, 1, null,
                    List.of());
            }
        }
    }

    /** Whether {@code changed} in {@code dir} is one of the sheet's sources. */
    @AllowAllocation("a file change, in development")
    private boolean isSource(Path dir, Path changed) {
        Path file = dir.resolve(changed).normalize();
        for (String source : sources) {
            if (file.equals(loader.file(source))) {
                return true;
            }
        }
        return false;
    }

    @AllowAllocation("the watch thread, in development")
    private void run() {
        WatchService service = watch;
        try {
            while (true) {
                WatchKey key = service.take();
                boolean relevant = drain(key);
                // let the editor finish writing, and fold several events into one compile
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos((long) DELAY.get());
                for (long left = until - System.nanoTime(); left > 0; left = until - System.nanoTime()) {
                    WatchKey more = service.poll(left, TimeUnit.NANOSECONDS);
                    if (more != null) {
                        relevant |= drain(more);
                    }
                }
                if (relevant) {
                    reload();
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // closed: the thread ends
        }
    }

    @AllowAllocation("a file change, in development")
    private boolean drain(WatchKey key) {
        boolean relevant = false;
        Path dir = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.context() instanceof Path changed && isSource(dir, changed)) {
                relevant = true;
            }
        }
        key.reset();
        return relevant;
    }

    /** Stops watching. */
    @Override
    @AllowAllocation("closing, in development")
    public synchronized void close() {
        if (watch != null) {
            try {
                watch.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            thread.interrupt();
            watch = null;
            thread = null;
        }
    }
}
