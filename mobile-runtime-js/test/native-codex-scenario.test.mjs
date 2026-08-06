import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);

test("Pi Codex native bridge keeps auth native and preserves multi-turn Responses input", async () => {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeCodexTaskSessionJson",
    JSON.stringify("task-codex-text"),
    JSON.stringify("Say hello"),
    JSON.stringify("gpt-5.4"),
    JSON.stringify("session-codex-text"),
  ));
  const first = await nextProviderRequest(context);
  assert.equal(first.kind, "codex_responses_stream");
  assert.equal(first.modelId, "gpt-5.4");
  assert.equal(first.body.store, false);
  assert.equal(first.body.stream, true);
  assert.equal(first.body.tool_choice, "auto");
  assert.equal(first.body.parallel_tool_calls, true);
  assert.equal(first.body.input[0].role, "user");
  assert.equal(first.body.input[0].content[0].text, "Say hello");
  assert.equal(JSON.stringify(first).includes("accessToken"), false);
  assert.equal(JSON.stringify(first).includes("Authorization"), false);
  finishText(context, first.id, "Hello from Codex", "response-1");
  let status = await waitForTerminal(context);
  assert.equal(status.finalText, "Hello from Codex");

  JSON.parse(call(
    context,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("And again"),
  ));
  const second = await nextProviderRequest(context);
  assert.equal(
    second.body.input.some((item) =>
      item.type === "message" && item.role === "assistant" &&
      item.content?.[0]?.text === "Hello from Codex"),
    true,
  );
  assert.equal(
    second.body.input.some((item) =>
      item.role === "user" && item.content?.[0]?.text === "And again"),
    true,
  );
  finishText(context, second.id, "Hello again", "response-2");
  status = await waitForTerminal(context);
  assert.equal(status.finalText, "Hello again");
  assert.equal(status.turnCount, 2);
  const snapshot = JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
  assert.deepEqual(
    snapshot.entries.find((entry) => entry.customType === "pi_mobile_provider_binding")?.data,
    { kind: "codex", modelId: "gpt-5.4" },
  );
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });

  const restoredContext = await bootRuntime();
  const restored = JSON.parse(call(
    restoredContext,
    "restoreNativeCodexTaskSessionJson",
    JSON.stringify("task-codex-text"),
    JSON.stringify("session-codex-text"),
    JSON.stringify(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify("gpt-5.4"),
  ));
  assert.equal(restored.terminal, true);
  assert.deepEqual(
    JSON.parse(call(restoredContext, "drainNativeProviderRequestsJson")),
    [],
  );
  JSON.parse(call(
    restoredContext,
    "continueNativeOpenRouterTaskPromptJson",
    JSON.stringify("After restore"),
  ));
  const third = await nextProviderRequest(restoredContext);
  assert.equal(
    third.body.input.some((item) =>
      item.role === "user" && item.content?.[0]?.text === "After restore"),
    true,
  );
  finishText(restoredContext, third.id, "Restored", "response-3");
  assert.equal((await waitForTerminal(restoredContext)).finalText, "Restored");
  assert.deepEqual(
    JSON.parse(call(restoredContext, "closeJson")),
    { ok: true, closed: true },
  );
});

test("Pi Codex native bridge handles function tools, malformed events, and Stop", async () => {
  const toolContext = await bootRuntime();
  JSON.parse(call(
    toolContext,
    "startNativeCodexScenarioJson",
    JSON.stringify("tool"),
    JSON.stringify("gpt-5.4"),
  ));
  const toolRequest = await nextProviderRequest(toolContext);
  assert.equal(toolRequest.body.tools[0].name, "mobile_fixture_echo");
  finishToolCall(toolContext, toolRequest.id);
  const nativeTool = await nextToolRequest(toolContext);
  assert.equal(nativeTool.toolName, "mobile_fixture_echo");
  JSON.parse(call(
    toolContext,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(nativeTool.id),
    JSON.stringify(JSON.stringify({ echo: "android" })),
  ));
  const finalRequest = await nextProviderRequest(toolContext);
  assert.equal(
    finalRequest.body.input.some((item) =>
      item.type === "function_call_output" && item.call_id === "call_fixture"),
    true,
  );
  finishText(
    toolContext,
    finalRequest.id,
    "Android native Provider tool complete",
    "response-tool-2",
  );
  const toolStatus = await waitForTerminal(toolContext);
  assert.equal(toolStatus.expectationMet, true, JSON.stringify(toolStatus));
  assert.equal(toolStatus.toolExecutionsStarted, 1);
  assert.deepEqual(JSON.parse(call(toolContext, "closeJson")), { ok: true, closed: true });

  const malformedContext = await bootRuntime();
  JSON.parse(call(
    malformedContext,
    "startNativeCodexScenarioJson",
    JSON.stringify("provider_error"),
    JSON.stringify("gpt-5.4"),
  ));
  const malformedRequest = await nextProviderRequest(malformedContext);
  pushEvent(malformedContext, malformedRequest.id, { nope: true });
  const malformedStatus = await waitForTerminal(malformedContext);
  assert.equal(malformedStatus.providerRequestsFailed, 1);
  assert.equal(malformedStatus.providerError, "Codex returned an invalid stream");
  assert.deepEqual(JSON.parse(call(malformedContext, "closeJson")), { ok: true, closed: true });

  const stopContext = await bootRuntime();
  JSON.parse(call(
    stopContext,
    "startNativeCodexScenarioJson",
    JSON.stringify("stop"),
    JSON.stringify("gpt-5.4"),
  ));
  await nextProviderRequest(stopContext);
  JSON.parse(call(stopContext, "abortNativeOpenRouterScenarioJson"));
  const cancellations = JSON.parse(call(stopContext, "drainNativeProviderCancellationsJson"));
  assert.deepEqual(cancellations.map((item) => item.kind), ["cancel_codex_responses_stream"]);
  const stopped = await waitForTerminal(stopContext);
  assert.equal(stopped.expectationMet, true, JSON.stringify(stopped));
  assert.equal(stopped.providerCancellationsIssued, 1);
  assert.deepEqual(JSON.parse(call(stopContext, "closeJson")), { ok: true, closed: true });
});

async function bootRuntime() {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({ console, crypto: webcrypto });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  JSON.parse(call(context, "bootstrapJson"));
  return context;
}

async function nextProviderRequest(context) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderRequestsJson"));
    if (requests.length > 0) return requests[0];
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Codex provider request did not arrive");
}

async function nextToolRequest(context) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderToolRequestsJson"));
    if (requests.length > 0) return requests[0];
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error(
    `Codex tool request did not arrive: ${call(context, "nativeOpenRouterScenarioStatusJson")}`,
  );
}

async function waitForTerminal(context) {
  for (let attempt = 0; attempt < 300; attempt += 1) {
    const status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
    if (status.terminal) return status;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Codex scenario did not settle");
}

function finishText(context, requestId, text, responseId) {
  const item = {
    type: "message",
    id: `msg_${responseId}`,
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [] }],
  };
  pushEvent(context, requestId, {
    type: "response.output_item.added",
    output_index: 0,
    item: { ...item, status: "in_progress", content: [] },
  });
  pushEvent(context, requestId, {
    type: "response.output_text.delta",
    output_index: 0,
    content_index: 0,
    delta: text,
  });
  pushEvent(context, requestId, {
    type: "response.output_item.done",
    output_index: 0,
    item,
  });
  pushEvent(context, requestId, terminalResponse(responseId));
  completeRequest(context, requestId);
}

function finishToolCall(context, requestId) {
  const item = {
    type: "function_call",
    id: "fc_fixture",
    call_id: "call_fixture",
    name: "mobile_fixture_echo",
    arguments: JSON.stringify({ text: "android" }),
    status: "completed",
  };
  pushEvent(context, requestId, {
    type: "response.output_item.added",
    output_index: 0,
    item: { ...item, arguments: "", status: "in_progress" },
  });
  pushEvent(context, requestId, {
    type: "response.function_call_arguments.done",
    output_index: 0,
    arguments: item.arguments,
  });
  pushEvent(context, requestId, {
    type: "response.output_item.done",
    output_index: 0,
    item,
  });
  pushEvent(context, requestId, terminalResponse("response-tool-1"));
  completeRequest(context, requestId);
}

function terminalResponse(id) {
  return {
    type: "response.done",
    response: {
      id,
      status: "completed",
      usage: {
        input_tokens: 7,
        output_tokens: 3,
        total_tokens: 10,
        input_tokens_details: { cached_tokens: 0 },
        output_tokens_details: { reasoning_tokens: 0 },
      },
    },
  };
}

function pushEvent(context, requestId, event) {
  JSON.parse(call(
    context,
    "pushNativeProviderChunkJson",
    JSON.stringify(requestId),
    JSON.stringify(JSON.stringify(event)),
  ));
}

function completeRequest(context, requestId) {
  JSON.parse(call(
    context,
    "completeNativeProviderRequestJson",
    JSON.stringify(requestId),
  ));
}

function call(context, functionName, ...serializedArguments) {
  return runInContext(
    `PiMobileRuntimeBundle.${functionName}(${serializedArguments.join(",")})`,
    context,
  );
}
