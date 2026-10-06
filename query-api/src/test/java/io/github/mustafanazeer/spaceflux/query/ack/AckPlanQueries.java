package io.github.mustafanazeer.spaceflux.query.ack;

/** The acknowledgement endpoint's SQL, exactly as it runs it, for the plan test. */
public final class AckPlanQueries {

    public static final String TARGET = AcknowledgementController.TARGET;
    public static final String CURRENT = AcknowledgementController.CURRENT;
    public static final String WRITTEN = AcknowledgementController.WRITTEN;
    public static final String EXISTS = AcknowledgementController.EXISTS;
    public static final String HISTORY = AcknowledgementController.HISTORY;
    public static final String HISTORY_AFTER = AcknowledgementController.HISTORY_AFTER;

    private AckPlanQueries() {
    }
}
