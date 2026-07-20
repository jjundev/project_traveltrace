package com.traveltrace.app.ui.map;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 리플레이 상태 머신. 프로토타입(prototype/TravelTrace.html)의
 * {@code schedule}/{@code glideTo}/{@code togglePlay}/{@code next}/{@code prev}/{@code jumpTo}
 * 를 네이티브로 옮긴 것이다. 카메라 연출은 {@link ReplayCamera} 에, 시간 계산은
 * {@link ReplayPlan} 에 위임하므로 여기 남는 건 "언제 무엇이 활성인가" 뿐이다.
 *
 * <p><b>active 와 moving 을 나누는 이유:</b> 카메라가 날아가는 동안 사진 카드는 아직 출발지를
 * 보여준다 — 카드는 <em>도착할 때</em> 팝으로 바뀐다(S2 수용 기준). 그래서 {@code activeIndex}
 * 는 도착 시점에만 갱신하고, "지금 어디로 가는 중"은 {@code movingIndex} 가 따로 안다.
 * 이전/다음/점프는 출발지가 아니라 <em>목적지</em>를 기준으로 계산해야 자연스러우므로
 * {@link #currentIndex()} 를 쓴다(프로토타입 {@code curIndex()}).
 */
public class ReplayEngine {

    /** 진행 중인 카메라 이동이 없음. */
    public static final int NO_MOVE = -1;

    public interface Listener {
        void onReplayChanged();
    }

    private final ReplayScheduler scheduler;
    private final List<MapUiState.Stop> stops = new ArrayList<>();

    @Nullable private ReplayCamera camera;
    @Nullable private Listener listener;

    private int activeIndex;
    private int movingIndex = NO_MOVE;
    private boolean playing;
    private boolean cinema;
    private MapUiState.Speed speed = MapUiState.Speed.NORMAL;

    public ReplayEngine(ReplayScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * 엔진이 들고 있는 스톱 목록 그대로. 호출부(ViewModel)는 이걸 {@code MapUiState} 에 실어
     * 보내는데, {@code MapReplayFragment.sameRoute()} 가 <em>원소 참조 동일성</em>으로 "경로가
     * 실제로 바뀌었는가"를 판별하므로 여기서 새 {@code Stop} 을 만들면 안 된다.
     */
    public List<MapUiState.Stop> stops() {
        return stops;
    }

    public int activeIndex() {
        return activeIndex;
    }

    /** 이동 중이면 목적지, 아니면 현재 (프로토타입 curIndex). */
    public int currentIndex() {
        return movingIndex != NO_MOVE ? movingIndex : activeIndex;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isCinema() {
        return cinema;
    }

    public MapUiState.Speed speed() {
        return speed;
    }

    /** 새 여행을 실었다 — 재생을 처음으로 되돌린다. */
    public void setStops(List<MapUiState.Stop> next) {
        scheduler.cancelAll();
        if (camera != null) camera.cancel();
        stops.clear();
        stops.addAll(next);
        activeIndex = 0;
        movingIndex = NO_MOVE;
        playing = false;
        notifyChanged();
    }

    /**
     * 지도가 준비됐다. <b>여기서 카메라를 움직이지 않는다</b> — 진입 직후 카메라는
     * {@code MapRouteRenderer.cameraFor()} 가 맞춘 전체 경로 bounds 여야 하고, 그걸 스톱 0 으로
     * 스냅해 버리면 사용자가 여행 전체를 볼 기회를 잃는다. 첫 이동은 재생/점프에서 일어난다.
     */
    public void attachCamera(CameraAnimator animator) {
        camera = new ReplayCamera(animator);
    }

    /**
     * 뷰가 죽는다. 재생을 멈추고 카메라를 놓는다.
     *
     * <p>동결해 둔 잔여 비행은 카메라와 함께 사라진다 — 회전 뒤 재생을 누르면 잔여 구간을
     * 잇는 대신 현재 스톱에서 dwell 부터 다시 시작한다. 알려진 절충이다(뷰가 사라진 동안
     * 카메라 위치를 신뢰할 수 없다).
     */
    public void detachCamera() {
        pause();
        camera = null;
    }

    /** 재생 중이면 멈춘다(비행 중이면 공중 정지). 이미 멈춰 있으면 아무 일도 없다. */
    public void pause() {
        if (!playing) return;
        scheduler.cancelAll();
        if (camera != null) camera.freeze();
        playing = false;
        notifyChanged();
    }

    /** ViewModel 이 죽을 때. 타이머·비행·리스너를 전부 놓는다. */
    public void release() {
        scheduler.cancelAll();
        if (camera != null) camera.cancel();
        camera = null;
        listener = null;
        playing = false;
    }

    public void togglePlay() {
        if (stops.isEmpty()) return;
        if (playing) {
            pause();
            return;
        }
        if (camera != null && camera.isFrozen()) {
            // 공중 정지해 둔 비행이 있다 — 잔여 구간을 이어간다. 도착 콜백은 얼리기 전에
            // 걸어 둔 것이 그대로 살아 있으므로 순회도 알아서 이어진다.
            playing = true;
            camera.resume();
            notifyChanged();
            return;
        }
        if (activeIndex >= stops.size() - 1) {
            // 끝에서 재생하면 처음부터 (프로토타입 togglePlay).
            activeIndex = 0;
            movingIndex = NO_MOVE;
            restAtActive();
        }
        playing = true;
        notifyChanged();
        scheduleAdvance();
    }

    public void next() {
        if (stops.isEmpty()) return;
        settleAtCurrent();
        if (activeIndex < stops.size() - 1) {
            glideTo(activeIndex + 1, null);
        }
        notifyChanged();
    }

    public void prev() {
        if (stops.isEmpty()) return;
        settleAtCurrent();
        if (activeIndex > 0) {
            glideTo(activeIndex - 1, null);
        }
        notifyChanged();
    }

    public void jumpTo(int index) {
        if (stops.isEmpty()) return;
        settleAtCurrent();
        int target = clamp(index);
        if (target != activeIndex) {
            glideTo(target, null);
        } else {
            restAtActive();
        }
        notifyChanged();
    }

    /** 다음 hop 부터 적용된다 — 이미 예약된 dwell 은 다시 걸지 않는다(프로토타입과 동일). */
    public void setSpeed(MapUiState.Speed next) {
        speed = next;
        notifyChanged();
    }

    public void setCinema(boolean next) {
        cinema = next;
        // 비행 중이면 즉시 이동으로 끊지 않는다 — 다음 착륙부터 새 줌이 적용된다.
        if (movingIndex == NO_MOVE) restAtActive();
        notifyChanged();
    }

    // ---- 내부 ----

    /**
     * dwell 후 다음 스톱으로 (프로토타입 schedule).
     *
     * <p>프로토타입은 마지막 스톱에 도착한 뒤 다음 dwell 을 걸지 않을 뿐 {@code playing} 을
     * true 로 남겨 둔다 — 재생 버튼이 계속 일시정지 아이콘인 채 아무 일도 안 일어나는 상태다.
     * 여기선 <b>도착 시점에 스스로 멈춘다.</b> 그래야 "끝에서 재생하면 처음부터"(togglePlay)
     * 규칙이 실제로 닿을 수 있는 상태가 된다.
     */
    private void scheduleAdvance() {
        scheduler.postDelayed(() -> {
            if (activeIndex >= stops.size() - 1) {
                // 스톱이 하나뿐인 여행 — 갈 곳이 없다.
                playing = false;
                notifyChanged();
                return;
            }
            glideTo(activeIndex + 1, () -> {
                if (!playing) return;
                if (activeIndex < stops.size() - 1) {
                    scheduleAdvance();
                } else {
                    playing = false;
                    notifyChanged();
                }
            });
        }, ReplayPlan.dwellMs(speed));
    }

    /** 수동 조작의 공통 앞부분: 타이머·비행을 끊고 "가고 있던 곳"에 앉힌다. */
    private void settleAtCurrent() {
        scheduler.cancelAll();
        int current = currentIndex();
        if (camera != null) camera.cancel();
        activeIndex = clamp(current);
        movingIndex = NO_MOVE;
        playing = false;
    }

    private int clamp(int index) {
        if (stops.isEmpty()) return 0;
        return Math.min(Math.max(index, 0), stops.size() - 1);
    }

    private void glideTo(int index, @Nullable Runnable done) {
        final int target = clamp(index);
        if (camera == null) {
            // 지도가 아직 안 붙었거나(헤드리스 테스트) 떨어진 상태 — 연출 없이 즉시 도착으로
            // 처리한다. 그러지 않으면 상태 머신이 영영 moving 에 갇힌다.
            //
            // 여기서 notifyChanged() 를 부르면 next/prev/jumpTo 경로에선 호출부의 마지막
            // notifyChanged() 와 겹쳐 같은 값이 두 번 발행된다. 그래도 <b>부른다</b>:
            // scheduleAdvance() 로 들어온 자동 순회에는 뒤따르는 호출부 알림이 없어서,
            // 빼면 카메라 없이 재생할 때 인덱스 변화가 조용히 묻힌다. 중복 발행은 무해하다 —
            // Fragment 의 도착 팝은 activeIndex 가 실제로 달라졌을 때만 튀고(lastPoppedIndex),
            // sameRoute 가드는 Stop 인스턴스가 그대로라 다시 그리지 않는다.
            activeIndex = target;
            movingIndex = NO_MOVE;
            notifyChanged();
            if (done != null) done.run();
            return;
        }
        MapUiState.Stop from = stops.get(clamp(activeIndex));
        MapUiState.Stop to = stops.get(target);
        movingIndex = target;
        long duration = ReplayPlan.flightMs(speed,
                ReplayPlan.distanceMeters(from.lat, from.lng, to.lat, to.lng));
        camera.flyTo(to.lat, to.lng, ReplayPlan.restZoom(cinema), duration,
                new ReplayCamera.Arrival() {
                    @Override
                    public void onArrive() {
                        activeIndex = target;
                        movingIndex = NO_MOVE;
                        notifyChanged();
                        if (done != null) done.run();
                    }

                    @Override
                    public void onInterrupted() {
                        // 사용자가 지도를 직접 만져 비행이 끊겼다 — 목적지에 앉히고 재생을 멈춘다.
                        // 여기서 계속 재생하면 사용자가 방금 옮긴 화면을 곧바로 다시 뺏는다.
                        scheduler.cancelAll();
                        activeIndex = target;
                        movingIndex = NO_MOVE;
                        playing = false;
                        notifyChanged();
                    }
                });
    }

    private void restAtActive() {
        if (camera == null || stops.isEmpty()) return;
        MapUiState.Stop stop = stops.get(clamp(activeIndex));
        camera.moveTo(stop.lat, stop.lng, ReplayPlan.restZoom(cinema));
    }

    private void notifyChanged() {
        if (listener != null) listener.onReplayChanged();
    }
}
