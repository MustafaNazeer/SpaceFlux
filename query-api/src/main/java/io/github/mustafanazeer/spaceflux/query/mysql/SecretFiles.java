package io.github.mustafanazeer.spaceflux.query.mysql;

import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Reads a pool's password only from its secret file. Spring would otherwise accept the same key from an
 * environment variable or a flag, and either would outrank the file and be visible in the process environment.
 */
final class SecretFiles {

    // Spring's view of every other source at once; it contains whatever they contain.
    private static final String ATTACHED = "configurationProperties";

    private SecretFiles() {
    }

    static String password(ConfigurableEnvironment env, String pool, String user) {
        String key = "mysql_" + pool + "_password";
        String bound = "spaceflux.mysql." + pool + ".password";
        String password = null;
        for (PropertySource<?> source : env.getPropertySources()) {
            if (source.getName().equals(ATTACHED)) {
                continue;
            }
            if (source.containsProperty(bound)) {
                throw refused(bound, source, pool);
            }
            if (source.containsProperty(key)) {
                if (!(source instanceof ConfigTreePropertySource)) {
                    throw refused(key, source, pool);
                }
                if (password == null) {
                    password = String.valueOf(source.getProperty(key));
                }
            }
        }
        if (password == null) {
            throw new IllegalStateException("no secret file " + key + " holds the password for " + user);
        }
        if (password.isEmpty()) {
            throw new IllegalStateException("the password for " + user + " is empty");
        }
        return password;
    }

    private static IllegalStateException refused(String key, PropertySource<?> source, String pool) {
        return new IllegalStateException(key + " is set by " + source.getName() + "; the " + pool
                + " password may come only from its secret file");
    }
}
