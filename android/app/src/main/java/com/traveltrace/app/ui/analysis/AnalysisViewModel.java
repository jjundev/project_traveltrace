package com.traveltrace.app.ui.analysis;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 화면-우선 단계: 정지된 진행 상태를 공급한다. 진행률을 실제로 올리는 타이머·파이프라인은
 * 로직 에픽(10·11) 소관 — 여기서 만들지 않는다.
 */
@HiltViewModel
public class AnalysisViewModel extends ViewModel {

    /** 디자인 확인용 대표 진행값 (프로토타입 중간 지점). */
    private static final int PREVIEW_ANALYZED = 41;

    private final MutableLiveData<AnalysisUiState> state = new MutableLiveData<>();

    @Inject
    public AnalysisViewModel() {
        state.setValue(ScreenFixtures.analysisInProgress(PREVIEW_ANALYZED));
    }

    public LiveData<AnalysisUiState> state() {
        return state;
    }

    /** 타임존 시트에 넣을 도시명. 로직 단계에서 이 공급원이 리포지토리로 교체된다. */
    public String city() {
        return ScreenFixtures.cityLabel();
    }

    /** 완료형 디자인 확인용 토글. */
    public void setDone(boolean done) {
        state.setValue(done
                ? ScreenFixtures.analysisDone()
                : ScreenFixtures.analysisInProgress(PREVIEW_ANALYZED));
    }
}
