package io.github.mustafanazeer.spaceflux.query.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.jdbc.JdbcConnection;
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
import org.testcontainers.containers.GenericContainer;

import io.github.mustafanazeer.spaceflux.query.QueryApiApplication;
import io.github.mustafanazeer.spaceflux.query.TestMysql;

/** Starts the service against the pinned MySQL image after the real migrations have run. */
class MysqlPoolsIntegrationTest {

    static final String CONSUMER = TestMysql.CONSUMER;
    static final String API = TestMysql.API;
    static final String CONSUMER_PASSWORD = TestMysql.CONSUMER_PASSWORD;
    static final String API_PASSWORD = TestMysql.API_PASSWORD;
    static final GenericContainer<?> MYSQL = TestMysql.MYSQL;

    static Path secrets;

    @BeforeAll
    static void startAndMigrate() {
        TestMysql.start();
        secrets = TestMysql.secrets();
    }

    static String password() {
        return TestMysql.password();
    }

    static void rootSql(String sql) throws Exception {
        TestMysql.rootSql(sql);
    }

    static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run(TestMysql.args("--spaceflux.alerts.enabled=false"));
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
    void theAlertsConsumerIsOnByDefaultAndOffWhenTurnedOff() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE).run(TestMysql.args())) {
            assertThat(context.containsBean("alertsListener")).isTrue();
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE).run(TestMysql.args("--SPACEFLUX_ALERTS_ENABLED=false"))) {
            assertThat(context.containsBean("alertsListener")).isFalse();
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
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306), "--spaceflux.alerts.enabled=false"))
                .satisfies(e -> assertThat(rootCause(e))
                        .hasMessage("no secret file mysql_consumer_password holds the password for "
                                + "spaceflux_consumer"));
        assertThat(output.getAll()).doesNotContain(API_PASSWORD);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aPasswordGivenAsAFlagStopsTheStartWithoutPrintingIt(CapturedOutput output) {
        String flagged = password();

        assertThatThrownBy(() -> new SpringApplicationBuilder(QueryApiApplication.class)
                .web(WebApplicationType.NONE)
                .run("--QUERY_API_SECRETS=" + secrets + "/", "--MYSQL_HOST=" + MYSQL.getHost(),
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306), "--mysql_api_password=" + flagged,
                        "--spaceflux.alerts.enabled=false"))
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
                        "--MYSQL_PORT=" + MYSQL.getMappedPort(3306), "--CONSUMER_USER=spaceflux'consumer",
                        "--spaceflux.alerts.enabled=false"))
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
                    assertThat(settings.getIntegerProperty(PropertyKey.socketTimeout).getValue()).isEqualTo(30_000);
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
