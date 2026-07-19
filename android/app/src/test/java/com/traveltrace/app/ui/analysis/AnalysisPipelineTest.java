package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
import com.traveltrace.app.domain.model.TripDetail;
import com.traveltrace.app.ui.selection.SelectionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.fakes.RoboCursor;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class AnalysisPipelineTest {

    private Context ctx;
    private AppExecutors executors;
    private TravelTraceDatabase db;
    private SelectionSession session;
    private RoomTripRepository tripRepo;
    private AnalysisViewModel vm;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        session = new SelectionSession();
        tripRepo = new RoomTripRepository(db, executors);

        seedGallery();

        vm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                new RoomPhotoAnalysisRepository(db, executors),
                session,
                executors);
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    /** 사진 3장: GPS 2장 + GPS 없는 1장. */
    private void seedGallery() throws Exception {
        // 브리프 원안은 MatrixCursor 를 썼지만, Robolectric 4.14.1 의
        // ShadowContentResolver#setCursor 는 BaseCursor 서브타입만 받는다 — MediaStoreImageSourceTest
        // 의 선례를 따라 RoboCursor 로 대체한다(컬럼/행 구성 방식만 다를 뿐 동작은 동일).
        RoboCursor cursor = new RoboCursor();
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN));
        cursor.setResults(new Object[][]{
                {1L, "a.jpg", 1_718_154_720_000L},
                {2L, "b.jpg", 1_718_158_320_000L},
                {3L, "c.jpg", 1_718_161_920_000L}});
        Shadows.shadowOf(ctx.getContentResolver())
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);

        registerJpeg(1L, true, "2024:06:12 10:12:00");
        registerJpeg(2L, true, "2024:06:12 11:12:00");
        registerJpeg(3L, false, "2024:06:12 12:12:00");
    }

    private void registerJpeg(long id, boolean withGps, String dateTime) throws Exception {
        File file = new File(ctx.getCacheDir(), id + ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        if (withGps) exif.setLatLong(48.85 + id / 100d, 2.29 + id / 100d);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        exif.saveAttributes();

        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(id)).build();
        // 브리프 원안은 이 bare uri 에 스트림을 등록했지만, ExifExtractor 는
        // MediaStore.setRequireOriginal(uri) 로 얻은(쿼리 파라미터 "?requireOriginal=1" 이 붙은)
        // 별도의 Uri 로만 스트림을 연다 — ShadowContentResolver#registerInputStream 은 정확히
        // 같은 Uri 로만 매칭하므로(ExifExtractorTest 의 선례), 실제로 여는 Uri 에 등록한다.
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(MediaStore.setRequireOriginal(uri), new FileInputStream(file));
    }

    /** 백그라운드 작업 + 메인 루퍼 콜백이 모두 소진될 때까지 돌린다. */
    private void drain() {
        for (int i = 0; i < 50; i++) {
            ShadowLooper.idleMainLooper();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ShadowLooper.idleMainLooper();
            if (vm.state().getValue() != null && vm.state().getValue().done) return;
            if (Boolean.TRUE.equals(vm.abandoned().getValue())) return;
        }
    }

    @Test
    public void analyzesEverySelectedPhotoAndReportsProgress() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        AnalysisUiState state = vm.state().getValue();
        assertNotNull(state);
        assertTrue("완료 상태로 끝나야 한다", state.done);
        assertEquals(3, state.total);
        assertEquals(3, state.analyzed);
        assertEquals(100, state.progressPercent());
    }

    @Test
    public void gpsPhotosBecomeRouteAndTheRestBecomeUnknown() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        AnalysisUiState state = vm.state().getValue();
        assertEquals("GPS 2장이 경로에 오른다", 2, state.routeCount);
        assertEquals("GPS 없는 1장은 위치 미상", 1, state.unknownCount);
    }

    @Test
    public void savedTripIsReopenableWithTheRouteIntact() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        String tripId = vm.savedTripId().getValue();
        assertNotNull("완료되면 tripId 가 나와야 한다", tripId);

        // 브리프 원안은 open() 직후 idleMainLooper() 를 한 번만 불렀지만, tripRepo.open() 도
        // executors.io() 의 진짜 백그라운드 스레드로 넘어가므로 한 번의 호출은 그 작업이 메인
        // 루퍼에 콜백을 쌓기 전에 지나가 버릴 수 있다(레이스) — drain() 과 같은 방식으로
        // 콜백이 실제로 도착할 때까지 폴링한다.
        AtomicReference<TripDetail> box = new AtomicReference<>();
        AtomicBoolean tripOpened = new AtomicBoolean(false);
        tripRepo.open(tripId, detail -> {
            box.set(detail);
            tripOpened.set(true);
        });
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!tripOpened.get() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (!tripOpened.get()) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        assertTrue("tripRepo.open 콜백이 도착해야 한다", tripOpened.get());

        TripDetail detail = box.get();
        assertNotNull(detail);
        assertEquals(2, detail.stops.size());
        assertEquals("시각순 정렬", 1L, detail.stops.get(0).mediaStoreId);
        assertEquals(1, detail.unknownCount);
    }

    @Test
    public void selectionIsClearedAfterSaving() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        assertTrue("저장이 끝나면 세션을 비워 재진입 시 중복 저장을 막는다", session.isEmpty());
    }

    @Test
    public void emptySessionAbandonsInsteadOfAnalyzing() {
        vm.start();
        ShadowLooper.idleMainLooper();

        assertTrue("프로세스 사망 후 재진입 — Home 으로 돌려보낸다",
                Boolean.TRUE.equals(vm.abandoned().getValue()));
        assertNull(vm.savedTripId().getValue());
    }

    @Test
    public void cancelStopsTheRunAndLeavesNothingSaved() {
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        vm.cancel();
        drain();

        assertNull("취소하면 여행이 저장되지 않는다", vm.savedTripId().getValue());
    }
}
