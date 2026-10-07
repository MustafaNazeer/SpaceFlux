package io.github.mustafanazeer.spaceflux.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.github.dockerjava.api.model.Capability;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Runs the real account script, server settings and migrations against the pinned MySQL image. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationIntegrationTest {

    static final String IMAGE =
            "mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242";
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final String MIGRATE = "spaceflux_migrate";
    static final String CONSUMER = "spaceflux_consumer";
    static final String API = "spaceflux_api";

    static final String ROOT_PASSWORD = password();
    static final String MIGRATE_PASSWORD = password();
    static final String CONSUMER_PASSWORD = password();
    static final String API_PASSWORD = password();

    // Started as Compose starts it: the image's mysql user, no capabilities, root on the socket only.
    static final GenericContainer<?> MYSQL = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(3306)
            .withCreateContainerCmdModifier(cmd -> {
                cmd.withUser("999:999");
                cmd.getHostConfig().withCapDrop(Capability.ALL);
            })
            .withEnv("MYSQL_ROOT_PASSWORD_FILE", "/run/secrets/mysql_root_password")
            .withEnv("MYSQL_ROOT_HOST", "localhost")
            .withEnv("MYSQL_INITDB_SKIP_TZINFO", "1")
            .withEnv("MYSQL_DATABASE", "spaceflux")
            .withEnv("MYSQL_MIGRATE_USER", MIGRATE)
            .withEnv("MYSQL_CONSUMER_USER", CONSUMER)
            .withEnv("MYSQL_API_USER", API)
            .withCopyFileToContainer(MountableFile.forHostPath(REPO.resolve("deploy/mysql/conf.d/spaceflux.cnf"), 0644),
                    "/etc/mysql/conf.d/spaceflux.cnf")
            .withCopyFileToContainer(MountableFile.forHostPath(REPO.resolve("deploy/mysql/initdb/10-users.sh"), 0644),
                    "/docker-entrypoint-initdb.d/10-users.sh")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(ROOT_PASSWORD, 0444),
                    "/run/secrets/mysql_root_password")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(MIGRATE_PASSWORD, 0444),
                    "/run/secrets/mysql_migrate_password")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(CONSUMER_PASSWORD, 0444),
                    "/run/secrets/mysql_consumer_password")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(API_PASSWORD, 0444),
                    "/run/secrets/mysql_api_password")
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @BeforeAll
    static void start() {
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    static String password() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    static Migrate.Settings settings() {
        return new Migrate.Settings(MYSQL.getHost(), MYSQL.getMappedPort(3306), "spaceflux", "REQUIRED", MIGRATE,
                MIGRATE_PASSWORD, CONSUMER, API);
    }

    static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(settings().jdbcUrl(), user, password);
    }

    /** Runs SQL as root over the socket inside the container; the password never reaches a command line. */
    static List<String> rootSql(String sql) throws Exception {
        String quoted = "'" + sql.replace("'", "'\\''") + "'";
        ExecResult result = MYSQL.execInContainer("bash", "-c",
                "mysql --defaults-extra-file=<(printf '[client]\\npassword=%s\\n' \"$(< /run/secrets/mysql_root_password)\")"
                        + " -uroot -N -B --raw -e " + quoted);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        String out = result.getStdout().strip();
        return out.isEmpty() ? List.of() : List.of(out.split("\n"));
    }

    static Set<String> grants(String user) throws Exception {
        return new HashSet<>(rootSql("SHOW GRANTS FOR '" + user + "'@'%'"));
    }

    @Test
    @Order(1)
    void everyMigrationAppliesAsTheMigrationUserAndASecondRunAppliesNothing() {
        MigrateResult first = Migrate.run(settings());
        MigrateResult second = Migrate.run(settings());

        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(15);
        assertThat(first.targetSchemaVersion).isEqualTo("15");
        assertThat(second.migrationsExecuted).isZero();
    }

    @Test
    @Order(2)
    void theMigrationUserHoldsExactlyTheReviewedPrivileges() throws Exception {
        assertThat(grants(MIGRATE)).containsExactlyInAnyOrder(
                "GRANT USAGE ON *.* TO `spaceflux_migrate`@`%`",
                "GRANT SELECT, INSERT, UPDATE, CREATE, DROP, REFERENCES, INDEX, ALTER ON `spaceflux`.* TO "
                        + "`spaceflux_migrate`@`%` WITH GRANT OPTION",
                "GRANT DELETE, CREATE ON `spaceflux`.`flyway_schema_history` TO `spaceflux_migrate`@`%`");
    }

    @Test
    @Order(2)
    void theConsumerUserCanOnlyReadAndAppendEventsAndUpdateTheTwoProjections() throws Exception {
        List<String> expected = new ArrayList<>();
        expected.add("GRANT USAGE ON *.* TO `spaceflux_consumer`@`%`");
        expected.add("GRANT SELECT, INSERT (`event_id`, `kind`, `payload`, `produced_at`, `rules_version`, "
                + "`schema_version`, `source_offset`, `source_partition`) ON `spaceflux`.`alert_event` TO "
                + "`spaceflux_consumer`@`%`");
        for (String table : List.of("space_weather_event", "close_approach", "screening_run",
                "screening_run_approach", "screening_run_suppressed", "screening_run_rejected",
                "screening_run_not_screened")) {
            expected.add("GRANT SELECT, INSERT ON `spaceflux`.`" + table + "` TO `spaceflux_consumer`@`%`");
        }
        for (String table : List.of("space_weather_series", "catalog_object")) {
            expected.add("GRANT SELECT, INSERT, UPDATE ON `spaceflux`.`" + table + "` TO `spaceflux_consumer`@`%`");
        }

        assertThat(grants(CONSUMER)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @Order(2)
    void theApiUserReadsEveryTableAndOnlyAppendsAcknowledgementsByFourColumns() throws Exception {
        List<String> expected = new ArrayList<>();
        expected.add("GRANT USAGE ON *.* TO `spaceflux_api`@`%`");
        for (String table : List.of("alert_event", "space_weather_event", "space_weather_series", "close_approach",
                "screening_run", "screening_run_approach", "screening_run_suppressed", "screening_run_rejected",
                "screening_run_not_screened", "catalog_object", "watchlist_object")) {
            expected.add("GRANT SELECT ON `spaceflux`.`" + table + "` TO `spaceflux_api`@`%`");
        }
        expected.add("GRANT SELECT, INSERT (`action`, `event_id`, `note`, `principal`) ON "
                + "`spaceflux`.`alert_acknowledgement` TO `spaceflux_api`@`%`");

        assertThat(grants(API)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @Order(2)
    void onlyTheThreeServiceAccountsAreReachableOverTheNetworkAndEachRequiresTls() throws Exception {
        assertThat(rootSql("SELECT user, host, plugin, ssl_type FROM mysql.user WHERE host <> 'localhost' "
                + "ORDER BY user")).containsExactly(
                "spaceflux_api\t%\tcaching_sha2_password\tANY",
                "spaceflux_consumer\t%\tcaching_sha2_password\tANY",
                "spaceflux_migrate\t%\tcaching_sha2_password\tANY");

        assertThatThrownBy(() -> connect("root", ROOT_PASSWORD).close())
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(1045));
    }

    @Test
    @Order(2)
    void theServerRefusesAPlainConnectionAndHasNoNativePasswordPlugin() throws Exception {
        String plain = settings().jdbcUrl().replace("sslMode=REQUIRED", "sslMode=DISABLED")
                .replace("allowPublicKeyRetrieval=false", "allowPublicKeyRetrieval=true");
        // The same credentials succeed over TLS, so the refusal can only come from the missing TLS. Observed here, not
        // stated in the manual: the account's REQUIRE SSL refuses it during authentication (1045), before
        // require_secure_transport is consulted.
        connect(API, API_PASSWORD).close();
        assertThatThrownBy(() -> DriverManager.getConnection(plain, API, API_PASSWORD).close())
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(1045));

        assertThat(rootSql("SELECT @@global.require_secure_transport")).containsExactly("1");
        assertThat(rootSql("SELECT plugin_name FROM information_schema.plugins WHERE plugin_status = 'ACTIVE' "
                + "AND plugin_name IN ('mysql_native_password', 'mysqlx')")).isEmpty();
    }

    @Test
    @Order(2)
    void theServerRunsWithTheCommittedSettings() throws Exception {
        assertThat(rootSql("SELECT @@global.time_zone, @@global.innodb_buffer_pool_size, "
                + "@@global.max_connections, @@global.log_bin")).containsExactly("+00:00\t134217728\t32\t0");
        assertThat(rootSql("SELECT @@global.innodb_log_buffer_size, @@global.temptable_max_ram, "
                + "@@global.performance_schema_digests_size, "
                + "@@global.performance_schema_events_statements_history_long_size"))
                .containsExactly("16777216\t67108864\t1000\t1000");
    }

    @Test
    @Order(2)
    void theDriverPutsTheSessionInUtcEvenWhenTheServerDefaultIsNot() throws Exception {
        rootSql("SET GLOBAL time_zone = '+05:00'");
        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement();
                ResultSet zone = s.executeQuery("SELECT @@session.time_zone")) {
            zone.next();
            assertThat(zone.getString(1)).isEqualTo("+00:00");
        } finally {
            rootSql("SET GLOBAL time_zone = '+00:00'");
        }
    }

    @Test
    @Order(3)
    void theApiUserInsertsAnAcknowledgementThatTheDatabaseTimestampsInUtc() throws Exception {
        insertAlert("close_approach/1/test-ack");

        Instant before = Instant.now().minusSeconds(5);
        try (Connection c = connect(API, API_PASSWORD)) {
            try (Statement s = c.createStatement(); ResultSet zone = s.executeQuery("SELECT @@session.time_zone")) {
                zone.next();
                assertThat(zone.getString(1)).isEqualTo("+00:00");
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO alert_acknowledgement "
                    + "(event_id, action, principal, note) VALUES (?, 'acknowledge', 'operator', ?)")) {
                p.setString(1, "close_approach/1/test-ack");
                p.setString(2, "seen");
                assertThat(p.executeUpdate()).isEqualTo(1);
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT acted_at FROM alert_acknowledgement WHERE event_id = 'close_approach/1/test-ack'")) {
                r.next();
                Instant actedAt = r.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC);
                assertThat(actedAt).isBetween(before, Instant.now().plusSeconds(5));
            }
        }
    }

    @Test
    @Order(3)
    void theApiUserCannotSetTheIdOrTimeOrChangeOrRemoveARow() throws Exception {
        insertAlert("close_approach/1/test-locked");
        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO alert_acknowledgement (event_id, action, principal) "
                    + "VALUES ('close_approach/1/test-locked', 'acknowledge', 'operator')");

            assertRefused(s, "INSERT INTO alert_acknowledgement (event_id, action, principal, acted_at) "
                    + "VALUES ('close_approach/1/test-locked', 'acknowledge', 'operator', '2026-01-01')", 1143);
            assertRefused(s, "INSERT INTO alert_acknowledgement (ack_id, event_id, action, principal) "
                    + "VALUES (999, 'close_approach/1/test-locked', 'acknowledge', 'operator')", 1143);
            assertRefused(s, "UPDATE alert_acknowledgement SET note = 'changed'", 1142);
            assertRefused(s, "DELETE FROM alert_acknowledgement", 1142);
            assertRefused(s, "INSERT INTO alert_event (event_id, kind, schema_version, rules_version, produced_at, "
                    + "source_partition, source_offset, payload) VALUES ('x', 'close_approach', 1, 1, NOW(6), 0, 0, '{}')",
                    1142);
            assertRefused(s, "SELECT * FROM flyway_schema_history", 1142);
        }
    }

    @Test
    @Order(3)
    void anAcknowledgementOfAnUnknownAlertOrAnUnknownActionIsRefused() throws Exception {
        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement()) {
            assertRefused(s, "INSERT INTO alert_acknowledgement (event_id, action, principal) "
                    + "VALUES ('close_approach/1/never-stored', 'acknowledge', 'operator')", 1452);
        }
        insertAlert("close_approach/1/test-action");
        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement()) {
            assertRefused(s, "INSERT INTO alert_acknowledgement (event_id, action, principal) "
                    + "VALUES ('close_approach/1/test-action', 'withdraw', 'operator')", 3819);
        }
    }

    @Test
    @Order(3)
    void theConsumerUserCannotReadOrWriteAcknowledgementsOrDeleteEvents() throws Exception {
        try (Connection c = connect(CONSUMER, CONSUMER_PASSWORD); Statement s = c.createStatement()) {
            assertRefused(s, "SELECT * FROM alert_acknowledgement", 1142);
            assertRefused(s, "INSERT INTO alert_event (event_id, kind, schema_version, rules_version, produced_at, "
                    + "received_at, source_partition, source_offset, payload) "
                    + "VALUES ('y', 'close_approach', 1, 1, NOW(6), NOW(6), 0, 0, '{}')", 1143);
            assertRefused(s, "DELETE FROM alert_event", 1142);
            assertRefused(s, "UPDATE alert_event SET payload = '{}'", 1142);
            assertRefused(s, "SELECT * FROM watchlist_object", 1142);
        }
    }

    @Test
    @Order(3)
    void theDatabaseMarksTheSpaceWeatherEventsTheAlertListShowsAndTheConsumerCannotSetTheMark() throws Exception {
        // state, previous_state, trigger, listed: entering, changing and leaving a level, restatements and revisions
        // included; never a refresh, and never a change between none and no data alone.
        String[][] cases = {
            {"level", "none", "level_change", "1"},
            {"level", "level", "level_change", "1"},
            {"none", "level", "level_change", "1"},
            {"no_data", "level", "level_change", "1"},
            {"ended", "level", "level_change", "1"},
            {"level", null, "level_change", "1"},
            {"none", "level", "revision", "1"},
            {"level", "none", "revision", "1"},
            {"no_data", "level", "restatement", "1"},
            {"level", null, "refresh", "0"},
            {"none", null, "refresh", "0"},
            {"no_data", "none", "level_change", "0"},
            {"none", "no_data", "level_change", "0"},
            {"none", null, "level_change", "0"},
            {"none", "none", "revision", "0"},
            {"no_data", "none", "restatement", "0"},
        };
        for (int i = 0; i < cases.length; i++) {
            String id = "space_weather_level/1/listed-" + i;
            insertAlert(id);
            try (Connection c = connect(CONSUMER, CONSUMER_PASSWORD); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO space_weather_event (alert_seq, rules_version, scale, product, state, derived_label, "
                            + "previous_state, trigger_kind, derived_from, estimated, unit) SELECT alert_seq, 1, 'G', "
                            + "'swpc.kp', ?, 'x', ?, ?, 'test', TRUE, 'Kp index' FROM alert_event WHERE event_id = ?")) {
                p.setString(1, cases[i][0]);
                p.setString(2, cases[i][1]);
                p.setString(3, cases[i][2]);
                p.setString(4, id);
                assertThat(p.executeUpdate()).isEqualTo(1);
            }
            try (Connection c = connect(API, API_PASSWORD); PreparedStatement p = c.prepareStatement(
                    "SELECT w.listed FROM space_weather_event w JOIN alert_event e ON e.alert_seq = w.alert_seq "
                            + "WHERE e.event_id = ?")) {
                p.setString(1, id);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    assertThat(r.getString(1)).as(String.join(" ", Stream.of(cases[i]).map(String::valueOf)
                            .toList())).isEqualTo(cases[i][3]);
                }
            }
        }
        insertAlert("space_weather_level/1/listed-set");
        try (Connection c = connect(CONSUMER, CONSUMER_PASSWORD); Statement s = c.createStatement()) {
            assertRefused(s, "INSERT INTO space_weather_event (alert_seq, rules_version, scale, product, state, "
                    + "derived_label, trigger_kind, derived_from, estimated, unit, listed) SELECT alert_seq, 1, 'G', "
                    + "'swpc.kp', 'none', 'none', 'refresh', 'test', TRUE, 'Kp index', 1 FROM alert_event "
                    + "WHERE event_id = 'space_weather_level/1/listed-set'", 3105);
        }
    }

    @Test
    @Order(3)
    void identitiesThatDifferOnlyByTrailingSpaceOrCaseAreDistinct() throws Exception {
        insertAlert("close_approach/1/case");
        insertAlert("close_approach/1/CASE");
        insertAlert("close_approach/1/case ");

        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement(); ResultSet r =
                s.executeQuery("SELECT COUNT(*) FROM alert_event WHERE event_id = 'close_approach/1/case'")) {
            r.next();
            assertThat(r.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    void everyNumberInTheCommittedAlertExamplesRoundTripsThroughDouble() throws Exception {
        List<Double> values = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        try (Stream<Path> files = Files.list(REPO.resolve("schemas/alerts/examples"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                collectFloats(json.readTree(file.toFile()), values);
            }
        }
        assertThat(values).contains(1.0624149581417441e-05);

        // Every DOUBLE column of catalog_object gets the value, so each column type is checked the same way.
        String insert = "INSERT INTO catalog_object (norad_cat_id, object_name_cut, epoch, epoch_text, mean_motion, "
                + "eccentricity, inclination, ra_of_asc_node, arg_of_pericenter, mean_anomaly, bstar, mean_motion_dot, "
                + "mean_motion_ddot, ephemeris_type, classification_type, element_set_no, rev_at_epoch, fetched_at, "
                + "source_url, first_fetched_at, last_fetched_at) VALUES (?, FALSE, '2026-10-03', 'probe', "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'U', 0, 0, '2026-10-03', 'probe', '2026-10-03', '2026-10-03')";
        try (Connection c = connect(CONSUMER, CONSUMER_PASSWORD); PreparedStatement p = c.prepareStatement(insert)) {
            for (int i = 0; i < values.size(); i++) {
                p.setInt(1, 900_000_000 + i);
                for (int column = 2; column <= 10; column++) {
                    p.setDouble(column, values.get(i));
                }
                p.executeUpdate();
            }
        }
        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement(); ResultSet r =
                s.executeQuery("SELECT norad_cat_id, mean_motion, eccentricity, inclination, ra_of_asc_node, "
                        + "arg_of_pericenter, mean_anomaly, bstar, mean_motion_dot, mean_motion_ddot "
                        + "FROM catalog_object WHERE norad_cat_id >= 900000000 ORDER BY norad_cat_id")) {
            int rows = 0;
            while (r.next()) {
                long expected = Double.doubleToRawLongBits(values.get(r.getInt(1) - 900_000_000));
                for (int column = 2; column <= 10; column++) {
                    assertThat(Double.doubleToRawLongBits(r.getDouble(column))).isEqualTo(expected);
                }
                rows++;
            }
            assertThat(rows).isEqualTo(values.size());
        }
    }

    @Test
    @Order(3)
    void theSeededWatchlistIsTheRiskEnginesWatchlist() throws Exception {
        Matcher version = Pattern.compile("public static final int RULES_VERSION = (\\d+);").matcher(Files.readString(
                REPO.resolve("risk-engine/src/main/java/io/github/mustafanazeer/spaceflux/risk/kafka/SwpcProcessor.java")));
        assertThat(version.find()).as("RULES_VERSION in SwpcProcessor.java").isTrue();
        int rulesVersion = Integer.parseInt(version.group(1));

        JsonNode file = new ObjectMapper().readTree(
                REPO.resolve("risk-engine/src/main/resources/screening/watchlist.json").toFile());
        Set<String> expected = new HashSet<>();
        for (JsonNode object : file.get("objects")) {
            expected.add(object.get("catalog_number").asLong() + " " + object.get("name").asString());
        }

        Set<String> seeded = new HashSet<>();
        try (Connection c = connect(API, API_PASSWORD); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT catalog_number, name, rules_version FROM watchlist_object")) {
            while (r.next()) {
                seeded.add(r.getLong(1) + " " + r.getString(2));
                assertThat(r.getInt(3)).isEqualTo(rulesVersion);
            }
        }
        assertThat(seeded).isEqualTo(expected);
    }

    private static void collectFloats(JsonNode node, List<Double> out) {
        if (node.isFloatingPointNumber()) {
            out.add(node.doubleValue());
        }
        for (JsonNode child : node) {
            collectFloats(child, out);
        }
    }

    private static void insertAlert(String eventId) throws SQLException {
        try (Connection c = connect(CONSUMER, CONSUMER_PASSWORD); PreparedStatement p = c.prepareStatement(
                "INSERT INTO alert_event (event_id, kind, schema_version, rules_version, produced_at, "
                        + "source_partition, source_offset, payload) VALUES (?, 'close_approach', 1, 1, ?, 0, 0, '{}')")) {
            p.setString(1, eventId);
            p.setObject(2, LocalDateTime.of(2026, 10, 3, 0, 0));
            p.executeUpdate();
        }
    }

    private static void assertRefused(Statement s, String sql, int errorCode) {
        assertThatThrownBy(() -> s.execute(sql))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(errorCode));
    }
}
