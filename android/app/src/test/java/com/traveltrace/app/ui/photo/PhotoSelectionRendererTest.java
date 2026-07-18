package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentPhotoSelectionBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionRendererTest {

    private FragmentPhotoSelectionBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentPhotoSelectionBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void rendersAll18TilesInThreeColumns() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertNotNull(binding.photoGrid.getAdapter());
        assertEquals(18, binding.photoGrid.getAdapter().getItemCount());
        assertEquals(3, ((androidx.recyclerview.widget.GridLayoutManager)
                binding.photoGrid.getLayoutManager()).getSpanCount());
    }

    @Test
    public void countReflectsSelectedTiles() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertEquals("선택한 사진 16장", binding.selectCount.getText().toString());
    }

    @Test
    public void countUpdatesAfterToggle() {
        PhotoSelectionUiState toggled = ScreenFixtures.photoSelection().withToggled(0);
        PhotoSelectionRenderer.render(binding, toggled, index -> {});

        assertEquals("선택한 사진 15장", binding.selectCount.getText().toString());
    }

    @Test
    public void periodAndHintUsePrototypeCopy() {
        PhotoSelectionRenderer.render(binding, ScreenFixtures.photoSelection(), index -> {});

        assertEquals("2024. 6. 12 – 6. 15 · 사진 94장", binding.periodLabel.getText().toString());
        assertEquals("최대 100장 · 탭하여 제외", binding.selectHint.getText().toString());
    }
}
