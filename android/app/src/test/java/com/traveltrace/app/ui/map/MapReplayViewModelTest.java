package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.lifecycle.SavedStateHandle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapReplayViewModelTest {

    // 픽스처(ScreenFixtures.map())는 정류장 6개(index 0..5) 를 시드한다.
    private static final int LAST_INDEX = 5;

    /**
     * tripId 없이(인자 없음) 프리뷰 픽스처를 싣는다 — 리포지토리는 호출되지 않으므로
     * null 로 충분하다(에픽 13, MapReplayViewModel 이 저장 여행도 읽게 되며 load() 가 명시 호출로 분리됨).
     */
    private static MapReplayViewModel newFixtureVm() {
        MapReplayViewModel vm = new MapReplayViewModel(new SavedStateHandle(), null);
        vm.load();
        return vm;
    }

    @Test
    public void jumpTo_clampsAtBothEnds() {
        MapReplayViewModel vm = newFixtureVm();

        vm.jumpTo(-3);
        assertEquals(0, vm.state().getValue().activeIndex);

        vm.jumpTo(999);
        assertEquals(LAST_INDEX, vm.state().getValue().activeIndex);
    }

    @Test
    public void next_stopsAtLastIndexAndDoesNotWrap() {
        MapReplayViewModel vm = newFixtureVm();

        for (int i = 0; i < LAST_INDEX + 3; i++) {
            vm.next();
        }

        assertEquals(LAST_INDEX, vm.state().getValue().activeIndex);
    }

    @Test
    public void prev_stopsAtFirstIndexAndDoesNotWrap() {
        MapReplayViewModel vm = newFixtureVm();

        for (int i = 0; i < 3; i++) {
            vm.prev();
        }

        assertEquals(0, vm.state().getValue().activeIndex);
    }

    @Test
    public void togglePlay_flipsPlaying() {
        MapReplayViewModel vm = newFixtureVm();
        assertFalse(vm.state().getValue().playing);

        vm.togglePlay();
        assertTrue(vm.state().getValue().playing);

        vm.togglePlay();
        assertFalse(vm.state().getValue().playing);
    }

    @Test
    public void jumpTo_forcesPlayingFalse() {
        MapReplayViewModel vm = newFixtureVm();
        vm.togglePlay();
        assertTrue(vm.state().getValue().playing);

        vm.jumpTo(2);

        assertFalse(vm.state().getValue().playing);
        assertEquals(2, vm.state().getValue().activeIndex);
    }

    @Test
    public void setSpeed_producesNewImmutableStateWithoutMutatingPrevious() {
        MapReplayViewModel vm = newFixtureVm();
        MapUiState before = vm.state().getValue();
        assertEquals(MapUiState.Speed.NORMAL, before.speed);

        vm.setSpeed(MapUiState.Speed.FAST);
        MapUiState after = vm.state().getValue();

        assertEquals(MapUiState.Speed.FAST, after.speed);
        assertEquals(MapUiState.Speed.NORMAL, before.speed);
        assertTrue(before != after);
    }

    @Test
    public void setSatellite_producesNewImmutableStateWithoutMutatingPrevious() {
        MapReplayViewModel vm = newFixtureVm();
        MapUiState before = vm.state().getValue();
        assertFalse(before.satellite);

        vm.setSatellite(true);
        MapUiState after = vm.state().getValue();

        assertTrue(after.satellite);
        assertFalse(before.satellite);
        assertTrue(before != after);
    }

    @Test
    public void setCinema_producesNewImmutableStateWithoutMutatingPrevious() {
        MapReplayViewModel vm = newFixtureVm();
        MapUiState before = vm.state().getValue();
        assertFalse(before.cinema);

        vm.setCinema(true);
        MapUiState after = vm.state().getValue();

        assertTrue(after.cinema);
        assertFalse(before.cinema);
        assertTrue(before != after);
    }

    /**
     * Finding 2 가 지키려는 것 그 자체: MapReplayFragment.onViewCreated 는 회전 등 뷰
     * 재생성마다 vm.load() 를 무조건 다시 부른다. ViewModel 은 살아남으므로, 이미 state
     * 가 있으면 재호출이 아무 것도 하지 않아야 한다 — 그러지 않으면 activeIndex/playing/
     * satellite/cinema/speed 가 기본값으로 리셋되고, toState() 가 Stop 을 새로 찍어내
     * MapReplayFragment.sameRoute() 의 참조 동일성 가드가 깨지면서 카메라가 whole-route
     * bounds 로 스냅된다.
     *
     * <p>load() 에서 idempotent 가드(state.getValue() != null 이면 return)를 없애면 이
     * 테스트는 반드시 실패한다 — 두 번째 load() 가 픽스처를 다시 불러 satellite/cinema/
     * activeIndex 를 전부 기본값으로 되돌리고 stops 리스트도 새 인스턴스로 바꿔 버리기
     * 때문이다.
     */
    @Test
    public void load_isIdempotentAndDoesNotResetUserOwnedState() {
        MapReplayViewModel vm = newFixtureVm();
        vm.setSatellite(true);
        vm.setCinema(true);
        vm.jumpTo(3);
        MapUiState before = vm.state().getValue();

        // Fragment.onViewCreated 가 회전 뒤 다시 부르는 vm.load() 를 흉내낸다.
        vm.load();

        MapUiState after = vm.state().getValue();
        assertTrue("이미 state 가 있으면 재로딩은 인스턴스조차 새로 만들면 안 된다",
                before == after);
        assertTrue("위성 모드가 리셋되면 안 된다", after.satellite);
        assertTrue("상영 모드가 리셋되면 안 된다", after.cinema);
        assertEquals("스크럽 위치가 리셋되면 안 된다", 3, after.activeIndex);
        assertTrue("stops 리스트도 같은 인스턴스여야 sameRoute 가드가 유지된다",
                before.stops == after.stops);
    }
}
