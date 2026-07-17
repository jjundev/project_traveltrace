package com.traveltrace.app.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentHomeBinding;
import com.traveltrace.app.ui.common.ToastPresenter;

import dagger.hilt.android.AndroidEntryPoint;

/** HOME: 여행 목록 / 빈 상태. 렌더는 HomeRenderer, 데이터는 HomeViewModel. */
@AndroidEntryPoint
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        HomeViewModel vm = new ViewModelProvider(this).get(HomeViewModel.class);

        binding.newTripButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_home_to_photo));

        vm.state().observe(getViewLifecycleOwner(), state ->
                HomeRenderer.render(binding, state, this::onTripClick));
    }

    private void onTripClick(HomeUiState.TripCard card) {
        if (card.enabled) {
            NavHostFragment.findNavController(this).navigate(R.id.action_home_to_map);
        } else {
            // 프로토타입 openTripLocked — 제주 카드는 데모에서 열리지 않는다.
            ToastPresenter.show(binding.getRoot(), getString(R.string.home_trip_locked_toast));
        }
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        binding = null;
    }
}
