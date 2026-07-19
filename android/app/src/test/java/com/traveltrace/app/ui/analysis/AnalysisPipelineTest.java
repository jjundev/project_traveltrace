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
import com.traveltrace.app.domain.Callback;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;
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

        // savedTripId 라이브데이터만 보면 안 된다 — RoomPhotoAnalysisRepository 는 Room
        // 트랜잭션을 커밋한 "뒤에" 콜백을 올리므로, 그 콜백의 cancelled 체크가 이미 durable
        // 해진 행을 막지는 못한다(finding 2, 수용된 레이스). 즉 savedTripId 가 null 인 것만으론
        // "아무것도 저장 안 됐다"를 증명하지 못하고, "저장은 됐는데 콜백이 UI 갱신만 건너뛰었다"
        // 와 구분이 안 된다. 여기서 취소는 vm.start() 직후, run() 이 for 루프를 돌기도 전에
        // 걸리므로(사진 EXIF 추출이 시작되기 전) saveTrip() 자체가 호출되지 않는 케이스만
        // 보장한다 — 그래서 DB 를 직접 조회해 trip 행이 하나도 없음을 확인한다.
        // (반대로 "루프를 다 돌고 saveTrip 콜백이 커밋한 바로 그 틈에" 취소가 끼는 창은
        // finding 2 가 명시한 대로 이 테스트가 다루지 않는 수용된 레이스다.)
        assertNull("취소하면 저장 완료 신호도 오지 않는다", vm.savedTripId().getValue());
        assertTrue("추출을 시작하기도 전에 취소되면 여행 행이 하나도 생기지 않는다",
                db.tripDao().listSummaries().isEmpty());
    }

    @Test
    public void onClearedCancelsAnInFlightBatchLikeAGenuineDeparture() {
        // AnalysisFragment.onDestroyView() 는 더 이상 vm.cancel() 을 부르지 않는다(finding 1) —
        // 회전으로 View 만 재생성돼도 그 콜백이 매번 불려 배치를 영구히 죽였기 때문이다.
        // 대신 진짜 취소 신호는 ViewModel.onCleared() 로 옮겼다. onCleared() 는 protected 지만
        // 이 테스트가 같은 패키지(com.traveltrace.app.ui.analysis)에 있으므로 Fragment/Hilt
        // 테스트 하네스(FragmentScenario, HiltTestApplication — 이 저장소엔 둘 다 없다) 없이도
        // "ViewModelStore 가 진짜로 이 ViewModel 을 버릴 때" 를 직접 재현할 수 있다.
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        vm.onCleared();
        drain();

        assertNull("onCleared 이후엔 저장 완료 신호가 오지 않는다", vm.savedTripId().getValue());
        assertTrue("onCleared 가 곧 취소이므로 추출 전 호출되면 여행 행이 생기지 않는다",
                db.tripDao().listSummaries().isEmpty());
    }

    @Test
    public void viewTeardownAloneNeverCancelsTheBatch() {
        // finding 1 이 지키려는 반대쪽 절반: 회전처럼 View 만 재생성되고 ViewModel 은
        // 살아남는 경우엔 그 무엇도 cancel()/onCleared() 를 부르지 않아야 배치가 끝까지
        // 진행돼 저장된다. AnalysisFragment.onDestroyView() 에 더는 vm.cancel() 호출이 없다는
        // 사실은 코드를 읽어 확인했고(Fragment/Hilt 하네스가 없어 실제 View 재생성을 이
        // 테스트로 몰아붙일 수는 없다), 여기서는 그 결과 — cancel()/onCleared() 를 아무도
        // 부르지 않으면 배치가 정상 완주한다 — 를 ViewModel 경계에서 확인한다.
        session.put(Arrays.asList(1L, 2L, 3L));

        vm.start();
        drain();

        assertNotNull("취소 신호가 없으면 정상적으로 저장까지 끝난다", vm.savedTripId().getValue());
        assertEquals("여행 행이 정확히 하나 생긴다", 1, db.tripDao().listSummaries().size());
    }

    /**
     * PhotoAnalysisRepository 페이크 — RoomPhotoAnalysisRepository 는 Room 트랜잭션을
     * 커밋한 "뒤에"만 콜백을 메인 스레드로 올린다. 즉 "행은 이미 durable 하게 저장됐는데
     * 콜백이 그 틈에 cancelled == true 를 보게 되는" 순간이 실제 존재하지만, 커밋과 콜백
     * 사이에 정확히 취소를 끼워 넣을 훅이 실제 레포지토리엔 없어서 테스트에서 그 순간을
     * 온디맨드로 재현할 방법이 없다(finding 2, out of bounds 라 원자적으로 만들지 않기로
     * 합의된 수용된 레이스). 이 페이크는 saveTrip() 이 tripId 콜백을 부르기 "직전"에
     * vm.cancel() 을 호출해 그 순간을 결정적으로 강제한다 — 실제 순서(커밋 완료 → 그
     * 뒤에 취소 관찰)를 그대로 흉내 내는 것이다.
     */
    private static final class CommitThenCancelRepository implements PhotoAnalysisRepository {
        private AnalysisViewModel viewModel;

        /** saveTrip() 을 받을 ViewModel 이 이 페이크보다 나중에 생성되므로 뒤늦게 붙인다. */
        void attachTo(AnalysisViewModel viewModel) {
            this.viewModel = viewModel;
        }

        @Override
        public void saveTrip(String name, String timeZoneId, java.util.List<PhotoAnalysis> results,
                              Callback<String> callback) {
            viewModel.cancel();
            callback.onResult("fake-trip-id");
        }
    }

    @Test
    public void sessionClearOrderingSurvivesACommitThenCancelRace() {
        // finding 2 가 지키려는 순서 그 자체를 핀으로 고정한다: session.clear() 는
        // cancelled 가드 "밖"에 있어야 한다. 위 페이크로 "커밋은 이미 끝났는데 콜백이
        // 그 직후 취소를 관찰하는" 상황을 강제로 만든 뒤, 계약의 두 절반을 함께 확인한다.
        // (1) 세션은 그래도 비워진다 — 같은 선택으로 재진입해 두 번째 여행이 생기는 걸
        // 막는 게 이 순서의 존재 이유다. (2) 하지만 화면은 이미 떠났으니 savedTripId 는
        // 여전히 null 이고 state 도 done 으로 넘어가지 않는다 — 저장은 됐어도 UI 갱신은
        // 계속 억제돼야 한다.
        //
        // session.clear() 를 다시 cancelled 가드 안으로 되돌리면: 페이크가 콜백을 부르기
        // 전에 이미 vm.cancel() 을 호출해 두므로, 가드가 clear() 보다 먼저 걸려 세션이
        // 전혀 비워지지 않는다 — 그래서 이 테스트는 그 회귀에서 반드시 실패한다.
        CommitThenCancelRepository fakeRepo = new CommitThenCancelRepository();
        AnalysisViewModel raceVm = new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul")),
                fakeRepo,
                session,
                executors);
        fakeRepo.attachTo(raceVm);

        session.put(Arrays.asList(1L, 2L, 3L));
        raceVm.start();

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!session.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ShadowLooper.idleMainLooper();
        }

        assertTrue("커밋 이후에 취소가 끼어들어도 세션은 비워 재진입 시 중복 저장을 막는다",
                session.isEmpty());
        assertNull("화면은 이미 사라졌으니 저장 완료 신호는 계속 억제된다",
                raceVm.savedTripId().getValue());
        AnalysisUiState raceState = raceVm.state().getValue();
        assertTrue("state 도 done 으로 넘어가면 안 된다", raceState == null || !raceState.done);
    }
}
