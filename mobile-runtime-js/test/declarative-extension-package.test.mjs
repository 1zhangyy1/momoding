import assert from "node:assert/strict";
import { createHash, webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);
const modelId = "fixture/extension-package";

test("schema v2 package runs through the native isolated Worker transport in the real Task", async () => {
  const context = await bootRuntime();
  const extensionPackage = piRegisterToolPackageSnapshot();
  startTask(context, "extension-pi-register-tool", [extensionPackage]);
  const first = await nextProviderRequest(context);
  const systemPrompt = first.messages.find((message) => message.role === "system")?.content;
  assert.match(systemPrompt, /Extension packages=1/);
  assert.match(systemPrompt, /Connector tools=not configured/);
  const tool = first.tools.find((candidate) => candidate.function.name === "fixture_echo");
  assert.deepEqual(tool.function.parameters, extensionPackage.tools[0].parameters);

  finishToolCall(context, first, "pi-register-call", "fixture_echo", { text: "hello" });
  const execution = await nextNativeToolRequest(context);
  assert.equal(execution.kind, "android_extension_package");
  assert.equal(execution.toolName, "extension_package_execute_pi");
  assert.deepEqual(execution.arguments, {
    action: "start",
    packageId: extensionPackage.id,
    packageDigest: extensionPackage.packageDigest,
    outerToolCallId: "pi-register-call",
    toolName: "fixture_echo",
    invocationArguments: { text: "hello" },
  });
  resolveNativeTool(context, execution.id, {
    ok: true,
    event: {
      type: "complete",
      invocationId: "fixture-invocation",
      generation: "fixture-generation",
      seq: 0,
      result: {
        content: [{ type: "text", text: "hello" }],
        details: { echoed: true },
      },
    },
  });

  const second = await nextProviderRequest(context);
  assert.match(JSON.stringify(second.messages), /hello/);
  finishTextRequest(context, second, "Extension completed in the isolated Worker.");
  const terminal = await waitForTerminal(context);
  assert.equal(terminal.finalText, "Extension completed in the isolated Worker.");
  assert.equal(terminal.toolRequestsIssued, 1);
  closeRuntime(context);
});

test("schema v2 Host steps become bounded product task activities without Tool payloads", async () => {
  const context = await bootRuntime();
  const extensionPackage = piRegisterHostToolPackageSnapshot();
  startTask(context, "extension-host-activity", [extensionPackage]);
  const first = await nextProviderRequest(context);
  finishToolCall(context, first, "outer-host", "fixture_calendar_summary", { query: "today" });

  const start = await nextNativeToolRequest(context);
  assert.equal(start.arguments.action, "start");
  resolveNativeTool(context, start.id, {
    ok: true,
    event: {
      type: "host_call",
      invocationId: "fixture-invocation",
      generation: "fixture-generation",
      seq: 0,
      name: "capabilities",
      arguments: {},
      childToolCallId: "ext:fixture:0",
    },
  });

  const authorization = await nextNativeToolRequest(context);
  assert.equal(authorization.arguments.action, "authorize_host_call");
  resolveNativeTool(context, authorization.id, { ok: true });
  const hostRequests = await nextNativeToolRequests(context);
  assert.equal(hostRequests.length, 2);
  const target = hostRequests.find((request) => request.toolName === "device_capabilities_get");
  const deadline = hostRequests.find(
    (request) => request.arguments.action === "await_deadline",
  );
  assert.ok(target);
  assert.ok(deadline);
  assert.equal(target.toolName, "device_capabilities_get");
  resolveNativeTool(context, target.id, { ok: true, capabilities: [] });
  let resume;
  while (resume === undefined) {
    const requests = await nextNativeToolRequests(context);
    for (const request of requests) {
      if (request.arguments.action === "cancel_deadline") {
        resolveNativeTool(context, request.id, { ok: true });
      } else if (request.arguments.action === "resume") {
        resume = request;
      } else {
        assert.fail(`unexpected Host request ${request.arguments.action}`);
      }
    }
  }
  assert.equal(resume.arguments.action, "resume");
  resolveNativeTool(context, resume.id, {
    ok: true,
    event: {
      type: "complete",
      invocationId: "fixture-invocation",
      generation: "fixture-generation",
      seq: 1,
      result: {
        content: [{ type: "text", text: "Host step complete" }],
      },
    },
  });

  const second = await nextProviderRequest(context);
  finishTextRequest(context, second, "Extension Host step completed.");
  const terminal = await waitForTerminal(context);
  assert.deepEqual(
    terminal.events.filter((event) => event.type === "extension_tool_activity"),
    [
      {
        type: "extension_tool_activity",
        state: "running",
        toolCallId: "outer-host",
        seq: 0,
        kind: "host_tool",
        packageId: "fixtures.host-call",
        name: "capabilities",
        targetTool: "device_capabilities_get",
      },
      {
        type: "extension_tool_activity",
        state: "completed",
        toolCallId: "outer-host",
        seq: 0,
        kind: "host_tool",
        packageId: "fixtures.host-call",
        name: "capabilities",
        targetTool: "device_capabilities_get",
      },
    ],
  );
  const activityJson = JSON.stringify(terminal.events.filter(
    (event) => event.type === "extension_tool_activity",
  ));
  assert.doesNotMatch(activityJson, /arguments|result|Host step complete|content:\/\//);
  closeRuntime(context);
});

test("schema v2 Host activity settles as cancelled on Task Stop without a late completion", async () => {
  const context = await bootRuntime();
  const extensionPackage = piRegisterHostToolPackageSnapshot();
  startTask(context, "extension-host-activity-stop", [extensionPackage]);
  const first = await nextProviderRequest(context);
  finishToolCall(context, first, "outer-host-stop", "fixture_calendar_summary", { query: "today" });

  const start = await nextNativeToolRequest(context);
  resolveNativeTool(context, start.id, {
    ok: true,
    event: {
      type: "host_call",
      invocationId: "fixture-invocation-stop",
      generation: "fixture-generation-stop",
      seq: 0,
      name: "capabilities",
      arguments: {},
      childToolCallId: "ext:fixture:stop:0",
    },
  });
  const authorization = await nextNativeToolRequest(context);
  resolveNativeTool(context, authorization.id, { ok: true });
  const hostRequests = await nextNativeToolRequests(context);
  const target = hostRequests.find((request) => request.toolName === "device_capabilities_get");
  assert.ok(target);

  JSON.parse(call(context, "abortNativeOpenRouterScenarioJson"));
  const terminal = await waitForTerminal(context);
  const activities = terminal.events.filter((event) => event.type === "extension_tool_activity");
  assert.deepEqual(activities.map((event) => event.state), ["running", "cancelled"]);
  assert.equal(activities[0].toolCallId, "outer-host-stop");
  assert.equal(activities[1].toolCallId, "outer-host-stop");
  assert.equal(activities[0].seq, 0);
  assert.equal(activities[1].seq, 0);
  assert.throws(
    () => resolveNativeTool(context, target.id, { ok: true, capabilities: [] }),
    /PI_MOBILE_NATIVE_PROVIDER_TOOL_NOT_FOUND/,
  );
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(
    JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson")).events
      .filter((event) => event.type === "extension_tool_activity").length,
    2,
  );
  closeRuntime(context);
});

test("schema v2 rejects image-valued screen capture as a Host Tool target", async () => {
  const context = await bootRuntime();
  const extensionPackage = {
    ...piRegisterToolPackageSnapshot(),
    hostTools: [{
      name: "screen",
      targetTool: "device_screen_capture",
      capability: "screen_capture",
    }],
    requiredCapabilities: ["screen_capture"],
  };
  assert.throws(
    () => startTask(context, "extension-screen-target", [extensionPackage]),
    /PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID/,
  );
  closeRuntime(context);
});

test("schema v2 Tool set reconciles across Plan Goal and ordinary restore", async () => {
  const extensionPackage = piRegisterToolPackageSnapshot();

  const planSource = await bootRuntime();
  startTask(planSource, "extension-pi-plan", [extensionPackage], null, true);
  const planRequest = await nextProviderRequest(planSource);
  assert.equal(planRequest.tools.some((tool) => tool.function.name === "fixture_echo"), false);
  finishTextRequest(planSource, planRequest, "Plan active.");
  await waitForTerminal(planSource);
  const planSnapshot = taskSnapshot(planSource);
  assert.equal(planSnapshot.prePlanActiveToolNames.includes("fixture_echo"), true);
  closeRuntime(planSource);

  const planWithoutPackage = await bootRuntime();
  restoreTask(planWithoutPackage, "extension-pi-plan", planSnapshot, []);
  JSON.parse(call(planWithoutPackage, "setNativeOpenRouterTaskPlanModeJson", "false"));
  const exitedPlan = await waitForTerminal(planWithoutPackage);
  assert.equal(exitedPlan.commandError, null);
  assert.equal(exitedPlan.activeToolNames.includes("fixture_echo"), false);
  const ordinarySnapshot = taskSnapshot(planWithoutPackage);
  closeRuntime(planWithoutPackage);

  const ordinaryRestored = await bootRuntime();
  const ordinary = restoreTask(
    ordinaryRestored,
    "extension-pi-plan",
    ordinarySnapshot,
    [extensionPackage],
  );
  assert.equal(ordinary.planMode, false);
  assert.equal(ordinary.activeToolNames.includes("fixture_echo"), true);
  closeRuntime(ordinaryRestored);

  const goalSource = await bootRuntime();
  startTask(goalSource, "extension-pi-goal", []);
  const initial = await nextProviderRequest(goalSource);
  finishTextRequest(goalSource, initial, "Ready.");
  await waitForTerminal(goalSource);
  JSON.parse(call(
    goalSource,
    "startNativeOpenRouterTaskGoalJson",
    JSON.stringify("extension-pi-goal-id"),
    JSON.stringify("Verify schema v2 restore."),
    "1",
    "1",
  ));
  const goalRequest = await nextProviderRequest(goalSource);
  finishTextRequest(goalSource, goalRequest, "Goal active.");
  await waitForTerminal(goalSource);
  const goalSnapshot = taskSnapshot(goalSource);
  closeRuntime(goalSource);

  const goalRestored = await bootRuntime();
  const goal = restoreTask(goalRestored, "extension-pi-goal", goalSnapshot, [extensionPackage]);
  assert.equal(goal.goal.state, "active");
  assert.equal(goal.activeToolNames.includes("fixture_echo"), true);
  closeRuntime(goalRestored);
});

test("declarative prompt Tool is task-scoped and Android-authorized before returning text", async () => {
  const context = await bootRuntime();
  const extensionPackage = packageSnapshot();
  startTask(context, "extension-prompt", [extensionPackage]);
  const first = await nextProviderRequest(context);
  const tools = new Map(first.tools.map((tool) => [tool.function.name, tool.function]));
  assert.equal(tools.has("extension_checklist"), true);
  assert.equal(tools.has("extension_device_status"), true);
  assert.deepEqual(
    tools.get("extension_device_status").parameters,
    tools.get("device_capabilities_get").parameters,
  );

  finishToolCall(context, first, "prompt-call", "extension_checklist", {});
  const authorization = await nextNativeToolRequest(context);
  assert.equal(authorization.kind, "android_extension_package");
  assert.equal(authorization.toolName, "extension_package_authorize");
  assert.deepEqual(authorization.arguments, {
    packageId: extensionPackage.id,
    packageDigest: extensionPackage.packageDigest,
    extensionToolName: "extension_checklist",
    type: "prompt-tool",
    description: "Return a fixed checklist.",
    prompt: "Check live state before reporting success.",
  });
  resolveNativeTool(context, authorization.id, { ok: true, packageId: extensionPackage.id });

  const second = await nextProviderRequest(context);
  assert.equal(
    JSON.stringify(second.messages).includes("Check live state before reporting success."),
    true,
    JSON.stringify(second),
  );
  finishTextRequest(context, second, "Checklist applied.");
  const terminal = await waitForTerminal(context);
  assert.equal(terminal.finalText, "Checklist applied.");
  assert.equal(terminal.extensionSetTrusted, true);
  assert.equal(terminal.extensionSetDigest, packageSetDigest([extensionPackage]));
  closeRuntime(context);
});

test("android alias runs package gate then the exact existing Tool executor", async () => {
  const context = await bootRuntime();
  const extensionPackage = packageSnapshot();
  startTask(context, "extension-alias", [extensionPackage]);
  const first = await nextProviderRequest(context);
  finishToolCall(context, first, "alias-call", "extension_device_status", {});

  const authorization = await nextNativeToolRequest(context);
  assert.equal(authorization.kind, "android_extension_package");
  assert.equal(authorization.arguments.targetTool, "device_capabilities_get");
  assert.equal(authorization.arguments.description, "Use the existing device capability Tool.");
  resolveNativeTool(context, authorization.id, { ok: true });

  const underlying = await nextNativeToolRequest(context);
  assert.equal(underlying.kind, "android_file_tool");
  assert.equal(underlying.toolName, "device_capabilities_get");
  resolveNativeTool(context, underlying.id, { ok: true, capabilities: [] });

  const second = await nextProviderRequest(context);
  finishTextRequest(context, second, "Device status checked.");
  const terminal = await waitForTerminal(context);
  assert.equal(terminal.finalText, "Device status checked.");
  closeRuntime(context);
});

test("isolated JavaScript Tool delegates only through a declared package host Tool", async () => {
  const context = await bootRuntime();
  const extensionPackage = javascriptPackageSnapshot();
  startTask(context, "extension-javascript", [extensionPackage]);
  const first = await nextProviderRequest(context);
  const tool = first.tools.find((candidate) => candidate.function.name === "extension_js_status");
  assert.deepEqual(tool.function.parameters, extensionPackage.tools[0].parameters);
  finishToolCall(context, first, "javascript-call", "extension_js_status", { label: "phone" });

  const execution = await nextNativeToolRequest(context);
  assert.equal(execution.kind, "android_extension_package");
  assert.equal(execution.toolName, "extension_package_execute_javascript");
  assert.deepEqual(execution.arguments, {
    packageId: extensionPackage.id,
    packageDigest: extensionPackage.packageDigest,
    extensionToolName: "extension_js_status",
    type: "javascript-tool",
    description: "Run isolated package JavaScript.",
    parametersDigest: extensionPackage.tools[0].parametersDigest,
    invocationArguments: { label: "phone" },
  });
  resolveNativeTool(context, execution.id, {
    ok: true,
    kind: "host-call",
    hostToolName: "extension_device_status",
    arguments: {},
  });

  const authorization = await nextNativeToolRequest(context);
  assert.equal(authorization.toolName, "extension_package_authorize");
  assert.equal(authorization.arguments.extensionToolName, "extension_device_status");
  resolveNativeTool(context, authorization.id, { ok: true });
  const underlying = await nextNativeToolRequest(context);
  assert.equal(underlying.toolName, "device_capabilities_get");
  resolveNativeTool(context, underlying.id, { ok: true, capabilities: [] });

  const second = await nextProviderRequest(context);
  finishTextRequest(context, second, "JavaScript Extension used the declared Android Tool.");
  const terminal = await waitForTerminal(context);
  assert.equal(terminal.finalText, "JavaScript Extension used the declared Android Tool.");
  closeRuntime(context);
});

test("revoked package blocks before the underlying Android Tool starts", async () => {
  const context = await bootRuntime();
  const extensionPackage = packageSnapshot();
  startTask(context, "extension-revoked", [extensionPackage]);
  const first = await nextProviderRequest(context);
  finishToolCall(context, first, "revoked-call", "extension_device_status", {});

  const authorization = await nextNativeToolRequest(context);
  resolveNativeTool(
    context,
    authorization.id,
    { ok: false, errorCode: "EXTENSION_PACKAGE_NOT_ENABLED" },
    true,
  );

  const second = await nextProviderRequest(context);
  assert.match(JSON.stringify(second.messages), /EXTENSION_PACKAGE_NOT_ENABLED/);
  finishTextRequest(context, second, "Extension was revoked.");
  const terminal = await waitForTerminal(context);
  assert.equal(terminal.toolRequestsIssued, 1, "only the live package gate reached Android");
  assert.equal(terminal.toolRequestsResolved, 1);
  closeRuntime(context);
});

test("connector proxy declaration reuses the task Connector through the package gate", async () => {
  const context = await bootRuntime();
  const extensionPackage = {
    ...packageSnapshot(),
    tools: [{
      type: "connector-proxy",
      name: "extension_connector",
      description: "Use the task-scoped Connector catalog.",
    }],
  };
  const connector = connectorSnapshot();
  startTask(context, "extension-connector", [extensionPackage], connector);
  const first = await nextProviderRequest(context);
  finishToolCall(context, first, "connector-search", "extension_connector", {
    action: "search",
    query: "records",
  });

  const authorization = await nextNativeToolRequest(context);
  assert.equal(authorization.kind, "android_extension_package");
  assert.equal(authorization.arguments.type, "connector-proxy");
  assert.equal(authorization.arguments.description, "Use the task-scoped Connector catalog.");
  resolveNativeTool(context, authorization.id, { ok: true });

  const second = await nextProviderRequest(context);
  assert.match(JSON.stringify(second.messages), /linear__search_records/);
  finishTextRequest(context, second, "Connector catalog inspected.");
  const terminal = await waitForTerminal(context);
  assert.equal(terminal.toolRequestsIssued, 1, "catalog search stays local after authorization");
  closeRuntime(context);
});

test("connector proxy stays absent when the task has no bound Connector", async () => {
  const context = await bootRuntime();
  const extensionPackage = {
    ...packageSnapshot(),
    tools: [{
      type: "connector-proxy",
      name: "extension_connector",
      description: "Use the task-scoped Connector catalog.",
    }],
  };
  startTask(context, "extension-no-connector", [extensionPackage]);
  const request = await nextProviderRequest(context);
  assert.equal(request.tools.some((tool) => tool.function.name === "extension_connector"), false);
  finishTextRequest(context, request, "No Connector is configured.");
  await waitForTerminal(context);
  closeRuntime(context);
});

test("package descriptor supports the full 80-character package id contract", async () => {
  const context = await bootRuntime();
  const extensionPackage = { ...packageSnapshot(), id: `a.${"b".repeat(78)}` };
  assert.equal(extensionPackage.id.length, 80);
  startTask(context, "extension-long-id", [extensionPackage]);
  const request = await nextProviderRequest(context);
  assert.equal(request.tools.some((tool) => tool.function.name === "extension_checklist"), true);
  finishTextRequest(context, request, "Long package ID accepted.");
  await waitForTerminal(context);
  closeRuntime(context);
});

test("package Tool snapshots reconcile across Plan disable and Goal enable restores", async () => {
  const extensionPackage = packageSnapshot();

  const planSource = await bootRuntime();
  startTask(planSource, "extension-plan-refresh", [extensionPackage], null, true);
  const planRequest = await nextProviderRequest(planSource);
  finishTextRequest(planSource, planRequest, "Plan remains active.");
  await waitForTerminal(planSource);
  const planSnapshot = taskSnapshot(planSource);
  assert.equal(planSnapshot.prePlanActiveToolNames.includes("extension_checklist"), true);
  closeRuntime(planSource);

  const planRestored = await bootRuntime();
  restoreTask(planRestored, "extension-plan-refresh", planSnapshot, []);
  JSON.parse(call(planRestored, "setNativeOpenRouterTaskPlanModeJson", "false"));
  const exitedPlan = await waitForTerminal(planRestored);
  assert.equal(exitedPlan.commandError, null);
  assert.equal(exitedPlan.activeToolNames.includes("extension_checklist"), false);
  const ordinarySnapshot = taskSnapshot(planRestored);
  closeRuntime(planRestored);

  const ordinaryRestored = await bootRuntime();
  const ordinaryStatus = restoreTask(
    ordinaryRestored,
    "extension-plan-refresh",
    ordinarySnapshot,
    [extensionPackage],
  );
  assert.equal(ordinaryStatus.planMode, false);
  assert.equal(ordinaryStatus.activeToolNames.includes("extension_checklist"), true);
  closeRuntime(ordinaryRestored);

  const goalSource = await bootRuntime();
  startTask(goalSource, "extension-goal-refresh", []);
  const ordinaryRequest = await nextProviderRequest(goalSource);
  finishTextRequest(goalSource, ordinaryRequest, "Ready for a goal.");
  await waitForTerminal(goalSource);
  JSON.parse(call(
    goalSource,
    "startNativeOpenRouterTaskGoalJson",
    JSON.stringify("extension-goal"),
    JSON.stringify("Verify Extension refresh."),
    "1",
    "1",
  ));
  const goalRequest = await nextProviderRequest(goalSource);
  finishTextRequest(goalSource, goalRequest, "Goal remains active.");
  await waitForTerminal(goalSource);
  const goalSnapshot = taskSnapshot(goalSource);
  assert.equal(goalSnapshot.goal.state, "active");
  assert.equal(goalSnapshot.goal.preGoalActiveToolNames.includes("extension_checklist"), false);
  closeRuntime(goalSource);

  const goalRestored = await bootRuntime();
  const status = restoreTask(goalRestored, "extension-goal-refresh", goalSnapshot, [extensionPackage]);
  assert.equal(status.activeToolNames.includes("extension_checklist"), true);
  closeRuntime(goalRestored);

  const pausedSource = await bootRuntime();
  restoreTask(pausedSource, "extension-goal-refresh", goalSnapshot, []);
  JSON.parse(call(
    pausedSource,
    "setNativeOpenRouterTaskGoalStateJson",
    JSON.stringify("extension-goal"),
    "1",
    JSON.stringify("paused"),
  ));
  const paused = await waitForTerminal(pausedSource);
  assert.equal(paused.commandError, null);
  const pausedSnapshot = taskSnapshot(pausedSource);
  closeRuntime(pausedSource);

  const pausedRestored = await bootRuntime();
  const pausedStatus = restoreTask(
    pausedRestored,
    "extension-goal-refresh",
    pausedSnapshot,
    [extensionPackage],
  );
  assert.equal(pausedStatus.goal.state, "paused");
  assert.equal(pausedStatus.activeToolNames.includes("extension_checklist"), true);
  closeRuntime(pausedRestored);
});

test("package snapshot rejects unknown fields and undeclared Android access", async () => {
  const unknownContext = await bootRuntime();
  const unknown = { ...packageSnapshot(), surprise: true };
  assert.throws(
    () => startTask(unknownContext, "extension-unknown", [unknown]),
    /PI_MOBILE_EXTENSION_PACKAGE_FIELDS_INVALID/,
  );
  closeRuntime(unknownContext);

  const capabilityContext = await bootRuntime();
  const base = packageSnapshot();
  const undeclared = {
    ...base,
    requiredCapabilities: [],
    tools: [{ ...base.tools[0], targetTool: "device_calendar" }, base.tools[1]],
  };
  assert.throws(
    () => startTask(capabilityContext, "extension-capability", [undeclared]),
    /PI_MOBILE_EXTENSION_PACKAGE_CAPABILITY_UNDECLARED/,
  );
  closeRuntime(capabilityContext);

  const unsafeContext = await bootRuntime();
  const javascript = javascriptPackageSnapshot();
  const unsafeInteger = {
    ...javascript,
    tools: [
      {
        ...javascript.tools[0],
        parameters: {
          type: "object",
          properties: {
            amount: { type: "integer", enum: [9_007_199_254_740_992] },
          },
          required: ["amount"],
          additionalProperties: false,
        },
      },
      javascript.tools[1],
    ],
  };
  assert.throws(
    () => startTask(unsafeContext, "extension-unsafe-integer", [unsafeInteger]),
    /PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID/,
  );
  closeRuntime(unsafeContext);
});

function packageSnapshot() {
  return {
    schemaVersion: 1,
    id: "com.example.device-helper",
    name: "Device helper",
    version: "1.0.0",
    description: "A bounded fixture Extension.",
    runtime: "declarative-v1",
    entrypoint: null,
    tools: [
      {
        type: "android-tool-alias",
        name: "extension_device_status",
        description: "Use the existing device capability Tool.",
        targetTool: "device_capabilities_get",
      },
      {
        type: "prompt-tool",
        name: "extension_checklist",
        description: "Return a fixed checklist.",
        prompt: "Check live state before reporting success.",
      },
    ],
    requiredCapabilities: ["calendar"],
    optionalCapabilities: [],
    networkOrigins: [],
    packageDigest: "a".repeat(64),
  };
}

function javascriptPackageSnapshot() {
  return {
    ...packageSnapshot(),
    id: "com.example.javascript-helper",
    name: "JavaScript helper",
    runtime: "javascript-v1",
    entrypoint: "dist/index.js",
    tools: [
      {
        type: "javascript-tool",
        name: "extension_js_status",
        description: "Run isolated package JavaScript.",
        parameters: {
          type: "object",
          properties: {
            label: { type: "string", minLength: 1, maxLength: 32 },
          },
          required: ["label"],
          additionalProperties: false,
        },
        parametersDigest: "d".repeat(64),
      },
      packageSnapshot().tools[0],
    ],
    packageDigest: "b".repeat(64),
  };
}

function piRegisterToolPackageSnapshot() {
  return {
    schemaVersion: 2,
    id: "fixtures.official-register-tool",
    name: "Official registerTool fixture",
    version: "1.0.0",
    description: "A deterministic schema v2 product fixture.",
    runtime: "pi-register-tool-v1",
    entrypoint: "dist/index.js",
    tools: [{
      type: "pi-register-tool",
      name: "fixture_echo",
      label: "Fixture echo",
      description: "Return one bounded text value.",
      parameters: {
        type: "object",
        properties: { text: { type: "string", maxLength: 160 } },
        required: ["text"],
        additionalProperties: false,
      },
      promptSnippet: null,
      promptGuidelines: [],
      executionMode: "sequential",
    }],
    hostTools: [],
    requiredCapabilities: [],
    optionalCapabilities: [],
    httpPolicy: { origins: [], methods: [], credentialSlots: [] },
    packageDigest: "c".repeat(64),
  };
}

function piRegisterHostToolPackageSnapshot() {
  return {
    ...piRegisterToolPackageSnapshot(),
    id: "fixtures.host-call",
    name: "Host call fixture",
    description: "Call one declared Android Host Tool.",
    tools: [{
      type: "pi-register-tool",
      name: "fixture_calendar_summary",
      label: "Fixture calendar summary",
      description: "Read Android capabilities through a declared Host Tool.",
      parameters: {
        type: "object",
        properties: { query: { type: "string", maxLength: 32 } },
        required: ["query"],
        additionalProperties: false,
      },
      promptSnippet: null,
      promptGuidelines: [],
      executionMode: "sequential",
    }],
    hostTools: [{
      name: "capabilities",
      targetTool: "device_capabilities_get",
      capability: null,
    }],
    packageDigest: "d".repeat(64),
  };
}

function packageSetDigest(packages) {
  const canonical = [...packages].sort((left, right) => left.id.localeCompare(right.id))
    .map((candidate) => `${candidate.id.length}:${candidate.id}:${candidate.packageDigest}`)
    .join("");
  return createHash("sha256").update(canonical).digest("hex");
}

async function bootRuntime() {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({ console, crypto: webcrypto });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  JSON.parse(call(context, "bootstrapJson"));
  return context;
}

function startTask(context, taskId, packages, connector = null, planMode = false) {
  return JSON.parse(call(
    context,
    "startNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify("Use the enabled Extension."),
    JSON.stringify(modelId),
    JSON.stringify(taskId),
    String(planMode),
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    "false",
    JSON.stringify(JSON.stringify(connector)),
    JSON.stringify(JSON.stringify(packages)),
  ));
}

function restoreTask(context, taskId, snapshot, packages) {
  return JSON.parse(call(
    context,
    "restoreNativeOpenRouterTaskSessionJson",
    JSON.stringify(taskId),
    JSON.stringify(taskId),
    String(snapshot.turnCount),
    JSON.stringify(JSON.stringify(snapshot.entries)),
    JSON.stringify(modelId),
    JSON.stringify("[]"),
    JSON.stringify("[]"),
    "false",
    JSON.stringify("null"),
    JSON.stringify(JSON.stringify(packages)),
  ));
}

function taskSnapshot(context) {
  return JSON.parse(call(context, "nativeOpenRouterTaskSessionSnapshotJson"));
}

async function nextProviderRequest(context) {
  for (let attempt = 0; attempt < 300; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderRequestsJson"));
    if (requests.length > 0) {
      assert.equal(requests.length, 1);
      return requests[0];
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Provider request did not arrive");
}

async function nextNativeToolRequest(context) {
  const requests = await nextNativeToolRequests(context);
  assert.equal(requests.length, 1);
  return requests[0];
}

async function nextNativeToolRequests(context) {
  for (let attempt = 0; attempt < 300; attempt += 1) {
    const requests = JSON.parse(call(context, "drainNativeProviderToolRequestsJson"));
    if (requests.length > 0) {
      return requests;
    }
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Native Tool request did not arrive");
}

async function waitForTerminal(context) {
  for (let attempt = 0; attempt < 500; attempt += 1) {
    const status = JSON.parse(call(context, "nativeOpenRouterScenarioStatusJson"));
    if (status.terminal) return status;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("Task did not settle");
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
          function: { name: toolName, arguments: JSON.stringify(args) },
        }],
      },
      finish_reason: "tool_calls",
    }],
  });
  completeRequest(context, request.id);
}

function finishTextRequest(context, request, text) {
  pushChunk(context, request.id, {
    id: `generation-${request.id}`,
    model: request.modelId,
    choices: [{ delta: { content: text }, finish_reason: "stop" }],
  });
  completeRequest(context, request.id);
}

function resolveNativeTool(context, requestId, result, isError = false) {
  JSON.parse(call(
    context,
    "resolveNativeProviderToolRequestJson",
    JSON.stringify(requestId),
    JSON.stringify(JSON.stringify(result)),
    JSON.stringify(JSON.stringify(result)),
    JSON.stringify(isError),
  ));
}

function connectorSnapshot() {
  const snapshot = {
    version: 1,
    connectorId: "linear",
    connectionId: "fixture-connection-1",
    sourceLabel: "Linear fixture",
    mode: "proxy",
    schemaDigest: "",
    tools: [{
      remoteName: "search_records",
      exposedName: "linear__search_records",
      title: "Search records",
      description: "Search deterministic records by text.",
      inputSchema: {
        type: "object",
        properties: { query: { type: "string", minLength: 1, maxLength: 128 } },
        required: ["query"],
        additionalProperties: false,
      },
      risk: "read",
    }],
  };
  const payload = { ...snapshot };
  delete payload.schemaDigest;
  snapshot.schemaDigest = createHash("sha256")
    .update(stableJson(payload), "utf8")
    .digest("hex");
  return snapshot;
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${stableJson(value[key])}`
    ).join(",")}}`;
  }
  return JSON.stringify(value);
}

function pushChunk(context, requestId, chunk) {
  JSON.parse(call(
    context,
    "pushNativeProviderChunkJson",
    JSON.stringify(requestId),
    JSON.stringify(JSON.stringify(chunk)),
  ));
}

function completeRequest(context, requestId) {
  JSON.parse(call(
    context,
    "completeNativeProviderRequestJson",
    JSON.stringify(requestId),
    JSON.stringify(`generation-${requestId}`),
  ));
}

function closeRuntime(context) {
  JSON.parse(call(context, "closeJson"));
}

function call(context, functionName, ...serializedArguments) {
  return runInContext(
    `PiMobileRuntimeBundle.${functionName}(${serializedArguments.join(",")})`,
    context,
  );
}
