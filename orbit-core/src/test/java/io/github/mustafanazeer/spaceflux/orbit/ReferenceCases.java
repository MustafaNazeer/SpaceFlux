package io.github.mustafanazeer.spaceflux.orbit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads the AIAA 2006-6753 verification files (see src/test/resources/sgp4/PROVENANCE.md). */
public final class ReferenceCases {

    public record Row(double minutes, double[] positionKm, double[] velocityKmPerS) {
    }

    public record Case(String line1, String line2, List<Row> rows) {
        public int catalogNumber() {
            return Integer.parseInt(line1.substring(2, 7).trim());
        }
    }

    private ReferenceCases() {
    }

    public static List<String> lines(String resource) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                ReferenceCases.class.getResourceAsStream(resource), StandardCharsets.US_ASCII))) {
            return in.lines().map(String::stripTrailing).toList();
        }
    }

    /** Element sets in file order, with the three trailing span fields removed from line 2. */
    static List<String[]> elementSets() throws IOException {
        List<String[]> sets = new ArrayList<>();
        String line1 = null;
        for (String line : lines("/sgp4/SGP4-VER.TLE")) {
            if (line.startsWith("1 ")) {
                line1 = line;
            } else if (line.startsWith("2 ")) {
                sets.add(new String[] {line1, line.substring(0, 69)});
            }
        }
        return sets;
    }

    /** Ephemeris blocks in file order; each block starts at a line ending in "xx". */
    static List<List<Row>> results(String resource) throws IOException {
        List<List<Row>> blocks = new ArrayList<>();
        for (String line : lines(resource)) {
            if (line.endsWith("xx")) {
                blocks.add(new ArrayList<>());
            } else if (!line.isBlank()) {
                String[] t = line.trim().split("\\s+");
                blocks.getLast().add(new Row(Double.parseDouble(t[0]),
                        new double[] {Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3])},
                        new double[] {Double.parseDouble(t[4]), Double.parseDouble(t[5]), Double.parseDouble(t[6])}));
            }
        }
        return blocks;
    }

    public static List<Case> published() throws IOException {
        List<String[]> sets = elementSets();
        List<List<Row>> blocks = results("/sgp4/tforverf.out");
        List<Integer> blockCatalogs = lines("/sgp4/tforverf.out").stream()
                .filter(l -> l.endsWith("xx")).map(l -> Integer.parseInt(l.trim().split("\\s+")[0])).toList();
        if (sets.size() != blocks.size()) {
            throw new IllegalStateException(sets.size() + " element sets but " + blocks.size() + " result blocks");
        }
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            int headerCatalog = blockCatalogs.get(i);
            int setCatalog = Integer.parseInt(sets.get(i)[0].substring(2, 7).trim());
            if (headerCatalog != setCatalog) {
                throw new IllegalStateException("result block " + i + " is " + headerCatalog + ", element set is " + setCatalog);
            }
            cases.add(new Case(sets.get(i)[0], sets.get(i)[1], blocks.get(i)));
        }
        return cases;
    }

    /**
     * The same element set as CelesTrak publishes it in GP JSON: text fields converted to numbers
     * exactly, with the epoch day fraction written out in full.
     */
    public static ObjectNode toGpJson(String line1, String line2) {
        ObjectNode gp = new ObjectMapper().createObjectNode();
        gp.put("NORAD_CAT_ID", Integer.parseInt(line1.substring(2, 7).trim()));
        gp.put("CLASSIFICATION_TYPE", line1.substring(7, 8));
        String designator = line1.substring(9, 17).trim();
        if (!designator.isEmpty()) {
            int yy = Integer.parseInt(designator.substring(0, 2));
            gp.put("OBJECT_ID", (yy < 57 ? 2000 + yy : 1900 + yy) + "-" + designator.substring(2, 5)
                    + designator.substring(5));
        }
        gp.put("EPOCH", epoch(line1.substring(18, 32)));
        gp.put("MEAN_MOTION_DOT", Double.parseDouble(line1.substring(33, 43).trim()));
        gp.put("MEAN_MOTION_DDOT", impliedDecimal(line1.substring(44, 52)));
        gp.put("BSTAR", impliedDecimal(line1.substring(53, 61)));
        gp.put("EPHEMERIS_TYPE", line1.substring(62, 63).isBlank() ? 0 : Integer.parseInt(line1.substring(62, 63)));
        gp.put("ELEMENT_SET_NO", Integer.parseInt(line1.substring(64, 68).trim()));
        gp.put("INCLINATION", Double.parseDouble(line2.substring(8, 16).trim()));
        gp.put("RA_OF_ASC_NODE", Double.parseDouble(line2.substring(17, 25).trim()));
        gp.put("ECCENTRICITY", Double.parseDouble("0." + line2.substring(26, 33).trim()));
        gp.put("ARG_OF_PERICENTER", Double.parseDouble(line2.substring(34, 42).trim()));
        gp.put("MEAN_ANOMALY", Double.parseDouble(line2.substring(43, 51).trim()));
        gp.put("MEAN_MOTION", Double.parseDouble(line2.substring(52, 63).trim()));
        gp.put("REV_AT_EPOCH", Integer.parseInt(line2.substring(63, 68).trim()));
        return gp;
    }

    private static String epoch(String field) {
        int yy = Integer.parseInt(field.substring(0, 2));
        BigDecimal day = new BigDecimal(field.substring(2).trim());
        int dayOfYear = day.intValue();
        BigDecimal seconds = day.subtract(BigDecimal.valueOf(dayOfYear)).multiply(BigDecimal.valueOf(86400));
        int wholeSeconds = seconds.intValue();
        String fraction = seconds.subtract(BigDecimal.valueOf(wholeSeconds)).toPlainString();
        LocalDateTime start = LocalDate.ofYearDay(yy < 57 ? 2000 + yy : 1900 + yy, dayOfYear).atStartOfDay()
                .plusSeconds(wholeSeconds);
        return start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                + (fraction.startsWith("0.") ? fraction.substring(1) : "");
    }

    private static double impliedDecimal(String field) {
        String f = field.trim();
        int exponentAt = Math.max(f.lastIndexOf('-'), f.lastIndexOf('+'));
        String mantissa = exponentAt > 0 ? f.substring(0, exponentAt) : f;
        String exponent = exponentAt > 0 ? f.substring(exponentAt) : "0";
        String sign = mantissa.startsWith("-") ? "-" : "";
        String digits = mantissa.replaceFirst("^[-+]", "");
        return Double.parseDouble(sign + "0." + digits + "e" + exponent);
    }
}
