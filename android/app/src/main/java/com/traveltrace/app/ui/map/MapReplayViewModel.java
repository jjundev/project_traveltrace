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

    /** 상영 모드 카드의 도시명. 로직 단계에서 이 공급원이 리포지토리로 교체된다. */
    public String city() {
        return ScreenFixtures.cityLabel();
    }

    /** 위치 미상 드로어의 썸네일 톤. 로직 단계에서 이 공급원이 리포지토리로 교체된다. */
    public int[] unknownThumbTones() {
        return ScreenFixtures.unknownThumbTones();
    }

    public void setSatellite(boolean satellite) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, s.playing, satellite, s.cinema, s.speed));
    }

    /** 재생 아이콘 토글만 — 실제 리플레이 진행은 로직 에픽 13 소관. */
    public void togglePlay() {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, !s.playing, s.satellite, s.cinema, s.speed));
    }

    public void setSpeed(MapUiState.Speed speed) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, s.playing, s.satellite, s.cinema, speed));
    }

    public void jumpTo(int index) {
        MapUiState s = state.getValue();
        if (s == null) return;
        int clamped = Math.min(Math.max(index, 0), s.stops.size() - 1);
        state.setValue(copy(s, clamped, false, s.satellite, s.cinema, s.speed));
    }

    public void next() {
        MapUiState s = state.getValue();
        if (s != null) jumpTo(s.activeIndex + 1);
    }

    public void prev() {
        MapUiState s = state.getValue();
        if (s != null) jumpTo(s.activeIndex - 1);
    }

    public void setCinema(boolean cinema) {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(copy(s, s.activeIndex, s.playing, s.satellite, cinema, s.speed));
    }

    private static MapUiState copy(MapUiState s, int activeIndex, boolean playing,
                                   boolean satellite, boolean cinema, MapUiState.Speed speed) {
        return new MapUiState(s.tripTitle, s.unknownCount, s.stops, activeIndex,
                playing, satellite, cinema, speed);
    }
}
