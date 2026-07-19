package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

@RunWith(RobolectricTestRunner.class)
public class MediaPermissionControllerTest {

    private Application app;
    private ShadowApplication shadowApp;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        shadowApp = Shadows.shadowOf(app);
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void fullGrantIsGranted() {
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);

        assertEquals(MediaPermissionController.State.GRANTED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void userSelectedOnlyIsPartialNotDenied() {
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        shadowApp.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);

        assertEquals("선택한 사진만 허용은 '거부'가 아니라 '부분'이다",
                MediaPermissionController.State.PARTIAL,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void nothingGrantedIsDenied() {
        shadowApp.denyPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertEquals(MediaPermissionController.State.DENIED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void onApi33ThereIsNoPartialState() {
        shadowApp.denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertEquals("API 33 엔 부분 접근이 없다 — READ_MEDIA_VISUAL_USER_SELECTED 가 실제로 "
                        + "허용돼 있어도 SDK 게이트가 이를 무시하고 거부로 판정해야 한다",
                MediaPermissionController.State.DENIED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void api34RequestsBothImagesAndUserSelected() {
        assertArrayEquals(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                        Manifest.permission.ACCESS_MEDIA_LOCATION},
                MediaPermissionController.requiredPermissions());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void api33DoesNotRequestUserSelected() {
        assertArrayEquals(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.ACCESS_MEDIA_LOCATION},
                MediaPermissionController.requiredPermissions());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void fullGrantWinsOverPartialWhenBothArePresent() {
        shadowApp.grantPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        assertEquals("READ_MEDIA_IMAGES 와 READ_MEDIA_VISUAL_USER_SELECTED 가 모두 허용돼 있으면 "
                        + "'부분'이 아니라 '전체 허용'이어야 한다",
                MediaPermissionController.State.GRANTED,
                MediaPermissionController.evaluate(app));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void mediaLocationIsNotPartOfTheReadVerdict() {
        // ACCESS_MEDIA_LOCATION 이 없어도 목록은 보여야 한다 — 없으면 GPS 만 못 읽는다.
        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);
        shadowApp.denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);

        assertEquals(MediaPermissionController.State.GRANTED,
                MediaPermissionController.evaluate(app));
        assertEquals(PackageManager.PERMISSION_DENIED,
                app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION));
    }
}
