package com.traveltrace.app.data.media;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 캐시 키의 콘텐츠 해시 (PRD §4.7, plan/04 "(_ID + 콘텐츠 해시)").
 *
 * <p><b>앞 64KiB + 크기</b>를 해시한다(전체 바이트가 아니다). 그래야 "해시 → 캐시 조회 →
 * miss 일 때만 EXIF 파싱" 순서가 가능하다 — 전체 바이트 해시는 EXIF 파싱과 같은 스트림에서
 * 계산해야 중복 I/O 를 피하므로 캐시 조회가 파싱 뒤로 밀려 hit 이어도 파싱을 못 건너뛴다.
 * JPEG/HEIC 는 EXIF(촬영 시각·서브초·썸네일)가 앞부분에 몰려 있어, 크기까지 같으면서 앞
 * 64KiB 가 동일한 서로 다른 사진은 실질적으로 존재하지 않는다.
 *
 * <p><b>{@link android.provider.MediaStore#setRequireOriginal} 을 쓰지 않는다.</b> 그 URI 가
 * 주는 스트림은 ACCESS_MEDIA_LOCATION 승인 여부에 따라 원본이 되기도 위치를 지운 파생본이
 * 되기도 해서, 권한 상태가 바뀌면 캐시 키가 통째로 바뀐다. 평범한 URI 는 항상 같은 파생본을
 * 주므로 키가 안정적이다 — EXIF 판독(ExifExtractor)만 원본 경로를 쓴다.
 *
 * <p>키는 기기 로컬 한정이다: 짝인 {@code mediaStoreId} 가 기기 내에서만 안정적이므로
 * 기기 변경·초기화 후에는 캐시가 통째로 miss 된다(정상, README 에 명시).
 */
@Singleton
public class ContentHasher {

    private static final String TAG = "ContentHasher";

    /** 해시에 넣을 파일 앞부분 크기. */
    public static final int PREFIX_BYTES = 64 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Context context;

    @Inject
    public ContentHasher(@ApplicationContext Context context) {
        this.context = context;
    }

    /**
     * @param sizeBytes MediaStore 가 아는 파일 크기. 커서에서 이미 읽어 오므로 파일을 한 번 더
     *                  열 필요가 없다. 모르면 0 을 넘겨도 되지만 그만큼 충돌 여지가 커진다.
     * @return 소문자 hex 64자, 또는 읽지 못했을 때 null(호출부는 캐시를 건너뛴다).
     */
    @Nullable
    @WorkerThread
    public String hash(Uri uri, long sizeBytes) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int remaining = PREFIX_BYTES;
            long consumed = 0;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                digest.update(buffer, 0, read);
                remaining -= read;
                consumed += read;
            }

            // 프리픽스 길이와 전체 크기를 섞는다 — 앞부분이 같고 뒤쪽만 다른 파일이
            // 서로의 분석 결과를 재사용하는 것을 크기가 막는다.
            digest.update(longBytes(consumed));
            digest.update(longBytes(sizeBytes));
            return hex(digest.digest());
        } catch (IOException | RuntimeException unreadable) {
            // 사진이 지워졌거나 권한이 빠진 흔한 경우다 — 던지면 배치 전체가 멈춘다.
            Log.w(TAG, "hash failed for " + uri, unreadable);
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 은 모든 JVM 이 제공한다", impossible);
        }
    }

    private static byte[] longBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
