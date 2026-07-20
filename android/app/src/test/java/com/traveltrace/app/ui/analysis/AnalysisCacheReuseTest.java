package com.traveltrace.app.ui.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.AsyncTestHarness;
import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.ContentHasher;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.data.repo.RoomAnalysisCacheStore;
import com.traveltrace.app.data.repo.RoomPhotoAnalysisRepository;
import com.traveltrace.app.data.repo.RoomTripRepository;
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
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 같은 사진을 두 여행에서 연달아 분석했을 때, 두 번째 분석이 EXIF 를 다시 읽지 않고
 * 캐시된 결과를 쓰는지 검증한다 (S8 수용 기준: "같은 사진 재분석 시 캐시 hit, 다른
 * 여행에서도 hit").
 */
@RunWith(RobolectricTestRunner.class)
public class AnalysisCacheReuseTest {

    /** extract() 가 실제로 몇 번 불렸는지 세는 스파이. Mockito 없이 상속으로 만든다. */
    private static class CountingExifExtractor extends ExifExtractor {
        final AtomicInteger extractions = new AtomicInteger();

        CountingExifExtractor(Context ctx) {
            super(ctx, TimeZone.getTimeZone("Asia/Seoul"));
        }

        @Override
        public PhotoAnalysis extract(GalleryImage image) {
            extractions.incrementAndGet();
            return super.extract(image);
        }
    }

    private Context ctx;
    private AppExecutors executors;
    private TravelTraceDatabase db;
    private SelectionSession session;
    private RoomTripRepository tripRepo;
    private AnalysisCostLog costLog;
    private CountingExifExtractor extractor;

    @Before
    public void setUp() throws Exception {
        ctx = ApplicationProvider.getApplicationContext();
        executors = new AppExecutors();
        db = Room.inMemoryDatabaseBuilder(ctx, TravelTraceDatabase.class)
                .allowMainThreadQueries()
                .build();
        session = new SelectionSession();
        tripRepo = new RoomTripRepository(db, executors);
        costLog = new AnalysisCostLog();
        extractor = new CountingExifExtractor(ctx);

        seedGallery();
    }

    @After
    public void tearDown() {
        executors.shutdown();
        db.close();
    }

    /**
     * GPS 가 있는 사진 2장. 크기까지 커서에 실어야 해시가 크기를 섞을 수 있다.
     *
     * <p>배치를 여행마다 새로 돌리므로(analyze() 를 한 테스트에서 여러 번 부른다)
     * {@code ShadowContentResolver#setCursor} 한 번으로는 부족하다 — 디컴파일로 확인한
     * 4.14.1 동작은 selection/selectionArgs 를 완전히 무시하고 <b>등록해 둔 커서 객체를
     * 그대로</b> 돌려준다(MediaStoreImageSourceTest 의 loadByIdsFiltersToOnlyTheRequestedIdsViaSqlSelection
     * 자바독 참고). 그러면 두 문제가 동시에 난다: (1) "_ID IN (1)" 처럼 부분집합을 물어도
     * 등록된 행 전부가 오고, (2) 커서 인스턴스가 재사용되며 첫 순회로 소진된 채라 두 번째
     * open() 이 빈 결과를 준다(스트림에 registerInputStreamSupplier 가 필요했던 것과 같은
     * 부류의 함정). 그래서 진짜 ContentProvider 를 등록해 매 쿼리마다 selectionArgs 로 걸러
     * <em>새</em> RoboCursor 를 만들어 돌려준다 — 그래야 "_ID IN (...)" 가 실제로 의미를
     * 갖고, 반복 호출도 매번 신선한 커서를 받는다.
     */
    private void seedGallery() throws Exception {
        long sizeOne = registerJpeg(1L, "2024:06:12 10:12:00");
        long sizeTwo = registerJpeg(2L, "2024:06:12 11:12:00");

        List<Object[]> gallery = Arrays.asList(
                new Object[]{1L, "a.jpg", 1_718_154_720_000L, sizeOne},
                new Object[]{2L, "b.jpg", 1_718_158_320_000L, sizeTwo});

        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, new ContentProvider() {
            @Override
            public boolean onCreate() {
                return true;
            }

            @Override
            public Cursor query(Uri uri, String[] projection, String selection,
                    String[] selectionArgs, String sortOrder) {
                Set<Long> wanted = new HashSet<>();
                if (selectionArgs != null) {
                    for (String arg : selectionArgs) wanted.add(Long.parseLong(arg));
                }

                List<Object[]> filtered = new ArrayList<>();
                for (Object[] row : gallery) {
                    if (selectionArgs == null || wanted.contains((Long) row[0])) filtered.add(row);
                }
                RoboCursor cursor = new RoboCursor();
                cursor.setColumnNames(Arrays.asList(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.SIZE));
                cursor.setResults(filtered.toArray(new Object[0][]));
                return cursor;
            }

            @Override
            public String getType(Uri uri) {
                return null;
            }

            @Override
            public Uri insert(Uri uri, ContentValues values) {
                return null;
            }

            @Override
            public int delete(Uri uri, String selection, String[] selectionArgs) {
                return 0;
            }

            @Override
            public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
                return 0;
            }
        });
    }

    /**
     * 배치를 두 번 돌리므로 각 Uri 가 여러 번 열린다 — registerInputStream 은 스트림
     * 인스턴스를 하나만 들고 있어 두 번째 open 이 고갈된 스트림을 받는다. 반드시
     * registerInputStreamSupplier 로 매번 새 스트림을 만들어야 한다.
     *
     * <p>ExifExtractor 는 setRequireOriginal(uri) 를, ContentHasher 는 평범한 uri 를
     * 연다(설계 결정 2) — 서로 다른 Uri 라 양쪽 모두 등록해야 한다.
     */
    private long registerJpeg(long id, String dateTime) throws Exception {
        File file = new File(ctx.getCacheDir(), id + ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setLatLong(48.85 + id / 100d, 2.29 + id / 100d);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        exif.saveAttributes();

        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(id)).build();
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(uri, () -> open(file));
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(MediaStore.setRequireOriginal(uri), () -> open(file));
        return file.length();
    }

    private static FileInputStream open(File file) {
        try {
            return new FileInputStream(file);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private AnalysisViewModel newViewModel() {
        return new AnalysisViewModel(
                ctx,
                new MediaStoreImageSource(ctx, executors),
                extractor,
                new RoomPhotoAnalysisRepository(db, executors),
                session,
                executors,
                new ContentHasher(ctx),
                new RoomAnalysisCacheStore(db),
                costLog);
    }

    /** 선택된 사진으로 배치를 1회 돌리고 저장된 tripId 를 돌려준다. */
    private String analyze(Long... ids) {
        session.put(Arrays.asList(ids));
        AnalysisViewModel vm = newViewModel();
        return AsyncTestHarness.awaitLiveData(
                vm.savedTripId(), vm::start, id -> id != null, "AnalysisViewModel.start()");
    }

    @Test
    public void reanalyzingTheSamePhotosInANewTripHitsTheCacheAndSkipsExif() {
        String firstTrip = analyze(1L, 2L);
        assertNotNull(firstTrip);
        assertEquals("첫 분석은 두 장 모두 EXIF 를 읽는다", 2, extractor.extractions.get());
        assertEquals("첫 분석은 두 장 모두 miss", 2, costLog.cacheMisses());
        assertEquals(0, costLog.cacheHits());

        String secondTrip = analyze(1L, 2L);
        assertNotNull(secondTrip);
        assertTrue("두 번째는 새 여행이어야 한다", !secondTrip.equals(firstTrip));

        assertEquals("두 번째 분석은 EXIF 를 한 장도 다시 읽지 않는다 — 캐시가 여행을 넘는다",
                2, extractor.extractions.get());
        assertEquals(2, costLog.cacheHits());
    }

    @Test
    public void aCacheHitStillProducesACompleteSavedTrip() {
        analyze(1L, 2L);
        String secondTrip = analyze(1L, 2L);

        TripDetail detail = AsyncTestHarness.awaitCallback(cb -> tripRepo.open(secondTrip, cb));

        assertNotNull(detail);
        assertEquals("캐시로 만든 여행도 좌표·시각이 전부 살아 있어야 한다",
                2, detail.stops.size());
        assertNotNull(detail.stops.get(0).lat);
        assertNotNull(detail.stops.get(0).takenAtUtc);
        assertEquals("파일명은 캐시가 아니라 살아 있는 MediaStore 값에서 온다",
                "a.jpg", detail.stops.get(0).displayName);
    }

    @Test
    public void noVisionOrGeocodeCallEverHappens() {
        analyze(1L, 2L);
        analyze(1L, 2L);

        assertEquals("GPS 사진은 AI 를 부르지 않는다 (PRD §4.2 우선순위)", 0, costLog.visionCalls());
        assertEquals(0, costLog.geocodeCalls());
    }

    @Test
    public void theContentHashIsPersistedOnThePhotoRow() {
        analyze(1L);

        assertEquals("캐시 행이 실제로 쓰였는지 — 사진 1장이면 항목 1개",
                1, db.analysisCacheDao().count());
    }

    @Test
    public void analyzingASubsetLaterStillHitsForThePhotosItShares() {
        analyze(1L, 2L);
        int afterFirst = extractor.extractions.get();

        analyze(1L);

        assertEquals("겹치는 한 장은 hit — 다른 선택 조합이어도 캐시는 사진 단위다",
                afterFirst, extractor.extractions.get());
        assertEquals(1, costLog.cacheHits());
    }
}
