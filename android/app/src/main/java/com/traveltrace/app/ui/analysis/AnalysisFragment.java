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

import dagger.hilt.android.AndroidEntryPoint;

/** ANALYZE: 진행 카드 + 완료 전환. */
@AndroidEntryPoint
public class AnalysisFragment extends Fragment {

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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
