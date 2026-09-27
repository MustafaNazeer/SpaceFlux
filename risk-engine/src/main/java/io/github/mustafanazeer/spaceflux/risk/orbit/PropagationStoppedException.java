package io.github.mustafanazeer.spaceflux.risk.orbit;

/** The element set cannot be screened at or after the requested date. */
public class PropagationStoppedException extends RuntimeException {

    public PropagationStoppedException(String message) {
        super(message);
    }

    public PropagationStoppedException(String message, Throwable cause) {
        super(message, cause);
    }
}
