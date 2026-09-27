package io.github.mustafanazeer.spaceflux.risk.orbit;

public class InvalidElementSetException extends RuntimeException {

    public InvalidElementSetException(String message) {
        super(message);
    }

    public InvalidElementSetException(String message, Throwable cause) {
        super(message, cause);
    }
}
