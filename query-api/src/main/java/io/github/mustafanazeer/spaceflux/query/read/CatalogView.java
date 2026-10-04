package io.github.mustafanazeer.spaceflux.query.read;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** One catalog_object row as the API returns it (docs/api/rest.md, section 5). */
record CatalogView(Long noradCatId, String objectName, Boolean objectNameCut, String objectId, String epoch,
        String epochText, Double meanMotion, Double eccentricity, Double inclination, Double raOfAscNode,
        Double argOfPericenter, Double meanAnomaly, Double bstar, Double meanMotionDot, Double meanMotionDdot,
        Integer ephemerisType, String classificationType, Integer elementSetNo, Long revAtEpoch, String fetchedAt,
        String sourceUrl, String firstFetchedAt, String lastFetchedAt) {

    static final String COLUMNS = "c.norad_cat_id, c.object_name, c.object_name_cut, c.object_id, c.epoch, "
            + "c.epoch_text, c.mean_motion, c.eccentricity, c.inclination, c.ra_of_asc_node, c.arg_of_pericenter, "
            + "c.mean_anomaly, c.bstar, c.mean_motion_dot, c.mean_motion_ddot, c.ephemeris_type, "
            + "c.classification_type, c.element_set_no, c.rev_at_epoch, c.fetched_at, c.source_url, "
            + "c.first_fetched_at, c.last_fetched_at";

    /** Reads the {@link #COLUMNS} starting at column {@code from}; null when the row has no catalog object. */
    static CatalogView read(ResultSet rs, int from) throws SQLException {
        if (rs.getObject(from) == null) {
            return null;
        }
        int i = from;
        return new CatalogView(rs.getLong(i++), rs.getString(i++), rs.getBoolean(i++), rs.getString(i++),
                ApiTimes.format(rs.getObject(i++, LocalDateTime.class)), rs.getString(i++), rs.getDouble(i++),
                rs.getDouble(i++), rs.getDouble(i++), rs.getDouble(i++), rs.getDouble(i++), rs.getDouble(i++),
                rs.getDouble(i++), rs.getDouble(i++), rs.getDouble(i++), rs.getInt(i++), rs.getString(i++),
                rs.getInt(i++), rs.getLong(i++), ApiTimes.format(rs.getObject(i++, LocalDateTime.class)),
                rs.getString(i++), ApiTimes.format(rs.getObject(i++, LocalDateTime.class)),
                ApiTimes.format(rs.getObject(i, LocalDateTime.class)));
    }
}
