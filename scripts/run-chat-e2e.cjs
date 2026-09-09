const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const net = require("node:net");
const crypto = require("node:crypto");

const root = path.resolve(__dirname, "..");
const run = path.join(root, "target", `chat-e2e-${crypto.randomUUID()}`);
fs.mkdirSync(run, { recursive: true });
const children = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function port() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const selected = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return selected;
}
function launch(executable, args, name, env = {}) {
  const log = fs.openSync(path.join(run, `${name}.log`), "w");
  const child = spawn(executable, args, { cwd: root, env: { ...process.env, ...env }, windowsHide: true,
    stdio: ["ignore", log, log] });
  fs.closeSync(log);
  child.on("error", error => { child.launchError = error; });
  children.push(child);
  return child;
}
async function waitFor(check, child, description) {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    if (child.launchError) throw child.launchError;
    if (child.exitCode !== null) throw new Error(`${description} exited with ${child.exitCode}; logs: ${run}`);
    if (await check()) return;
    await delay(250);
  }
  throw new Error(`${description} readiness timed out; logs: ${run}`);
}

(async () => {
  try {
    const storagePort = await port(), consolePort = await port();
    const storageURL = `http://127.0.0.1:${storagePort}`;
    const access = "chat-e2e-user", secret = crypto.randomUUID();
    const storage = launch(process.env.MINIO_BIN || "minio", ["server", path.join(run, "objects"),
      "--address", `127.0.0.1:${storagePort}`, "--console-address", `127.0.0.1:${consolePort}`], "minio",
      { MINIO_ROOT_USER: access, MINIO_ROOT_PASSWORD: secret, MINIO_BROWSER: "off" });
    await waitFor(async () => { try { return (await fetch(`${storageURL}/minio/health/live`)).ok; } catch { return false; } }, storage, "MinIO");
    const classpath = [path.join(root, "target/classes"), path.join(root, "target/test-classes"),
      fs.readFileSync(path.join(root, "target/chat-e2e-classpath.txt"), "utf8").trim()].join(path.delimiter);
    const ready = path.join(run, "ready.json");
    const argsFile = path.join(run, "java.args");
    fs.writeFileSync(argsFile, ["-Xmx768m", "-cp", JSON.stringify(classpath.replaceAll("\\", "/")),
      "com.hpj.admin.chat.ChatE2eApplication", `--chat.e2e.ready-file=${ready.replaceAll("\\", "/")}`].join("\n"));
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
    const app = launch(java, [`@${argsFile}`], "app", { CHAT_E2E_MINIO_ENDPOINT: storageURL,
      CHAT_E2E_MINIO_ACCESS_KEY: access, CHAT_E2E_MINIO_SECRET_KEY: secret });
    await waitFor(async () => fs.existsSync(ready), app, "E2E application");
    const { baseURL } = JSON.parse(fs.readFileSync(ready, "utf8"));
    const test = spawn(process.execPath, [require.resolve("@playwright/test/cli"), "test"], {
      cwd: root, windowsHide: true, stdio: "inherit", env: { ...process.env, CHAT_E2E_URL: baseURL }
    });
    children.push(test);
    process.exitCode = await new Promise((resolve, reject) => { test.on("exit", code => resolve(code ?? 1)); test.on("error", reject); });
  } catch (error) { console.error(error); process.exitCode = 1; }
  finally {
    for (const child of children.reverse()) {
      if (child.exitCode === null) {
        const exited = new Promise(resolve => child.once("exit", resolve)); child.kill();
        await Promise.race([exited, delay(5000)]);
        if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
      }
    }
    // Only remove the fresh, owned object-data directory. Keep logs and reports for diagnosis.
    const objects = path.resolve(run, "objects");
    if (!objects.startsWith(path.resolve(root, "target") + path.sep)) throw new Error("Unexpected cleanup target");
    fs.rmSync(objects, { recursive: true, force: true, maxRetries: 5, retryDelay: 250 });
    console.log(`E2E logs: ${run}`);
  }
})();
