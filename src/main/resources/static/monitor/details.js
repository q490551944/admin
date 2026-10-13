(function (global) {
  "use strict";
  const monitor = typeof module === "object" && module.exports ? require("./overview.js") : global.MonitorOverview;
  const CATEGORIES = { CPU: "CPU", MEMORY: "内存", STORAGE: "存储与容量", SPECIFIC: "专属指标" };
  const list = value => Array.isArray(value) ? value : [];
  function category(metric) {
    const definition = metric.latestAttempt?.definition, key = definition?.key || "";
    if (/(^|\.)cpu(\.|$)/.test(key)) return "CPU";
    if (/(^|\.)(memory|heap|cache|buffer_pool|wiredtiger)(\.|$)/.test(key) || ["CACHE", "JVM_HEAP"].includes(definition?.scope?.kind)) return "MEMORY";
    if (metric.kind === "CAPACITY" || /(^|\.)(storage|disk|filesystem|fs|store|volume|aof|rdb)(\.|$)/.test(key)) return "STORAGE";
    return "SPECIFIC";
  }
  function validate(data, target) {
    if (!data || !Number.isSafeInteger(data.generation) || !Number.isFinite(Date.parse(data.serverTime)) ||
      data.target?.target?.id !== target.id || data.target.target.type !== target.type ||
      !monitor.TYPES.includes(target.type) || !Array.isArray(data.metrics) || data.metrics.length > 5000) throw new Error("Inconsistent target detail");
    return data;
  }
  function ageDetail(data, serverMillis) {
    return { ...data, target: monitor.ageSnapshot({ targets: [data.target] }, serverMillis).targets[0],
      metrics: data.metrics.map(metric => monitor.ageMetric(metric, serverMillis)) };
  }
  function createReader({ load, onData, onError, onLoading = () => {} }) {
    let version = 0, pending;
    function stop() { version++; pending?.controller.abort(); pending = null; }
    function read(target) {
      if (pending?.id === target.id && pending.type === target.type) return pending.promise;
      stop(); const job = { id: target.id, type: target.type, version, controller: new AbortController() };
      const valid = () => version === job.version && !job.controller.signal.aborted;
      job.promise = Promise.resolve().then(() => load(target.id, job.controller.signal)).then(data => {
        if (valid()) onData(validate(data, target));
      }).catch(error => { if (valid()) onError(error); }).finally(() => { if (pending === job) pending = null; });
      pending = job; onLoading(); return job.promise;
    }
    return Object.freeze({ read, stop });
  }
  function create({ client, onSessionError }) {
    const document = global.document, byId = id => document.getElementById(id), root = byId("target-detail");
    let selected, cached, current, serverNow, lastSignature, limit = 200, suspended = false, directoryGeneration, loading = false;
    function node(tag, className, text) {
      const element = document.createElement(tag); if (className) element.className = className;
      if (text !== undefined) element.textContent = text; return element;
    }
    function badge(state, labels) {
      const element = node("span", "state-badge", (labels[state] || ["?", "未知"]).join(" "));
      element.dataset.state = state; return element;
    }
    function message(text) { const element = byId("detail-message"); element.textContent = text; element.hidden = false; }
    function heading(target) {
      byId("detail-title").textContent = monitor.NAMES[target.type] + " · " + target.name;
      byId("detail-identity").textContent = "目标 " + target.id + " · 连接来源 " + (list(target.connectionSources).join("、") || "暂无连接");
    }
    function render(data) {
      const opened = new Set([...root.querySelectorAll(".detail-metric")].filter(card => card.querySelector("details")?.open).map(card => JSON.stringify([card.dataset.metricKey, card.dataset.bindingId])));
      current = data; heading(data.target.target);
      const states = byId("detail-states"); states.replaceChildren();
      if (data.target.schedulingState === "ACTIVE") states.append(badge(monitor.serviceState(data.target), monitor.SERVICE), badge(monitor.collectionState(data.target), monitor.COLLECTION));
      else states.append(node("span", "state-badge", data.target.schedulingState === "DISABLED" ? "— 未启用" : data.target.schedulingState === "CONFIGURATION_MISSING" ? "! 配置问题" : "… 正在解析"));
      for (const observation of list(data.target.serviceObservations)) {
        states.append(node("small", "muted", `${observation.source} · ${monitor.SERVICE[observation.availability]?.[1] || "未知"} · 探测 ${monitor.time(observation.lastObservation?.sampledAt)}`));
      }
      const rules = data.freshnessRules || {};
      byId("detail-periods").textContent = `普通指标 ${(rules.ordinaryIntervalMillis || 15000) / 1000} 秒周期 · 容量指标 ${(rules.capacityIntervalMillis || 60000) / 1000} 秒独立周期 · 各项按原生对象展示，不跨范围相加`;
      const groups = byId("detail-metrics"); groups.replaceChildren();
      const shown = data.metrics.slice(0, limit);
      for (const [key, label] of Object.entries(CATEGORIES)) {
        const section = node("section", "metric-category"); section.dataset.category = key;
        section.append(node("h3", "", label));
        const all = data.metrics.filter(metric => category(metric) === key), visible = shown.filter(metric => category(metric) === key);
        if (!all.length) section.append(node("p", "muted", "当前快照未报告此类指标；具体能力以原生采集结果为准。"));
        else if (!visible.length) section.append(node("p", "muted", "此类别还有指标，使用下方按钮继续显示。"));
        const grid = node("div", "detail-metric-grid");
        for (const metric of visible) {
          const view = monitor.metricView(metric); if (!view) continue;
          const card = node("article", "detail-metric"); card.dataset.metricKey = view.definition.key; card.dataset.bindingId = metric.bindingId; card.dataset.valueState = view.state;
          card.append(node("h4", "metric-label", view.definition.label), node("strong", "metric-value", view.text + (view.unit ? " " + view.unit : "")));
          if (view.note) card.append(node("p", "metric-note", view.note));
          if (view.state === "NO_DATA") card.append(node("p", "metric-note", "暂无数据"));
          card.append(node("p", "metric-scope", "对象范围 " + monitor.scopeText(view.definition.scope)),
            node("p", "muted", "单位 " + (view.unit || ({ TEXT: "文本", BOOLEAN: "是否" }[view.definition.unit]) || "未知")),
            node("p", "muted", (view.state === "LAST_SUCCESS" || view.state === "EXPIRED" ? "上次成功 " : "数值采样 ") + monitor.time(view.sample?.sampledAt)),
            node("p", "muted", "最近尝试 " + monitor.time(metric.latestAttempt.sampledAt)));
          const freshness = node("p", "muted live-freshness"); freshness.dataset.validUntil = view.validUntil || ""; card.append(freshness);
          if (metric.kind === "CAPACITY") card.append(node("p", "metric-period", `容量独立采样 · ${(rules.capacityIntervalMillis || 60000) / 1000} 秒周期`));
          const explanation = node("details", "metric-explanation"); explanation.append(node("summary", "", "来源与计算说明"),
            node("p", "muted", "指标 " + view.definition.key), node("p", "muted", "绑定 " + metric.bindingId + " · " + metric.source),
            node("p", "", "原生来源 " + view.definition.source), node("p", "", "计算说明 " + view.definition.calculation));
          explanation.open = opened.has(JSON.stringify([view.definition.key, metric.bindingId]));
          card.append(explanation); grid.append(card);
        }
        section.append(grid); groups.append(section);
      }
      byId("detail-count").textContent = `当前显示 ${Math.min(limit, data.metrics.length)} / ${data.metrics.length} 项原生指标`;
      byId("detail-more").hidden = data.metrics.length <= limit;
      const collection = byId("detail-collection"); collection.replaceChildren();
      if (!data.target.collections.length) collection.append(node("p", "muted", "暂无采集尝试。能力尚未确认，不表示资源为零。"));
      for (const item of data.target.collections) collection.append(node("p", "muted", `${item.source} · 绑定 ${item.bindingId} · ${item.kind === "CAPACITY" ? "容量" : "普通"} · ${monitor.COLLECTION[item.status]?.[1] || "等待采样"}${item.reason ? " · " + (monitor.REASONS[item.reason] || "暂无数据") : ""} · 最近完成 ${monitor.time(item.lastAttempt?.completedAt)}`));
      byId("detail-time").textContent = "详情读取 " + monitor.time(data.serverTime);
    }
    function recompute(force = true) {
      if (!cached || suspended) return;
      const millis = serverNow(), data = ageDetail(cached, millis);
      const signature = JSON.stringify([limit, monitor.serviceState(data.target), monitor.collectionState(data.target), data.metrics.map(metric => metric.valueState)]);
      if (force || signature !== lastSignature) { render(data); lastSignature = signature; }
      else current = data;
      monitor.updateFreshness(root, millis);
      byId("detail-time").textContent = "详情读取 " + monitor.time(data.serverTime) + ` · 已过去 ${Math.floor((millis - Date.parse(data.serverTime)) / 1000)} 秒`;
    }
    const reader = createReader({ load: (id, signal) => client.detail(id, signal),
      onLoading() { loading = true; root.setAttribute("aria-busy", "true"); message(cached ? "正在更新当前实例详情…" : "正在读取当前实例详情…"); },
      onData(data) {
        loading = false; cached = data; serverNow = monitor.createServerClock(data.serverTime); root.setAttribute("aria-busy", "false"); byId("detail-message").hidden = true; recompute();
      },
      onError(error) {
        loading = false; root.setAttribute("aria-busy", "false");
        if (["SESSION_EXPIRED", "ACCESS_DENIED", "IDENTITY_UNAVAILABLE", "MONITOR_DISABLED"].includes(error.code)) { onSessionError(error); return; }
        message(error.code === "TARGET_NOT_FOUND" ? "! 此实例已不在当前目录，请重新选择实例。" : cached ? "! 当前实例详情读取失败，可重试；已保留该实例上次结果。" : "! 当前实例详情读取失败，暂无数据；可重试。");
      }
    });
    function clearValues() {
      cached = null; current = null; serverNow = null; lastSignature = null;
      for (const id of ["detail-states", "detail-metrics", "detail-collection"]) byId(id).replaceChildren();
      for (const id of ["detail-periods", "detail-count", "detail-time"]) byId(id).textContent = "";
      byId("detail-more").hidden = true;
    }
    function open(row) {
      selected = row.target; suspended = false; limit = 200; clearValues(); heading(selected); root.hidden = false;
      byId("detail-title").focus({ preventScroll: true }); root.scrollIntoView({ block: "start" }); return reader.read(selected);
    }
    function close() { reader.stop(); selected = null; clearValues(); root.hidden = true; byId("detail-title").textContent = ""; byId("detail-identity").textContent = ""; }
    function sync(data) {
      const changed = directoryGeneration !== undefined && directoryGeneration !== data.generation; directoryGeneration = data.generation;
      if (!selected) return;
      const row = data.rows.find(row => row.target.id === selected.id && row.target.type === selected.type);
      if (!row) { reader.stop(); clearValues(); message("! 此实例已不在当前目录，请关闭详情后重新选择。"); return; }
      selected = row.target;
      if (changed) { reader.stop(); clearValues(); heading(selected); }
    }
    byId("detail-close").addEventListener("click", () => {
      const id = selected?.id; close();
      [...document.querySelectorAll(".detail-button")].find(button => button.dataset.openTarget === id)?.focus();
    });
    byId("detail-retry").addEventListener("click", () => { if (selected && !suspended) reader.read(selected); });
    byId("detail-more").addEventListener("click", () => { limit = Math.min(5000, limit + 200); recompute(); });
    return Object.freeze({ open, close, sync, recompute, current: () => current,
      refresh() { if (selected && !suspended) return reader.read(selected); },
      suspend() {
        suspended = true; reader.stop(); root.hidden = true; root.setAttribute("aria-busy", "false");
        if (loading) message(cached ? "详情读取已取消，保留上次结果；可重读详情。" : "详情尚未读取，可重读详情。");
        loading = false;
      },
      resume() { suspended = false; if (selected) { root.hidden = false; heading(selected); recompute(); } }
    });
  }
  const api = Object.freeze({ CATEGORIES, category, validate, ageDetail, createReader, create });
  if (typeof module === "object" && module.exports) module.exports = api;
  else global.MonitorDetails = api;
})(typeof window === "object" ? window : globalThis);
