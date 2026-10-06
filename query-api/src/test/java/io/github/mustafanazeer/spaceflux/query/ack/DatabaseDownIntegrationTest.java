package io.github.mustafanazeer.spaceflux.query.ack;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.Browser;
import io.github.mustafanazeer.spaceflux.query.OperatorApp;
import io.github.mustafanazeer.spaceflux.query.TestMysql;

/**
 * A read and an acknowledgement while MySQL does not answer (docs/api/rest.md, common rules). The shared container is
 * paused rather than stopped so the classes after this one find it as it was; each request waits out the pool's
 * connection timeout.
 */
class DatabaseDownIntegrationTest {

    static OperatorApp app;

    @BeforeAll
    static void start() {
        app = new OperatorApp();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    static void assertGenericProblem(HttpResponse<String> r, String path) {
        AcknowledgementIntegrationTest.assertProblem(r, 500);
        var problem = AcknowledgementIntegrationTest.JSON.readTree(r.body());
        assertThat(problem.get("detail").asString()).isEqualTo("The request failed.");
        assertThat(problem.get("instance").asString()).isEqualTo(path);
        assertThat(r.body()).doesNotContainIgnoringCase("sql").doesNotContainIgnoringCase("jdbc")
                .doesNotContainIgnoringCase("communications").doesNotContainIgnoringCase("exception");
    }

    @Test
    void whileMysqlDoesNotAnswerAReadAndAnAcknowledgementAreAGenericProblemAndNoRowIsWritten() throws Exception {
        String id = AcknowledgementIntegrationTest.stored("valid-close-approach.json");
        Browser b = app.signedIn();
        var docker = TestMysql.MYSQL.getDockerClient();
        String container = TestMysql.MYSQL.getContainerId();

        docker.pauseContainerCmd(container).exec();
        HttpResponse<String> read;
        HttpResponse<String> write;
        try {
            read = b.get("/api/watchlist");
            write = AcknowledgementIntegrationTest.post(b, id, AcknowledgementIntegrationTest.ACK);
        } finally {
            docker.unpauseContainerCmd(container).exec();
        }

        assertGenericProblem(read, "/api/watchlist");
        assertGenericProblem(write, "/api/alerts/acknowledgements");
        assertThat(AcknowledgementIntegrationTest.rows(id)).isZero();
        assertThat(b.get("/api/watchlist").statusCode()).isEqualTo(200);
    }
}
