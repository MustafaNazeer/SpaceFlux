package io.github.mustafanazeer.spaceflux.query;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The service on a free port with both consumers off and one operator whose hash is made at cost 4, to keep tests fast. */
public final class OperatorApp implements AutoCloseable {

    public static final String OPERATOR = "operator";
    public static final int COST = 4;

    public final String password = TestMysql.password();
    public final ConfigurableApplicationContext context;
    public final String base;

    public OperatorApp(String... more) {
        this(new Class<?>[0], more);
    }

    /** With extra configuration classes, such as a test's own controller, which component scanning leaves out. */
    public OperatorApp(Class<?>[] sources, String... more) {
        TestMysql.start();
        String[] args = {"--server.port=0", "--spaceflux.alerts.enabled=false", "--spaceflux.catalog.enabled=false",
            "--ACK_OPERATOR_USERNAME=" + OPERATOR, "--ACK_OPERATOR_BCRYPT_COST=" + COST,
            "--ACK_OPERATOR_PASSWORD_HASH={bcrypt}" + new BCryptPasswordEncoder(COST).encode(password)};
        String[] all = new String[args.length + more.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(more, 0, all, args.length, more.length);
        Class<?>[] classes = new Class<?>[sources.length + 1];
        classes[0] = QueryApiApplication.class;
        System.arraycopy(sources, 0, classes, 1, sources.length);
        context = new SpringApplicationBuilder(classes).web(WebApplicationType.SERVLET)
                .run(TestMysql.args(all));
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    public Browser browser() {
        return new Browser(base);
    }

    /** A browser signed in as the operator. */
    public Browser signedIn() throws Exception {
        Browser b = browser();
        if (b.login(OPERATOR, password).statusCode() != 204) {
            throw new IllegalStateException("login failed");
        }
        return b;
    }

    @Override
    public void close() {
        context.close();
    }
}
