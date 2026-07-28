import assert from "node:assert/strict";
import { createHash, webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);

test("real AgentHarness consumes Android-native OpenRouter chunks, tools, errors, and stop", async () => {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({
    console,
    crypto: webcrypto,
  });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  JSON.parse(call(context, "bootstrapJson"));

  const text = await runScenario(context, "text");
  assert.equal(text.status.expectationMet, true, JSON.stringify(text.status));
  assert.equal(text.requests.length, 1);
  assert.equal(text.requests[0].modelId, "deepseek/deepseek-v4-pro");
  assert.equal(text.requests[0].kind, "openrouter_chat_stream");
  assert.equal(JSON.stringify(text.requests).includes("apiKey"), false);
  assert.equal(JSON.stringify(text.requests).includes("Authorization"), false);

  const tool = await runScenario(context, "tool");
  assert.equal(tool.status.expectationMet, true, JSON.stringify(tool.status));
  assert.equal(tool.requests.length, 2);
  assert.equal(tool.requests[0].tools[0].function.name, "mobile_fixture_echo");
  assert.equal(tool.status.toolExecutionsStarted, 1);
  assert.equal(tool.status.toolExecutionsEnded, 1);

  const providerError = await runScenario(context, "provider_error");
  assert.equal(providerError.status.expectationMet, true, JSON.stringify(providerError.status));
  assert.equal(providerError.status.providerRequestsFailed, 1);
  assert.equal(providerError.status.providerError, "OpenRouter test provider failed");

  const stopped = await runScenario(context, "stop");
  assert.equal(stopped.status.expectationMet, true, JSON.stringify(stopped.status));
  assert.equal(stopped.status.providerCancellationsIssued, 1);
  assert.equal(stopped.cancellations.length, 1);
  assert.equal(stopped.status.lateProviderRequestsAfterStop, 0);
  assert.equal(stopped.status.lateToolStartsAfterStop, 0);

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("real AgentHarness exposes a user prompt through the same native Provider mailbox", async () => {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({
    console,
    crypto: webcrypto,
  });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  JSON.parse(call(context, "bootstrapJson"));

  JSON.parse(call(
    context,
    "startNativeOpenRouterPromptJson",
    JSON.stringify("Explain this Android project in one sentence."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let requests = [];
  for (let attempt = 0; attempt < 100 && requests.length === 0; attempt += 1) {
    requests = JSON.parse(call(context, "drainNativeProviderRequestsJson"));
    if (requests.length === 0) {
      await new Promise((resolve) => setImmediate(resolve));
    }
  }
  assert.equal(requests.length, 1);
  assert.equal(
    requests[0].messages.some((message) =>
      message.role === "user" &&
      message.content === "Explain this Android project in one sentence."
    ),
    true,
  );
  assert.equal(JSON.stringify(requests).includes("apiKey"), false);

  pushChunk(context, requests[0].id, {
    id: "gen-prompt",
    choices: [{
      delta: { content: "A local Android coding agent." },
      finish_reason: "stop",
    }],
  });
  completeRequest(context, requests[0].id, "gen-prompt");
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
    if (status.terminal) {
      assert.equal(status.kind, "prompt");
      assert.equal(status.expectationMet, true, JSON.stringify(status));
      assert.equal(status.finalText, "A local Android coding agent.");
      assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
      return;
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Provider prompt did not settle");
});

test("one phone-local task keeps the same real AgentHarness context across three prompts", async () => {
  const context = await bootRuntime();
  const taskId = "task-m1-three-turn";

  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Create an Android release plan."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  assertCompleteFilePreconditionSchema(first);
  assert.deepEqual(userTexts(first), ["Create an Android release plan."]);
  finishTextRequest(context, first, "Draft: build, test, release.");
  let status = await waitForTerminal(context);
  assert.equal(status.taskId, taskId);
  assert.equal(status.turnCount, 1);
  assert.equal(status.sessionEntryCount, 2);

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Reduce it to three steps."),
  ));
  const second = await nextProviderRequest(context);
  assert.deepEqual(
    userTexts(second),
    ["Create an Android release plan.", "Reduce it to three steps."],
  );
  assert.equal(
    second.messages.some((message) =>
      message.role === "assistant" &&
      message.content === "Draft: build, test, release."
    ),
    true,
  );
  finishTextRequest(context, second, "Three steps: build, test, release.");
  status = await waitForTerminal(context);
  assert.equal(status.turnCount, 2);
  assert.equal(status.sessionEntryCount, 4);

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Make the second step a Xiaomi 12X device test."),
  ));
  const third = await nextProviderRequest(context);
  assert.deepEqual(userTexts(third), [
    "Create an Android release plan.",
    "Reduce it to three steps.",
    "Make the second step a Xiaomi 12X device test.",
  ]);
  assert.equal(
    third.messages.some((message) =>
      message.role === "assistant" &&
      message.content === "Three steps: build, test, release."
    ),
    true,
  );
  finishTextRequest(
    context,
    third,
    "Three steps: build, test on Xiaomi 12X, release.",
  );
  status = await waitForTerminal(context);
  assert.equal(status.turnCount, 3);
  assert.equal(status.sessionEntryCount, 6);
  assert.equal(status.providerRequestsIssued, 1);
  assert.equal(status.commandError, null);

  const snapshot = JSON.parse(
    call(context, "nativeOpenRouterTaskSessionSnapshotJson"),
  );
  assert.equal(snapshot.taskId, taskId);
  assert.equal(snapshot.turnCount, 3);
  assert.equal(snapshot.entries.length, 6);
  assert.deepEqual(
    snapshot.entries
      .filter((entry) => entry.type === "message")
      .map((entry) => entry.message.role),
    ["user", "assistant", "user", "assistant", "user", "assistant"],
  );

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("phone-local task exposes real project terminal and test tools through the Android mailbox", async () => {
  const context = await bootRuntime();
  const taskId = "task-e5b3-project-tools";

  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Inspect the selected Android project."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  const commandTool = first.tools.find(
    (tool) => tool.function.name === "run_command",
  );
  const testsTool = first.tools.find(
    (tool) => tool.function.name === "run_tests",
  );
  assert.ok(commandTool, "phone-local task must expose run_command");
  assert.ok(testsTool, "phone-local task must expose run_tests");
  assert.deepEqual(commandTool.function.parameters.required, ["command"]);
  assert.equal(commandTool.function.parameters.properties.timeoutMillis.maximum, 900_000);
  assert.equal(testsTool.function.parameters.properties.outputLimitBytes.maximum, 1_048_576);
  assert.equal(
    first.messages.some((message) =>
      message.role === "system" &&
      message.content.includes("device_files_commit_changes") &&
      message.content.includes("Never claim")
    ),
    true,
  );

  finishToolCall(
    context,
    first,
    "call-run-command",
    "run_command",
    { command: "pwd && printf terminal-ok", timeoutMillis: 12_000 },
  );
  const commandRequest = await nextNativeToolRequest(context);
  assert.equal(commandRequest.kind, "android_project_tool");
  assert.equal(commandRequest.toolName, "run_command");
  assert.deepEqual(commandRequest.arguments, {
    command: "pwd && printf terminal-ok",
    timeoutMillis: 12_000,
  });
  resolveNativeTool(context, commandRequest.id, {
    ok: true,
    kind: "terminal",
    stdout: "/workspace\nterminal-ok",
    stderr: "",
    exitCode: 0,
    timedOut: false,
    stopped: false,
    outputTruncated: false,
    durationMillis: 8,
    fileChanges: { state: "none" },
  });
  const afterCommand = await nextProviderRequest(context);
  assert.match(JSON.stringify(afterCommand.messages), /terminal-ok/);
  finishTextRequest(context, afterCommand, "The project command completed.");
  let status = await waitForTerminal(context);
  assert.equal(status.toolExecutionsStarted, 1);
  assert.equal(status.toolExecutionsEnded, 1);

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Run its tests."),
  ));
  const second = await nextProviderRequest(context);
  finishToolCall(
    context,
    second,
    "call-run-tests",
    "run_tests",
    { command: "./gradlew test", outputLimitBytes: 65_536 },
  );
  const testRequest = await nextNativeToolRequest(context);
  assert.equal(testRequest.kind, "android_project_tool");
  assert.equal(testRequest.toolName, "run_tests");
  resolveNativeTool(context, testRequest.id, {
    ok: false,
    kind: "test",
    stdout: "1 test failed",
    stderr: "",
    exitCode: 1,
    timedOut: false,
    stopped: false,
    outputTruncated: false,
    durationMillis: 15,
    fileChanges: { state: "none" },
    errorCode: "PROJECT_COMMAND_FAILED",
    errorMessage: "Project command exited with a failure",
  });
  const afterTests = await nextProviderRequest(context);
  assert.match(JSON.stringify(afterTests.messages), /PROJECT_COMMAND_FAILED/);
  finishTextRequest(context, afterTests, "The project tests failed.");
  status = await waitForTerminal(context);
  assert.equal(status.toolExecutionsStarted, 1);
  assert.equal(status.toolExecutionsEnded, 1);
  assert.equal(status.toolRequestsResolved, 1);

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("a persisted Pi Session restores without replay and continues with full history", async () => {
  const firstProcess = await bootRuntime();
  const taskId = "task-m3-restored";
  const sessionId = "session-m3-restored";

  JSON.parse(call(
    firstProcess,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Remember that the device is a Xiaomi 12X."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(firstProcess);
  finishTextRequest(firstProcess, first, "I will remember the Xiaomi 12X.");
  await waitForTerminal(firstProcess);

  JSON.parse(call(
    firstProcess,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Also remember that the build channel is beta."),
  ));
  const second = await nextProviderRequest(firstProcess);
  finishTextRequest(firstProcess, second, "I will remember the beta channel.");
  await waitForTerminal(firstProcess);

  JSON.parse(call(
    firstProcess,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Finally remember that the codename is Psyche."),
  ));
  const third = await nextProviderRequest(firstProcess);
  finishTextRequest(firstProcess, third, "I will remember the Psyche codename.");
  await waitForTerminal(firstProcess);

  const snapshot = JSON.parse(
    call(firstProcess, "nativeOpenRouterTaskSessionSnapshotJson"),
  );
  assert.deepEqual(
    JSON.parse(call(firstProcess, "closeJson")),
    { ok: true, closed: true },
  );

  const secondProcess = await bootRuntime();
  const restored = JSON.parse(call(
    secondProcess,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  assert.equal(restored.taskId, taskId);
  assert.equal(restored.terminal, true);
  assert.equal(restored.turnCount, 3);
  assert.equal(restored.sessionEntryCount, 6);
  assert.deepEqual(
    JSON.parse(call(secondProcess, "drainNativeProviderRequestsJson")),
    [],
    "restore must not replay the persisted prompt",
  );

  JSON.parse(call(
    secondProcess,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Which device did I name?"),
  ));
  const continued = await nextProviderRequest(secondProcess);
  assert.deepEqual(userTexts(continued), [
    "Remember that the device is a Xiaomi 12X.",
    "Also remember that the build channel is beta.",
    "Finally remember that the codename is Psyche.",
    "Which device did I name?",
  ]);
  assert.equal(
    continued.messages.some((message) =>
      message.role === "assistant" &&
      message.content === "I will remember the Xiaomi 12X."
    ),
    true,
  );
  finishTextRequest(secondProcess, continued, "You named the Xiaomi 12X.");
  const terminal = await waitForTerminal(secondProcess);
  assert.equal(terminal.turnCount, 4);
  assert.equal(terminal.providerRequestsIssued, 1);
  assert.deepEqual(
    JSON.parse(call(secondProcess, "closeJson")),
    { ok: true, closed: true },
  );
});

test("Pi native images serialize to OpenRouter and restore only through attachment references", async () => {
  const firstProcess = await bootRuntime();
  const taskId = "task-e7-native-images";
  const sessionId = "session-e7-native-images";
  const firstImage = {
    attachmentId: "11111111-1111-4111-8111-111111111111",
    mimeType: "image/jpeg",
    data: "/9j/2Q==",
  };
  const duplicateFirstImage = {
    attachmentId: "33333333-3333-4333-8333-333333333333",
    mimeType: "image/jpeg",
    data: firstImage.data,
  };

  JSON.parse(call(
    firstProcess,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(""),
    JSON.stringify("openai/gpt-4.1-mini"),
    JSON.stringify(sessionId),
    "false",
    JSON.stringify("[]"),
    JSON.stringify(JSON.stringify([firstImage, duplicateFirstImage])),
  ));
  const first = await nextProviderRequest(firstProcess);
  const firstUser = first.messages.find((message) => message.role === "user");
  assert.deepEqual(firstUser.content, [
    {
      type: "image_url",
      image_url: { url: "data:image/jpeg;base64,/9j/2Q==" },
    },
    {
      type: "image_url",
      image_url: { url: "data:image/jpeg;base64,/9j/2Q==" },
    },
  ]);
  finishTextRequest(firstProcess, first, "The image contains a test fixture.");
  const firstStatus = await waitForTerminal(firstProcess);
  const firstEvents = JSON.stringify(firstStatus.events);
  assert.equal(firstEvents.includes(`attachment:${firstImage.attachmentId}`), true);
  assert.equal(firstEvents.includes(`attachment:${duplicateFirstImage.attachmentId}`), true);

  const snapshot = JSON.parse(call(firstProcess, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persisted = JSON.stringify(snapshot);
  assert.equal(persisted.includes(firstImage.data), false, "snapshot must not persist base64");
  assert.equal(persisted.includes(`attachment:${firstImage.attachmentId}`), true);
  assert.equal(persisted.includes(`attachment:${duplicateFirstImage.attachmentId}`), true);
  assert.equal(
    JSON.stringify(JSON.parse(call(firstProcess, "nativeOpenRouterScenarioStatusJson"))).includes(firstImage.data),
    false,
    "recorded Pi events must not expose base64 to Android",
  );
  assert.deepEqual(JSON.parse(call(firstProcess, "closeJson")), { ok: true, closed: true });

  const secondProcess = await bootRuntime();
  const restored = JSON.parse(call(
    secondProcess,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("openai/gpt-4.1-mini"),
    JSON.stringify("[]"),
    JSON.stringify(JSON.stringify([firstImage, duplicateFirstImage])),
  ));
  assert.equal(restored.terminal, true);
  assert.deepEqual(JSON.parse(call(secondProcess, "drainNativeProviderRequestsJson")), []);

  const secondImage = {
    attachmentId: "22222222-2222-4222-8222-222222222222",
    mimeType: "image/jpeg",
    data: firstImage.data,
  };
  JSON.parse(call(
    secondProcess,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Compare it with this image."),
    JSON.stringify(JSON.stringify([secondImage])),
  ));
  const continued = await nextProviderRequest(secondProcess);
  const users = continued.messages.filter((message) => message.role === "user");
  assert.equal(users.length, 2);
  assert.equal(users[0].content[0].image_url.url, "data:image/jpeg;base64,/9j/2Q==");
  assert.equal(users[1].content[0].text, "Compare it with this image.");
  assert.equal(users[1].content[1].image_url.url, "data:image/jpeg;base64,/9j/2Q==");
  finishTextRequest(secondProcess, continued, "The second fixture differs from the first.");
  await waitForTerminal(secondProcess);
  const secondSnapshot = JSON.parse(call(secondProcess, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persistedSecondSnapshot = JSON.stringify(secondSnapshot);
  assert.equal(persistedSecondSnapshot.includes(firstImage.data), false);
  assert.equal(persistedSecondSnapshot.includes(`attachment:${firstImage.attachmentId}`), true);
  assert.equal(persistedSecondSnapshot.includes(`attachment:${secondImage.attachmentId}`), true);
  assert.deepEqual(JSON.parse(call(secondProcess, "closeJson")), { ok: true, closed: true });

  const thirdProcess = await bootRuntime();
  const restoredDuplicateBytes = JSON.parse(call(
    thirdProcess,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    JSON.stringify(secondSnapshot.turnCount),
    JSON.stringify(JSON.stringify(secondSnapshot.entries)),
    JSON.stringify("openai/gpt-4.1-mini"),
    JSON.stringify("[]"),
    JSON.stringify(JSON.stringify([firstImage, duplicateFirstImage, secondImage])),
  ));
  assert.equal(restoredDuplicateBytes.terminal, true);
  assert.deepEqual(JSON.parse(call(thirdProcess, "drainNativeProviderRequestsJson")), []);
  assert.deepEqual(JSON.parse(call(thirdProcess, "closeJson")), { ok: true, closed: true });
});

test("text attachments stay as Pi Session metadata and read through the Android task tool", async () => {
  const firstProcess = await bootRuntime();
  const taskId = "task-e7-text-attachment";
  const sessionId = "session-e7-text-attachment";
  const attachment = {
    attachmentId: "55555555-5555-4555-8555-555555555555",
    displayName: "context.md",
    mimeType: "text/plain",
    byteSize: 42,
  };

  JSON.parse(call(
    firstProcess,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Summarize the attached context."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify(sessionId),
    "false",
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    JSON.stringify(JSON.stringify([attachment])),
  ));
  const first = await nextProviderRequest(firstProcess);
  assert.equal(first.tools.some((tool) => tool.function.name === "attachment_read"), true);
  assert.equal(first.messages[0].content.includes("attachment_read"), true);
  const decorated = userTexts(first).at(-1);
  assert.equal(decorated.includes("Summarize the attached context."), true);
  assert.equal(decorated.includes(attachment.attachmentId), true);
  assert.equal(decorated.includes("context.md"), true);

  finishToolCall(firstProcess, first, "call-attachment-read", "attachment_read", {
    attachmentId: attachment.attachmentId,
    offset: 0,
    limit: 16384,
  });
  const nativeRead = await nextNativeToolRequest(firstProcess);
  assert.equal(nativeRead.kind, "android_attachment_tool");
  assert.equal(nativeRead.toolName, "attachment_read");
  assert.deepEqual(nativeRead.arguments, {
    attachmentId: attachment.attachmentId,
    offset: 0,
    limit: 16384,
  });
  resolveNativeTool(firstProcess, nativeRead.id, {
    ok: true,
    attachmentId: attachment.attachmentId,
    displayName: attachment.displayName,
    mimeType: attachment.mimeType,
    offset: 0,
    nextOffset: 42,
    totalBytes: 42,
    eof: true,
    content: "The Android attachment tool returned this text.",
  });
  const second = await nextProviderRequest(firstProcess);
  assert.equal(
    second.messages.some((message) =>
      message.role === "tool" &&
      message.name === "attachment_read" &&
      message.content.includes("Android attachment tool returned this text")
    ),
    true,
  );
  finishTextRequest(firstProcess, second, "The attachment was read through Android.");
  await waitForTerminal(firstProcess);
  const snapshot = JSON.parse(call(firstProcess, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persisted = JSON.stringify(snapshot);
  assert.equal(persisted.includes("pi_mobile_text_attachments"), true);
  assert.equal(persisted.includes(attachment.attachmentId), true);
  assert.equal(persisted.includes("Android attachment tool returned this text"), true);
  assert.deepEqual(JSON.parse(call(firstProcess, "closeJson")), { ok: true, closed: true });

  const restoredProcess = await bootRuntime();
  const restored = JSON.parse(call(
    restoredProcess,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  assert.equal(restored.terminal, true);
  assert.deepEqual(JSON.parse(call(restoredProcess, "drainNativeProviderRequestsJson")), []);
  JSON.parse(call(
    restoredProcess,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("What file did I attach?"),
  ));
  const continued = await nextProviderRequest(restoredProcess);
  assert.equal(userTexts(continued)[0].includes(attachment.attachmentId), true);
  finishTextRequest(restoredProcess, continued, "You attached context.md.");
  await waitForTerminal(restoredProcess);
  assert.deepEqual(JSON.parse(call(restoredProcess, "closeJson")), { ok: true, closed: true });
});

test("image Session restore fails closed when private attachment bytes are unavailable", async () => {
  const context = await bootRuntime();
  assert.throws(
    () => call(
      context,
      "restoreNativeOpenRouterTaskSessionJson",
      JSON.stringify("task-e7-missing-image"),
      JSON.stringify("session-e7-missing-image"),
      JSON.stringify(1),
      JSON.stringify(JSON.stringify([{
        id: "entry-e7-user",
        parentId: null,
        timestamp: "2026-07-22T00:00:00.000Z",
        type: "message",
        message: {
          role: "user",
          content: [{
            type: "image",
            data: "attachment:33333333-3333-4333-8333-333333333333",
            mimeType: "image/jpeg",
          }],
        },
      }])),
      JSON.stringify("openai/gpt-4.1-mini"),
    ),
    /PI_MOBILE_IMAGE_SESSION_REFERENCE_MISSING/,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("a corrupt persisted Pi Session is rejected before any provider request", async () => {
  const context = await bootRuntime();
  assert.throws(
    () => call(
      context,
      "restoreNativeOpenRouterTaskSessionJson",
      JSON.stringify("task-m3-corrupt"),
      JSON.stringify("session-m3-corrupt"),
      JSON.stringify(1),
      JSON.stringify(JSON.stringify([{
        id: "orphan",
        parentId: "missing-parent",
        timestamp: "2026-07-20T00:00:00.000Z",
        type: "message",
        message: { role: "user", content: [{ type: "text", text: "old" }] },
      }])),
      JSON.stringify("deepseek/deepseek-v4-pro"),
    ),
    /PI_MOBILE_TASK_SESSION_PARENT_INVALID/,
  );
  assert.throws(
    () => call(context, "drainNativeProviderRequestsJson"),
    /PI_MOBILE_NATIVE_PROVIDER_SCENARIO_NOT_STARTED/,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("phone-local task queues native steer and follow-up on the active Pi run", async () => {
  const context = await bootRuntime();

  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-m1-queue"),
    JSON.stringify("Draft a migration plan."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  JSON.parse(call(
    context,
    "steerNativeOpenRouterTaskJson",
    JSON.stringify("Change the second step to device testing."),
  ));
  JSON.parse(call(
    context,
    "followUpNativeOpenRouterTaskJson",
    JSON.stringify("After that, summarize the final plan."),
    JSON.stringify(JSON.stringify([{
      attachmentId: "44444444-4444-4444-8444-444444444444",
      mimeType: "image/jpeg",
      data: "/9j/4A==",
    }])),
    JSON.stringify(JSON.stringify([{
      attachmentId: "66666666-6666-4666-8666-666666666666",
      displayName: "queued.md",
      mimeType: "text/plain",
      byteSize: 12,
    }])),
  ));

  finishTextRequest(context, first, "Initial migration plan.");
  const steered = await nextProviderRequest(context);
  assert.equal(
    userTexts(steered).at(-1),
    "Change the second step to device testing.",
  );
  finishTextRequest(context, steered, "Updated with device testing.");

  const followUp = await nextProviderRequest(context);
  const queuedUser = followUp.messages.filter((message) => message.role === "user").at(-1);
  assert.equal(queuedUser.content[0].text.includes("After that, summarize the final plan."), true);
  assert.equal(queuedUser.content[0].text.includes("queued.md"), true);
  assert.equal(queuedUser.content[1].image_url.url, "data:image/jpeg;base64,/9j/4A==");
  finishTextRequest(context, followUp, "Final plan: migrate, device-test, release.");

  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.turnCount, 1);
  assert.equal(status.providerRequestsIssued, 3);
  assert.equal(status.providerRequestsCompleted, 3);
  assert.equal(status.commandError, null);
  assert.equal(
    status.runEventTypes.filter((type) => type === "queue_update").length >= 2,
    true,
  );
  assert.equal(status.finalText, "Final plan: migrate, device-test, release.");
  assert.equal(
    JSON.stringify(JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson")))
      .includes("attachment:44444444-4444-4444-8444-444444444444"),
    true,
  );
  assert.equal(
    JSON.stringify(JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson")))
      .includes("66666666-6666-4666-8666-666666666666"),
    true,
  );

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("phone-local task stop cancels its active request and closes without late work", async () => {
  const context = await bootRuntime();

  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-m1-stop"),
    JSON.stringify("Run until stopped."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  await nextProviderRequest(context);
  JSON.parse(call(context, "abortNativeOpenRouterScenarioJson"));

  const cancellation = await nextProviderCancellation(context);
  assert.equal(cancellation.kind, "cancel_openrouter_stream");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.taskId, "task-m1-stop");
  assert.equal(status.stopCompleted, true);
  assert.equal(status.hasAbort, true);
  assert.equal(status.lateProviderRequestsAfterStop, 0);
  assert.equal(status.lateToolStartsAfterStop, 0);
  assert.deepEqual(
    JSON.parse(call(context, "drainNativeProviderRequestsJson")),
    [],
  );

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
  assert.throws(
    () => call(
      context,
      "continueNativeOpenRouterTaskPromptJson",
      JSON.stringify("This must not run."),
    ),
    /PI_MOBILE_NATIVE_PROVIDER_SCENARIO_NOT_STARTED/,
  );
});

test("Plan Mode restricts real Pi tools, restores exactly, survives rebuild, and rejects stale Implement", async () => {
  const context = await bootRuntime();
  const taskId = "task-e6-plan";
  const sessionId = "session-e6-plan";
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Plan how to add an Android release check."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify(sessionId),
    "true",
  ));
  const planning = await nextProviderRequest(context);
  const planningTools = planning.tools.map((tool) => tool.function.name);
  assert.deepEqual(planningTools, [
    "request_user_question",
    "request_user_confirmation",
    "device_capabilities_get",
    "device_files_list",
    "device_files_read",
    "device_media_list",
    "device_screen_capture",
    "device_ui_inspect",
    "device_packages_list",
    "device_package_inspect",
    "attachment_read",
    "task_plan_update",
  ]);
  assert.equal(planningTools.includes("run_command"), false);
  assert.equal(planningTools.includes("run_tests"), false);
  assert.equal(
    planning.messages.some((message) =>
      message.role === "system" &&
      message.content.includes("PLAN MODE IS ACTIVE") &&
      message.content.includes("MUST call task_plan_update")
    ),
    true,
  );

  const planInput = {
    explanation: "Add one deterministic release gate.",
    steps: [
      { id: "inspect", text: "Inspect the current Gradle checks", status: "completed" },
      { id: "implement", text: "Add the release validation", status: "in_progress" },
      { id: "verify", text: "Run focused and full tests", status: "pending" },
    ],
  };
  finishToolCall(
    context,
    planning,
    "call-task-plan-update",
    "task_plan_update",
    planInput,
  );
  const afterPlanTool = await nextProviderRequest(context);
  assert.match(JSON.stringify(afterPlanTool.messages), /task_plan_update/);
  finishTextRequest(context, afterPlanTool, "The plan is ready for review.");
  let status = await waitForTerminal(context);
  const expectedDigest = createHash("sha256")
    .update(JSON.stringify(planInput))
    .digest("hex");
  assert.equal(status.planMode, true);
  assert.equal(status.planTransitionPending, false);
  assert.equal(status.latestPlan.planDigest, expectedDigest);
  assert.deepEqual(status.latestPlan.steps, planInput.steps);

  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  assert.equal(snapshot.planMode, true);
  assert.equal(snapshot.latestPlan.planDigest, expectedDigest);
  assert.equal(
    snapshot.entries.some((entry) =>
      entry.type === "custom" && entry.customType === "pi_mobile_plan_mode"
    ),
    true,
  );
  assert.equal(
    snapshot.entries.some((entry) =>
      entry.type === "custom" &&
      entry.customType === "pi_mobile_task_plan" &&
      entry.data.planDigest === expectedDigest
    ),
    true,
  );
  JSON.parse(call(context, "closeJson"));

  const restored = await bootRuntime();
  status = JSON.parse(call(
    restored,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    String(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  assert.equal(status.terminal, true);
  assert.equal(status.planMode, true);
  assert.deepEqual(status.activeToolNames, planningTools);
  assert.deepEqual(JSON.parse(call(restored, "drainNativeProviderRequestsJson")), []);
  assert.throws(
    () => call(
      restored,
      "implementNativeOpenRouterTaskPlanJson",
      JSON.stringify("0".repeat(64)),
    ),
    /PI_MOBILE_PLAN_DIGEST_STALE/,
  );

  status = JSON.parse(call(
    restored,
    "implementNativeOpenRouterTaskPlanJson",
    JSON.stringify(expectedDigest),
  ));
  assert.equal(status.terminal, false);
  const implementation = await nextProviderRequest(restored);
  const implementationTools = implementation.tools.map((tool) => tool.function.name);
  assert.equal(implementationTools.includes("run_command"), true);
  assert.equal(implementationTools.includes("run_tests"), true);
  assert.equal(implementationTools.includes("device_files_commit_changes"), true);
  assert.equal(implementationTools.includes("task_plan_update"), false);
  assert.equal(
    implementation.messages.some((message) =>
      message.role === "system" && message.content.includes("PLAN MODE IS ACTIVE")
    ),
    false,
  );
  assert.match(
    userTexts(implementation).at(-1),
    /^\[momoding:implement-plan control=[^\]]+\]/,
  );
  finishToolCall(
    restored,
    implementation,
    "call-implement-command",
    "run_command",
    { command: "printf plan-implemented" },
  );
  const command = await nextNativeToolRequest(restored);
  assert.equal(command.toolName, "run_command");
  resolveNativeTool(restored, command.id, {
    ok: true,
    kind: "terminal",
    stdout: "plan-implemented",
    stderr: "",
    exitCode: 0,
    timedOut: false,
    stopped: false,
    outputTruncated: false,
    durationMillis: 1,
    fileChanges: { state: "none" },
  });
  const completed = await nextProviderRequest(restored);
  finishTextRequest(restored, completed, "Implemented and verified the approved plan.");
  status = await waitForTerminal(restored);
  assert.equal(status.planMode, false);
  assert.equal(status.commandError, null);
  assert.equal(status.finalText, "Implemented and verified the approved plan.");
  const implementedSnapshot = JSON.parse(
    call(restored, "nativeOpenRouterTaskSessionSnapshotJson"),
  );
  const implementationControl = implementedSnapshot.entries.find((entry) =>
    entry.type === "custom" &&
    entry.customType === "pi_mobile_plan_implementation" &&
    entry.data?.kind === "implement_plan" &&
    entry.data?.taskId === taskId &&
    entry.data?.planDigest === expectedDigest
  );
  assert.ok(implementationControl);
  const implementationMessage = implementedSnapshot.entries.find((entry) =>
    entry.type === "message" &&
    entry.parentId === implementationControl.id &&
    entry.message?.role === "user"
  );
  assert.ok(implementationMessage);
  const implementationSnapshotText = implementationMessage.message.content
    .filter((item) => item.type === "text")
    .map((item) => item.text)
    .join("");
  assert.match(
    implementationSnapshotText,
    new RegExp(`^\\[momoding:implement-plan control=${implementationControl.id}\\]`),
  );
  assert.deepEqual(JSON.parse(call(restored, "closeJson")), { ok: true, closed: true });
});

test("Goal lifecycle uses real Pi tools, trusted continuations, pause, restore, resume, and completion", async () => {
  const context = await bootRuntime();
  const taskId = "task-goal-native";
  const sessionId = "session-goal-native";
  const goalId = "goal-native-1";
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Prepare this task for a durable goal."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify(sessionId),
    "false",
  ));
  const initial = await nextProviderRequest(context);
  finishTextRequest(context, initial, "Ready for a goal.");
  await waitForTerminal(context);

  let status = JSON.parse(call(
    context,
    "startNativeOpenRouterTaskGoalJson",
    JSON.stringify(goalId),
    JSON.stringify("Finish two deterministic verification checkpoints."),
    "1",
    "1000",
  ));
  assert.equal(status.terminal, false);
  const firstGoalTurn = await nextProviderRequest(context);
  const firstGoalTools = firstGoalTurn.tools.map((tool) => tool.function.name);
  assert.equal(firstGoalTools.includes("task_goal_progress"), true);
  assert.equal(firstGoalTools.includes("task_goal_complete"), true);
  assert.equal(
    firstGoalTurn.messages.some((message) =>
      message.role === "system" && message.content.includes("GOAL MODE IS ACTIVE")
    ),
    true,
  );
  assert.match(
    userTexts(firstGoalTurn).at(-1),
    /^\[momoding:goal-continuation control=[^\]]+\]/,
  );
  finishToolCall(
    context,
    firstGoalTurn,
    "call-goal-progress",
    "task_goal_progress",
    { summary: "First checkpoint is verified.", progressMarker: "1/2" },
  );
  const afterProgress = await nextProviderRequest(context);
  finishTextRequest(context, afterProgress, "First checkpoint saved.");
  status = await waitForTerminal(context);
  assert.equal(status.goal.state, "active");
  assert.equal(status.goal.progressMarker, "1/2");

  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskGoalStateJson",
    JSON.stringify(goalId),
    "1",
    JSON.stringify("paused"),
  ));
  status = await waitForTerminal(context);
  assert.equal(status.goal.state, "paused");
  assert.equal(status.activeToolNames.includes("task_goal_progress"), false);
  const pausedTools = status.activeToolNames;

  JSON.parse(call(context, "setNativeOpenRouterTaskPlanModeJson", "true"));
  status = await waitForTerminal(context);
  const planTools = status.activeToolNames;
  assert.equal(status.planMode, true);
  assert.equal(planTools.includes("task_plan_update"), true);
  assert.equal(planTools.includes("run_command"), false);
  assert.equal(planTools.includes("run_tests"), false);
  assert.equal(planTools.includes("device_files_commit_changes"), false);
  assert.throws(
    () => call(
      context,
      "setNativeOpenRouterTaskGoalStateJson",
      JSON.stringify(goalId),
      "1",
      JSON.stringify("cleared"),
    ),
    /PI_MOBILE_GOAL_PLAN_MODE_CONFLICT/,
  );
  status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
  assert.equal(status.planMode, true);
  assert.equal(status.goal.state, "paused");
  assert.deepEqual(status.activeToolNames, planTools);

  JSON.parse(call(context, "setNativeOpenRouterTaskPlanModeJson", "false"));
  status = await waitForTerminal(context);
  assert.equal(status.planMode, false);
  assert.equal(status.goal.state, "paused");
  assert.deepEqual(status.activeToolNames, pausedTools);
  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  assert.equal(snapshot.goal.state, "paused");
  assert.equal(
    snapshot.entries.some((entry) =>
      entry.type === "custom" &&
      entry.customType === "pi_mobile_goal_continuation" &&
      entry.data?.goalId === goalId
    ),
    true,
  );
  JSON.parse(call(context, "closeJson"));

  const restored = await bootRuntime();
  status = JSON.parse(call(
    restored,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    String(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  assert.equal(status.goal.state, "paused");
  assert.deepEqual(JSON.parse(call(restored, "drainNativeProviderRequestsJson")), []);

  JSON.parse(call(
    restored,
    "continueNativeOpenRouterTaskGoalJson",
    JSON.stringify(goalId),
    "1",
    "1",
    "true",
  ));
  const resumed = await nextProviderRequest(restored);
  assert.equal(resumed.tools.some((tool) => tool.function.name === "task_goal_complete"), true);
  assert.match(userTexts(resumed).at(-1), /goal-continuation control=/);
  finishToolCall(
    restored,
    resumed,
    "call-goal-complete",
    "task_goal_complete",
    { summary: "Both checkpoints are verified.", terminalReason: "achieved" },
  );
  const afterComplete = await nextProviderRequest(restored);
  finishTextRequest(restored, afterComplete, "Goal achieved.");
  status = await waitForTerminal(restored);
  assert.equal(status.goal.state, "achieved");
  assert.equal(status.goal.terminalReason, "achieved");
  assert.equal(status.activeToolNames.includes("task_goal_progress"), false);
  assert.deepEqual(JSON.parse(call(restored, "closeJson")), { ok: true, closed: true });
});

test("delegate runs two isolated Pi child harnesses with overlapping Provider intervals", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-child-overlap"),
    JSON.stringify("Compare two Android release risks."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const parent = await nextProviderRequest(context);
  const delegate = parent.tools.find((tool) => tool.function.name === "delegate");
  assert.ok(delegate, "parent Pi Harness must expose delegate");
  assert.deepEqual(delegate.function.parameters.required, ["name", "task"]);

  finishParallelToolCalls(context, parent, [
    { id: "delegate-build", name: "delegate", arguments: { name: "Build analyst", task: "Analyze build reproducibility." } },
    { id: "delegate-device", name: "delegate", arguments: { name: "Device analyst", task: "Analyze Xiaomi device risk." } },
  ]);
  const childRequests = await nextProviderRequests(context, 2);
  assert.equal(new Set(childRequests.map((request) => request.childId)).size, 2);
  assert.deepEqual(
    new Set(childRequests.map((request) => request.parentToolCallId)),
    new Set(["delegate-build", "delegate-device"]),
  );
  for (const request of childRequests) {
    assert.equal(request.parentTaskId, "task-e6-child-overlap");
    assert.equal(request.tools, undefined, "child must not receive nested or side-effect tools");
    assert.equal(userTexts(request).length, 1, "each child must have isolated context");
    assert.equal(userTexts(request).includes("Compare two Android release risks."), false);
    assert.equal(
      request.messages.some((message) =>
        message.role === "system" &&
        message.content.includes("read-only child analysis agent") &&
        message.content.includes("must not claim to modify files")
      ),
      true,
    );
  }
  let status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
  assert.equal(status.pendingProviderCount, 2);
  assert.deepEqual(status.childAgents.map((child) => child.state).sort(), ["running", "running"]);
  assert.equal(status.queuedChildEventCount > 0, true);
  assert.equal("childEvents" in status, false, "status must not repeat the child event history");
  assert.equal(status.childAgents.every((child) => !("events" in child)), true);
  assert.equal(status.childAgents.every((child) => child.eventCount > 0), true);
  const startedEvents = JSON.parse(call(context, "peekNativeOpenRouterChildEventsJson"));
  assert.equal(startedEvents.filter((event) => event.event.type === "agent_start").length, 2);
  assert.deepEqual(
    JSON.parse(call(context, "peekNativeOpenRouterChildEventsJson")),
    startedEvents,
    "peek must preserve the exact unacknowledged batch",
  );
  assert.equal(startedEvents.every((event) => Number.isSafeInteger(event.eventOrdinal)), true);
  status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
  assert.equal(status.queuedChildEventCount, startedEvents.length, "peek must not consume the outbox");
  const startedAcks = childEventAcknowledgements(startedEvents);
  assert.throws(
    () => call(
      context,
      "acknowledgeNativeOpenRouterChildEventsJson",
      JSON.stringify(JSON.stringify([
        { ...startedAcks[0], throughEventOrdinal: startedAcks[0].throughEventOrdinal + 1 },
      ])),
    ),
    /PI_MOBILE_CHILD_EVENT_ACK_GAP/,
  );
  assert.throws(
    () => call(
      context,
      "acknowledgeNativeOpenRouterChildEventsJson",
      JSON.stringify(JSON.stringify([
        { ...startedAcks[0], throughDigest: "0".repeat(64) },
      ])),
    ),
    /PI_MOBILE_CHILD_EVENT_ACK_DIGEST_MISMATCH/,
  );
  assert.equal(ackChildEvents(context, startedEvents), startedEvents.length);
  assert.throws(
    () => call(
      context,
      "acknowledgeNativeOpenRouterChildEventsJson",
      JSON.stringify(JSON.stringify([startedAcks[0]])),
    ),
    /PI_MOBILE_CHILD_EVENT_ACK_STALE/,
  );
  assert.deepEqual(JSON.parse(call(context, "peekNativeOpenRouterChildEventsJson")), []);

  const device = childRequests.find((request) => request.parentToolCallId === "delegate-device");
  const build = childRequests.find((request) => request.parentToolCallId === "delegate-build");
  finishTextRequestWithUsage(context, device, "Device risk is background execution variance.", {
    prompt_tokens: 21,
    completion_tokens: 8,
    total_tokens: 29,
  });
  finishTextRequestWithUsage(context, build, "Build risk is an unpinned toolchain.", {
    prompt_tokens: 17,
    completion_tokens: 7,
    total_tokens: 24,
  });

  const parentSummary = await nextProviderRequest(context);
  assert.equal(parentSummary.childId, undefined);
  const toolResults = parentSummary.messages.filter((message) => message.role === "tool");
  assert.equal(toolResults.length, 2);
  assert.equal(toolResults.every((message) => message.content.includes('"state":"completed"')), true);
  finishTextRequest(context, parentSummary, "Both child analyses completed.");
  status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.providerRequestsIssued, 2);
  assert.equal(status.childProviderRequestsIssued, 2);
  assert.equal(status.childProviderRequestsCompleted, 2);
  assert.equal(status.childProviderRequestsFailed, 0);
  assert.deepEqual(status.childAgents.map((child) => child.state).sort(), ["completed", "completed"]);
  assert.deepEqual(status.childAgents.map((child) => child.inputTokens).sort((a, b) => a - b), [17, 21]);
  assert.deepEqual(status.childAgents.map((child) => child.outputTokens).sort((a, b) => a - b), [7, 8]);
  assert.deepEqual(status.childAgents.map((child) => child.contextTokens).sort((a, b) => a - b), [24, 29]);
  assert.equal(status.childAgents.every((child) => child.model === "deepseek/deepseek-v4-pro"), true);
  assert.equal(status.childAgents.every((child) => child.resultText?.length > 0), true);
  assert.equal(status.childAgents.every((child) => child.eventTypes.includes("settled")), true);
  assert.equal(status.childAgents.every((child) => child.eventCount >= child.eventTypes.length), true);
  const remainingEvents = JSON.parse(call(context, "peekNativeOpenRouterChildEventsJson"));
  assert.equal(remainingEvents.some((event) => event.event.type === "settled"), true);
  assert.equal(ackChildEvents(context, remainingEvents), remainingEvents.length);
  assert.equal(
    JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson")).queuedChildEventCount,
    0,
  );
  const evicted = JSON.parse(call(
    context,
    "acknowledgeNativeOpenRouterChildAgentsJson",
    JSON.stringify(JSON.stringify(status.childAgents.map((child) => child.childId))),
  ));
  assert.deepEqual(evicted.evictedChildIds.sort(), status.childAgents.map((child) => child.childId).sort());
  assert.deepEqual(
    JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson")).childAgents,
    [],
    "persisted terminal children must be releasable from the live Runtime",
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("native attention resolution keeps the model payload separate from exact delivery proof", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-attention-proof"),
    JSON.stringify("Ask before continuing."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const providerRequest = await nextProviderRequest(context);
  finishToolCall(
    context,
    providerRequest,
    "call-attention-proof",
    "request_user_confirmation",
    { summary: "Continue?", details: "No files will change." },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  const payload = {
    code: "USER_DECLINED",
    message: "User declined the confirmation",
  };
  const details = {
    callId: "local-attention-call",
    toolName: "request_user_confirmation",
    terminalSemanticSha256: "a".repeat(64),
    sideEffect: false,
  };
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeRequest.id),
    JSON.stringify(JSON.stringify(payload)),
    JSON.stringify(JSON.stringify(details)),
    "true",
  ));

  const followUp = await nextProviderRequest(context);
  assert.match(JSON.stringify(followUp.messages), /USER_DECLINED/);
  finishTextRequest(context, followUp, "The confirmation was declined.");
  const status = await waitForTerminal(context);
  const completion = status.runEvents.find((event) =>
    event.type === "tool_execution_end" &&
    event.toolCallId === "call-attention-proof"
  );
  assert.deepEqual(completion.result.content, [{
    type: "text",
    text: JSON.stringify(payload),
  }]);
  assert.deepEqual(completion.result.details, details);
  assert.equal(completion.isError, true);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("screen capture reaches only the current provider turn and expires from events and session", async () => {
  const context = await bootRuntime();
  const imageData = "iVBORw0KGgo=";
  const digest = createHash("sha256").update(Buffer.from(imageData, "base64")).digest("hex");
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-live-screen"),
    JSON.stringify("Inspect the current Android screen."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  const screenTool = first.tools.find((tool) =>
    tool.function.name === "device_screen_capture"
  );
  assert.ok(screenTool);
  assert.equal(screenTool.function.parameters.required.includes("purpose"), true);
  finishToolCall(
    context,
    first,
    "call-live-screen",
    "device_screen_capture",
    { purpose: "Understand the visible error", targetPackage: "com.example.target" },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_screen_tool");
  assert.deepEqual(nativeRequest.arguments, {
    purpose: "Understand the visible error",
    targetPackage: "com.example.target",
  });
  const details = {
    ok: true,
    liveOnly: true,
    source: "accessibility",
    contentSha256: digest,
    width: 720,
    height: 1280,
    mimeType: "image/png",
  };
  const content = [
    { type: "text", text: JSON.stringify({ ...details, image: "live-only" }) },
    { type: "image", data: imageData, mimeType: "image/png" },
  ];
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeRequest.id),
    JSON.stringify(JSON.stringify(details)),
    JSON.stringify(JSON.stringify(details)),
    "false",
    JSON.stringify(JSON.stringify(content)),
  ));

  const followUp = await nextProviderRequest(context);
  const toolMessage = followUp.messages.find((message) =>
    message.role === "tool" && message.tool_call_id === "call-live-screen"
  );
  assert.match(toolMessage.content, /live-only/);
  const liveImageMessage = followUp.messages.find((message) =>
    message.role === "user" && Array.isArray(message.content) &&
    message.content.some((block) => block.type === "image_url")
  );
  assert.equal(
    liveImageMessage.content[1].image_url.url,
    `data:image/png;base64,${imageData}`,
  );
  finishTextRequest(context, followUp, "The visible screen shows a test error.");
  const status = await waitForTerminal(context);
  assert.equal(JSON.stringify(status.runEvents).includes(imageData), false);
  assert.match(JSON.stringify(status.runEvents), /live screen image expired/);

  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persisted = JSON.stringify(snapshot);
  assert.equal(persisted.includes(imageData), false);
  assert.match(persisted, /live screen image expired/);
  assert.match(persisted, new RegExp(digest));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });

  const restored = await bootRuntime();
  const restoredStatus = JSON.parse(call(
    restored,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-live-screen"),
    JSON.stringify("task-live-screen"),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify("[]"),
    JSON.stringify("[]"),
  ));
  assert.equal(restoredStatus.terminal, true);
  assert.deepEqual(JSON.parse(call(restored, "drainNativeProviderRequestsJson")), []);
  assert.deepEqual(JSON.parse(call(restored, "closeJson")), { ok: true, closed: true });
});

test("UI inspect and one bounded action use the Android mailbox in sequence", async () => {
  const context = await bootRuntime();
  const snapshotId = "ui-11111111111111111111111111111111";
  const nodeHandle = `${snapshotId}:n2`;
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-ui-action"),
    JSON.stringify("Open the harmless fixture control."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  const inspectTool = first.tools.find((tool) => tool.function.name === "device_ui_inspect");
  const actionTool = first.tools.find((tool) => tool.function.name === "device_ui_action");
  assert.ok(inspectTool);
  assert.ok(actionTool);
  assert.equal(inspectTool.function.parameters.properties.maxNodes.maximum, 250);
  assert.deepEqual(
    actionTool.function.parameters.properties.action.enum,
    ["click", "scroll", "input_draft", "back"],
  );

  finishToolCall(
    context,
    first,
    "call-ui-inspect",
    "device_ui_inspect",
    { targetPackage: "dev.fixture", maxNodes: 50 },
  );
  const inspectRequest = await nextNativeToolRequest(context);
  assert.equal(inspectRequest.kind, "android_ui_tool");
  assert.equal(inspectRequest.toolName, "device_ui_inspect");
  resolveNativeTool(context, inspectRequest.id, {
    ok: true,
    snapshotId,
    packageName: "dev.fixture",
    nodes: [{ handle: nodeHandle, clickable: true, redacted: false }],
  });

  const second = await nextProviderRequest(context);
  finishToolCall(
    context,
    second,
    "call-ui-action",
    "device_ui_action",
    { snapshotId, nodeHandle, action: "click" },
  );
  const actionRequest = await nextNativeToolRequest(context);
  assert.equal(actionRequest.kind, "android_ui_tool");
  assert.equal(actionRequest.toolName, "device_ui_action");
  assert.deepEqual(actionRequest.arguments, { snapshotId, nodeHandle, action: "click" });
  resolveNativeTool(context, actionRequest.id, {
    ok: true,
    action: "click",
    beforeSnapshotId: snapshotId,
    afterSnapshotId: "ui-22222222222222222222222222222222",
    foregroundPackage: "dev.fixture",
    changed: true,
    noChangeCount: 0,
    sessionPaused: false,
    actionCount: 1,
  });

  const finalRequest = await nextProviderRequest(context);
  assert.match(JSON.stringify(finalRequest.messages), /afterSnapshotId/);
  finishTextRequest(context, finalRequest, "The harmless fixture control opened.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("capability and package facts stay in one Pi task through the Android mailbox", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-package-facts"),
    JSON.stringify("Check whether the Android settings package is available."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));

  const capabilityTurn = await nextProviderRequest(context);
  const listSchema = capabilityTurn.tools.find(
    (tool) => tool.function.name === "device_packages_list",
  )?.function.parameters;
  const inspectSchema = capabilityTurn.tools.find(
    (tool) => tool.function.name === "device_package_inspect",
  )?.function.parameters;
  assert.ok(listSchema);
  assert.ok(inspectSchema);
  assert.equal(listSchema.additionalProperties, false);
  assert.equal(listSchema.properties.limit.maximum, 100);
  assert.equal(inspectSchema.additionalProperties, false);
  assert.match(inspectSchema.properties.packageName.pattern, /A-Za-z/);

  finishToolCall(
    context,
    capabilityTurn,
    "call-package-capabilities",
    "device_capabilities_get",
    {},
  );
  const capabilityRequest = await nextNativeToolRequest(context);
  assert.equal(capabilityRequest.kind, "android_file_tool");
  resolveNativeTool(context, capabilityRequest.id, {
    ok: true,
    capabilities: [{
      id: "shizuku_shell_uid",
      availability: "ready",
      source: "shizuku",
      safeMessage: "Shizuku is ready.",
      toolNames: ["device_packages_list", "device_package_inspect"],
    }],
  });

  const listTurn = await nextProviderRequest(context);
  assert.match(JSON.stringify(listTurn.messages), /shizuku_shell_uid/);
  finishToolCall(
    context,
    listTurn,
    "call-package-list",
    "device_packages_list",
    { purpose: "Find settings", includeSystem: true, offset: 0, limit: 10 },
  );
  const listRequest = await nextNativeToolRequest(context);
  assert.equal(listRequest.kind, "android_package_tool");
  assert.equal(listRequest.toolName, "device_packages_list");
  resolveNativeTool(context, listRequest.id, {
    ok: true,
    capabilityId: "shizuku_shell_uid",
    packages: [{
      packageName: "com.android.settings",
      label: "Settings",
      system: true,
      enabled: true,
    }],
  });

  const inspectTurn = await nextProviderRequest(context);
  assert.match(JSON.stringify(inspectTurn.messages), /com.android.settings/);
  finishToolCall(
    context,
    inspectTurn,
    "call-package-inspect",
    "device_package_inspect",
    { purpose: "Verify settings metadata", packageName: "com.android.settings" },
  );
  const inspectRequest = await nextNativeToolRequest(context);
  assert.equal(inspectRequest.kind, "android_package_tool");
  assert.equal(inspectRequest.toolName, "device_package_inspect");
  resolveNativeTool(context, inspectRequest.id, {
    ok: true,
    capabilityId: "shizuku_shell_uid",
    package: {
      packageName: "com.android.settings",
      label: "Settings",
      system: true,
      enabled: true,
      versionName: "1",
      versionCode: 1,
      minSdk: 35,
      targetSdk: 35,
    },
  });

  const finalTurn = await nextProviderRequest(context);
  assert.match(JSON.stringify(finalTurn.messages), /targetSdk/);
  finishTextRequest(context, finalTurn, "Android Settings is installed and enabled.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.toolExecutionsStarted, 3);
  assert.equal(status.toolExecutionsEnded, 3);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("device media list uses the Android mailbox and preserves success and failure semantics", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-media-list"),
    JSON.stringify("Inspect recent photo metadata."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  const mediaTool = first.tools.find((tool) => tool.function.name === "device_media_list");
  assert.ok(mediaTool);
  assert.equal(mediaTool.function.parameters.properties.limit.maximum, 20);
  finishToolCall(
    context,
    first,
    "call-media-success",
    "device_media_list",
    { purpose: "Find screenshots", limit: 3 },
  );
  const successRequest = await nextNativeToolRequest(context);
  assert.equal(successRequest.kind, "android_media_tool");
  assert.equal(successRequest.toolName, "device_media_list");
  assert.deepEqual(successRequest.arguments, { purpose: "Find screenshots", limit: 3 });
  resolveNativeTool(context, successRequest.id, {
    access: "partial",
    limit: 3,
    returnedCount: 0,
    items: [],
  });
  const afterSuccess = await nextProviderRequest(context);
  finishTextRequest(context, afterSuccess, "No recent photo metadata was returned.");
  let status = await waitForTerminal(context);
  assert.notEqual(
    status.runEvents.find((event) =>
      event.type === "tool_execution_end" && event.toolCallId === "call-media-success"
    )?.isError,
    true,
  );

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Try once more."),
  ));
  const second = await nextProviderRequest(context);
  finishToolCall(
    context,
    second,
    "call-media-denied",
    "device_media_list",
    { purpose: "Find screenshots", limit: 3 },
  );
  const deniedRequest = await nextNativeToolRequest(context);
  const deniedPayload = {
    code: "PHOTO_LIBRARY_PERMISSION_REQUIRED",
    message: "Enable photo-library access in Device capabilities",
  };
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(deniedRequest.id),
    JSON.stringify(JSON.stringify(deniedPayload)),
    JSON.stringify(JSON.stringify(deniedPayload)),
    "true",
  ));
  const afterDenied = await nextProviderRequest(context);
  assert.match(JSON.stringify(afterDenied.messages), /PHOTO_LIBRARY_PERMISSION_REQUIRED/);
  finishTextRequest(context, afterDenied, "Photo-library permission is required.");
  status = await waitForTerminal(context);
  assert.equal(
    status.runEvents.find((event) =>
      event.type === "tool_execution_end" && event.toolCallId === "call-media-denied"
    )?.isError,
    true,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("delegate keeps child failure explicit while the parent Pi turn can recover", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-child-failure"),
    JSON.stringify("Delegate a risky analysis."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const parent = await nextProviderRequest(context);
  finishToolCall(
    context,
    parent,
    "delegate-failure",
    "delegate",
    { name: "Failure analyst", task: "Analyze the failing dependency." },
  );
  const child = await nextProviderRequest(context);
  assert.equal(child.parentToolCallId, "delegate-failure");
  JSON.parse(call(
    context,
    "failNativeProviderRequestJson",
    JSON.stringify(child.id),
    JSON.stringify("Child Provider failed safely"),
  ));

  const parentRecovery = await nextProviderRequest(context);
  const failedResult = parentRecovery.messages.find((message) =>
    message.role === "tool" && message.tool_call_id === "delegate-failure"
  );
  assert.match(failedResult.content, /PI_MOBILE_CHILD_FAILED/);
  assert.match(failedResult.content, /Child Provider failed safely/);
  finishTextRequest(context, parentRecovery, "The delegated analysis failed; no result was fabricated.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.providerError, null, "child failure must not poison parent Provider state");
  assert.equal(status.providerRequestsFailed, 0);
  assert.equal(status.childProviderRequestsFailed, 1);
  assert.equal(status.childAgents[0].state, "failed");
  assert.equal(status.childAgents[0].terminalReason, "Child Provider failed safely");
  assert.equal(
    status.runEvents.some((event) =>
      event.type === "tool_execution_end" &&
      event.toolCallId === "delegate-failure" &&
      event.isError === true
    ),
    true,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("delegate enforces three children per parent turn and rejects the fourth", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-child-limit"),
    JSON.stringify("Delegate the bounded release analysis."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const parent = await nextProviderRequest(context);
  finishParallelToolCalls(context, parent, [
    { id: "delegate-limit-1", name: "delegate", arguments: { name: "Limit 1", task: "Analyze risk 1." } },
    { id: "delegate-limit-2", name: "delegate", arguments: { name: "Limit 2", task: "Analyze risk 2." } },
    { id: "delegate-limit-3", name: "delegate", arguments: { name: "Limit 3", task: "Analyze risk 3." } },
    { id: "delegate-limit-4", name: "delegate", arguments: { name: "Limit 4", task: "Analyze risk 4." } },
  ]);
  const children = await nextProviderRequests(context, 3);
  assert.equal(children.every((request) => request.childId !== undefined), true);
  for (const child of children) finishTextRequest(context, child, `Completed ${child.childName}.`);
  const parentSummary = await nextProviderRequest(context);
  const rejected = parentSummary.messages.find((message) =>
    message.role === "tool" && message.tool_call_id === "delegate-limit-4"
  );
  assert.match(rejected.content, /PI_MOBILE_CHILD_LIMIT_REACHED/);
  finishTextRequest(context, parentSummary, "Three children completed; the fourth was rejected by policy.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.childAgents.length, 3);
  assert.equal(status.childProviderRequestsIssued, 3);
  assert.equal(
    status.runEvents.some((event) =>
      event.type === "tool_execution_end" &&
      event.toolCallId === "delegate-limit-4" &&
      event.isError === true
    ),
    true,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("one child can be cancelled without stopping the parent task", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-child-cancel"),
    JSON.stringify("Delegate one cancellable analysis."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const parent = await nextProviderRequest(context);
  finishToolCall(
    context,
    parent,
    "delegate-cancel-one",
    "delegate",
    { name: "Cancellable child", task: "Analyze until cancelled." },
  );
  const child = await nextProviderRequest(context);
  const cancelResult = JSON.parse(call(
    context,
    "cancelNativeOpenRouterChildAgentJson",
    JSON.stringify(child.childId),
  ));
  assert.equal(cancelResult.accepted, true);
  assert.equal(cancelResult.status.commandError, null);
  const repeatedCancel = JSON.parse(call(
    context,
    "cancelNativeOpenRouterChildAgentJson",
    JSON.stringify("missing-child"),
  ));
  assert.equal(repeatedCancel.accepted, false);
  assert.equal(repeatedCancel.status.commandError, null, "an invalid cancel must not fail the parent task");
  const cancellation = await nextProviderCancellation(context);
  assert.equal(cancellation.id, child.id);
  const parentRecovery = await nextProviderRequest(context);
  const cancelled = parentRecovery.messages.find((message) =>
    message.role === "tool" && message.tool_call_id === "delegate-cancel-one"
  );
  assert.match(cancelled.content, /PI_MOBILE_CHILD_CANCELLED/);
  assert.match(cancelled.content, /user_cancelled/);
  finishTextRequest(context, parentRecovery, "The child was cancelled; the parent task is still active.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.equal(status.stopRequested, false);
  assert.equal(status.childAgents[0].state, "cancelled");
  assert.equal(status.childAgents[0].terminalReason, "user_cancelled");
  assert.equal(status.childProviderCancellationsIssued, 1);
  assert.equal(status.lateProviderRequestsAfterStop, 0);
  const childEvents = JSON.parse(call(context, "drainNativeOpenRouterChildEventsJson"));
  assert.equal(childEvents.length, status.childAgents[0].eventCount);
  assert.equal(childEvents.some((event) => event.event.type === "abort"), true);
  assert.equal(status.childAgents[0].eventTypes.includes("abort"), true);
  assert.equal(
    JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson")).queuedChildEventCount,
    0,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("parent Stop cancels every running child without late Provider work", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-child-stop"),
    JSON.stringify("Delegate two long analyses."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const parent = await nextProviderRequest(context);
  finishParallelToolCalls(context, parent, [
    { id: "delegate-stop-a", name: "delegate", arguments: { name: "Stop A", task: "Wait on analysis A." } },
    { id: "delegate-stop-b", name: "delegate", arguments: { name: "Stop B", task: "Wait on analysis B." } },
  ]);
  const children = await nextProviderRequests(context, 2);
  assert.equal(children.every((request) => request.childId !== undefined), true);
  JSON.parse(call(context, "abortNativeOpenRouterScenarioJson"));
  const cancellations = await nextProviderCancellations(context, 2);
  assert.deepEqual(
    new Set(cancellations.map((cancellation) => cancellation.id)),
    new Set(children.map((request) => request.id)),
  );
  const status = await waitForTerminal(context);
  assert.equal(status.stopCompleted, true);
  assert.equal(status.hasAbort, true);
  assert.equal(status.childProviderCancellationsIssued, 2);
  assert.equal(status.lateProviderRequestsAfterStop, 0);
  assert.deepEqual(status.childAgents.map((child) => child.state).sort(), ["cancelled", "cancelled"]);
  assert.equal(status.childAgents.every((child) => child.terminalReason === "parent_stopped"), true);
  assert.equal(status.childAgents.every((child) => child.eventTypes.includes("abort")), true);
  const childEvents = JSON.parse(call(context, "drainNativeOpenRouterChildEventsJson"));
  assert.equal(
    childEvents.length,
    status.childAgents.reduce((count, child) => count + child.eventCount, 0),
  );
  assert.deepEqual(
    new Set(childEvents.filter((event) => event.event.type === "abort").map((event) => event.childId)),
    new Set(status.childAgents.map((child) => child.childId)),
  );
  assert.equal(
    JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson")).queuedChildEventCount,
    0,
  );
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("Pi loadSkills authoritatively parses one bounded mobile SKILL.md", async () => {
  const context = await bootRuntime();
  const parsed = await parseSkillDocument(context, [
    "---",
    "name: mobile-review",
    "description: Reviews a bounded Android change",
    "disable-model-invocation: true",
    "---",
    "# Review",
    "Return PASS or FAIL with one reason.",
  ].join("\n"));
  assert.equal(parsed.ok, true);
  assert.equal(parsed.phase, "completed");
  assert.equal(parsed.availability, "available");
  assert.deepEqual(parsed.resource, {
    name: "mobile-review",
    description: "Reviews a bounded Android change",
    content: "# Review\nReturn PASS or FAIL with one reason.",
    contentSha256: parsed.resource.contentSha256,
    disableModelInvocation: true,
  });
  assert.match(parsed.resource.contentSha256, /^[0-9a-f]{64}$/);

  const missingName = await parseSkillDocument(context, [
    "---",
    "description: Missing an explicit name",
    "---",
    "No name.",
  ].join("\n"));
  assert.equal(missingName.ok, false);
  assert.equal(missingName.errorCode, "SKILL_NAME_REQUIRED");

  const malformed = await parseSkillDocument(context, [
    "---",
    "name: [",
    "description: malformed",
    "---",
    "Broken YAML.",
  ].join("\n"));
  assert.equal(malformed.ok, false);
  assert.equal(malformed.errorCode, "SKILL_PARSE_FAILED");

  const relative = await parseSkillDocument(context, [
    "---",
    "name: relative-review",
    "description: References a local file",
    "---",
    "Read [the checklist](references/checklist.md).",
  ].join("\n"));
  assert.equal(relative.ok, true);
  assert.equal(relative.availability, "unavailable");
  assert.equal(relative.diagnosticCode, "RELATIVE_DEPENDENCY_UNSUPPORTED");

  const relativeDefinition = await parseSkillDocument(context, [
    "---",
    "name: relative-definition",
    "description: Uses a Markdown reference definition",
    "---",
    "Read [the checklist][checklist].",
    "[checklist]: references/checklist.md",
  ].join("\n"));
  assert.equal(relativeDefinition.ok, true);
  assert.equal(relativeDefinition.availability, "unavailable");

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("mobile Skill parsing fails closed for invalid metadata, bounds, and HTML relatives", async () => {
  const context = await bootRuntime();
  const missingDescription = await parseSkillDocument(context, [
    "---",
    "name: missing-description",
    "---",
    "No description.",
  ].join("\n"));
  assert.equal(missingDescription.ok, false);
  assert.equal(missingDescription.errorCode, "SKILL_METADATA_INVALID");

  const illegalName = await parseSkillDocument(context, [
    "---",
    "name: Illegal_Name",
    "description: Has an invalid name",
    "---",
    "Invalid.",
  ].join("\n"));
  assert.equal(illegalName.ok, false);
  assert.equal(illegalName.errorCode, "SKILL_NAME_INVALID");

  const overlongName = await parseSkillDocument(context, [
    "---",
    `name: ${"a".repeat(65)}`,
    "description: Has a name over the mobile limit",
    "---",
    "Invalid.",
  ].join("\n"));
  assert.equal(overlongName.ok, false);
  assert.equal(overlongName.errorCode, "SKILL_NAME_INVALID");

  const overlongDescription = await parseSkillDocument(context, [
    "---",
    "name: long-description",
    `description: ${"d".repeat(1025)}`,
    "---",
    "Invalid.",
  ].join("\n"));
  assert.equal(overlongDescription.ok, false);
  assert.equal(overlongDescription.errorCode, "SKILL_METADATA_INVALID");

  const htmlRelative = await parseSkillDocument(context, [
    "---",
    "name: html-relative",
    "description: Uses a local HTML source",
    "---",
    '<img src="assets/check.png">',
  ].join("\n"));
  assert.equal(htmlRelative.ok, true);
  assert.equal(htmlRelative.availability, "unavailable");
  assert.equal(htmlRelative.diagnosticCode, "RELATIVE_DEPENDENCY_UNSUPPORTED");

  assert.throws(
    () => call(
      context,
      "beginSkillDocumentParseJson",
      JSON.stringify("x".repeat(65_537)),
    ),
    /PI_MOBILE_SKILL_DOCUMENT_INVALID/,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("settled Harness replaces Skill resources exactly once and skips an identical set", async () => {
  const context = await bootRuntime();
  const parsed = await parseSkillDocument(context, validSkillDocument());
  const resource = parsed.resource;

  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-skills-resources"),
    JSON.stringify("Start without Skills."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  finishTextRequest(context, first, "Ready.");
  let status = await waitForTerminal(context);
  assert.equal(status.resourceUpdateCount, 0);
  assert.deepEqual(status.skillNames, []);

  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskResourcesJson",
    JSON.stringify(JSON.stringify([resource])),
  ));
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);
  status = await waitForTerminal(context);
  assert.equal(status.resourceUpdateCount, 1);
  assert.deepEqual(status.skillNames, ["mobile-review"]);
  const firstDigest = status.resourceSetDigest;
  const resourceEvent = status.events.findLast((event) => event.type === "resources_update");
  assert.deepEqual(resourceEvent.skillNames, ["mobile-review"]);
  assert.equal(JSON.stringify(resourceEvent).includes(resource.content), false);

  const eventCount = status.events.length;
  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskResourcesJson",
    JSON.stringify(JSON.stringify([resource])),
  ));
  status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
  assert.equal(status.terminal, true);
  assert.equal(status.resourceUpdateCount, 1);
  assert.equal(status.events.length, eventCount);
  assert.equal(status.resourceSetDigest, firstDigest);

  const descriptionOnly = { ...resource, description: "Reviews one bounded mobile change" };
  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskResourcesJson",
    JSON.stringify(JSON.stringify([descriptionOnly])),
  ));
  status = await waitForTerminal(context);
  assert.equal(status.resourceUpdateCount, 2);
  assert.notEqual(status.resourceSetDigest, firstDigest);
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);

  assert.throws(
    () => call(
      context,
      "setNativeOpenRouterTaskResourcesJson",
      JSON.stringify(JSON.stringify([resource, resource])),
    ),
    /PI_MOBILE_SKILL_NAME_DUPLICATED/,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("invalid Skill resources and invocations fail before mutation or Provider work", async () => {
  const context = await bootRuntime();
  const parsed = await parseSkillDocument(context, validSkillDocument());
  const resource = parsed.resource;
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-skill-fail-closed"),
    JSON.stringify("Establish one settled turn."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    "undefined",
    "false",
    JSON.stringify(JSON.stringify([resource])),
  ));
  const first = await nextProviderRequest(context);
  assert.equal(
    first.messages.some((message) =>
      message.role === "system" &&
      (message.content.includes("mobile-review") || message.content.includes("<available_skills>"))
    ),
    false,
    "explicit-only mobile Skills must not be advertised as autonomously readable",
  );
  finishTextRequest(context, first, "Settled.");
  let status = await waitForTerminal(context);
  const originalDigest = status.resourceSetDigest;
  assert.equal(status.resourceSetTrusted, true);

  assert.throws(
    () => call(
      context,
      "setNativeOpenRouterTaskResourcesJson",
      JSON.stringify(JSON.stringify([{ ...resource, contentSha256: "0".repeat(64) }])),
    ),
    /PI_MOBILE_SKILL_CONTENT_DIGEST_INVALID/,
  );
  status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
  assert.equal(status.resourceSetTrusted, true);
  assert.equal(status.resourceSetDigest, originalDigest);
  assert.equal(status.resourceUpdateCount, 0);
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);

  assert.throws(
    () => call(
      context,
      "invokeNativeOpenRouterTaskSkillJson",
      JSON.stringify("unknown-skill"),
    ),
    /PI_MOBILE_SKILL_NOT_ENABLED/,
  );
  assert.throws(
    () => call(
      context,
      "invokeNativeOpenRouterTaskSkillJson",
      JSON.stringify("mobile-review"),
      JSON.stringify("x".repeat(65_537)),
    ),
    /PI_MOBILE_SKILL_INSTRUCTIONS_INVALID/,
  );
  status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
  assert.equal(status.turnCount, 1);
  assert.equal(status.resourceSetTrusted, true);
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("explicit Skill invocation uses the same Pi Harness and fails before extra Provider work", async () => {
  const context = await bootRuntime();
  const parsed = await parseSkillDocument(context, validSkillDocument());
  const resourcesJson = JSON.stringify([parsed.resource]);

  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-e6-skill-turn"),
    JSON.stringify("Start a normal task."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    "undefined",
    "false",
    JSON.stringify(resourcesJson),
  ));
  const first = await nextProviderRequest(context);
  assert.throws(
    () => call(
      context,
      "invokeNativeOpenRouterTaskSkillJson",
      JSON.stringify("mobile-review"),
      JSON.stringify("must not queue while running"),
    ),
    /PI_MOBILE_TASK_SESSION_BUSY/,
  );
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);
  finishTextRequest(context, first, "Normal turn complete.");
  await waitForTerminal(context);

  JSON.parse(call(
    context,
    "invokeNativeOpenRouterTaskSkillJson",
    JSON.stringify("mobile-review"),
    JSON.stringify("Check only the current result."),
  ));
  const skillRequest = await nextProviderRequest(context);
  const skillMessage = skillRequest.messages.findLast((message) => message.role === "user");
  assert.equal(typeof skillMessage.content, "string");
  assert.equal(skillMessage.content.includes('<skill name="mobile-review" location="/mobile-skills/mobile-review/SKILL.md">'), true);
  assert.equal(skillMessage.content.includes("Return PASS or FAIL with one reason."), true);
  assert.equal(skillMessage.content.endsWith("Check only the current result."), true);
  finishTextRequest(context, skillRequest, "PASS — bounded result is correct.");
  let status = await waitForTerminal(context);
  assert.equal(status.turnCount, 2);

  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskPlanModeJson",
    "true",
  ));
  status = await waitForTerminal(context);
  assert.equal(status.planMode, true);
  assert.throws(
    () => call(
      context,
      "invokeNativeOpenRouterTaskSkillJson",
      JSON.stringify("mobile-review"),
    ),
    /PI_MOBILE_SKILL_PLAN_MODE_CONFLICT/,
  );
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);

  JSON.parse(call(context, "setNativeOpenRouterTaskPlanModeJson", "false"));
  await waitForTerminal(context);
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskGoalJson",
    JSON.stringify("goal-e6-skill-conflict"),
    JSON.stringify("Keep the Skill conflict explicit."),
    "1",
    "1",
  ));
  const goalRequest = await nextProviderRequest(context);
  finishTextRequest(context, goalRequest, "Goal remains active.");
  status = await waitForTerminal(context);
  assert.equal(status.goal.state, "active");
  assert.throws(
    () => call(
      context,
      "invokeNativeOpenRouterTaskSkillJson",
      JSON.stringify("mobile-review"),
    ),
    /PI_MOBILE_SKILL_GOAL_CONFLICT/,
  );
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);
  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskGoalStateJson",
    JSON.stringify("goal-e6-skill-conflict"),
    "1",
    JSON.stringify("cleared"),
  ));
  await waitForTerminal(context);
  JSON.parse(call(
    context,
    "setNativeOpenRouterTaskResourcesJson",
    JSON.stringify("[]"),
  ));
  status = await waitForTerminal(context);
  assert.equal(status.resourceUpdateCount, 1);
  assert.throws(
    () => call(
      context,
      "invokeNativeOpenRouterTaskSkillJson",
      JSON.stringify("mobile-review"),
    ),
    /PI_MOBILE_SKILL_NOT_ENABLED/,
  );
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderRequestsJson")), []);
  assert.equal(JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson")).turnCount, 3);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("a New Task can start with a Skill and restore its resources without replay", async () => {
  const firstProcess = await bootRuntime();
  const parsed = await parseSkillDocument(firstProcess, validSkillDocument());
  const resourcesJson = JSON.stringify([parsed.resource]);
  JSON.parse(call(
    firstProcess,
    "startNativeOpenRouterTaskSkillSessionJson",
    JSON.stringify("task-e6-new-skill"),
    JSON.stringify("mobile-review"),
    JSON.stringify("Review the initial task."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    "undefined",
    JSON.stringify(resourcesJson),
  ));
  const first = await nextProviderRequest(firstProcess);
  assert.equal(userTexts(first).length, 1);
  assert.equal(userTexts(first)[0].includes('<skill name="mobile-review"'), true);
  finishTextRequest(firstProcess, first, "PASS — initial Skill turn.");
  const firstStatus = await waitForTerminal(firstProcess);
  assert.equal(firstStatus.turnCount, 1);
  const snapshot = JSON.parse(call(firstProcess, "nativeOpenRouterTaskSessionSnapshotJson"));
  const skillControl = snapshot.entries.find((entry) =>
    entry.type === "custom" && entry.customType === "pi_mobile_skill_invocation"
  );
  assert.deepEqual(skillControl.data, {
    kind: "skill_invocation",
    name: "mobile-review",
    additionalInstructions: "Review the initial task.",
  });
  JSON.parse(call(firstProcess, "closeJson"));

  const restored = await bootRuntime();
  const restoreStatus = JSON.parse(call(
    restored,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(snapshot.taskId),
    JSON.stringify("task-e6-new-skill"),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify(resourcesJson),
  ));
  assert.equal(restoreStatus.terminal, true);
  assert.deepEqual(restoreStatus.skillNames, ["mobile-review"]);
  assert.equal(restoreStatus.providerRequestsIssued, 0);
  assert.equal(restoreStatus.resourceUpdateCount, 0);
  assert.deepEqual(JSON.parse(call(restored, "drainNativeProviderRequestsJson")), []);

  JSON.parse(call(
    restored,
    "invokeNativeOpenRouterTaskSkillJson",
    JSON.stringify("mobile-review"),
    JSON.stringify("Review after restore."),
  ));
  const second = await nextProviderRequest(restored);
  assert.equal(userTexts(second).at(-1).includes("Review after restore."), true);
  finishTextRequest(restored, second, "PASS — restored Skill turn.");
  const secondStatus = await waitForTerminal(restored);
  assert.equal(secondStatus.turnCount, 2);
  assert.deepEqual(JSON.parse(call(restored, "closeJson")), { ok: true, closed: true });
});

async function runScenario(context, kind) {
  JSON.parse(call(
    context,
    "startNativeOpenRouterScenarioJson",
    JSON.stringify(kind),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const requestsSeen = [];
  const cancellationsSeen = [];
  let stopIssued = false;
  for (let attempt = 0; attempt < 1_000; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderRequestsJson"));
    for (const request of requests) {
      requestsSeen.push(request);
      if (kind === "stop" && !stopIssued) {
        JSON.parse(call(context, "abortNativeOpenRouterScenarioJson"));
        stopIssued = true;
        continue;
      }
      if (kind === "provider_error") {
        JSON.parse(call(
          context,
          "failNativeProviderRequestJson",
          JSON.stringify(request.id),
          JSON.stringify("OpenRouter test provider failed"),
        ));
        continue;
      }
      if (kind === "tool" && requestsSeen.length === 1) {
        pushChunk(context, request.id, {
          id: "gen-tool",
          model: "deepseek/deepseek-v4-pro",
          choices: [{
            delta: {
              tool_calls: [{
                index: 0,
                id: "call-native-1",
                function: {
                  name: "mobile_fixture_echo",
                  arguments: "{\"text\":",
                },
              }],
            },
          }],
        });
        pushChunk(context, request.id, {
          id: "gen-tool",
          choices: [{
            delta: {
              tool_calls: [{
                index: 0,
                function: { arguments: "\"native\"}" },
              }],
            },
            finish_reason: "tool_calls",
          }],
          usage: {
            prompt_tokens: 10,
            completion_tokens: 4,
            total_tokens: 14,
          },
        });
        completeRequest(context, request.id, "gen-tool");
      } else {
        const text = kind === "tool"
          ? "Android native Provider tool complete"
          : "Hello from Android native Provider";
        pushChunk(context, request.id, {
          id: `gen-${kind}`,
          model: "deepseek/deepseek-v4-pro",
          choices: [{ delta: { content: text.slice(0, 12) } }],
        });
        pushChunk(context, request.id, {
          id: `gen-${kind}`,
          choices: [{
            delta: { content: text.slice(12) },
            finish_reason: "stop",
          }],
          usage: {
            prompt_tokens: 8,
            completion_tokens: 6,
            total_tokens: 14,
          },
        });
        completeRequest(context, request.id, `gen-${kind}`);
      }
    }

    const toolRequests = JSON.parse(
      call(context, "drainNativeProviderToolRequestsJson"),
    );
    for (const request of toolRequests) {
      JSON.parse(call(
        context,
        "resolveNativeProviderToolRequestJson",
        JSON.stringify(request.id),
        JSON.stringify(JSON.stringify({
          echoed: request.arguments.text,
          executor: "android-local-mock",
        })),
      ));
    }
    cancellationsSeen.push(
      ...JSON.parse(call(context, "drainNativeProviderCancellationsJson")),
    );
    const status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
    if (status.terminal) {
      return {
        status,
        requests: requestsSeen,
        cancellations: cancellationsSeen,
      };
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error(`Native Provider scenario ${kind} did not settle`);
}

async function bootRuntime() {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({
    console,
    crypto: webcrypto,
  });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  JSON.parse(call(context, "bootstrapJson"));
  return context;
}

function validSkillDocument() {
  return [
    "---",
    "name: mobile-review",
    "description: Reviews a bounded Android change",
    "disable-model-invocation: true",
    "---",
    "# Review",
    "Return PASS or FAIL with one reason.",
  ].join("\n");
}

async function parseSkillDocument(context, content) {
  const started = JSON.parse(call(
    context,
    "beginSkillDocumentParseJson",
    JSON.stringify(content),
  ));
  assert.equal(started.phase, "parsing");
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const status = JSON.parse(call(
      context,
      "skillDocumentParseStatusJson",
      JSON.stringify(started.parseId),
    ));
    if (status.phase !== "parsing") {
      assert.deepEqual(
        JSON.parse(call(
          context,
          "clearSkillDocumentParseJson",
          JSON.stringify(started.parseId),
        )),
        { ok: true, cleared: true },
      );
      assert.throws(
        () => call(
          context,
          "skillDocumentParseStatusJson",
          JSON.stringify(started.parseId),
        ),
        /PI_MOBILE_SKILL_PARSE_NOT_FOUND/,
      );
      return status;
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Skill document parse did not settle");
}

async function nextProviderRequest(context) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderRequestsJson"));
    if (requests.length > 0) {
      assert.equal(requests.length, 1);
      return requests[0];
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Provider request did not arrive");
}

async function nextProviderRequests(context, count) {
  const requests = [];
  for (let attempt = 0; attempt < 400 && requests.length < count; attempt += 1) {
    requests.push(...JSON.parse(call(context, "drainNativeProviderRequestsJson")));
    if (requests.length < count) await new Promise((resolve) => setImmediate(resolve));
  }
  assert.equal(requests.length, count, `expected ${count} Provider requests`);
  return requests;
}

async function nextProviderCancellation(context) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const cancellations = JSON.parse(
      call(context, "drainNativeProviderCancellationsJson"),
    );
    if (cancellations.length > 0) {
      assert.equal(cancellations.length, 1);
      return cancellations[0];
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Provider cancellation did not arrive");
}

async function nextProviderCancellations(context, count) {
  const cancellations = [];
  for (let attempt = 0; attempt < 400 && cancellations.length < count; attempt += 1) {
    cancellations.push(
      ...JSON.parse(call(context, "drainNativeProviderCancellationsJson")),
    );
    if (cancellations.length < count) await new Promise((resolve) => setImmediate(resolve));
  }
  assert.equal(cancellations.length, count, `expected ${count} Provider cancellations`);
  return cancellations;
}

async function nextNativeToolRequest(context) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderToolRequestsJson"));
    if (requests.length > 0) {
      assert.equal(requests.length, 1);
      return requests[0];
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Android tool request did not arrive");
}

async function waitForTerminal(context) {
  for (let attempt = 0; attempt < 400; attempt += 1) {
    const status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
    if (status.terminal) return status;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Provider task did not settle");
}

function finishTextRequest(context, request, text) {
  pushChunk(context, request.id, {
    id: `generation-${request.id}`,
    model: request.modelId,
    choices: [{
      delta: { content: text },
      finish_reason: "stop",
    }],
  });
  completeRequest(context, request.id, `generation-${request.id}`);
}

function finishTextRequestWithUsage(context, request, text, usage) {
  pushChunk(context, request.id, {
    id: `generation-${request.id}`,
    model: request.modelId,
    choices: [{
      delta: { content: text },
      finish_reason: "stop",
    }],
    usage,
  });
  completeRequest(context, request.id, `generation-${request.id}`);
}

function finishToolCall(context, request, callId, toolName, args) {
  pushChunk(context, request.id, {
    id: `generation-${request.id}`,
    model: request.modelId,
    choices: [{
      delta: {
        tool_calls: [{
          index: 0,
          id: callId,
          function: {
            name: toolName,
            arguments: JSON.stringify(args),
          },
        }],
      },
      finish_reason: "tool_calls",
    }],
  });
  completeRequest(context, request.id, `generation-${request.id}`);
}

function finishParallelToolCalls(context, request, calls) {
  pushChunk(context, request.id, {
    id: `generation-${request.id}`,
    model: request.modelId,
    choices: [{
      delta: {
        tool_calls: calls.map((call, index) => ({
          index,
          id: call.id,
          function: {
            name: call.name,
            arguments: JSON.stringify(call.arguments),
          },
        })),
      },
      finish_reason: "tool_calls",
    }],
  });
  completeRequest(context, request.id, `generation-${request.id}`);
}

function resolveNativeTool(context, requestId, result) {
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(requestId),
    JSON.stringify(JSON.stringify(result)),
  ));
}

function childEventAcknowledgements(events) {
  const byParent = new Map();
  for (const event of events) {
    const batch = byParent.get(event.parentToolCallId) ?? [];
    batch.push(event);
    byParent.set(event.parentToolCallId, batch);
  }
  return [...byParent.values()].map((batch) => {
    const last = [...batch].sort((left, right) => left.eventOrdinal - right.eventOrdinal).at(-1);
    return {
      parentTaskId: last.parentTaskId,
      parentToolCallId: last.parentToolCallId,
      childId: last.childId,
      childName: last.childName,
      throughEventOrdinal: last.eventOrdinal,
      throughDigest: createHash("sha256")
        .update(JSON.stringify(last.event), "utf8")
        .digest("hex"),
    };
  });
}

function ackChildEvents(context, events) {
  const result = JSON.parse(call(
    context,
    "acknowledgeNativeOpenRouterChildEventsJson",
    JSON.stringify(JSON.stringify(childEventAcknowledgements(events))),
  ));
  return result.acknowledgedEventCount;
}

function userTexts(request) {
  return request.messages
    .filter((message) => message.role === "user")
    .map((message) => message.content);
}

function assertCompleteFilePreconditionSchema(request) {
  const prepareTool = request.tools.find(
    (tool) => tool.function.name === "device_files_prepare_changes",
  );
  assert.ok(prepareTool, "phone-local task must expose the Android file prepare tool");
  const writeFile = prepareTool.function.parameters.properties.operations.items.anyOf.find(
    (operation) => operation.properties.kind.const === "write_file",
  );
  const expected = writeFile.properties.expected;
  assert.deepEqual(
    [...expected.required].sort(),
    ["byteCount", "displayName", "lastModifiedMillis", "mimeType"],
  );
  assert.equal(
    expected.properties.byteCount.anyOf.some((shape) => shape.type === "null"),
    true,
  );
  assert.equal(
    expected.properties.lastModifiedMillis.anyOf.some((shape) => shape.type === "null"),
    true,
  );
}

function pushChunk(context, requestId, chunk) {
  JSON.parse(call(
    context,
    "pushNativeProviderChunkJson",
    JSON.stringify(requestId),
    JSON.stringify(JSON.stringify(chunk)),
  ));
}

function completeRequest(context, requestId, generationId) {
  JSON.parse(call(
    context,
    "completeNativeProviderRequestJson",
    JSON.stringify(requestId),
    JSON.stringify(generationId),
  ));
}

function call(context, functionName, ...serializedArguments) {
  return runInContext(
    `PiMobileRuntimeBundle.${functionName}(${serializedArguments.join(",")})`,
    context,
  );
}
