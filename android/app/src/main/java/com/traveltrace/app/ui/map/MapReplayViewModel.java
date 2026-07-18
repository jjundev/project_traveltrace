package com.traveltrace.app.ui.map;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.ui.preview.ScreenFixtures;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 화면-우선 단계: 정지된 지도 상태를 공급한다. 리플레이 진행·카메라 이동은
 * 로직 에픽 12~14 소관 — 여기서 만들지 않는다.
 */
@HiltViewModel
public class MapReplayViewModel extends ViewModel {

    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();

    @Inject
    public MapReplayViewModel() {
        state.setValue(ScreenFixtures.map());
    }

    public LiveData<MapUiState> state() {
        return state;
    }

    public void setSatellite(boolean satellite) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(new MapUiState(s.tripTitle, s.unknownCount, s.stops, s.activeIndex,
                s.playing, satellite, s.cinema, s.speed));
    }
}
