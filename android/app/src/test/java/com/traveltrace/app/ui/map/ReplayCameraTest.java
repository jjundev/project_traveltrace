package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 공중 정지(freeze)와 잔여 구간 재개(resume)가 이 슬라이스의 수용 기준 중 하나다
 * ("일시정지→재개가 이동 중에도 자연스럽게 잔여 구간을 이어감").
 */
public class ReplayCameraTest {

    private FakeCameraAnimator animator;
    private AtomicLong now;
    private ReplayCamera camera;
    private AtomicInteger arrivals;
    private AtomicInteger interruptions;
    private ReplayCamera.Arrival probe;

    @Before
    public void setUp() {
        animator = new FakeCameraAnimator();
        now = new AtomicLong(1_000L);
        camera = new ReplayCamera(animator, now::get);
        arrivals = new AtomicInteger();
        interruptions = new AtomicInteger();
        probe = new ReplayCamera.Arrival() {
            @Override public void onArrive() { arrivals.incrementAndGet(); }
            @Override public void onInterrupted() { interruptions.incrementAndGet(); }
        };
    }

    @Test
    public void flyToStartsAnAnimationWithTheGivenDuration() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        assertEquals(1, animator.animateCount);
        assertEquals(48.86, animator.lastLat, 0.0001d);
        assertEquals(2_000L, animator.lastDurationMs);
    }

    @Test
    public void arrivalReportsOnceAndEndsTheFlight() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        animator.arrive();

        assertEquals(1, arrivals.get());
        assertFalse(camera.isFrozen());
        assertFalse("도착 후에는 얼릴 비행이 없다", camera.freeze());
    }

    @Test
    public void freezeStopsTheAnimationAndKeepsTheRemainder() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        now.set(1_800L); // 800ms 경과

        assertTrue(camera.freeze());

        assertEquals(1, animator.stopCount);
        assertTrue(camera.isFrozen());
        assertEquals(1_200L, camera.remainingMs());
    }

    @Test
    public void resumeFliesOnlyTheRemainingSegment() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        now.set(1_800L);
        camera.freeze();

        assertTrue(camera.resume());

        assertEquals("재개는 새 애니메이션 1건", 2, animator.animateCount);
        assertEquals("남은 구간만 이어간다", 1_200L, animator.lastDurationMs);
        assertEquals("목적지는 그대로", 48.86, animator.lastLat, 0.0001d);
        assertFalse(camera.isFrozen());
    }

    @Test
    public void resumeThenArriveStillReportsTheOriginalArrival() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        now.set(1_800L);
        camera.freeze();
        camera.resume();

        animator.arrive();

        assertEquals(1, arrivals.get());
        assertEquals(0, interruptions.get());
    }

    @Test
    public void freezeThenResumeTwiceKeepsShrinkingTheRemainder() {
        camera.flyTo(48.86, 2.29, 14f, 3_000L, probe);
        now.set(2_000L); // 1000ms 경과
        camera.freeze();
        now.set(5_000L); // 정지 중 흐른 시간은 세지 않는다
        camera.resume();
        now.set(5_500L); // 다시 500ms 비행
        camera.freeze();

        assertEquals(1_500L, camera.remainingMs());
    }

    @Test
    public void lateCancelAfterOurOwnFreezeIsNotReportedAsInterruption() {
        // 실제 GoogleMap 은 stopAnimation() 뒤 다음 프레임에 onCancel 을 준다 —
        // 우리가 스스로 멈춘 것을 "사용자가 방해했다"로 오인하면 재생이 죽는다.
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);
        camera.freeze();

        animator.cancel();

        assertEquals(0, interruptions.get());
        assertTrue("여전히 재개 가능해야 한다", camera.isFrozen());
    }

    @Test
    public void externalCancelDuringFlightIsReportedAsInterruption() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        animator.cancel(); // 사용자가 지도를 만져 애니메이션이 끊긴 경우

        assertEquals(1, interruptions.get());
        assertEquals(0, arrivals.get());
    }

    @Test
    public void cancelSilencesTheCallbackEntirely() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        camera.cancel();
        animator.arrive();
        animator.cancel();

        assertEquals(0, arrivals.get());
        assertEquals(0, interruptions.get());
    }

    @Test
    public void staleCallbackFromAnInterruptedFlightIsIgnoredAfterANewFlightStarts() {
        // ReplayCamera 의 존재 이유: 실제 GoogleMap 은 onCancel/onArrive 를 다음 프레임에
        // 비동기로 준다. flight A 가 flight B 에 의해 끊긴 뒤, A 의 콜백이 뒤늦게 도착해도
        // B 를 오염시키면 안 된다 — 세대(generation) 가드가 이걸 보장해야 한다.
        AtomicInteger arrivalsA = new AtomicInteger();
        AtomicInteger interruptionsA = new AtomicInteger();
        ReplayCamera.Arrival probeA = new ReplayCamera.Arrival() {
            @Override public void onArrive() { arrivalsA.incrementAndGet(); }
            @Override public void onInterrupted() { interruptionsA.incrementAndGet(); }
        };

        camera.flyTo(48.86, 2.29, 14f, 2_000L, probeA); // flight A

        camera.flyTo(41.90, 12.49, 12f, 3_000L, probe); // flight B — A 를 끊고 세대를 올린다

        // A 의 뒤늦은 콜백들이 지금에서야 도착한다.
        animator.arrivePrevious();
        animator.cancelPrevious();

        assertEquals("스테일 비행의 도착은 완전히 무시돼야 한다", 0, arrivalsA.get());
        assertEquals("스테일 비행의 중단도 완전히 무시돼야 한다", 0, interruptionsA.get());
        assertEquals("새 비행의 도착 카운트가 오염되면 안 된다", 0, arrivals.get());
        assertEquals("새 비행의 중단 카운트가 오염되면 안 된다", 0, interruptions.get());
        assertTrue("새 비행(B)은 스테일 콜백 이후에도 여전히 살아있어야 한다", camera.freeze());
    }

    @Test
    public void moveToCancelsAnyFlightAndJumpsInstantly() {
        camera.flyTo(48.86, 2.29, 14f, 2_000L, probe);

        camera.moveTo(48.88, 2.34, 15f);
        animator.arrive();

        assertEquals(1, animator.moveCount);
        assertEquals(48.88, animator.lastLat, 0.0001d);
        assertEquals(0, arrivals.get());
    }
}
