package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCalculations.BaselineAction;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation;
import com.hpj.admin.monitor.metric.MonitoringCalculations.CounterSample;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.BaselineAction.KEEP;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.BaselineAction.REPLACE;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.*;

class MonitoringCalculationsTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration PERIOD = Duration.ofSeconds(15);
    private static final Object EPOCH = new Object();

    @ParameterizedTest
    @EnumSource(Calculation.class)
    void everyCounterCalculationStartsWithWaitingAndBuildsABaseline(Calculation kind) {
        CounterSample current = kind == RATE || kind == DELTA ? sample(0, "100") : sample(0, "100", "100");
        missing(MonitoringCalculations.evaluate(kind, null, current, PERIOD), MissingReason.WAITING_SAMPLE, REPLACE);
    }

    @Test
    void ratesUseActualElapsedTimeAndPreserveTrueZeroAndLargeCounterPrecision() {
        numeric(calculate(RATE, sample(0, "100"), sample(15, "130")), "2", REPLACE);
        numeric(calculate(RATE, sample(0, "100"), sample(12, "130")), "2.5", REPLACE);
        numeric(calculate(RATE, sample(0, "100"), sample(15, "100")), "0", REPLACE);
        numeric(calculate(RATE, sample(0, "90071992547409930"), sample(15, "90071992547409960")), "2", REPLACE);
        numeric(calculate(DELTA, sample(0, "100"), sample(12, "130")), "30", REPLACE);
    }

    @Test
    void subsecondIntervalsAreNotTruncatedToWholeSeconds() {
        CounterSample current = new CounterSample(START.plusMillis(250), EPOCH, List.of(new BigDecimal("101")));
        numeric(calculate(RATE, sample(0, "100"), current), "4", REPLACE);
    }

    @Test
    void aCounterDecreaseOrNewEpochReplacesTheBaselineWithoutProducingASpike() {
        missing(calculate(RATE, sample(0, "100"), sample(15, "10")), MissingReason.WAITING_SAMPLE, REPLACE);
        CounterSample replacement = new CounterSample(START.plusSeconds(15), new Object(), List.of(new BigDecimal("10000")));
        missing(calculate(RATE, sample(0, "100"), replacement), MissingReason.WAITING_SAMPLE, REPLACE);
        CounterSample earlierReplacement = new CounterSample(START.minusSeconds(1), new Object(), List.of(BigDecimal.ONE));
        missing(calculate(RATE, sample(0, "100"), earlierReplacement), MissingReason.WAITING_SAMPLE, REPLACE);
    }

    @Test
    void eitherComponentResetRebuildsACompoundCountersBaseline() {
        missing(calculate(REDIS_HIT_PERCENT, sample(0, "100", "10"), sample(15, "110", "2")),
                MissingReason.WAITING_SAMPLE, REPLACE);
        // The total CPU counter still rises; a reset in either component nevertheless invalidates the interval.
        missing(calculate(CPU_SINGLE_CORE_PERCENT, sample(0, "10", "10"), sample(15, "9", "100")),
                MissingReason.WAITING_SAMPLE, REPLACE);
    }

    @Test
    void duplicateAndOutOfOrderSamplesKeepTheNewerBaseline() {
        missing(calculate(RATE, sample(15, "100"), sample(15, "120")), MissingReason.WAITING_SAMPLE, KEEP);
        missing(calculate(RATE, sample(15, "100"), sample(14, "120")), MissingReason.WAITING_SAMPLE, KEEP);
    }

    @Test
    void exactlyThreePeriodsIsValidButOneNanosecondMoreRebuildsTheBaseline() {
        CounterSample boundary = sample(45, "190");
        numeric(calculate(RATE, sample(0, "100"), boundary), "2", REPLACE);
        CounterSample expired = new CounterSample(boundary.at().plusNanos(1), EPOCH, boundary.counters());
        missing(calculate(RATE, sample(0, "100"), expired), MissingReason.WAITING_SAMPLE, REPLACE);
        numeric(MonitoringCalculations.evaluate(RATE, sample(0, "100"), sample(180, "460"), Duration.ofSeconds(60)),
                "2", REPLACE);
    }

    @Test
    void intervalComparisonDoesNotOverflowForLargeDurations() {
        numeric(MonitoringCalculations.evaluate(RATE, sample(0, "100"), sample(15, "130"), Duration.ofSeconds(Long.MAX_VALUE)),
                "2", REPLACE);
    }

    @Test
    void redisHitPercentUsesIntervalHitsAndMissesRatherThanLifetimeTotals() {
        numeric(calculate(REDIS_HIT_PERCENT, sample(0, "80", "20"), sample(15, "90", "30")), "50", REPLACE);
        numeric(calculate(REDIS_HIT_PERCENT, sample(0, "80", "20"), sample(15, "80", "30")), "0", REPLACE);
        numeric(calculate(REDIS_HIT_PERCENT, sample(0, "80", "20"), sample(15, "90", "20")), "100", REPLACE);
    }

    @Test
    void mysqlHitPercentUsesPhysicalAndLogicalReadDeltasAndRejectsInconsistentIntervals() {
        numeric(calculate(MYSQL_HIT_PERCENT, sample(0, "10", "100"), sample(15, "12", "120")), "90", REPLACE);
        numeric(calculate(MYSQL_HIT_PERCENT, sample(0, "10", "100"), sample(15, "30", "120")), "0", REPLACE);
        missing(calculate(MYSQL_HIT_PERCENT, sample(0, "10", "100"), sample(15, "40", "110")),
                MissingReason.INVALID_VALUE, REPLACE);
    }

    @Test
    void noRequestsIsDifferentFromANumericZeroHitPercent() {
        missing(calculate(REDIS_HIT_PERCENT, sample(0, "100", "10"), sample(15, "100", "10")),
                MissingReason.NO_REQUESTS, REPLACE);
        missing(calculate(MYSQL_HIT_PERCENT, sample(0, "10", "100"), sample(15, "10", "100")),
                MissingReason.NO_REQUESTS, REPLACE);
    }

    @Test
    void processCpuKeepsFractionalSecondsAndCanExceedOneCore() {
        numeric(calculate(CPU_SINGLE_CORE_PERCENT, sample(0, "10", "5"), sample(15, "25", "20")), "200", REPLACE);
        numeric(calculate(CPU_SINGLE_CORE_PERCENT, sample(0, "10.1", "5.2"), sample(15, "10.4", "5.65")), "5", REPLACE);
        // The contract accepts exactly the two process counters, preventing accidental child-counter summation.
        assertThatThrownBy(() -> new CounterSample(START, EPOCH, List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingNegativeAndWrongArityInputsCannotReplaceAValidBaseline() {
        missing(calculate(RATE, sample(0, "100"), sample(15, (String) null)), MissingReason.INVALID_VALUE, KEEP);
        missing(calculate(RATE, sample(0, "100"), sample(15, "-1")), MissingReason.INVALID_VALUE, KEEP);
        missing(calculate(REDIS_HIT_PERCENT, sample(0, "100", "10"), sample(15, "110")), MissingReason.INVALID_VALUE, KEEP);
        missing(calculate(RATE, sample(0, "100"), sample(15)), MissingReason.INVALID_VALUE, KEEP);
        missing(calculate(RATE, sample(0, (String) null), sample(15, "100")), MissingReason.WAITING_SAMPLE, REPLACE);
    }

    @Test
    void capacityPercentDistinguishesZeroMissingAndUnboundedCapacityWithoutClampingUsage() {
        numeric(MonitoringCalculations.percent(BigDecimal.ZERO, new BigDecimal("100")), "0", KEEP);
        numeric(MonitoringCalculations.percent(new BigDecimal("150"), new BigDecimal("100")), "150", KEEP);
        missing(MonitoringCalculations.percent(BigDecimal.TEN, BigDecimal.ZERO), MissingReason.NOT_APPLICABLE, KEEP);
        missing(MonitoringCalculations.percent(BigDecimal.TEN, null), MissingReason.INVALID_VALUE, KEEP);
        missing(MonitoringCalculations.percent(null, BigDecimal.TEN), MissingReason.INVALID_VALUE, KEEP);
        missing(MonitoringCalculations.percent(BigDecimal.ONE.negate(), BigDecimal.TEN), MissingReason.INVALID_VALUE, KEEP);
        missing(MonitoringCalculations.percent(BigDecimal.ONE, BigDecimal.TEN.negate()), MissingReason.INVALID_VALUE, KEEP);
    }

    @Test
    void samplesAreDefensiveAndTheirStringRepresentationNeverCallsTheEpoch() {
        List<BigDecimal> values = new ArrayList<>(List.of(BigDecimal.TEN));
        Object epoch = new Object() {
            @Override public String toString() { throw new AssertionError("Private epoch must not be printed"); }
        };
        CounterSample sample = new CounterSample(START, epoch, values);
        values.set(0, BigDecimal.ZERO);
        assertThat(sample.counters()).containsExactly(BigDecimal.TEN);
        assertThatThrownBy(() -> sample.counters().set(0, BigDecimal.ONE)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(sample.toString()).isEqualTo("CounterSample[at=2026-01-01T00:00:00Z, counterCount=1]");
    }

    @Test
    void accidentalRootOrNestedSerializationCannotExposeThePrivateEpoch() throws Exception {
        CounterSample sample = new CounterSample(START,
                Map.of("endpoint", "redis://private-user:private-password@host", "generation", "private-generation"),
                List.of(BigDecimal.TEN));
        ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        assertThat(mapper.writeValueAsString(sample)).isEqualTo("{}");
        assertThat(mapper.writeValueAsString(new Envelope("ready", sample))).isEqualTo("{\"status\":\"ready\"}");
        assertThat(sample.toString()).doesNotContain("private-user", "private-password", "private-generation");
    }

    @Test
    void resultAndPeriodContractsRejectAmbiguousOrInvalidArguments() {
        assertThatThrownBy(() -> new Result(null, null, KEEP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Result(BigDecimal.ZERO, MissingReason.FAILED, KEEP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringCalculations.evaluate(RATE, null, sample(0, "0"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitoringCalculations.evaluate(RATE, null, sample(0, "0"), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CounterSample sample(long seconds, String... values) {
        return new CounterSample(START.plusSeconds(seconds), EPOCH,
                Arrays.stream(values).map(value -> value == null ? null : new BigDecimal(value)).toList());
    }

    private static Result calculate(Calculation kind, CounterSample previous, CounterSample current) {
        return MonitoringCalculations.evaluate(kind, previous, current, PERIOD);
    }

    private static void numeric(Result result, String expected, BaselineAction action) {
        assertThat(result.value()).isEqualByComparingTo(expected);
        assertThat(result.reason()).isNull();
        assertThat(result.baselineAction()).isEqualTo(action);
    }

    private static void missing(Result result, MissingReason reason, BaselineAction action) {
        assertThat(result.value()).isNull();
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.baselineAction()).isEqualTo(action);
    }

    private record Envelope(String status, CounterSample sample) {}
}
