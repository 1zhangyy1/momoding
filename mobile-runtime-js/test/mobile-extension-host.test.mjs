import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);

const ORDINARY_TOOL_NAMES = [
  "delegate",
  "run_command",
  "run_tests",
  "attachment_read",
  "device_capabilities_get",
  "device_capability_request",
  "device_files_list",
  "device_files_read",
  "device_media_list",
  "device_media",
  "device_calendar",
  "device_contacts",
  "device_location",
  "device_clipboard",
  "device_notification",
  "device_screen_capture",
  "device_ui_inspect",
  "device_ui_action",
  "device_packages_list",
  "device_package_inspect",
  "device_files_prepare_changes",
  "device_files_commit_changes",
  "request_user_question",
  "request_user_confirmation",
];

const PLAN_TOOL_NAMES = [
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
];

test("MobileExtensionHost is static, deterministic, composable, and fail-closed", async () => {
  const context = await bootRuntime();
  const result = JSON.parse(await runInContext(
    "PiMobileRuntimeBundle.mobileExtensionHostContractJson()",
    context,
  ));

  assert.deepEqual(result.toolNames, ["legacy_tool", "fixture_alpha", "fixture_beta"]);
  assert.deepEqual(result.activeToolNames, ["legacy_tool", "fixture_beta"]);
  assert.equal(result.loaded.status, "loaded");
  assert.deepEqual(result.loaded.supportedEventTypes, ["context", "tool_result"]);
  assert.deepEqual(
    result.loaded.extensions.map(({ id, source, status, toolNames, eventTypes }) => ({
      id,
      source,
      status,
      toolNames,
      eventTypes,
    })),
    [
      {
        id: "fixture.alpha",
        source: "builtin",
        status: "loaded",
        toolNames: ["fixture_alpha"],
        eventTypes: ["context", "tool_result"],
      },
      {
        id: "fixture.beta",
        source: "builtin",
        status: "loaded",
        toolNames: ["fixture_beta"],
        eventTypes: ["context", "tool_result"],
      },
    ],
  );
  assert.equal(result.attached.status, "attached");
  assert.equal(result.disposed.status, "disposed");
  assert.deepEqual(result.contextTrace, ["alpha", "beta:1"]);
  assert.deepEqual(result.contextPatch.messages, [
    { role: "user", content: "second", timestamp: 1 },
  ]);
  assert.deepEqual(result.eventTrace, ["alpha", "beta:true"]);
  assert.deepEqual(result.toolResultPatch, {
    details: { alpha: true, original: { original: true } },
    isError: true,
    terminate: true,
  });
  assert.equal(result.attachedHandlerCount, 2, "host must aggregate handlers per Pi hook type");
  assert.equal(result.disposedHandlerCount, 0);
  assert.deepEqual(result.disposeTrace, ["beta", "alpha"]);
  assert.match(result.lateRegistrationError, /PI_MOBILE_EXTENSION_REGISTRATION_CLOSED/);
  assert.match(result.repeatedAttachError, /PI_MOBILE_EXTENSION_HOST_ALREADY_ATTACHED/);
  assert.match(result.duplicateDescriptorError, /PI_MOBILE_EXTENSION_ID_DUPLICATE/);
  assert.match(result.duplicateExtensionToolError, /PI_MOBILE_EXTENSION_TOOL_COLLISION/);
  assert.match(result.legacyCollisionError, /PI_MOBILE_EXTENSION_TOOL_COLLISION/);
  assert.match(result.duplicateActiveToolError, /PI_MOBILE_EXTENSION_ACTIVE_TOOL_DUPLICATE/);
  assert.match(result.unknownActiveToolError, /PI_MOBILE_EXTENSION_ACTIVE_TOOL_UNKNOWN/);
  assert.match(result.asyncFactoryError, /PI_MOBILE_EXTENSION_ASYNC_FACTORY_UNSUPPORTED/);
  assert.match(result.rollbackError, /PI_MOBILE_EXTENSION_LOAD_FAILED/);
  assert.deepEqual(result.rollbackTrace, ["rollback"]);
  assert.match(result.failingDisposeError, /PI_MOBILE_EXTENSION_DISPOSE_FAILED/);
  assert.deepEqual(result.failingDisposeTrace, ["second", "first"]);
  assert.equal(result.failingDisposeStatus, "disposed");
  assert.match(result.disposedHostError, /PI_MOBILE_EXTENSION_HOST_DISPOSED/);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

test("empty production ExtensionHost preserves the frozen ordinary, plan, and goal tool baselines", async () => {
  const ordinary = await captureInitialRequest({ taskId: "pxp1-ordinary" });
  assertToolBaseline(ordinary.request, ORDINARY_TOOL_NAMES, 28_612);

  const plan = await captureInitialRequest({ taskId: "pxp1-plan", planMode: true });
  assertToolBaseline(plan.request, PLAN_TOOL_NAMES, 7_105);

  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify("pxp1-goal"),
    JSON.stringify("Prepare a goal."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify("pxp1-goal-session"),
  ));
  const initial = await nextProviderRequest(context);
  finishTextRequest(context, initial, "Ready.");
  await waitForTerminal(context);
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskGoalJson",
    JSON.stringify("pxp1-goal-id"),
    JSON.stringify("Complete one deterministic checkpoint."),
    "1",
    "1000",
  ));
  const goal = await nextProviderRequest(context);
  assertToolBaseline(
    goal,
    [...ORDINARY_TOOL_NAMES, "task_goal_progress", "task_goal_complete"],
    29_444,
  );
  finishTextRequest(context, goal, "Checkpoint remains active.");
  await waitForTerminal(context);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

async function captureInitialRequest({ taskId, planMode = false }) {
  const context = await bootRuntime();
  JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Run the frozen PXP-1 baseline."),
    JSON.stringify("deepseek/deepseek-v4-pro"),
    JSON.stringify(`${taskId}-session`),
    String(planMode),
  ));
  const request = await nextProviderRequest(context);
  finishTextRequest(context, request, "Baseline captured.");
  await waitForTerminal(context);
  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
  return { request };
}

function assertToolBaseline(request, expectedNames, expectedBytes) {
  assert.deepEqual(
    request.tools.map((tool) => tool.function.name),
    expectedNames,
  );
  assert.equal(request.tools.length, expectedNames.length);
  assert.equal(Buffer.byteLength(JSON.stringify(request.tools), "utf8"), expectedBytes);
}

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
    if (requests.length > 0) {
      assert.equal(requests.length, 1);
      return requests[0];
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Provider request did not arrive");
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
  JSON.parse(call(
    context,
    "pushNativeProviderChunkJson",
    JSON.stringify(request.id),
    JSON.stringify(JSON.stringify({
      id: `generation-${request.id}`,
      model: request.modelId,
      choices: [{ delta: { content: text }, finish_reason: "stop" }],
    })),
  ));
  JSON.parse(call(
    context,
    "completeNativeProviderRequestJson",
    JSON.stringify(request.id),
    JSON.stringify(`generation-${request.id}`),
  ));
}

function call(context, functionName, ...serializedArguments) {
  return runInContext(
    `PiMobileRuntimeBundle.${functionName}(${serializedArguments.join(",")})`,
    context,
  );
}
