package io.github.mustafanazeer.spaceflux.query.passes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.orekit.errors.OrekitException;
import org.orekit.time.AbsoluteDate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.github.mustafanazeer.spaceflux.orbit.GpElementSets;
import io.github.mustafanazeer.spaceflux.orbit.InvalidElementSetException;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /api/watchlist/{catalog_number}/passes} (docs/api/rest.md, section 10): the next 24 hours of geometric
 * passes over the fixed observer, computed on request from the newest stored element set (ADR 0013).
 */
@RestController
class PassesController {

    static final long NORAD_MAX = 999_999_999L;

    static final String NOTE = "Geometric passes: times when this public element set, propagated with SGP4, puts "
            + "the object at or above 10 degrees of elevation from this point, with no atmospheric refraction. They do "
            + "not say whether the object can be seen (sunlight, darkness, weather), they grow less accurate as the "
            + "element set ages, and they are not an operational prediction.";

    static final String WATCHLIST_OBJECT = "SELECT w.catalog_number, w.name, c.norad_cat_id, c.object_id, "
            + "c.epoch_text, c.mean_motion, c.eccentricity, c.inclination, c.ra_of_asc_node, c.arg_of_pericenter, "
            + "c.mean_anomaly, c.bstar, c.mean_motion_dot, c.mean_motion_ddot, c.ephemeris_type, "
            + "c.classification_type, c.element_set_no, c.rev_at_epoch FROM watchlist_object w "
            + "LEFT JOIN catalog_object c ON c.norad_cat_id = w.catalog_number WHERE w.catalog_number = ?";

    private final JdbcClient api;
    private final Clock clock;
    private final PassFinder finder;

    record Observer(String name, String ngsPid, double latitudeDeg, double longitudeDeg, double heightM) {
    }

    record PassPoint(String time, double elevationDeg, double azimuthDeg) {
    }

    record PassView(PassPoint rise, boolean riseClipped, PassPoint startEdge, PassPoint set, boolean setClipped,
            PassPoint endEdge, PassPoint peak, boolean peakAtEdge, int peakCount, double elementAgeDays) {
    }

    /** Fields with no value are left out of the JSON (ADR 0010, amendment). */
    record Answer(long catalogNumber, String name, Observer observer, double elevationMaskDeg, String note,
            String windowStart, String windowEnd, String status, String reason, String epochText, String searchEnd,
            String stopReason, List<PassView> passes) {
    }

    /** One watchlist row and its catalog row's element set as GP JSON, or null when there is no catalog row. */
    private record Stored(long catalogNumber, String name, String epochText, ObjectNode gp) {
    }

    PassesController(@Qualifier("apiJdbcClient") JdbcClient api, Clock clock, PassFinder finder) {
        this.api = api;
        this.clock = clock;
        this.finder = finder;
    }

    @GetMapping("/api/watchlist/{catalog_number}/passes")
    Answer passes(@PathVariable("catalog_number") long catalogNumber) {
        return passes(catalogNumber, clock.instant());
    }

    /** The passes over the window that starts at {@code now} truncated to the second (6.6). */
    Answer passes(long catalogNumber, Instant now) {
        if (catalogNumber < 0 || catalogNumber > NORAD_MAX) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "A catalog number is a whole number from 0 to "
                    + "999999999.");
        }
        Stored stored = api.sql(WATCHLIST_OBJECT).param(catalogNumber).query(PassesController::read).optional()
                .orElseThrow(() -> new ApiErrors.Refused(HttpStatus.NOT_FOUND, "No watchlist object has this "
                        + "number; passes are computed for watchlist objects only."));
        return forObject(stored, now.truncatedTo(ChronoUnit.SECONDS));
    }

    private Answer forObject(Stored stored, Instant now) {
        AbsoluteDate start = new AbsoluteDate(now.toString().substring(0, 19), OrekitData.utc());
        AbsoluteDate end = start.shiftedBy(PassFinder.WINDOW_S);
        if (stored.gp() == null) {
            return refused(stored, start, end, PassStatus.NO_ELEMENT_SET, "the catalog holds no element set for "
                    + "this object; no passes computed");
        }
        TrackedObject object;
        try {
            object = new TrackedObject(stored.name(), GpElementSets.toTle(stored.gp()));
        } catch (InvalidElementSetException | OrekitException e) {
            return refused(stored, start, end, PassStatus.INVALID_ELEMENT_SET, "the stored element set cannot be "
                    + "built into an SGP4 element set: " + e.getMessage() + "; no passes computed");
        }
        return answer(stored.catalogNumber(), stored.name(), stored.epochText(), finder.find(object, start));
    }

    private static Answer refused(Stored stored, AbsoluteDate start, AbsoluteDate end, PassStatus status,
            String reason) {
        return new Answer(stored.catalogNumber(), stored.name(), observer(), PassGrouping.MASK_DEG, NOTE, time(start),
                time(end), code(status), reason, stored.epochText(), null, null, null);
    }

    static Answer answer(long catalogNumber, String name, String epochText, PassFinder.Outcome o) {
        List<PassView> passes = null;
        if (o.passes() != null) {
            passes = new ArrayList<>();
            for (PassGrouping.Pass p : o.passes()) {
                passes.add(new PassView(point(o, p.rise()), p.riseClipped(), point(o, p.startEdge()),
                        point(o, p.set()), p.setClipped(), point(o, p.endEdge()), point(o, p.peak()), p.peakAtEdge(),
                        p.peakCount(), o.elementAgeDaysAt(p.peak().t())));
            }
        }
        return new Answer(catalogNumber, name, observer(), PassGrouping.MASK_DEG, NOTE, time(o.windowStart()),
                time(o.windowEnd()), code(o.status()), o.reason(), epochText,
                o.searchEnd() == null ? null : time(o.searchEnd()), o.stopReason(), passes);
    }

    private static Observer observer() {
        return new Observer(PassFinder.OBSERVER_NAME, PassFinder.OBSERVER_PID, PassFinder.LATITUDE_DEG,
                PassFinder.LONGITUDE_DEG, PassFinder.HEIGHT_M);
    }

    private static PassPoint point(PassFinder.Outcome o, PassGrouping.Point p) {
        return p == null ? null : new PassPoint(time(o.at(p.t())), p.elevationDeg(), p.azimuthDeg());
    }

    /** Milliseconds, as screening writes the times it computes (docs/risk/orbital-conventions.md 6.6). */
    static String time(AbsoluteDate date) {
        return date.toStringWithoutUtcOffset(OrekitData.utc(), 3) + "Z";
    }

    private static String code(PassStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** The element set is rebuilt from the stored columns under the GP field names GpElementSets reads. */
    private static Stored read(ResultSet rs, int n) throws SQLException {
        long catalogNumber = rs.getLong(1);
        String name = rs.getString(2);
        if (rs.getObject(3) == null) {
            return new Stored(catalogNumber, name, null, null);
        }
        ObjectNode gp = JsonNodeFactory.instance.objectNode();
        gp.put("NORAD_CAT_ID", rs.getLong(3));
        gp.put("OBJECT_ID", rs.getString(4));
        gp.put("EPOCH", rs.getString(5));
        gp.put("MEAN_MOTION", rs.getDouble(6));
        gp.put("ECCENTRICITY", rs.getDouble(7));
        gp.put("INCLINATION", rs.getDouble(8));
        gp.put("RA_OF_ASC_NODE", rs.getDouble(9));
        gp.put("ARG_OF_PERICENTER", rs.getDouble(10));
        gp.put("MEAN_ANOMALY", rs.getDouble(11));
        gp.put("BSTAR", rs.getDouble(12));
        gp.put("MEAN_MOTION_DOT", rs.getDouble(13));
        gp.put("MEAN_MOTION_DDOT", rs.getDouble(14));
        gp.put("EPHEMERIS_TYPE", rs.getLong(15));
        gp.put("CLASSIFICATION_TYPE", rs.getString(16));
        gp.put("ELEMENT_SET_NO", rs.getLong(17));
        gp.put("REV_AT_EPOCH", rs.getLong(18));
        return new Stored(catalogNumber, name, rs.getString(5), gp);
    }
}
