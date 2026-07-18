package com.traveltrace.app.ui;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import com.github.takahirom.roborazzi.RoborazziKt;
import com.github.takahirom.roborazzi.RoborazziOptions;
import com.traveltrace.app.R;

import org.robolectric.Robolectric;

/**
 * Shared plumbing for the View-based Roborazzi screenshot tests: builds a themed Robolectric
 * {@link Activity}, attaches a root view as its content, measures/lays it out at a chosen frame,
 * and captures the result to a named golden file. Individual *ScreenshotTest classes own what
 * gets rendered (fixture + Renderer/Fragment call) and what file name it lands in; this class
 * only de-duplicates the boilerplate around that.
 *
 * <p>Two framings are supported (see {@link #captureFixedFrame} and
 * {@link #captureWrapContentHeight}): a fixed 390x844dp phone frame for full-screen captures, and
 * a fixed-width/wrap-content-height frame for modal sheet content that doesn't fill the screen.
 *
 * <p>Record vs verify (whether a mismatch fails the test) is controlled entirely by the
 * {@code roborazzi.test.record} / {@code roborazzi.test.verify} system properties set in
 * build.gradle; this class stays agnostic to that switch and always passes a plain
 * {@code new RoborazziOptions()}.
 *
 * <p>Golden location: honors the {@code roborazzi.output.dir} system property (also set in
 * build.gradle). This has to be read explicitly here because the
 * {@code captureRoboImage(View, String, RoborazziOptions)} overload we call does NOT consult
 * that property itself — it resolves the given string as a literal file path (relative to the
 * working directory unless absolute), via Roborazzi's {@code RelativePathFromCurrentDirectory}
 * strategy. {@code roborazzi.output.dir} only feeds Roborazzi's own default file-name generator,
 * which we don't use since every test names its own file explicitly.
 *
 * <p>Roborazzi's Gradle plugin is deliberately not applied (it requires the Kotlin Gradle
 * plugin and this app is Java-only), so capture is driven library-only: this class + the system
 * properties above are the entire wiring.
 */
public final class ScreenshotHarness {

    private static final String OUTPUT_DIR =
            System.getProperty("roborazzi.output.dir", "build/outputs/roborazzi");

    private final Activity activity;
    private final Context ctx;

    private ScreenshotHarness(Activity activity) {
        this.activity = activity;
        this.ctx = activity;
    }

    /** Builds a themed {@link Activity}, ready to host a view for capture. */
    public static ScreenshotHarness create() {
        Context appCtx = ApplicationProvider.getApplicationContext();
        appCtx.setTheme(R.style.Theme_TravelTrace);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_TravelTrace);
        return new ScreenshotHarness(activity);
    }

    /** A {@link LayoutInflater} bound to the harness's themed activity. */
    public LayoutInflater inflater() {
        return LayoutInflater.from(activity);
    }

    /** The harness's themed activity, for tests that need it directly (e.g. fragments). */
    public Activity activity() {
        return activity;
    }

    /**
     * Attaches {@code root} to the activity and force-lays it out at the 390x844dp phone frame
     * the prototype screens use, then captures it to {@code fileName} under the configured
     * output dir.
     */
    public void captureFixedFrame(View root, String fileName) {
        int w = dp(390);
        int h = dp(844);
        FrameLayout host = attach(root);
        host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);
        capture(host, fileName);
    }

    /**
     * Attaches {@code root} to the activity and lays it out at a fixed 390dp width with
     * wrap-content (UNSPECIFIED) height — for modal sheet content that doesn't cover the whole
     * screen — then captures it to {@code fileName} under the configured output dir.
     */
    public void captureWrapContentHeight(View root, String fileName) {
        int w = dp(390);
        FrameLayout host = attach(root);
        host.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        host.layout(0, 0, w, host.getMeasuredHeight());
        capture(host, fileName);
    }

    private FrameLayout attach(View root) {
        FrameLayout host = new FrameLayout(activity);
        host.addView(root);
        activity.setContentView(host);
        return host;
    }

    private void capture(View host, String fileName) {
        RoborazziKt.captureRoboImage(host, OUTPUT_DIR + "/" + fileName, new RoborazziOptions());
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
