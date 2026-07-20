package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.vision.VertexResponseParser;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.VisionProvider;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * {@link PhotoAnalysisPipeline} 은 전송 파이프라인의 유일한 진입점이라 여기서 틀리면
 * "GPS 사진이 실수로 업로드된다"거나 "AI 경로 실패 1건이 배치 전체를 죽인다"는 회귀가
 * 곧장 프로덕션으로 샌다. 이 테스트가 잠그는 것은 두 가지 계약뿐이다:
 * <ol>
 *   <li>GPS 사진은 ②③(재인코딩·업로드·인식)을 통째로 건너뛴다.</li>
 *   <li>②③ 블록에서 던진 예외는 절대 analyze() 밖으로 새지 않는다(S3 는 재시도하지 않는다).</li>
 * </ol>
 *
 * <p>NATIVE 그래픽스 모드가 필수다 — {@link UploadPreparer} 가 실제 Skia 인코더로
 * 재인코딩해야 하며(UploadPreparerTest 의 선례), 기본(LEGACY) 모드는 이를 가짜로 대체한다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoAnalysisPipelineTest {

    private Context ctx;
    private ExifExtractor exif;
    private UploadPreparer preparer;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        exif = new ExifExtractor(ctx, TimeZone.getTimeZone("Asia/Seoul"));
        preparer = new UploadPreparer(ctx);
    }

    /**
     * 실제 JPEG 를 임시 파일에 만들고 ContentResolver 에 등록한다.
     *
     * <p>스트림을 두 Uri 모두에 등록한다 — {@link ExifExtractor} 는
     * {@code MediaStore.setRequireOriginal(uri)} 로 얻은 Uri 로만 열고,
     * {@link UploadPreparer} 는 일반 {@code uri} 를 그대로 연다(AnalysisPipelineTest 의 선례).
     * 스트림은 1회용이므로 두 등록에 각각 새 {@link FileInputStream} 을 쓴다.
     */
    private Uri registerJpeg(long id, boolean withGps, String dateTime) throws Exception {
        File file = new File(ctx.getCacheDir(), "pipeline-" + id + ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exifWriter = new ExifInterface(file.getAbsolutePath());
        if (withGps) {
            exifWriter.setLatLong(48.85, 2.29);
        }
        if (dateTime != null) {
            exifWriter.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
            exifWriter.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        }
        exifWriter.saveAttributes();

        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(id)).build();
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(MediaStore.setRequireOriginal(uri), new FileInputStream(file));
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(uri, new FileInputStream(file));
        return uri;
    }

    @Test
    public void gpsPhotoSkipsPrepareAndRecognizeEntirely() throws Exception {
        Uri uri = registerJpeg(1L, true, "2024:06:12 10:12:00");
        GalleryImage image = new GalleryImage(1L, uri, "a.jpg", null, 2048L);
        FakeVisionProvider vision = new FakeVisionProvider();
        PhotoAnalysisPipeline pipeline = new PhotoAnalysisPipeline(
                exif, preparer, vision, new AiLocationResolver(new FakeGeocoder(Collections.emptyList())));

        PhotoAnalysis result = pipeline.analyze(image);

        assertEquals("GPS 사진은 GPS 출처로 남는다", LocationSource.GPS, result.source);
        assertEquals("GPS 사진은 AI 호출이 0회여야 한다(PRD §4.2-1)", 0, vision.callCount);
        assertNull("contentHash 는 ②③ 블록 안에서만 세팅된다 — null 이면 prepare() 가 "
                + "전혀 호출되지 않았다는 증거다", result.contentHash);
    }

    @Test
    public void recognitionFailureLeavesThePhotoUnknownWithoutThrowing() throws Exception {
        Uri uri = registerJpeg(2L, false, "2024:06:12 11:12:00");
        GalleryImage image = new GalleryImage(2L, uri, "b.jpg", null, 2048L);
        FakeVisionProvider vision = new FakeVisionProvider();
        vision.toReturn = VertexResponseParser.UNRECOGNIZED;
        PhotoAnalysisPipeline pipeline = new PhotoAnalysisPipeline(
                exif, preparer, vision, new AiLocationResolver(new FakeGeocoder(Collections.emptyList())));

        PhotoAnalysis result = pipeline.analyze(image);

        assertEquals("실내·음식 사진은 위치 미상이 정상 결과다",
                LocationClassification.UNKNOWN, result.classification);
        assertEquals(LocationSource.NONE, result.source);
    }

    @Test
    public void visionExceptionDoesNotCrashTheBatch() throws Exception {
        Uri uri = registerJpeg(3L, false, "2024:06:12 12:12:00");
        GalleryImage image = new GalleryImage(3L, uri, "c.jpg", null, 2048L);
        FakeVisionProvider vision = new FakeVisionProvider();
        vision.toThrow = new IOException("boom");
        PhotoAnalysisPipeline pipeline = new PhotoAnalysisPipeline(
                exif, preparer, vision, new AiLocationResolver(new FakeGeocoder(Collections.emptyList())));

        // 던지지 않고 정상 반환해야 한다 — S3 는 재시도하지 않으므로 사진 1장의 실패가
        // 배치 전체를 멈추면 안 된다.
        PhotoAnalysis result = pipeline.analyze(image);

        assertEquals("실패한 사진은 위치 미상으로 남는다",
                LocationClassification.UNKNOWN, result.classification);
    }

    @Test
    public void happyAiPathProducesAPlacedStop() throws Exception {
        Uri uri = registerJpeg(4L, false, "2024:06:12 13:12:00");
        GalleryImage image = new GalleryImage(4L, uri, "d.jpg", null, 2048L);
        FakeVisionProvider vision = new FakeVisionProvider();
        vision.toReturn = new RecognitionResult("에펠탑", "파리", "프랑스", 0.9);
        FakeGeocoder geocoder = new FakeGeocoder(
                Collections.singletonList(new GeoPoint(48.8584, 2.2945)));
        PhotoAnalysisPipeline pipeline = new PhotoAnalysisPipeline(
                exif, preparer, vision, new AiLocationResolver(geocoder));

        PhotoAnalysis result = pipeline.analyze(image);

        assertEquals(LocationSource.AI, result.source);
        assertEquals(LocationClassification.PLACED, result.classification);
        assertEquals(48.8584, result.lat, 1e-9);
        assertEquals(2.2945, result.lng, 1e-9);
        assertEquals("에펠탑", result.landmarkName);
        assertNotNull("업로드 경로가 실제로 돌았다는 증거", result.contentHash);
    }

    /** 테스트 안에서만 쓰는 손수 만든 페이크(저장소 규약: 모킹 라이브러리 없음). */
    private static final class FakeVisionProvider implements VisionProvider {
        int callCount;
        RecognitionResult toReturn;
        Exception toThrow;

        @Override
        public RecognitionResult recognize(byte[] imageJpeg) throws Exception {
            callCount++;
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn;
        }
    }

    private static final class FakeGeocoder implements Geocoder {
        private final List<GeoPoint> results;

        FakeGeocoder(List<GeoPoint> results) {
            this.results = results;
        }

        @Override
        public List<GeoPoint> geocode(GeocodeQuery query) {
            return results;
        }
    }
}
