package io.github.mustafanazeer.spaceflux.query.alerts;

/**
 * A schema valid event holds a value its column cannot store as received. The event is dead lettered with this
 * message as the reason and no check, since neither a schema nor a rule rejected it (docs/data/mysql-schema.md).
 */
public class NotStorable extends RuntimeException {

    public NotStorable(String reason) {
        super(reason, null, false, false);
    }
}
