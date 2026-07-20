package com.traveltrace.app.ui.map;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.net.ConnectivityMonitor;
import com.traveltrace.app.domain.TripRepository;
import com.traveltrace.app.domain.model.StopRow;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * 저장된 여행을 TripRepository 에서 불러와 지도 화면 상태(MapUiState)로 공급한다.
 * 재생/속도/위성/상영 전환, 스크럽 같은 사용자 조작은 여기서 상태만 갱신하고, 실제
 * 마커·경로 렌더링과 카메라 이동은 MapReplayFragment 가 맡는다 — Fragment 는 stops 가
 * 실제로 바뀐 emission(여행을 새로 열었을 때)에서만 다시 그리고 카메라를 맞춘다.
 */
@HiltViewModel
public class MapReplayViewModel extends ViewModel {

    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();
    private final SavedStateHandle savedState;
    private final TripRepository tripRepository;
    private final ConnectivityMonitor connectivity;

    /** Glide 썸네일이 붙기 전 하단시트 배너의 placeholder 톤. */
    private static final int[] TONES = {
            0xFFD9C9A8, 0xFFB7C6D6, 0xFFA9C6DA, 0xFFCDBFA1, 0xFFC3B69B, 0xFFD7D0BF};

    @Inject
    public MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository,
                              ConnectivityMonitor connectivity) {
        this.savedState = savedState;
        this.tripRepository = tripRepository;
        this.connectivity = connectivity;
    }

    /**
     * tripId 가 있으면 저장 여행을, 없으면 디자인 프리뷰 픽스처를 싣는다.
     *
     * <p>Fragment.onViewCreated 는 회전 등 뷰 재생성마다 무조건 다시 부른다(finding 2와
     * 짝인 finding 1 도 같은 모양이다). 이 ViewModel 은 뷰보다 오래 살아남으므로, 이미
     * state 가 있으면 재조회를 건너뛴다 — 그러지 않으면 activeIndex/playing/satellite/
     * cinema/speed 가 전부 기본값으로 리셋되고, toState() 가 Stop 인스턴스를 새로 찍어내
     * MapReplayFragment.sameRoute() 가드가 깨지면서(참조 동일성 비교라서) 불필요한 Room
     * 재조회+ 카메라가 whole-route bounds 로 스냅되는 부작용까지 겹친다.
     */
    public void load() {
        if (state.getValue() != null) return;
        String tripId = tripId();
        if (tripId == null) {
            state.setValue(ScreenFixtures.map().withOffline(!connectivity.isOnline()));
            return;
        }
        tripRepository.open(tripId, detail -> {
            if (detail == null) {
                state.setValue(new MapUiState("", 0, new ArrayList<>(), 0,
                        false, false, false, MapUiState.Speed.NORMAL, !connectivity.isOnline()));
                return;
            }
            state.setValue(toState(detail).withOffline(!connectivity.isOnline()));
        });
    }

    private static MapUiState toState(TripDetail detail) {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.KOREA);
        fmt.setTimeZone(TimeZone.getTimeZone(detail.timeZoneId));

        List<MapUiState.Stop> stops = new ArrayList<>();
        for (int i = 0; i < detail.stops.size(); i++) {
            StopRow row = detail.stops.get(i);
            // PLACED 행은 좌표가 있어야 하지만, 그 불변식이 깨졌을 때 0d 로 메우면
            // 정확히 금지된 (0,0) 핀이 생긴다 — 조용히 메우지 말고 걸러내고 로그를 남긴다.
            if (row.lat == null || row.lng == null) {
                Log.w("MapReplayViewModel", "PLACED stop without coordinates: " + row.photoId);
                continue;
            }
            stops.add(new MapUiState.Stop(
                    row.photoId,
                    row.landmarkName != null ? row.landmarkName : row.displayName,
                    row.takenAtUtc == null ? "" : fmt.format(new Date(row.takenAtUtc)),
                    row.source == LocationSource.AI,
                    0,
                    TONES[i % TONES.length],
                    row.lat,
                    row.lng));
        }
        return new MapUiState(detail.name, detail.unknownCount, stops, 0,
                false, false, false, MapUiState.Speed.NORMAL);
    }

    /** nav argument 로 들어온 저장 여행 식별자. 없으면 null(프리뷰 진입). */
    public static String tripIdOf(SavedStateHandle handle) {
        return handle.get(MapReplayFragment.ARG_TRIP_ID);
    }

    public String tripId() {
        return tripIdOf(savedState);
    }

    public LiveData<MapUiState> state() {
        return state;
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
                playing, satellite, cinema, speed, s.offline);
    }

    /**
     * 화면으로 돌아올 때 연결 상태만 다시 확인한다. NetworkCallback 을 등록하지 않는 것은
     * 의도다 — 배너 하나를 위해 콜백 생명주기를 관리할 값어치가 없고, 사용자가 비행기 모드를
     * 끄고 돌아오는 흐름은 onResume 으로 충분히 잡힌다.
     */
    public void refreshConnectivity() {
        MapUiState s = state.getValue();
        if (s == null) return;
        state.setValue(s.withOffline(!connectivity.isOnline()));
    }
}
