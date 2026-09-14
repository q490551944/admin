const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const root = path.resolve(__dirname, "..");
const run = path.join(root, "target", `monitor-e2e-${crypto.randomUUID()}`);
fs.mkdirSync(run, { recursive: true });
const children = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let stopping = false;

async function stop() {
  if (stopping) return;
  stopping = true;
  for (const child of children.reverse()) {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = new Promise(resolve => child.once("exit", resolve));
      child.kill();
      await Promise.race([exited, delay(5000)]);
      if (child.exitCode === null && child.signalCode === null) {
        child.kill("SIGKILL");
        await Promise.race([exited, delay(5000)]);
      }
    }
  }
}
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.once(signal, async () => {
    await stop();
    process.exit(signal === "SIGINT" ? 130 : 143);
  });
}

(async () => {
  try {
    const classpathFile = process.env.MONITOR_E2E_CLASSPATH_FILE ||
      ["target/monitor-e2e-classpath.txt", "target/chat-e2e-classpath.txt"]
        .map(file => path.join(root, file)).find(file => fs.existsSync(file));
    if (!classpathFile) throw new Error("Compile the test application and build target/monitor-e2e-classpath.txt first; see docs/middleware-monitoring-browser-tests.md");
    const classpath = [path.join(root, "target/classes"), path.join(root, "target/test-classes"),
      fs.readFileSync(classpathFile, "utf8").trim()].join(path.delimiter);
    const ready = path.join(run, "ready.json");
    const argsFile = path.join(run, "java.args");
    fs.writeFileSync(argsFile, ["-Xmx512m", "-cp", JSON.stringify(classpath.replaceAll("\\", "/")),
      "com.hpj.admin.monitor.MonitoringE2eApplication",
      JSON.stringify(`--monitor.e2e.ready-file=${ready.replaceAll("\\", "/")}`)].join("\n"));
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin",
      process.platform === "win32" ? "java.exe" : "java") : "java";
    const controlToken = crypto.randomBytes(32).toString("hex");
    const log = fs.openSync(path.join(run, "app.log"), "w");
    const app = spawn(java, [`@${argsFile}`], { cwd: root, windowsHide: true,
      env: { ...process.env, MONITOR_E2E_CONTROL_TOKEN: controlToken }, stdio: ["ignore", log, log] });
    fs.closeSync(log);
    app.on("error", error => { app.launchError = error; });
    children.push(app);
    const deadline = Date.now() + 120000;
    while (!fs.existsSync(ready)) {
      if (app.launchError) throw app.launchError;
      if (app.exitCode !== null) throw new Error(`Monitoring application exited with ${app.exitCode}; logs: ${run}`);
      if (Date.now() >= deadline) throw new Error(`Monitoring application readiness timed out; logs: ${run}`);
      await delay(250);
    }
    const { baseURL, chatEnabled, monitorEnabled } = JSON.parse(fs.readFileSync(ready, "utf8"));
    if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseURL) || chatEnabled !== false || monitorEnabled !== true) {
      throw new Error("Unexpected monitoring test application configuration");
    }
    const test = spawn(process.execPath, [require.resolve("@playwright/test/cli"), "test",
      "--config=playwright.monitor.config.cjs", ...process.argv.slice(2)], {
      cwd: root, windowsHide: true, stdio: "inherit", env: { ...process.env, MONITOR_E2E_URL: baseURL,
        MONITOR_E2E_CONTROL_TOKEN: controlToken }
    });
    children.push(test);
    process.exitCode = await new Promise((resolve, reject) => {
      test.on("exit", code => resolve(code ?? 1));
      test.on("error", reject);
    });
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  } finally {
    await stop();
    console.log(`Monitoring E2E logs: ${run}`);
  }
})();
