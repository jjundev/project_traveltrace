package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * 프라이버시 계약(PRD §5)의 회귀 방지선. 업로드 산출물에 GPS/시각 EXIF 가 남으면
 * 사진의 실제 촬영 위치가 외부 API 로 새어나간다 — 이 앱이 AI 에 사진을 보내면서도
 * 위치는 로컬에만 둔다고 말할 수 있는 근거가 통째로 무너지는 지점이다.
 *
 * <p>NATIVE 그래픽스 모드가 필수다. 기본(LEGACY) 모드에서는 Robolectric 이
 * BitmapFactory/Bitmap.compress 를 가짜로 대체하므로 "재인코딩이 EXIF 를 지운다"를
 * 검증할 수 없다 — 실제 Skia 인코더가 돌아야 의미 있는 테스트다.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UploadPreparerTest {

    private Context ctx;
    private UploadPreparer preparer;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        preparer = new UploadPreparer(ctx);
    }

    /** GPS·촬영시각 EXIF 를 실제로 박은 JPEG 를 만들어 ContentResolver 에 등록한다. */
    private Uri seedJpegWithExif(int width, int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF3366CC);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, raw);

        // ExifInterface 는 파일 경로로 열어야 저장(saveAttributes)이 가능하다.
        File file = File.createTempFile("seed", ".jpg", ctx.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(raw.toByteArray());
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,2999/100");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,17/1,4000/100");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:06:01 09:30:00");
        exif.saveAttributes();

        byte[] withExif = readFile(file);
        Uri uri = Uri.parse("content://media/external/images/media/42");
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(uri, new ByteArrayInputStream(withExif));
        return uri;
    }

    private static byte[] readFile(File file) throws Exception {
        byte[] buf = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return buf;
    }

    @Test
    public void strippedOutputHasNoGpsOrTimestampExif() throws Exception {
        Uri uri = seedJpegWithExif(2000, 1500);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        ExifInterface out = new ExifInterface(new ByteArrayInputStream(prepared.jpeg));
        assertNull("업로드본에 GPS 위도가 남으면 안 된다", out.getLatLong());
        assertNull("업로드본에 촬영 시각이 남으면 안 된다",
                out.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
    }

    @Test
    public void downscalesLongEdgeToTheCap() throws Exception {
        Uri uri = seedJpegWithExif(2000, 1500);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.length, bounds);
        assertEquals("장변이 상한에 맞춰진다", UploadPreparer.MAX_EDGE_PX, bounds.outWidth);
        assertEquals("종횡비가 유지된다", 768, bounds.outHeight);
    }

    @Test
    public void doesNotUpscaleSmallOriginals() throws Exception {
        Uri uri = seedJpegWithExif(320, 240);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.length, bounds);
        assertEquals("작은 원본을 늘리면 인식률은 그대로인데 업로드 비용만 커진다",
                320, bounds.outWidth);
        assertEquals(240, bounds.outHeight);
    }

    @Test
    public void outputIsAlwaysJpegRegardlessOfInput() throws Exception {
        Uri uri = seedJpegWithExif(800, 600);

        UploadPreparer.Prepared prepared = preparer.prepare(uri);

        // SOI 마커 0xFFD8 — 포맷 분기 없이 항상 JPEG 로 재인코딩된다는 계약의 증거.
        assertEquals((byte) 0xFF, prepared.jpeg[0]);
        assertEquals((byte) 0xD8, prepared.jpeg[1]);
    }

    @Test
    public void contentHashIsStableAcrossCallsAndDiffersByContent() throws Exception {
        Uri a1 = seedJpegWithExif(400, 300);
        String first = preparer.prepare(a1).contentHash;
        Uri a2 = seedJpegWithExif(400, 300);
        String second = preparer.prepare(a2).contentHash;
        assertEquals("같은 바이트는 같은 해시 — S4 캐시 키의 전제", first, second);

        Uri other = seedJpegWithExif(401, 300);
        assertNotEquals("다른 사진은 다른 해시", first, preparer.prepare(other).contentHash);
    }

    @Test
    public void hashIsSha256Hex() throws Exception {
        String hash = preparer.prepare(seedJpegWithExif(200, 200)).contentHash;
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
