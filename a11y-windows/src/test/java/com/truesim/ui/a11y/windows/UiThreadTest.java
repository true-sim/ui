package com.truesim.ui.a11y.windows;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.a11y.AccessibilityRig;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * A hidden window with the rig's controls and the provider attached, its messages pumped and the UI and provider
 * updated every 2 ms on a UI thread; tests run tasks there ({@link #onUi}). Windows only: skipped elsewhere.
 */
abstract class UiThreadTest {

    Thread uiThread;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    AccessibilityRig rig;
    TestWindow window;
    WindowsAccessibility access;

    @BeforeEach
    void start() throws Exception {
        assumeTrue(WindowsAccessibility.supported(), "UI Automation is Windows only");
        CompletableFuture<Void> ready = new CompletableFuture<>();
        uiThread = new Thread(() -> {
            try {
                rig = new AccessibilityRig();
                window = new TestWindow("true-sim UIA test");
                access = WindowsAccessibility.attach(window.hwnd, rig.bridge);
                ready.complete(null);
                while (running) {
                    window.pump();
                    for (Runnable r = tasks.poll(); r != null; r = tasks.poll()) {
                        r.run();
                    }
                    rig.input.beginFrame();
                    rig.ui.update(800, 600, rig.input, System.nanoTime());
                    access.update();
                    Thread.sleep(2);
                }
                access.close();
                window.destroy();
            } catch (Throwable t) {
                ready.completeExceptionally(t);
            }
        }, "ui");
        uiThread.start();
        ready.get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (uiThread != null) {
            running = false;
            uiThread.join(10_000);
        }
    }

    <T> T onUi(Callable<T> task) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        tasks.add(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    boolean ui(Callable<Boolean> c) {
        try {
            return onUi(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
