package io.github.mustafanazeer.spaceflux.query.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.github.dockerjava.api.model.Capability;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.jdbc.JdbcConnection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;

/** Starts the service against the pinned MySQL image after the real migrations have run. */
class MysqlPoolsIntegrationTest {

    static final String IMAGE =
            "mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242";
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final String MIGRATE = "spaceflux_migrate";
    static final String CONSUMER = "spaceflux_consumer";
    static final String API = "spaceflux_api";

    static final String MIGRATE_PASSWORD = password();
    static final String CONSUMER_PASSWORD = password();
    static final String API_PASSWORD = password();

    // Started as Compose starts it, with the same server settings and account script.
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
            .withCopyToContainer(Transferable.of(password(), 0444), "/run/secrets/mysql_root_password")
            .withCopyToContainer(Transferable.of(MIGRATE_PASSWORD, 0444), "/run/secrets/mysql_migrate_password")
            .withCopyToContainer(Transferable.of(CONSUMER_PASSWORD, 0444), "/run/secrets/mysql_consumer_password")
            .withCopyToContainer(Transferable.of(API_PASSWORD, 0444), "/run/secrets/mysql_api_password")
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @TempDir
    static Path secrets;

    @BeforeAll
    static void startAndMigrate() throws Exception {
        MYSQL.start();
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/spaceflux"
                + "?sslMode=REQUIRED&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true";
        Flyway.configure()
                .dataSource(url, MIGRATE, MIGRATE_PASSWORD)
                .locations("filesystem:" + REPO.resolve("db-migrate/src/main/resources/db/migration"))
                .placeholders(Map.of("consumer_user", CONSUMER, "api_user", API))
                .cleanDisabled(true)
                .load()
                .migrate();
        // Compose mounts each secret as a file named after it; the service reads them as a config tree.
        Files.writeString(secrets.resolve("mysql_consumer_password"), CONSUMER_PASSWORD, StandardCharsets.US_ASCII);
        Files.writeString(secrets.resolve("mysql_api_password"), API_PASSWORD, StandardCharsets.US_ASCII);
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

    /** Runs SQL as root over the socket inside the container; the password never reaches a command line. */
    static void rootSql(String sql) throws Exception {
        String quoted = "'" + sql.replace("'", "'\\''") + "'";
        ExecResult result = MYSQL.execInContainer("bash", "-c",
                "mysql --defaults-extra-file=<(printf '[client]\\npassword=%s\\n' \"$(< /run/secrets/mysql_root_password)\")"
                        + " -uroot -N -B -e " + quoted);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run("--QUERY_API_SECRETS=" + secrets + "/",
                        "--MYSQL_HOST=" + MYSQL.getHost(),
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306));
    }

    static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void eachPoolConnectsAsItsOwnUserOverTlsWithAUtcSession() {
        try (ConfigurableApplicationContext context = start()) {
            for (Map.Entry<String, String> pool : Map.of("consumerJdbcClient", CONSUMER, "apiJdbcClient", API)
                    .entrySet()) {
                JdbcClient jdbc = context.getBean(pool.getKey(), JdbcClient.class);
                assertThat(jdbc.sql("SELECT CURRENT_USER()").query(String.class).single())
                        .isEqualTo(pool.getValue() + "@%");
                assertThat(jdbc.sql("SHOW SESSION STATUS LIKE 'Ssl_cipher'").query((rs, n) -> rs.getString(2))
                        .single()).isNotBlank();
                assertThat(jdbc.sql("SELECT @@session.time_zone").query(String.class).single()).isEqualTo("+00:00");
            }
        }
    }

    @Test
    void noPoolCanBeInjectedWithoutNamingIt() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getBeansOfType(DataSource.class).keySet())
                    .containsExactlyInAnyOrder("consumerDataSource", "apiDataSource");
            assertThat(context.getBeansOfType(PlatformTransactionManager.class).keySet())
                    .containsExactlyInAnyOrder("consumerTransactionManager", "apiTransactionManager");
            assertThat(context.getBeanProvider(DataSource.class).getIfUnique()).isNull();
            assertThat(context.getBeanProvider(JdbcClient.class).getIfUnique()).isNull();
        }
    }

    @Test
    void anExtraPrivilegeOnTheApiUserStopsTheStart() throws Exception {
        rootSql("GRANT UPDATE ON spaceflux.alert_acknowledgement TO 'spaceflux_api'@'%'");
        try {
            assertThatThrownBy(MysqlPoolsIntegrationTest::start)
                    .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("api pool")
                            .hasMessageContaining("UPDATE ON `spaceflux`.`alert_acknowledgement`"));
        } finally {
            rootSql("REVOKE UPDATE ON spaceflux.alert_acknowledgement FROM 'spaceflux_api'@'%'");
        }
    }

    @Test
    void anExtraPrivilegeOnTheConsumerUserStopsTheStart() throws Exception {
        rootSql("GRANT DELETE ON spaceflux.catalog_object TO 'spaceflux_consumer'@'%'");
        try {
            assertThatThrownBy(MysqlPoolsIntegrationTest::start)
                    .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("consumer pool")
                            .hasMessageContaining("DELETE ON `spaceflux`.`catalog_object`"));
        } finally {
            rootSql("REVOKE DELETE ON spaceflux.catalog_object FROM 'spaceflux_consumer'@'%'");
        }
    }

    @Test
    void aMissingPrivilegeStopsTheStart() throws Exception {
        rootSql("REVOKE SELECT ON spaceflux.watchlist_object FROM 'spaceflux_api'@'%'");
        try {
            assertThatThrownBy(MysqlPoolsIntegrationTest::start)
                    .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("api pool")
                            .hasMessageContaining("missing [GRANT SELECT ON `spaceflux`.`watchlist_object`"));
        } finally {
            rootSql("GRANT SELECT ON spaceflux.watchlist_object TO 'spaceflux_api'@'%'");
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aMissingPasswordFileStopsTheStartWithoutPrintingTheOtherPassword(CapturedOutput output,
            @TempDir Path partial) throws Exception {
        Files.writeString(partial.resolve("mysql_api_password"), API_PASSWORD, StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run("--QUERY_API_SECRETS=" + partial + "/", "--MYSQL_HOST=" + MYSQL.getHost(),
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306)))
                .satisfies(e -> assertThat(rootCause(e))
                        .hasMessage("no secret file mysql_consumer_password holds the password for spaceflux_consumer"));
        assertThat(output.getAll()).doesNotContain(API_PASSWORD);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aPasswordGivenAsAFlagStopsTheStartWithoutPrintingIt(CapturedOutput output) {
        String flagged = password();

        assertThatThrownBy(() -> new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run("--QUERY_API_SECRETS=" + secrets + "/", "--MYSQL_HOST=" + MYSQL.getHost(),
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306), "--mysql_api_password=" + flagged))
                .satisfies(e -> assertThat(rootCause(e))
                        .hasMessageContaining("mysql_api_password is set by commandLineArgs"));
        assertThat(output.getAll()).doesNotContain(flagged).doesNotContain(API_PASSWORD)
                .doesNotContain(CONSUMER_PASSWORD);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aBadUserNameStopsTheStartWithoutPrintingAnyPassword(CapturedOutput output) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run("--QUERY_API_SECRETS=" + secrets + "/", "--MYSQL_HOST=" + MYSQL.getHost(),
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306), "--CONSUMER_USER=spaceflux'consumer"))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("username"));
        assertThat(output.getAll()).doesNotContain(API_PASSWORD).doesNotContain(CONSUMER_PASSWORD);
    }

    @Test
    void aRoleGrantedToTheApiUserStopsTheStart() throws Exception {
        rootSql("CREATE ROLE 'spaceflux_extra'");
        rootSql("GRANT DELETE ON spaceflux.alert_event TO 'spaceflux_extra'");
        rootSql("GRANT 'spaceflux_extra' TO 'spaceflux_api'@'%'");
        rootSql("SET DEFAULT ROLE ALL TO 'spaceflux_api'@'%'");
        try {
            assertThatThrownBy(MysqlPoolsIntegrationTest::start)
                    .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("api pool")
                            .hasMessageContaining("spaceflux_extra"));
        } finally {
            rootSql("SET DEFAULT ROLE NONE TO 'spaceflux_api'@'%'");
            rootSql("DROP ROLE 'spaceflux_extra'");
        }
    }

    @Test
    void aHikariConfigurationFileStopsTheStart(@TempDir Path dir) throws Exception {
        // HikariCP's no argument config loads this file, and its dataSource entries could override the URL.
        Path file = Files.writeString(dir.resolve("hikari.properties"), "dataSource.allowLoadLocalInfile=true\n");
        System.setProperty("hikaricp.configurationFile", file.toString());
        try {
            assertThatThrownBy(MysqlPoolsIntegrationTest::start)
                    .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("hikaricp.configurationFile"));
        } finally {
            System.clearProperty("hikaricp.configurationFile");
        }
    }

    @Test
    void theDriverUsesTheSafeSettingsOnEveryPool() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            for (String pool : List.of("consumerDataSource", "apiDataSource")) {
                try (Connection connection = context.getBean(pool, DataSource.class).getConnection()) {
                    PropertySet settings = connection.unwrap(JdbcConnection.class).getPropertySet();
                    assertThat(settings.getEnumProperty(PropertyKey.sslMode).getValue()).hasToString("REQUIRED");
                    assertThat(settings.getBooleanProperty(PropertyKey.allowLoadLocalInfile).getValue()).isFalse();
                    assertThat(settings.getBooleanProperty(PropertyKey.allowUrlInLocalInfile).getValue()).isFalse();
                    assertThat(settings.getBooleanProperty(PropertyKey.allowPublicKeyRetrieval).getValue()).isFalse();
                }
            }
        }
    }

    @Test
    void theRealGrantFilesMatchWhatTheMigrationsGrant() {
        // Kept separate so a drift between V14 and grants/*.txt names the file, not just a failed start.
        for (String[] pool : List.of(new String[] {"consumer", CONSUMER, CONSUMER_PASSWORD},
                new String[] {"api", API, API_PASSWORD})) {
            String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/spaceflux"
                    + "?sslMode=REQUIRED";
            var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, pool[1], pool[2]);
            GrantCheck.verify(pool[0], ds, GrantCheck.expected(pool[0], pool[1], "spaceflux"));
        }
    }
}
