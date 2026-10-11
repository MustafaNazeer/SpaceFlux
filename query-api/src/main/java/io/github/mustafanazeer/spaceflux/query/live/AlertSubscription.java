package io.github.mustafanazeer.spaceflux.query.live;

import java.util.Map;

import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.CloseStatus;

import graphql.schema.DataFetchingEnvironment;
import io.github.mustafanazeer.spaceflux.query.web.ApiErrors;
import reactor.core.publisher.Flux;

/** {@code Subscription.alert_stored} (docs/api/graphql.md). */
@Controller
class AlertSubscription {

    /** A subscriber whose notices back up, or whose delivery failed; the client reconnects. */
    static final CloseStatus UNDELIVERABLE = new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(),
            "Notices cannot be delivered.");

    private final AlertNotices notices;

    AlertSubscription(AlertNotices notices) {
        this.notices = notices;
    }

    @SubscriptionMapping
    Flux<Map<String, Object>> alert_stored(DataFetchingEnvironment env) {
        Connection connection = env.getGraphQlContext().get(Connection.KEY);
        if (connection == null) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, Transports.OVER_SOCKET_ONLY);
        }
        if (!connection.subscribe()) {
            throw new ApiErrors.Refused(HttpStatus.BAD_REQUEST, "One subscription per connection.");
        }
        return notices.subscribe(() -> connection.close(UNDELIVERABLE)).doFinally(signal -> connection.unsubscribe());
    }
}
