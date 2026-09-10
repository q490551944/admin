package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure calculations over adjacent native counter samples; never keeps history or reads a clock. */
public final class MonitoringCalculations {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private MonitoringCalculations() {}

    public enum Calculation {
        RATE(1), DELTA(1), REDIS_HIT_PERCENT(2), MYSQL_HIT_PERCENT(2), CPU_SINGLE_CORE_PERCENT(2);

        private final int counterCount;

        Calculation(int counterCount) { this.counterCount = counterCount; }
    }

    public enum BaselineAction { KEEP, REPLACE }

    /**
     * The epoch is an immutable, server-only token for the connection/service generation.
     * Redis uses [hits, misses], MySQL [physical reads, logical reads], CPU [process user seconds, process system seconds].
     * A null counter is an explicit missing native value and is rejected by evaluate, without replacing a good baseline.
     */
    @JsonIgnoreType
    public record CounterSample(@JsonIgnore Instant at, @JsonIgnore Object epoch,
                                @JsonIgnore List<BigDecimal> counters) {
        public CounterSample {
            Objects.requireNonNull(at, "sample time");
            Objects.requireNonNull(epoch, "sample epoch");
            Objects.requireNonNull(counters, "sample counters");
            if (counters.size() > 2) throw new IllegalArgumentException("At most two counters are supported");
            counters = Collections.unmodifiableList(new ArrayList<>(counters));
        }

        @Override public String toString() {
            return "CounterSample[at=" + at + ", counterCount=" + counters.size() + "]";
        }
    }

    /** Numeric zero and unavailable results are distinct; only one of value/reason may be present. */
    public record Result(BigDecimal value, MissingReason reason, BaselineAction baselineAction) {
        public Result {
            Objects.requireNonNull(baselineAction, "baseline action");
            if ((value == null) == (reason == null)) {
                throw new IllegalArgumentException("Exactly one of value and missing reason is required");
            }
        }
    }

    public static Result evaluate(Calculation calculation, CounterSample previous, CounterSample current,
                                  Duration period) {
        Objects.requireNonNull(calculation, "calculation");
        Objects.requireNonNull(current, "current sample");
        Objects.requireNonNull(period, "sampling period");
        if (period.isZero() || period.isNegative()) throw new IllegalArgumentException("Sampling period must be positive");
        if (!valid(calculation, current)) return missing(MissingReason.INVALID_VALUE, BaselineAction.KEEP);
        if (previous == null || !Objects.equals(previous.epoch(), current.epoch()) || !valid(calculation, previous)) {
            return missing(MissingReason.WAITING_SAMPLE, BaselineAction.REPLACE);
        }
        if (!current.at().isAfter(previous.at())) {
            return missing(MissingReason.WAITING_SAMPLE, BaselineAction.KEEP);
        }
        BigDecimal elapsed = seconds(Duration.between(previous.at(), current.at()));
        // Decimal seconds preserve the strict nanosecond boundary without Duration multiplication overflow.
        if (elapsed.compareTo(seconds(period).multiply(THREE)) > 0) {
            return missing(MissingReason.WAITING_SAMPLE, BaselineAction.REPLACE);
        }
        List<BigDecimal> deltas = new ArrayList<>(calculation.counterCount);
        for (int index = 0; index < calculation.counterCount; index++) {
            BigDecimal delta = current.counters().get(index).subtract(previous.counters().get(index));
            if (delta.signum() < 0) return missing(MissingReason.WAITING_SAMPLE, BaselineAction.REPLACE);
            deltas.add(delta);
        }
        BigDecimal first = deltas.get(0);
        return switch (calculation) {
            case RATE -> numeric(first.divide(elapsed, PRECISION), BaselineAction.REPLACE);
            case DELTA -> numeric(first, BaselineAction.REPLACE);
            case REDIS_HIT_PERCENT -> ratio(first, first.add(deltas.get(1)), false);
            case MYSQL_HIT_PERCENT -> ratio(first, deltas.get(1), true);
            case CPU_SINGLE_CORE_PERCENT -> numeric(first.add(deltas.get(1)).multiply(HUNDRED)
                    .divide(elapsed, PRECISION), BaselineAction.REPLACE);
        };
    }

    /** A positive capacity denominator is required; over-limit usage is preserved rather than clamped. */
    public static Result percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || numerator.signum() < 0 || denominator.signum() < 0) {
            return missing(MissingReason.INVALID_VALUE, BaselineAction.KEEP);
        }
        if (denominator.signum() == 0) return missing(MissingReason.NOT_APPLICABLE, BaselineAction.KEEP);
        return numeric(numerator.multiply(HUNDRED).divide(denominator, PRECISION), BaselineAction.KEEP);
    }

    private static Result ratio(BigDecimal numerator, BigDecimal denominator, boolean complement) {
        if (denominator.signum() == 0) return missing(MissingReason.NO_REQUESTS, BaselineAction.REPLACE);
        // Inconsistent native intervals must not produce a negative MySQL hit percentage.
        if (complement && numerator.compareTo(denominator) > 0) {
            return missing(MissingReason.INVALID_VALUE, BaselineAction.REPLACE);
        }
        BigDecimal value = (complement ? denominator.subtract(numerator) : numerator).multiply(HUNDRED)
                .divide(denominator, PRECISION);
        return numeric(value, BaselineAction.REPLACE);
    }

    private static boolean valid(Calculation calculation, CounterSample sample) {
        return sample.counters().size() == calculation.counterCount
                && sample.counters().stream().allMatch(counter -> counter != null && counter.signum() >= 0);
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private static Result numeric(BigDecimal value, BaselineAction action) { return new Result(value, null, action); }

    private static Result missing(MissingReason reason, BaselineAction action) { return new Result(null, reason, action); }
}
