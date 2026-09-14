const { test, expect } = require("@playwright/test");

const API = "/api/monitor/v1";
const PASSWORD = "Monitor-Test-26!";
const ALLOWED = "monitor-allowed";
const DENIED = "monitor-denied";
const sensitive = /jdbc:|password\s*=|SQLSyntaxError|SQLException|SELECT\s+.+\s+FROM|java\.sql|org\.springframework|stack\s*trace|Injected E2E employee database outage|IDENTITY_UNAVAILABLE|CSRF_INVALID/i;

async function control(request, state) {
  const token = process.env.MONITOR_E2E_CONTROL_TOKEN;
  if (!token) throw new Error("Use npm run test:monitor:e2e to start the isolated monitoring test application");
  const response = await request.post("/__monitor_test/control", {
    headers: { "X-Monitor-Test-Token": token }, data: state
  });
  expect(response.status()).toBe(200);
  return response.json();
}

function sessionResponse(page, method, status) {
  return page.waitForResponse(response => new URL(response.url()).pathname ===
    `${API}/sessions${method === "POST" ? "" : "/current"}` &&
    response.request().method() === method && response.status() === status);
}

async function submitLogin(page, username = ALLOWED, password = PASSWORD, status = 201, keyboard = false) {
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  const response = sessionResponse(page, "POST", status);
  if (keyboard) await page.getByLabel("密码", { exact: true }).press("Enter");
  else await page.getByRole("button", { name: "登录", exact: true }).click();
  return response;
}

async function expectIdentity(page) {
  await expect(page).toHaveURL(/\/monitor\/index\.html(?:[?#]|$)/);
  await expect(page.getByRole("heading", { name: "监控入口", exact: true })).toBeVisible();
  await expect(page.locator("#identity-name")).toHaveText(ALLOWED);
  await expect(page.getByRole("button", { name: "退出登录", exact: true })).toBeVisible();
}

async function login(page, keyboard = false) {
  await page.goto("/monitor/login.html");
  const current = sessionResponse(page, "GET", 200);
  await submitLogin(page, ALLOWED, PASSWORD, 201, keyboard);
  await current;
  await expectIdentity(page);
}

async function expectSafeError(page, message) {
  await expect(page.locator("#status-panel")).toContainText(message);
  await expect(page.locator("#identity-name")).not.toBeVisible();
  const visible = await page.locator("body").innerText();
  expect(visible).not.toMatch(sensitive);
  expect(visible).not.toContain(PASSWORD);
}

async function screenshot(page, testInfo, name) {
  const file = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  await testInfo.attach(name, { path: file, contentType: "image/png" });
}

async function expectFitsViewport(page, controls) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const viewport = page.viewportSize();
  for (const element of controls) {
    await expect(element).toBeVisible();
    const box = await element.boundingBox();
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
  }
}

test.beforeEach(async ({ request }) => { await control(request, { reset: true }); });
test.afterEach(async ({ request }) => { await control(request, { reset: true }); });

test("anonymous page and directory entries check the real session before login", async ({ page }) => {
  for (const entry of ["/monitor/index.html", "/monitor", "/monitor/"]) {
    const anonymous = sessionResponse(page, "GET", 401);
    await page.goto(entry);
    await anonymous;
    await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  }
  await expect(page.getByLabel("用户名", { exact: true })).toBeVisible();
  await expect(page.getByLabel("密码", { exact: true })).toHaveAttribute("type", "password");
  await expect(page.locator("#identity-name")).not.toBeVisible();
});

test("allowlisted employee logs in with chat disabled, survives reload and signs out", async ({ page, context }) => {
  expect((await context.request.get("/api/chat/v1/sessions/current")).status()).toBe(403);
  await login(page, true);
  const revalidated = sessionResponse(page, "GET", 200);
  await page.reload();
  await revalidated;
  await expectIdentity(page);
  expect((await context.request.get(`${API}/sessions/current`)).status()).toBe(200);
  const logout = sessionResponse(page, "DELETE", 204);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await logout;
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  expect((await context.request.get(`${API}/sessions/current`)).status()).toBe(401);
  await page.goto("/monitor/index.html");
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
});

for (const [description, username, password] of [
  ["incorrect password", ALLOWED, "incorrect-password"],
  ["disabled employee", "monitor-disabled", PASSWORD],
  ["unknown employee", "does-not-exist", PASSWORD]
]) {
  test(`${description} receives the same safe credential error`, async ({ page }) => {
    await page.goto("/monitor/login.html");
    await submitLogin(page, username, password, 401);
    await expectSafeError(page, "用户名或密码错误");
    await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
    await expect(page.getByRole("button", { name: "登录", exact: true })).toBeEnabled();
  });
}

test("201 login is followed by allowlist validation and a denied account cannot enter", async ({ page, request }) => {
  await page.goto("/monitor/login.html");
  const forbidden = sessionResponse(page, "GET", 403);
  await submitLogin(page, DENIED);
  await forbidden;
  await expectSafeError(page, "无查看权限");
  await expect(page.getByRole("link", { name: "返回监控入口", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "返回监控入口", exact: true }).click();
  await expectSafeError(page, "无查看权限");
  // Authorization is a server decision on every check, including after an administrator changes the list.
  await control(request, { allowedUserIds: [1, 2] });
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expect(page.locator("#identity-name")).toHaveText(DENIED);
  await control(request, { allowedUserIds: [1] });
  await page.reload();
  await expectSafeError(page, "无查看权限");
  const logout = sessionResponse(page, "DELETE", 204);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await logout;
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  await submitLogin(page);
  await expectIdentity(page);
});

test("identity outage preserves a session for retry and still permits logout", async ({ page, request, context }) => {
  await login(page);
  await control(request, { outage: true });
  const unavailable = sessionResponse(page, "GET", 503);
  await page.reload();
  await unavailable;
  await expectSafeError(page, "认证服务暂不可用");
  await control(request, { outage: false });
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expectIdentity(page);

  await control(request, { outage: true });
  await page.reload();
  await expectSafeError(page, "认证服务暂不可用");
  const logout = sessionResponse(page, "DELETE", 204);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await logout;
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  await control(request, { outage: false });
  expect((await context.request.get(`${API}/sessions/current`)).status()).toBe(401);
});

test("login outage has a safe message and credentials can be retried after recovery", async ({ page, request }) => {
  await page.goto("/monitor/login.html");
  await control(request, { outage: true });
  await submitLogin(page, ALLOWED, PASSWORD, 503);
  await expectSafeError(page, "认证服务暂不可用");
  await control(request, { outage: false });
  await submitLogin(page);
  await expectIdentity(page);
});

test("disabled monitoring is a recoverable feature state without redirecting to chat", async ({ page, request }) => {
  await login(page);
  await control(request, { monitorEnabled: false });
  const disabled = sessionResponse(page, "GET", 404);
  await page.reload();
  await disabled;
  await expectSafeError(page, "监控功能未启用");
  await expect(page).toHaveURL(/\/monitor\//);
  await control(request, { monitorEnabled: true });
  await page.getByRole("link", { name: "返回监控入口", exact: true }).click();
  await expectIdentity(page);
});

test("real browser writes require fresh CSRF after login and logout clears authorization", async ({ page }) => {
  await page.goto("/monitor/login.html");
  const before = await page.evaluate(async api => (await fetch(`${api}/csrf-token`)).json(), API);
  const missingLogin = await page.evaluate(async ({ api, username, password }) => {
    const response = await fetch(`${api}/sessions`, { method: "POST", body: new URLSearchParams({ username, password }) });
    return { status: response.status, body: await response.json() };
  }, { api: API, username: ALLOWED, password: PASSWORD });
  expect(missingLogin).toMatchObject({ status: 403, body: { code: "CSRF_INVALID" } });
  await submitLogin(page);
  await expectIdentity(page);
  const result = await page.evaluate(async ({ api, oldToken }) => {
    const token = await (await fetch(`${api}/csrf-token`)).json();
    const probe = async headers => (await fetch(`${api}/security-probe`, { method: "POST", headers })).status;
    return {
      rotated: token.token !== oldToken.token,
      missing: await probe({}),
      stale: await probe({ [oldToken.headerName]: oldToken.token }),
      fresh: await probe({ [token.headerName]: token.token }),
      missingLogout: (await fetch(`${api}/sessions/current`, { method: "DELETE" })).status,
      current: (await fetch(`${api}/sessions/current`)).status,
      storage: JSON.stringify({ ...localStorage, ...sessionStorage }),
      token: token.token
    };
  }, { api: API, oldToken: before });
  expect(result).toMatchObject({ rotated: true, missing: 403, stale: 403, fresh: 200, missingLogout: 403, current: 200 });
  expect(result.storage).not.toContain(PASSWORD);
  expect(result.storage).not.toContain(result.token);
  const logout = sessionResponse(page, "DELETE", 204);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await logout;
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
});

test("a second tab rechecks the shared cookie session after another tab signs out", async ({ page, context }) => {
  await login(page);
  const second = await context.newPage();
  await second.goto("/monitor/index.html");
  await expectIdentity(second);
  await page.bringToFront();
  const logout = sessionResponse(page, "DELETE", 204);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await logout;
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  await second.bringToFront();
  await expect(second).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  await second.reload();
  await expect(second.getByLabel("用户名", { exact: true })).toBeVisible();
  expect((await context.request.get(`${API}/sessions/current`)).status()).toBe(401);
});

test("revoked employee identity disappears on the next entry check", async ({ page, request }) => {
  await login(page);
  await control(request, { userId: 1, enabled: false });
  const expired = sessionResponse(page, "GET", 401);
  await page.reload();
  await expired;
  await expect(page).toHaveURL(/\/monitor\/login\.html(?:[?#]|$)/);
  await expect(page.locator("#identity-name")).not.toBeVisible();
});

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
  test(`login, entry and safe error fit the ${viewport.width}px viewport`, async ({ page, request }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.goto("/monitor/login.html");
    await expectFitsViewport(page, [page.getByLabel("用户名", { exact: true }),
      page.getByLabel("密码", { exact: true }), page.getByRole("button", { name: "登录", exact: true })]);
    await screenshot(page, testInfo, `login-${viewport.width}`);
    await submitLogin(page);
    await expectIdentity(page);
    await expectFitsViewport(page, [page.locator("#identity-name"),
      page.getByRole("button", { name: "退出登录", exact: true })]);
    await screenshot(page, testInfo, `entry-${viewport.width}`);
    await control(request, { outage: true });
    await page.reload();
    await expectSafeError(page, "认证服务暂不可用");
    await expectFitsViewport(page, [page.getByRole("button", { name: "重试", exact: true }),
      page.getByRole("button", { name: "退出登录", exact: true })]);
    await screenshot(page, testInfo, `error-${viewport.width}`);
  });
}
