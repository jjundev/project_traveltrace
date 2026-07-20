package com.traveltrace.app.ui.map;

import android.content.ContentUris;
import android.provider.MediaStore;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.core.model.LocationSource;
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
 * 저장된 여행을 TripRepository 에서 불러오고, 재생 상태 머신({@link ReplayEngine})을 감싸
 * 지도 화면 상태({@link MapUiState})로 공급한다.
 *
 * <p><b>재생 로직이 왜 여기 있는가:</b> 카메라는 Fragment 의 {@code GoogleMap} 에 붙어 있지만,
 * 재생 상태(어느 스톱이 활성인지·재생 중인지)는 회전보다 오래 살아야 하고 화면이 렌더하는
 * 단일 SoT 여야 한다. 그래서 엔진은 ViewModel 이 들고, Fragment 는 지도가 준비되면
 * {@link #attachCamera} 로 애니메이터만 꽂아 준다. Fragment 가 상태를 보고 재생 동작을
 * 유도하면 렌더 → 동작 → 렌더 루프가 생기므로, Fragment 는 <em>렌더만</em> 한다.
 */
@HiltViewModel
public class MapReplayViewModel extends ViewModel {

    private final MutableLiveData<MapUiState> state = new MutableLiveData<>();
    private final SavedStateHandle savedState;
    private final TripRepository tripRepository;
    private final ReplayEngine engine;

    /** Glide 썸네일이 실패하거나 아직 안 붙었을 때 하단시트 배너에 남는 placeholder 톤. */
    private static final int[] TONES = {
            0xFFD9C9A8, 0xFFB7C6D6, 0xFFA9C6DA, 0xFFCDBFA1, 0xFFC3B69B, 0xFFD7D0BF};

    /** 엔진이 모르는, 순수 화면 정보. */
    private String tripTitle = "";
    private int unknownCount;
    private boolean satellite;
    private boolean loadStarted;

    @Inject
    public MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository) {
        this(savedState, tripRepository, new MainThreadReplayScheduler());
    }

    /** 테스트가 가짜 스케줄러를 넣을 수 있게 분리한 생성자. */
    MapReplayViewModel(SavedStateHandle savedState, TripRepository tripRepository,
                       ReplayScheduler scheduler) {
        this.savedState = savedState;
        this.tripRepository = tripRepository;
        this.engine = new ReplayEngine(scheduler);
        this.engine.setListener(this::publish);
    }

    /**
     * tripId 가 있으면 저장 여행을, 없으면 디자인 프리뷰 픽스처를 싣는다.
     *
     * <p>Fragment.onViewCreated 는 회전 등 뷰 재생성마다 무조건 다시 부른다. 이 ViewModel 은
     * 뷰보다 오래 살아남으므로 한 번 시작한 적재는 다시 하지 않는다 — 그러지 않으면
     * activeIndex/playing/satellite/cinema/speed 가 전부 기본값으로 리셋되고, 새로 찍어낸
     * {@code Stop} 인스턴스 때문에 {@code MapReplayFragment.sameRoute()} 가드가 깨져
     * 카메라가 whole-route bounds 로 스냅되는 부작용까지 겹친다. {@code state} 값이 아니라
     * 별도 플래그로 판별하는 이유는 저장 여행 적재가 비동기라, 콜백이 오기 전에 두 번째
     * load() 가 들어오면 조회가 중복되기 때문이다.
     */
    public void load() {
        if (loadStarted) return;
        loadStarted = true;
        String tripId = tripId();
        if (tripId == null) {
            MapUiState fixture = ScreenFixtures.map();
            adopt(fixture.tripTitle, fixture.unknownCount, fixture.stops);
            return;
        }
        tripRepository.open(tripId, detail -> {
            if (detail == null) {
                adopt("", 0, new ArrayList<>());
                return;
            }
            adopt(detail.name, detail.unknownCount, toStops(detail));
        });
    }

    private void adopt(String title, int unknown, List<MapUiState.Stop> stops) {
        tripTitle = title;
        unknownCount = unknown;
        engine.setStops(stops); // 엔진이 리스너로 publish() 를 부른다.
    }

    private static List<MapUiState.Stop> toStops(TripDetail detail) {
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
                    row.lng,
                    ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId)));
        }
        return stops;
    }

    /**
     * 엔진 상태 + 화면 정보 → 새 {@link MapUiState}. {@code engine.stops()} 를 그대로 넘기므로
     * Stop 인스턴스는 여행을 새로 열 때만 바뀐다 — 그게 Fragment 의 sameRoute 가드가 기대하는
     * 계약이다.
     */
    private void publish() {
        state.setValue(new MapUiState(tripTitle, unknownCount, engine.stops(),
                engine.activeIndex(), engine.isPlaying(), satellite, engine.isCinema(),
                engine.speed()));
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

    /** 위치 미상 드로어의 썸네일 톤. 실데이터 교체는 S6 소관이다. */
    public int[] unknownThumbTones() {
        return ScreenFixtures.unknownThumbTones();
    }

    // ---- 지도 연결 ----

    /** 지도가 준비됐다. 이 시점부터 재생이 실제 카메라를 움직인다. */
    public void attachCamera(CameraAnimator animator) {
        engine.attachCamera(animator);
    }

    /** 뷰가 죽는다. 재생을 멈추고 카메라를 놓는다. */
    public void detachCamera() {
        engine.detachCamera();
    }

    /** 화면이 백그라운드로 갔다. 이미 멈춰 있으면 아무 일도 없다. */
    public void pausePlayback() {
        engine.pause();
    }

    // ---- 사용자 조작 ----

    public void setSatellite(boolean satellite) {
        this.satellite = satellite;
        publish();
    }

    public void togglePlay() {
        engine.togglePlay();
    }

    public void setSpeed(MapUiState.Speed speed) {
        engine.setSpeed(speed);
    }

    public void jumpTo(int index) {
        engine.jumpTo(index);
    }

    public void next() {
        engine.next();
    }

    public void prev() {
        engine.prev();
    }

    public void setCinema(boolean cinema) {
        engine.setCinema(cinema);
    }

    @Override
    protected void onCleared() {
        engine.release();
        super.onCleared();
    }
}
