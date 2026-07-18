package com.traveltrace.app.ui.map;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.DashPathEffect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.traveltrace.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 리플레이 타임라인 스크러버 (프로토타입 하단 시트의 도트+연결선).
 *
 * <p>레이아웃 규칙은 프로토타입과 동일하다: 정차점 N개가 가로를 N등분하고, 각 슬롯은
 * [연결선 + 도트] 순서다. 지나온 구간(index &lt;= active)은 브랜드 색, 남은 구간은 회색.
 * 활성 도트만 커지고, AI 정차점의 미방문 도트는 점선 테두리를 쓴다.
 */
public class TimelineScrubberView extends View {

    public interface OnStopSelectedListener {
        void onStopSelected(int index);
    }

    public static final int NO_SELECTION = -1;

    private final List<MapUiState.Stop> stops = new ArrayList<>();
    private int activeIndex = 0;
    @Nullable private OnStopSelectedListener listener;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int colorBrand;
    private final int colorTrack;
    private final int colorSurface;
    private final int colorApprox;
    private final float lineHeight;
    private final float dotRadius;
    private final float dotRadiusActive;

    public TimelineScrubberView(Context context) {
        this(context, null);
    }

    public TimelineScrubberView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        colorBrand = ContextCompat.getColor(context, R.color.fill_brand);
        colorTrack = ContextCompat.getColor(context, R.color.border_default);
        colorSurface = ContextCompat.getColor(context, R.color.surface);
        colorApprox = ContextCompat.getColor(context, R.color.location_approx);

        lineHeight = res(R.dimen.scrubber_line_height);
        dotRadius = res(R.dimen.scrubber_dot) / 2f;
        dotRadiusActive = res(R.dimen.scrubber_dot_active) / 2f;

        linePaint.setStyle(Paint.Style.FILL);
        dotFillPaint.setStyle(Paint.Style.FILL);
        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(res(R.dimen.scrubber_dot_stroke));
    }

    private float res(int dimenRes) {
        return getResources().getDimensionPixelSize(dimenRes);
    }

    public void setStops(List<MapUiState.Stop> next) {
        stops.clear();
        stops.addAll(next);
        setActiveIndex(activeIndex);
        invalidate();
    }

    public void setActiveIndex(int index) {
        int max = Math.max(0, stops.size() - 1);
        activeIndex = Math.min(Math.max(index, 0), max);
        invalidate();
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setOnStopSelectedListener(@Nullable OnStopSelectedListener l) {
        listener = l;
    }

    /** x 좌표가 속한 정차점 인덱스. 정차점이 없거나 뷰 폭이 0이면 NO_SELECTION. */
    public int indexAt(float x) {
        if (stops.isEmpty()) return NO_SELECTION;
        float slot = getWidth() / (float) stops.size();
        if (slot <= 0f) return NO_SELECTION;
        int index = (int) (x / slot);
        return Math.min(Math.max(index, 0), stops.size() - 1);
    }

    /** 탭 판정을 테스트에서 직접 호출할 수 있게 분리 (터치 이벤트 합성 불필요). */
    public void performTapAt(float x) {
        int index = indexAt(x);
        if (index == NO_SELECTION) return;
        setActiveIndex(index);
        if (listener != null) listener.onStopSelected(index);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            performTapAt(event.getX());
            performClick();
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (stops.isEmpty()) return;

        float slot = getWidth() / (float) stops.size();
        float cy = getHeight() / 2f;

        for (int i = 0; i < stops.size(); i++) {
            boolean reached = i <= activeIndex;
            boolean active = i == activeIndex;

            float slotStart = slot * i;
            float dotCx = slotStart + slot - dotRadiusActive;

            // 연결선: 슬롯 시작 ~ 도트 앞
            linePaint.setColor(reached ? colorBrand : colorTrack);
            canvas.drawRoundRect(slotStart, cy - lineHeight / 2f, dotCx, cy + lineHeight / 2f,
                    lineHeight / 2f, lineHeight / 2f, linePaint);

            float r = active ? dotRadiusActive : dotRadius;
            if (reached) {
                dotFillPaint.setColor(colorBrand);
                canvas.drawCircle(dotCx, cy, r, dotFillPaint);
                dotStrokePaint.setColor(colorSurface);
                dotStrokePaint.setPathEffect(null);
            } else {
                dotFillPaint.setColor(colorSurface);
                canvas.drawCircle(dotCx, cy, r, dotFillPaint);
                dotStrokePaint.setColor(stops.get(i).ai ? colorApprox : colorTrack);
                // AI 근사 정차점은 점선 테두리 (프로토타입 dotBorder: 2px dashed).
                dotStrokePaint.setPathEffect(stops.get(i).ai
                        ? new DashPathEffect(new float[]{4f, 3f}, 0f)
                        : null);
            }
            canvas.drawCircle(dotCx, cy, r, dotStrokePaint);
        }
    }
}
