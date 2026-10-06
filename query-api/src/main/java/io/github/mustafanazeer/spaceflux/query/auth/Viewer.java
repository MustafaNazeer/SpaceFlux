package io.github.mustafanazeer.spaceflux.query.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is asking: the signed in operator, or an anonymous viewer (ADR 0009, decision 5). */
public final class Viewer {

    private Viewer() {
    }

    public static boolean isOperator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated()
                && a.getAuthorities().stream().anyMatch(g -> Operator.AUTHORITY.equals(g.getAuthority()));
    }
}
