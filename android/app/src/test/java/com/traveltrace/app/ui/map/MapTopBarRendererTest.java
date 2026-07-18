package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.ViewMapTopBarBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MapTopBarRendererTest {

    private Context ctx;
    private ViewMapTopBarBinding binding;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = ViewMapTopBarBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void showsTripTitleAndUnknownCount() {
        MapRenderer.renderTopBar(binding, ScreenFixtures.map());

        assertEquals("2024 파리 여행", binding.mapTitle.getText().toString());
        assertEquals("위치 미상 5", binding.unknownChipText.getText().toString());
    }

    @Test
    public void mapTabIsSelectedByDefault() {
        MapRenderer.renderTopBar(binding, ScreenFixtures.map());

        assertEquals(ctx.getColor(R.color.text_on_fill), binding.tabMap.getCurrentTextColor());
        assertEquals(ctx.getColor(R.color.text_tertiary),
                binding.tabSatellite.getCurrentTextColor());
    }

    @Test
    public void satelliteTabSelectionSwapsHighlight() {
        MapUiState base = ScreenFixtures.map();
        MapUiState sat = new MapUiState(base.tripTitle, base.unknownCount, base.stops,
                base.activeIndex, base.playing, true, base.cinema, base.speed);

        MapRenderer.renderTopBar(binding, sat);

        assertEquals(ctx.getColor(R.color.text_on_fill),
                binding.tabSatellite.getCurrentTextColor());
        assertNotEquals(ctx.getColor(R.color.text_on_fill), binding.tabMap.getCurrentTextColor());
    }
}
