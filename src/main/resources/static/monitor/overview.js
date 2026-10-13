(function (global) {
  "use strict";
  const TYPES = ["MYSQL", "REDIS", "KAFKA", "MONGODB", "ELASTICSEARCH", "MINIO"];
  const NAMES = { MYSQL: "MySQL", REDIS: "Redis", KAFKA: "Kafka", MONGODB: "MongoDB", ELASTICSEARCH: "Elasticsearch", MINIO: "MinIO" };
  const SERVICE = { AVAILABLE: ["●", "可用"], DEGRADED: ["△", "降级"], CONNECTION_FAILED: ["!", "连接失败"], UNKNOWN: ["?", "未知"] };
  const COLLECTION = { SUCCESS: ["●", "采集成功"], PARTIAL: ["△", "部分可采"], UNAUTHORIZED: ["!", "未授权"],
    UNSUPPORTED: ["—", "不支持"], BUSY: ["…", "繁忙"], FAILED: ["!", "采集失败"], STALE: ["◷", "已过期"], WAITING: ["…", "等待采样"] };
  const REASONS = { UNSUPPORTED: "不支持", UNAUTHORIZED: "未授权", NOT_APPLICABLE: "不适用", WAITING_SAMPLE: "等待采样",
    NO_REQUESTS: "暂无请求", BUSY: "繁忙", TIMEOUT: "超时", TLS_FAILED: "TLS 校验失败", FAILED: "采集失败", INVALID_VALUE: "原生值无效",
    NO_COMMITTED_OFFSET: "未提交偏移量", PARTIAL_SCOPE: "范围不完整" };
  const SCOPES = { PROCESS: "进程", JVM_HEAP: "JVM 堆", CACHE: "缓存", DATABASE: "数据库", INDEX: "索引", TOPIC: "Topic",
    PARTITION: "分区", CONSUMER_GROUP: "消费者组", BUCKET: "桶", NODE: "节点", CLUSTER: "集群", FILESYSTEM: "文件系统",
    NODE_REPORTED_OS: "节点报告的 OS", CONFIGURED_SCOPE: "配置范围", ENDPOINT: "连接端点" };
  const UNITS = { COUNT: "次 / 项", COUNT_PER_SECOND: "/秒", BYTES: "B", BYTES_PER_SECOND: "B/秒", SECONDS: "秒",
    MILLISECONDS: "ms", PERCENT: "%", CORES: "核", TEXT: "", BOOLEAN: "" };
  const list = value => Array.isArray(value) ? value : [];
  const reason = value => REASONS[value] || "暂无数据";

  function serviceState(row) {
    const states = list(row.serviceObservations).map(item => item.availability);
    for (const state of ["CONNECTION_FAILED", "DEGRADED", "UNKNOWN"]) if (states.includes(state)) return state;
    return states.length && states.every(state => state === "AVAILABLE") ? "AVAILABLE" : "UNKNOWN";
  }
  function collectionState(row) {
    const states = list(row.collections).map(item => item.status);
    for (const state of ["FAILED", "UNAUTHORIZED", "STALE", "BUSY", "PARTIAL"]) if (states.includes(state)) return state;
    if (states.includes("UNSUPPORTED")) return states.every(state => state === "UNSUPPORTED") ? "UNSUPPORTED" : "PARTIAL";
    if (states.includes("WAITING")) return "WAITING";
    return states.length && states.every(state => state === "SUCCESS") ? "SUCCESS" : "WAITING";
  }
  function model(catalog, snapshot) {
    if (!catalog || !snapshot || !Number.isSafeInteger(catalog.generation) || catalog.generation !== snapshot.generation ||
        !Array.isArray(catalog.types) || !Array.isArray(snapshot.targets) || !Number.isFinite(Date.parse(snapshot.serverTime))) {
      throw new Error("Invalid monitoring snapshot");
    }
    const directory = new Map(catalog.types.flatMap(type => list(type.targets).map(target => [target.id, target])));
    const seen = new Set();
    const rows = snapshot.targets.map(row => {
      const target = row.target;
      const declaration = directory.get(target?.id);
      if (!target || !TYPES.includes(target.type) || !declaration || declaration.type !== target.type || seen.has(target.id)) {
        throw new Error("Inconsistent monitoring directory");
      }
      seen.add(target.id);
      return { ...row, service: serviceState(row), collection: collectionState(row) };
    });
    if (seen.size !== directory.size) throw new Error("Incomplete monitoring directory");
    const summary = { AVAILABLE: 0, DEGRADED: 0, CONNECTION_FAILED: 0, UNKNOWN: 0 };
    const configuration = { disabled: 0, missing: 0, resolving: 0, collectionProblems: 0 };
    for (const row of rows) {
      if (row.schedulingState === "ACTIVE" && row.target.configurationStatus === "CONFIGURED") {
        summary[row.service]++;
        if (!["SUCCESS", "WAITING"].includes(row.collection)) configuration.collectionProblems++;
      } else if (row.schedulingState === "DISABLED") configuration.disabled++;
      else if (row.schedulingState === "CONFIGURATION_MISSING") configuration.missing++;
      else configuration.resolving++;
    }
    return { rows, summary, configuration, generation: snapshot.generation, serverTime: snapshot.serverTime,
      types: TYPES.map(type => ({ type, name: NAMES[type], rows: rows.filter(row => row.target.type === type) })) };
  }
  function metricView(metric) {
    const latest = metric.latestAttempt;
    if (!latest?.definition) return null;
    const sample = latest.missingReason ? metric.lastSuccess : latest;
    const value = sample?.value;
    let text = "—";
    if (value !== null && value !== undefined) {
      if (typeof value === "boolean") text = value ? "是" : "否";
      else if (typeof value === "number") text = Number.isFinite(value) ? value.toLocaleString("zh-CN", { maximumFractionDigits: 3 }) : "—";
      else text = String(value);
    }
    return { definition: latest.definition, text, unit: UNITS[latest.definition.unit] || "", sample,
      note: latest.missingReason ? (sample ? "上次成功值 · " : "") + reason(latest.missingReason) : "", source: metric.source };
  }
  function time(value) {
    return Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "暂无采样";
  }
  function scopeText(scope) {
    return [SCOPES[scope?.kind] || "对象", scope?.id, scope?.nodeId && "节点 " + scope.nodeId].filter(Boolean).join(" · ");
  }
  function create({ client, onSessionError }) {
    const document = global.document;
    const byId = id => document.getElementById(id);
    const root = byId("overview");
    let controller;
    let generation = 0;
    let current;
    function node(tag, className, text) {
      const element = document.createElement(tag);
      if (className) element.className = className;
      if (text !== undefined) element.textContent = text;
      return element;
    }
    function badge(state, labels) {
      const [icon, label] = labels[state];
      const element = node("span", "state-badge", icon + " " + label);
      element.dataset.state = state;
      return element;
    }
    function render(data) {
      current = data;
      const summary = byId("service-summary");
      summary.replaceChildren();
      for (const [state, count] of Object.entries(data.summary)) {
        const item = node("div", "summary-item");
        item.dataset.service = state;
        item.append(badge(state, SERVICE), node("strong", "", count));
        summary.append(item);
      }
      const cfg = data.configuration;
      byId("configuration-summary").textContent = `未启用 ${cfg.disabled} · 配置问题 ${cfg.missing} · 正在解析 ${cfg.resolving} · 采集问题 ${cfg.collectionProblems}（与服务状态分别统计）`;
      byId("overview-time").textContent = "快照读取 " + time(data.serverTime);
      const cards = byId("type-cards");
      cards.replaceChildren();
      for (const type of data.types) {
        const card = node("a", "type-card");
        card.href = "#instances";
        card.dataset.type = type.type;
        const active = type.rows.filter(row => row.schedulingState === "ACTIVE");
        const missing = type.rows.filter(row => row.schedulingState === "CONFIGURATION_MISSING").length;
        const resolving = type.rows.some(row => row.schedulingState === "RESOLVING");
        const label = active.length ? `已配置 ${active.length} 个实例` : missing ? "! 配置问题" : resolving ? "… 正在解析" : "— 未启用";
        card.append(node("h3", "", type.name), node("p", "", label));
        if (active.length) card.append(node("small", "muted", `可用 ${active.filter(row => row.service === "AVAILABLE").length} · 异常/未知 ${active.filter(row => row.service !== "AVAILABLE").length}`));
        cards.append(card);
      }
      byId("instance-count").textContent = `${data.rows.length} 个声明实例`;
      const instances = byId("instance-list");
      instances.replaceChildren();
      if (!data.rows.length) instances.append(node("p", "empty-state", "尚未配置监控实例。启用并配置连接后，实例会出现在这里。"));
      for (const row of data.rows) {
        const article = node("article", "instance-card");
        article.dataset.targetId = row.target.id;
        const header = node("div", "instance-heading");
        const title = node("div", "instance-title");
        title.append(node("small", "muted", NAMES[row.target.type]), node("h3", "", row.target.name));
        header.append(title);
        const states = node("div", "instance-states");
        if (row.schedulingState === "ACTIVE") states.append(badge(row.service, SERVICE), badge(row.collection, COLLECTION));
        else states.append(node("span", "state-badge", row.schedulingState === "DISABLED" ? "— 未启用" : row.schedulingState === "CONFIGURATION_MISSING" ? "! 配置问题" : "… 正在解析"));
        header.append(states);
        article.append(header);
        const metrics = node("div", "core-metrics");
        const previews = list(row.coreMetrics).map(metricView).filter(Boolean);
        for (const view of previews) {
          const item = node("div", "core-metric");
          item.append(node("p", "metric-label", view.definition.label), node("strong", "metric-value", view.text + (view.unit ? " " + view.unit : "")),
            node("small", "metric-scope", scopeText(view.definition.scope)), node("small", "muted", time(view.sample?.sampledAt)));
          if (view.note) item.append(node("small", "metric-note", view.note));
          metrics.append(item);
        }
        if (!previews.length) metrics.append(node("p", "muted", row.schedulingState === "ACTIVE" ? "暂无核心数值 · 等待首样本或采集结果" : "未安排采集"));
        article.append(metrics);
        const detail = node("details", "instance-entry");
        detail.append(node("summary", "", "查看实例"));
        detail.append(node("p", "muted", "目标 " + row.target.id), node("p", "muted", "连接来源 " + (list(row.target.connectionSources).join("、") || "暂无连接")));
        for (const collection of list(row.collections)) {
          detail.append(node("p", "muted", `${collection.source} · ${collection.kind === "CAPACITY" ? "容量" : "普通"} · ${COLLECTION[collection.status]?.[1] || "等待采样"} · ${time(collection.lastAttempt?.completedAt)}`));
        }
        article.append(detail);
        instances.append(article);
      }
      byId("overview-message").hidden = true;
      root.setAttribute("aria-busy", "false");
    }
    function stop() {
      generation++;
      controller?.abort();
      controller = null;
      current = null;
      root.hidden = true;
      for (const id of ["service-summary", "type-cards", "instance-list"]) byId(id).replaceChildren();
      byId("configuration-summary").textContent = "";
      byId("overview-time").textContent = "";
    }
    async function start() {
      stop();
      root.hidden = false;
      root.setAttribute("aria-busy", "true");
      const message = byId("overview-message");
      message.hidden = false;
      message.textContent = "正在读取当前快照…";
      controller = new AbortController();
      const signal = controller.signal;
      const version = generation;
      try {
        let data;
        for (let attempt = 0; attempt < 2; attempt++) {
          const [catalog, snapshot] = await Promise.all([client.catalog(signal), client.snapshots(signal)]);
          if (catalog?.generation === snapshot?.generation) { data = model(catalog, snapshot); break; }
        }
        if (!data) throw new Error("Directory changed");
        if (version === generation && !signal.aborted) render(data);
      } catch (error) {
        if (version !== generation || signal.aborted) return;
        if (["SESSION_EXPIRED", "ACCESS_DENIED", "IDENTITY_UNAVAILABLE", "MONITOR_DISABLED"].includes(error.code)) { onSessionError(error); return; }
        message.textContent = "! 当前快照读取失败，请重试页面。";
        root.setAttribute("aria-busy", "false");
      }
    }
    return Object.freeze({ start, stop, current: () => current });
  }
  const api = Object.freeze({ TYPES, NAMES, SERVICE, COLLECTION, REASONS, SCOPES, model, serviceState, collectionState, metricView, time, scopeText, create });
  if (typeof module === "object" && module.exports) module.exports = api;
  else global.MonitorOverview = api;
})(typeof window === "object" ? window : globalThis);
