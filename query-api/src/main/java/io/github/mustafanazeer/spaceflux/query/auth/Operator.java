package io.github.mustafanazeer.spaceflux.query.auth;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The one operator account built from its configured username and password hash (ADR 0009, decision 2). */
final class Operator {

    static final String AUTHORITY = "OPERATOR";

    private static final Logger LOG = LoggerFactory.getLogger(Operator.class);
    private static final Pattern BCRYPT = Pattern.compile("\\{bcrypt}(\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53})");

    private Operator() {
    }

    /**
     * The operator, or empty when either value is missing or the hash is anything but a bcrypt hash at the configured
     * cost: a weaker format such as {noop} would otherwise be accepted, and a hash at another cost would make an
     * unknown username cheaper to check than a wrong password.
     */
    static Optional<UserDetails> from(String username, String passwordHash, int cost) {
        if (username.isBlank() && passwordHash.isBlank()) {
            LOG.warn("No operator is configured (ACK_OPERATOR_USERNAME, ACK_OPERATOR_PASSWORD_HASH); "
                    + "acknowledgement is disabled");
            return Optional.empty();
        }
        if (username.isBlank() || passwordHash.isBlank()) {
            LOG.warn("{} is not set; nobody can sign in",
                    username.isBlank() ? "ACK_OPERATOR_USERNAME" : "ACK_OPERATOR_PASSWORD_HASH");
            return Optional.empty();
        }
        Matcher m = BCRYPT.matcher(passwordHash);
        if (!m.matches() || Integer.parseInt(m.group(2)) != cost) {
            LOG.warn("ACK_OPERATOR_PASSWORD_HASH is not a {bcrypt} hash at cost {}; nobody can sign in", cost);
            return Optional.empty();
        }
        return Optional.of(User.withUsername(username).password(m.group(1)).authorities(AUTHORITY).build());
    }

    /** bcrypt only, at the configured cost, which is also the cost of the check run for an unknown username. */
    static PasswordEncoder encoder(int cost) {
        return new BCryptPasswordEncoder(cost);
    }
}
