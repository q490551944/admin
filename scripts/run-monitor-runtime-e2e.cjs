const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const root = path.resolve(__dirname, "..");
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const run = path.join(root, "target", `monitor-runtime-e2e-${crypto.randomUUID()}`);
const token = crypto.randomBytes(32).toString("hex");
let app, test, baseURL, stopping;
function exited(child) {
  return child.exitCode !== null || child.signalCode !== null ? Promise.resolve() : new Promise(resolve => child.once("exit", resolve));
}
async function waitExit(child, millis) {
  let timer;
  try { await Promise.race([exited(child), new Promise(resolve => { timer = setTimeout(resolve, millis); })]); }
  finally { clearTimeout(timer); }
}
async function kill(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  child.kill();
  await waitExit(child, 5000);
  if (child.exitCode === null && child.signalCode === null) {
    child.kill("SIGKILL"); await waitExit(child, 5000);
  }
}
function stop() {
  return stopping ||= (async () => {
    await kill(test);
    if (app && app.exitCode === null && app.signalCode === null && baseURL) {
      try {
        await fetch(`${baseURL}/__monitor_test/finish`, { method: "POST", headers: { "X-Monitor-Test-Token": token }, signal: AbortSignal.timeout(3000) });
        await waitExit(app, 30000);
      } catch { /* The JVM hook/Ryuk also own cleanup on a failed startup or forced exit. */ }
    }
    await kill(app);
  })();
}
for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, async () => { await stop(); process.exit(signal === "SIGINT" ? 130 : 143); });

(async () => {
  fs.mkdirSync(run, { recursive: true });
  try {
    const cpFile = process.env.MONITOR_E2E_CLASSPATH_FILE || ["target/monitor-e2e-classpath.txt", "target/chat-e2e-classpath.txt"]
      .map(file => path.join(root, file)).find(file => fs.existsSync(file));
    if (!cpFile) throw new Error("Compile test classes and build the test classpath first; see docs/middleware-monitoring-runtime-browser-tests.md");
    const cp = [path.join(root, "target/classes"), path.join(root, "target/test-classes"), fs.readFileSync(cpFile, "utf8").trim()].join(path.delimiter);
    const ready = path.join(run, "ready.json"), args = path.join(run, "java.args");
    fs.writeFileSync(args, ["-Xmx512m", "-cp", JSON.stringify(cp.replaceAll("\\", "/")),
      "com.hpj.admin.monitor.MonitoringRuntimeE2eApplication", JSON.stringify(ready.replaceAll("\\", "/"))].join("\n"));
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
    if (process.env.GITHUB_ACTIONS) console.log(`::add-mask::${token}`);
    const log = fs.openSync(path.join(run, "app.log"), "w");
    app = spawn(java, [`@${args}`], { cwd: root, windowsHide: true, stdio: ["ignore", log, log], env: { ...process.env, MONITOR_E2E_CONTROL_TOKEN: token } });
    fs.closeSync(log); app.on("error", error => { app.launchError = error; });
    const deadline = Date.now() + 240000;
    while (!fs.existsSync(ready)) {
      if (app.launchError) throw app.launchError;
      if (app.exitCode !== null) throw new Error(`Owned real runtime exited with ${app.exitCode}; logs: ${run}`);
      if (Date.now() > deadline) throw new Error(`Owned real runtime readiness timed out; logs: ${run}`);
      await delay(250);
    }
    const metadata = JSON.parse(fs.readFileSync(ready, "utf8")); baseURL = metadata.baseURL;
    if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseURL) || metadata.chatEnabled !== false || metadata.monitorEnabled !== true || metadata.periodic !== true
      || metadata.types.join(",") !== "MYSQL,REDIS" || metadata.ordinaryIntervalMillis !== 15000 || metadata.capacityIntervalMillis !== 60000) throw new Error("Unexpected real runtime isolation/defaults");
    test = spawn(process.execPath, [require.resolve("@playwright/test/cli"), "test", "--config=playwright.monitor-runtime.config.cjs", ...process.argv.slice(2)], {
      cwd: root, windowsHide: true, stdio: "inherit", env: { ...process.env, MONITOR_E2E_URL: baseURL, MONITOR_E2E_CONTROL_TOKEN: token,
        MONITOR_RUNTIME_RUN: run, MONITOR_NATIVE_REFERENCES: JSON.stringify({ mysqlBufferPoolBytes: metadata.mysqlBufferPoolNativeBytes, redisMaxmemoryBytes: metadata.redisNativeMaxmemoryBytes }) }
    });
    process.exitCode = await new Promise((resolve, reject) => { test.once("exit", code => resolve(code ?? 1)); test.once("error", reject); });
  } catch (error) { console.error(error); process.exitCode = 1; }
  finally {
    await stop();
    const cleanupFile = path.join(run, "cleanup.json");
    const cleanup = fs.existsSync(cleanupFile) ? JSON.parse(fs.readFileSync(cleanupFile, "utf8")) : {};
    if (app?.exitCode !== 0 || cleanup.closed !== true || cleanup.sentinelsUnchanged !== true || cleanup.ownedSources !== 2) {
      console.error("Owned runtime cleanup/sentinel verification did not complete"); process.exitCode = 1;
    }
    console.log(`Real monitoring browser artifacts: ${run}`);
  }
})();
