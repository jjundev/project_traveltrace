package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapReplayViewModelTest {

    // 픽스처(ScreenFixtures.map())는 정류장 6개(index 0..5) 를 시드한다.
    private static final int LAST_INDEX = 5;

    @Test
    public void jumpTo_clampsAtBothEnds() {
        MapReplayViewModel vm = new MapReplayViewModel();

        vm.jumpTo(-3);
        assertEquals(0, vm.state().getValue().activeIndex);

        vm.jumpTo(999);
        assertEquals(LAST_INDEX, vm.state().getValue().activeIndex);
    }

    @Test
    public void next_stopsAtLastIndexAndDoesNotWrap() {
        MapReplayViewModel vm = new MapReplayViewModel();

        for (int i = 0; i < LAST_INDEX + 3; i++) {
            vm.next();
        }

        assertEquals(LAST_INDEX, vm.state().getValue().activeIndex);
    }

    @Test
    public void prev_stopsAtFirstIndexAndDoesNotWrap() {
        MapReplayViewModel vm = new MapReplayViewModel();

        for (int i = 0; i < 3; i++) {
            vm.prev();
        }

        assertEquals(0, vm.state().getValue().activeIndex);
    }

    @Test
    public void togglePlay_flipsPlaying() {
        MapReplayViewModel vm = new MapReplayViewModel();
        assertFalse(vm.state().getValue().playing);

        vm.togglePlay();
        assertTrue(vm.state().getValue().playing);

        vm.togglePlay();
        assertFalse(vm.state().getValue().playing);
    }

    @Test
    public void jumpTo_forcesPlayingFalse() {
        MapReplayViewModel vm = new MapReplayViewModel();
        vm.togglePlay();
        assertTrue(vm.state().getValue().playing);

        vm.jumpTo(2);

        assertFalse(vm.state().getValue().playing);
        assertEquals(2, vm.state().getValue().activeIndex);
    }

    @Test
    public void setSpeed_producesNewImmutableStateWithoutMutatingPrevious() {
        MapReplayViewModel vm = new MapReplayViewModel();
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
        MapReplayViewModel vm = new MapReplayViewModel();
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
        MapReplayViewModel vm = new MapReplayViewModel();
        MapUiState before = vm.state().getValue();
        assertFalse(before.cinema);

        vm.setCinema(true);
        MapUiState after = vm.state().getValue();

        assertTrue(after.cinema);
        assertFalse(before.cinema);
        assertTrue(before != after);
    }
}
