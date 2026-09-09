const { test, expect } = require("@playwright/test");
const path = require("node:path");
const fs = require("node:fs");

async function login(browser, baseURL, username, sharedContext) {
  const context = sharedContext || await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  await page.goto("/chat/index.html?mode=live");
  await expect(page).toHaveURL(/login\.html/);
  await page.locator('[name="username"]').fill(username);
  await page.locator('[name="password"]').fill("chat-e2e-only");
  await page.locator("#loginButton").click();
  await expect(page).toHaveURL(/index\.html/);
  return { context, page };
}

async function request(page, url, options = {}) {
  const id = await page.evaluate(() => sessionStorage.getItem("chat.sessionId"));
  return page.context().request.fetch(url, { ...options, headers: { ...options.headers, "X-Chat-Session": id } });
}

async function send(page, text) {
  await page.locator("#messageInput").fill(text);
  await expect(page.locator("#sendButton")).toBeEnabled();
  await page.locator("#messageInput").press("Enter");
  const message = page.locator(".message-row").filter({ hasText: text });
  await expect(message).toHaveCount(1);
  await expect(message.locator(".message-status")).toHaveClass(/settled/);
}

async function openConversation(page, id) {
  await page.locator(`.conversation-item[data-conversation-id="${id}"]`).click();
  await expect(page.locator(`.conversation-item[data-conversation-id="${id}"]`)).toHaveAttribute("aria-current", "true");
}

async function upload(page) {
  // A renamed WebP may be declared as PNG by the browser; its detected MIME must win.
  await page.locator("#imageInput").setInputFiles({ name: "sample.png", mimeType: "image/png",
    buffer: fs.readFileSync(path.resolve("src/test/resources/chat/sample.webp")) });
  await expect(page.locator("#uploadPreview")).toBeVisible();
  await expect(page.locator("#sendButton")).toBeEnabled();
  await page.locator("#sendButton").click();
  const row = page.locator(".message-row.own").filter({ has: page.locator(".image-message") }).last();
  await expect(row.locator(".message-status")).toHaveClass(/settled/);
}

test("removed lazy thumbnails release URLs during rapid conversation switches", async ({ page }) => {
  // 仅测试响应暴露模块内部状态，用于核对浏览器实际持有的 URL 数量。
  await page.route("**/chat/app.js", route => route.fulfill({ contentType: "text/javascript",
    body: fs.readFileSync(path.resolve("src/main/resources/static/chat/app.js"), "utf8")
      + "\nwindow.chatMediaTest = { api, state, normalizeMessage, renderMessages, clearMediaUrls, mediaUrls, mediaBlobs };" }));
  await page.goto("/chat/index.html?mode=demo");
  await expect(page.locator(".message-row").first()).toBeVisible();
  const result = await page.evaluate(async bytes => {
    const { api, state, normalizeMessage, renderMessages, clearMediaUrls, mediaUrls, mediaBlobs } = window.chatMediaTest;
    const blob = new Blob([new Uint8Array(bytes)], { type: "image/webp" });
    api.imageBlob = async () => blob;
    state.usingDemo = false;
    state.currentUser = { id: "1", name: "Alice" };
    state.conversations = [{ id: "1", name: "Room 1" }, { id: "2", name: "Room 2" }];
    state.messages.clear();
    clearMediaUrls();
    for (let i = 1; i <= 40; i++) {
      state.activeConversationId = "1";
      state.messages.set("1", [normalizeMessage({ id: String(i), senderId: "2", type: "IMAGE", attachmentId: String(i) }, "1")]);
      renderMessages();
      state.activeConversationId = "2";
      renderMessages();
      await new Promise(resolve => setTimeout(resolve, 0));
    }
    const result = { urls: mediaUrls.size, cachedBlobs: mediaBlobs.size,
      visibleImages: document.querySelectorAll(".image-message img").length };
    state.activeConversationId = "1";
    renderMessages();
    return result;
  }, [...fs.readFileSync(path.resolve("src/test/resources/chat/sample.webp"))]);
  expect(result).toEqual({ urls: 0, cachedBlobs: 32, visibleImages: 0 });
  await expect.poll(() => page.locator(".image-message img").evaluate(image => image.naturalWidth)).toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => window.chatMediaTest.mediaUrls.size)).toBe(0);
});

test("three users complete public chat, images, history, private isolation, recovery and dissolution", async ({ browser, baseURL }) => {
  const sessions = [];
  try {
    const alice = await login(browser, baseURL, "alice"); sessions.push(alice);
    await expect(alice.page.locator("#conversationEmpty")).toBeVisible();
    await alice.page.locator("#createRoomButton").click();
    await alice.page.locator("#roomNameInput").fill("E2E team");
    await alice.page.locator("#submitRoomButton").click();
    await expect(alice.page.locator("#activeTitle")).toHaveText("E2E team");
    const publicId = await alice.page.locator(".conversation-item.active").getAttribute("data-conversation-id");
    const bob = await login(browser, baseURL, "bob"); sessions.push(bob);
    const carol = await login(browser, baseURL, "carol"); sessions.push(carol);
    await openConversation(bob.page, publicId);
    await send(alice.page, "public hello 😀 <script>window.chatInjected=true</script>");
    await expect(bob.page.locator(".message-bubble")).toContainText(["public hello 😀 <script>"]);
    expect(await bob.page.evaluate(() => window.chatInjected)).toBeUndefined();
    await upload(bob.page);
    await expect(alice.page.locator(".image-message img")).toHaveCount(1);
    await expect.poll(() => alice.page.locator(".image-message img").evaluate(image => image.naturalWidth)).toBeGreaterThan(0);
    await openConversation(alice.page, publicId);
    await alice.page.locator("#conversationInfoButton").click();
    await expect(alice.page.locator("#mediaGrid img")).toHaveCount(1);
    await expect.poll(() => alice.page.locator("#mediaGrid img").evaluate(image => image.naturalWidth)).toBeGreaterThan(0);
    await alice.page.locator("#closeDetailsButton").click();
    const [original] = await Promise.all([alice.context.waitForEvent("page"), alice.page.locator(".image-message img").click()]);
    await original.waitForLoadState();
    await expect(original).toHaveURL(/^blob:/);
    await expect.poll(() => original.locator("img").evaluate(image => image.naturalWidth)).toBeGreaterThan(0);
    await original.close();
    for (const { page } of [alice, bob]) {
      await page.reload(); await openConversation(page, publicId);
      await expect(page.locator(".image-message img")).toHaveCount(1);
      await expect(page.locator(".message-bubble")).toContainText(["public hello"]);
    }

    // Exercise browser connectivity, then assert history catch-up without duplicates.
    await bob.context.setOffline(true);
    await expect(bob.page.locator("#connectionDot")).not.toHaveClass(/\bconnected\b/);
    await send(alice.page, "missed while offline");
    await bob.context.setOffline(false);
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "missed while offline" })).toHaveCount(1);

    // Repeat the same real browser SEND frame, including its request ID, over the socket.
    await alice.page.evaluate(() => {
      const originalSend = WebSocket.prototype.send;
      WebSocket.prototype.send = function (frame) {
        const result = originalSend.call(this, frame);
        if (typeof frame === "string" && frame.startsWith("SEND\n") && frame.includes("duplicate browser send")) {
          WebSocket.prototype.send = originalSend;
          originalSend.call(this, frame);
        }
        return result;
      };
    });
    await send(alice.page, "duplicate browser send");
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "duplicate browser send" })).toHaveCount(1);

    // A server rejection produces a failed message; retry resends its stable request ID.
    await alice.page.route("**/attachments/*/upload", route => route.fulfill({ status: 503,
      contentType: "application/json", body: JSON.stringify({ code: "STORAGE_UNAVAILABLE", message: "test upload failure" }) }));
    await alice.page.locator("#imageInput").setInputFiles(path.resolve("src/test/resources/chat/sample.webp"));
    await alice.page.locator("#sendButton").click();
    const failed = alice.page.locator(".message-status.failed");
    await expect(failed).toHaveCount(1);
    await alice.page.unroute("**/attachments/*/upload");
    await failed.getByRole("button", { name: "重试" }).click();
    await expect(alice.page.locator(".message-status.failed")).toHaveCount(0);
    await expect(bob.page.locator(".image-message img")).toHaveCount(2);

    for (let index = 0; index < 35; index++) await send(alice.page, `history-row-${String(index).padStart(2, "0")}`);
    await bob.page.reload(); await openConversation(bob.page, publicId);
    await expect(bob.page.locator(".message-row")).toHaveCount(30);
    await bob.page.locator("#messageScroller").evaluate(element => { element.scrollTop = 0; });
    await expect(bob.page.locator(".message-row")).toHaveCount(40);
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "history-row-00" })).toHaveCount(1);
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "duplicate browser send" })).toHaveCount(1);
    await expect(bob.page.locator(".sender-line:visible")).toHaveCount(40);
    expect(await bob.page.locator(".sender-line time").evaluateAll(times =>
      times.every(time => time.dateTime && !Number.isNaN(Date.parse(time.dateTime))))).toBe(true);
    await expect.poll(() => bob.page.locator("#messageScroller").evaluate(element => element.scrollTop)).toBeGreaterThan(0);

    await alice.page.locator("#newConversationButton").click();
    await alice.page.locator("#peopleSearch").fill("bob");
    await alice.page.locator("#peopleList button").filter({ hasText: "bob" }).click();
    await alice.page.locator("#submitDirectButton").click();
    await expect(alice.page.locator("#activeTitle")).toHaveText("bob");
    const privateId = await alice.page.locator(".conversation-item.active").getAttribute("data-conversation-id");
    // Opening the same colleague again preserves the unique conversation and selected state.
    await alice.page.locator("#newConversationButton").click();
    await alice.page.locator("#peopleSearch").fill("bob");
    await alice.page.locator("#peopleList button").filter({ hasText: "bob" }).click();
    await alice.page.locator("#submitDirectButton").click();
    await expect(alice.page.locator(`.conversation-item[data-conversation-id="${privateId}"]`)).toHaveCount(1);
    await alice.page.locator('[data-filter="PUBLIC_ROOM"]').click();
    await expect(alice.page.locator(`.conversation-item[data-conversation-id="${privateId}"]`)).toHaveCount(0);
    await alice.page.locator('[data-filter="DIRECT_MESSAGE"]').click();
    await expect(alice.page.locator(`.conversation-item[data-conversation-id="${privateId}"]`)).toHaveCount(1);
    await alice.page.locator('[data-filter="all"]').click();
    await send(alice.page, "private hello");
    const incomingDirect = bob.page.locator(`.conversation-item[data-conversation-id="${privateId}"]`);
    await expect(incomingDirect).toContainText("alice");
    await expect(incomingDirect).toContainText("private hello");
    await expect(incomingDirect.locator(".unread-badge")).toHaveText("1");
    await expect(bob.page.locator("#activeTitle")).toHaveText("E2E team");
    await expect(carol.page.locator(`.conversation-item[data-conversation-id="${privateId}"]`)).toHaveCount(0);
    await openConversation(bob.page, privateId);
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "private hello" })).toHaveCount(1);
    await expect(incomingDirect.locator(".unread-badge")).toHaveCount(0);
    await send(alice.page, "private live follow-up");
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "private live follow-up" })).toHaveCount(1);
    await openConversation(bob.page, publicId);
    await send(alice.page, "private while viewing public room");
    await expect(incomingDirect.locator(".unread-badge")).toHaveText("1");
    await openConversation(bob.page, privateId);
    await expect(bob.page.locator(".message-bubble").filter({ hasText: "private while viewing public room" })).toHaveCount(1);
    await upload(bob.page);
    // Keep the upload pending while switching the sender to another conversation.
    let resumeUpload;
    const uploadStarted = new Promise(resolve => {
      bob.page.route("**/attachments/*/upload", route => {
        resumeUpload = () => route.continue();
        resolve();
      });
    });
    await bob.page.locator("#imageInput").setInputFiles(path.resolve("src/test/resources/chat/sample.webp"));
    await bob.page.locator("#sendButton").click();
    await uploadStarted;
    await openConversation(bob.page, publicId);
    await resumeUpload();
    await expect(alice.page.locator(".image-message img")).toHaveCount(2);
    await bob.page.unroute("**/attachments/*/upload");
    await openConversation(bob.page, privateId);
    await expect(bob.page.locator(".image-message img")).toHaveCount(2);
    await expect(bob.page.locator(".message-status.failed")).toHaveCount(0);
    const imageUrl = await alice.page.locator(".image-message img").first().getAttribute("data-source");
    await carol.page.reload();
    await expect(carol.page.locator(`.conversation-item[data-conversation-id="${privateId}"]`)).toHaveCount(0);
    expect((await request(carol.page, `/api/chat/v1/conversations/${privateId}/messages`)).status()).toBe(403);
    expect((await request(carol.page, imageUrl)).status()).toBe(403);
    expect((await request(carol.page, imageUrl.replace("/thumbnail", "/content"))).status()).toBe(403);
    // Declared oversize uploads are rejected before storage or message creation.
    const csrf = await (await request(alice.page, "/api/chat/v1/csrf-token")).json();
    expect((await request(alice.page, `/api/chat/v1/conversations/${privateId}/attachments`, { method: "POST",
      headers: { [csrf.headerName]: csrf.token }, data: { fileName: "oversize.png", contentType: "image/png", sizeBytes: 10485761 }
    })).status()).toBe(413);

    await openConversation(alice.page, publicId); await openConversation(bob.page, publicId);
    await bob.page.locator("#conversationInfoButton").click();
    await expect(bob.page.locator("#dissolveRoomButton")).toBeHidden();
    await bob.page.locator("#closeDetailsButton").click();
    await alice.page.locator("#conversationInfoButton").click();
    await alice.page.locator("#dissolveRoomButton").click();
    await expect(alice.page.locator("#confirmRoomName")).toHaveText("E2E team");
    await alice.page.locator("#confirmDissolveButton").click();
    await expect(bob.page.locator(`.conversation-item[data-conversation-id="${publicId}"]`)).toHaveCount(0);
    expect((await request(bob.page, `/api/chat/v1/conversations/${publicId}/messages`)).status()).toBe(404);
    await alice.page.screenshot({ path: "target/chat-e2e-final.png", fullPage: true });
  } finally { for (const { context } of sessions) await context.close(); }
});

test("two windows sharing browser cookies keep separate accounts through login, images, reload and logout", async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL });
  try {
    const alice = await login(browser, baseURL, "alice", context);
    const aliceId = await alice.page.evaluate(() => sessionStorage.getItem("chat.sessionId"));
    await alice.page.locator("#createRoomButton").click();
    await alice.page.locator("#roomNameInput").fill("Window session isolation");
    await alice.page.locator("#submitRoomButton").click();
    await expect(alice.page.locator("#activeTitle")).toHaveText("Window session isolation");
    const roomId = await alice.page.locator(".conversation-item.active").getAttribute("data-conversation-id");
    const bob = await login(browser, baseURL, "bob", context);
    expect(await bob.page.evaluate(() => sessionStorage.getItem("chat.sessionId"))).not.toBe(aliceId);
    await openConversation(bob.page, roomId);
    await send(alice.page, "alice remains logged in");
    await expect(bob.page.locator(".message-bubble")).toContainText(["alice remains logged in"]);
    await send(bob.page, "bob has his own identity");
    await expect(alice.page.locator(".message-bubble")).toContainText(["bob has his own identity"]);
    await upload(alice.page);
    await expect.poll(() => bob.page.locator(".image-message img").evaluate(image => image.naturalWidth)).toBeGreaterThan(0);
    for (const [session, username] of [[alice, "alice"], [bob, "bob"]]) {
      await session.page.reload();
      expect((await (await request(session.page, "/api/chat/v1/sessions/current")).json()).username).toBe(username);
      await openConversation(session.page, roomId);
    }
    await alice.page.locator("#sessionButton").click();
    await expect(alice.page).toHaveURL(/login\.html/);
    expect(await alice.page.evaluate(() => sessionStorage.getItem("chat.sessionId"))).toBeNull();
    await send(bob.page, "bob continues after alice logs out");
    await bob.page.reload();
    expect((await (await request(bob.page, "/api/chat/v1/sessions/current")).json()).username).toBe("bob");
    expect((await context.cookies()).filter(cookie => cookie.name === "JSESSIONID")).toHaveLength(0);
  } finally { await context.close(); }
});
