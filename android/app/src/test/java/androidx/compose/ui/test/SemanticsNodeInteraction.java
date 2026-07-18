package androidx.compose.ui.test;

/**
 * 컴파일 전용 스텁 — 런타임에는 절대 로드되지 않는다.
 *
 * <p>Roborazzi 의 captureRoboImage facade({@code RoborazziKt})에는 Compose 오버로드가
 * 섞여 있어, 우리가 {@code captureRoboImage(View, String, RoborazziOptions)} 를 호출해도
 * javac 는 오버로드 해석을 위해 이 Compose 타입을 classpath 에서 찾으려 한다. 이 앱은
 * 의도적으로 Compose·Kotlin 을 쓰지 않으므로 실제 클래스가 없다.
 *
 * <p>Compose 전체를 test 의존성으로 끌어오는 대신, javac 가 View 오버로드를 고를 수 있도록
 * 이 빈 타입만 제공한다. 인자 타입이 {@code android.view.View} 로 명확해 실제 호출은 항상
 * View 오버로드로 가고, 이 클래스는 인스턴스화되지 않는다.
 *
 * <p>훗날 실제 Compose 를 도입하면 이 스텁을 지워야 한다(진짜 클래스와 충돌).
 */
public final class SemanticsNodeInteraction {
    private SemanticsNodeInteraction() {}
}
