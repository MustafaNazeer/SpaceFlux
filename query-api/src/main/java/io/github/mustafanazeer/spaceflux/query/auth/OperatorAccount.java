package io.github.mustafanazeer.spaceflux.query.auth;

import java.util.Optional;

import org.springframework.security.core.userdetails.UserDetails;

/** The operator as configured at startup, or empty when nobody can sign in. */
record OperatorAccount(Optional<UserDetails> user) {
}
