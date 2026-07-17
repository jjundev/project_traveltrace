package com.traveltrace.app.ui.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 화면-우선 단계: 상태를 ScreenFixtures 에서 공급한다.
 * 로직 에픽에서 이 클래스의 공급원만 TripRepository 로 교체된다 — Renderer/레이아웃은 불변.
 */
@HiltViewModel
public class HomeViewModel extends ViewModel {

    private final MutableLiveData<HomeUiState> state = new MutableLiveData<>();

    @Inject
    public HomeViewModel() {
        state.setValue(ScreenFixtures.home());
    }

    public LiveData<HomeUiState> state() {
        return state;
    }

    /** 빈 상태 디자인 확인용 토글 (프로토타입 homeState prop 대응). */
    public void showEmpty(boolean empty) {
        state.setValue(empty ? ScreenFixtures.homeEmpty() : ScreenFixtures.home());
    }
}
