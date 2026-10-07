package io.github.mustafanazeer.spaceflux.query.graphql;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses a GraphQL request body over 64 KiB with 413 (SEC-API-20). Tomcat's maxPostSize covers only form and
 * multipart parameters, and neither Spring for GraphQL nor Jackson's defaults bound a JSON body, so without this a
 * client could make the service hold any amount of variables in memory. A declared length is refused before
 * reading; a chunked body is read up to one byte over the limit and refused there.
 */
@Component
class BodySizeLimit extends OncePerRequestFilter {

    static final int MAX_BYTES = 64 * 1024;

    /**
     * Matched on the decoded path, as the security rules and the GraphQL router match it, so a percent encoded
     * spelling of the path cannot reach the endpoint without passing here.
     */
    private static final RequestMatcher GRAPHQL = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/graphql");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GRAPHQL.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }
        chain.doFilter(new Buffered(request, body), response);
    }

    /** The request with its body already read, served again from memory. */
    private static final class Buffered extends HttpServletRequestWrapper {

        private final byte[] body;

        Buffered(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
