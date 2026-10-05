package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.SqlErrors;

/**
 * Keeps the newest element set per object through the consumer pool: reads the object's row under a lock, applies
 * {@link CatalogMerge}, and writes the row back when it changed, in one transaction per element set.
 */
@Component
class MysqlCatalogStore implements CatalogStore {

    private static final String COLUMNS = "norad_cat_id, object_name, object_name_cut, object_id, epoch, epoch_text, "
            + "mean_motion, eccentricity, inclination, ra_of_asc_node, arg_of_pericenter, mean_anomaly, bstar, "
            + "mean_motion_dot, mean_motion_ddot, ephemeris_type, classification_type, element_set_no, rev_at_epoch, "
            + "fetched_at, source_url, first_fetched_at, last_fetched_at";

    static final String HELD_FOR_UPDATE = "SELECT " + COLUMNS + " FROM catalog_object WHERE norad_cat_id = ? "
            + "FOR UPDATE";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    MysqlCatalogStore(@Qualifier("consumerJdbcClient") JdbcClient jdbc,
            @Qualifier("consumerTransactionManager") JdbcTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void apply(CatalogRow received) {
        try {
            tx.executeWithoutResult(status -> {
                CatalogState held = jdbc.sql(HELD_FOR_UPDATE).param(received.noradCatId())
                        .query((rs, n) -> new CatalogState(
                                new CatalogRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                                        rs.getObject(5, LocalDateTime.class), rs.getString(6), rs.getDouble(7),
                                        rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11),
                                        rs.getDouble(12), rs.getDouble(13), rs.getDouble(14), rs.getDouble(15),
                                        rs.getInt(16), rs.getString(17), rs.getInt(18), rs.getLong(19),
                                        rs.getObject(20, LocalDateTime.class), rs.getString(21)),
                                rs.getObject(22, LocalDateTime.class), rs.getObject(23, LocalDateTime.class)))
                        .optional().orElse(null);
                CatalogState next = CatalogMerge.apply(held, received);
                if (next.equals(held)) {
                    return;
                }
                CatalogRow r = next.row();
                Object[] values = {r.objectName(), r.objectNameCut(), r.objectId(), r.epoch(), r.epochText(),
                        r.meanMotion(), r.eccentricity(), r.inclination(), r.raOfAscNode(), r.argOfPericenter(),
                        r.meanAnomaly(), r.bstar(), r.meanMotionDot(), r.meanMotionDdot(), r.ephemerisType(),
                        r.classificationType(), r.elementSetNo(), r.revAtEpoch(), r.fetchedAt(), r.sourceUrl(),
                        next.firstFetchedAt(), next.lastFetchedAt(), r.noradCatId()};
                if (held == null) {
                    jdbc.sql("INSERT INTO catalog_object (object_name, object_name_cut, object_id, epoch, epoch_text, "
                            + "mean_motion, eccentricity, inclination, ra_of_asc_node, arg_of_pericenter, "
                            + "mean_anomaly, bstar, mean_motion_dot, mean_motion_ddot, ephemeris_type, "
                            + "classification_type, element_set_no, rev_at_epoch, fetched_at, source_url, "
                            + "first_fetched_at, last_fetched_at, norad_cat_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").params(values).update();
                } else {
                    jdbc.sql("UPDATE catalog_object SET object_name = ?, object_name_cut = ?, object_id = ?, "
                            + "epoch = ?, epoch_text = ?, mean_motion = ?, eccentricity = ?, inclination = ?, "
                            + "ra_of_asc_node = ?, arg_of_pericenter = ?, mean_anomaly = ?, bstar = ?, "
                            + "mean_motion_dot = ?, mean_motion_ddot = ?, ephemeris_type = ?, "
                            + "classification_type = ?, element_set_no = ?, rev_at_epoch = ?, fetched_at = ?, "
                            + "source_url = ?, first_fetched_at = ?, last_fetched_at = ? WHERE norad_cat_id = ?")
                            .params(values).update();
                }
            });
        } catch (DataAccessException e) {
            NotStorable refused = SqlErrors.refusedValue(e);
            if (refused != null) {
                throw refused;
            }
            throw e;
        }
    }
}
