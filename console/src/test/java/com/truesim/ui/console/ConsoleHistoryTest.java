package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** History per app id: walking, the line being typed, repeats, the limit, persistence. */
class ConsoleHistoryTest {

    @Test
    void walksOlderAndNewerKeepingTheDraft() {
        HistoryStore store = HistoryStore.memory();
        ConsoleHistory h = new ConsoleHistory("game", store);
        assertThat(h.older("x")).isNull();
        h.add("a");
        h.add("b");
        h.add("b"); // a repeat is kept once
        h.add("  ");
        assertThat(h.lines()).containsExactly("a", "b");
        assertThat(h.older("typing")).isEqualTo("b");
        assertThat(h.older("b")).isEqualTo("a");
        assertThat(h.older("a")).isNull();
        assertThat(h.newer()).isEqualTo("b");
        assertThat(h.newer()).isEqualTo("typing");
        assertThat(h.newer()).isNull();
        h.older("again");
        h.reset();
        assertThat(h.older("z")).isEqualTo("b");
        assertThat(h.appId()).isEqualTo("game");
    }

    @Test
    void itIsKeptPerAppIdAndLimited() {
        HistoryStore store = HistoryStore.memory();
        ConsoleHistory game = new ConsoleHistory("game", store);
        game.add("one");
        new ConsoleHistory("editor", store).add("two");
        assertThat(new ConsoleHistory("game", store).lines()).containsExactly("one");
        assertThat(store.load("editor")).containsExactly("two");
        assertThat(store.load("none")).isEmpty();
        float limit = ConsoleTunables.HISTORY_LIMIT.get();
        try {
            ConsoleTunables.HISTORY_LIMIT.set(3);
            ConsoleHistory h = new ConsoleHistory("limited", store);
            for (String s : List.of("1", "2", "3", "4", "5")) {
                h.add(s);
            }
            assertThat(h.lines()).containsExactly("3", "4", "5");
            assertThat(new ConsoleHistory("limited", store).lines()).containsExactly("3", "4", "5");
        } finally {
            ConsoleTunables.HISTORY_LIMIT.set(limit);
        }
    }
}
