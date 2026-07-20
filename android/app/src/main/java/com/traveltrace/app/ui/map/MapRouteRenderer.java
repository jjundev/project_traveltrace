package com.traveltrace.app.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Dash;
import com.google.android.gms.maps.model.Gap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import com.traveltrace.app.R;

import java.util.Arrays;
import java.util.List;

/**
 * 스톱 목록 → 지도 위 마커·경로·카메라 (PRD §4.4).
 *
 * <p>GPS 핀은 실선·{@code pin_gps}, AI 근사 위치는 점선·{@code pin_approx} 로 그린다.
 * 구간은 양 끝 중 하나라도 AI 면 점선이다 — 어디를 지나갔는지 모르는 구간을 실선으로
 * 그리면 없는 정확도를 주장하게 된다(PRD §4.4).
 *
 * <p>좌표 없는 분류는 여기 오기 전에 걸러진다 — PLACED 만 Stop 이 되므로 (0,0) 핀이
 * 생길 여지가 없다.
 */
public final class MapRouteRenderer {

    /** 스톱이 1곳뿐이면 bounds 대신 이 줌으로 맞춘다. */
    public static final float SINGLE_STOP_ZOOM = 15f;

    private MapRouteRenderer() {}

    /**
     * 전체 스톱을 담는 bounds. 스톱이 0·1개이거나 좌표가 전부 같으면 null 을 돌려준다
     * — span 이 0인 bounds 는 과도 줌/예외를 낸다(고전적 함정).
     */
    @Nullable
    public static LatLngBounds boundsOf(List<MapUiState.Stop> stops) {
        if (stops.size() < 2) return null;

        LatLngBounds.Builder builder = LatLngBounds.builder();
        for (MapUiState.Stop stop : stops) {
            builder.include(new LatLng(stop.lat, stop.lng));
        }
        LatLngBounds bounds = builder.build();
        boolean degenerate = bounds.southwest.latitude == bounds.northeast.latitude
                && bounds.southwest.longitude == bounds.northeast.longitude;
        return degenerate ? null : bounds;
    }

    /** bounds 가 없으면 첫 스톱을 고정 줌으로 잡는다. 스톱이 없으면 null. */
    @Nullable
    public static CameraUpdate cameraFor(List<MapUiState.Stop> stops, int paddingPx) {
        LatLngBounds bounds = boundsOf(stops);
        if (bounds != null) {
            return CameraUpdateFactory.newLatLngBounds(bounds, paddingPx);
        }
        if (stops.isEmpty()) return null;
        MapUiState.Stop only = stops.get(0);
        return CameraUpdateFactory.newLatLngZoom(
                new LatLng(only.lat, only.lng), SINGLE_STOP_ZOOM);
    }

    /** 점선 대시·간격 길이(px). 실선과 확실히 구분되되 산만하지 않은 크기. */
    private static final float DASH_PX = 18f;
    private static final float GAP_PX = 12f;

    /** 기존 마커·경로를 지우고 다시 그린다. */
    public static void draw(GoogleMap map, List<MapUiState.Stop> stops, Context context) {
        map.clear();
        if (stops.isEmpty()) return;

        // 두 아이콘을 루프 밖에서 1회만 굽는다 — 사진마다 비트맵을 새로 만들면
        // 100장짜리 여행에서 눈에 띄게 버벅인다.
        BitmapDescriptor gpsPin = pinIcon(context, R.drawable.pin_gps);
        BitmapDescriptor approxPin = pinIcon(context, R.drawable.pin_approx);

        for (MapUiState.Stop stop : stops) {
            map.addMarker(new MarkerOptions()
                    .position(new LatLng(stop.lat, stop.lng))
                    .title(stop.name)
                    .icon(stop.ai ? approxPin : gpsPin));
        }
        drawRoute(map, stops, context);
    }

    /**
     * AI 근사 위치가 끼면 그 구간을 점선으로 그린다 (PRD §4.4 "근사 위치 시각 구분").
     *
     * <p>연속된 같은 종류의 구간을 하나의 폴리라인으로 묶는다 — 구간마다 폴리라인을
     * 만들면 100장 여행에서 오버레이가 99개 생긴다.
     */
    private static void drawRoute(GoogleMap map, List<MapUiState.Stop> stops, Context context) {
        boolean[] dashed = dashedSegments(stops);
        if (dashed.length == 0) return;   // 스톱이 1곳이면 그릴 선이 없다.

        float width = context.getResources().getDimension(R.dimen.map_route_width);
        int color = ContextCompat.getColor(context, R.color.fill_brand);

        int i = 0;
        while (i < dashed.length) {
            boolean isDashed = dashed[i];
            PolylineOptions segment = new PolylineOptions().width(width).color(color);
            segment.add(new LatLng(stops.get(i).lat, stops.get(i).lng));

            int j = i;
            while (j < dashed.length && dashed[j] == isDashed) {
                segment.add(new LatLng(stops.get(j + 1).lat, stops.get(j + 1).lng));
                j++;
            }
            if (isDashed) {
                segment.pattern(Arrays.asList(new Dash(DASH_PX), new Gap(GAP_PX)));
            }
            map.addPolyline(segment);
            i = j;
        }
    }

    /**
     * 구간별 점선 여부. 양 끝 중 하나라도 AI 근사 위치면 그 구간은 추정 경로다 —
     * 실제로 어디를 지나갔는지 모르는 구간을 실선으로 그리면 없는 정확도를 주장하게 된다.
     *
     * <p>SDK 타입을 쓰지 않아 단위 테스트가 가능하다({@code sameRoute} 와 같은 이유).
     */
    public static boolean[] dashedSegments(List<MapUiState.Stop> stops) {
        if (stops == null || stops.size() < 2) {
            return new boolean[0];
        }
        boolean[] dashed = new boolean[stops.size() - 1];
        for (int i = 0; i < dashed.length; i++) {
            dashed[i] = stops.get(i).ai || stops.get(i + 1).ai;
        }
        return dashed;
    }

    /** GPS 핀과 AI 근사 위치 핀은 시각적으로 달라야 한다(PRD §4.4). */
    public static int pinResFor(boolean ai) {
        return ai ? R.drawable.pin_approx : R.drawable.pin_gps;
    }

    /** 벡터 드로어블은 BitmapDescriptorFactory 가 직접 못 읽어 비트맵으로 굽는다. */
    private static BitmapDescriptor pinIcon(Context context, int drawableRes) {
        Drawable drawable = ContextCompat.getDrawable(context, drawableRes);
        if (drawable == null) {
            return BitmapDescriptorFactory.defaultMarker();
        }
        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }
}
