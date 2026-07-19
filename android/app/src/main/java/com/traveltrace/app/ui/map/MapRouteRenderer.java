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
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import com.traveltrace.app.R;

import java.util.List;

/**
 * 스톱 목록 → 지도 위 마커·경로·카메라 (PRD §4.4).
 *
 * <p>S1 은 GPS 핀만 그린다. AI 근사 위치의 점선·반투명 구분은 AI 핀이 생기는 S3 부터
 * 의미가 있다(plan/12).
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

    /** 기존 마커·경로를 지우고 다시 그린다. */
    public static void draw(GoogleMap map, List<MapUiState.Stop> stops, Context context) {
        map.clear();
        if (stops.isEmpty()) return;

        PolylineOptions route = new PolylineOptions()
                .width(context.getResources().getDimension(R.dimen.map_route_width))
                .color(ContextCompat.getColor(context, R.color.fill_brand));

        BitmapDescriptor pin = pinIcon(context);
        for (MapUiState.Stop stop : stops) {
            LatLng position = new LatLng(stop.lat, stop.lng);
            route.add(position);
            map.addMarker(new MarkerOptions()
                    .position(position)
                    .title(stop.name)
                    .icon(pin));
        }
        // 스톱이 1곳이면 선을 그릴 게 없다.
        if (stops.size() > 1) {
            map.addPolyline(route);
        }
    }

    /** 벡터 드로어블은 BitmapDescriptorFactory 가 직접 못 읽어 비트맵으로 굽는다. */
    private static BitmapDescriptor pinIcon(Context context) {
        Drawable drawable = ContextCompat.getDrawable(context, R.drawable.pin_gps);
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
