package com.truesim.ui.controls.files;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A file system in memory, for tests and galleries: folders and files with sizes and times set by code, paths with
 * {@code /} separators under roots such as {@code /} or {@code D:/}. Every change bumps the changed folder's version,
 * so its watch reports it. Nothing touches the disk.
 *
 * <pre>{@code
 * MemoryFileModel files = new MemoryFileModel("/home/player");
 * files.file("/home/player/saves/slot1.sav", 2048, time);
 * }</pre>
 */
@AllowAllocation("file system calls run when a player navigates, not per frame")
public final class MemoryFileModel implements FileModel {

    private static final class Node {
        final boolean directory;
        long size;
        long modified;
        boolean hidden;
        final List<String> children = new ArrayList<>();
        int version;

        Node(boolean directory) {
            this.directory = directory;
        }
    }

    private final Map<String, Node> nodes = new HashMap<>();
    private final List<String> roots = new ArrayList<>();
    private final String home;
    private final FilePlaces places = new FilePlaces();
    private long now = 1_700_000_000_000L;

    /** A file system with the root {@code /} and the folder {@code home} (made). */
    public MemoryFileModel(String home) {
        root("/");
        this.home = normalize(home);
        dir(this.home);
    }

    /** Adds a root ({@code D:/}); returns this. */
    public MemoryFileModel root(String root) {
        String r = root.endsWith("/") ? root : root + "/";
        if (!nodes.containsKey(r)) {
            nodes.put(r, new Node(true));
            roots.add(r);
        }
        return this;
    }

    /** Makes the folder {@code path} and its parents; returns this. */
    public MemoryFileModel dir(String path) {
        String p = normalize(path);
        if (nodes.containsKey(p)) {
            return this;
        }
        String parent = parent(p);
        if (parent == null) {
            return root(p);
        }
        dir(parent);
        add(parent, p, new Node(true));
        return this;
    }

    /** Makes the file {@code path} (and its folders) of {@code size} bytes modified at {@code modified}. */
    public MemoryFileModel file(String path, long size, long modified) {
        String p = normalize(path);
        String parent = parent(p);
        dir(parent);
        Node n = nodes.get(p);
        if (n == null) {
            n = new Node(false);
            add(parent, p, n);
        }
        n.size = size;
        n.modified = modified;
        nodes.get(parent).version++;
        return this;
    }

    /** Marks {@code path} hidden; returns this. */
    public MemoryFileModel hidden(String path) {
        Node n = nodes.get(normalize(path));
        if (n != null) {
            n.hidden = true;
        }
        return this;
    }

    /** Removes {@code path} (a folder with what it holds); returns this. */
    public MemoryFileModel delete(String path) {
        String p = normalize(path);
        Node n = nodes.remove(p);
        if (n == null) {
            return this;
        }
        for (String c : List.copyOf(n.children)) {
            delete(c);
        }
        String parent = parent(p);
        if (parent != null && nodes.containsKey(parent)) {
            Node pn = nodes.get(parent);
            pn.children.remove(p);
            pn.version++;
        }
        return this;
    }

    private void add(String parent, String p, Node n) {
        n.modified = n.modified == 0 ? now : n.modified;
        nodes.put(p, n);
        Node pn = nodes.get(parent);
        pn.children.add(p);
        pn.version++;
    }

    /** The root of {@code path}: its prefix up to the first {@code /}. */
    private static String rootOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path + "/" : path.substring(0, slash + 1);
    }

    private static String normalize(String path) {
        String root = rootOf(path);
        List<String> names = new ArrayList<>();
        for (String s : path.substring(Math.min(path.length(), root.length())).split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (!names.isEmpty()) {
                    names.remove(names.size() - 1);
                }
            } else {
                names.add(s);
            }
        }
        return root + String.join("/", names);
    }

    private FileEntry entry(String p, Node n) {
        String name = p.equals(rootOf(p)) ? p : p.substring(p.lastIndexOf('/') + 1);
        return new FileEntry(p, name, n.directory, n.size, n.modified, n.hidden);
    }

    @Override
    public List<FileEntry> list(String directory) throws IOException {
        Node n = nodes.get(normalize(directory));
        if (n == null || !n.directory) {
            throw new IOException("No such folder: " + directory);
        }
        List<FileEntry> out = new ArrayList<>();
        for (String c : n.children) {
            out.add(entry(c, nodes.get(c)));
        }
        return out;
    }

    @Override
    public FileEntry stat(String path) {
        String p = normalize(path);
        Node n = nodes.get(p);
        return n == null ? null : entry(p, n);
    }

    @Override
    public List<FileEntry> roots() {
        List<FileEntry> out = new ArrayList<>();
        for (String r : roots) {
            out.add(entry(r, nodes.get(r)));
        }
        return out;
    }

    @Override
    public String home() {
        return home;
    }

    @Override
    public String parent(String path) {
        String p = normalize(path);
        String root = rootOf(p);
        if (p.equals(root)) {
            return null;
        }
        int slash = p.lastIndexOf('/');
        return slash < root.length() ? root : p.substring(0, slash);
    }

    @Override
    public String child(String directory, String name) {
        String d = normalize(directory);
        return d.endsWith("/") ? d + name : d + "/" + name;
    }

    @Override
    public String resolve(String base, String path) {
        if (path.startsWith("/") || path.indexOf(':') > 0 && path.indexOf(':') < path.indexOf('/')) {
            return normalize(path);
        }
        return normalize(child(base, path));
    }

    @Override
    public List<String> steps(String path) {
        String p = normalize(path);
        String root = rootOf(p);
        List<String> out = new ArrayList<>();
        out.add(root);
        for (String s : p.substring(root.length()).split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public void createDirectory(String path) throws IOException {
        String p = normalize(path);
        if (nodes.containsKey(p)) {
            throw new IOException("Exists: " + path);
        }
        String parent = parent(p);
        if (parent == null || !nodes.containsKey(parent)) {
            throw new IOException("No such folder: " + parent);
        }
        add(parent, p, new Node(true));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        String f = normalize(from);
        String t = normalize(to);
        Node n = nodes.get(f);
        if (n == null) {
            throw new IOException("No such file: " + from);
        }
        if (nodes.containsKey(t)) {
            throw new IOException("Exists: " + to);
        }
        if (n.directory) {
            dir(t);
            for (String c : List.copyOf(n.children)) {
                rename(c, child(t, c.substring(c.lastIndexOf('/') + 1)));
            }
            delete(f);
        } else {
            file(t, n.size, n.modified);
            delete(f);
        }
    }

    @Override
    public FilePlaces places() {
        return places;
    }

    @Override
    public Watch watch(String directory) {
        Node n = nodes.get(normalize(directory));
        if (n == null) {
            return null;
        }
        return new Watch() {
            private int seen = n.version;

            @Override
            public boolean changed() {
                if (n.version == seen) {
                    return false;
                }
                seen = n.version;
                return true;
            }

            @Override
            public void close() {
            }
        };
    }
}
