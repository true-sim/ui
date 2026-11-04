package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** The log ring, its inbox and its filtered rows: wrapping, coalescing, filters, search, allocation. */
class LogRingTest {

    private static List<String> messages(LogRows rows) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(rows.get(i).message().toString());
        }
        return out;
    }

    @Test
    void theRingKeepsTheLatestRecords() {
        LogRing ring = new LogRing(4);
        assertThat(ring.last()).isNull();
        for (int i = 0; i < 6; i++) {
            ring.log(LogLevel.INFO, "a", "line " + i, null, i);
        }
        assertThat(ring.size()).isEqualTo(4);
        assertThat(ring.oldest()).isEqualTo(2);
        assertThat(ring.next()).isEqualTo(6);
        assertThat(ring.get(0).message().toString()).isEqualTo("line 2");
        assertThat(ring.record(1)).isNull();
        assertThat(ring.record(6)).isNull();
        assertThat(ring.record(5).seq()).isEqualTo(5);
        assertThat(ring.last().time()).isEqualTo(5);
        assertThat(ring.capacity()).isEqualTo(4);
        ring.clear();
        assertThat(ring.size()).isZero();
        assertThat(ring.last()).isNull();
        assertThat(ring.clears()).isEqualTo(1);
        ring.log(LogLevel.WARN, "b", "after", "B.java:3", 9);
        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.last().seq()).isEqualTo(6);
        assertThat(ring.last().source().toString()).isEqualTo("B.java:3");
        assertThat(ring.last().logger()).isEqualTo("b");
        assertThat(ring.last().toString()).isEqualTo("warn b: after");
    }

    @Test
    void repeatsAreCoalesced() {
        LogRing ring = new LogRing(8);
        ring.log(LogLevel.WARN, "net", "timeout", "Net.java:10", 1);
        long structure = ring.structureVersion();
        ring.log(LogLevel.WARN, "net", "timeout", "Net.java:10", 2);
        ring.log(LogLevel.WARN, "net", "timeout", "Net.java:10", 3);
        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.last().count()).isEqualTo(3);
        assertThat(ring.last().time()).isEqualTo(1);
        assertThat(ring.structureVersion()).isEqualTo(structure);
        assertThat(ring.version()).isGreaterThan(structure);
        assertThat(ring.last().toString()).endsWith("(x3)");
        ring.log(LogLevel.WARN, "net", "timeout", "Net.java:11", 4); // another source
        ring.log(LogLevel.ERROR, "net", "timeout", "Net.java:11", 4); // another level
        ring.log(LogLevel.ERROR, "io", "timeout", "Net.java:11", 4); // another logger
        ring.log(LogLevel.ERROR, "io", "timeout", null, 4); // no source
        ring.log(LogLevel.ERROR, "io", "timeout", null, 4);
        ring.log(LogLevel.ERROR, "io", "timeouts", null, 4);
        ring.log(LogLevel.ERROR, "io", "timeout!", null, 4);
        assertThat(ring.size()).isEqualTo(7);
        assertThat(ring.get(4).count()).isEqualTo(2);
    }

    @Test
    void longLinesAreCutAndControlCharactersFlattened() {
        LogRing ring = new LogRing(2);
        String longLine = "x".repeat(5000);
        ring.log(LogLevel.INFO, null, longLine, null, 0);
        CharSequence m = ring.last().message();
        assertThat(m.length()).isEqualTo((int) ConsoleTunables.MAX_LINE.get());
        assertThat(m.charAt(m.length() - 1)).isEqualTo('\u2026');
        ring.log(LogLevel.INFO, "a", "two\nlines\tand tab", null, 0);
        assertThat(ring.last().message().toString()).isEqualTo("two lines and tab");
        assertThat(ring.last().message().subSequence(0, 3).toString()).isEqualTo("two");
        assertThat(ring.last().source()).isNull();
        ring.log(LogLevel.INFO, "a", null, null, 0);
        assertThat(ring.last().message().length()).isZero();
        assertThat(ring.loggerName(ring.last().loggerIndex())).isEqualTo("a");
    }

    @Test
    void loggersAreInternedOnce() {
        LogRing ring = new LogRing(4);
        for (int i = 0; i < 200; i++) {
            assertThat(ring.logger("logger." + i)).isEqualTo(i);
        }
        assertThat(ring.logger(new StringBuilder("logger.17"))).isEqualTo(17);
        assertThat(ring.loggerCount()).isEqualTo(200);
    }

    @Test
    void rowsFilterByLevelLoggerAndSearch() {
        LogRing ring = new LogRing(16);
        LogRows rows = new LogRows(ring);
        assertThat(rows.ring()).isSameAs(ring);
        ring.log(LogLevel.INFO, "com.truesim.render", "frame ready", null, 0);
        ring.log(LogLevel.WARN, "com.truesim.render.Bloom", "bloom slow", null, 0);
        ring.log(LogLevel.ERROR, "com.truesim.audio", "device lost", null, 0);
        ring.log(LogLevel.DEBUG, "com.truesim.renderer", "other logger", null, 0);
        rows.refresh();
        assertThat(rows.size()).isEqualTo(4);
        rows.levels(LogLevel.atLeast(LogLevel.WARN)).refresh();
        assertThat(messages(rows)).containsExactly("bloom slow", "device lost");
        assertThat(rows.levels()).isEqualTo(LogLevel.atLeast(LogLevel.WARN));
        rows.levels(LogLevel.ALL).logger("com.truesim.render").refresh();
        assertThat(messages(rows)).containsExactly("frame ready", "bloom slow");
        assertThat(rows.logger()).isEqualTo("com.truesim.render");
        ring.log(LogLevel.INFO, "com.truesim.render.Shadows", "shadows ready", null, 0);
        rows.refresh();
        assertThat(messages(rows)).containsExactly("frame ready", "bloom slow", "shadows ready");
        rows.logger("").search("READY").refresh();
        assertThat(rows.logger()).isNull();
        assertThat(messages(rows)).containsExactly("frame ready", "shadows ready");
        assertThat(rows.search().toString()).isEqualTo("READY");
        rows.search("").refresh();
        assertThat(rows.size()).isEqualTo(5);
        assertThat(rows.indexOf(rows.seq(2))).isEqualTo(2);
        assertThat(rows.indexOf(999)).isEqualTo(-1);
        assertThat(rows.key(1)).isEqualTo(rows.seq(1));
    }

    @Test
    void searchNarrowsIncrementallyAndWidensByRescanning() {
        LogRing ring = new LogRing(64);
        LogRows rows = new LogRows(ring);
        for (int i = 0; i < 40; i++) {
            ring.log(LogLevel.INFO, "a", "item " + i, null, 0);
        }
        rows.search("item 1").refresh();
        assertThat(rows.size()).isEqualTo(11);
        long structure = rows.structureVersion();
        rows.search("item 1").refresh(); // unchanged: nothing happens
        assertThat(rows.structureVersion()).isEqualTo(structure);
        rows.search("item 12").refresh(); // narrower: filters the matches
        assertThat(messages(rows)).containsExactly("item 12");
        assertThat(rows.structureVersion()).isGreaterThan(structure);
        rows.search("item 2").refresh(); // another: scans the log again
        assertThat(rows.size()).isEqualTo(11);
        rows.search("item").refresh();
        assertThat(rows.size()).isEqualTo(40);
    }

    @Test
    void rowsFollowTheRingAsItWraps() {
        LogRing ring = new LogRing(8);
        LogRows rows = new LogRows(ring);
        for (int i = 0; i < 20; i++) {
            ring.log(LogLevel.INFO, "a", "line " + i, null, 0);
            if (i % 3 == 0) {
                rows.refresh();
            }
        }
        rows.refresh();
        assertThat(messages(rows)).containsExactly("line 12", "line 13", "line 14", "line 15", "line 16", "line 17",
            "line 18", "line 19");
        ring.clear();
        rows.refresh();
        assertThat(rows.size()).isZero();
    }

    @Test
    void coalescingChangesTheLastRowInPlace() {
        LogRing ring = new LogRing(8);
        LogRows rows = new LogRows(ring);
        ring.log(LogLevel.INFO, "a", "one", null, 0);
        ring.log(LogLevel.INFO, "a", "two", null, 0);
        rows.refresh();
        long seen = rows.version();
        assertThat(rows.changesSince(seen, i -> { throw new AssertionError(); })).isTrue();
        ring.log(LogLevel.INFO, "a", "two", null, 0);
        rows.refresh();
        assertThat(rows.version()).isGreaterThan(seen);
        assertThat(rows.structureVersion()).isLessThanOrEqualTo(seen);
        List<Integer> changed = new ArrayList<>();
        assertThat(rows.changesSince(seen, changed::add)).isTrue();
        assertThat(changed).containsExactly(1);
        assertThat(rows.version(1)).isGreaterThan(seen);
        ring.log(LogLevel.INFO, "a", "three", null, 0);
        rows.refresh();
        assertThat(rows.changesSince(seen, changed::add)).isFalse(); // rows came: reconcile by keys
    }

    @Test
    void theInboxTakesRecordsFromAnyThread() throws InterruptedException {
        LogInbox inbox = new LogInbox(10_000);
        LogRing ring = new LogRing(10_000);
        int threads = 4;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 1000; i++) {
                    inbox.log(LogLevel.INFO, "thread." + id, "message " + i, "T.java:" + i, i);
                }
                done.countDown();
            });
        }
        done.await();
        assertThat(inbox.waiting()).isEqualTo(4000);
        assertThat(inbox.drainTo(ring)).isEqualTo(4000);
        assertThat(ring.size()).isEqualTo(4000);
        assertThat(ring.loggerCount()).isEqualTo(4);
        assertThat(inbox.waiting()).isZero();
        assertThat(inbox.dropped()).isZero();
    }

    @Test
    void aFullInboxDropsItsOldestRecords() {
        LogInbox inbox = new LogInbox(3);
        for (int i = 0; i < 5; i++) {
            inbox.log(LogLevel.INFO, "a", "m" + i, i == 4 ? "S:1" : null, i);
        }
        assertThat(inbox.dropped()).isEqualTo(2);
        LogRing ring = new LogRing(8);
        inbox.drainTo(ring);
        assertThat(ring.get(0).message().toString()).isEqualTo("m2");
        assertThat(ring.last().source().toString()).isEqualTo("S:1");
        inbox.log(LogLevel.INFO, null, "x".repeat(300), null, 0);
        inbox.drainTo((level, logger, message, source, time) -> {
            assertThat(logger.toString()).isEmpty();
            assertThat(message.length()).isEqualTo(300);
            assertThat(message.subSequence(0, 2).toString()).isEqualTo("xx");
        });
    }

    @Test
    void aBurstLargerThanTheRingHandsOnlyWhatTheRingKeeps() {
        LogInbox inbox = new LogInbox(100);
        for (int i = 0; i < 100; i++) {
            inbox.log(LogLevel.INFO, "a", "m" + i, null, i);
        }
        LogRing ring = new LogRing(10);
        assertThat(inbox.drainTo(ring)).isEqualTo(100);
        assertThat(ring.size()).isEqualTo(10);
        assertThat(ring.next()).isEqualTo(10);
        assertThat(ring.get(0).message().toString()).isEqualTo("m90");
    }

    @Test
    void levelsHaveNamesAndBits() {
        assertThat(LogLevel.name(LogLevel.WARN)).isEqualTo("warn");
        assertThat(LogLevel.of("WARNING")).isEqualTo(LogLevel.WARN);
        assertThat(LogLevel.of("Error")).isEqualTo(LogLevel.ERROR);
        assertThat(LogLevel.of("loud")).isEqualTo(-1);
        assertThat(LogLevel.atLeast(LogLevel.ERROR)).isEqualTo(LogLevel.bit(LogLevel.ERROR) | LogLevel.bit(LogLevel.COMMAND));
        assertThat(ConsoleTunables.all()).hasSize(5);
    }

    @Test
    void appendingAndFilteringAllocateNothing() {
        LogRing ring = new LogRing(1000);
        LogRows rows = new LogRows(ring).levels(LogLevel.atLeast(LogLevel.INFO)).logger("game");
        LogInbox inbox = new LogInbox(1000);
        String[] messages = new String[16];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = "message number " + i + " with some text";
        }
        int[] step = {0};
        Runnable append = () -> {
            for (int k = 0; k < 100; k++) {
                int s = step[0]++;
                ring.log(s % 5, s % 3 == 0 ? "game.world" : "engine", messages[s % messages.length], "Game.java:12",
                    s);
                ring.log(s % 5, "game", messages[s % messages.length], null, s); // a repeat now and then
            }
            rows.search(step[0] % 400 < 200 ? "number" : "number 1");
            rows.refresh();
        };
        Runnable throughInbox = () -> {
            for (int k = 0; k < 100; k++) {
                int s = step[0]++;
                inbox.log(LogLevel.WARN, "game", messages[s % messages.length], "Game.java:7", s);
            }
            inbox.drainTo(ring);
            rows.refresh();
        };
        for (int i = 0; i < 200; i++) {
            append.run();
            throughInbox.run();
        }
        AllocationAssertions.assertAllocationFree(append);
        AllocationAssertions.assertAllocationFree(throughInbox);
    }
}
