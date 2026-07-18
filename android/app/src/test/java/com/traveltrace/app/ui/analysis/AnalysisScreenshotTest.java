package com.traveltrace.app.ui.analysis;

import com.traveltrace.app.databinding.FragmentAnalysisBinding;
import com.traveltrace.app.ui.ScreenshotHarness;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * ANALYZE 화면의 두 상태(진행중/완료)를 PNG 로 캡처한다(프로토타입 육안 대조).
 * 방식은 HomeScreenshotTest 와 동일. 공통 준비/캡처 로직은 {@link ScreenshotHarness} 참고.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class AnalysisScreenshotTest {

    private ScreenshotHarness harness;
    private FragmentAnalysisBinding binding;

    @Before
    public void setUp() {
        harness = ScreenshotHarness.create();
        binding = FragmentAnalysisBinding.inflate(harness.inflater());
    }

    @Test
    public void analyze_inProgress() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));
        harness.captureFixedFrame(binding.getRoot(), "analyze_progress.png");
    }

    @Test
    public void analyze_done() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisDone());
        harness.captureFixedFrame(binding.getRoot(), "analyze_done.png");
    }
}
