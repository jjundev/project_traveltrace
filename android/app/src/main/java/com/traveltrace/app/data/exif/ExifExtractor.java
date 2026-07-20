package com.traveltrace.app.data.exif;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.io.IOException;
import java.io.InputStream;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 사진 1장의 원본 EXIF 에서 GPS·촬영 시각을 읽는다 (PRD §4.2).
 *
 * <p>scoped storage 는 기본적으로 위치 EXIF 를 가리므로 ACCESS_MEDIA_LOCATION +
 * {@link MediaStore#setRequireOriginal(Uri)} 로 <em>원본</em> 스트림을 열어야 한다.
 * 그 경로가 실패하면 일반 스트림으로 폴백하되 반드시 카운트한다 — 조용히 누락되면
 * S3 에서 GPS 있는 사진까지 전부 AI 로 흘러 비용이 폭증한다(plan/06 리스크).
 *
 * <p>S1 은 AI 를 쓰지 않으므로 GPS 가 없으면 그대로 UNKNOWN 으로 끝난다. AI 경로는 S3.
 */
@Singleton
public class ExifExtractor {

    private static final String TAG = "ExifExtractor";

    private final Context context;
    private final TimeZone deviceZone;
    private final AtomicInteger originalAccessFailures = new AtomicInteger();

    @Inject
    public ExifExtractor(@ApplicationContext Context context) {
        this(context, TimeZone.getDefault());
    }

    /** 테스트가 기기 타임존을 고정할 수 있게 하는 생성자. */
    public ExifExtractor(Context context, TimeZone deviceZone) {
        this.context = context;
        this.deviceZone = deviceZone;
    }

    /** 원본 접근에 실패해 GPS 를 못 읽었을 수 있는 사진 수. 완료 요약에 노출한다. */
    public int originalAccessFailures() {
        return originalAccessFailures.get();
    }

    public void resetFailureCount() {
        originalAccessFailures.set(0);
    }

    public PhotoAnalysis extract(GalleryImage image) {
        PhotoAnalysis result = new PhotoAnalysis();
        result.mediaStoreId = image.id;
        result.displayName = image.displayName;
        result.source = LocationSource.NONE;
        result.classification = LocationClassification.UNKNOWN;

        ExifInterface exif = openExif(image.contentUri);
        if (exif == null) {
            return result;
        }

        double[] latLong = exif.getLatLong();
        TimeNormalizer.Result time = TimeNormalizer.toUtcMillis(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                deviceZone);

        // EXIF 에 촬영 시각이 없으면(브라우저 다운로드·메신저로 받은 사진에 흔하다)
        // MediaStore 가 아는 시각(DATE_TAKEN, 없으면 DATE_ADDED)으로 폴백한다. 근사 시각이라도
        // 있어야 경로에 참여할 수 있다 — 없으면 AI 가 장소를 알아내도 NO_TIME 으로 분류돼
        // 지도에서 통째로 사라진다. 폴백은 오프셋 정보가 없으므로 hasOffset=false 로 둔다.
        Long takenUtc = time.utcMillis != null ? time.utcMillis : image.dateTakenUtc;
        result.takenAtUtc = takenUtc;
        result.takenAtHasOffset = time.hasOffset;

        if (latLong == null) {
            // GPS 없음 → S1 은 여기서 끝. S3 이 이 사진들을 AI 로 보낸다.
            return result;
        }

        result.lat = latLong[0];
        result.lng = latLong[1];
        result.source = LocationSource.GPS;
        // 좌표는 있는데 시각이 아예 없으면(폴백조차 없으면) 경로 순서에 넣을 수 없다(PRD §4.6).
        result.classification = takenUtc == null
                ? LocationClassification.NO_TIME
                : LocationClassification.PLACED;
        return result;
    }

    /**
     * 원본 스트림으로 ExifInterface 를 연다. setRequireOriginal 이 막히면
     * (권한 미승인·제공자 미지원) 일반 스트림으로 폴백하고 실패를 센다.
     */
    private ExifInterface openExif(Uri uri) {
        Uri original = uri;
        boolean requestedOriginal = true;
        try {
            original = MediaStore.setRequireOriginal(uri);
        } catch (UnsupportedOperationException | SecurityException notOriginal) {
            requestedOriginal = false;
            originalAccessFailures.incrementAndGet();
            Log.w(TAG, "setRequireOriginal unavailable for " + uri, notOriginal);
        }

        try (InputStream in = context.getContentResolver().openInputStream(original)) {
            if (in == null) {
                originalAccessFailures.incrementAndGet();
                return null;
            }
            return new ExifInterface(in);
        } catch (SecurityException denied) {
            // 원본 요청이 거부됐다 — 일반 스트림으로 한 번 더 시도한다(GPS 는 못 읽는다).
            if (requestedOriginal) {
                originalAccessFailures.incrementAndGet();
                return openPlain(uri);
            }
            return null;
        } catch (IOException unreadable) {
            originalAccessFailures.incrementAndGet();
            Log.w(TAG, "unreadable photo " + uri, unreadable);
            return null;
        }
    }

    private ExifInterface openPlain(Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in == null ? null : new ExifInterface(in);
        } catch (IOException | SecurityException failed) {
            Log.w(TAG, "plain stream also failed for " + uri, failed);
            return null;
        }
    }
}
