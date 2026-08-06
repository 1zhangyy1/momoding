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
      delta: { content: "Momoding is an Android action agent." },
      finish_reason: "stop",
    }],
  });
  completeRequest(context, requests[0].id, "gen-prompt");
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
    if (status.terminal) {
      assert.equal(status.kind, "prompt");
      assert.equal(status.expectationMet, true, JSON.stringify(status));
      assert.equal(status.finalText, "Momoding is an Android action agent.");
      assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
      return;
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Provider prompt did not settle");
});

test("OpenRouter web search annotations become bounded task events without persisting excerpts", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-web-search"),
    JSON.stringify("What changed today?"),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const request = await nextProviderRequest(context);
  const annotation = {
    type: "url_citation",
    url_citation: {
      url: "https://example.com/latest",
      title: "Latest update",
      content: "untrusted webpage excerpt that must not be persisted",
      start_index: 0,
      end_index: 13,
    },
  };
  pushChunk(context, request.id, {
    id: "generation-web-search",
    choices: [{
      delta: {
        content: "Grounded answer",
        annotations: [annotation, annotation],
      },
      finish_reason: "stop",
    }],
    usage: {
      prompt_tokens: 8,
      completion_tokens: 4,
      total_tokens: 12,
      server_tool_use_details: {
        tool_calls_executed: 1,
        tool_calls_requested: 1,
        web_search_requests: 1,
      },
    },
  });
  completeRequest(context, request.id, "generation-web-search");

  const status = await waitForTerminal(context);
  const events = status.events.filter((event) => event.type === "provider_web_activity");
  assert.deepEqual(events.map((event) => event.state), ["running", "completed"]);
  assert.equal(events[1].searchRequests, 1);
  assert.equal(events[1].sources.length, 1);
  assert.deepEqual(events[1].sources[0], {
    url: "https://example.com/latest",
    title: "Latest update",
    domain: "example.com",
    startIndex: 0,
    endIndex: 13,
  });
  assert.equal(JSON.stringify(events).includes("untrusted webpage excerpt"), false);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("current OpenRouter server tool usage emits a search activity without citations", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-web-search-usage"),
    JSON.stringify("Search for the latest release"),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const request = await nextProviderRequest(context);
  finishTextRequestWithUsage(context, request, "Grounded answer without citations", {
    prompt_tokens: 8,
    completion_tokens: 4,
    total_tokens: 12,
    server_tool_use_details: {
      tool_calls_executed: 2,
      tool_calls_requested: 2,
      web_search_requests: 2,
    },
  });

  const status = await waitForTerminal(context);
  const events = status.events.filter((event) => event.type === "provider_web_activity");
  assert.deepEqual(events.map((event) => event.state), ["running", "completed"]);
  assert.equal(events[1].searchRequests, 2);
  assert.deepEqual(events[1].sources, []);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("current OpenRouter generic server-tool usage becomes one bounded web activity", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-web-fetch-usage"),
    JSON.stringify("Read https://example.com/docs"),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const request = await nextProviderRequest(context);
  finishTextRequestWithUsage(context, request, "The page explains the API.", {
    prompt_tokens: 8,
    completion_tokens: 4,
    total_tokens: 12,
    server_tool_use_details: {
      tool_calls_executed: 1,
      tool_calls_requested: 1,
    },
  });

  const status = await waitForTerminal(context);
  const events = status.events.filter((event) => event.type === "provider_web_activity");
  assert.deepEqual(events.map((event) => event.state), ["running", "completed"]);
  assert.equal(events[1].webRequests, 1);
  assert.equal(events[1].fetchRequests, undefined);
  assert.equal(events[1].searchRequests, undefined);
  assert.deepEqual(events[1].sources, []);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("malformed OpenRouter web citation fails the provider stream closed", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-web-search-invalid"),
    JSON.stringify("Search the web"),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const request = await nextProviderRequest(context);
  pushChunk(context, request.id, {
    id: "generation-web-search-invalid",
    choices: [{
      delta: {
        annotations: [{ type: "url_citation", url_citation: { url: 42 } }],
      },
    }],
  });

  const status = await waitForTerminal(context);
  assert.equal(status.providerRequestsFailed, 1);
  assert.equal(status.providerError, "OpenRouter returned an invalid stream");
  assert.equal(status.events.some((event) => event.type === "provider_web_activity"), false);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("Stop cancels an observed web search without replaying provider work", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-web-search-stop"),
    JSON.stringify("Search, then stop"),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const request = await nextProviderRequest(context);
  pushChunk(context, request.id, {
    id: "generation-web-search-stop",
    choices: [],
    usage: {
      prompt_tokens: 2,
      completion_tokens: 0,
      total_tokens: 2,
      server_tool_use: { web_search_requests: 1 },
    },
  });
  JSON.parse(call(context, "abortNativeOpenRouterScenarioJson"));

  const status = await waitForTerminal(context);
  const events = status.events.filter((event) => event.type === "provider_web_activity");
  assert.deepEqual(events.map((event) => event.state), ["running", "cancelled"]);
  assert.equal(status.providerRequestsIssued, 1);
  assert.equal(status.providerCancellationsIssued, 1);
  assert.equal(status.lateProviderRequestsAfterStop, 0);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
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
  assert.equal(status.sessionEntryCount, 3);
  const firstSnapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  assert.deepEqual(
    firstSnapshot.entries.find((entry) => entry.customType === "pi_mobile_provider_binding")?.data,
    { kind: "openrouter", modelId: "deepseek/deepseek-v4-pro" },
  );

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
  assert.equal(status.sessionEntryCount, 5);

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
  assert.equal(status.sessionEntryCount, 7);
  assert.equal(status.providerRequestsIssued, 1);
  assert.equal(status.commandError, null);

  const snapshot = JSON.parse(
    call(context, "nativeOpenRouterTaskSessionSnapshotJson"),
  );
  assert.equal(snapshot.taskId, taskId);
  assert.equal(snapshot.turnCount, 3);
  assert.equal(snapshot.entries.length, 7);
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
  const systemPrompt = first.messages.find((message) => message.role === "system")?.content;
  assert.equal(typeof systemPrompt, "string");
  assert.match(systemPrompt, /^You are Momoding, an action agent/);
  assert.match(systemPrompt, /persistent, multi-turn tasks/);
  assert.match(systemPrompt, /Coding is one capability, not your identity/);
  assert.match(systemPrompt, /Respond in the user's language/);
  assert.match(systemPrompt, /Never claim an action succeeded/);
  assert.doesNotMatch(systemPrompt, /coding agent|apk add/);
  assert.ok(systemPrompt.length <= 1_200, `base prompt too large: ${systemPrompt.length}`);
  assert.match(commandTool.function.description, /persistent \/workspace/);
  assert.match(commandTool.function.description, /device_files_commit_changes/);
  const commitTool = first.tools.find(
    (tool) => tool.function.name === "device_files_commit_changes",
  );
  assert.ok(commitTool);
  assert.match(commitTool.function.description, /No real Android file changed until this tool succeeds/);

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
  assert.equal(restored.sessionEntryCount, 7);
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
  const taskId = "task-native-images";
  const sessionId = "session-native-images";
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

test("image generation is opt-in and persists only an attachment reference", async () => {
  const disabled = await bootRuntime();
  JSON.parse(call(
    disabled,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-image-disabled"),
    JSON.stringify("Draw a cat."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const disabledRequest = await nextProviderRequest(disabled);
  assert.equal(
    disabledRequest.tools.some((tool) => tool.function.name === "image_generate"),
    false,
  );
  finishTextRequest(disabled, disabledRequest, "Image generation is not enabled.");
  await waitForTerminal(disabled);
  assert.deepEqual(JSON.parse(call(disabled, "closeJson")), { ok: true, closed: true });

  const context = await bootRuntime();
  const taskId = "task-image-enabled";
  const sessionId = "session-image-enabled";
  const attachmentId = "77777777-7777-4777-8777-777777777777";
  const imageData = "/9j/2Q==";
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Draw a small green robot."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify(sessionId),
    "false",
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    "true",
  ));
  const first = await nextProviderRequest(context);
  const imageTool = first.tools.find((tool) => tool.function.name === "image_generate");
  assert.ok(imageTool);
  assert.equal(first.tools.length, 25);
  assert.equal(imageTool.function.parameters.additionalProperties, false);
  assert.deepEqual(imageTool.function.parameters.required, ["prompt"]);
  finishToolCall(
    context,
    first,
    "call-image-generate",
    "image_generate",
    { prompt: "A small green robot", aspect_ratio: "1:1" },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_image_generation_tool");
  const artifact = {
    ok: true,
    kind: "generated_image_artifact",
    persistent: true,
    attachmentId,
    displayName: "Momoding image.jpg",
    model: "openai/gpt-image-2",
    mimeType: "image/jpeg",
    byteSize: 4,
    sha256: "a".repeat(64),
  };
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeRequest.id),
    JSON.stringify(JSON.stringify(artifact)),
    JSON.stringify(JSON.stringify(artifact)),
    "false",
    JSON.stringify(JSON.stringify([
      { type: "text", text: JSON.stringify(artifact) },
    ])),
  ));

  const followUp = await nextProviderRequest(context);
  assert.equal(JSON.stringify(followUp.messages).includes(imageData), false);
  assert.match(JSON.stringify(followUp.messages), new RegExp(attachmentId));
  finishTextRequest(context, followUp, "Created the image.");
  await waitForTerminal(context);
  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persisted = JSON.stringify(snapshot);
  assert.equal(persisted.includes(imageData), false);
  assert.match(persisted, new RegExp(attachmentId));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });

  const restoredContext = await bootRuntime();
  const restored = JSON.parse(call(
    restoredContext,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(sessionId),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    "true",
  ));
  assert.equal(restored.terminal, true);
  assert.deepEqual(JSON.parse(call(restoredContext, "drainNativeProviderRequestsJson")), []);
  assert.deepEqual(JSON.parse(call(restoredContext, "closeJson")), { ok: true, closed: true });
});

test("text attachments stay as Pi Session metadata and read through the Android task tool", async () => {
  const firstProcess = await bootRuntime();
  const taskId = "task-text-attachment";
  const sessionId = "session-text-attachment";
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
  const attachmentTool = first.tools.find(
    (tool) => tool.function.name === "attachment_read",
  );
  assert.ok(attachmentTool);
  assert.match(attachmentTool.function.description, /nextOffset until eof/);
  const decorated = userTexts(first).at(-1);
  assert.equal(decorated.includes("Summarize the attached context."), true);
  assert.equal(decorated.includes("attachment_read"), true);
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
      JSON.stringify("task-missing-image"),
      JSON.stringify("session-missing-image"),
      JSON.stringify(1),
      JSON.stringify(JSON.stringify([{
        id: "entry-user",
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
  const taskId = "task-plan";
  const sessionId = "session-plan";
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
      "device_location",
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
    JSON.stringify("task-child-overlap"),
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
    assert.equal(request.parentTaskId, "task-child-overlap");
    assert.equal(request.tools, undefined, "child must not receive nested or side-effect tools");
    assert.equal(userTexts(request).length, 1, "each child must have isolated context");
    assert.equal(userTexts(request).includes("Compare two Android release risks."), false);
    assert.equal(
      request.messages.some((message) =>
        message.role === "system" &&
        message.content.includes("read-only child analysis agent") &&
        message.content.includes("working for Momoding") &&
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

test("current location is typed for the LLM and expires after one provider turn", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-live-location"),
    JSON.stringify("Estimate my current area."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  const locationTool = first.tools.find((tool) =>
    tool.function.name === "device_location"
  );
  assert.ok(locationTool);
  assert.equal(locationTool.function.parameters.additionalProperties, false);
  assert.deepEqual(
    locationTool.function.parameters.required,
    ["action", "precision", "purpose"],
  );
  assert.equal(locationTool.function.parameters.properties.action.const, "get_current");
  assert.deepEqual(
    locationTool.function.parameters.properties.precision.enum,
    ["approximate", "precise"],
  );

  finishToolCall(
    context,
    first,
    "call-live-location",
    "device_location",
    {
      action: "get_current",
      precision: "approximate",
      purpose: "Estimate my current area",
    },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_location_tool");
  assert.equal(nativeRequest.toolName, "device_location");
  const payload = {
    ok: true,
    action: "get_current",
    data: {
      precision: "approximate",
      latitude: 31.23,
      longitude: 121.47,
      accuracyMeters: 1000,
      capturedAt: "2026-07-29T04:00:00Z",
      ageMillis: 1000,
      providerCategory: "network",
    },
    verification: {
      status: "observed",
      observedAt: "2026-07-29T04:00:01Z",
    },
  };
  const payloadText = JSON.stringify(payload);
  const digest = createHash("sha256").update(payloadText, "utf8").digest("hex");
  const details = {
    liveOnly: true,
    dataClass: "location",
    contentSha256: digest,
    precision: "approximate",
  };
  assert.throws(
    () => JSON.parse(call(
      context,
      "resolveNativeProviderToolRequestJson",
      JSON.stringify(nativeRequest.id),
      JSON.stringify(payloadText),
      JSON.stringify("{}"),
      "false",
    )),
    /PI_MOBILE_LIVE_TEXT_DETAILS_INVALID/,
  );
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeRequest.id),
    JSON.stringify(payloadText),
    JSON.stringify(JSON.stringify(details)),
    "false",
  ));

  const followUp = await nextProviderRequest(context);
  const providerMessages = JSON.stringify(followUp.messages);
  assert.match(providerMessages, /31\.23/);
  assert.match(providerMessages, /121\.47/);
  finishTextRequest(context, followUp, "You are currently in the estimated area.");
  const status = await waitForTerminal(context);
  const events = JSON.stringify(status.runEvents);
  assert.equal(events.includes("31.23"), false);
  assert.equal(events.includes("121.47"), false);
  assert.match(events, /live Android location expired/);
  assert.match(events, new RegExp(digest));

  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persisted = JSON.stringify(snapshot);
  assert.equal(persisted.includes("31.23"), false);
  assert.equal(persisted.includes("121.47"), false);
  assert.match(persisted, /live Android location expired/);
  assert.match(persisted, new RegExp(digest));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("clipboard uses strict action branches and expires read text after one provider turn", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-live-clipboard"),
    JSON.stringify("Read the text I just copied."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  assert.ok(first.tools.length <= 24);
  assert.ok(Buffer.byteLength(JSON.stringify(first.tools), "utf8") <= 36 * 1024);
  const clipboardTool = first.tools.find((tool) =>
    tool.function.name === "device_clipboard"
  );
  assert.ok(clipboardTool);
  assert.equal(clipboardTool.function.parameters.additionalProperties, undefined);
  assert.deepEqual(
    clipboardTool.function.parameters.oneOf.map((branch) => ({
      action: branch.properties.action.const,
      required: branch.required,
      additionalProperties: branch.additionalProperties,
    })),
    [
      { action: "get", required: ["action", "purpose"], additionalProperties: false },
      {
        action: "set",
        required: ["action", "purpose", "text"],
        additionalProperties: false,
      },
      { action: "clear", required: ["action", "purpose"], additionalProperties: false },
    ],
  );
  assert.equal(
    clipboardTool.function.parameters.oneOf[1].properties.text.maxLength,
    4096,
  );
  assert.ok(JSON.stringify(clipboardTool.function.parameters).length < 4096);

  finishToolCall(
    context,
    first,
    "call-live-clipboard",
    "device_clipboard",
    {
      action: "get",
      purpose: "Read the text the user just copied",
    },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_clipboard_tool");
  assert.equal(nativeRequest.toolName, "device_clipboard");
  const secretText = "ordinary clipboard note 7342f2";
  const payload = {
    ok: true,
    action: "get",
    data: {
      state: "text",
      text: secretText,
      characterCount: secretText.length,
    },
    verification: {
      status: "observed",
      observedAt: "2026-07-29T05:00:00Z",
    },
  };
  const payloadText = JSON.stringify(payload);
  const digest = createHash("sha256").update(payloadText, "utf8").digest("hex");
  assert.throws(
    () => JSON.parse(call(
      context,
      "resolveNativeProviderToolRequestJson",
      JSON.stringify(nativeRequest.id),
      JSON.stringify(payloadText),
      JSON.stringify("{}"),
      "false",
    )),
    /PI_MOBILE_LIVE_TEXT_DETAILS_INVALID/,
  );
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeRequest.id),
    JSON.stringify(payloadText),
    JSON.stringify(JSON.stringify({
      liveOnly: true,
      dataClass: "clipboard",
      contentSha256: digest,
    })),
    "false",
  ));

  const followUp = await nextProviderRequest(context);
  assert.match(JSON.stringify(followUp.messages), new RegExp(secretText));
  finishTextRequest(context, followUp, "The clipboard contains an ordinary note.");
  const status = await waitForTerminal(context);
  const events = JSON.stringify(status.runEvents);
  assert.equal(events.includes(secretText), false);
  assert.match(events, /live Android clipboard expired/);
  assert.match(events, new RegExp(digest));

  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  const persisted = JSON.stringify(snapshot);
  assert.equal(persisted.includes(secretText), false);
  assert.match(persisted, /live Android clipboard expired/);
  assert.match(persisted, new RegExp(digest));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("notification uses one bounded domain tool and forwards an exact native request", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-notification-contract"),
    JSON.stringify("Notify me that the export finished."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const provider = await nextProviderRequest(context);
  assert.ok(provider.tools.length <= 24);
  assert.ok(Buffer.byteLength(JSON.stringify(provider.tools), "utf8") <= 36 * 1024);
  const tool = provider.tools.find((entry) =>
    entry.function.name === "device_notification"
  );
  assert.ok(tool);
  assert.ok(JSON.stringify(tool.function.parameters).length < 4096);
  assert.deepEqual(
    tool.function.parameters.oneOf.map((branch) => ({
      action: branch.properties.action.const,
      required: branch.required,
      additionalProperties: branch.additionalProperties,
    })),
    [
      { action: "status", required: ["action"], additionalProperties: false },
      {
        action: "post",
        required: ["action", "title", "message"],
        additionalProperties: false,
      },
      { action: "list_active", required: ["action"], additionalProperties: false },
      {
        action: "update",
        required: ["action", "notificationHandle", "title", "message"],
        additionalProperties: false,
      },
      {
        action: "cancel",
        required: ["action", "notificationHandle"],
        additionalProperties: false,
      },
      { action: "open_settings", required: ["action"], additionalProperties: false },
    ],
  );
  assert.equal(tool.function.parameters.oneOf[1].properties.title.maxLength, 80);
  assert.equal(tool.function.parameters.oneOf[1].properties.message.maxLength, 240);
  assert.equal(tool.function.parameters.oneOf[2].properties.limit.maximum, 20);
  assert.equal(
    tool.function.parameters.oneOf[3].properties.notificationHandle.pattern,
    "^notification-[0-9a-f]{32}$",
  );

  finishToolCall(
    context,
    provider,
    "call-notification-post",
    "device_notification",
    {
      action: "post",
      title: "Export complete",
      message: "The requested export is ready.",
    },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_notification_tool");
  assert.equal(nativeRequest.toolName, "device_notification");
  assert.deepEqual(nativeRequest.arguments, {
    action: "post",
    title: "Export complete",
    message: "The requested export is ready.",
  });
  resolveNativeTool(context, nativeRequest.id, {
    ok: true,
    action: "post",
    data: {
      notificationHandle: "notification-0123456789abcdef0123456789abcdef",
      state: "active",
    },
    verification: {
      status: "verified",
      observedAt: "2026-07-29T06:00:00Z",
      planDigest: "a".repeat(64),
    },
  });
  const followUp = await nextProviderRequest(context);
  assert.match(JSON.stringify(followUp.messages), /notificationHandle/);
  finishTextRequest(context, followUp, "The notification was posted.");
  await waitForTerminal(context);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
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

test("missing Android capability can open the exact native setup flow and resume Pi", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-capability-request"),
    JSON.stringify("Inspect the current Android interface."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));

  const provider = await nextProviderRequest(context);
  const schema = provider.tools.find(
    (tool) => tool.function.name === "device_capability_request",
  )?.function.parameters;
  assert.ok(schema);
  assert.equal(schema.type, "object");
  assert.equal(schema.oneOf.length, 4);
  const ordinaryCapability = schema.oneOf[0];
  const calendarCapability = schema.oneOf[1];
  const contactsCapability = schema.oneOf[2];
  const locationCapability = schema.oneOf[3];
  assert.deepEqual(ordinaryCapability.required, ["capability", "purpose"]);
  assert.equal(ordinaryCapability.additionalProperties, false);
  assert.deepEqual(ordinaryCapability.properties.capability.enum, [
    "saf_folders",
    "photo_library",
    "accessibility_control",
    "screen_capture",
    "all_files",
    "shizuku_shell_uid",
    "notifications",
  ]);
  assert.deepEqual(
    calendarCapability.required,
    ["capability", "requiredAccess", "purpose"],
  );
  assert.equal(calendarCapability.additionalProperties, false);
  assert.equal(calendarCapability.properties.capability.const, "calendar");
  assert.deepEqual(
    calendarCapability.properties.requiredAccess.enum,
    ["read", "write"],
  );
  assert.equal(contactsCapability.properties.capability.const, "contacts");
  assert.deepEqual(
    contactsCapability.properties.requiredAccess.enum,
    ["read", "write"],
  );
  assert.equal(locationCapability.properties.capability.const, "location");
  assert.deepEqual(
    locationCapability.properties.requiredAccess.enum,
    ["approximate", "precise"],
  );

  finishToolCall(
    context,
    provider,
    "call-capability-request",
    "device_capability_request",
    {
      capability: "accessibility_control",
      purpose: "Inspect the current screen",
    },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_capability_tool");
  assert.equal(nativeRequest.toolName, "device_capability_request");
  assert.deepEqual(nativeRequest.arguments, {
    capability: "accessibility_control",
    purpose: "Inspect the current screen",
  });
  resolveNativeTool(context, nativeRequest.id, {
    capability: "accessibility_control",
    availability: "ready",
    ready: true,
    requested: true,
  });

  const resumed = await nextProviderRequest(context);
  assert.match(JSON.stringify(resumed.messages), /accessibility_control/);
  finishTextRequest(context, resumed, "Accessibility control is ready.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("Calendar capability carries typed access and rejects missing access before Android", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-calendar-capability-request"),
    JSON.stringify("Find tomorrow's meetings."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));

  let provider = await nextProviderRequest(context);
  finishToolCall(
    context,
    provider,
    "call-calendar-capability",
    "device_capability_request",
    {
      capability: "calendar",
      requiredAccess: "read",
      purpose: "Find tomorrow's meetings",
    },
  );
  const nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_capability_tool");
  assert.deepEqual(nativeRequest.arguments, {
    capability: "calendar",
    requiredAccess: "read",
    purpose: "Find tomorrow's meetings",
  });
  resolveNativeTool(context, nativeRequest.id, {
    capability: "calendar",
    requiredAccess: "read",
    availability: "partial",
    ready: true,
    requested: true,
  });

  provider = await nextProviderRequest(context);
  finishToolCall(
    context,
    provider,
    "call-calendar-capability-invalid",
    "device_capability_request",
    {
      capability: "calendar",
      purpose: "SENSITIVE_PURPOSE_MUST_NOT_BE_ECHOED",
    },
  );
  provider = await nextProviderRequest(context);
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderToolRequestsJson")), []);
  const failure = provider.messages.find(
    (message) =>
      message.role === "tool" &&
      message.tool_call_id === "call-calendar-capability-invalid",
  );
  assert.ok(failure);
  assert.match(failure.content, /"code":"INVALID_ARGUMENTS"/);
  assert.doesNotMatch(failure.content, /SENSITIVE_PURPOSE/);

  finishTextRequest(context, provider, "Calendar read access is ready.");
  const status = await waitForTerminal(context);
  assert.equal(status.toolRequestsIssued, 1);
  assert.equal(status.toolRequestsResolved, 1);
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

test("device media exposes three strict handle-based consent mutations", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-media-mutation"),
    JSON.stringify("Favorite the selected photo."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const first = await nextProviderRequest(context);
  const mediaTool = first.tools.find((tool) => tool.function.name === "device_media");
  assert.ok(mediaTool);
  assert.deepEqual(
    mediaTool.function.parameters.oneOf.map((branch) => branch.properties.action.const),
    ["set_favorite", "set_trashed", "delete"],
  );
  assert.ok(
    mediaTool.function.parameters.oneOf.every(
      (branch) => branch.additionalProperties === false &&
        branch.required.includes("mediaHandle"),
    ),
  );
  finishToolCall(
    context,
    first,
    "call-media-favorite",
    "device_media",
    { action: "set_favorite", mediaHandle: `media-${"a".repeat(24)}`, favorite: true },
  );
  const request = await nextNativeToolRequest(context);
  assert.equal(request.kind, "android_media_tool");
  assert.equal(request.toolName, "device_media");
  assert.deepEqual(request.arguments, {
    action: "set_favorite",
    mediaHandle: `media-${"a".repeat(24)}`,
    favorite: true,
  });
  resolveNativeTool(context, request.id, {
    ok: true,
    action: "set_favorite",
    data: { changed: true, favorite: true },
    verification: {
      status: "verified",
      observedAt: "2026-07-29T08:00:00Z",
      planDigest: "b".repeat(64),
    },
  });
  const finalTurn = await nextProviderRequest(context);
  assert.match(JSON.stringify(finalTurn.messages), /verified/);
  finishTextRequest(context, finalTurn, "The photo is now a favorite.");
  const status = await waitForTerminal(context);
  assert.equal(status.expectationMet, true, JSON.stringify(status));
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("calendar exposes six strict action branches and completes one deterministic fake chain", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-calendar-contract"),
    JSON.stringify("Review and change the project calendar."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(context);
  const calendar = provider.tools.find((tool) => tool.function.name === "device_calendar");
  assert.ok(calendar);
  assert.equal(calendar.function.parameters.type, "object");
  assert.equal(calendar.function.parameters.oneOf.length, 6);
  assert.deepEqual(
    calendar.function.parameters.oneOf.map((branch) => branch.properties.action.const),
    [
      "list_calendars",
      "list_events",
      "get_event",
      "create_event",
      "update_event",
      "delete_event",
    ],
  );
  for (const branch of calendar.function.parameters.oneOf) {
    assert.equal(branch.type, "object");
    assert.equal(branch.additionalProperties, false);
    assert.equal(branch.required.includes("action"), true);
    assert.equal(branch.required.includes("purpose"), true);
  }
  const rfc3339Pattern =
    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$";
  const listEvents = calendar.function.parameters.oneOf[1];
  const createEvent = calendar.function.parameters.oneOf[3];
  assert.equal(listEvents.properties.start.pattern, rfc3339Pattern);
  assert.equal(listEvents.properties.end.pattern, rfc3339Pattern);
  assert.equal(createEvent.properties.schedule.oneOf[0].properties.start.pattern, rfc3339Pattern);
  assert.equal(createEvent.properties.schedule.oneOf[0].properties.end.pattern, rfc3339Pattern);
  assert.ok(
    Buffer.byteLength(JSON.stringify(calendar), "utf8") <= 6 * 1024,
    "Calendar Tool definition must stay within its context budget",
  );

  const calendarHandle = "calendar-111111111111111111111111";
  const eventHandle = "event-222222222222222222222222";
  const createdHandle = "event-333333333333333333333333";
  const calls = [
    {
      callId: "calendar-list-calendars",
      arguments: { action: "list_calendars", purpose: "Find a writable project calendar" },
      result: {
        ok: true,
        action: "list_calendars",
        data: {
          items: [{ calendarHandle, displayName: "Project", writable: true, timeZone: "Asia/Shanghai" }],
          count: 1,
        },
        page: { truncated: false, nextCursor: null },
        verification: { status: "observed", observedAt: "2026-07-29T10:00:00+08:00" },
      },
    },
    {
      callId: "calendar-list-events",
      arguments: {
        action: "list_events",
        purpose: "Find the project review",
        start: "2026-07-31T00:00:00+08:00",
        end: "2026-08-01T00:00:00+08:00",
        calendarHandle,
        query: "review",
        cursor: null,
      },
      result: {
        ok: true,
        action: "list_events",
        data: {
          items: [{ eventHandle, title: "Project review", start: "2026-07-31T15:00:00+08:00" }],
          count: 1,
        },
        page: { truncated: false, nextCursor: null },
        verification: { status: "observed", observedAt: "2026-07-29T10:00:01+08:00" },
      },
    },
    {
      callId: "calendar-get-event",
      arguments: { action: "get_event", purpose: "Inspect the project review", eventHandle },
      result: {
        ok: true,
        action: "get_event",
        data: {
          item: {
            eventHandle,
            title: "Project review",
            schedule: {
              kind: "timed",
              start: "2026-07-31T15:00:00+08:00",
              end: "2026-07-31T15:30:00+08:00",
              timeZone: "Asia/Shanghai",
            },
          },
        },
        verification: { status: "observed", observedAt: "2026-07-29T10:00:02+08:00" },
      },
    },
    {
      callId: "calendar-create-event",
      arguments: {
        action: "create_event",
        purpose: "Schedule the approved follow-up",
        title: "Project follow-up",
        schedule: {
          kind: "timed",
          start: "2026-07-31T16:00:00+08:00",
          end: "2026-07-31T16:30:00+08:00",
          timeZone: "Asia/Shanghai",
        },
        location: null,
        description: null,
        calendarHandle,
      },
      result: {
        ok: true,
        action: "create_event",
        data: { change: "created", eventHandle: createdHandle, title: "Project follow-up" },
        verification: { status: "verified", observedAt: "2026-07-29T10:00:03+08:00" },
      },
    },
    {
      callId: "calendar-update-event",
      arguments: {
        action: "update_event",
        purpose: "Rename the approved follow-up",
        eventHandle: createdHandle,
        changes: { title: "Project follow-up notes" },
      },
      result: {
        ok: true,
        action: "update_event",
        data: { change: "updated", eventHandle: createdHandle, title: "Project follow-up notes" },
        verification: { status: "verified", observedAt: "2026-07-29T10:00:04+08:00" },
      },
    },
    {
      callId: "calendar-delete-event",
      arguments: {
        action: "delete_event",
        purpose: "Delete the temporary approved follow-up",
        eventHandle: createdHandle,
      },
      result: {
        ok: true,
        action: "delete_event",
        data: { change: "deleted", eventHandle: createdHandle },
        verification: { status: "verified", observedAt: "2026-07-29T10:00:05+08:00" },
      },
    },
  ];

  let firstTurnStatus = null;
  for (const [index, expected] of calls.entries()) {
    finishToolCall(
      context,
      provider,
      expected.callId,
      "device_calendar",
      expected.arguments,
    );
    const nativeRequest = await nextNativeToolRequest(context);
    assert.equal(nativeRequest.kind, "android_calendar_tool");
    assert.equal(nativeRequest.toolName, "device_calendar");
    assert.deepEqual(nativeRequest.arguments, expected.arguments);
    assert.equal(JSON.stringify(nativeRequest).includes("content://"), false);
    resolveNativeTool(context, nativeRequest.id, expected.result);
    provider = await nextProviderRequest(context);
    assert.match(JSON.stringify(provider.messages), new RegExp(expected.result.action));
    if (index === 2) {
      finishTextRequest(context, provider, "I found the event and retained only its opaque handles.");
      firstTurnStatus = await waitForTerminal(context);
      JSON.parse(call(
        context,
        "continueNativeOpenRouterTaskPromptJson",
        JSON.stringify("Create the follow-up, rename it, then delete the temporary event."),
      ));
      provider = await nextProviderRequest(context);
      assert.match(JSON.stringify(provider.messages), new RegExp(eventHandle));
    }
  }

  finishTextRequest(context, provider, "The deterministic Calendar contract chain completed.");
  const status = await waitForTerminal(context);
  assert.equal(status.turnCount, 2);
  assert.equal(firstTurnStatus.toolExecutionsStarted + status.toolExecutionsStarted, 6);
  assert.equal(firstTurnStatus.toolExecutionsEnded + status.toolExecutionsEnded, 6);
  assert.equal(firstTurnStatus.toolRequestsIssued + status.toolRequestsIssued, 6);
  assert.equal(firstTurnStatus.toolRequestsResolved + status.toolRequestsResolved, 6);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("contacts exposes five strict bounded branches and keeps opaque handles across turns", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-contacts-contract"),
    JSON.stringify("Find Alex in my contacts."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(context);
  const contacts = provider.tools.find((tool) => tool.function.name === "device_contacts");
  assert.ok(contacts);
  assert.equal(contacts.function.parameters.type, "object");
  assert.deepEqual(
    contacts.function.parameters.oneOf.map((branch) => branch.properties.action.const),
    ["search", "get_contact", "create_contact", "update_contact", "delete_contact"],
  );
  for (const branch of contacts.function.parameters.oneOf) {
    assert.equal(branch.type, "object");
    assert.equal(branch.additionalProperties, false);
    assert.equal(branch.required.includes("purpose"), true);
  }
  const updateBranch = contacts.function.parameters.oneOf.find(
    (branch) => branch.properties.action.const === "update_contact",
  );
  assert.equal(updateBranch.properties.changes.minProperties, 1);
  assert.ok(
    Buffer.byteLength(JSON.stringify(contacts), "utf8") <= 6 * 1024,
    "Contacts Tool definition must stay within its context budget",
  );

  finishToolCall(
    context,
    provider,
    "contacts-search",
    "device_contacts",
    {
      action: "search",
      purpose: "Find Alex",
      query: "Alex",
      cursor: null,
    },
  );
  const searchRequest = await nextNativeToolRequest(context);
  assert.equal(searchRequest.kind, "android_contacts_tool");
  assert.equal(searchRequest.toolName, "device_contacts");
  const contactHandle = "contact-111111111111111111111111";
  resolveNativeTool(context, searchRequest.id, {
    ok: true,
    action: "search",
    data: {
      items: [{
        contactHandle,
        displayName: "Alex Chen",
        primaryPhone: { value: "+8613800000000", label: "Mobile", primary: true },
        phoneCount: 1,
        emailCount: 1,
      }],
      count: 1,
    },
    page: { truncated: false, nextCursor: null },
    verification: { status: "observed", observedAt: "2026-07-29T10:00:00.000Z" },
  });
  provider = await nextProviderRequest(context);
  assert.match(JSON.stringify(provider.messages), new RegExp(contactHandle));
  finishTextRequest(context, provider, "I found Alex and kept only the task contact handle.");
  const firstTurn = await waitForTerminal(context);

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Read Alex's contact details."),
  ));
  provider = await nextProviderRequest(context);
  assert.match(JSON.stringify(provider.messages), new RegExp(contactHandle));
  finishToolCall(
    context,
    provider,
    "contacts-get",
    "device_contacts",
    {
      action: "get_contact",
      purpose: "Read the selected Alex contact",
      contactHandle,
    },
  );
  const getRequest = await nextNativeToolRequest(context);
  assert.equal(getRequest.kind, "android_contacts_tool");
  assert.deepEqual(getRequest.arguments, {
    action: "get_contact",
    purpose: "Read the selected Alex contact",
    contactHandle,
  });
  assert.equal(JSON.stringify(getRequest).includes("content://"), false);
  resolveNativeTool(context, getRequest.id, {
    ok: true,
    action: "get_contact",
    data: {
      contact: {
        contactHandle,
        displayName: "Alex Chen",
        phones: [{ value: "+8613800000000", label: "Mobile", primary: true }],
        emails: [{ value: "alex@example.test", label: "Work", primary: true }],
        organization: { company: "Example", title: "Engineer" },
      },
    },
    verification: { status: "observed", observedAt: "2026-07-29T10:00:01.000Z" },
  });
  provider = await nextProviderRequest(context);
  assert.match(JSON.stringify(provider.messages), /alex@example\.test/);
  finishTextRequest(context, provider, "Alex's bounded contact details are available.");
  const secondTurn = await waitForTerminal(context);

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("Create a temporary contact, update it, then delete it."),
  ));
  provider = await nextProviderRequest(context);
  const createdHandle = "contact-222222222222222222222222";
  const mutations = [
    {
      callId: "contacts-create",
      arguments: {
        action: "create_contact",
        purpose: "Create temporary Alex",
        displayName: "Alex Temporary",
        phones: [{ value: "+8613900000000", label: "Mobile", primary: true }],
        emails: [],
        organization: null,
      },
      result: {
        ok: true,
        action: "create_contact",
        data: {
          contact: {
            contactHandle: createdHandle,
            displayName: "Alex Temporary",
            phones: [{ value: "+8613900000000", label: "Mobile", primary: true }],
            emails: [],
            organization: null,
          },
        },
        verification: { status: "verified", observedAt: "2026-07-29T10:00:02.000Z" },
      },
    },
    {
      callId: "contacts-update",
      arguments: {
        action: "update_contact",
        purpose: "Rename temporary Alex",
        contactHandle: createdHandle,
        changes: { displayName: "Alex Temporary Updated" },
      },
      result: {
        ok: true,
        action: "update_contact",
        data: {
          contact: {
            contactHandle: createdHandle,
            displayName: "Alex Temporary Updated",
            phones: [{ value: "+8613900000000", label: "Mobile", primary: true }],
            emails: [],
            organization: null,
          },
        },
        verification: { status: "verified", observedAt: "2026-07-29T10:00:03.000Z" },
      },
    },
    {
      callId: "contacts-delete",
      arguments: {
        action: "delete_contact",
        purpose: "Delete temporary Alex",
        contactHandle: createdHandle,
      },
      result: {
        ok: true,
        action: "delete_contact",
        data: { deleted: true },
        verification: { status: "verified", observedAt: "2026-07-29T10:00:04.000Z" },
      },
    },
  ];
  for (const expected of mutations) {
    finishToolCall(
      context,
      provider,
      expected.callId,
      "device_contacts",
      expected.arguments,
    );
    const nativeRequest = await nextNativeToolRequest(context);
    assert.equal(nativeRequest.kind, "android_contacts_tool");
    assert.equal(nativeRequest.toolName, "device_contacts");
    assert.deepEqual(nativeRequest.arguments, expected.arguments);
    assert.equal(JSON.stringify(nativeRequest).includes("content://"), false);
    resolveNativeTool(context, nativeRequest.id, expected.result);
    provider = await nextProviderRequest(context);
    assert.match(JSON.stringify(provider.messages), new RegExp(expected.result.action));
  }
  finishTextRequest(context, provider, "The temporary contact was created, updated, and deleted.");
  const thirdTurn = await waitForTerminal(context);
  assert.equal(thirdTurn.turnCount, 3);
  assert.equal(
    firstTurn.toolRequestsIssued + secondTurn.toolRequestsIssued + thirdTurn.toolRequestsIssued,
    5,
  );
  assert.equal(
    firstTurn.toolRequestsResolved + secondTurn.toolRequestsResolved +
      thirdTurn.toolRequestsResolved,
    5,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("one real Pi task combines Contacts, Location, and Calendar within the Alpha context budget", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-domain-cross-alpha"),
    JSON.stringify("Find Alex, check my current area, then list tomorrow's calendars."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));

  let provider = await nextProviderRequest(context);
  const toolsByName = new Map(
    provider.tools.map((tool) => [tool.function.name, tool]),
  );
  const domainToolNames = [
    "device_calendar",
    "device_contacts",
    "device_location",
    "device_clipboard",
    "device_notification",
    "device_media_list",
    "device_media",
  ];
  assert.deepEqual(
    domainToolNames.filter((name) => toolsByName.has(name)),
    domainToolNames,
  );
  assert.ok(provider.tools.length <= 24);
  assert.ok(Buffer.byteLength(JSON.stringify(provider.tools), "utf8") <= 36 * 1024);
  const domainToolBytes = domainToolNames.map((name) =>
    Buffer.byteLength(JSON.stringify(toolsByName.get(name)), "utf8")
  );
  assert.ok(
    Math.max(...domainToolBytes) * 2 < domainToolBytes.reduce((sum, bytes) => sum + bytes, 0),
    "No single domain Tool may consume half of the Android-domain schema budget",
  );

  finishToolCall(
    context,
    provider,
    "cross-contacts-search",
    "device_contacts",
    {
      action: "search",
      purpose: "Find Alex for the requested cross-domain task",
      query: "Alex",
      cursor: null,
    },
  );
  let nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_contacts_tool");
  const contactHandle = "contact-aaaaaaaaaaaaaaaaaaaaaaaa";
  resolveNativeTool(context, nativeRequest.id, {
    ok: true,
    action: "search",
    data: {
      items: [{ contactHandle, displayName: "Alex Chen", phoneCount: 1, emailCount: 0 }],
      count: 1,
    },
    page: { truncated: false, nextCursor: null },
    verification: { status: "observed", observedAt: "2026-07-29T12:00:00Z" },
  });

  provider = await nextProviderRequest(context);
  assert.match(JSON.stringify(provider.messages), new RegExp(contactHandle));
  finishToolCall(
    context,
    provider,
    "cross-location-current",
    "device_location",
    {
      action: "get_current",
      precision: "approximate",
      purpose: "Check the current area for the requested cross-domain task",
    },
  );
  nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_location_tool");
  const locationPayload = {
    ok: true,
    action: "get_current",
    data: {
      precision: "approximate",
      latitude: 31.23,
      longitude: 121.47,
      accuracyMeters: 1000,
      capturedAt: "2026-07-29T12:00:01Z",
      ageMillis: 1000,
      providerCategory: "network",
    },
    verification: { status: "observed", observedAt: "2026-07-29T12:00:02Z" },
  };
  const locationPayloadText = JSON.stringify(locationPayload);
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeRequest.id),
    JSON.stringify(locationPayloadText),
    JSON.stringify(JSON.stringify({
      liveOnly: true,
      dataClass: "location",
      contentSha256: createHash("sha256").update(locationPayloadText, "utf8").digest("hex"),
      precision: "approximate",
    })),
    "false",
  ));

  provider = await nextProviderRequest(context);
  assert.match(JSON.stringify(provider.messages), /31\.23/);
  assert.match(JSON.stringify(provider.messages), new RegExp(contactHandle));
  finishToolCall(
    context,
    provider,
    "cross-calendar-list",
    "device_calendar",
    {
      action: "list_calendars",
      purpose: "List calendars for tomorrow's requested follow-up",
    },
  );
  nativeRequest = await nextNativeToolRequest(context);
  assert.equal(nativeRequest.kind, "android_calendar_tool");
  resolveNativeTool(context, nativeRequest.id, {
    ok: true,
    action: "list_calendars",
    data: {
      items: [{
        calendarHandle: "calendar-bbbbbbbbbbbbbbbbbbbbbbbb",
        displayName: "Personal",
        writable: true,
        timeZone: "Asia/Shanghai",
      }],
      count: 1,
    },
    page: { truncated: false, nextCursor: null },
    verification: { status: "observed", observedAt: "2026-07-29T12:00:03Z" },
  });

  provider = await nextProviderRequest(context);
  const finalMessages = JSON.stringify(provider.messages);
  assert.match(finalMessages, /list_calendars/);
  assert.match(finalMessages, new RegExp(contactHandle));
  assert.match(finalMessages, /31\.23/);
  finishTextRequest(context, provider, "Alex, the current area, and the calendar were checked.");
  const status = await waitForTerminal(context);
  assert.equal(status.toolExecutionsStarted, 3);
  assert.equal(status.toolExecutionsEnded, 3);
  assert.equal(status.toolRequestsIssued, 3);
  assert.equal(status.toolRequestsResolved, 3);
  assert.equal(status.lateToolStartsAfterStop, 0);
  assert.doesNotMatch(JSON.stringify(status.runEvents), /31\.23/);
  assert.match(JSON.stringify(status.runEvents), /live Android location expired/);
  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  assert.doesNotMatch(JSON.stringify(snapshot), /31\.23/);
  assert.match(JSON.stringify(snapshot), /live Android location expired/);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("one domain capability failure stays local and cross-domain Stop rejects late Android results", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-domain-failure-isolation"),
    JSON.stringify("Find Alex and list my calendars."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(context);
  finishToolCall(
    context,
    provider,
    "cross-contacts-denied",
    "device_contacts",
    {
      action: "search",
      purpose: "Find Alex",
      query: "Alex",
      cursor: null,
    },
  );
  const deniedRequest = await nextNativeToolRequest(context);
  const deniedPayload = {
    ok: false,
    action: "search",
    error: {
      code: "CONTACTS_PERMISSION_REQUIRED",
      message: "Grant Contacts permission before retrying.",
      retryable: true,
    },
  };
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(deniedRequest.id),
    JSON.stringify(JSON.stringify(deniedPayload)),
    JSON.stringify(JSON.stringify(deniedPayload)),
    "true",
  ));

  provider = await nextProviderRequest(context);
  assert.match(JSON.stringify(provider.messages), /CONTACTS_PERMISSION_REQUIRED/);
  finishToolCall(
    context,
    provider,
    "cross-calendar-after-denial",
    "device_calendar",
    {
      action: "list_calendars",
      purpose: "Continue the unaffected Calendar part of the task",
    },
  );
  const pendingCalendar = await nextNativeToolRequest(context);
  assert.equal(pendingCalendar.kind, "android_calendar_tool");
  JSON.parse(call(context, "abortNativeOpenRouterScenarioJson"));
  const status = await waitForTerminal(context);
  assert.equal(status.stopCompleted, true);
  assert.equal(status.hasAbort, true);
  assert.equal(status.toolRequestsIssued, 2);
  assert.equal(status.toolRequestsResolved, 1);
  assert.equal(status.lateToolStartsAfterStop, 0);
  assert.throws(
    () => resolveNativeTool(context, pendingCalendar.id, {
      ok: true,
      action: "list_calendars",
      data: { items: [], count: 0 },
    }),
    /PI_MOBILE_NATIVE_PROVIDER_TOOL_NOT_FOUND/,
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("Provider personal data remains inside the owning Pi task", async () => {
  const privateMarker = "DNT7_PRIVATE_CONTACT_MARKER";
  const ownerContext = await bootRuntime();
  JSON.parse(call(
    ownerContext,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-domain-private-owner"),
    JSON.stringify("Find the private test contact."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(ownerContext);
  finishToolCall(
    ownerContext,
    provider,
    "private-contact-search",
    "device_contacts",
    {
      action: "search",
      purpose: "Find the private test contact",
      query: "private",
      cursor: null,
    },
  );
  const nativeRequest = await nextNativeToolRequest(ownerContext);
  resolveNativeTool(ownerContext, nativeRequest.id, {
    ok: true,
    action: "search",
    data: {
      items: [{
        contactHandle: "contact-cccccccccccccccccccccccc",
        displayName: privateMarker,
        phoneCount: 0,
        emailCount: 0,
      }],
      count: 1,
    },
    page: { truncated: false, nextCursor: null },
    verification: { status: "observed", observedAt: "2026-07-29T12:00:04Z" },
  });
  provider = await nextProviderRequest(ownerContext);
  assert.match(JSON.stringify(provider.messages), new RegExp(privateMarker));
  finishTextRequest(ownerContext, provider, "The private test contact was found.");
  await waitForTerminal(ownerContext);

  const otherContext = await bootRuntime();
  JSON.parse(call(
    otherContext,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-domain-private-other"),
    JSON.stringify("List my calendars."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const otherProvider = await nextProviderRequest(otherContext);
  assert.doesNotMatch(JSON.stringify(otherProvider.messages), new RegExp(privateMarker));
  finishTextRequest(otherContext, otherProvider, "No data from another task is present.");
  await waitForTerminal(otherContext);

  assert.deepEqual(JSON.parse(call(ownerContext, "closeJson")), { ok: true, closed: true });
  assert.deepEqual(JSON.parse(call(otherContext, "closeJson")), { ok: true, closed: true });
});

test("pre-native Contacts validation is redacted and never calls Android", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-contacts-invalid"),
    JSON.stringify("Find a contact."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(context);
  finishToolCall(
    context,
    provider,
    "contacts-invalid",
    "device_contacts",
    {
      action: "search",
      purpose: "SENSITIVE_PURPOSE_MUST_NOT_BE_ECHOED",
      query: "SENSITIVE_QUERY_MUST_NOT_BE_ECHOED",
      unexpected: true,
    },
  );
  provider = await nextProviderRequest(context);
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderToolRequestsJson")), []);
  const failure = provider.messages.find(
    (message) => message.role === "tool" && message.tool_call_id === "contacts-invalid",
  );
  assert.ok(failure);
  assert.match(failure.content, /"code":"INVALID_ARGUMENTS"/);
  assert.match(failure.content, /"action":"search"/);
  assert.doesNotMatch(failure.content, /SENSITIVE_PURPOSE/);
  assert.doesNotMatch(failure.content, /SENSITIVE_QUERY/);
  finishTextRequest(context, provider, "The invalid Contacts call was rejected before Android.");
  const status = await waitForTerminal(context);
  assert.equal(status.toolRequestsIssued, 0);
  assert.equal(status.toolRequestsResolved, 0);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("pending Calendar work is cancelled on Stop and stable timeout recovery stays model-visible", async () => {
  const stoppedContext = await bootRuntime();
  JSON.parse(call(
    stoppedContext,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-calendar-stop"),
    JSON.stringify("List calendars, but stop if the task is cancelled."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  const stoppedProvider = await nextProviderRequest(stoppedContext);
  finishToolCall(
    stoppedContext,
    stoppedProvider,
    "calendar-pending-stop",
    "device_calendar",
    { action: "list_calendars", purpose: "List calendars before cancellation" },
  );
  const stoppedNativeRequest = await nextNativeToolRequest(stoppedContext);
  JSON.parse(call(stoppedContext, "abortNativeOpenRouterScenarioJson"));
  const stoppedStatus = await waitForTerminal(stoppedContext);
  assert.equal(stoppedStatus.stopCompleted, true);
  assert.equal(stoppedStatus.hasAbort, true);
  assert.equal(stoppedStatus.toolRequestsIssued, 1);
  assert.equal(stoppedStatus.toolRequestsResolved, 0);
  assert.equal(stoppedStatus.lateToolStartsAfterStop, 0);
  assert.throws(
    () => resolveNativeTool(
      stoppedContext,
      stoppedNativeRequest.id,
      { ok: true, action: "list_calendars", data: { items: [] } },
    ),
    /PI_MOBILE_NATIVE_PROVIDER_TOOL_NOT_FOUND/,
  );
  assert.deepEqual(
    JSON.parse(call(stoppedContext, "closeJson")),
    { ok: true, closed: true },
  );

  const timedOutContext = await bootRuntime();
  JSON.parse(call(
    timedOutContext,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-calendar-timeout"),
    JSON.stringify("List calendars and report a recoverable timeout accurately."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(timedOutContext);
  finishToolCall(
    timedOutContext,
    provider,
    "calendar-timeout",
    "device_calendar",
    { action: "list_calendars", purpose: "List calendars within the bounded operation" },
  );
  const timedOutRequest = await nextNativeToolRequest(timedOutContext);
  const timeoutPayload = {
    ok: false,
    action: "list_calendars",
    error: {
      code: "DEVICE_TOOL_TIMEOUT",
      message: "Calendar operation timed out.",
      retryable: true,
    },
  };
  JSON.parse(call(
    timedOutContext,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(timedOutRequest.id),
    JSON.stringify(JSON.stringify(timeoutPayload)),
    JSON.stringify(JSON.stringify(timeoutPayload)),
    "true",
  ));
  provider = await nextProviderRequest(timedOutContext);
  const timeoutResult = provider.messages.find((message) =>
    message.role === "tool" && message.tool_call_id === "calendar-timeout"
  );
  assert.ok(timeoutResult);
  assert.deepEqual(JSON.parse(timeoutResult.content), timeoutPayload);
  finishTextRequest(timedOutContext, provider, "The Calendar operation timed out and can be retried.");
  const timedOutStatus = await waitForTerminal(timedOutContext);
  assert.equal(
    timedOutStatus.runEvents.find((event) =>
      event.type === "tool_execution_end" && event.toolCallId === "calendar-timeout"
    )?.isError,
    true,
  );
  assert.deepEqual(
    JSON.parse(call(timedOutContext, "closeJson")),
    { ok: true, closed: true },
  );
});

test("pre-native Calendar validation returns redacted stable JSON and never calls Android", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-calendar-invalid"),
    JSON.stringify("Create a calendar event."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
  ));
  let provider = await nextProviderRequest(context);
  finishToolCall(
    context,
    provider,
    "calendar-invalid-known-action",
    "device_calendar",
    {
      action: "create_event",
      purpose: "Create a private fixture",
      title: "SENSITIVE_CALENDAR_TITLE_MUST_NOT_BE_ECHOED",
      unexpected: "SENSITIVE_LOCATION_MUST_NOT_BE_ECHOED",
    },
  );
  provider = await nextProviderRequest(context);
  assert.deepEqual(
    JSON.parse(call(context, "drainNativeProviderToolRequestsJson")),
    [],
    "schema rejection must happen before the Android mailbox",
  );
  const knownFailure = provider.messages.find(
    (message) =>
      message.role === "tool" &&
      message.tool_call_id === "calendar-invalid-known-action",
  );
  assert.ok(knownFailure);
  assert.match(knownFailure.content, /"code":"INVALID_ARGUMENTS"/);
  assert.match(knownFailure.content, /"action":"create_event"/);
  assert.doesNotMatch(knownFailure.content, /SENSITIVE_CALENDAR_TITLE/);
  assert.doesNotMatch(knownFailure.content, /SENSITIVE_LOCATION/);

  finishToolCall(
    context,
    provider,
    "calendar-invalid-unknown-action",
    "device_calendar",
    {
      action: "SENSITIVE_UNKNOWN_ACTION_MUST_NOT_BE_ECHOED",
      purpose: "Invalid fixture",
    },
  );
  provider = await nextProviderRequest(context);
  assert.deepEqual(JSON.parse(call(context, "drainNativeProviderToolRequestsJson")), []);
  const unknownFailure = provider.messages.find(
    (message) =>
      message.role === "tool" &&
      message.tool_call_id === "calendar-invalid-unknown-action",
  );
  assert.ok(unknownFailure);
  assert.match(unknownFailure.content, /"action":null/);
  assert.doesNotMatch(unknownFailure.content, /SENSITIVE_UNKNOWN_ACTION/);
  finishTextRequest(context, provider, "The invalid Calendar calls were rejected before Android.");
  const status = await waitForTerminal(context);
  assert.equal(status.toolRequestsIssued, 0);
  assert.equal(status.toolRequestsResolved, 0);
  for (const callId of [
    "calendar-invalid-known-action",
    "calendar-invalid-unknown-action",
  ]) {
    assert.equal(
      status.runEvents.find((event) =>
        event.type === "tool_execution_end" && event.toolCallId === callId
      )?.isError,
      true,
    );
  }
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("delegate keeps child failure explicit while the parent Pi turn can recover", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("task-child-failure"),
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
    JSON.stringify("task-child-limit"),
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
    JSON.stringify("task-child-cancel"),
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
    JSON.stringify("task-child-stop"),
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
    JSON.stringify("task-skills-resources"),
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
    JSON.stringify("task-skill-fail-closed"),
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
    JSON.stringify("task-skill-turn"),
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
    JSON.stringify("goal-skill-conflict"),
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
    JSON.stringify("goal-skill-conflict"),
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
    JSON.stringify("task-new-skill"),
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
    JSON.stringify("task-new-skill"),
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
