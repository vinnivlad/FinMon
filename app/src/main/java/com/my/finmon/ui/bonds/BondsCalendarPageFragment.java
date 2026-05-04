package com.my.finmon.ui.bonds;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import androidx.core.content.ContextCompat;

import com.kizitonwose.calendar.core.CalendarDay;
import com.kizitonwose.calendar.core.CalendarMonth;
import com.kizitonwose.calendar.core.DayPosition;
import com.kizitonwose.calendar.view.MonthDayBinder;
import com.kizitonwose.calendar.view.MonthHeaderFooterBinder;
import com.kizitonwose.calendar.view.ViewContainer;
import com.my.finmon.R;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPayment;
import com.my.finmon.data.repository.PortfolioRepository.ExpectedPaymentsResult;
import com.my.finmon.databinding.FragmentBondsCalendarBinding;
import com.my.finmon.databinding.ViewCalendarDayBinding;
import com.my.finmon.databinding.ViewCalendarMonthHeaderBinding;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bonds → Calendar tab. Infinite-scroll month grid (kizitonwose:calendar) where days
 * with expected bond payments are marked with a small primary-colored dot. Tapping
 * a marked date opens {@link DatePaymentsDialog}. The currency filter from the
 * Activity-scoped global filter narrows which payments are visible — the page
 * picks them up via {@link BondsViewModel#expectedPayments()}.
 */
public class BondsCalendarPageFragment extends Fragment {

    private static final DateTimeFormatter MONTH_HEADER_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault());

    private FragmentBondsCalendarBinding binding;
    private BondsViewModel viewModel;
    private DayOfWeek firstDayOfWeek;

    /** Lookup: a date's payment list, grouped from {@code expectedPayments.payments}. */
    @NonNull private Map<LocalDate, List<ExpectedPayment>> paymentsByDate = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBondsCalendarBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireParentFragment()).get(BondsViewModel.class);
        firstDayOfWeek = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();

        renderWeekdayLegend();
        setupCalendar();

        viewModel.expectedPayments().observe(getViewLifecycleOwner(), this::onPaymentsChanged);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private void renderWeekdayLegend() {
        LinearLayout legend = binding.weekdayLegend;
        legend.removeAllViews();
        // Weekday letters in the user's locale, starting from firstDayOfWeek so the
        // legend lines up with the calendar grid below.
        DayOfWeek[] order = new DayOfWeek[7];
        for (int i = 0; i < 7; i++) {
            order[i] = firstDayOfWeek.plus(i);
        }
        for (DayOfWeek dow : order) {
            TextView t = new TextView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            t.setLayoutParams(lp);
            t.setGravity(android.view.Gravity.CENTER);
            t.setText(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()));
            // Editorial Kicker: Inter 9/600 caps, ink_mute. Letter-spacing comes
            // from the style. Letters are already a single character so caps is
            // a visual no-op here; the style still nails type/color/spacing.
            androidx.core.widget.TextViewCompat.setTextAppearance(
                    t, R.style.TextAppearance_FinMon_Kicker);
            legend.addView(t);
        }
    }

    private void setupCalendar() {
        binding.calendarView.setDayBinder(new MonthDayBinder<DayContainer>() {
            @NonNull
            @Override
            public DayContainer create(@NonNull View view) {
                return new DayContainer(view);
            }

            @Override
            public void bind(@NonNull DayContainer container, @NonNull CalendarDay day) {
                container.bind(day);
            }
        });

        binding.calendarView.setMonthHeaderBinder(
                new MonthHeaderFooterBinder<MonthHeaderContainer>() {
                    @NonNull
                    @Override
                    public MonthHeaderContainer create(@NonNull View view) {
                        return new MonthHeaderContainer(view);
                    }

                    @Override
                    public void bind(@NonNull MonthHeaderContainer container,
                                     @NonNull CalendarMonth month) {
                        container.bind(month, countEventsIn(month.getYearMonth()));
                    }
                });

        // Range chosen to comfortably cover both past dividend history and any
        // realistic bond maturity (longest UAH OVDPs run ~3 years; foreign bonds
        // can run decades). 10y back / 30y forward is generous.
        YearMonth current = YearMonth.now();
        binding.calendarView.setup(
                current.minusYears(10),
                current.plusYears(30),
                firstDayOfWeek);
        binding.calendarView.scrollToMonth(current);
    }

    // ─── Data plumbing ─────────────────────────────────────────────────────

    private void onPaymentsChanged(@Nullable ExpectedPaymentsResult r) {
        if (binding == null) return;
        if (r == null || r.payments.isEmpty()) {
            paymentsByDate = new HashMap<>();
        } else {
            Map<LocalDate, List<ExpectedPayment>> grouped = new HashMap<>();
            for (ExpectedPayment p : r.payments) {
                grouped.computeIfAbsent(p.date, k -> new ArrayList<>()).add(p);
            }
            paymentsByDate = grouped;
        }
        // Triggers each visible day cell to re-bind, picking up the new marker state.
        binding.calendarView.notifyCalendarChanged();
    }

    // ─── ViewContainers ────────────────────────────────────────────────────

    final class DayContainer extends ViewContainer {
        private final ViewCalendarDayBinding dayBinding;
        @Nullable private CalendarDay day;

        DayContainer(@NonNull View view) {
            super(view);
            dayBinding = ViewCalendarDayBinding.bind(view);
            view.setOnClickListener(v -> {
                if (day == null) return;
                List<ExpectedPayment> rows = paymentsByDate.get(day.getDate());
                if (rows == null || rows.isEmpty()) return;  // only payment days are tappable
                DatePaymentsDialog.show(
                        BondsCalendarPageFragment.this, day.getDate(), rows);
            });
        }

        void bind(@NonNull CalendarDay day) {
            this.day = day;
            LocalDate date = day.getDate();
            dayBinding.dayText.setText(String.valueOf(date.getDayOfMonth()));

            boolean inMonth = day.getPosition() == DayPosition.MonthDate;
            boolean isToday = inMonth && date.equals(LocalDate.now());
            boolean hasPayment = inMonth && paymentsByDate.containsKey(date);

            // Out-of-month cells are dimmed so the user can see the month boundary
            // without losing the day number entirely.
            dayBinding.dayText.setAlpha(inMonth ? 1f : 0.3f);

            // Today gets the outlined-oval today ring (matches MaterialDatePicker).
            // Day text stays editorial ink in both states — the ring alone signals
            // "today", no extra color treatment needed against cream.
            dayBinding.dayBackground.setBackgroundResource(
                    isToday ? R.drawable.calendar_day_today : R.drawable.calendar_day_bg);
            dayBinding.dayText.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.fm_ink));

            dayBinding.dayMarker.setVisibility(hasPayment ? View.VISIBLE : View.GONE);
        }
    }

    final class MonthHeaderContainer extends ViewContainer {
        private final ViewCalendarMonthHeaderBinding headerBinding;

        MonthHeaderContainer(@NonNull View view) {
            super(view);
            headerBinding = ViewCalendarMonthHeaderBinding.bind(view);
        }

        void bind(@NonNull CalendarMonth month, int eventCount) {
            headerBinding.monthHeaderText.setText(month.getYearMonth().format(MONTH_HEADER_FMT));
            headerBinding.monthHeaderEvents.setText(eventCountLabel(eventCount));
        }
    }

    /** Count of payment-bearing dates in the given month, drawn from the current data. */
    private int countEventsIn(@NonNull YearMonth ym) {
        int total = 0;
        for (Map.Entry<LocalDate, List<ExpectedPayment>> e : paymentsByDate.entrySet()) {
            if (YearMonth.from(e.getKey()).equals(ym)) total += e.getValue().size();
        }
        return total;
    }

    private String eventCountLabel(int n) {
        if (n == 0) return getString(R.string.bonds_calendar_month_events_zero);
        if (n == 1) return getString(R.string.bonds_calendar_month_events_one);
        return getString(R.string.bonds_calendar_month_events_many, n);
    }
}
