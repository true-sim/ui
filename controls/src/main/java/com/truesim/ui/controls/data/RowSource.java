package com.truesim.ui.controls.data;

import com.truesim.common.state.IndexVisitor;
import com.truesim.common.state.VersionedList;

/**
 * The rows a virtualized view shows, versioned so it updates only what changed (design 4.2): a
 * {@link VersionedList} ({@link #of}), a sorted and filtered {@link DataView}, a tree's visible rows. Versions are
 * times of one {@code ChangeClock}.
 *
 * <ul>
 *   <li>{@link #version()} grows with any change; an idle view compares it and stops.</li>
 *   <li>{@link #changesSince} visits rows whose values changed in place; a view rebinds those it shows.</li>
 *   <li>{@link #structureVersion()} grows when rows moved, came or went: a view reconciles its visible window by
 *       {@link #key} (a row keeps its view while it stays visible, wherever it moved) and {@link #version(int)}.</li>
 * </ul>
 *
 * @param <T> the row type
 */
public interface RowSource<T> {

    /** How many rows. */
    int size();

    /** Row {@code index}. */
    T get(int index);

    /** Row {@code index}'s identity across structural changes (its index in the underlying list, a node id). */
    long key(int index);

    /** When row {@code index}'s value last changed. */
    long version(int index);

    /** When anything changed. */
    long version();

    /** When rows last moved, came or went. */
    long structureVersion();

    /**
     * Visits the rows whose values changed after {@code since} without moving and returns true; false when that
     * cannot be told (rows moved since, the log overflowed): then reconcile by keys.
     */
    boolean changesSince(long since, IndexVisitor visitor);

    /** Brings a derived source up to date with what it derives from; called by views once a frame. */
    default void refresh() {
    }

    /** The rows of {@code list}: keys are indices. */
    static <T> RowSource<T> of(VersionedList<T> list) {
        return new ListRows<>(list);
    }

    /** {@link #of}'s rows. */
    final class ListRows<T> implements RowSource<T> {

        private final VersionedList<T> list;

        ListRows(VersionedList<T> list) {
            this.list = list;
        }

        /** The list. */
        public VersionedList<T> list() {
            return list;
        }

        @Override
        public int size() {
            return list.size();
        }

        @Override
        public T get(int index) {
            return list.get(index);
        }

        @Override
        public long key(int index) {
            return index;
        }

        @Override
        public long version(int index) {
            return list.version(index);
        }

        @Override
        public long version() {
            return list.version();
        }

        @Override
        public long structureVersion() {
            return list.structureVersion();
        }

        @Override
        public boolean changesSince(long since, IndexVisitor visitor) {
            return list.changesSince(since, visitor);
        }
    }
}
