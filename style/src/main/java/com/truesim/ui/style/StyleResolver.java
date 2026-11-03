package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.Versioned;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.Selector.Combinator;
import com.truesim.ui.style.Selector.Compound;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.ValueException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Resolves the elements of one {@link UiStore} against a {@link StyleSheet} (design 4.4, "Performance"). Styles are
 * resolved once and cached, never looked up per frame:
 *
 * <ul>
 *   <li>An element's selector inputs (type, id path, style name inherited from the parent when it has none, state
 *       bits, facts, and its ancestors' inputs) are interned into a node; equal inputs share one node, and the
 *       resolved style is cached per node and inline style. Interning on a cache hit allocates nothing.</li>
 *   <li>{@link #resolve(int)} is called for an element whose {@link UiStore#DIRTY_STYLE} bit is set, in tree order
 *       (a parent before its children; a child whose parent was never resolved resolves the parent first). It returns
 *       a handle; {@link #style(int)} gives the {@link ResolvedStyle}, read by key index.</li>
 *   <li>Replacing the sheet ({@link #sheet(StyleSheet)}) drops the cache, bumps {@link #version()} and marks every
 *       element {@code DIRTY_STYLE}, so each re-resolves once on its next update.</li>
 *   <li>Changing {@link Tokens} re-evaluates, at the next {@link #update()}, only the resolved styles that depend on
 *       the changed tokens, in place (their handles stay), and marks the elements using them {@code DIRTY_STYLE}.</li>
 * </ul>
 *
 * <p>Precedence (pinned by tests): inline values set in code win; then rules with state pseudo-classes; then by
 * {@link Selector#specificity()} (style names, id segments, facts, types, compounds), then by more states, then the
 * later rule. The first match per key wins, as in Lemur, attribute by attribute.
 *
 * <p>Rules are indexed by their subject's id tail, type or style name, so an element tests only the rules that can
 * match it. Not thread-safe: the UI thread.
 */
public final class StyleResolver implements Versioned {

    /**
     * How many interned nodes the cache may hold before it is dropped and rebuilt (elements with unique ids or facts
     * grow it). A setting, as every tunable.
     */
    public static final Tunable CACHE_LIMIT = Tunable.of("ui.style.cacheLimit", 65_536, 256, 16_777_216,
        "Interned style nodes kept before the style cache is rebuilt");

    private final UiStore store;
    private final Tokens tokens;
    private StyleSheet sheet;
    private long version;
    private long seenTokens;
    private Consumer<String> errors = message -> { };
    private String lastError;
    private int computed;
    private int reevaluated;

    // the sheet, indexed
    private Rule[] rules;
    private Map<String, int[]> byId;
    private Map<String, int[]> byType;
    private Map<String, int[]> byStyle;
    private int[] universal;
    private int ancestorStates;
    private int[] candidates = new int[64];

    // per slot
    private Element[] owner = new Element[0];
    private int[] handle = new int[0];
    private int[] context = new int[0];
    private int[] factsOf = new int[0];
    private int[] inlineOf = new int[0];

    // interned facts and inline styles (index 0: none)
    private final Map<Facts, Integer> factsIds = new HashMap<>();
    private final List<Facts> factsList = new ArrayList<>(List.of(Facts.NONE));
    private final Map<InlineStyle, Integer> inlineIds = new HashMap<>();
    private final List<InlineStyle> inlineList = new ArrayList<>(List.of(InlineStyle.NONE));

    // the cache
    private final Nodes nodes = new Nodes();
    private final LongIntMap styleOf = new LongIntMap();
    private ResolvedStyle[] styles = new ResolvedStyle[64];
    private int styleCount;
    private int[] liveHandles = new int[16];
    private int liveCount;

    /** A resolver for {@code store}'s elements against {@code sheet}, with its own token table. */
    public StyleResolver(UiStore store, StyleSheet sheet) {
        this(store, sheet, new Tokens());
    }

    /**
     * A resolver for {@code store}'s elements against {@code sheet}, reading live tokens from {@code tokens} (which
     * may be shared by the resolvers of several UIs); {@code tokens} loads the sheet's token defaults and sets.
     */
    @AllowAllocation("one per UI")
    public StyleResolver(UiStore store, StyleSheet sheet, Tokens tokens) {
        this.store = store;
        this.tokens = tokens;
        factsIds.put(Facts.NONE, 0);
        inlineIds.put(InlineStyle.NONE, 0);
        sheet(sheet);
    }

    // ---------------------------------------------------------------- the sheet

    /**
     * Replaces the sheet (a reload, a new theme): loads its tokens, drops the cache, bumps the version and marks every
     * element of the store {@code DIRTY_STYLE}.
     */
    @AllowAllocation("a new sheet is indexed once")
    public void sheet(StyleSheet newSheet) {
        tokens.load(newSheet);
        this.sheet = newSheet;
        List<Rule> list = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (Rule r : newSheet.rules()) {
            if (!r.selector().hasPlaceholder()) {
                order.add(list.size());
                list.add(r);
            }
        }
        // highest priority first: state rules, then specificity, then more states, then the later rule
        order.sort(Comparator.comparing((Integer i) -> list.get(i).selector().stateCount() > 0 ? 1 : 0)
            .thenComparingLong(i -> list.get(i).selector().specificity())
            .thenComparingInt(i -> list.get(i).selector().stateCount())
            .thenComparingInt(i -> i).reversed());
        rules = new Rule[list.size()];
        Map<String, List<Integer>> ids = new HashMap<>();
        Map<String, List<Integer>> types = new HashMap<>();
        Map<String, List<Integer>> styleNames = new HashMap<>();
        List<Integer> any = new ArrayList<>();
        int mask = 0;
        for (int rank = 0; rank < rules.length; rank++) {
            Rule r = list.get(order.get(rank));
            rules[rank] = r;
            Selector s = r.selector();
            Compound subject = s.subject();
            if (!subject.id().isEmpty()) {
                ids.computeIfAbsent(subject.id().get(subject.id().size() - 1), k -> new ArrayList<>()).add(rank);
            } else if (subject.type() != null) {
                types.computeIfAbsent(subject.type(), k -> new ArrayList<>()).add(rank);
            } else if (subject.style() != null) {
                styleNames.computeIfAbsent(subject.style(), k -> new ArrayList<>()).add(rank);
            } else {
                any.add(rank);
            }
            for (int i = 0; i < s.compounds().size() - 1; i++) {
                mask |= s.compounds().get(i).states();
            }
        }
        byId = index(ids);
        byType = index(types);
        byStyle = index(styleNames);
        universal = toArray(any);
        ancestorStates = mask;
        clearCache();
    }

    private static Map<String, int[]> index(Map<String, List<Integer>> lists) {
        Map<String, int[]> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : lists.entrySet()) {
            out.put(e.getKey(), toArray(e.getValue()));
        }
        return out;
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    @AllowAllocation("the cache is dropped on a new sheet or when it outgrows its limit")
    private void clearCache() {
        nodes.clear();
        styleOf.clear();
        styles = new ResolvedStyle[64];
        styleCount = 0;
        liveCount = 0;
        Arrays.fill(handle, -1);
        Arrays.fill(context, -1);
        seenTokens = tokens.version();
        version = store.clock().stamp();
        store.markAll(UiStore.DIRTY_STYLE);
    }

    /** The current sheet. */
    public StyleSheet sheet() {
        return sheet;
    }

    /** The live token table. */
    public Tokens tokens() {
        return tokens;
    }

    /** The store whose elements it resolves. */
    public UiStore store() {
        return store;
    }

    /** Where errors of live values go (a token set to a value a key cannot take); the key keeps its default. */
    public void errors(Consumer<String> listener) {
        this.errors = listener;
    }

    /** The last error of a live value, or null. */
    public String lastError() {
        return lastError;
    }

    @Override
    public long version() {
        return version;
    }

    // ---------------------------------------------------------------- per element inputs

    @AllowAllocation("growing with the store, rarely")
    private void ensureCapacity() {
        int n = store.capacity();
        if (handle.length < n) {
            int old = handle.length;
            owner = Arrays.copyOf(owner, n);
            handle = Arrays.copyOf(handle, n);
            context = Arrays.copyOf(context, n);
            factsOf = Arrays.copyOf(factsOf, n);
            inlineOf = Arrays.copyOf(inlineOf, n);
            Arrays.fill(handle, old, n, -1);
            Arrays.fill(context, old, n, -1);
        }
    }

    /** Takes over {@code slot} for its current element: a reused slot forgets the previous element's inputs. */
    private void own(int slot) {
        Element e = store.element(slot);
        if (owner[slot] != e) {
            owner[slot] = e;
            factsOf[slot] = 0;
            inlineOf[slot] = 0;
            handle[slot] = -1;
            context[slot] = -1;
        }
    }

    /** Sets the attribute facts of the element in {@code slot} ({@code [name=value]} selectors test them). */
    @AllowAllocation("facts are set when an element is built")
    public void facts(int slot, Facts facts) {
        ensureCapacity();
        own(slot);
        factsOf[slot] = intern(facts, factsIds, factsList);
        store.markDirty(slot, UiStore.DIRTY_STYLE);
    }

    /** Sets the inline values of the element in {@code slot}: they beat every rule. */
    @AllowAllocation("inline values are set when an element is configured")
    public void inline(int slot, InlineStyle inline) {
        ensureCapacity();
        own(slot);
        inlineOf[slot] = intern(inline, inlineIds, inlineList);
        store.markDirty(slot, UiStore.DIRTY_STYLE);
    }

    @AllowAllocation("interning when an element is configured")
    private static <T> int intern(T value, Map<T, Integer> ids, List<T> list) {
        Integer id = ids.get(value);
        if (id == null) {
            id = list.size();
            list.add(value);
            ids.put(value, id);
        }
        return id;
    }

    // ---------------------------------------------------------------- resolution

    /**
     * Resolves the element in {@code slot} and clears its {@code DIRTY_STYLE} bit: the handle of its style. On a cache
     * hit this allocates nothing. If the context it gives its children changed (its own inputs changed in a way
     * ancestor selectors see), its children are marked {@code DIRTY_STYLE}.
     */
    public int resolve(int slot) {
        update();
        if (nodes.count > CACHE_LIMIT.get()) {
            clearCache();
        }
        ensureCapacity();
        own(slot);
        int parentSlot = store.parent(slot);
        int parentContext = -1;
        if (parentSlot != UiStore.NONE) {
            if (owner[parentSlot] != store.element(parentSlot) || context[parentSlot] < 0) {
                resolve(parentSlot);
            }
            parentContext = context[parentSlot];
        }
        String style = store.style(slot);
        if (style == null && parentContext >= 0) {
            style = nodes.style[parentContext];
        }
        String type = store.type(slot);
        String id = store.id(slot);
        int state = store.state(slot);
        int facts = factsOf[slot];
        int node = nodes.intern(parentContext, type, id, style, state, facts);
        int masked = state & ancestorStates;
        int ctx = masked == state ? node : nodes.intern(parentContext, type, id, style, masked, facts);
        if (ctx != context[slot] && context[slot] >= 0) {
            for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
                store.markDirty(c, UiStore.DIRTY_STYLE);
            }
        }
        context[slot] = ctx;
        int inline = inlineOf[slot];
        long key = (long) node << 32 | inline;
        int h = styleOf.get(key);
        if (h < 0) {
            h = compute(node, inline);
            styleOf.put(key, h);
        }
        handle[slot] = h;
        store.clearDirty(slot, UiStore.DIRTY_STYLE);
        return h;
    }

    /** The resolved style of {@code handle} (from {@link #resolve}). */
    public ResolvedStyle style(int handle) {
        return styles[handle];
    }

    /** The resolved style of the element in {@code slot}, resolving it if it never was. */
    public ResolvedStyle styleOf(int slot) {
        int h = slot < handle.length && owner[slot] == store.element(slot) ? handle[slot] : -1;
        return styles[h >= 0 ? h : resolve(slot)];
    }

    /** The handle the element in {@code slot} last resolved to, or -1. */
    public int handle(int slot) {
        return slot < handle.length && owner[slot] == store.element(slot) ? handle[slot] : -1;
    }

    /** How many styles were computed by matching rules (cache misses), for tests and diagnostics. */
    public int computed() {
        return computed;
    }

    /** How many resolved styles were evaluated again because their tokens changed. */
    public int reevaluated() {
        return reevaluated;
    }

    /** How many styles the cache holds. */
    public int cachedStyles() {
        return styleCount;
    }

    @AllowAllocation("a cache miss: rules are matched once per new combination of inputs")
    private int compute(int node, int inline) {
        computed++;
        int keys = StyleKeys.count();
        Object[] values = new Object[keys];
        boolean[] taken = new boolean[keys];
        List<Integer> liveKeys = new ArrayList<>();
        List<TokenExpr> liveExpressions = new ArrayList<>();
        apply(inlineList.get(inline).declarations(), values, taken, liveKeys, liveExpressions);
        int n = gather(node);
        for (int i = 0; i < n; i++) {
            Rule r = rules[candidates[i]];
            if (matches(r.selector(), node)) {
                apply(r.declarations(), values, taken, liveKeys, liveExpressions);
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (TokenExpr e : liveExpressions) {
            e.tokens(names);
        }
        int[] slots = new int[names.size()];
        int k = 0;
        for (String name : names) {
            slots[k++] = tokens.slot(name);
        }
        int[] liveKeyArray = toArray(liveKeys);
        int h = styleCount;
        if (h == styles.length) {
            styles = Arrays.copyOf(styles, h * 2);
        }
        ResolvedStyle style = new ResolvedStyle(h, values, liveKeyArray,
            liveExpressions.toArray(new TokenExpr[0]), slots, store.clock().stamp());
        styles[h] = style;
        styleCount++;
        if (liveKeyArray.length > 0) {
            if (liveCount == liveHandles.length) {
                liveHandles = Arrays.copyOf(liveHandles, liveCount * 2);
            }
            liveHandles[liveCount++] = h;
        }
        return h;
    }

    @AllowAllocation("a cache miss")
    private void apply(List<Declaration> declarations, Object[] values, boolean[] taken, List<Integer> liveKeys,
        List<TokenExpr> liveExpressions) {
        for (Declaration d : declarations) {
            int i = d.key().index();
            if (taken[i]) {
                continue;
            }
            taken[i] = true;
            if (d.isLive()) {
                liveKeys.add(i);
                liveExpressions.add(d.live());
                values[i] = evaluate(d.key(), d.live());
            } else {
                values[i] = d.value();
            }
        }
    }

    @AllowAllocation("live values are evaluated on a cache miss or a token change")
    private Object evaluate(StyleKey<?> key, TokenExpr expression) {
        try {
            Value v = expression.evaluate(tokens);
            return key.type().parse(v);
        } catch (ValueException e) {
            lastError = key.name() + ": " + expression + ": " + e.getMessage();
            errors.accept(lastError);
            return null;
        }
    }

    /** Gathers the ranks of the rules that may match {@code node}, in priority order, into {@link #candidates}. */
    @AllowAllocation("a cache miss; the scratch array grows rarely")
    private int gather(int node) {
        int n = 0;
        String id = nodes.id[node];
        if (id != null) {
            String[] path = nodes.path[node];
            n = add(byId.get(path[path.length - 1]), n);
        }
        n = add(byType.get(nodes.type[node]), n);
        String style = nodes.style[node];
        if (style != null) {
            n = add(byStyle.get(style), n);
        }
        n = add(universal, n);
        Arrays.sort(candidates, 0, n);
        return n; // the buckets are disjoint, so there are no duplicates
    }

    @AllowAllocation("the scratch array grows rarely")
    private int add(int[] ranks, int n) {
        if (ranks == null) {
            return n;
        }
        if (n + ranks.length > candidates.length) {
            candidates = Arrays.copyOf(candidates, Math.max(candidates.length * 2, n + ranks.length));
        }
        System.arraycopy(ranks, 0, candidates, n, ranks.length);
        return n + ranks.length;
    }

    /** Whether {@code selector} matches {@code node} (with its ancestors' context nodes). */
    boolean matches(Selector selector, int node) {
        List<Compound> compounds = selector.compounds();
        int last = compounds.size() - 1;
        return matches(compounds.get(last), node) && (last == 0 || ancestors(selector, last - 1, node));
    }

    private boolean ancestors(Selector selector, int k, int node) {
        Compound c = selector.compounds().get(k);
        if (selector.combinators().get(k) == Combinator.CHILD) {
            int p = nodes.parent[node];
            return p >= 0 && matches(c, p) && (k == 0 || ancestors(selector, k - 1, p));
        }
        for (int p = nodes.parent[node]; p >= 0; p = nodes.parent[p]) {
            if (matches(c, p) && (k == 0 || ancestors(selector, k - 1, p))) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(Compound c, int node) {
        if (c.type() != null && !c.type().equals(nodes.type[node])) {
            return false;
        }
        if (c.style() != null && !c.style().equals(nodes.style[node])) {
            return false;
        }
        if ((nodes.state[node] & c.states()) != c.states()) {
            return false;
        }
        List<String> id = c.id();
        if (!id.isEmpty()) {
            if (nodes.id[node] == null) {
                return false;
            }
            String[] path = nodes.path[node];
            int offset = path.length - id.size();
            if (offset < 0) {
                return false;
            }
            for (int i = 0; i < id.size(); i++) {
                if (!id.get(i).equals(path[offset + i])) {
                    return false;
                }
            }
        }
        List<Selector.Fact> tests = c.facts();
        if (!tests.isEmpty()) {
            Facts facts = factsList.get(nodes.facts[node]);
            for (int i = 0; i < tests.size(); i++) {
                if (!facts.matches(tests.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- live tokens

    /**
     * Applies token changes: re-evaluates, in place, only the resolved styles depending on tokens that changed since
     * the last update, and marks the elements using them {@code DIRTY_STYLE}. Called by {@link #resolve} and once per
     * UI update; with no change it is one comparison. Returns whether any style changed.
     */
    public boolean update() {
        long tv = tokens.version();
        if (tv == seenTokens) {
            return false;
        }
        return reevaluate(tv);
    }

    @AllowAllocation("a token change, not per frame")
    private boolean reevaluate(long tv) {
        boolean[] changedStyles = new boolean[styleCount];
        boolean any = false;
        long stamp = 0;
        for (int i = 0; i < liveCount; i++) {
            ResolvedStyle s = styles[liveHandles[i]];
            if (!dependsOnChange(s)) {
                continue;
            }
            if (!any) {
                stamp = store.clock().stamp();
                any = true;
            }
            for (int k = 0; k < s.liveKeys.length; k++) {
                int key = s.liveKeys[k];
                s.values[key] = evaluate(StyleKeys.key(key), s.liveExpressions[k]);
            }
            s.version = stamp;
            changedStyles[s.handle()] = true;
            reevaluated++;
        }
        seenTokens = tv;
        if (any) {
            version = stamp;
            for (int slot = 0; slot < store.highWater() && slot < handle.length; slot++) {
                int h = handle[slot];
                if (h >= 0 && store.alive(slot) && owner[slot] == store.element(slot) && changedStyles[h]) {
                    store.markDirty(slot, UiStore.DIRTY_STYLE);
                }
            }
        }
        return any;
    }

    private boolean dependsOnChange(ResolvedStyle s) {
        for (int slot : s.tokenSlots) {
            if (slot < 0 || tokens.changed(slot) > seenTokens) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- interned nodes

    /** Selector inputs interned by value: parent context, type, id, style name, state bits, facts. */
    private static final class Nodes {
        int count;
        int[] parent = new int[64];
        String[] type = new String[64];
        String[] id = new String[64];
        String[] style = new String[64];
        int[] state = new int[64];
        int[] facts = new int[64];
        String[][] path = new String[64][];
        int[] table = new int[128];

        Nodes() {
            Arrays.fill(table, -1);
        }

        @AllowAllocation("dropping the cache")
        void clear() {
            count = 0;
            Arrays.fill(type, null);
            Arrays.fill(id, null);
            Arrays.fill(style, null);
            Arrays.fill(path, null);
            Arrays.fill(table, -1);
        }

        private static int hash(int parent, String type, String id, String style, int state, int facts) {
            int h = parent * 31 + (type == null ? 0 : type.hashCode());
            h = h * 31 + (id == null ? 0 : id.hashCode());
            h = h * 31 + (style == null ? 0 : style.hashCode());
            h = h * 31 + state;
            h = h * 31 + facts;
            return h ^ (h >>> 16);
        }

        private static boolean same(String a, String b) {
            return a == b || a != null && a.equals(b);
        }

        int intern(int p, String t, String i, String s, int st, int f) {
            int mask = table.length - 1;
            for (int at = hash(p, t, i, s, st, f) & mask; ; at = (at + 1) & mask) {
                int n = table[at];
                if (n < 0) {
                    return add(at, p, t, i, s, st, f);
                }
                if (parent[n] == p && state[n] == st && facts[n] == f && same(type[n], t) && same(id[n], i)
                    && same(style[n], s)) {
                    return n;
                }
            }
        }

        @AllowAllocation("a new node: a cache miss")
        private int add(int at, int p, String t, String i, String s, int st, int f) {
            int n = count;
            if (n == parent.length) {
                int size = n * 2;
                parent = Arrays.copyOf(parent, size);
                type = Arrays.copyOf(type, size);
                id = Arrays.copyOf(id, size);
                style = Arrays.copyOf(style, size);
                state = Arrays.copyOf(state, size);
                facts = Arrays.copyOf(facts, size);
                path = Arrays.copyOf(path, size);
            }
            parent[n] = p;
            type[n] = t;
            id[n] = i;
            style[n] = s;
            state[n] = st;
            facts[n] = f;
            String[] parentPath = p >= 0 ? path[p] : new String[0];
            if (i == null) {
                path[n] = parentPath;
            } else {
                List<String> segments = new ArrayList<>(Arrays.asList(parentPath));
                int start = 0;
                for (int c = 0; c <= i.length(); c++) {
                    if (c == i.length() || i.charAt(c) == '.') {
                        segments.add(i.substring(start, c));
                        start = c + 1;
                    }
                }
                path[n] = segments.toArray(new String[0]);
            }
            count++;
            table[at] = n;
            if (count * 2 > table.length) {
                rehash();
            }
            return n;
        }

        @AllowAllocation("growing, rarely")
        private void rehash() {
            table = new int[table.length * 2];
            Arrays.fill(table, -1);
            int mask = table.length - 1;
            for (int n = 0; n < count; n++) {
                int at = hash(parent[n], type[n], id[n], style[n], state[n], facts[n]) & mask;
                while (table[at] >= 0) {
                    at = (at + 1) & mask;
                }
                table[at] = n;
            }
        }
    }
}
