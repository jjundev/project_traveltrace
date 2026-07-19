package com.traveltrace.app.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
    private HomeViewModel vm;

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
        vm = new ViewModelProvider(this).get(HomeViewModel.class);

        binding.newTripButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_home_to_photo));

        vm.state().observe(getViewLifecycleOwner(), state ->
                HomeRenderer.render(binding, state, new TripCardAdapter.Listener() {
                    @Override
                    public void onTripClick(HomeUiState.TripCard card) {
                        HomeFragment.this.onTripClick(card);
                    }

                    @Override
                    public void onTripLongPress(HomeUiState.TripCard card) {
                        HomeFragment.this.onTripLongPress(card);
                    }
                }));
    }

    @Override
    public void onResume() {
        super.onResume();
        // 분석을 마치고 돌아오면 새 여행이 즉시 보여야 한다.
        vm.refresh();
    }

    private void onTripClick(HomeUiState.TripCard card) {
        if (card.enabled) {
            NavHostFragment.findNavController(this).navigate(
                    R.id.action_home_to_map,
                    com.traveltrace.app.ui.map.MapReplayFragment.argsFor(card.id));
        } else {
            // 프로토타입 openTripLocked — 제주 카드는 데모에서 열리지 않는다.
            ToastPresenter.show(binding.getRoot(), getString(R.string.home_trip_locked_toast));
        }
    }

    /**
     * 롱프레스 → 삭제 확인 다이얼로그(finding 3). 취소된 채 커밋만 된 분석이 하나
     * 남는 수용된 레이스의 유일한 출구라 최소한으로 둔다 — 멀티셀렉트·되돌리기·
     * 스와이프 삭제는 없다. 새 의존성 없이 이미 쓰는 appcompat AlertDialog 만 쓴다.
     */
    private void onTripLongPress(HomeUiState.TripCard card) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.home_delete_trip_title)
                .setMessage(getString(R.string.home_delete_trip_message, card.title))
                .setPositiveButton(R.string.home_delete_trip_confirm,
                        (dialog, which) -> vm.delete(card.id))
                .setNegativeButton(R.string.home_delete_trip_cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        binding = null;
    }
}
