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
import com.traveltrace.app.ui.common.ToastPresenter;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import dagger.hilt.android.AndroidEntryPoint;

/** ANALYZE: 진행 카드 + 완료 전환. */
@AndroidEntryPoint
public class AnalysisFragment extends Fragment implements TimezoneSheetFragment.Listener {

    private FragmentAnalysisBinding binding;

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
        AnalysisViewModel vm = new ViewModelProvider(this).get(AnalysisViewModel.class);

        binding.analyzeBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
        binding.gotoMapButton.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_analysis_to_map));

        vm.state().observe(getViewLifecycleOwner(), state ->
                AnalysisRenderer.render(binding, state));

        // 프로토타입 startAnalyze: ANALYZE 진입 직후 타임존 확인 시트가 뜬다.
        // 회전 등으로 재생성될 때 두 번 띄우지 않도록 savedInstanceState 로 가드한다.
        if (savedInstanceState == null
                && getChildFragmentManager().findFragmentByTag(TimezoneSheetFragment.TAG) == null) {
            TimezoneSheetFragment.newInstance(ScreenFixtures.cityLabel())
                    .show(getChildFragmentManager(), TimezoneSheetFragment.TAG);
        }
    }

    @Override
    public void onTimezoneConfirmed() {
        // 화면 단계에선 확인만 받고 끝 — 실제 분석 파이프라인 착수는 로직 에픽 10·11 소관.
    }

    @Override
    public void onChangeCityRequested() {
        ToastPresenter.show(binding.getRoot(), getString(R.string.tz_change_city_toast));
    }

    @Override
    public void onDestroyView() {
        ToastPresenter.cancel(binding.getRoot());
        super.onDestroyView();
        binding = null;
    }
}
