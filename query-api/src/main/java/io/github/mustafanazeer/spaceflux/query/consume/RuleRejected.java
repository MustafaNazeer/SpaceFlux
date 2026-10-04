package io.github.mustafanazeer.spaceflux.query.consume;

/** A schema valid event that a rule of this consumer refuses; dead lettered with this reason and check rule. */
public class RuleRejected extends RuntimeException {

    public RuleRejected(String reason) {
        super(reason, null, false, false);
    }
}
