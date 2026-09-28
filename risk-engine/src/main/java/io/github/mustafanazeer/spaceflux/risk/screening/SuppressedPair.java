package io.github.mustafanazeer.spaceflux.risk.screening;

public record SuppressedPair(int catalogNumberA, int catalogNumberB, Mechanism mechanism, String detail) {

    public enum Mechanism {
        STATIC_STACK,
        CO_ORBITING
    }
}
