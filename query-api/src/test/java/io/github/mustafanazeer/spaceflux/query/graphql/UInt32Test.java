package io.github.mustafanazeer.spaceflux.query.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

/** The UInt32 scalar: whole numbers from 0 to 4294967295, written as JSON integers, and nothing else. */
class UInt32Test {

    @SuppressWarnings("unchecked")
    static final Coercing<Long, Long> COERCING = (Coercing<Long, Long>) UInt32.SCALAR.getCoercing();
    static final GraphQLContext CONTEXT = GraphQLContext.getDefault();

    static Object serialize(Object value) {
        return COERCING.serialize(value, CONTEXT, Locale.ROOT);
    }

    static Object parseValue(Object value) {
        return COERCING.parseValue(value, CONTEXT, Locale.ROOT);
    }

    static Object parseLiteral(Value<?> value) {
        return COERCING.parseLiteral(value, CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT);
    }

    @Test
    void theScalarIsNamedUInt32() {
        assertThat(UInt32.SCALAR.getName()).isEqualTo("UInt32");
    }

    @Test
    void serializesEveryWholeNumberInRangeAsALong() {
        assertThat(serialize(0)).isEqualTo(0L);
        assertThat(serialize(4_294_967_295L)).isEqualTo(4_294_967_295L);
        assertThat(serialize(BigInteger.valueOf(4_294_967_295L))).isEqualTo(4_294_967_295L);
        assertThat(serialize(Integer.MAX_VALUE)).isEqualTo(2_147_483_647L);
    }

    /** A stored payload can hold a whole number written with a decimal point, which its contract allows. */
    @Test
    void serializesAWholeNumberWrittenWithADecimalPointAsALong() {
        assertThat(serialize(1.0)).isEqualTo(1L);
        assertThat(serialize(4_294_967_295.0)).isEqualTo(4_294_967_295L);
        assertThat(serialize(new java.math.BigDecimal("1.00"))).isEqualTo(1L);
    }

    @Test
    void refusesToSerializeAnythingElse() {
        for (Object bad : new Object[] {4_294_967_296L, -1, -1L, 1.5, 4_294_967_296.0, -1.0, Double.NaN,
            Double.POSITIVE_INFINITY, "1", BigInteger.TWO.pow(64), true}) {
            assertThatThrownBy(() -> serialize(bad)).as(String.valueOf(bad))
                    .isInstanceOf(CoercingSerializeException.class);
        }
    }

    @Test
    void parsesVariablesInRange() {
        assertThat(parseValue(0)).isEqualTo(0L);
        assertThat(parseValue(4_294_967_295L)).isEqualTo(4_294_967_295L);
        assertThat(parseValue(BigInteger.valueOf(4_294_967_295L))).isEqualTo(4_294_967_295L);
        assertThat(parseValue(1.0)).isEqualTo(1L);
    }

    @Test
    void refusesVariablesOutOfRangeFractionalOrNotNumbers() {
        for (Object bad : new Object[] {4_294_967_296L, -1, 1.5, "1", true}) {
            assertThatThrownBy(() -> parseValue(bad)).as(String.valueOf(bad))
                    .isInstanceOf(CoercingParseValueException.class);
        }
    }

    @Test
    void parsesIntegerLiteralsInRange() {
        assertThat(parseLiteral(new IntValue(BigInteger.ZERO))).isEqualTo(0L);
        assertThat(parseLiteral(new IntValue(BigInteger.valueOf(4_294_967_295L)))).isEqualTo(4_294_967_295L);
    }

    @Test
    void refusesLiteralsOutOfRangeFractionalOrNotIntegers() {
        for (Value<?> bad : new Value<?>[] {new IntValue(BigInteger.valueOf(4_294_967_296L)),
            new IntValue(BigInteger.valueOf(-1)), new FloatValue(new java.math.BigDecimal("1.5")),
            new FloatValue(new java.math.BigDecimal("1.0")), new StringValue("1")}) {
            assertThatThrownBy(() -> parseLiteral(bad)).as(String.valueOf(bad))
                    .isInstanceOf(CoercingParseLiteralException.class);
        }
    }

    /** A literal written back out, as graphql-java does for default values, is an integer literal. */
    @Test
    void writesAValueBackAsAnIntegerLiteral() {
        assertThat(COERCING.valueToLiteral(4_294_967_295L, CONTEXT, Locale.ROOT))
                .isInstanceOfSatisfying(IntValue.class,
                        v -> assertThat(v.getValue()).isEqualTo(BigInteger.valueOf(4_294_967_295L)));
    }

    /** A UInt32 field is a leaf like an Int: 50 x (1 + (1 + 1)) = 150 for either. */
    @Test
    void aUInt32FieldCostsWhatAnIntFieldCosts() throws Exception {
        assertThat(PassesCostTest.cost("{ alerts { items { rules_version } } }")).isEqualTo(150)
                .isEqualTo(PassesCostTest.cost("{ alerts { items { schema_version } } }"));
        // 1 + (1 + (1 + 1) + (1 + 1) + 1) = 7.
        assertThat(PassesCostTest.cost("{ alert(event_id: \"x\") { screening_run { coverage { pairs } omitted { "
                + "suppressed } approach_count } } }")).isEqualTo(7);
    }

    /** A huge exponent is refused before it is expanded into its digits (SEC-API-23). */
    @Test
    void refusesAHugeDecimalExponentWithoutExpandingIt() {
        for (String huge : new String[] {"1E+10000000", "1E+50000000"}) {
            java.math.BigDecimal value = new java.math.BigDecimal(huge);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertThatThrownBy(() -> serialize(value)).isInstanceOf(CoercingSerializeException.class);
                assertThatThrownBy(() -> parseValue(value)).isInstanceOf(CoercingParseValueException.class);
            }, huge);
        }
    }

    /** A refusal says what is allowed and never repeats the value it refused. */
    @Test
    void refusalsUseFixedTextAndNeverRepeatTheValue() {
        BigInteger digits = BigInteger.TEN.pow(20_000).add(BigInteger.valueOf(1234567));
        String number = "UInt32 needs a whole number from 0 to 4294967295.";

        assertThatThrownBy(() -> serialize(digits)).hasMessage(number);
        assertThatThrownBy(() -> parseValue(digits)).hasMessage(number);
        assertThatThrownBy(() -> serialize("secret text")).hasMessage(number);
        assertThatThrownBy(() -> parseValue(1.5)).hasMessage(number);
        assertThatThrownBy(() -> parseLiteral(new IntValue(digits)))
                .hasMessage("UInt32 needs an integer literal from 0 to 4294967295.");
        assertThatThrownBy(() -> parseLiteral(new StringValue("secret text")))
                .hasMessage("UInt32 needs an integer literal from 0 to 4294967295.");
    }
}
