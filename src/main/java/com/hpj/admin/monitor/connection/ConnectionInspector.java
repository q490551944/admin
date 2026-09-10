package com.hpj.admin.monitor.connection;

import java.util.Optional;

/** Inspect only an existing singleton. Implementations must never open a connection or unwrap a lazy proxy. */
public interface ConnectionInspector {
    Optional<ResolvedConnection> inspect(String source, Object singleton);
}
