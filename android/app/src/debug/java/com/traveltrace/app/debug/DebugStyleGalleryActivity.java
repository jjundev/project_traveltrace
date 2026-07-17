package com.traveltrace.app.debug;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.traveltrace.app.R;

/**
 * DEBUG 전용: 디자인 토큰/스타일/공용 컴포넌트를 프로토타입과 육안 대조하는 갤러리.
 * 릴리스 빌드에 포함되지 않는다(debug 소스셋). 실행:
 *   adb shell am start -n com.traveltrace.app/com.traveltrace.app.debug.DebugStyleGalleryActivity
 */
public class DebugStyleGalleryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dev_style_gallery);
    }
}
