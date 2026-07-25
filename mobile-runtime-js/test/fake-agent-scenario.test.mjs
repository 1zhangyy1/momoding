import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import test from "node:test";

const bundleUrl = new URL(
  "../../android-app/app/src/main/assets/pi-runtime/pi-mobile.js",
  import.meta.url,
);

test("real AgentHarness runs fake provider, Android mock tool, errors, and stop", async () => {
  const bundle = await readFile(bundleUrl, "utf8");
  const context = createContext({
    console,
    crypto: webcrypto,
  });
  runInContext(bundle, context, { filename: "pi-mobile.js" });
  JSON.parse(call(context, "bootstrapJson"));

  const success = await runScenario(context, "tool_success");
  assert.equal(success.expectationMet, true, JSON.stringify(success));
  assert.equal(success.toolRequestsResolved, 1);
  assert.deepEqual(
    pickLifecycle(success.eventTypes),
    ["agent_start", "tool_execution_start", "tool_execution_end", "settled"],
  );
  assert.equal(
    success.events.find((event) => event.type === "tool_execution_end")?.isError,
    false,
  );

  const toolError = await runScenario(context, "tool_error");
  assert.equal(toolError.expectationMet, true, JSON.stringify(toolError));
  assert.equal(toolError.toolRequestsRejected, 1);
  assert.equal(
    toolError.events.find((event) => event.type === "tool_execution_end")?.isError,
    true,
  );

  const providerError = await runScenario(context, "provider_error");
  assert.equal(providerError.expectationMet, true, JSON.stringify(providerError));
  assert.equal(providerError.toolRequestsIssued, 0);
  assert.equal(providerError.hasSettled, true);

  const stopped = await runScenario(context, "stop_before_tool");
  assert.equal(stopped.expectationMet, true, JSON.stringify(stopped));
  assert.equal(stopped.stopCompleted, true);
  assert.equal(stopped.hasAbort, true);
  assert.equal(stopped.toolRequestsIssued, 0);
  assert.equal(stopped.toolExecutionsStarted, 0);
  assert.equal(stopped.lateToolStartsAfterStop, 0);

  assert.deepEqual(JSON.parse(call(context, "closeJson")), { ok: true, closed: true });
});

async function runScenario(context, kind) {
  JSON.parse(call(context, "startScenarioJson", JSON.stringify(kind)));
  let stopIssued = false;
  for (let attempt = 0; attempt < 500; attempt += 1) {
    const status = JSON.parse(call(context, "scenarioStatusJson"));
    if (kind === "stop_before_tool" && status.phase === "provider_waiting" && !stopIssued) {
      JSON.parse(call(context, "abortScenarioJson"));
      stopIssued = true;
    }
    const requests = JSON.parse(call(context, "drainNativeRequestsJson"));
    for (const request of requests) {
      if (kind === "tool_error") {
        JSON.parse(call(
          context,
          "rejectNativeRequestJson",
          JSON.stringify(request.id),
          JSON.stringify("Android mock tool rejected the request"),
        ));
      } else {
        JSON.parse(call(
          context,
          "resolveNativeRequestJson",
          JSON.stringify(request.id),
          JSON.stringify(JSON.stringify({ echoed: request.arguments.text })),
        ));
      }
    }
    const after = JSON.parse(call(context, "scenarioStatusJson"));
    if (after.terminal) return after;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error(`Scenario ${kind} did not settle`);
}

function call(context, functionName, ...serializedArguments) {
  return runInContext(
    `PiMobileRuntimeBundle.${functionName}(${serializedArguments.join(",")})`,
    context,
  );
}

function pickLifecycle(eventTypes) {
  const expected = new Set([
    "agent_start",
    "tool_execution_start",
    "tool_execution_end",
    "settled",
  ]);
  return eventTypes.filter((type) => expected.has(type));
}
