package io.github.mustafanazeer.spaceflux.query.mysql;

import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where MySQL is and the two accounts query-api connects as, one per pool. */
@ConfigurationProperties("spaceflux.mysql")
public record MysqlProperties(String host, int port, String database, String sslMode, Account consumer,
        Account api) {

    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Set<String> SSL_MODES = Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");

    public MysqlProperties {
        if (host == null || !host.matches("^[A-Za-z0-9.-]{1,253}$")) {
            throw new IllegalArgumentException("spaceflux.mysql.host must be a host name or address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("spaceflux.mysql.port must be between 1 and 65535");
        }
        if (database == null || !NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("spaceflux.mysql.database must match " + NAME.pattern());
        }
        if (!SSL_MODES.contains(sslMode)) {
            throw new IllegalArgumentException("spaceflux.mysql.ssl-mode must be one of " + SSL_MODES);
        }
        if (consumer == null || api == null) {
            throw new IllegalArgumentException("spaceflux.mysql needs both the consumer and the api account");
        }
    }

    // Only the TLS mode varies; nothing configurable can switch on key retrieval or local file loading.
    String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?sslMode=" + sslMode
                + "&allowPublicKeyRetrieval=false&allowLoadLocalInfile=false&allowUrlInLocalInfile=false"
                // A literal + in a URL query decodes as a space, so the +00:00 offset is percent encoded.
                + "&connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true"
                // A database that accepts a connection and then stops answering fails a statement after 30 s, a
                // logged and retried error, instead of blocking a consumer past Kafka's max.poll.interval.ms.
                + "&socketTimeout=30000";
    }

    public record Account(String username) {

        public Account {
            if (username == null || !NAME.matcher(username).matches()) {
                throw new IllegalArgumentException("the username must match " + NAME.pattern());
            }
        }
    }
}
