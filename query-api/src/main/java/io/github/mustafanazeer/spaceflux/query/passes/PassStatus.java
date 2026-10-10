package io.github.mustafanazeer.spaceflux.query.passes;

/** Whether passes were computed for an object, and if not, why (docs/risk/orbital-conventions.md 6.5). */
public enum PassStatus {
    COMPUTED,
    NO_ELEMENT_SET,
    INVALID_ELEMENT_SET,
    STALE_ELEMENT_SET,
    DEEP_SPACE,
    CANNOT_PROPAGATE,
    DECAYED
}
