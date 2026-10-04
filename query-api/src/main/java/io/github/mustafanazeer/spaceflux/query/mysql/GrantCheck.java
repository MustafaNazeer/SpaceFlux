package io.github.mustafanazeer.spaceflux.query.mysql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Refuses a pool whose user holds anything other than the reviewed grants in {@code grants/<pool>.txt}, so a
 * privilege added by hand to the database stops the service instead of widening what it can do.
 */
final class GrantCheck {

    private GrantCheck() {
    }

    static List<String> expected(String pool, String user, String database) {
        String file = "grants/" + pool + ".txt";
        try (InputStream in = GrantCheck.class.getClassLoader().getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("no grant file " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> line.replace("${user}", user).replace("${database}", database))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    static void verify(String pool, DataSource dataSource, List<String> expected) {
        List<String> actual = JdbcClient.create(dataSource).sql("SHOW GRANTS").query(String.class).list();
        compare(pool, expected, actual);
    }

    static void compare(String pool, List<String> expected, List<String> actual) {
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(Set.copyOf(actual));
        if (!unexpected.isEmpty() || !missing.isEmpty()) {
            throw new IllegalStateException("the " + pool + " pool's database grants differ from grants/" + pool
                    + ".txt: unexpected " + unexpected + ", missing " + missing);
        }
    }
}
