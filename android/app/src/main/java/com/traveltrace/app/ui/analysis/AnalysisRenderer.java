package com.traveltrace.app.ui.analysis;

import android.content.Context;
import android.view.View;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentAnalysisBinding;

/** AnalysisUiState → ANALYZE 뷰 반영. 진행중/완료 두 형태를 한 카드 안에서 갈아끼운다. */
public final class AnalysisRenderer {

    private AnalysisRenderer() {}

    public static void render(FragmentAnalysisBinding binding, AnalysisUiState state) {
        Context ctx = binding.getRoot().getContext();
        int analyzingVis = state.done ? View.GONE : View.VISIBLE;
        int doneVis = state.done ? View.VISIBLE : View.GONE;

        binding.analyzingHeader.setVisibility(analyzingVis);
        binding.analyzeProgress.setVisibility(analyzingVis);
        binding.recogRow.setVisibility(analyzingVis);

        binding.doneHeader.setVisibility(doneVis);
        binding.gotoMapButton.setVisibility(doneVis);
        binding.analyzeCtaFade.setVisibility(doneVis);

        if (state.done) {
            binding.analyzeSummary.setText(ctx.getString(
                    R.string.analyze_done_summary, state.routeCount, state.unknownCount));
            return;
        }

        binding.analyzeCount.setText(ctx.getString(
                R.string.analyze_progress_count, state.analyzed, state.total));
        binding.analyzeProgress.setProgress(state.progressPercent());
        binding.analyzeRecog.setText(state.recognizedName);
    }
}
