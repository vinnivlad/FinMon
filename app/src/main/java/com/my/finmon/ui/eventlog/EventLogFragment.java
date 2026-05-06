package com.my.finmon.ui.eventlog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.R;
import com.my.finmon.data.repository.PortfolioRepository.EventLogItem;
import com.my.finmon.databinding.FragmentEventLogBinding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Event Log screen — flat newest-first list of every recorded event, grouped by
 * date. Reachable from the Portfolio FAB row alongside the Add Trade button.
 */
public final class EventLogFragment extends Fragment {

    private FragmentEventLogBinding binding;
    private EventLogViewModel viewModel;
    private EventLogAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentEventLogBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(
                this, EventLogViewModel.factory(requireContext()))
                .get(EventLogViewModel.class);

        adapter = new EventLogAdapter();
        binding.eventList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.eventList.setAdapter(adapter);
        // Hairline between adjacent rows; date headers carry their own ink rule
        // so the divider continues to read consistently across the list.
        DividerItemDecoration divider = new DividerItemDecoration(
                requireContext(), DividerItemDecoration.VERTICAL);
        android.graphics.drawable.Drawable hairline = androidx.core.content.ContextCompat
                .getDrawable(requireContext(), R.drawable.fm_row_divider);
        if (hairline != null) divider.setDrawable(hairline);
        binding.eventList.addItemDecoration(divider);

        viewModel.items().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Repository isn't reactive — re-fetch when the user returns from Add Trade
        // / Manual Event so the new event lands at the top.
        if (viewModel != null) viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void render(@Nullable List<EventLogItem> events) {
        if (binding == null) return;
        if (events == null || events.isEmpty()) {
            binding.emptyState.setVisibility(View.VISIBLE);
            adapter.submitList(new ArrayList<>());
            return;
        }
        binding.emptyState.setVisibility(View.GONE);

        // Insert one date header before each new date, walking the already-newest-first
        // list so the resulting flattened list keeps that ordering.
        List<EventLogAdapter.Item> items = new ArrayList<>(events.size() + 16);
        LocalDate currentDate = null;
        for (EventLogItem ev : events) {
            LocalDate evDate = ev.primary.timestamp.toLocalDate();
            if (currentDate == null || !currentDate.equals(evDate)) {
                items.add(new EventLogAdapter.Item.Header(evDate));
                currentDate = evDate;
            }
            items.add(new EventLogAdapter.Item.Row(ev));
        }
        adapter.submitList(items);
    }
}
