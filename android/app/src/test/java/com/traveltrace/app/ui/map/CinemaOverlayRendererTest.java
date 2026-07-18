package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewCinemaOverlayBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CinemaOverlayRendererTest {

    private ViewCinemaOverlayBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewCinemaOverlayBinding.inflate(LayoutInflater.from(ctx));
    }

    private static MapUiState cinemaAt(int index) {
        MapUiState b = ScreenFixtures.map();
        return new MapUiState(b.tripTitle, b.unknownCount, b.stops, index,
                b.playing, b.satellite, true, b.speed);
    }

    @Test
    public void hiddenWhenCinemaIsOff() {
        MapRenderer.renderCinema(binding, ScreenFixtures.map(), ScreenFixtures.cityLabel());
        assertEquals(View.GONE, binding.cinemaRoot.getVisibility());
    }

    @Test
    public void visibleWithActiveStopNameAndCityMeta() {
        MapRenderer.renderCinema(binding, cinemaAt(0), ScreenFixtures.cityLabel());

        assertEquals(View.VISIBLE, binding.cinemaRoot.getVisibility());
        assertEquals("개선문", binding.cinemaName.getText().toString());
        assertEquals("10:12 · 파리", binding.cinemaMeta.getText().toString());
    }

    @Test
    public void followsActiveStop() {
        MapRenderer.renderCinema(binding, cinemaAt(5), ScreenFixtures.cityLabel());

        assertEquals("몽마르트", binding.cinemaName.getText().toString());
        assertEquals("18:30 · 파리", binding.cinemaMeta.getText().toString());
    }
}
