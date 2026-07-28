import { createHash } from "node:crypto";

const TARGET_SCREENS = new Set(["S1", "S2", "S3", "S4", "S8"]);
const P2_MOBILE_FILE_BOUNDARY = "Mobile files unavailable until P3A; no permission probe or action.";

const P2_5_VISIBLE_CHECKS = new Set([
  "HOME_EMPTY",
  "HOME_LOADING",
  "HOME_READY",
  "HOME_ATTENTION",
  "HOME_OFFLINE",
  "HOME_RECONNECTING",
  "HOME_UNPAIRED",
  "HOME_RUNNING",
  "HOME_WAITING",
  "HOME_COMPLETED_UNREAD",
  "HOME_COMPLETED_READ",
  "HOME_FAILED",
  "HOME_SEARCH_EMPTY",
  "HOME_LONG_TITLE",
  "NEW_EMPTY",
  "NEW_VALID",
  "NEW_MULTILINE",
  "NEW_IME",
  "NEW_DRAFT_RESTORED",
  "NEW_OFFLINE",
  "NEW_HOST_UNAVAILABLE",
  "NEW_MODEL_LOADING",
  "NEW_MODEL_ERROR",
  "NEW_SEND_PENDING",
  "NEW_SEND_ERROR",
  "NEW_ATTACHMENTS_UNAVAILABLE",
]);

const P2_5_SEMANTIC_CHECKS = new Set([
  "HOME_EMPTY_ANNOUNCED",
  "HOME_LOADING_ANNOUNCED",
  "HOME_PRIORITY_ORDER",
  "HOME_ATTENTION_REASON",
  "HOME_OFFLINE_DISTINCT",
  "HOME_RECONNECTING_DISTINCT",
  "HOME_UNPAIRED_DISTINCT",
  "HOME_RUNNING_LABELED",
  "HOME_WAITING_REASON",
  "HOME_COMPLETED_UNREAD_SEPARATE",
  "HOME_COMPLETED_READ_DISTINCT",
  "HOME_FAILED_LABELED",
  "HOME_SEARCH_EMPTY_DISTINCT",
  "HOME_LONG_TITLE_COMPLETE",
  "NEW_EMPTY_BLOCKED",
  "NEW_READ_ONLY_PROFILE",
  "NEW_MULTILINE_REACHABLE",
  "NEW_IME_REACHABLE",
  "NEW_RESTORED_ONCE",
  "NEW_OFFLINE_BLOCKED",
  "NEW_HOST_UNAVAILABLE_DISTINCT",
  "NEW_MODEL_LOADING_LABELED",
  "NEW_MODEL_ERROR_DISTINCT",
  "NEW_PENDING_ONCE",
  "NEW_SEND_ERROR_RETAINED",
  "NEW_UNAVAILABLE_NO_ACTION",
]);

const P2_5_FORBIDDEN_ACTIONS = new Set([
  "OpenFiles",
  "RenameTask",
  "RetryTask",
  "MarkRead",
  "CancelReconnect",
  "SelectHost",
  "SelectGrant",
  "AddAttachment",
  "ChangePermission",
  "CancelSend",
  "SaveDraft",
]);

const S8_VISIBLE_CHECKS = Object.freeze([
  ["Sanitized diagnostics export in progress.", "DIAGNOSTICS_EXPORTING"],
  ["Sanitized archive ready for Android share.", "DIAGNOSTICS_READY"],
  ["Power Mode disabled; capability not installed.", "POWER_UNAVAILABLE"],
  ["Paired Host offline; Retry; Mobile files unavailable.", "HOST_OFFLINE"],
  ["Paired credential revoked; Pair again; Mobile files unavailable.", "HOST_REVOKED"],
  ["Agent defaults unavailable; Retry.", "AGENT_ERROR"],
  ["Default model and thinking settings loading.", "AGENT_LOADING"],
  ["Pairing progress and cancel action.", "PAIRING"],
  ["Reduced motion follows Android system setting.", "REDUCED_MOTION"],
  ["Connected Mac Studio; read-only agent profile; system theme; Mobile files unavailable.", "SETTINGS_READY"],
  ["Dark theme selected.", "THEME_DARK"],
  ["Light theme selected.", "THEME_LIGHT"],
  ["Theme follows system.", "THEME_SYSTEM"],
  ["No Host; Pair Host; no mobile folder capability in P2.", "UNPAIRED"],
  ["Wire version mismatch; Host update required; execution blocked.", "VERSION_MISMATCH"],
]);

const S8_SEMANTIC_CHECKS = Object.freeze(new Map([
  ["Export scope states that tokens URIs and file contents are excluded.", "DIAGNOSTICS_EXCLUSIONS"],
  ["Archive lifetime and exclusions are named.", "DIAGNOSTICS_LIFETIME"],
  ["Unavailable experimental capability does not request Android permission.", "POWER_NO_PERMISSION"],
  ["Offline is distinct from unpaired and does not imply a folder-permission change.", "OFFLINE_DISTINCT"],
  ["Credential revoke is a Host-auth state; P2 does not claim an Android grant state.", "HOST_REVOKED_DISTINCT"],
  ["Agent error does not hide Host connection state.", "AGENT_ERROR_HOST_VISIBLE"],
  ["Loading row retains its group label.", "AGENT_LOADING_LABELED"],
  ["Pairing credential is never displayed.", "PAIRING_SECRET_HIDDEN"],
  ["Motion preference has text and state.", "REDUCED_MOTION_SYSTEM"],
  ["Host state and the unavailable mobile-file row are separate and neither implies a folder grant.", "HOST_AND_MOBILE_SEPARATE"],
  ["Dark selection is not represented only by color.", "THEME_DARK_SELECTED"],
  ["Light selection is not represented only by color.", "THEME_LIGHT_SELECTED"],
  ["Selected theme exposes checked state.", "THEME_SYSTEM_SELECTED"],
  ["Unpaired state does not claim that a local folder grant exists.", "NO_GRANT_CLAIM"],
  ["Mismatch names App and Host protocol versions in details.", "VERSION_DETAILS"],
]));

const S8_DIRECT_ACTION_CONTROLS = Object.freeze({
  PairHost: ["PAIR_HOST"],
  CancelPairing: ["CANCEL_PAIRING"],
  RetryConnection: ["RETRY_CONNECTION"],
  UnpairHost: ["UNPAIR_HOST"],
  RetryAgentDefaults: ["RETRY_AGENT_DEFAULTS"],
  SelectSystem: ["SELECT_SYSTEM"],
  SelectLight: ["SELECT_LIGHT"],
  SelectDark: ["SELECT_DARK"],
  OpenSystemAccessibility: ["OPEN_SYSTEM_ACCESSIBILITY"],
  ExportDiagnostics: ["EXPORT_DIAGNOSTICS"],
  CancelDiagnosticsExport: ["CANCEL_DIAGNOSTICS_EXPORT"],
  ShareDiagnostics: ["SHARE_DIAGNOSTICS"],
  DeleteDiagnosticsArchive: ["DELETE_DIAGNOSTICS_ARCHIVE"],
  OpenVersionDetails: ["OPEN_VERSION_DETAILS"],
  OpenCapabilityInfo: ["OPEN_DEVICE_CAPABILITIES"],
});

function createS8RenderOracle(fixture) {
  requireCondition(
    fixture.visibleData.endsWith(P2_MOBILE_FILE_BOUNDARY),
    `${fixture.id} visible data omits the P2 mobile-file boundary`,
  );
  const visibleMatches = S8_VISIBLE_CHECKS.filter(
    ([visibleData]) => fixture.visibleData === `${visibleData} ${P2_MOBILE_FILE_BOUNDARY}`,
  );
  requireCondition(visibleMatches.length === 1, `${fixture.id} visible data is not a recognized S8 oracle`);
  const semanticCheck = S8_SEMANTIC_CHECKS.get(fixture.expectedSemantics);
  requireCondition(semanticCheck, `${fixture.id} expected semantics is not a recognized S8 oracle`);

  const actionBindings = {};
  for (const action of fixture.allowedActions ?? []) {
    const controls = action === "ChangeTheme"
      ? ["CHANGE_THEME", "SELECT_SYSTEM", "SELECT_LIGHT", "SELECT_DARK"]
      : action === "ChangeSetting"
        ? ["SELECT_SYSTEM", "SELECT_LIGHT", "SELECT_DARK"]
        : S8_DIRECT_ACTION_CONTROLS[action];
    requireCondition(controls, `${fixture.id} has an unsupported S8 action ${action}`);
    for (const control of controls) {
      requireCondition(!actionBindings[control], `${fixture.id} ambiguously binds ${control}`);
      actionBindings[control] = action;
    }
  }
  return {
    visibleCheck: visibleMatches[0][1],
    semanticCheck,
    actionBindings,
  };
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function canonicalJson(value) {
  const canonicalize = (entry) => {
    if (Array.isArray(entry)) return entry.map(canonicalize);
    if (entry && typeof entry === "object") {
      return Object.fromEntries(
        Object.keys(entry)
          .sort()
          .map((key) => [key, canonicalize(entry[key])]),
      );
    }
    return entry;
  };
  return JSON.stringify(canonicalize(value));
}

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function applyOverride(fixture, override) {
  for (const [key, value] of Object.entries(override ?? {})) {
    if (key === "stateDimensions" || key === "disabledReasons") {
      fixture[key] = { ...(fixture[key] ?? {}), ...clone(value) };
    } else if (key === "allowedActions") {
      fixture.allowedActions = clone(value);
    } else {
      fixture[key] = clone(value);
    }
  }
}

function createP2_5StepProjection(profile, p2Fixtures, p2Captures) {
  const step = profile?.stepProfiles?.P2_5;
  requireCondition(step, "phaseProfiles.P2.stepProfiles.P2_5 is missing");
  requireCondition(
    JSON.stringify(step.projectionOrder) === JSON.stringify([
      "clone-P2-fixture",
      "apply-step-screen-action-overrides",
      "apply-step-fixture-action-overrides",
      "attach-closed-render-oracle",
      "attach-synthetic-seed",
    ]),
    "P2_5 projection order differs",
  );

  const sourceHash = createHash("sha256")
    .update(JSON.stringify(step.fixtureContracts ?? {}))
    .digest("hex");
  requireCondition(sourceHash === step.fixtureContractsSha256, "P2_5 fixture prose registry differs");

  const p2ById = new Map(p2Fixtures.map((fixture) => [fixture.id, fixture]));
  const expectedIds = p2Fixtures
    .filter((fixture) => fixture.screen === "S1" || fixture.screen === "S2")
    .map((fixture) => fixture.id)
    .sort();
  const applicabilityIds = Object.keys(step.fixtureApplicability ?? {}).sort();
  const contractIds = Object.keys(step.fixtureContracts ?? {}).sort();
  requireCondition(
    JSON.stringify(expectedIds) === JSON.stringify(applicabilityIds),
    "P2_5 fixture applicability does not exactly match S1/S2",
  );
  requireCondition(
    JSON.stringify(expectedIds) === JSON.stringify(contractIds),
    "P2_5 fixture contracts do not exactly match S1/S2",
  );
  requireCondition(expectedIds.length === step.expectedFixtureCount, "P2_5 fixture count differs");
  requireCondition(expectedIds.filter((id) => id.startsWith("S1-")).length === 14, "P2_5 S1 fixture count differs");
  requireCondition(expectedIds.filter((id) => id.startsWith("S2-")).length === 12, "P2_5 S2 fixture count differs");

  const actionControls = step.actionControls ?? {};
  const controlsToActions = new Map();
  for (const [action, controls] of Object.entries(actionControls)) {
    requireCondition(Array.isArray(controls) && controls.length > 0, `P2_5 action ${action} has no control`);
    requireCondition(new Set(controls).size === controls.length, `P2_5 action ${action} repeats a control`);
    for (const control of controls) {
      requireCondition(typeof control === "string" && /^[A-Z][A-Z0-9_]+$/.test(control), `P2_5 control differs: ${control}`);
      requireCondition(!controlsToActions.has(control), `P2_5 control ${control} has duplicate binding`);
      controlsToActions.set(control, action);
    }
  }

  const actionOverrideIds = Object.keys(step.fixtureActionOverrides ?? {});
  requireCondition(
    actionOverrideIds.every((id) => expectedIds.includes(id)),
    "P2_5 action override targets an unknown fixture",
  );

  const projected = expectedIds.map((fixtureId) => {
    const fixture = clone(p2ById.get(fixtureId));
    const applicability = step.fixtureApplicability[fixtureId];
    const contract = step.fixtureContracts[fixtureId];
    requireCondition(applicability.screen === fixture.screen, `P2_5 profile screen differs for ${fixtureId}`);
    requireCondition(P2_5_VISIBLE_CHECKS.has(contract.visibleCheck), `${fixtureId} visible check is unknown`);
    requireCondition(P2_5_SEMANTIC_CHECKS.has(contract.semanticCheck), `${fixtureId} semantic check is unknown`);
    requireCondition(typeof contract.visibleData === "string" && contract.visibleData.trim() === contract.visibleData, `${fixtureId} visible prose differs`);
    requireCondition(typeof contract.expectedSemantics === "string" && contract.expectedSemantics.trim() === contract.expectedSemantics, `${fixtureId} semantic prose differs`);
    requireCondition(typeof contract.seedAlias === "string" && /^[a-z0-9-]+$/.test(contract.seedAlias), `${fixtureId} seed alias differs`);

    const removed = new Set(step.screenOverrides?.[fixture.screen]?.removeAllowedActions ?? []);
    fixture.allowedActions = (fixture.allowedActions ?? []).filter((action) => !removed.has(action));
    const actionOverride = step.fixtureActionOverrides?.[fixtureId] ?? {};
    const fixtureRemovals = new Set(actionOverride.removeAllowedActions ?? []);
    fixture.allowedActions = fixture.allowedActions.filter((action) => !fixtureRemovals.has(action));
    fixture.allowedActions.push(...clone(actionOverride.addAllowedActions ?? []));
    requireCondition(
      fixture.allowedActions.length === new Set(fixture.allowedActions).size,
      `${fixtureId} has duplicate P2_5 actions`,
    );
    requireCondition(
      fixture.allowedActions.every((action) => !P2_5_FORBIDDEN_ACTIONS.has(action)),
      `${fixtureId} exposes a future or fake P2_5 action`,
    );

    const actionBindings = {};
    for (const action of fixture.allowedActions) {
      const controls = actionControls[action];
      requireCondition(controls, `${fixtureId} has an unsupported P2_5 action ${action}`);
      for (const control of controls) {
        requireCondition(!actionBindings[control], `${fixtureId} ambiguously binds ${control}`);
        actionBindings[control] = action;
      }
    }
    requireCondition(
      JSON.stringify([...new Set(Object.values(actionBindings))].sort()) ===
        JSON.stringify([...new Set(fixture.allowedActions)].sort()),
      `${fixtureId} action oracle differs from allowed actions`,
    );

    fixture.visibleData = contract.visibleData;
    fixture.expectedSemantics = contract.expectedSemantics;
    fixture.step = "P2_5";
    fixture.renderOracle = {
      visibleCheck: contract.visibleCheck,
      semanticCheck: contract.semanticCheck,
      actionBindings,
    };
    fixture.seed = fixture.screen === "S1"
      ? { profileAlias: "p2-single-host", taskSetAlias: contract.seedAlias }
      : { profileAlias: "p2-single-host", draftAlias: contract.seedAlias };
    return fixture;
  });

  const usedActions = new Set(projected.flatMap((fixture) => fixture.allowedActions));
  requireCondition(
    JSON.stringify([...usedActions].sort()) === JSON.stringify(Object.keys(actionControls).sort()),
    "P2_5 action control registry does not exactly match projected actions",
  );
  const hostUnavailable = projected.find((fixture) => fixture.id === "S2-host-unavailable");
  requireCondition(hostUnavailable.allowedActions.includes("RetryConnection"), "S2-host-unavailable omits RetryConnection");

  const captureById = new Map(p2Captures.map((capture) => [capture.captureId, capture]));
  const captureIds = step.captureIds ?? [];
  requireCondition(captureIds.length === 10 && new Set(captureIds).size === 10, "P2_5 capture candidate count differs");
  const captureCandidates = captureIds.map((captureId) => {
    const capture = clone(captureById.get(captureId));
    requireCondition(capture, `P2_5 capture is missing: ${captureId}`);
    requireCondition(expectedIds.includes(capture.fixtureId), `P2_5 capture fixture differs: ${captureId}`);
    capture.status = "CAPTURED_CANDIDATES_NOT_APPROVED";
    return capture;
  });

  return {
    schemaVersion: 1,
    step: "P2_5",
    projectionOrder: clone(step.projectionOrder),
    fixtureCount: projected.length,
    fixtureCounts: { S1: 14, S2: 12 },
    fixtures: projected,
    captureCandidates,
  };
}

function createP2_6StepProjection(profile, p2Fixtures, p2Captures) {
  const step = profile?.stepProfiles?.P2_6;
  requireCondition(step, "phaseProfiles.P2.stepProfiles.P2_6 is missing");
  requireCondition(
    JSON.stringify(step.projectionOrder) === JSON.stringify([
      "clone-P2-fixture",
      "apply-step-screen-action-overrides",
      "apply-step-fixture-action-overrides",
      "attach-closed-render-oracle",
      "attach-synthetic-seed",
    ]),
    "P2_6 projection order differs",
  );
  const sourceHash = createHash("sha256")
    .update(JSON.stringify(step.fixtureContracts ?? {}))
    .digest("hex");
  requireCondition(sourceHash === step.fixtureContractsSha256, "P2_6 fixture prose registry differs");

  const p2ById = new Map(p2Fixtures.map((fixture) => [fixture.id, fixture]));
  const expectedIds = p2Fixtures
    .filter((fixture) => fixture.screen === "S3")
    .map((fixture) => fixture.id)
    .sort();
  requireCondition(expectedIds.length === 17, "P2_6 S3 fixture count differs");
  requireCondition(expectedIds.length === step.expectedFixtureCount, "P2_6 fixture count differs");
  requireCondition(
    JSON.stringify(expectedIds) === JSON.stringify(Object.keys(step.fixtureApplicability ?? {}).sort()),
    "P2_6 fixture applicability does not exactly match S3",
  );
  requireCondition(
    JSON.stringify(expectedIds) === JSON.stringify(Object.keys(step.fixtureContracts ?? {}).sort()),
    "P2_6 fixture contracts do not exactly match S3",
  );

  const actionControls = step.actionControls ?? {};
  const controlsToActions = new Map();
  for (const [action, controls] of Object.entries(actionControls)) {
    requireCondition(Array.isArray(controls) && controls.length > 0, `P2_6 action ${action} has no control`);
    requireCondition(new Set(controls).size === controls.length, `P2_6 action ${action} repeats a control`);
    for (const control of controls) {
      requireCondition(/^[A-Z][A-Z0-9_]+$/.test(control), `P2_6 control differs: ${control}`);
      requireCondition(!controlsToActions.has(control), `P2_6 control ${control} has duplicate binding`);
      controlsToActions.set(control, action);
    }
  }
  const dynamicBehaviorContracts = step.dynamicBehaviorContracts ?? {};
  requireCondition(
    JSON.stringify(Object.keys(dynamicBehaviorContracts).sort()) === JSON.stringify(["timeline-detached-jump"]),
    "P2_6 dynamic behavior registry differs",
  );
  const jumpContract = dynamicBehaviorContracts["timeline-detached-jump"];
  requireCondition(
    JSON.stringify(Object.keys(jumpContract ?? {}).sort()) === JSON.stringify([
      "action",
      "condition",
      "control",
      "expectedSemantics",
      "interaction",
      "screen",
    ]),
    "P2_6 timeline jump contract shape differs",
  );
  requireCondition(
    jumpContract.screen === "S3" &&
      jumpContract.condition === "timeline-detached-with-unseen-items" &&
      jumpContract.control === "JUMP_TO_LATEST" &&
      jumpContract.action === "JumpToLatest" &&
      jumpContract.interaction === "JUMP_TO_LATEST" &&
      jumpContract.expectedSemantics ===
        "A detached timeline exposes one enabled Jump to latest action; activating it returns to the live edge and removes the action.",
    "P2_6 timeline jump contract differs",
  );
  requireCondition(
    actionControls[jumpContract.action]?.length === 1 &&
      actionControls[jumpContract.action][0] === jumpContract.control,
    "P2_6 timeline jump action binding differs",
  );
  const forbidden = new Set([
    "RetryTool",
    "RetryTask",
    "OpenCachedOutput",
    "StopRecovery",
    "EditQueued",
    "DeleteQueued",
    "AddAttachment",
    "SelectGrant",
  ]);
  const projected = expectedIds.map((fixtureId) => {
    const fixture = clone(p2ById.get(fixtureId));
    const applicability = step.fixtureApplicability[fixtureId];
    const contract = step.fixtureContracts[fixtureId];
    requireCondition(applicability.screen === "S3" && fixture.screen === "S3", `${fixtureId} P2_6 screen differs`);
    requireCondition(/^DETAIL_[A-Z0-9_]+$/.test(contract.visibleCheck), `${fixtureId} visible check differs`);
    requireCondition(/^DETAIL_[A-Z0-9_]+$/.test(contract.semanticCheck), `${fixtureId} semantic check differs`);
    requireCondition(typeof contract.seedAlias === "string" && /^[a-z0-9-]+$/.test(contract.seedAlias), `${fixtureId} seed differs`);

    const removed = new Set(step.screenOverrides?.S3?.removeAllowedActions ?? []);
    fixture.allowedActions = (fixture.allowedActions ?? []).filter((action) => !removed.has(action));
    const override = step.fixtureActionOverrides?.[fixtureId] ?? {};
    const fixtureRemovals = new Set(override.removeAllowedActions ?? []);
    fixture.allowedActions = fixture.allowedActions.filter((action) => !fixtureRemovals.has(action));
    fixture.allowedActions.push(...clone(override.addAllowedActions ?? []));
    requireCondition(fixture.allowedActions.length === new Set(fixture.allowedActions).size, `${fixtureId} repeats P2_6 actions`);
    requireCondition(fixture.allowedActions.every((action) => !forbidden.has(action)), `${fixtureId} exposes a fake P2_6 action`);

    const actionBindings = {};
    for (const action of fixture.allowedActions) {
      const controls = actionControls[action];
      requireCondition(controls, `${fixtureId} has unsupported P2_6 action ${action}`);
      for (const control of controls) {
        requireCondition(!actionBindings[control], `${fixtureId} ambiguously binds ${control}`);
        actionBindings[control] = action;
      }
    }
    fixture.visibleData = contract.visibleData;
    fixture.expectedSemantics = contract.expectedSemantics;
    fixture.step = "P2_6";
    fixture.renderOracle = {
      visibleCheck: contract.visibleCheck,
      semanticCheck: contract.semanticCheck,
      actionBindings,
    };
    fixture.seed = { profileAlias: "p2-single-host", taskSetAlias: contract.seedAlias };
    return fixture;
  });

  const usedActions = new Set([
    ...projected.flatMap((fixture) => fixture.allowedActions),
    ...Object.values(dynamicBehaviorContracts).map((contract) => contract.action),
  ]);
  requireCondition(
    JSON.stringify([...usedActions].sort()) === JSON.stringify(Object.keys(actionControls).sort()),
    "P2_6 action control registry does not exactly match projected actions",
  );
  const captureById = new Map(p2Captures.map((capture) => [capture.captureId, capture]));
  const captureIds = step.captureIds ?? [];
  requireCondition(captureIds.length === 6 && new Set(captureIds).size === 6, "P2_6 capture candidate count differs");
  const captureCandidates = captureIds.map((captureId) => {
    const capture = clone(captureById.get(captureId));
    requireCondition(capture, `P2_6 capture is missing: ${captureId}`);
    requireCondition(expectedIds.includes(capture.fixtureId), `P2_6 capture fixture differs: ${captureId}`);
    capture.status = "CAPTURED_CANDIDATES_NOT_APPROVED";
    return capture;
  });
  return {
    schemaVersion: 1,
    step: "P2_6",
    projectionOrder: clone(step.projectionOrder),
    fixtureCount: projected.length,
    fixtureCounts: { S3: 17 },
    fixtures: projected,
    dynamicBehaviorContracts: Object.entries(dynamicBehaviorContracts).map(([id, contract]) => ({
      id,
      ...clone(contract),
    })),
    captureCandidates,
  };
}

const P2_7_ACTION_CONTROLS = Object.freeze({
  ConfirmOnce: ["CONFIRM_ONCE"],
  Reject: ["REJECT"],
  Dismiss: ["DISMISS", "SYSTEM_BACK_OR_SWIPE"],
  SelectChoice: ["SELECT_CHOICE"],
  Answer: ["ANSWER"],
  Skip: ["SKIP"],
  ReturnToTask: ["RETURN_TO_TASK"],
  RetryConnection: ["RETRY_CONNECTION"],
  EditAnswer: ["EDIT_ANSWER"],
});

const P2_7_SCREEN_OVERRIDES = Object.freeze({
  S4: { removeAllowedActions: ["OpenDiff", "OpenLatestPlan"] },
});

const P2_7_FIXTURE_ACTION_OVERRIDES = Object.freeze({
  "S4-validation-error": {
    removeAllowedActions: ["Answer"],
    addAllowedActions: ["Dismiss"],
  },
});

const P2_7_CLOSED_ORACLE = Object.freeze({
  "S4-confirm": {
    stateDimensions: {
      requestKind: "confirmation",
      responseState: "pending",
      connectionState: "connected",
      validityState: "current",
      selectionState: "none",
    },
    allowedActions: ["ConfirmOnce", "Reject", "Dismiss"],
    disabledActions: [],
    visibleCheck: "ATTENTION_CONFIRM",
    semanticCheck: "ATTENTION_CONFIRM_NON_SIDE_EFFECT",
    seedAlias: "attention-confirm",
  },
  "S4-question": {
    stateDimensions: {
      requestKind: "question",
      responseState: "pending",
      connectionState: "connected",
      validityState: "current",
      selectionState: "option-0",
    },
    allowedActions: ["SelectChoice", "Answer", "Skip", "Dismiss"],
    disabledActions: [],
    visibleCheck: "ATTENTION_QUESTION",
    semanticCheck: "ATTENTION_QUESTION_RADIO_GROUP",
    seedAlias: "attention-question",
  },
  "S4-responding": {
    stateDimensions: {
      requestKind: "question",
      responseState: "responding",
      connectionState: "connected",
      validityState: "current",
      selectionState: "option-0",
    },
    allowedActions: [],
    disabledActions: ["Answer", "Skip", "Dismiss"],
    visibleCheck: "ATTENTION_RESPONDING",
    semanticCheck: "ATTENTION_RESPONDING_LOCKED",
    seedAlias: "attention-responding",
  },
  "S4-resolved": {
    stateDimensions: {
      requestKind: "question",
      responseState: "resolved",
      connectionState: "connected",
      validityState: "current",
      selectionState: "option-0",
    },
    allowedActions: ["ReturnToTask"],
    disabledActions: ["Answer"],
    visibleCheck: "ATTENTION_RESOLVED",
    semanticCheck: "ATTENTION_RESOLVED_TERMINAL",
    seedAlias: "attention-resolved",
  },
  "S4-rejected": {
    stateDimensions: {
      requestKind: "confirmation",
      responseState: "rejected",
      connectionState: "connected",
      validityState: "current",
      selectionState: "none",
    },
    allowedActions: ["ReturnToTask"],
    disabledActions: ["ConfirmOnce"],
    visibleCheck: "ATTENTION_REJECTED",
    semanticCheck: "ATTENTION_REJECTED_EXPLICIT",
    seedAlias: "attention-rejected",
  },
  "S4-skipped": {
    stateDimensions: {
      requestKind: "question",
      responseState: "skipped",
      connectionState: "connected",
      validityState: "current",
      selectionState: "none",
    },
    allowedActions: ["ReturnToTask"],
    disabledActions: ["Answer"],
    visibleCheck: "ATTENTION_SKIPPED",
    semanticCheck: "ATTENTION_SKIPPED_DISTINCT",
    seedAlias: "attention-skipped",
  },
  "S4-expired": {
    stateDimensions: {
      requestKind: "confirmation",
      responseState: "expired",
      connectionState: "connected",
      validityState: "expired",
      selectionState: "none",
    },
    allowedActions: ["Dismiss"],
    disabledActions: ["ConfirmOnce", "Reject"],
    visibleCheck: "ATTENTION_EXPIRED",
    semanticCheck: "ATTENTION_EXPIRED_FAIL_CLOSED",
    seedAlias: "attention-expired",
  },
  "S4-cancelled": {
    stateDimensions: {
      requestKind: "question",
      responseState: "cancelled",
      connectionState: "connected",
      validityState: "cancelled",
      selectionState: "none",
    },
    allowedActions: ["ReturnToTask"],
    disabledActions: ["Answer", "Skip"],
    visibleCheck: "ATTENTION_CANCELLED",
    semanticCheck: "ATTENTION_CANCELLED_DISTINCT",
    seedAlias: "attention-cancelled",
  },
  "S4-offline": {
    stateDimensions: {
      requestKind: "question",
      responseState: "pending",
      connectionState: "offline",
      validityState: "unknown",
      selectionState: "option-0",
    },
    allowedActions: ["Dismiss", "RetryConnection"],
    disabledActions: ["Answer", "Skip"],
    visibleCheck: "ATTENTION_OFFLINE",
    semanticCheck: "ATTENTION_OFFLINE_RETAINED",
    seedAlias: "attention-offline",
  },
  "S4-already-answered": {
    stateDimensions: {
      requestKind: "question",
      responseState: "already-answered",
      connectionState: "connected",
      validityState: "host-terminal-observed",
      selectionState: "none",
    },
    allowedActions: ["ReturnToTask"],
    disabledActions: ["Answer"],
    visibleCheck: "ATTENTION_ALREADY_ANSWERED",
    semanticCheck: "ATTENTION_TERMINAL_IDEMPOTENT",
    seedAlias: "attention-already-answered",
  },
  "S4-validation-error": {
    stateDimensions: {
      requestKind: "question",
      responseState: "validation-error",
      connectionState: "connected",
      validityState: "current",
      selectionState: "custom-invalid",
    },
    allowedActions: ["EditAnswer", "Skip", "Dismiss"],
    disabledActions: ["Answer"],
    visibleCheck: "ATTENTION_VALIDATION_ERROR",
    semanticCheck: "ATTENTION_VALIDATION_ASSOCIATED",
    seedAlias: "attention-validation-error",
  },
});

const P2_7_STATIC_FUTURE_TERM_PATTERN = new RegExp(
  [
    "\\bkeep[- ]both\\b",
    "\\bresolved[- ]elsewhere\\b",
    "request\\s*id",
    "\\breceipts?\\b",
    "\\bfiles?\\b",
    "\\bfolders?\\b",
    "\\btrees?\\b",
    "\\bgrants?\\b",
    "\\b(?:file|folder|operation)\\s+counts?\\b",
    "\\bmoves?\\b",
    "\\bdeletes?\\b",
    "\\boverwrite\\b",
    "\\bduplicates?\\b",
    "\\bchange\\s+plan\\b",
    "\\bopen\\s+(?:folder|diff|latest\\s+plan)\\b",
    "device_files_[a-z0-9_]*",
    "\\bsaf\\b",
    "\\buris?\\b",
    "\\bpaths?\\b",
    "\\bdirector(?:y|ies)\\b",
    "\\b(?:content|file):\\/\\/",
    "/(?:storage|data|sdcard)(?:/|\\b)",
    "documentfile",
    "\\bmime\\b",
    "\\bapproval\\s+token\\b",
    "sideeffect\\s*[:=]\\s*true",
    "operationid",
    "\\battachments?\\b",
    "\\bphotos?\\b",
    "\\bpower\\s+mode\\b",
    "\\blocal\\s+shell\\b",
    "\\bp2[-_]8\\b",
  ].join("|"),
  "i",
);

function requireNoP2_7FutureTerms(value, label) {
  requireCondition(
    !P2_7_STATIC_FUTURE_TERM_PATTERN.test(JSON.stringify(value)),
    `${label} retains a stale or future P2_7 term`,
  );
}

function createP2_7StepProjection(profile, p2Fixtures, p2Captures) {
  const step = profile?.stepProfiles?.P2_7;
  requireCondition(step, "phaseProfiles.P2.stepProfiles.P2_7 is missing");
  requireCondition(
    JSON.stringify(step.projectionOrder) === JSON.stringify([
      "clone-P2-fixture",
      "apply-step-screen-action-overrides",
      "apply-step-fixture-action-overrides",
      "attach-closed-render-oracle",
      "attach-synthetic-seed",
    ]),
    "P2_7 projection order differs",
  );
  const sourceHash = createHash("sha256")
    .update(JSON.stringify(step.fixtureContracts ?? {}))
    .digest("hex");
  requireCondition(sourceHash === step.fixtureContractsSha256, "P2_7 fixture prose registry differs");

  const p2ById = new Map(p2Fixtures.map((fixture) => [fixture.id, fixture]));
  const expectedIds = p2Fixtures
    .filter((fixture) => fixture.screen === "S4")
    .map((fixture) => fixture.id)
    .sort();
  requireCondition(expectedIds.length === 11, "P2_7 S4 fixture count differs");
  requireCondition(expectedIds.length === step.expectedFixtureCount, "P2_7 fixture count differs");
  requireCondition(
    JSON.stringify(expectedIds) === JSON.stringify(Object.keys(step.fixtureApplicability ?? {}).sort()),
    "P2_7 fixture applicability does not exactly match S4",
  );
  requireCondition(
    JSON.stringify(expectedIds) === JSON.stringify(Object.keys(step.fixtureContracts ?? {}).sort()),
    "P2_7 fixture contracts do not exactly match S4",
  );

  const actionControls = step.actionControls ?? {};
  requireCondition(
    canonicalJson(actionControls) === canonicalJson(P2_7_ACTION_CONTROLS),
    "P2_7 action control registry differs from the closed oracle",
  );
  requireCondition(
    canonicalJson(step.screenOverrides ?? {}) === canonicalJson(P2_7_SCREEN_OVERRIDES),
    "P2_7 screen override registry differs from the closed oracle",
  );
  requireCondition(
    canonicalJson(step.fixtureActionOverrides ?? {}) === canonicalJson(P2_7_FIXTURE_ACTION_OVERRIDES),
    "P2_7 fixture action override registry differs from the closed oracle",
  );
  requireNoP2_7FutureTerms(actionControls, "P2_7 action/control registry");
  const controlsToActions = new Map();
  for (const [action, controls] of Object.entries(actionControls)) {
    requireCondition(Array.isArray(controls) && controls.length > 0, `P2_7 action ${action} has no control`);
    requireCondition(new Set(controls).size === controls.length, `P2_7 action ${action} repeats a control`);
    for (const control of controls) {
      requireCondition(/^[A-Z][A-Z0-9_]+$/.test(control), `P2_7 control differs: ${control}`);
      requireCondition(!controlsToActions.has(control), `P2_7 control ${control} has duplicate binding`);
      controlsToActions.set(control, action);
    }
  }
  const stateDimensionKeys = [
    "connectionState",
    "requestKind",
    "responseState",
    "selectionState",
    "validityState",
  ];
  const stateValueRegistry = {
    requestKind: new Set(["question", "confirmation"]),
    responseState: new Set([
      "pending",
      "responding",
      "resolved",
      "rejected",
      "skipped",
      "expired",
      "cancelled",
      "already-answered",
      "validation-error",
    ]),
    connectionState: new Set(["connected", "offline"]),
    validityState: new Set(["current", "expired", "cancelled", "unknown", "host-terminal-observed"]),
    selectionState: new Set(["none", "option-0", "custom-invalid"]),
  };
  const projected = expectedIds.map((fixtureId) => {
    const fixture = clone(p2ById.get(fixtureId));
    const applicability = step.fixtureApplicability[fixtureId];
    const contract = step.fixtureContracts[fixtureId];
    const closedOracle = P2_7_CLOSED_ORACLE[fixtureId];
    requireCondition(closedOracle, `${fixtureId} is missing from the P2_7 closed oracle`);
    requireCondition(applicability.screen === "S4" && fixture.screen === "S4", `${fixtureId} P2_7 screen differs`);
    requireCondition(/^ATTENTION_[A-Z0-9_]+$/.test(contract.visibleCheck), `${fixtureId} visible check differs`);
    requireCondition(/^ATTENTION_[A-Z0-9_]+$/.test(contract.semanticCheck), `${fixtureId} semantic check differs`);
    requireCondition(typeof contract.seedAlias === "string" && /^[a-z0-9-]+$/.test(contract.seedAlias), `${fixtureId} seed differs`);
    requireCondition(
      JSON.stringify(Object.keys(contract.stateDimensions ?? {}).sort()) === JSON.stringify(stateDimensionKeys),
      `${fixtureId} state dimensions differ`,
    );
    for (const [key, allowed] of Object.entries(stateValueRegistry)) {
      requireCondition(allowed.has(contract.stateDimensions[key]), `${fixtureId} ${key} differs`);
    }
    requireCondition(
      canonicalJson(contract.stateDimensions) === canonicalJson(closedOracle.stateDimensions),
      `${fixtureId} state tuple differs from the closed oracle`,
    );
    requireCondition(contract.visibleCheck === closedOracle.visibleCheck, `${fixtureId} visible check differs from the closed oracle`);
    requireCondition(contract.semanticCheck === closedOracle.semanticCheck, `${fixtureId} semantic check differs from the closed oracle`);
    requireCondition(contract.seedAlias === closedOracle.seedAlias, `${fixtureId} seed differs from the closed oracle`);
    requireCondition(
      contract.disabledReasons &&
        typeof contract.disabledReasons === "object" &&
        !Array.isArray(contract.disabledReasons),
      `${fixtureId} disabled reasons differ`,
    );
    for (const [action, reason] of Object.entries(contract.disabledReasons)) {
      requireCondition(actionControls[action], `${fixtureId} has unsupported disabled P2_7 action ${action}`);
      requireCondition(typeof reason === "string" && reason.trim() === reason && reason.length > 0, `${fixtureId} disabled reason differs`);
    }
    requireCondition(
      canonicalJson(Object.keys(contract.disabledReasons).sort()) === canonicalJson(closedOracle.disabledActions.slice().sort()),
      `${fixtureId} disabled actions differ from the closed oracle`,
    );
    requireNoP2_7FutureTerms(contract, `${fixtureId} contract`);

    const removed = new Set(step.screenOverrides?.S4?.removeAllowedActions ?? []);
    fixture.allowedActions = (fixture.allowedActions ?? []).filter((action) => !removed.has(action));
    const override = step.fixtureActionOverrides?.[fixtureId] ?? {};
    const fixtureRemovals = new Set(override.removeAllowedActions ?? []);
    fixture.allowedActions = fixture.allowedActions.filter((action) => !fixtureRemovals.has(action));
    fixture.allowedActions.push(...clone(override.addAllowedActions ?? []));
    requireCondition(fixture.allowedActions.length === new Set(fixture.allowedActions).size, `${fixtureId} repeats P2_7 actions`);
    requireCondition(
      canonicalJson(fixture.allowedActions) === canonicalJson(closedOracle.allowedActions),
      `${fixtureId} enabled actions differ from the closed oracle`,
    );

    const actionBindings = {};
    for (const action of fixture.allowedActions) {
      const controls = actionControls[action];
      requireCondition(controls, `${fixtureId} has unsupported P2_7 action ${action}`);
      for (const control of controls) {
        requireCondition(!actionBindings[control], `${fixtureId} ambiguously binds ${control}`);
        actionBindings[control] = action;
      }
    }
    const disabledBindings = {};
    for (const action of Object.keys(contract.disabledReasons)) {
      for (const control of actionControls[action]) {
        requireCondition(!disabledBindings[control], `${fixtureId} ambiguously disables ${control}`);
        disabledBindings[control] = action;
      }
    }
    for (const control of Object.keys(actionBindings)) {
      requireCondition(!disabledBindings[control], `${fixtureId} enables and disables ${control}`);
    }
    fixture.stateDimensions = clone(contract.stateDimensions);
    fixture.disabledReasons = clone(contract.disabledReasons);
    fixture.visibleData = contract.visibleData;
    fixture.expectedSemantics = contract.expectedSemantics;
    fixture.step = "P2_7";
    fixture.renderOracle = {
      visibleCheck: contract.visibleCheck,
      semanticCheck: contract.semanticCheck,
      actionBindings,
      disabledBindings,
    };
    fixture.seed = { profileAlias: "p2-single-host", attentionAlias: contract.seedAlias };
    requireNoP2_7FutureTerms(fixture, `${fixtureId} projected fixture`);
    return fixture;
  });

  const usedActions = new Set(projected.flatMap((fixture) => fixture.allowedActions));
  requireCondition(
    JSON.stringify([...usedActions].sort()) === JSON.stringify(Object.keys(actionControls).sort()),
    "P2_7 action control registry does not exactly match projected actions",
  );
  const expired = projected.find((fixture) => fixture.id === "S4-expired");
  requireCondition(
    expired.stateDimensions.responseState === "expired" &&
      expired.stateDimensions.validityState === "expired",
    "P2_7 expired terminal state differs",
  );
  const answered = projected.find((fixture) => fixture.id === "S4-already-answered");
  requireCondition(
    answered.stateDimensions.validityState === "host-terminal-observed" &&
      answered.stateDimensions.selectionState === "none" &&
      answered.disabledReasons.Answer === "This request was already handled." &&
      /does not claim a locally stored answer or authoritative Pi delivery proof/.test(answered.expectedSemantics),
    "P2_7 already-answered call binding differs",
  );
  const cancelled = projected.find((fixture) => fixture.id === "S4-cancelled");
  requireCondition(
    cancelled.stateDimensions.responseState === "cancelled" &&
      cancelled.stateDimensions.validityState === "cancelled" &&
      JSON.stringify(cancelled.allowedActions) === JSON.stringify(["ReturnToTask"]),
    "P2_7 cancelled terminal state differs",
  );
  const captureById = new Map(p2Captures.map((capture) => [capture.captureId, capture]));
  const captureIds = step.captureIds ?? [];
  requireCondition(captureIds.length === 5 && new Set(captureIds).size === 5, "P2_7 capture candidate count differs");
  const captureCandidates = captureIds.map((captureId) => {
    const capture = clone(captureById.get(captureId));
    requireCondition(capture, `P2_7 capture is missing: ${captureId}`);
    requireCondition(expectedIds.includes(capture.fixtureId), `P2_7 capture fixture differs: ${captureId}`);
    capture.status = "CAPTURED_CANDIDATES_NOT_APPROVED";
    return capture;
  });
  return {
    schemaVersion: 1,
    step: "P2_7",
    projectionOrder: clone(step.projectionOrder),
    fixtureCount: projected.length,
    fixtureCounts: { S4: 11 },
    fixtures: projected,
    captureCandidates,
  };
}

export function projectP2Fixtures(source) {
  const profile = source?.phaseProfiles?.P2;
  requireCondition(profile, "phaseProfiles.P2 is missing");
  requireCondition(
    JSON.stringify(profile.projectionOrder) === JSON.stringify([
      "clone-base-fixture",
      "apply-screen-state-action-overrides",
      "apply-fixture-overrides",
      "append-screen-visible-data",
    ]),
    "P2 projection order differs",
  );

  const base = new Map();
  for (const [screenId, screen] of Object.entries(source.screens ?? {})) {
    for (const fixture of screen.fixtures ?? []) {
      requireCondition(!base.has(fixture.id), `duplicate base fixture ${fixture.id}`);
      requireCondition(fixture.screen === screenId, `base screen differs for ${fixture.id}`);
      base.set(fixture.id, fixture);
    }
  }

  const applicability = profile.fixtureApplicability ?? {};
  const targetIds = [...base.values()]
    .filter((fixture) => TARGET_SCREENS.has(fixture.screen))
    .map((fixture) => fixture.id)
    .sort();
  const profileIds = Object.keys(applicability).sort();
  requireCondition(
    JSON.stringify(targetIds) === JSON.stringify(profileIds),
    "P2 fixture applicability does not exactly match S1/S2/S3/S4/S8",
  );
  requireCondition(profileIds.length === profile.expectedFixtureCount, "P2 fixture count differs");

  const dispositions = new Map();
  const projected = profileIds.map((fixtureId) => {
    const applicabilityEntry = applicability[fixtureId];
    const fixture = clone(base.get(fixtureId));
    requireCondition(applicabilityEntry.screen === fixture.screen, `profile screen differs for ${fixtureId}`);
    dispositions.set(
      applicabilityEntry.disposition,
      (dispositions.get(applicabilityEntry.disposition) ?? 0) + 1,
    );

    const screenOverride = profile.screenOverrides?.[fixture.screen] ?? {};
    const removed = new Set(screenOverride.removeAllowedActions ?? []);
    fixture.allowedActions = (fixture.allowedActions ?? []).filter((action) => !removed.has(action));
    fixture.stateDimensions = {
      ...(fixture.stateDimensions ?? {}),
      ...(clone(screenOverride.stateDimensionOverrides ?? {})),
    };
    fixture.disabledReasons = {
      ...(fixture.disabledReasons ?? {}),
      ...(clone(screenOverride.mergeDisabledReasons ?? {})),
    };

    applyOverride(fixture, profile.fixtureOverrides?.[fixtureId]);
    if (screenOverride.appendVisibleData) {
      fixture.visibleData = `${fixture.visibleData} ${screenOverride.appendVisibleData}`.trim();
    }
    fixture.phase = "P2";
    fixture.disposition = applicabilityEntry.disposition;
    fixture.unavailableSurfaces = clone(applicabilityEntry.unavailableSurfaces ?? []);
    return fixture;
  });

  for (const [name, expected] of Object.entries(profile.dispositionCounts ?? {})) {
    requireCondition((dispositions.get(name) ?? 0) === expected, `P2 ${name} disposition count differs`);
  }

  for (const fixture of projected) {
    const actions = new Set(fixture.allowedActions ?? []);
    if (fixture.screen === "S1") requireCondition(!actions.has("OpenFiles"), `${fixture.id} exposes OpenFiles`);
    if (fixture.screen === "S2") {
      requireCondition(!actions.has("SelectGrant") && !actions.has("AddAttachment"), `${fixture.id} exposes a P2 file action`);
      requireCondition(fixture.stateDimensions.attachmentEntryState === "unavailable", `${fixture.id} attachment is available`);
    }
    if (fixture.screen === "S8") {
      requireCondition(!actions.has("OpenAuthorizedFolders"), `${fixture.id} exposes authorized folders`);
      requireCondition(fixture.stateDimensions.permissionProbeState === "unavailable", `${fixture.id} probes mobile files`);
      requireCondition(!/one authorized folder|existing folder grant/i.test(fixture.visibleData), `${fixture.id} claims a folder grant`);
      fixture.renderOracle = createS8RenderOracle(fixture);
    }
  }

  const captures = clone(profile.goldenCaptures ?? []);
  requireCondition(captures.length === 24, "P2 golden capture count differs");
  const captureIds = new Set();
  const projectedById = new Map(projected.map((fixture) => [fixture.id, fixture]));
  for (const capture of captures) {
    requireCondition(!captureIds.has(capture.captureId), `duplicate capture ${capture.captureId}`);
    captureIds.add(capture.captureId);
    requireCondition(projectedById.has(capture.fixtureId), `capture fixture is missing: ${capture.fixtureId}`);
    requireCondition(["360x800", "412x915"].includes(capture.viewport), `capture viewport differs: ${capture.captureId}`);
    requireCondition(["light", "dark"].includes(capture.theme), `capture theme differs: ${capture.captureId}`);
    requireCondition([1, 2].includes(capture.fontScale), `capture fontScale differs: ${capture.captureId}`);
  }

  return {
    schemaVersion: 1,
    phase: "P2",
    sourceSchemaVersion: source.schemaVersion,
    projectionOrder: clone(profile.projectionOrder),
    fixtureCounts: {
      total: projected.length,
      active: dispositions.get("active") ?? 0,
      unavailablePlaceholder: dispositions.get("unavailable-placeholder") ?? 0,
      laterPhaseOnly: dispositions.get("later-phase-only") ?? 0,
    },
    fixtures: projected,
    goldenCaptures: captures,
    stepProfiles: {
      P2_5: createP2_5StepProjection(profile, projected, captures),
      P2_6: createP2_6StepProjection(profile, projected, captures),
      P2_7: createP2_7StepProjection(profile, projected, captures),
    },
  };
}
