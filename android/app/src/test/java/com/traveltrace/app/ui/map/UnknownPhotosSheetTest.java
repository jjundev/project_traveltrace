package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetUnknownPhotosBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class UnknownPhotosSheetTest {

    private SheetUnknownPhotosBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = SheetUnknownPhotosBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void titleShowsCount() {
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        assertEquals("위치 미상 · 5장", binding.unknownTitle.getText().toString());
    }

    @Test
    public void addsOneThumbPerTone() {
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        assertEquals(5, binding.unknownGrid.getChildCount());
    }

    @Test
    public void rebindDoesNotDuplicateThumbs() {
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        UnknownPhotosSheetFragment.bindContent(binding, 5, ScreenFixtures.unknownThumbTones());
        assertEquals(5, binding.unknownGrid.getChildCount());
    }
}
