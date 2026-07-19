package com.traveltrace.app.data.exif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.TimeZone;

// 브리프 원안과의 두 가지 편차(Robolectric 4.14.1 실측):
//
// 1) jpegWithExif() 는 원안대로 스트림을 "원본 uri"에 등록했지만, ExifExtractor 는
//    MediaStore.setRequireOriginal(uri) 로 얻은 (쿼리 파라미터 "?requireOriginal=1" 가
//    붙은) 별도의 Uri 로만 스트림을 연다. ShadowContentResolver#registerInputStream 은
//    정확히 같은 Uri 문자열로만 매칭하므로(바이트코드 확인: inputStreamMap.get(uri) 는
//    Uri.equals 기반 정확 일치), 원안 그대로면 실제로 등록된 스트림을 못 찾는다.
//    이때 흔히 기대하듯 예외가 나거나 null 이 오는 게 아니라 — 미등록 content:// Uri 는
//    ShadowContentResolver$UnregisteredInputStream 으로 대체되고, androidx.exifinterface
//    의 ExifInterface(InputStream) 생성자는 IOException 뿐 아니라 UnsupportedOperationException
//    까지 내부에서 통째로 삼켜 "빈 EXIF" 를 조용히 돌려준다(디컴파일로 확인:
//    loadAttributes() 의 예외 테이블이 두 타입 모두를 잡는다). 그 결과 GPS 가 진짜로 있는
//    사진인데도 "GPS 없음"과 똑같이 보여 브리프가 요구한 "GPS 가린 파생본과의 구분"이
//    실제로는 검증되지 않는 상태였다. 고쳐서 실제 setRequireOriginal() 결과 Uri 에
//    등록한다.
//
// 2) unreadableStreamIsCountedAndDegradesToUnknown() 은 원안대로 "등록 안 한 Uri" 를
//    그대로 넘겼지만, 위에서 설명한 대로 미등록 Uri 도 예외 없이 빈 ExifInterface 로
//    조용히 성공한다 — 그래서 원안 그대로면 실패 카운터가 절대 오르지 않는다(항상 0).
//    실제 기기에서 원본 접근이 막히는 상황(권한 철회·행 삭제 등)을 재현하려면
//    openInputStream() 자체가 던져야 한다. MediaStoreImageSourceTest 의 선례처럼
//    ShadowContentResolver.registerProviderInternal (여기서는 attachInfo 까지 해 주는
//    Robolectric.setupContentProvider) 로 openFile() 에서 FileNotFoundException 을
//    던지는 실제 ContentProvider 를 등록해 그 경로를 재현한다.
@RunWith(RobolectricTestRunner.class)
public class ExifExtractorTest {

    private Context ctx;
    private ExifExtractor extractor;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        extractor = new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul"));
    }

    /**
     * 최소 JPEG 을 만들고 EXIF 를 써 넣은 뒤, 그 파일을 uri 에 물린다.
     * withGps=false 면 GPS 태그를 아예 쓰지 않아 "GPS 가린 파생본"을 흉내낸다.
     */
    private Uri jpegWithExif(String name, boolean withGps, String dateTime, String offset)
            throws Exception {
        File file = new File(ctx.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        if (withGps) {
            exif.setLatLong(48.8584, 2.2945);
        }
        if (dateTime != null) {
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
        }
        if (offset != null) {
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset);
        }
        exif.saveAttributes();

        Uri uri = Uri.parse("content://media/external/images/media/" + name.hashCode());
        // ExifExtractor 가 실제로 여는 건 이 uri 가 아니라 setRequireOriginal(uri) 다 —
        // 그 결과 Uri 에 스트림을 등록해야 진짜 원본 판독 경로를 검증하는 셈이 된다.
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(MediaStore.setRequireOriginal(uri),
                        new java.io.FileInputStream(file));
        return uri;
    }

    /** openFile() 에서 FileNotFoundException 을 던져 "원본 접근 실패"를 실제로 재현하는 provider. */
    public static class UnreadableMediaProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            return null;
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

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            throw new FileNotFoundException("가짜 원본 접근 실패(테스트)");
        }
    }

    private static GalleryImage image(Uri uri, long id, String name) {
        return new GalleryImage(id, uri, name, null);
    }

    @Test
    public void gpsPhotoBecomesPlacedWithRealCoordinates() throws Exception {
        Uri uri = jpegWithExif("gps.jpg", true, "2024:06:12 10:12:00", "+02:00");

        PhotoAnalysis result = extractor.extract(image(uri, 11L, "gps.jpg"));

        assertEquals(LocationSource.GPS, result.source);
        assertEquals(LocationClassification.PLACED, result.classification);
        assertNotNull(result.lat);
        assertEquals(48.8584, result.lat, 0.0001);
        assertEquals(2.2945, result.lng, 0.0001);
        assertTrue(result.takenAtHasOffset);
        assertEquals(Long.valueOf(1_718_179_920_000L), result.takenAtUtc);
    }

    @Test
    public void photoWithoutGpsBecomesUnknownAndKeepsNullCoordinates() throws Exception {
        Uri uri = jpegWithExif("nogps.jpg", false, "2024:06:12 10:12:00", "+02:00");

        PhotoAnalysis result = extractor.extract(image(uri, 12L, "nogps.jpg"));

        assertEquals(LocationSource.NONE, result.source);
        assertEquals(LocationClassification.UNKNOWN, result.classification);
        assertNull("좌표가 없으면 null 이어야 한다 — 0.0 이면 (0,0) 핀이 생긴다", result.lat);
        assertNull(result.lng);
    }

    @Test
    public void gpsPhotoWithoutTimeBecomesNoTime() throws Exception {
        Uri uri = jpegWithExif("notime.jpg", true, null, null);

        PhotoAnalysis result = extractor.extract(image(uri, 13L, "notime.jpg"));

        assertEquals(LocationSource.GPS, result.source);
        assertEquals("좌표는 있는데 시각이 없으면 NO_TIME — 경로 순서에서 빠진다",
                LocationClassification.NO_TIME, result.classification);
        assertNotNull(result.lat);
        assertNull(result.takenAtUtc);
    }

    @Test
    public void missingOffsetFallsBackAndIsFlagged() throws Exception {
        Uri uri = jpegWithExif("nooffset.jpg", true, "2024:06:12 10:12:00", null);

        PhotoAnalysis result = extractor.extract(image(uri, 14L, "nooffset.jpg"));

        assertEquals(LocationClassification.PLACED, result.classification);
        assertEquals("기기 타임존(Asia/Seoul) 폴백",
                Long.valueOf(1_718_154_720_000L), result.takenAtUtc);
        assertEquals(false, result.takenAtHasOffset);
    }

    @Test
    public void unreadableStreamIsCountedAndDegradesToUnknown() {
        // 등록되지 않은 content:// Uri 를 그냥 넘기면(브리프 원안) Robolectric 이
        // 예외 없이 빈 스트림으로 조용히 응답해 실패를 재현하지 못한다(클래스 상단 주석
        // 참고). openFile() 이 실제로 던지는 provider 를 등록해 원본 접근 실패를 재현한다.
        Robolectric.setupContentProvider(UnreadableMediaProvider.class, MediaStore.AUTHORITY);
        Uri missing = Uri.parse("content://media/external/images/media/999999");

        PhotoAnalysis result = extractor.extract(image(missing, 99L, "gone.jpg"));

        assertEquals(LocationClassification.UNKNOWN, result.classification);
        assertNull(result.lat);
        assertEquals("원본 접근 실패는 반드시 세어야 한다 — 조용히 삼키면 S3 에서 비용이 폭증한다",
                1, extractor.originalAccessFailures());
    }

    @Test
    public void displayNameAndIdSurviveExtraction() throws Exception {
        Uri uri = jpegWithExif("named.jpg", true, "2024:06:12 10:12:00", "+02:00");

        PhotoAnalysis result = extractor.extract(image(uri, 42L, "named.jpg"));

        assertEquals(42L, result.mediaStoreId);
        assertEquals("named.jpg", result.displayName);
    }
}
