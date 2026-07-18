package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentAnalysisBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AnalysisRendererTest {

    private FragmentAnalysisBinding binding;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentAnalysisBinding.inflate(LayoutInflater.from(ctx));
    }

    @Test
    public void inProgress_showsSpinnerRowAndHidesDoneRowAndCta() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));

        assertEquals(View.VISIBLE, binding.analyzingHeader.getVisibility());
        assertEquals(View.VISIBLE, binding.analyzeProgress.getVisibility());
        assertEquals(View.VISIBLE, binding.recogRow.getVisibility());
        assertEquals(View.GONE, binding.doneHeader.getVisibility());
        assertEquals(View.GONE, binding.gotoMapButton.getVisibility());
    }

    @Test
    public void inProgress_showsCountAndPercent() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));

        assertEquals("41 / 82", binding.analyzeCount.getText().toString());
        assertEquals(50, binding.analyzeProgress.getProgress());
    }

    @Test
    public void inProgress_showsRecognizedNameForThreshold() {
        // lit 임계치: 41 >= 38(센강) 이지만 54(루브르) 미만
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));
        assertEquals("센강 유람선", binding.analyzeRecog.getText().toString());
    }

    @Test
    public void beforeFirstThreshold_showsReadingPlaceholder() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(3));
        assertEquals("사진 읽는 중", binding.analyzeRecog.getText().toString());
    }

    @Test
    public void done_swapsToDoneHeaderAndRevealsCta() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisDone());

        assertEquals(View.GONE, binding.analyzingHeader.getVisibility());
        assertEquals(View.GONE, binding.analyzeProgress.getVisibility());
        assertEquals(View.GONE, binding.recogRow.getVisibility());
        assertEquals(View.VISIBLE, binding.doneHeader.getVisibility());
        assertEquals(View.VISIBLE, binding.gotoMapButton.getVisibility());
        assertEquals("경로 6 · 위치 미상 5", binding.analyzeSummary.getText().toString());
    }
}
