package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.os.Bundle;

import androidx.lifecycle.SavedStateHandle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapReplayViewModelArgsTest {

    @Test
    public void argsForPutsTheTripIdUnderTheAgreedKey() {
        Bundle args = MapReplayFragment.argsFor("trip-42");

        assertEquals("trip-42", args.getString(MapReplayFragment.ARG_TRIP_ID));
    }

    @Test
    public void viewModelReadsTheTripIdFromSavedState() {
        SavedStateHandle handle = new SavedStateHandle();
        handle.set(MapReplayFragment.ARG_TRIP_ID, "trip-42");

        assertEquals("trip-42", MapReplayViewModel.tripIdOf(handle));
    }

    @Test
    public void missingTripIdIsNullNotACrash() {
        assertNull("데모 진입(인자 없음)에도 죽지 않아야 한다",
                MapReplayViewModel.tripIdOf(new SavedStateHandle()));
    }
}
