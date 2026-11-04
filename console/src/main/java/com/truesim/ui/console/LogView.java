package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.data.CellState;
import com.truesim.ui.controls.data.Selection;
import com.truesim.ui.controls.data.VirtualView;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.style.Facts;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * The console's log, virtualized (design 5.10): the {@link LogRows} of a {@link LogRing} as rows of the time, the
 * level, the logger, the message, the repeat count and the source position. Only the visible rows have elements;
 * a flood of records costs its new records and the rows on screen. The view follows the newest record while it is
 * scrolled to the end, and stays where it is while the reader scrolled up.
 *
 * <p><b>Elements and styles.</b> The root is a {@code log-view} (role LIST) holding the {@code scroll-viewport} with
 * the {@code log-body}; each row is a {@code log-row} (role LIST_ITEM, named by its message) with the fact
 * {@code level} ({@code log-row[level=warn]}) and labels styled {@code log-time}, {@code log-level},
 * {@code log-logger}, {@code log-message}, {@code log-count} (repeats; hidden for one) and {@code log-source} (hidden
 * without one): {@code log-row[level=error] label.log-message}. A click
 * on a logger filters the view to it ({@link LogRows#logger}); a click on a source position copies it to the
 * clipboard. Steady frames (idle, scrolling, a flood) allocate nothing once the visible rows exist.
 */
public final class LogView extends VirtualView<LogRecord> {

    /** What clicks on a row's parts do: by default filter by the logger and copy the source. */
    public interface Listener {
        /** The logger of a row was clicked. */
        void logger(LogView view, String name);

        /** The source position of a row was clicked (already on the clipboard). */
        default void source(LogView view, CharSequence position) {
        }
    }

    private static final Facts[] LEVEL_FACTS = new Facts[LogLevel.COUNT];
    private static final long DAY = 86_400_000L;

    static {
        for (int i = 0; i < LogLevel.COUNT; i++) {
            LEVEL_FACTS[i] = Facts.of("level", LogLevel.name(i));
        }
    }

    private final LogRows source;
    private Row[] bySlot = new Row[64];
    private String[] shortNames = new String[16];
    private final long offsetMillis;
    private boolean follow = true;
    private boolean pendingFollow;
    private Listener listener;
    private final Clicks clicks = new Clicks();

    /** One row's parts and its text buffers. */
    private static final class Row {
        Element time;
        Element level;
        Element logger;
        Element message;
        Element count;
        Element source;
        final StringBuilder timeText = new StringBuilder(12);
        final StringBuilder countText = new StringBuilder(8);
        int shownLevel = -1;
        long shownSeq = -1;
        long shownVersion = -1;
    }

    /** A log view named {@code name} showing {@code rows}. */
    @AllowAllocation("controls are made when a UI is built")
    public LogView(Ui ui, String name, LogRows rows) {
        super(ui, "log-view", "log-body", name, rows, Role.LIST_ITEM, Selection.Mode.NONE);
        this.source = rows;
        root.semantics(Role.LIST, name);
        offsetMillis = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds() * 1000L;
        interaction.on(body, EventType.CLICK, clicks);
    }

    /** The rows shown (filters, search). */
    public LogRows logRows() {
        return source;
    }

    /** What clicks on loggers and sources do (null: filter by the logger); returns this. */
    public LogView listener(Listener value) {
        listener = value;
        return this;
    }

    /** Whether it follows the newest record (it is scrolled to the end). */
    public boolean following() {
        return follow;
    }

    /** Scrolls to the newest record and follows new ones again. */
    public void scrollToEnd() {
        follow = true;
        pendingFollow = true;
        invalidate();
    }

    @Override
    protected int kindOf(int index, LogRecord row) {
        return 0;
    }

    @Override
    @AllowAllocation("rows are created while the visible window fills, then recycled")
    protected Element createRow(int kind) {
        Element row = ui.element("log-row", null);
        Row r = new Row();
        r.time = ui.label("log-time", "");
        r.level = ui.label("log-level", "");
        r.logger = ui.label("log-logger", "");
        r.message = ui.label("log-message", "");
        r.count = ui.label("log-count", "");
        r.source = ui.label("log-source", "");
        interaction.pickable(r.logger, PointerEvents.ALL);
        interaction.pickable(r.source, PointerEvents.ALL);
        row.add(r.time).add(r.level).add(r.logger).add(r.message).add(r.count).add(r.source);
        int slot = row.slot();
        if (slot >= bySlot.length) {
            bySlot = Arrays.copyOf(bySlot, Math.max(slot + 1, bySlot.length * 2));
        }
        bySlot[slot] = r;
        return row;
    }

    @Override
    protected void bindRow(Element row, int kind, int index, LogRecord value, CellState state) {
        Row r = bySlot[row.slot()];
        if (r.shownLevel != value.level()) {
            r.shownLevel = value.level();
            ui.styles().facts(row.slot(), LEVEL_FACTS[value.level()]);
            ui.text(r.level, LogLevel.name(value.level()));
        }
        if (r.shownSeq != value.seq() || r.shownVersion != value.version()) {
            r.shownSeq = value.seq();
            r.shownVersion = value.version();
            time(r.timeText, value.time() + offsetMillis);
            ui.text(r.time, r.timeText);
            ui.text(r.logger, shortName(value.loggerIndex()));
            ui.text(r.message, value.message());
            int count = value.count();
            ui.layout().visible(r.count.slot(), count > 1);
            if (count > 1) {
                r.countText.setLength(0);
                r.countText.append('x').append(count);
                ui.text(r.count, r.countText);
            }
            CharSequence where = value.source();
            ui.layout().visible(r.source.slot(), where != null);
            if (where != null) {
                ui.text(r.source, where);
            }
        }
        state.name(ui.text(r.message));
    }

    /** The last part of a dotted logger name: {@code Bloom} for {@code com.truesim.render.Bloom}. */
    private String shortName(int logger) {
        if (logger >= shortNames.length || shortNames[logger] == null) {
            return newShortName(logger);
        }
        return shortNames[logger];
    }

    @AllowAllocation("a logger's short name, once")
    private String newShortName(int logger) {
        if (logger >= shortNames.length) {
            shortNames = Arrays.copyOf(shortNames, Math.max(logger + 1, shortNames.length * 2));
        }
        String name = source.ring().loggerName(logger);
        shortNames[logger] = name.substring(name.lastIndexOf('.') + 1);
        return shortNames[logger];
    }

    /** {@code HH:mm:ss.SSS} of local {@code millis}, into {@code out}. */
    static void time(StringBuilder out, long millis) {
        long m = Math.floorMod(millis, DAY);
        out.setLength(0);
        two(out, (int) (m / 3_600_000));
        out.append(':');
        two(out, (int) (m / 60_000 % 60));
        out.append(':');
        two(out, (int) (m / 1000 % 60));
        out.append('.');
        int ms = (int) (m % 1000);
        out.append((char) ('0' + ms / 100)).append((char) ('0' + ms / 10 % 10)).append((char) ('0' + ms % 10));
    }

    private static void two(StringBuilder out, int v) {
        out.append((char) ('0' + v / 10)).append((char) ('0' + v % 10));
    }

    @Override
    protected void beforeLayout(double seconds) {
        double range = vertical().range();
        if (!pendingFollow) {
            follow = range <= 0 || vertical().position() >= range - 0.5;
        }
        super.beforeLayout(seconds);
    }

    @Override
    protected boolean afterLayout() {
        boolean changed = super.afterLayout();
        if (follow) {
            pendingFollow = false;
            vertical().extents(contentHeight(), vertical().viewport());
            if (vertical().scrollTo(vertical().range())) {
                invalidate();
                changed = true;
            }
        }
        return changed;
    }

    /** Logger and source clicks. */
    private final class Clicks implements UiListener {

        @Override
        public void handle(UiEvent e) {
            UiStore store = ui.store();
            int target = e.targetSlot();
            String id = store.style(target);
            if (id == null || !id.equals("log-logger") && !id.equals("log-source")) {
                return;
            }
            int index = indexOf(store.element(target));
            if (index < 0) {
                return;
            }
            LogRecord r = rows().get(index);
            e.consume();
            if (id.equals("log-logger")) {
                if (listener != null) {
                    listener.logger(LogView.this, r.logger());
                } else {
                    source.logger(r.logger());
                }
            } else if (r.source() != null) {
                copy(r.source());
                if (listener != null) {
                    listener.source(LogView.this, r.source());
                }
            }
        }

        @AllowAllocation("a click copies a source position")
        private void copy(CharSequence position) {
            interaction.clipboard().text(position.toString());
        }
    }
}
