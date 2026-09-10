package com.hpj.admin.monitor.security;

/** Fixed public errors; authentication exceptions and database messages never become response fields. */
public record MonitoringError(String code, String message) {
    public static final MonitoringError SESSION_EXPIRED = new MonitoringError("SESSION_EXPIRED", "登录已失效，请重新登录");
    public static final MonitoringError INVALID_CREDENTIALS = new MonitoringError("INVALID_CREDENTIALS", "用户名或密码错误，或账号已停用");
    public static final MonitoringError ACCESS_DENIED = new MonitoringError("ACCESS_DENIED", "此账号没有监控查看权限");
    public static final MonitoringError CSRF_INVALID = new MonitoringError("CSRF_INVALID", "请求凭证无效，请刷新页面后重试");
    public static final MonitoringError IDENTITY_UNAVAILABLE = new MonitoringError("IDENTITY_UNAVAILABLE", "身份服务暂不可用");
    public static final MonitoringError MONITOR_DISABLED = new MonitoringError("MONITOR_DISABLED", "监控功能未启用");
}
