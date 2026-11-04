package com.truesim.ui.controls.basic;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedValue;
import com.truesim.common.state.Watch;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.UiEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * A date picker: a month calendar (type {@code date-picker}) bound two-way to a {@link VersionedValue} of
 * {@link LocalDate} (null: none chosen). Parts: a {@code date-header} with the previous and next month buttons (ids
 * {@code previous}, {@code next}) and the title label; a {@code date-weekdays} row of labels (style name
 * {@code date-weekday}) from the locale's first day of the week; a {@code date-grid} of 42 {@code date-day} cells
 * (style name {@code outside} for days of the neighbouring months, {@code today} for today; {@code :selected} for
 * the chosen day).
 *
 * <p>Cells take focus: arrows move a day or a week, page keys a month, home and end to the week's ends, crossing into
 * the neighbouring months as needed; ACCEPT or a click chooses the day. Navigating allocates (dates, the title), as
 * an edit does; frames do not.
 */
public final class DatePicker extends Control {

    private static final String[] NUMBERS = new String[32];

    static {
        for (int i = 0; i < NUMBERS.length; i++) {
            NUMBERS[i] = Integer.toString(i);
        }
    }

    private final Locale locale;
    private final DayOfWeek firstDay;
    private final Button previous;
    private final Button next;
    private final Element title;
    private final Element grid;
    private final Element[] cells = new Element[42];
    private final Element[] cellLabels = new Element[42];
    private final LocalDate[] dates = new LocalDate[42];
    private VersionedValue<LocalDate> model;
    private Watch watch;
    private YearMonth month;
    private LocalDate today;

    /** A picker named {@code name} in {@code locale}, bound to {@code model}, showing the chosen day's month. */
    @AllowAllocation("controls are built once")
    public DatePicker(Controls controls, String name, Locale locale, VersionedValue<LocalDate> model) {
        super(controls, "date-picker", Role.GROUP, name);
        this.locale = locale;
        this.firstDay = WeekFields.of(locale).getFirstDayOfWeek();
        this.today = LocalDate.now();
        Element header = part("date-header");
        previous = new RepeatButton(controls, "previous", null, "Previous month");
        previous.element().id("previous");
        next = new RepeatButton(controls, "next", null, "Next month");
        next.element().id("next");
        title = label("");
        header.add(previous.element()).add(title).add(next.element());
        title.semantics(Role.TEXT, null);
        Element weekdays = part("date-weekdays");
        for (int i = 0; i < 7; i++) {
            DayOfWeek d = firstDay.plus(i);
            Element w = ui.label("date-weekday", d.getDisplayName(TextStyle.SHORT_STANDALONE, locale));
            weekdays.add(w);
        }
        grid = part("date-grid");
        for (int i = 0; i < cells.length; i++) {
            Element cell = part("date-day");
            Element label = label("");
            label.semantics(Role.NONE, null);
            cell.add(label);
            cell.semantics(Role.BUTTON, "");
            focusable(cell);
            listen(cell, EventType.CLICK.bit());
            cells[i] = cell;
            cellLabels[i] = label;
            grid.add(cell);
        }
        listen(grid, EventType.COMMAND.bit());
        root.add(header).add(weekdays).add(grid);
        previous.onClick(this::previousMonth);
        next.onClick(this::nextMonth);
        controls.poll(this);
        bind(model);
    }

    /** Binds the picker to {@code value} and shows its month; returns this. */
    @AllowAllocation("binding happens when a control is configured")
    public DatePicker bind(VersionedValue<LocalDate> value) {
        model = value;
        watch = value.watch();
        watch.update();
        LocalDate d = value.get();
        show(YearMonth.from(d != null ? d : today));
        return this;
    }

    /** Sets what counts as today (marked {@code today}); returns this. */
    @AllowAllocation("set when configured")
    public DatePicker today(LocalDate value) {
        today = value;
        show(month);
        return this;
    }

    /** The model. */
    public VersionedValue<LocalDate> model() {
        return model;
    }

    /** The month shown. */
    public YearMonth month() {
        return month;
    }

    /** The cell showing grid position {@code index} (0 to 41). */
    public Element cell(int index) {
        return cells[index];
    }

    /** The date in cell {@code index}. */
    public LocalDate date(int index) {
        return dates[index];
    }

    /** The cell of {@code date}, or -1 if it is not on the grid. */
    public int cellOf(LocalDate date) {
        for (int i = 0; i < dates.length; i++) {
            if (dates[i].equals(date)) {
                return i;
            }
        }
        return -1;
    }

    /** The previous month button. */
    public Button previousButton() {
        return previous;
    }

    /** The next month button. */
    public Button nextButton() {
        return next;
    }

    /** The title element. */
    public Element title() {
        return title;
    }

    /** Shows the month before. */
    @AllowAllocation("navigating is an edit")
    public void previousMonth() {
        show(month.minusMonths(1));
    }

    /** Shows the month after. */
    @AllowAllocation("navigating is an edit")
    public void nextMonth() {
        show(month.plusMonths(1));
    }

    /** Chooses {@code date} (null: none), showing its month. */
    @AllowAllocation("choosing is an edit")
    public void select(LocalDate date) {
        model.set(date);
        watch.update();
        show(date == null ? month : YearMonth.from(date));
    }

    @AllowAllocation("navigating is an edit")
    private void show(YearMonth value) {
        month = value;
        ui.text(title, month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + month.getYear());
        LocalDate first = month.atDay(1);
        int lead = (first.getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
        LocalDate start = first.minusDays(lead);
        LocalDate chosen = model.get();
        for (int i = 0; i < cells.length; i++) {
            LocalDate d = start.plusDays(i);
            dates[i] = d;
            Element cell = cells[i];
            ui.text(cellLabels[i], NUMBERS[d.getDayOfMonth()]);
            cell.semantics(Role.BUTTON, d.toString());
            boolean outside = d.getMonthValue() != month.getMonthValue();
            cell.style(outside ? "outside" : d.equals(today) ? "today" : null);
            deepState(cell, UiState.SELECTED, d.equals(chosen));
        }
    }

    @Override
    public void poll(long nanos) {
        if (watch.isChanged()) {
            watch.update();
            LocalDate d = model.get();
            show(d == null ? month : YearMonth.from(d));
        }
    }

    @Override
    @AllowAllocation("choosing and navigating are edits")
    public void handle(UiEvent event) {
        if (event.type() == EventType.CLICK) {
            for (int i = 0; i < cells.length; i++) {
                if (event.currentSlot() == cells[i].slot()) {
                    event.consume();
                    LocalDate d = dates[i];
                    select(d);
                    interaction().focus(cells[cellOf(d)], event.player());
                    return;
                }
            }
            return;
        }
        if (event.type() != EventType.COMMAND) {
            return;
        }
        int from = -1;
        Element f = interaction().focused(event.player());
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == f) {
                from = i;
            }
        }
        if (from < 0) {
            return;
        }
        LocalDate d = dates[from];
        LocalDate to;
        switch (event.command()) {
            case LEFT -> to = d.minusDays(1);
            case RIGHT -> to = d.plusDays(1);
            case UP -> to = d.minusWeeks(1);
            case DOWN -> to = d.plusWeeks(1);
            case PAGE_UP -> to = d.minusMonths(1);
            case PAGE_DOWN -> to = d.plusMonths(1);
            case HOME -> to = d.minusDays(from % 7);
            case END -> to = d.plusDays(6 - from % 7);
            default -> {
                return;
            }
        }
        event.consume();
        if (!YearMonth.from(to).equals(month)) {
            show(YearMonth.from(to));
        }
        interaction().focus(cells[cellOf(to)], event.player());
    }
}
