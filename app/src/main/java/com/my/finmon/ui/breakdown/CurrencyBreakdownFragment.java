package com.my.finmon.ui.breakdown;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.databinding.FragmentCurrencyBreakdownBinding;

public class CurrencyBreakdownFragment extends Fragment {

    private FragmentCurrencyBreakdownBinding binding;
    private CurrencyBreakdownViewModel viewModel;
    private CurrencyBucketAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCurrencyBreakdownBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new CurrencyBucketAdapter();
        binding.bucketList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.bucketList.setAdapter(adapter);

        viewModel = new ViewModelProvider(
                this,
                CurrencyBreakdownViewModel.factory(requireContext())
        ).get(CurrencyBreakdownViewModel.class);

        viewModel.buckets().observe(getViewLifecycleOwner(), list -> {
            adapter.submitList(list);
            boolean empty = (list == null || list.isEmpty());
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
