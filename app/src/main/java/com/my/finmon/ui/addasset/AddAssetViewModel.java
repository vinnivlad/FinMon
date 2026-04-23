package com.my.finmon.ui.addasset;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.repository.PortfolioRepository;

import java.util.concurrent.ExecutorService;

/**
 * One-shot "save asset" command wrapped as LiveData so the Fragment can react to completion.
 * Emits the new (or existing, if already present) asset id on {@link #savedAssetId()}, or
 * a human-readable message on {@link #error()}.
 */
public final class AddAssetViewModel extends ViewModel {

    private static final String TAG = "AddAssetViewModel";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<Long> savedAssetId = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public AddAssetViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
    }

    @NonNull public LiveData<Long> savedAssetId() { return savedAssetId; }
    @NonNull public LiveData<String> error() { return error; }

    public void save(@NonNull AssetEntity prototype) {
        viewExecutor.execute(() -> {
            try {
                Long id = repo.findOrCreateAsset(prototype).get();
                savedAssetId.postValue(id);
            } catch (Exception e) {
                Log.w(TAG, "save failed", e);
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                error.postValue(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            }
        });
    }

    @NonNull
    public static ViewModelProvider.Factory factory(@NonNull Context anyContext) {
        ServiceLocator sl = ServiceLocator.get(anyContext);
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(AddAssetViewModel.class)) {
                    return (T) new AddAssetViewModel(sl.portfolioRepository(), sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
            }
        };
    }
}
