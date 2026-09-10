package com.hpj.admin.monitor.metric;

import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCalculations.CounterSample;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hpj.admin.monitor.metric.MonitoringCalculations.BaselineAction.KEEP;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringCounterStoreTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration PERIOD = Duration.ofSeconds(15);
    private static final Object EPOCH = new Object();

    @Test
    void bothTargetAndSeriesBoundsRejectNewKeysWhileExistingSeriesContinue() {
        MonitoringCounterStore store = new MonitoringCounterStore(2, 2);
        assertThat(update(store, "redis-a", "ordinary:commands", 0, "100").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        update(store, "redis-a", "ordinary:expired", 0, "10");
        update(store, "redis-b", "ordinary:commands", 0, "20");
        busy(update(store, "redis-a", "ordinary:evicted", 0, "0"));
        busy(update(store, "redis-c", "ordinary:commands", 0, "0"));
        assertThat(update(store, "redis-a", "ordinary:commands", 15, "130").value()).isEqualByComparingTo("2");
        assertThat(store.targetCount()).isEqualTo(2);
        assertThat(store.seriesCount("redis-a")).isEqualTo(2);
        assertThat(store.seriesCount("redis-b")).isEqualTo(1);
        assertThat(store.seriesCount("redis-c")).isZero();
    }

    @Test
    void differentCalculationsDoNotAccidentallyReuseTheSameSeriesBaseline() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 2);
        update(store, "redis", "commands", 0, "100");
        assertThat(store.update("redis", "commands", DELTA, sample(15, EPOCH, "130"), PERIOD).reason())
                .isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(store.seriesCount("redis")).isEqualTo(2);
        assertThat(store.update("redis", "commands", DELTA, sample(30, EPOCH, "140"), PERIOD).value())
                .isEqualByComparingTo("10");
    }

    @Test
    void changingEpochsReusesOneEntryAndEachReplacementNeedsANewBaseline() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        Object lastEpoch = null;
        for (int index = 0; index < 100; index++) {
            lastEpoch = new Object();
            Result result = store.update("redis", "commands", RATE, sample(index, lastEpoch, Integer.toString(index)), PERIOD);
            assertThat(result.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(store.targetCount()).isEqualTo(1);
            assertThat(store.seriesCount("redis")).isEqualTo(1);
        }
        assertThat(store.update("redis", "commands", RATE, sample(100, lastEpoch, "109"), PERIOD).value())
                .isEqualByComparingTo("10");
    }

    @Test
    void deletingATargetAndRestartingTheStoreClearBaselinesAndReleaseCapacity() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        update(store, "redis-a", "commands", 0, "100");
        store.removeTarget("redis-a");
        assertThat(store.targetCount()).isZero();
        assertThat(store.seriesCount("redis-a")).isZero();
        assertThat(update(store, "redis-b", "commands", 15, "130").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        store.clear();
        assertThat(update(store, "redis-b", "commands", 30, "140").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        MonitoringCounterStore restarted = new MonitoringCounterStore(1, 1);
        assertThat(update(restarted, "redis-b", "commands", 45, "150").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
    }

    @Test
    void retiringADynamicScopeReleasesItsSeriesAndTheLastSeriesReleasesItsTargetSlot() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        update(store, "redis", "ordinary:node-a:commands", 0, "100");
        busy(update(store, "redis", "ordinary:node-b:commands", 15, "200"));
        assertThat(store.removeSeries("redis", "ordinary:node-a:commands", RATE)).isTrue();
        assertThat(store.seriesCount("redis")).isZero();
        assertThat(store.targetCount()).isZero();
        assertThat(update(store, "redis", "ordinary:node-b:commands", 15, "200").reason())
                .isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(update(store, "redis", "ordinary:node-b:commands", 30, "215").value()).isEqualByComparingTo("1");
        assertThat(store.removeSeries("redis", "ordinary:node-b:commands", RATE)).isTrue();
        assertThat(update(store, "replacement", "ordinary:commands", 45, "1000").reason())
                .isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(store.targetCount()).isEqualTo(1);
    }

    @Test
    void retiringOneSeriesPreservesOtherScopesAndOtherCalculationsForTheSameSeriesId() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 3);
        update(store, "redis", "ordinary:node-a:commands", 0, "100");
        store.update("redis", "ordinary:node-a:commands", DELTA, sample(0, EPOCH, "100"), PERIOD);
        update(store, "redis", "ordinary:node-b:commands", 0, "1000");

        assertThat(store.removeSeries("redis", "ordinary:node-a:commands", RATE)).isTrue();
        assertThat(store.removeSeries("redis", "ordinary:node-a:commands", RATE)).isFalse();
        assertThat(store.removeSeries("missing", "ordinary:node-a:commands", RATE)).isFalse();
        assertThat(store.seriesCount("redis")).isEqualTo(2);
        assertThat(store.update("redis", "ordinary:node-a:commands", DELTA, sample(15, EPOCH, "130"), PERIOD).value())
                .isEqualByComparingTo("30");
        assertThat(update(store, "redis", "ordinary:node-b:commands", 15, "1015").value()).isEqualByComparingTo("1");
        assertThat(store.targetCount()).isEqualTo(1);
    }

    @Test
    void missingAndOutOfOrderSamplesDoNotOverwriteAValidBaselineOrConsumeSlots() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        assertThat(update(store, "invalid", "commands", 0, (String) null).reason()).isEqualTo(MissingReason.INVALID_VALUE);
        assertThat(store.targetCount()).isZero();
        update(store, "redis", "commands", 0, "100");
        assertThat(update(store, "redis", "commands", 15, (String) null).reason()).isEqualTo(MissingReason.INVALID_VALUE);
        assertThat(update(store, "redis", "commands", 30, "130").value()).isEqualByComparingTo("1");
        Result older = update(store, "redis", "commands", 10, "10000");
        assertThat(older.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(older.baselineAction()).isEqualTo(KEEP);
        assertThat(update(store, "redis", "commands", 45, "145").value()).isEqualByComparingTo("1");
        assertThat(store.seriesCount("redis")).isEqualTo(1);
    }

    @Test
    void resetAndExpiredSamplesReplaceTheBaselineForTheFollowingInterval() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        update(store, "redis", "commands", 0, "100");
        assertThat(update(store, "redis", "commands", 15, "10").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(update(store, "redis", "commands", 30, "40").value()).isEqualByComparingTo("2");
        assertThat(update(store, "redis", "commands", 76, "1000").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(update(store, "redis", "commands", 91, "1030").value()).isEqualByComparingTo("2");
    }

    @Test
    void invalidDerivedRatioStillAdvancesValidRawCountersForTheNextInterval() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        store.update("mysql", "buffer-hit", MYSQL_HIT_PERCENT, sample(0, EPOCH, "10", "100"), PERIOD);
        assertThat(store.update("mysql", "buffer-hit", MYSQL_HIT_PERCENT, sample(15, EPOCH, "40", "110"), PERIOD).reason())
                .isEqualTo(MissingReason.INVALID_VALUE);
        assertThat(store.update("mysql", "buffer-hit", MYSQL_HIT_PERCENT, sample(30, EPOCH, "42", "130"), PERIOD).value())
                .isEqualByComparingTo("90");
    }

    @Test
    void storedValuesCannotBeChangedByTheSuppliedCounterListAndStringsHideKeysAndEpochs() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        List<BigDecimal> counters = new ArrayList<>(List.of(new BigDecimal("100")));
        CounterSample sample = new CounterSample(START, EPOCH, counters);
        store.update("private-target", "private-series", RATE, sample, PERIOD);
        counters.set(0, new BigDecimal("10000"));
        assertThat(update(store, "private-target", "private-series", 15, "130").value()).isEqualByComparingTo("2");
        assertThat(store.toString()).isEqualTo("MonitoringCounterStore[targetCount=1]");
    }

    @Test
    void concurrentSameEpochUpdatesKeepTheNewestSampleWithOnlyOneStoredEntry() throws Exception {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        update(store, "redis", "commands", 0, "0");
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> pending = new ArrayList<>();
        try {
            for (int index = 1; index <= 64; index++) {
                int value = index;
                pending.add(workers.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
                    return update(store, "redis", "commands", value, Integer.toString(value * 10));
                }));
            }
            start.countDown();
            for (Future<Result> future : pending) assertThat(future.get(5, TimeUnit.SECONDS).reason()).isNotEqualTo(MissingReason.BUSY);
            // Only the newest sample (64s, 640) gives 1/s here; retaining an older sample would give another result.
            assertThat(update(store, "redis", "commands", 65, "641").value()).isEqualByComparingTo("1");
            assertThat(store.targetCount()).isEqualTo(1);
            assertThat(store.seriesCount("redis")).isEqualTo(1);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentNewKeysCannotExceedEitherStorageLimit() throws Exception {
        MonitoringCounterStore store = new MonitoringCounterStore(2, 2);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                int value = index;
                pending.add(workers.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
                    return update(store, "target-" + (value % 4), "series-" + value, 0, "0");
                }));
            }
            start.countDown();
            int retained = 0;
            for (Future<Result> future : pending) {
                Result result = future.get(5, TimeUnit.SECONDS);
                if (result.reason() == MissingReason.WAITING_SAMPLE) retained++;
                else busy(result);
            }
            assertThat(retained).isEqualTo(4);
            assertThat(store.targetCount()).isEqualTo(2);
            for (int index = 0; index < 4; index++) assertThat(store.seriesCount("target-" + index)).isBetween(0, 2);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invalidLimitsAndBlankKeysAreRejectedWithoutEchoingInput() {
        assertThatThrownBy(() -> new MonitoringCounterStore(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringCounterStore(1, -1)).isInstanceOf(IllegalArgumentException.class);
        MonitoringCounterStore store = new MonitoringCounterStore(1, 1);
        assertThatThrownBy(() -> update(store, " ", "commands", 0, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(store, "redis", "", 0, "1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.targetCount()).isZero();
    }

    @Test
    void targetIdsUseSafeReferencesAndSeriesTextHasAnInclusiveLengthBound() {
        MonitoringCounterStore store = new MonitoringCounterStore(1, 2);
        String target = "t" + "x".repeat(95);
        String longestSeries = "s".repeat(2048);
        assertThat(update(store, target, longestSeries, 0, "1").reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThat(update(store, target, "source|ordinary|节点=/data/cache a|commands", 0, "1").reason())
                .isEqualTo(MissingReason.WAITING_SAMPLE);
        assertThatThrownBy(() -> update(store, target + "x", "commands", 0, "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(store, "redis://private-password@host", "commands", 0, "1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-password");
        assertThatThrownBy(() -> update(store, target, longestSeries + "x", 0, "1"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String control : List.of("\n", "\t", "\u0000", "\u007f", "\u0085")) {
            assertThatThrownBy(() -> update(store, target, "source" + control + "scope", 0, "1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.removeTarget("invalid target")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.seriesCount("invalid target")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.removeSeries("invalid target", "commands", RATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.removeSeries(target, longestSeries + "x", RATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.removeSeries(target, "commands\n", RATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.removeSeries(target, "commands", null)).isInstanceOf(NullPointerException.class);
        assertThat(store.targetCount()).isEqualTo(1);
        assertThat(store.seriesCount(target)).isEqualTo(2);
    }

    private static Result update(MonitoringCounterStore store, String target, String series, long seconds, String... counters) {
        return store.update(target, series, RATE, sample(seconds, EPOCH, counters), PERIOD);
    }

    private static CounterSample sample(long seconds, Object epoch, String... values) {
        return new CounterSample(START.plusSeconds(seconds), epoch,
                Arrays.stream(values).map(value -> value == null ? null : new BigDecimal(value)).toList());
    }

    private static void busy(Result result) {
        assertThat(result.value()).isNull();
        assertThat(result.reason()).isEqualTo(MissingReason.BUSY);
        assertThat(result.baselineAction()).isEqualTo(KEEP);
    }
}
