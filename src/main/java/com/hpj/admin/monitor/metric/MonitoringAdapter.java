package com.hpj.admin.monitor.metric;

import com.hpj.admin.monitor.MiddlewareType;

/** One middleware adapter handles ordinary and capacity requests through the same collection contract. */
public interface MonitoringAdapter {
    MiddlewareType type();

    MetricContract.CollectionResult collect(CollectionRequest request);
}
