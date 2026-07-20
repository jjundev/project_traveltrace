package com.traveltrace.app.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 전송 파이프라인 ② 단계 (PRD §5): 원본 → 다운스케일 JPEG + 콘텐츠 해시.
 *
 * <p><b>EXIF 제거는 재인코딩이 보장한다.</b> {@code ExifInterface} 의 스트립은 JPEG
 * 전용이라 HEIC/RAW 엔 동작하지 않으므로 별도 스트립 단계를 두지 않는다. 대신 입력
 * 포맷과 무관하게 <em>항상</em> 픽셀로 디코드해 JPEG 로 다시 인코딩한다 — 산출물이
 * 원본 메타데이터를 물려받을 경로 자체가 없다. 포맷 분기가 없다는 것이 계약이다.
 *
 * <p><b>원본이 아닌 일반 URI 로 연다.</b> {@code setRequireOriginal} 은 여기서 쓰지
 * 않는다. ① 단계({@link com.traveltrace.app.data.exif.ExifExtractor})가 이미 원본에서
 * GPS 를 읽어갔고, 업로드 경로엔 위치가 필요 없다. scoped storage 가 일반 URI 의 위치
 * EXIF 를 이미 가려주므로 재인코딩과 합쳐 방어가 2겹이 된다.
 *
 * <p><b>스트림은 1회만 읽는다</b>(PRD §4.7 해시 시점). 바이트를 메모리에 한 번 받아
 * 그 배열로 해시와 디코드를 모두 해결한다 — 같은 사진을 두 번 읽지 않는다. 배열 하나가
 * 통째로 메모리에 올라오지만 S3 는 사진을 <em>직렬로</em> 1장씩 처리하므로 동시에 살아
 * 있는 원본은 항상 1개다(동시 4건은 S4 소관이며, 그때 이 전제를 다시 봐야 한다).
 */
@Singleton
public class UploadPreparer {

    /** 업로드 장변 상한. 더 키워도 랜드마크 인식률 이득이 작고 업로드 비용만 는다. */
    public static final int MAX_EDGE_PX = 1024;

    /** JPEG 품질. 80 이하로 내리면 간판·표지판 인식이 눈에 띄게 나빠진다. */
    public static final int JPEG_QUALITY = 80;

    private final Context context;

    @Inject
    public UploadPreparer(@ApplicationContext Context context) {
        this.context = context;
    }

    /** 업로드 바이트와 콘텐츠 해시. 해시는 <em>원본</em> 바이트 기준이다. */
    public static final class Prepared {
        public final byte[] jpeg;
        public final String contentHash;

        Prepared(byte[] jpeg, String contentHash) {
            this.jpeg = jpeg;
            this.contentHash = contentHash;
        }
    }

    public Prepared prepare(Uri contentUri) throws IOException {
        byte[] original = readAll(contentUri);
        String hash = sha256Hex(original);

        Bitmap decoded = decodeDownscaled(original);
        if (decoded == null) {
            throw new IOException("이미지를 디코드할 수 없다: " + contentUri);
        }
        Bitmap scaled = scaleToCap(decoded);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            return new Prepared(out.toByteArray(), hash);
        } finally {
            if (scaled != decoded) {
                scaled.recycle();
            }
            decoded.recycle();
        }
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("스트림을 열 수 없다: " + uri);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    /**
     * inSampleSize 로 <em>디코드 단계에서</em> 이미 줄여 받는다. 4000x3000 RAW 를
     * 원본 해상도로 올렸다가 줄이면 48MB 비트맵이 잠깐 뜨고 저사양 기기에서 OOM 이 난다.
     */
    private static Bitmap decodeDownscaled(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    /** 장변이 상한 아래로 내려가지 않는 선까지만 2의 거듭제곱으로 줄인다. */
    static int sampleSizeFor(int width, int height) {
        int longEdge = Math.max(width, height);
        int sample = 1;
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2;
        }
        return sample;
    }

    /** inSampleSize 는 2의 거듭제곱 단위라 남는 오차를 여기서 정확히 맞춘다. */
    private static Bitmap scaleToCap(Bitmap source) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= MAX_EDGE_PX) {
            return source;  // 확대는 하지 않는다.
        }
        float ratio = (float) MAX_EDGE_PX / longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * ratio));
        int height = Math.max(1, Math.round(source.getHeight() * ratio));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 은 모든 안드로이드 런타임이 제공한다.
            throw new IllegalStateException(impossible);
        }
    }
}
