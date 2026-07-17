package com.traveltrace.app.ui.analysis;

import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/** A1 골격용 빈 ViewModel. 분석 파이프라인/진행률은 Epic D~G에서 구현. */
@HiltViewModel
public class AnalysisViewModel extends ViewModel {

    @Inject
    public AnalysisViewModel() {
    }
}
