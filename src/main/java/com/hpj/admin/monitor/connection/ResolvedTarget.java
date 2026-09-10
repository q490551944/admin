package com.hpj.admin.monitor.connection;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.monitor.MonitoringTarget;

import java.util.List;

/** Safe display data plus server-only source bindings; each source keeps its own allowed scope. */
@JsonIgnoreType
public record ResolvedTarget(MonitoringTarget display, List<String> memberIds,
                             List<SourceBinding> bindings, Reason reason) {
    public ResolvedTarget {
        memberIds = List.copyOf(memberIds);
        bindings = List.copyOf(bindings);
    }

    public enum Reason { CONFIGURED, DISABLED, SOURCE_MISSING, AMBIGUOUS_SOURCE, UNSUPPORTED_SOURCE }

    @JsonIgnoreType
    public record SourceBinding(String targetId, String declaredSource, ResolvedConnection connection,
                                MonitoringTarget.Scope scope) {
        @Override public String toString() { return "SourceBinding[" + targetId + ", " + declaredSource + "]"; }
    }

    @Override public String toString() { return "ResolvedTarget[" + display.id() + ", " + reason + "]"; }
}
