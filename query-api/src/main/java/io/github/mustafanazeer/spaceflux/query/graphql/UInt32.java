package io.github.mustafanazeer.spaceflux.query.graphql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.function.Function;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.IntValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

/**
 * A whole number from 0 to 4294967295, the range of a MySQL {@code INT UNSIGNED} column, written as a JSON integer.
 * GraphQL's Int stops at 2147483647. A whole number written with a decimal point (1.0), which the event contract
 * allows, is the same number. Anything else, including a fraction, a negative number, a string or a boolean, is
 * refused rather than converted. Literals must be integer literals.
 */
final class UInt32 {

    static final long MAX = 4_294_967_295L;
    static final String NOT_A_NUMBER_IN_RANGE = "UInt32 needs a whole number from 0 to 4294967295.";
    static final String NOT_A_LITERAL_IN_RANGE = "UInt32 needs an integer literal from 0 to 4294967295.";
    private static final BigDecimal DECIMAL_MAX = BigDecimal.valueOf(MAX);

    static final GraphQLScalarType SCALAR = GraphQLScalarType.newScalar().name("UInt32")
            .description("A whole number from 0 to 4294967295.").coercing(new Coercing<Long, Long>() {

                @Override
                public Long serialize(Object value, GraphQLContext context, Locale locale) {
                    return inRange(value, CoercingSerializeException::new);
                }

                @Override
                public Long parseValue(Object value, GraphQLContext context, Locale locale) {
                    return inRange(value, CoercingParseValueException::new);
                }

                @Override
                public Long parseLiteral(Value<?> value, CoercedVariables variables, GraphQLContext context,
                        Locale locale) {
                    if (!(value instanceof IntValue literal)) {
                        throw new CoercingParseLiteralException(NOT_A_LITERAL_IN_RANGE);
                    }
                    try {
                        return inRange(literal.getValue(), CoercingParseLiteralException::new);
                    } catch (CoercingParseLiteralException e) {
                        throw new CoercingParseLiteralException(NOT_A_LITERAL_IN_RANGE);
                    }
                }

                @Override
                public Value<?> valueToLiteral(Object value, GraphQLContext context, Locale locale) {
                    return new IntValue(BigInteger.valueOf(inRange(value, CoercingSerializeException::new)));
                }
            }).build();

    private UInt32() {
    }

    private static <E extends RuntimeException> long inRange(Object value, Function<String, E> refusal) {
        BigInteger whole = switch (value) {
            case Integer n -> BigInteger.valueOf(n);
            case Long n -> BigInteger.valueOf(n);
            case Short n -> BigInteger.valueOf(n);
            case Byte n -> BigInteger.valueOf(n);
            case BigInteger n -> n;
            case Double n when Double.isFinite(n) -> whole(new BigDecimal(n), refusal);
            case BigDecimal n -> whole(n, refusal);
            case null, default -> throw refusal.apply(NOT_A_NUMBER_IN_RANGE);
        };
        if (whole.signum() < 0 || whole.compareTo(BigInteger.valueOf(MAX)) > 0) {
            throw refusal.apply(NOT_A_NUMBER_IN_RANGE);
        }
        return whole.longValueExact();
    }

    /**
     * A contract integer may be written with a decimal point (1.0); its value is still whole. The range is checked
     * first, so a huge exponent such as 1E+50000000 is never expanded into its digits.
     */
    private static <E extends RuntimeException> BigInteger whole(BigDecimal n, Function<String, E> refusal) {
        if (n.signum() < 0 || n.compareTo(DECIMAL_MAX) > 0) {
            throw refusal.apply(NOT_A_NUMBER_IN_RANGE);
        }
        try {
            return n.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw refusal.apply(NOT_A_NUMBER_IN_RANGE);
        }
    }
}
