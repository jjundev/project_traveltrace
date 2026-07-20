package com.traveltrace.app.data.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.data.media.AlbumBucket;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * loadByIds() 도 loadRecent() 와 같은 콜백 스레딩 계약(io 에서 실행, 메인 루퍼로 전달)을
     * 따르므로 같은 폴링 방식으로 기다린다.
     */
    private List<GalleryImage> loadByIds(List<Long> ids) {
        AtomicReference<List<GalleryImage>> box = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        Callback<List<GalleryImage>> callback = value -> {
            box.set(value);
            done.set(true);
        };
        source.loadByIds(ids, callback);

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

    /**
     * ShadowContentResolver#setCursor 는 selection/selectionArgs 를 완전히 무시하고 등록된
     * 커서를 그대로 돌려주므로(위 테스트들이 그 훅을 쓴다), loadByIds() 가 실제로
     * "_ID IN (...)" 로 걸러 쿼리하는지는 그 훅으로는 검증할 수 없다. 대신
     * securityExceptionOnQueryYieldsEmptyListNotCrash 와 같은 패턴으로 진짜 ContentProvider
     * 를 등록해, 넘어온 selection/selectionArgs 를 실제로 해석해 걸러 돌려주는 최소 구현으로
     * 프로덕션 SQL 필터링 계약을 재현한다 — "갤러리 전체가 아니라 요청한 id 만 온다"를
     * 의미 있게 확인하는 유일한 방법이다(finding 5).
     */
    @Test
    public void loadByIdsFiltersToOnlyTheRequestedIdsViaSqlSelection() {
        List<Object[]> gallery = Arrays.asList(
                new Object[]{1L, "a.jpg", 3_000L},
                new Object[]{2L, "b.jpg", 2_000L},
                new Object[]{3L, "c.jpg", 1_000L});

        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, new ContentProvider() {
            @Override
            public boolean onCreate() {
                return true;
            }

            @Override
            public Cursor query(Uri uri, String[] projection, String selection,
                    String[] selectionArgs, String sortOrder) {
                Set<Long> wanted = new HashSet<>();
                for (String arg : selectionArgs) wanted.add(Long.parseLong(arg));

                List<Object[]> filtered = new ArrayList<>();
                for (Object[] row : gallery) {
                    if (wanted.contains((Long) row[0])) filtered.add(row);
                }
                RoboCursor cursor = new RoboCursor();
                cursor.setColumnNames(Arrays.asList(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_TAKEN));
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

        List<GalleryImage> images = loadByIds(Arrays.asList(1L, 3L));

        assertEquals("요청한 id 2개만 와야 한다(전체 3장이 아니라)", 2, images.size());
        assertEquals(1L, images.get(0).id);
        assertEquals(3L, images.get(1).id);
    }

    @Test
    public void loadByIdsWithNoIdsReturnsEmptyListWithoutCrashing() {
        assertTrue("빈 id 목록이면 쿼리 없이 바로 빈 목록", loadByIds(new ArrayList<>()).isEmpty());
    }

    // ---- 앨범(버킷) 집계 ----

    private void seedBuckets(Object[]... rows) {
        RoboCursor cursor = new RoboCursor();
        cursor.setColumnNames(Arrays.asList(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME));
        cursor.setResults(rows);
        Shadows.shadowOf(ctx.getContentResolver())
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor);
    }

    private List<AlbumBucket> loadAlbums() {
        AtomicReference<List<AlbumBucket>> box = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        source.loadAlbums(value -> {
            box.set(value);
            done.set(true);
        });

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
    public void loadAlbumsAggregatesCountsPerBucket() {
        seedBuckets(
                new Object[]{"1", "카메라"},
                new Object[]{"1", "카메라"},
                new Object[]{"1", "카메라"},
                new Object[]{"2", "카카오톡"},
                new Object[]{"2", "카카오톡"});

        List<AlbumBucket> albums = loadAlbums();

        assertEquals(2, albums.size());
        // 큰 앨범이 먼저 온다 — 사용자가 실제로 고를 만한 앨범이 위로 오게.
        assertEquals("1", albums.get(0).bucketId);
        assertEquals("카메라", albums.get(0).displayName);
        assertEquals(3, albums.get(0).count);
        assertEquals("2", albums.get(1).bucketId);
        assertEquals(2, albums.get(1).count);
    }

    @Test
    public void loadAlbumsWithEmptyGalleryYieldsEmptyListNotNull() {
        seedBuckets();
        assertTrue(loadAlbums().isEmpty());
    }

    @Test
    public void loadAlbumsSecurityExceptionYieldsEmptyListNotCrash() {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, new ContentProvider() {
            @Override
            public boolean onCreate() {
                return true;
            }

            @Override
            public Cursor query(Uri uri, String[] projection, String selection,
                    String[] selectionArgs, String sortOrder) {
                throw new SecurityException("갤러리 접근 권한 없음(테스트)");
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

        assertTrue(loadAlbums().isEmpty());
    }

    /**
     * setCursor 훅은 selection 을 무시하므로(위 loadByIds 테스트와 같은 이유), 실제 SQL
     * 필터링은 ContentProvider 스텁으로만 검증할 수 있다 — "선택한 앨범 사진만 온다"의
     * 유일한 의미 있는 증거.
     */
    @Test
    public void loadRecentWithBucketIdFiltersViaSqlSelection() {
        List<Object[]> gallery = Arrays.asList(
                new Object[]{1L, "cam1.jpg", 3_000L},
                new Object[]{2L, "kakao1.jpg", 2_000L},
                new Object[]{3L, "cam2.jpg", 1_000L});

        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, new ContentProvider() {
            @Override
            public boolean onCreate() {
                return true;
            }

            @Override
            public Cursor query(Uri uri, String[] projection, String selection,
                    String[] selectionArgs, String sortOrder) {
                assertEquals(MediaStore.Images.Media.BUCKET_ID + " = ?", selection);
                String wantedBucket = selectionArgs[0];
                List<Object[]> filtered = new ArrayList<>();
                for (Object[] row : gallery) {
                    // cam* 은 버킷 "1", kakao* 는 버킷 "2" 라고 가정한 테스트 전용 매핑.
                    boolean inCam = ((String) row[1]).startsWith("cam") && wantedBucket.equals("1");
                    if (inCam) filtered.add(row);
                }
                RoboCursor cursor = new RoboCursor();
                cursor.setColumnNames(Arrays.asList(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_TAKEN));
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

        List<GalleryImage> images = loadRecentWithBucket(100, "1");

        assertEquals("버킷 1(카메라) 사진 2장만 와야 한다", 2, images.size());
    }

    private List<GalleryImage> loadRecentWithBucket(int limit, String bucketId) {
        AtomicReference<List<GalleryImage>> box = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        source.loadRecent(limit, bucketId, value -> {
            box.set(value);
            done.set(true);
        });

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
    public void loadRecentWithNullBucketIdAppliesNoFilter() {
        seed(new Object[]{1L, "a.jpg", 3_000L}, new Object[]{2L, "b.jpg", 2_000L});

        assertEquals("null 버킷이면 기존과 같이 전부 온다", 2, loadRecentWithBucket(100, null).size());
    }

    // MediaStoreImageSource#query() 는 SecurityException 을 잡아 빈 목록으로 조용히
    // 끝내는 걸 명시적 계약으로 삼는다(클래스 Javadoc 참고) — 권한이 없거나 철회된 상태에서
    // 크래시 대신 "사진 없음"으로 내려가야 한다.
    //
    // ShadowContentResolver#setCursor 로는 이 경로를 못 만든다: 예외 없이 커서만 갈아
    // 끼우는 훅이라서다. 대신 query()의 실제 디스패치 경로를 이용한다 — 4.14.1의
    // ShadowContentResolver#query 는 먼저 ShadowContentResolver#getProvider(authority)로
    // 등록된 실제 ContentProvider 가 있는지 보고, 있으면 그 provider.query()를 그대로
    // 호출한 뒤에야 setCursor 로 등록한 커서로 폴백한다(디컴파일로 확인함). 그래서
    // registerProviderInternal(MediaStore.AUTHORITY, ...)로 query()에서 SecurityException
    // 을 던지는 최소 ContentProvider 를 등록하면, 실제 권한 거부 시 MediaStore가 겪는
    // 예외 전달 경로를 그대로 재현할 수 있다. registerProviderInternal 은 프로덕션 코드를
    // 건드리지 않고도 이 경로를 여는, 이 버전이 제공하는 유일한 공식 훅이다.
    @Test
    public void securityExceptionOnQueryYieldsEmptyListNotCrash() {
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, new ContentProvider() {
            @Override
            public boolean onCreate() {
                return true;
            }

            @Override
            public Cursor query(Uri uri, String[] projection, String selection,
                    String[] selectionArgs, String sortOrder) {
                throw new SecurityException("갤러리 접근 권한 없음(테스트)");
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

        assertTrue("SecurityException 은 삼켜지고 빈 목록이 와야 한다(크래시 금지)",
                load(100).isEmpty());
    }
}
