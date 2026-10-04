package io.github.mustafanazeer.spaceflux.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

import com.github.dockerjava.api.model.Capability;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One MySQL container for every test class in the run, started as Compose starts it (same server settings and account
 * script) and migrated with the real migrations from db-migrate. The container stops when the test JVM exits.
 */
public final class TestMysql {

    static final String IMAGE =
            "mysql:8.4.11@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242";
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    public static final String MIGRATE = "spaceflux_migrate";
    public static final String CONSUMER = "spaceflux_consumer";
    public static final String API = "spaceflux_api";

    public static final String MIGRATE_PASSWORD = password();
    public static final String CONSUMER_PASSWORD = password();
    public static final String API_PASSWORD = password();

    public static final GenericContainer<?> MYSQL = new GenericContainer<>(DockerImageName.parse(IMAGE))
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

    private static Path secrets;

    private TestMysql() {
    }

    /** Starts and migrates the container once, and writes the two service passwords as Compose mounts them. */
    public static synchronized void start() {
        if (secrets != null) {
            return;
        }
        MYSQL.start();
        Flyway.configure()
                .dataSource(url(), MIGRATE, MIGRATE_PASSWORD)
                .locations("filesystem:" + REPO.resolve("db-migrate/src/main/resources/db/migration"))
                .placeholders(Map.of("consumer_user", CONSUMER, "api_user", API))
                .cleanDisabled(true)
                .load()
                .migrate();
        try {
            Path dir = Files.createTempDirectory("query-api-secrets");
            Files.writeString(dir.resolve("mysql_consumer_password"), CONSUMER_PASSWORD, StandardCharsets.US_ASCII);
            Files.writeString(dir.resolve("mysql_api_password"), API_PASSWORD, StandardCharsets.US_ASCII);
            for (String file : new String[] {"mysql_consumer_password", "mysql_api_password", ""}) {
                dir.resolve(file).toFile().deleteOnExit();
            }
            secrets = dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path secrets() {
        return secrets;
    }

    public static String url() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/spaceflux"
                + "?sslMode=REQUIRED&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true";
    }

    /** The arguments that point the service at this database and its secret files. */
    public static String[] args(String... more) {
        String[] base = {"--QUERY_API_SECRETS=" + secrets + "/", "--MYSQL_HOST=" + MYSQL.getHost(),
                "--MYSQL_PORT=" + MYSQL.getMappedPort(3306)};
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    public static String password() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /** Runs SQL as root over the socket inside the container; the password never reaches a command line. */
    public static void rootSql(String sql) throws Exception {
        String quoted = "'" + sql.replace("'", "'\\''") + "'";
        ExecResult result = MYSQL.execInContainer("bash", "-c",
                "mysql --defaults-extra-file=<(printf '[client]\\npassword=%s\\n' \"$(< /run/secrets/mysql_root_password)\")"
                        + " -uroot -N -B -e " + quoted);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
}
