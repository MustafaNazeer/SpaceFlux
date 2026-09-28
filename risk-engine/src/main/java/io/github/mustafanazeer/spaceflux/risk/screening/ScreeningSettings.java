package io.github.mustafanazeer.spaceflux.risk.screening;

/** Values ratified in docs/risk/orbital-conventions.md Sections 2.4 and 3. */
public final class ScreeningSettings {

    public static final double REPORT_DISTANCE_M = 5_000;
    public static final double WINDOW_S = 7 * 86_400;
    public static final double DEEP_SPACE_PERIOD_MIN = 225;
    public static final double SAMPLE_STEP_S = 10;
    public static final double MAX_CHECK_S = 60;
    public static final double CONVERGENCE_THRESHOLD_S = 1e-6;
    public static final int MAX_ITERATIONS = 100;
    public static final double CO_ORBITING_BOUND_M = 500_000;
    public static final double MAX_ELEMENT_AGE_S = 10 * 86_400;

    private ScreeningSettings() {
    }
}
