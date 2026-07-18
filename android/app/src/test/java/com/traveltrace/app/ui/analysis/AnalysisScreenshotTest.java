package com.traveltrace.app.ui.analysis;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.FragmentAnalysisBinding;
import com.traveltrace.app.ui.preview.ScreenFixtures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * ANALYZE 화면의 두 상태(진행중/완료)를 PNG 로 캡처한다(프로토타입 육안 대조).
 * 방식은 HomeScreenshotTest 와 동일.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
public class AnalysisScreenshotTest {

    private static final String OUT = "build/outputs/roborazzi/";

    private Context ctx;
    private FragmentAnalysisBinding binding;
    private Activity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.setTheme(R.style.Theme_TravelTrace);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_TravelTrace);
        binding = FragmentAnalysisBinding.inflate(LayoutInflater.from(activity));
    }

    private void attachAndCapture(String fileName) {
        FrameLayout host = new FrameLayout(activity);
        host.addView(binding.getRoot());
        activity.setContentView(host);

        int w = dp(390);
        int h = dp(844);
        host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);

        RoborazziKt.captureRoboImage(host, OUT + fileName, new RoborazziOptions());
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    @Test
    public void analyze_inProgress() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisInProgress(41));
        attachAndCapture("analyze_progress.png");
    }

    @Test
    public void analyze_done() {
        AnalysisRenderer.render(binding, ScreenFixtures.analysisDone());
        attachAndCapture("analyze_done.png");
    }
}
