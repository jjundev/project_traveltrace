package com.traveltrace.app.ui.analysis;

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
import com.traveltrace.app.databinding.FragmentAnalysisBinding;
import com.traveltrace.app.ui.map.MapReplayFragment;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * ANALYZE: 선택 사진의 EXIF 배치 진행 → 완료 → 여행 저장 → MAP.
 *
 * <p>타임존 확인 시트는 S4 소관이라 여기서 띄우지 않는다 — S1 은 EXIF 오프셋이 없으면
 * 기기 타임존으로 폴백한다(plan/06 의 무타임존 폴백).
 */
@AndroidEntryPoint
public class AnalysisFragment extends Fragment {

    private FragmentAnalysisBinding binding;
    private AnalysisViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAnalysisBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(AnalysisViewModel.class);

        binding.analyzeBack.setOnClickListener(v -> {
            vm.cancel();
            NavHostFragment.findNavController(this).popBackStack();
        });

        vm.state().observe(getViewLifecycleOwner(), state ->
                AnalysisRenderer.render(binding, state));

        // 선택 세션이 비어 있으면(프로세스 사망 후 재진입) 분석할 게 없다 — Home 으로.
        vm.abandoned().observe(getViewLifecycleOwner(), abandoned -> {
            if (Boolean.TRUE.equals(abandoned)) {
                NavHostFragment.findNavController(this)
                        .popBackStack(R.id.homeFragment, false);
            }
        });

        vm.savedTripId().observe(getViewLifecycleOwner(), tripId -> {
            if (tripId == null) return;
            binding.gotoMapButton.setOnClickListener(v ->
                    NavHostFragment.findNavController(this).navigate(
                            R.id.action_analysis_to_map,
                            MapReplayFragment.argsFor(tripId)));
        });

        vm.start();
    }

    @Override
    public void onDestroyView() {
        // onDestroyView() 는 회전 등 설정 변경으로 View 만 재생성될 때도 매번 불린다.
        // 반면 이 화면의 ViewModel 은 회전에도 살아남아야 하는 배치를 들고 있으므로,
        // 여기서 vm.cancel() 을 무조건 부르면 회전 한 번에 취소 플래그가 영구히 켜져
        // 진행 중이던 배치가 되살아나지 못하고 멈춰버린다(finding 1). 그래서 취소는
        // ViewModel.onCleared() 로 옮겼다 — 그건 이 ViewModel 이 진짜로 폐기될 때(뒤로
        // 가기로 이 화면을 완전히 벗어날 때)만 불리므로 "회전"과 "이탈"을 정확히 구분한다.
        super.onDestroyView();
        binding = null;
    }
}
