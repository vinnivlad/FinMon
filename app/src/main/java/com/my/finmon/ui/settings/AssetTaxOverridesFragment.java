package com.my.finmon.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.my.finmon.databinding.FragmentAssetTaxOverridesBinding;

public final class AssetTaxOverridesFragment extends Fragment {

    private FragmentAssetTaxOverridesBinding binding;
    private AssetTaxOverridesViewModel vm;
    private AssetTaxOverridesAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAssetTaxOverridesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        vm = new ViewModelProvider(this, AssetTaxOverridesViewModel.factory(requireContext()))
                .get(AssetTaxOverridesViewModel.class);

        adapter = new AssetTaxOverridesAdapter((assetId, pct) -> vm.setOverride(assetId, pct));
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setAdapter(adapter);

        vm.rows().observe(getViewLifecycleOwner(), rows -> {
            if (rows == null) return;
            adapter.submitList(rows);
            binding.emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            binding.recycler.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
