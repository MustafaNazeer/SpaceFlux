package io.github.mustafanazeer.spaceflux.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request needs the bearer token read from the Compose secret file, and a request a browser sent, which carries
 * an Origin header, is refused even with it (ADR 0015, decision 3). Only the token's SHA-256 digest is kept, and it
 * is compared in constant time.
 */
final class BearerTokenFilter extends OncePerRequestFilter {

    static final int MIN_LENGTH = 32;
    private static final String PREFIX = "Bearer ";

    private final byte[] digest;

    BearerTokenFilter(Path file) {
        String token;
        try {
            token = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("The tool server's token file cannot be read: " + file);
        }
        if (token.length() < MIN_LENGTH || token.chars().anyMatch(c -> c <= ' ' || c > '~')) {
            throw new IllegalStateException("The tool server's token is at least " + MIN_LENGTH
                    + " printable ASCII characters with no space.");
        }
        this.digest = sha256(token);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)
                || !MessageDigest.isEqual(digest, sha256(header.substring(PREFIX.length())))) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (request.getHeader("Origin") != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
