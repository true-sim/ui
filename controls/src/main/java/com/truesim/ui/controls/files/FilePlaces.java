package com.truesim.ui.controls.files;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.ArrayList;
import java.util.List;

/**
 * The places a player keeps in a file browser's side list: favourite folders (in the order added) and recent ones
 * (newest first, at most {@link #RECENT_LIMIT}). A version grows with every change, so browsers update their lists
 * only then. {@link #describe()} and {@link #restore} carry them across sessions (a game saves the text where it
 * saves settings).
 */
public final class FilePlaces {

    /** How many recent places a file browser remembers. */
    public static final Tunable RECENT_LIMIT = Tunable.of("ui.files.recentLimit", 10, 0, 100,
        "How many recently used folders a file browser remembers");

    private final List<String> favourites = new ArrayList<>();
    private final List<String> recent = new ArrayList<>();
    private int version;

    /** The favourite places, in order. */
    public List<String> favourites() {
        return List.copyOf(favourites);
    }

    /** The recent places, newest first. */
    public List<String> recent() {
        return List.copyOf(recent);
    }

    /** Grows with every change. */
    public int version() {
        return version;
    }

    /** Adds {@code path} to the favourites (once); returns this. */
    @AllowAllocation("a user action")
    public FilePlaces favourite(String path) {
        if (!favourites.contains(path)) {
            favourites.add(path);
            version++;
        }
        return this;
    }

    /** Removes {@code path} from the favourites; returns this. */
    @AllowAllocation("a user action")
    public FilePlaces unfavourite(String path) {
        if (favourites.remove(path)) {
            version++;
        }
        return this;
    }

    /** Whether {@code path} is a favourite. */
    public boolean isFavourite(String path) {
        return favourites.contains(path);
    }

    /** Puts {@code path} first among the recent places (a file was opened or saved there); returns this. */
    @AllowAllocation("a user action")
    public FilePlaces used(String path) {
        if (!recent.isEmpty() && recent.get(0).equals(path)) {
            return this;
        }
        recent.remove(path);
        recent.add(0, path);
        int limit = (int) RECENT_LIMIT.get();
        while (recent.size() > limit) {
            recent.remove(recent.size() - 1);
        }
        version++;
        return this;
    }

    /** The places as text: {@code favourite <path>} and {@code recent <path>} lines. */
    @AllowAllocation("asked when saving")
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (String f : favourites) {
            out.append("favourite ").append(f).append('\n');
        }
        for (String r : recent) {
            out.append("recent ").append(r).append('\n');
        }
        return out.toString();
    }

    /** Replaces the places with those {@link #describe()} gave. */
    @AllowAllocation("restored when loading")
    public void restore(String description) {
        favourites.clear();
        recent.clear();
        for (String line : description.split("\n")) {
            if (line.startsWith("favourite ")) {
                favourites.add(line.substring(10));
            } else if (line.startsWith("recent ")) {
                recent.add(line.substring(7));
            }
        }
        version++;
    }

    /** The tunables of the file browser. */
    public static List<Tunable> tunables() {
        return List.of(RECENT_LIMIT);
    }
}
