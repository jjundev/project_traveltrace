package com.traveltrace.app.ui.home;

import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.traveltrace.app.databinding.FragmentHomeBinding;

/** HomeUiState → HOME 뷰 반영. 상태 없음 — Robolectric 이 직접 호출해 검증한다. */
public final class HomeRenderer {

    private HomeRenderer() {}

    public static void render(FragmentHomeBinding binding, HomeUiState state,
                              TripCardAdapter.Listener listener) {
        binding.tripList.setVisibility(state.empty ? View.GONE : View.VISIBLE);
        binding.emptyGroup.setVisibility(state.empty ? View.VISIBLE : View.GONE);

        if (state.empty) return;

        TripCardAdapter adapter;
        if (binding.tripList.getAdapter() instanceof TripCardAdapter) {
            adapter = (TripCardAdapter) binding.tripList.getAdapter();
            adapter.setListener(listener);
        } else {
            adapter = new TripCardAdapter(listener);
            binding.tripList.setLayoutManager(
                    new LinearLayoutManager(binding.getRoot().getContext()));
            binding.tripList.setAdapter(adapter);
        }
        adapter.submit(state.trips);
    }
}
