package com.my.finmon.ui.eventlog;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.my.finmon.ServiceLocator;
import com.my.finmon.data.repository.PortfolioRepository;
import com.my.finmon.data.repository.PortfolioRepository.EventLogItem;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Drives the Event Log screen. Always shows the full event stream — the global
 * filter bar is hidden on this destination, so applying its currency selection
 * silently would be confusing. Period filters never apply: the log is a
 * chronological stream the user can scroll all the way back through.
 */
public final class EventLogViewModel extends ViewModel {

    private static final String TAG = "EventLogVM";

    private final PortfolioRepository repo;
    private final ExecutorService viewExecutor;

    private final MutableLiveData<List<EventLogItem>> items = new MutableLiveData<>();

    public EventLogViewModel(
            @NonNull PortfolioRepository repo,
            @NonNull ExecutorService viewExecutor) {
        this.repo = repo;
        this.viewExecutor = viewExecutor;
        refresh();
    }

    @NonNull
    public LiveData<List<EventLogItem>> items() { return items; }

    public void refresh() {
        viewExecutor.execute(() -> {
            try {
                items.postValue(repo.getEventLog(null).get());
            } catch (Exception e) {
                Log.w(TAG, "event log refresh failed", e);
                items.postValue(Collections.emptyList());
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
                if (modelClass.isAssignableFrom(EventLogViewModel.class)) {
                    return (T) new EventLogViewModel(
                            sl.portfolioRepository(),
                            sl.viewExecutor());
                }
                throw new IllegalArgumentException("Unknown ViewModel: " + modelClass);
            }
        };
    }
}
