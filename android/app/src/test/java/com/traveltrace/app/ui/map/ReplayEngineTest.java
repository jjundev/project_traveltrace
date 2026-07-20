package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * S2 수용 기준의 본체. 지도도 실제 시간도 없이 상태 머신만 검증한다
 * — 카메라는 {@link FakeCameraAnimator}, dwell 은 {@link FakeReplayScheduler} 가 대신한다.
 *
 * <p>Robolectric 러너를 쓰는 이유는 {@code MapUiState.Stop} 이 {@code android.net.Uri} 를
 * 필드로 갖기 때문이다(값은 null 이지만 타입이 로드된다).
 */
@RunWith(RobolectricTestRunner.class)
public class ReplayEngineTest {

    private FakeReplayScheduler scheduler;
    private FakeCameraAnimator animator;
    private ReplayEngine engine;
    private int changeCount;

    /** 파리 랜드마크 4곳 — 실제 좌표라 비행 시간 계산도 현실적인 값이 나온다. */
    private static List<MapUiState.Stop> fourStops() {
        List<MapUiState.Stop> stops = new ArrayList<>();
        stops.add(new MapUiState.Stop("arc", "개선문", "10:12", false, 0, 0xFFD9C9A8,
                48.8738, 2.2950, null));
        stops.add(new MapUiState.Stop("eiffel", "에펠탑", "11:05", false, 0, 0xFFB7C6D6,
                48.8584, 2.2945, null));
        stops.add(new MapUiState.Stop("louvre", "루브르", "15:40", true, 0, 0xFFCDBFA1,
                48.8606, 2.3376, null));
        stops.add(new MapUiState.Stop("sacre", "몽마르트", "18:30", false, 0, 0xFFD7D0BF,
                48.8867, 2.3431, null));
        return stops;
    }

    @Before
    public void setUp() {
        scheduler = new FakeReplayScheduler();
        animator = new FakeCameraAnimator();
        engine = new ReplayEngine(scheduler);
        engine.setListener(() -> changeCount++);
        engine.setStops(fourStops());
        engine.attachCamera(animator);
    }

    /** dwell 만료 → 비행 시작 → 도착 을 한 번 수행한다. */
    private void advanceOneStop() {
        scheduler.runPending();
        animator.arrive();
    }

    @Test
    public void attachingTheCameraDoesNotMoveIt() {
        // 진입 직후 카메라는 MapRouteRenderer 가 맞춘 전체 경로 bounds 여야 한다.
        assertEquals(0, animator.moveCount);
        assertEquals(0, animator.animateCount);
    }

    @Test
    public void playAdvancesThroughStopsInOrder() {
        engine.togglePlay();
        assertTrue(engine.isPlaying());
        assertEquals(0, engine.activeIndex());

        advanceOneStop();
        assertEquals(1, engine.activeIndex());

        advanceOneStop();
        assertEquals(2, engine.activeIndex());

        advanceOneStop();
        assertEquals(3, engine.activeIndex());
    }

    @Test
    public void playStopsAtTheLastStop() {
        engine.togglePlay();
        advanceOneStop();
        advanceOneStop();
        advanceOneStop();

        assertEquals(3, engine.activeIndex());
        assertFalse("마지막 스톱에 닿으면 스스로 멈춘다", engine.isPlaying());
        assertEquals("멈춘 뒤 새 타이머가 남지 않는다", 0, scheduler.pendingCount());
    }

    /** 스톱이 하나뿐이면 갈 곳이 없다 — dwell 이 만료되는 순간 스스로 멈춘다. */
    @Test
    public void playOnASingleStopRouteStopsAtTheFirstDwell() {
        engine.setStops(fourStops().subList(0, 1));
        engine.togglePlay();
        assertTrue(engine.isPlaying());

        scheduler.runPending();

        assertFalse(engine.isPlaying());
        assertEquals(0, engine.activeIndex());
    }

    @Test
    public void playFromTheEndRestartsFromTheFirstStop() {
        engine.jumpTo(3);
        animator.arrive();
        assertEquals(3, engine.activeIndex());

        engine.togglePlay();

        assertEquals("끝에서 재생하면 처음부터 다시 (프로토타입 togglePlay)", 0, engine.activeIndex());
        assertTrue(engine.isPlaying());
    }

    @Test
    public void dwellUsesTheSelectedSpeedPreset() {
        engine.setSpeed(MapUiState.Speed.FAST);
        engine.togglePlay();

        assertEquals(ReplayPlan.DWELL_FAST_MS, scheduler.lastDelayMs());
    }

    @Test
    public void movingIndexTracksTheFlightDestination() {
        engine.togglePlay();
        scheduler.runPending(); // 비행 시작, 아직 도착 전

        assertEquals("도착 전에는 active 가 안 움직인다", 0, engine.activeIndex());
        assertEquals("목적지는 moving 이 안다", 1, engine.currentIndex());

        animator.arrive();

        assertEquals(1, engine.activeIndex());
        assertEquals("도착했으면 이동 중이 아니다", 1, engine.currentIndex());
    }

    @Test
    public void pauseMidFlightFreezesAndResumeContinuesTheRemainder() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중
        int animatesBeforePause = animator.animateCount;

        engine.togglePlay(); // 일시정지

        assertFalse(engine.isPlaying());
        assertEquals("공중 정지는 애니메이션을 멈춘다", 1, animator.stopCount);
        assertEquals("정지가 새 비행을 만들지 않는다", animatesBeforePause, animator.animateCount);

        engine.togglePlay(); // 재개

        assertTrue(engine.isPlaying());
        assertEquals("잔여 구간 비행 1건이 새로 뜬다", animatesBeforePause + 1, animator.animateCount);
        assertEquals("목적지는 그대로", 1, engine.currentIndex());

        animator.arrive();

        assertEquals(1, engine.activeIndex());
        assertTrue("재개 후에도 순회가 이어진다", scheduler.pendingCount() > 0);
    }

    @Test
    public void pauseWhileRestingJustStopsTheTimer() {
        engine.togglePlay();
        assertTrue(scheduler.pendingCount() > 0);

        engine.togglePlay();

        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void nextFliesToTheFollowingStopAndStopsPlayback() {
        engine.togglePlay();

        engine.next();

        assertFalse("수동 조작은 자동 재생을 끈다", engine.isPlaying());
        assertEquals(1, engine.currentIndex());
        animator.arrive();
        assertEquals(1, engine.activeIndex());
    }

    @Test
    public void nextMidFlightAdvancesFromTheDestinationNotTheOrigin() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중

        engine.next();

        assertEquals("비행 중 '다음'은 목적지(1)의 다음(2)으로 간다", 2, engine.currentIndex());
    }

    @Test
    public void prevStopsAtTheFirstStop() {
        engine.prev();
        animator.arrive();

        assertEquals(0, engine.activeIndex());
        assertEquals("첫 스톱에서 이전은 비행을 만들지 않는다", 0, animator.animateCount);
    }

    @Test
    public void nextStopsAtTheLastStop() {
        engine.jumpTo(3);
        animator.arrive();
        int animatesAtEnd = animator.animateCount;

        engine.next();

        assertEquals(3, engine.activeIndex());
        assertEquals("마지막 스톱에서 다음은 비행을 만들지 않는다", animatesAtEnd, animator.animateCount);
    }

    @Test
    public void jumpToTheCurrentStopRecentersWithoutFlying() {
        engine.jumpTo(0);

        assertEquals("같은 스톱으로의 점프는 즉시 이동", 1, animator.moveCount);
        assertEquals(0, animator.animateCount);
    }

    @Test
    public void jumpToClampsAtBothEnds() {
        engine.jumpTo(-5);
        animator.arrive();
        assertEquals(0, engine.activeIndex());

        engine.jumpTo(99);
        animator.arrive();
        assertEquals(3, engine.activeIndex());
    }

    @Test
    public void cinemaRestsCloserAndRecentersImmediately() {
        engine.setCinema(true);

        assertTrue(engine.isCinema());
        assertEquals(1, animator.moveCount);
        assertEquals(ReplayPlan.REST_ZOOM_CINEMA, animator.lastZoom, 0.001f);
    }

    @Test
    public void cinemaDuringAFlightDoesNotYankTheCamera() {
        engine.togglePlay();
        scheduler.runPending(); // 비행 중

        engine.setCinema(true);

        assertEquals("비행 중에는 즉시 이동으로 끊지 않는다", 0, animator.moveCount);
    }

    @Test
    public void externalCameraInterruptionSettlesAtTheDestinationAndStops() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중

        animator.cancel(); // 사용자가 지도를 직접 만졌다

        assertEquals(1, engine.activeIndex());
        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void detachingTheCameraPausesPlayback() {
        engine.togglePlay();

        engine.detachCamera();

        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }

    /**
     * 회전 중처럼 카메라가 비행 도중에 떨어지면, 그 비행은 확정된 도착이 아니므로
     * 잊혀야 한다 — {@code movingIndex} 가 목적지를 계속 들고 있으면 뷰가 재생성된 뒤
     * 첫 Next 가 마지막 도착이 아니라 그 목적지에서 계산돼 카드가 한 번에 두 칸을 넘는다.
     */
    @Test
    public void detachMidFlightForgetsTheAbandonedHopSoNextAdvancesJustOne() {
        engine.togglePlay();
        scheduler.runPending(); // 0 → 1 비행 중, 아직 도착 전

        engine.detachCamera();

        assertEquals("중단된 비행은 잊는다 — 마지막 확정 도착(0)에 머물러야 한다",
                0, engine.currentIndex());

        FakeCameraAnimator freshAnimator = new FakeCameraAnimator();
        engine.attachCamera(freshAnimator);
        engine.next();
        freshAnimator.arrive();

        assertEquals("Next 한 번은 마지막 도착에서 한 칸만 전진해야 한다 (0 → 1)",
                1, engine.activeIndex());
    }

    @Test
    public void withoutACameraTheMachineStillAdvancesInstantly() {
        // 회전 직후처럼 카메라가 없어도 상태 머신이 멈추면 안 된다.
        engine.detachCamera();

        engine.next();

        assertEquals(1, engine.activeIndex());
        assertEquals(1, engine.currentIndex());
    }

    /**
     * 카메라 없이도 자동 순회가 리스너에 <em>알려져야</em> 한다.
     *
     * <p>이 테스트가 {@code glideTo} 의 camera-null 분기에 있는 {@code notifyChanged()} 의
     * 존재 이유를 못 박는다. next/prev/jumpTo 경로에선 호출부가 어차피 또 알리므로 그 줄이
     * 중복처럼 보이지만, {@code scheduleAdvance} 로 들어온 hop 에는 뒤따르는 호출부 알림이
     * 없다 — 지우면 카메라 없이 재생할 때 인덱스가 조용히 움직인다.
     */
    @Test
    public void headlessAutoPlayStillNotifiesEachHop() {
        engine.detachCamera();
        engine.togglePlay();
        int before = changeCount;

        scheduler.runPending(); // dwell 만료 → 카메라가 없으니 즉시 도착 처리

        assertEquals(1, engine.activeIndex());
        assertTrue("카메라가 없어도 각 hop 이 알려져야 한다", changeCount > before);
    }

    @Test
    public void releaseCancelsEverythingAndSilencesTheListener() {
        engine.togglePlay();
        int before = changeCount;

        engine.release();
        engine.next();

        assertEquals("release 뒤에는 상태 알림이 없다", before, changeCount);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void emptyRouteIgnoresEveryControl() {
        engine.setStops(new ArrayList<>());

        engine.togglePlay();
        engine.next();
        engine.prev();
        engine.jumpTo(2);

        assertFalse(engine.isPlaying());
        assertEquals(0, engine.activeIndex());
        assertEquals(0, animator.animateCount);
    }

    @Test
    public void setStopsResetsPlaybackToTheStart() {
        engine.togglePlay();
        advanceOneStop();
        assertEquals(1, engine.activeIndex());

        engine.setStops(fourStops());

        assertEquals(0, engine.activeIndex());
        assertFalse(engine.isPlaying());
        assertEquals(0, scheduler.pendingCount());
    }
}
