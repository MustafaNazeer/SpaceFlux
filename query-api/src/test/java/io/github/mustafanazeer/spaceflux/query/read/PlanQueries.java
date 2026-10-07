package io.github.mustafanazeer.spaceflux.query.read;

/** The read endpoints' SQL, exactly as they run it, for the plan test. */
public final class PlanQueries {

    public static final String SPACE_WEATHER_CURRENT = SpaceWeatherController.CURRENT;
    public static final String ALERT_BY_ID = AlertController.BY_ID;
    public static final String LATEST_ACKNOWLEDGEMENT = Acknowledgement.LATEST;
    public static final String CATALOG_BY_NUMBER = CatalogController.BY_NUMBER;
    public static final String WATCHLIST = WatchlistController.WATCHLIST;
    public static final String RUN_CANDIDATES = ScreeningController.CANDIDATES;
    public static final String RUN_CANDIDATES_AFTER = ScreeningController.CANDIDATES_AFTER;
    public static final String RUN_LISTED_MISSING = ScreeningController.LISTED_MISSING;
    public static final String RUN_CUT_COUNT = ScreeningController.CUT_COUNT;
    public static final String RUN_SUMMARY = ScreeningController.SUMMARY;
    public static final String RUN_LISTED_APPROACHES = ScreeningController.LISTED_APPROACHES;
    public static final String RUN_CUT_APPROACHES = ScreeningController.CUT_APPROACHES;
    public static final String ALERTS_RECENT = AlertLists.RECENT;
    public static final String ALERTS_RECENT_AFTER = AlertLists.RECENT_AFTER;
    public static final String OBJECT_APPROACHES = AlertLists.OBJECT_APPROACHES;
    public static final String OBJECT_APPROACHES_AFTER = AlertLists.OBJECT_APPROACHES_AFTER;

    private PlanQueries() {
    }

    public static String history(boolean g, boolean cursor) {
        return SpaceWeatherHistoryController.sql(g, cursor);
    }
}
