package io.github.mustafanazeer.spaceflux.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Applies the query-api schema as the migration user, then exits. */
public final class Migrate {

    static final Pattern USER_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Set<String> SSL_MODES = Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");

    private Migrate() {
    }

    record Settings(String host, int port, String database, String sslMode, String user, String password,
            String consumerUser, String apiUser) {

        Settings {
            requireName("MIGRATE_DATABASE", database);
            requireName("MIGRATE_USER", user);
            requireName("CONSUMER_USER", consumerUser);
            requireName("API_USER", apiUser);
            if (host == null || !host.matches("^[A-Za-z0-9.-]{1,253}$")) {
                throw new IllegalArgumentException("MIGRATE_HOST must be a host name or address");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("MIGRATE_PORT must be between 1 and 65535");
            }
            if (!SSL_MODES.contains(sslMode)) {
                throw new IllegalArgumentException("MIGRATE_SSL_MODE must be one of " + SSL_MODES);
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("the migration password is empty");
            }
        }

        // Only the TLS mode varies; nothing a caller supplies can switch on key retrieval or local file loading.
        String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?sslMode=" + sslMode
                    + "&allowPublicKeyRetrieval=false&allowLoadLocalInfile=false&allowUrlInLocalInfile=false"
                    // A literal + in a URL query decodes as a space, so the +00:00 offset is percent encoded.
                    + "&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true";
        }

        @Override
        public String toString() {
            return "Settings[" + user + "@" + host + ":" + port + "/" + database + "]";
        }

        static Settings fromEnvironment(Map<String, String> env) throws IOException {
            String passwordFile = env.getOrDefault("MIGRATE_PASSWORD_FILE", "/run/secrets/mysql_migrate_password");
            String password = Files.readString(Path.of(passwordFile), StandardCharsets.US_ASCII).strip();
            return new Settings(
                    env.getOrDefault("MIGRATE_HOST", "mysql"),
                    Integer.parseInt(env.getOrDefault("MIGRATE_PORT", "3306")),
                    env.getOrDefault("MIGRATE_DATABASE", "spaceflux"),
                    env.getOrDefault("MIGRATE_SSL_MODE", "REQUIRED"),
                    env.get("MIGRATE_USER"),
                    password,
                    env.get("CONSUMER_USER"),
                    env.get("API_USER"));
        }

        private static void requireName(String what, String value) {
            if (value == null || !USER_NAME.matcher(value).matches()) {
                throw new IllegalArgumentException(what + " must match " + USER_NAME.pattern());
            }
        }
    }

    static MigrateResult run(Settings settings) {
        return Flyway.configure(Migrate.class.getClassLoader())
                .dataSource(settings.jdbcUrl(), settings.user(), settings.password())
                .locations("classpath:db/migration")
                .placeholders(Map.of("consumer_user", settings.consumerUser(), "api_user", settings.apiUser()))
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    public static void main(String[] args) throws IOException {
        Settings settings;
        try {
            settings = Settings.fromEnvironment(System.getenv());
        } catch (NoSuchFileException e) {
            System.err.println("db-migrate: password file not found: " + e.getFile());
            System.exit(2);
            return;
        } catch (IllegalArgumentException e) {
            System.err.println("db-migrate: " + e.getMessage());
            System.exit(2);
            return;
        }
        MigrateResult result = run(settings);
        if (result.migrationsExecuted == 0) {
            System.out.println("db-migrate: schema already current at version " + result.initialSchemaVersion);
        } else {
            System.out.println("db-migrate: " + result.migrationsExecuted + " migrations applied, schema at version "
                    + result.targetSchemaVersion);
        }
    }
}
