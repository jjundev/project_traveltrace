package com.traveltrace.app.ui.photo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * 미디어 권한 판정 (PRD §4.1, plan/05).
 *
 * <p>minSdk 33 이므로 READ_MEDIA_IMAGES 는 항상 필요하다. API 34+ 에서 사용자가
 * "선택한 사진만" 을 고르면 READ_MEDIA_IMAGES 는 <em>거부</em>되고
 * READ_MEDIA_VISUAL_USER_SELECTED 만 허용된다 — 이 조합을 PARTIAL 로 읽지 않으면
 * 갤러리가 통째로 비어 보인다.
 *
 * <p>ACCESS_MEDIA_LOCATION 은 판정에 넣지 않는다. 없으면 원본 GPS 를 못 읽을 뿐
 * (plan/06 의 setRequireOriginal 실패 경로) 목록 자체는 정상이다.
 */
public final class MediaPermissionController {

    public enum State {
        /** 전체 갤러리 접근 가능. */
        GRANTED,
        /** 사용자가 고른 사진만 보인다 — "더 선택하기" 유도가 필요하다. */
        PARTIAL,
        /** 아무것도 못 읽는다 — 차단 화면 + 설정 이동 유도. */
        DENIED
    }

    private MediaPermissionController() {}

    private static boolean supportsPartialAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** 요청할 권한 묶음. API 34+ 에서만 VISUAL_USER_SELECTED 를 함께 요청한다. */
    public static String[] requiredPermissions() {
        if (supportsPartialAccess()) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.ACCESS_MEDIA_LOCATION};
        }
        return new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION};
    }

    public static State evaluate(Context context) {
        if (granted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
            return State.GRANTED;
        }
        if (supportsPartialAccess()
                && granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            return State.PARTIAL;
        }
        return State.DENIED;
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
