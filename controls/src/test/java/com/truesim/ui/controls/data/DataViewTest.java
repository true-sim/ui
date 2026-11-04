package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Sorted and filtered views against brute force, under random edits, appends, inserts and removals. */
class DataViewTest {

    record Item(int key, String name) {
    }

    static final Comparator<Item> BY_KEY = Comparator.comparingInt(Item::key);
    static final Predicate<Item> EVEN = i -> i.key() % 2 == 0;

    /** What the view must show: the source's indices passing the filter, stably sorted. */
    static List<Integer> expected(VersionedList<Item> source, Predicate<? super Item> filter,
        Comparator<? super Item> order) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            if (filter == null || filter.test(source.get(i))) {
                out.add(i);
            }
        }
        if (order != null) {
            out.sort((a, b) -> {
                int c = order.compare(source.get(a), source.get(b));
                return c != 0 ? c : Integer.compare(a, b);
            });
        }
        return out;
    }

    static List<Integer> shown(DataView<Item> view) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < view.size(); i++) {
            out.add(view.sourceIndex(i));
            assertThat(view.get(i)).isSameAs(view.source().get(view.sourceIndex(i)));
            assertThat(view.key(i)).isEqualTo(view.sourceIndex(i));
            assertThat(view.positionOf(view.sourceIndex(i))).isEqualTo(i);
        }
        return out;
    }

    @Test
    void randomEditsKeepTheViewEqualToBruteForce() {
        Random random = new Random(42);
        ChangeClock clock = new ChangeClock();
        VersionedList<Item> source = clock.list(64);
        for (int i = 0; i < 3_000; i++) {
            source.add(new Item(random.nextInt(500), "n" + i));
        }
        DataView<Item> view = new DataView<>(clock, source).filter(EVEN).sort(BY_KEY);
        view.refresh();
        assertThat(shown(view)).isEqualTo(expected(source, EVEN, BY_KEY));
        for (int round = 0; round < 300; round++) {
            int edits = 1 + random.nextInt(round % 10 == 0 ? 120 : 8); // some rounds overflow the 64-entry log
            for (int e = 0; e < edits; e++) {
                int op = random.nextInt(20);
                if (op == 0) {
                    source.add(new Item(random.nextInt(500), "appended"));
                } else if (op == 1 && round % 7 == 0) {
                    source.add(random.nextInt(source.size()), new Item(random.nextInt(500), "inserted"));
                } else if (op == 2 && round % 7 == 0 && source.size() > 10) {
                    source.remove(random.nextInt(source.size()));
                } else {
                    int i = random.nextInt(source.size());
                    source.set(i, new Item(random.nextInt(500), source.get(i).name()));
                }
            }
            view.refresh();
            assertThat(shown(view)).as("round %d", round).isEqualTo(expected(source, EVEN, BY_KEY));
        }
        // a new order and a new filter rebuild
        Comparator<Item> byName = Comparator.comparing(Item::name);
        view.sort(byName).filter(null);
        view.refresh();
        assertThat(shown(view)).isEqualTo(expected(source, null, byName));
        view.sort(null).filter(i -> i.key() > 250);
        view.refresh();
        assertThat(shown(view)).isEqualTo(expected(source, view.filter(), null));
        assertThat(view.comparator()).isNull();
    }

    @Test
    void inPlaceChangesAreLoggedAndMovesChangeTheStructure() {
        ChangeClock clock = new ChangeClock();
        VersionedList<Item> source = clock.list();
        for (int i = 0; i < 10; i++) {
            source.add(new Item(i * 10, "n" + i));
        }
        DataView<Item> view = new DataView<>(clock, source).sort(BY_KEY);
        view.refresh();
        long structure = view.structureVersion();
        long since = view.version();
        // a new value keeping its place: in place
        source.set(3, new Item(31, "n3'"));
        view.refresh();
        assertThat(view.structureVersion()).isEqualTo(structure);
        assertThat(view.version()).isGreaterThan(since);
        List<Integer> visited = new ArrayList<>();
        assertThat(view.changesSince(since, visited::add)).isTrue();
        assertThat(visited).containsExactly(3);
        assertThat(view.version(3)).isEqualTo(source.version(3));
        // nothing new: an idle refresh changes nothing
        long v = view.version();
        view.refresh();
        assertThat(view.version()).isEqualTo(v);
        // a value moving: structural
        source.set(3, new Item(95, "n3''"));
        view.refresh();
        assertThat(view.structureVersion()).isGreaterThan(structure);
        assertThat(view.changesSince(since, visited::add)).isFalse();
        assertThat(view.sourceIndex(9)).isEqualTo(3);
        // a filtered-out row changing stays out and changes nothing
        view.filter(i -> i.key() < 90);
        view.refresh();
        long before = view.version();
        source.set(3, new Item(99, "still out"));
        view.refresh();
        assertThat(view.version()).isEqualTo(before);
        assertThat(view.positionOf(3)).isEqualTo(-1);
        assertThat(view.known()).isEqualTo(10);
    }

    @Test
    void theOrderIndexSplitsAndMergesBlocks() {
        OrderIndex index = new OrderIndex();
        OrderIndex.Order natural = Integer::compare;
        index.capacity(5_000);
        Random random = new Random(7);
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            int v = random.nextInt(5_000);
            if (!index.contains(v)) {
                int p = index.insert(v, natural);
                int at = 0;
                while (at < expected.size() && expected.get(at) < v) {
                    at++;
                }
                expected.add(at, v);
                assertThat(p).isEqualTo(at);
            }
        }
        for (int i = 0; i < expected.size(); i++) {
            assertThat(index.get(i)).isEqualTo(expected.get(i));
        }
        while (!expected.isEmpty()) {
            int k = random.nextInt(expected.size());
            int v = expected.remove(k);
            assertThat(index.positionOf(v)).isEqualTo(k);
            index.remove(v);
        }
        assertThat(index.size()).isZero();
        assertThat(index.insert(3, natural)).isZero();
    }
}
