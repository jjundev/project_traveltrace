package com.traveltrace.app.data.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.domain.Callback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.fakes.RoboCursor;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class MediaStoreImageSourceTest {

    private Context ctx;
    private AppExecutors executors;
    private MediaStoreImageSource source;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        source = new MediaStoreImageSource(ctx, executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
    }

    // Robolectric 4.14.1 의 ShadowContentResolver#setCursor 는 BaseCursor 서브타입만
    // 받는다(MatrixCursor 는 아니다) — 브리프의 MatrixCursor 를 그대로 쓰면 컴파일이 안 돼
    // RoboCursor(같은 BaseCursor 서브클래스)로 대체했다. 컬럼/행 구성 방식만 다를 뿐
    // 커서 동작(getLong/isNull/getColumnIndexOrThrow 등)은 동일하다.
    private void seed(Object[]... rows) {
        RoboCursor cursor = new RoboCursor();
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN));
        cursor.setResults(rows);
        ContentResolver resolver = ctx.getContentResolver();
        ShadowContentResolver shadow = Shadows.shadowOf(resolver);
        shadow.setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);
    }

    /**
     * 콜백이 메인 루퍼로 오므로 테스트에서 루퍼를 비워 결과를 받는다.
     *
     * <p>실제 io() 는 진짜 백그라운드 스레드풀이라 loadRecent 호출 직후 한 번만
     * idleMainLooper() 를 불러선 안 된다 — 그 시점엔 아직 io 작업이 안 끝나 메인 루퍼에
     * 아무것도 안 쌓여 있을 수 있다. 콜백이 도착할 때까지 짧게 반복해서 비운다.
     */
    private List<GalleryImage> load(int limit) {
        AtomicReference<List<GalleryImage>> box = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        Callback<List<GalleryImage>> callback = value -> {
            box.set(value);
            done.set(true);
        };
        source.loadRecent(limit, callback);

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (!done.get()) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
        assertTrue("콜백이 5초 안에 와야 한다", done.get());
        return box.get();
    }

    @Test
    public void mapsCursorRowsToGalleryImages() {
        seed(new Object[]{11L, "a.jpg", 1_700_000_000_000L});

        List<GalleryImage> images = load(100);

        assertEquals(1, images.size());
        GalleryImage first = images.get(0);
        assertEquals(11L, first.id);
        assertEquals("a.jpg", first.displayName);
        assertEquals(Long.valueOf(1_700_000_000_000L), first.dateTakenUtc);
        assertTrue("contentUri 는 _ID 로 만들어져야 한다",
                first.contentUri.toString().endsWith("/11"));
    }

    @Test
    public void zeroDateTakenBecomesNullNotEpoch() {
        seed(new Object[]{12L, "b.jpg", 0L});

        assertNull("DATE_TAKEN 0 은 '없음'이지 1970년이 아니다",
                load(100).get(0).dateTakenUtc);
    }

    @Test
    public void respectsTheLimit() {
        seed(new Object[]{1L, "a.jpg", 3_000L},
                new Object[]{2L, "b.jpg", 2_000L},
                new Object[]{3L, "c.jpg", 1_000L});

        assertEquals(2, load(2).size());
    }

    @Test
    public void emptyGalleryYieldsEmptyListNotNull() {
        seed();
        assertTrue(load(100).isEmpty());
    }
}
