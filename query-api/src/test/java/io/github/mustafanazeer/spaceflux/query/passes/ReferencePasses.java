package io.github.mustafanazeer.spaceflux.query.passes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.orbit.GpElementSets;
import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads passes/reference-passes.json (passes/PROVENANCE.md) and the recorded element sets it was computed from,
 * which the orbit-core test jar carries under /celestrak.
 */
final class ReferencePasses {

    static final ObjectMapper JSON = new ObjectMapper();

    record Point(String time, double elevationDeg, double azimuthDeg) {

        AbsoluteDate date() {
            return new AbsoluteDate(time.substring(0, time.length() - 1), OrekitData.utc());
        }

        static Point of(JsonNode n) {
            return n == null || n.isNull() ? null
                    : new Point(n.get("time").asString(), n.get("elevation_deg").asDouble(),
                            n.get("azimuth_deg").asDouble());
        }
    }

    record Pass(Point rise, boolean riseClipped, Point startEdge, Point set, boolean setClipped, Point endEdge,
            int peakCount, List<Point> peaks, Point peak, boolean peakAtEdge) {
    }

    record Window(String kind, String cut, String start, String end, double elevationAtStartDeg,
            double elevationAtEndDeg, List<Pass> passes, int ambiguousMaxima) {

        AbsoluteDate startDate() {
            return new AbsoluteDate(start.substring(0, start.length() - 1), OrekitData.utc());
        }
    }

    record ReferenceObject(int noradCatId, String objectName, String epoch, String sourceFile, String sourceSha256,
            List<Window> windows) {

        TrackedObject elementSet() {
            return ReferencePasses.elementSet(sourceFile, noradCatId);
        }
    }

    record File(JsonNode root, List<ReferenceObject> objects) {
    }

    private ReferencePasses() {
    }

    static File load() {
        JsonNode root;
        try (InputStream in = ReferencePasses.class.getResourceAsStream("/passes/reference-passes.json")) {
            root = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<ReferenceObject> objects = new ArrayList<>();
        for (JsonNode o : root.get("objects")) {
            List<Window> windows = new ArrayList<>();
            for (JsonNode w : o.get("windows")) {
                List<Pass> passes = new ArrayList<>();
                for (JsonNode p : w.get("passes")) {
                    List<Point> peaks = new ArrayList<>();
                    p.get("peaks").forEach(x -> peaks.add(Point.of(x)));
                    passes.add(new Pass(Point.of(p.get("rise")), p.get("rise_clipped").asBoolean(),
                            Point.of(p.get("start_edge")), Point.of(p.get("set")), p.get("set_clipped").asBoolean(),
                            Point.of(p.get("end_edge")), p.get("peak_count").asInt(), peaks, Point.of(p.get("peak")),
                            p.get("peak_at_edge").asBoolean()));
                }
                windows.add(new Window(w.get("kind").asString(), w.get("cut").isNull() ? null : w.get("cut").asString(),
                        w.get("start").asString(), w.get("end").asString(), w.get("elevation_at_start_deg").asDouble(),
                        w.get("elevation_at_end_deg").asDouble(), passes,
                        w.get("maxima_within_0_001_deg_of_threshold").size()));
            }
            objects.add(new ReferenceObject(o.get("norad_cat_id").asInt(), o.get("object_name").asString(),
                    o.get("epoch").asString(), o.get("source_file").asString(), o.get("source_sha256").asString(),
                    windows));
        }
        return new File(root, objects);
    }

    static byte[] recorded(String sourceFile) {
        String name = sourceFile.substring(sourceFile.lastIndexOf('/') + 1);
        try (InputStream in = ReferencePasses.class.getResourceAsStream("/celestrak/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no recorded file " + name + " on the test classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The one record with this catalog number in a recorded CelesTrak response, built as the service builds it. */
    static TrackedObject elementSet(String sourceFile, int noradCatId) {
        for (JsonNode gp : JSON.readTree(recorded(sourceFile))) {
            if (gp.get("NORAD_CAT_ID").asInt() == noradCatId) {
                return new TrackedObject(gp.get("OBJECT_NAME").asString(), GpElementSets.toTle(gp));
            }
        }
        throw new IllegalStateException("no record " + noradCatId + " in " + sourceFile);
    }
}
