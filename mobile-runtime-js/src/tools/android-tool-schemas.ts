import type { AgentTool } from "@earendil-works/pi-agent-core";

type ToolParameters = AgentTool["parameters"];

export interface AndroidToolSchemas {
  projectCommand: (defaultTimeoutMillis: number) => ToolParameters;
  attachmentRead: ToolParameters;
  skillResource: ToolParameters;
  imageGeneration: ToolParameters;
  capabilities: ToolParameters;
  capabilityRequest: ToolParameters;
  filesList: ToolParameters;
  filesRead: ToolParameters;
  filesPrepare: ToolParameters;
  filesCommit: ToolParameters;
  mediaList: ToolParameters;
  media: ToolParameters;
  calendar: ToolParameters;
  contacts: ToolParameters;
  location: ToolParameters;
  clipboard: ToolParameters;
  notification: ToolParameters;
  screenCapture: ToolParameters;
  uiInspect: ToolParameters;
  uiAction: ToolParameters;
  packagesList: ToolParameters;
  packageInspect: ToolParameters;
  question: ToolParameters;
  confirmation: ToolParameters;
}

export function createAndroidFixtureSchema(): ToolParameters {
  return {
    type: "object",
    properties: {
      text: { type: "string", minLength: 1, maxLength: 128 },
    },
    required: ["text"],
    additionalProperties: false,
  } as ToolParameters;
}

export function createAndroidToolSchemas(): AndroidToolSchemas {
  return {
    projectCommand: (defaultTimeoutMillis) => ({
      type: "object",
      properties: {
        command: { type: "string", minLength: 1, maxLength: 8192 },
        timeoutMillis: {
          type: "integer",
          minimum: 1,
          maximum: 900_000,
          default: defaultTimeoutMillis,
        },
        outputLimitBytes: {
          type: "integer",
          minimum: 1,
          maximum: 1_048_576,
          default: 32_768,
        },
      },
      required: ["command"],
      additionalProperties: false,
    } as ToolParameters),
    attachmentRead: {
      type: "object",
      properties: {
        attachmentId: {
          type: "string",
          pattern:
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        },
        offset: { type: "integer", minimum: 0, default: 0 },
        limit: { type: "integer", minimum: 256, maximum: 65_536, default: 16_384 },
      },
      required: ["attachmentId", "offset", "limit"],
      additionalProperties: false,
    } as ToolParameters,
    skillResource: {
      type: "object",
      oneOf: [
        {
          type: "object",
          properties: {
            action: { type: "string", const: "list" },
            skillName: {
              type: "string",
              minLength: 1,
              maxLength: 64,
              pattern: "^[a-z0-9]+(?:-[a-z0-9]+)*$",
            },
            prefix: { type: "string", minLength: 1, maxLength: 600 },
            offset: { type: "integer", minimum: 0, default: 0 },
            limit: { type: "integer", minimum: 1, maximum: 64, default: 64 },
          },
          required: ["action", "skillName", "offset", "limit"],
          additionalProperties: false,
        },
        {
          type: "object",
          properties: {
            action: { type: "string", const: "read" },
            skillName: {
              type: "string",
              minLength: 1,
              maxLength: 64,
              pattern: "^[a-z0-9]+(?:-[a-z0-9]+)*$",
            },
            path: { type: "string", minLength: 1, maxLength: 600 },
            offset: { type: "integer", minimum: 0, default: 0 },
            limit: { type: "integer", minimum: 256, maximum: 65_536, default: 16_384 },
          },
          required: ["action", "skillName", "path", "offset", "limit"],
          additionalProperties: false,
        },
      ],
    } as ToolParameters,
    imageGeneration: {
      type: "object",
      properties: {
        prompt: { type: "string", minLength: 1, maxLength: 4096 },
        aspect_ratio: {
          type: "string",
          enum: ["1:1", "16:9", "9:16", "4:3", "3:4"],
        },
        quality: {
          type: "string",
          enum: ["auto", "low", "medium", "high"],
        },
      },
      required: ["prompt"],
      additionalProperties: false,
    } as ToolParameters,
    capabilities: {
      type: "object",
      properties: {},
      additionalProperties: false,
    } as ToolParameters,
    capabilityRequest: capabilityRequestParameters() as ToolParameters,
    filesList: {
      type: "object",
      properties: {
        grantId: { type: "string" },
        parentAlias: { type: "string", pattern: "^doc-[0-9a-f]{24}$" },
        recursive: { type: "boolean" },
      },
      required: ["grantId"],
      additionalProperties: false,
    } as ToolParameters,
    filesRead: {
      type: "object",
      properties: {
        grantId: { type: "string" },
        purpose: { type: "string", minLength: 1, maxLength: 1024 },
        documents: {
          type: "array",
          minItems: 1,
          maxItems: 16,
          items: {
            type: "object",
            properties: {
              alias: { type: "string", pattern: "^doc-[0-9a-f]{24}$" },
              expectedMimeType: { type: "string", minLength: 1, maxLength: 128 },
              maxBytes: { type: "integer", minimum: 1, maximum: 262144 },
            },
            required: ["alias", "expectedMimeType", "maxBytes"],
            additionalProperties: false,
          },
        },
        totalMaxBytes: { type: "integer", minimum: 1, maximum: 524288 },
      },
      required: ["grantId", "purpose", "documents", "totalMaxBytes"],
      additionalProperties: false,
    } as ToolParameters,
    filesPrepare: filePrepareParameters() as ToolParameters,
    filesCommit: {
      type: "object",
      properties: {
        preparedId: {
          type: "string",
          pattern:
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        },
        planDigest: { type: "string", pattern: "^[0-9a-f]{64}$" },
      },
      required: ["preparedId", "planDigest"],
      additionalProperties: false,
    } as ToolParameters,
    mediaList: {
      type: "object",
      properties: {
        purpose: { type: "string", minLength: 1, maxLength: 512 },
        limit: { type: "integer", minimum: 1, maximum: 20 },
      },
      required: ["purpose"],
      additionalProperties: false,
    } as ToolParameters,
    media: mediaToolParameters() as ToolParameters,
    calendar: calendarToolParameters() as ToolParameters,
    contacts: contactsToolParameters() as ToolParameters,
    location: {
      type: "object",
      properties: {
        action: { type: "string", const: "get_current" },
        precision: {
          type: "string",
          enum: ["approximate", "precise"],
        },
        purpose: { type: "string", minLength: 1, maxLength: 160 },
      },
      required: ["action", "precision", "purpose"],
      additionalProperties: false,
    } as ToolParameters,
    clipboard: clipboardToolParameters() as ToolParameters,
    notification: notificationToolParameters() as ToolParameters,
    screenCapture: {
      type: "object",
      properties: {
        purpose: { type: "string", minLength: 1, maxLength: 512 },
        targetPackage: {
          anyOf: [
            { type: "string", minLength: 1, maxLength: 255 },
            { type: "null" },
          ],
        },
      },
      required: ["purpose"],
      additionalProperties: false,
    } as ToolParameters,
    uiInspect: {
      type: "object",
      properties: {
        targetPackage: {
          anyOf: [
            { type: "string", minLength: 1, maxLength: 255 },
            { type: "null" },
          ],
        },
        maxNodes: { type: "integer", minimum: 1, maximum: 250, default: 250 },
      },
      additionalProperties: false,
    } as ToolParameters,
    uiAction: {
      type: "object",
      properties: {
        snapshotId: { type: "string", pattern: "^ui-[0-9a-f]{32}$" },
        nodeHandle: { type: "string", minLength: 38, maxLength: 320 },
        action: { type: "string", enum: ["click", "scroll", "input_draft", "back"] },
        text: { type: "string", minLength: 1, maxLength: 4096 },
        direction: { type: "string", enum: ["up", "down", "left", "right"] },
      },
      required: ["snapshotId", "action"],
      additionalProperties: false,
    } as ToolParameters,
    packagesList: {
      type: "object",
      properties: {
        purpose: { type: "string", minLength: 1, maxLength: 512 },
        includeSystem: { type: "boolean", default: false },
        offset: { type: "integer", minimum: 0, maximum: 10_000, default: 0 },
        limit: { type: "integer", minimum: 1, maximum: 100, default: 50 },
      },
      required: ["purpose"],
      additionalProperties: false,
    } as ToolParameters,
    packageInspect: {
      type: "object",
      properties: {
        purpose: { type: "string", minLength: 1, maxLength: 512 },
        packageName: {
          type: "string",
          minLength: 3,
          maxLength: 255,
          pattern: "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$",
        },
      },
      required: ["purpose", "packageName"],
      additionalProperties: false,
    } as ToolParameters,
    question: {
      type: "object",
      properties: {
        question: { type: "string", minLength: 1, maxLength: 4096 },
        options: {
          type: "array",
          minItems: 1,
          maxItems: 10,
          items: {
            type: "object",
            properties: {
              label: { type: "string", minLength: 1, maxLength: 256 },
              description: { type: "string", minLength: 1, maxLength: 1024 },
              recommended: { type: "boolean" },
            },
            required: ["label"],
            additionalProperties: false,
          },
        },
      },
      required: ["question"],
      additionalProperties: false,
    } as ToolParameters,
    confirmation: {
      type: "object",
      properties: {
        summary: { type: "string", minLength: 1, maxLength: 4096 },
        details: { type: "string", minLength: 1, maxLength: 8192 },
      },
      required: ["summary"],
      additionalProperties: false,
    } as ToolParameters,
  };
}

function capabilityRequestParameters(): Record<string, unknown> {
  return {
    type: "object",
    oneOf: [
      {
        type: "object",
        properties: {
          capability: {
            type: "string",
            enum: [
              "saf_folders",
              "photo_library",
              "accessibility_control",
              "screen_capture",
              "all_files",
              "shizuku_shell_uid",
              "notifications",
            ],
          },
          purpose: { type: "string", minLength: 1, maxLength: 512 },
        },
        required: ["capability", "purpose"],
        additionalProperties: false,
      },
      capabilityAccessBranch("calendar", ["read", "write"]),
      capabilityAccessBranch("contacts", ["read", "write"]),
      capabilityAccessBranch("location", ["approximate", "precise"]),
    ],
  };
}

function capabilityAccessBranch(
  capability: string,
  access: string[],
): Record<string, unknown> {
  return {
    type: "object",
    properties: {
      capability: { type: "string", const: capability },
      requiredAccess: { type: "string", enum: access },
      purpose: { type: "string", minLength: 1, maxLength: 512 },
    },
    required: ["capability", "requiredAccess", "purpose"],
    additionalProperties: false,
  };
}

function calendarToolParameters(): Record<string, unknown> {
  const purpose = { type: "string", minLength: 1, maxLength: 160 };
  const calendarHandle = {
    type: "string",
    pattern: "^calendar-[0-9a-f]{24}$",
  };
  const eventHandle = {
    type: "string",
    pattern: "^event-[0-9a-f]{24}$",
  };
  const nullable = (schema: Record<string, unknown>) => ({
    anyOf: [schema, { type: "null" }],
  });
  const timeZone = { type: "string", minLength: 1, maxLength: 64 };
  const rfc3339DateTime = {
    type: "string",
    minLength: 20,
    maxLength: 35,
    pattern:
      "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$",
  };
  const timedSchedule = {
    type: "object",
    properties: {
      kind: { const: "timed" },
      start: rfc3339DateTime,
      end: rfc3339DateTime,
      timeZone,
    },
    required: ["kind", "start", "end", "timeZone"],
    additionalProperties: false,
  };
  const allDaySchedule = {
    type: "object",
    properties: {
      kind: { const: "all_day" },
      startDate: {
        type: "string",
        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$",
      },
      endDateExclusive: {
        type: "string",
        pattern: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$",
      },
      timeZone,
    },
    required: ["kind", "startDate", "endDateExclusive", "timeZone"],
    additionalProperties: false,
  };
  const schedule = {
    oneOf: [timedSchedule, allDaySchedule],
  };
  const title = { type: "string", minLength: 1, maxLength: 200 };
  const location = nullable({ type: "string", minLength: 1, maxLength: 256 });
  const description = nullable({ type: "string", minLength: 1, maxLength: 1024 });
  const branch = (
    action: string,
    properties: Record<string, unknown>,
    required: string[],
  ) => ({
    type: "object",
    properties: {
      action: { const: action },
      purpose,
      ...properties,
    },
    required: ["action", "purpose", ...required],
    additionalProperties: false,
  });
  return {
    type: "object",
    oneOf: [
      branch("list_calendars", {}, []),
      branch("list_events", {
        start: rfc3339DateTime,
        end: rfc3339DateTime,
        calendarHandle: nullable(calendarHandle),
        query: nullable({ type: "string", minLength: 1, maxLength: 120 }),
        cursor: nullable({
          type: "string",
          minLength: 8,
          maxLength: 160,
          pattern: "^calendar-page-[A-Za-z0-9_-]+$",
        }),
      }, ["start", "end", "calendarHandle", "query", "cursor"]),
      branch("get_event", { eventHandle }, ["eventHandle"]),
      branch("create_event", {
        title,
        schedule,
        location,
        description,
        calendarHandle: nullable(calendarHandle),
      }, ["title", "schedule", "location", "description", "calendarHandle"]),
      branch("update_event", {
        eventHandle,
        changes: {
          type: "object",
          minProperties: 1,
          properties: {
            title,
            schedule,
            location,
            description,
          },
          additionalProperties: false,
        },
      }, ["eventHandle", "changes"]),
      branch("delete_event", { eventHandle }, ["eventHandle"]),
    ],
  };
}

function contactsToolParameters(): Record<string, unknown> {
  const purpose = { type: "string", minLength: 1, maxLength: 160 };
  const contactHandle = {
    type: "string",
    pattern: "^contact-[0-9a-f]{24}$",
  };
  const branch = (
    action: string,
    properties: Record<string, unknown>,
    required: string[],
  ) => ({
    type: "object",
    properties: {
      action: { const: action },
      purpose,
      ...properties,
    },
    required: ["action", "purpose", ...required],
    additionalProperties: false,
  });
  const contactValue = (maximum: number) => ({
    type: "object",
    properties: {
      value: { type: "string", minLength: 1, maxLength: maximum },
      label: { type: "string", minLength: 1, maxLength: 64 },
      primary: { type: "boolean" },
    },
    required: ["value", "label", "primary"],
    additionalProperties: false,
  });
  const phones = {
    type: "array",
    maxItems: 10,
    items: contactValue(128),
  };
  const emails = {
    type: "array",
    maxItems: 10,
    items: contactValue(320),
  };
  const company = { type: "string", minLength: 1, maxLength: 256 };
  const title = { type: "string", minLength: 1, maxLength: 160 };
  const nullable = (value: Record<string, unknown>) => ({
    anyOf: [value, { type: "null" }],
  });
  const organizationVariant = (
    companySchema: Record<string, unknown>,
    titleSchema: Record<string, unknown>,
  ) => ({
    type: "object",
    properties: {
      company: companySchema,
      title: titleSchema,
    },
    required: ["company", "title"],
    additionalProperties: false,
  });
  const organization = {
    anyOf: [
      organizationVariant(company, nullable(title)),
      organizationVariant(nullable(company), title),
      { type: "null" },
    ],
  };
  return {
    type: "object",
    oneOf: [
      branch("search", {
        query: { type: "string", minLength: 1, maxLength: 120 },
        cursor: {
          anyOf: [
            {
              type: "string",
              minLength: 8,
              maxLength: 160,
              pattern: "^contacts-page-[A-Za-z0-9_-]+$",
            },
            { type: "null" },
          ],
        },
      }, ["query", "cursor"]),
      branch("get_contact", { contactHandle }, ["contactHandle"]),
      branch("create_contact", {
        displayName: { type: "string", minLength: 1, maxLength: 200 },
        phones,
        emails,
        organization,
      }, ["displayName", "phones", "emails", "organization"]),
      branch("update_contact", {
        contactHandle,
        changes: {
          type: "object",
          minProperties: 1,
          properties: {
            displayName: { type: "string", minLength: 1, maxLength: 200 },
            phones,
            emails,
            organization,
          },
          additionalProperties: false,
        },
      }, ["contactHandle", "changes"]),
      branch("delete_contact", { contactHandle }, ["contactHandle"]),
    ],
  };
}

function clipboardToolParameters(): Record<string, unknown> {
  const purpose = { type: "string", minLength: 1, maxLength: 160 };
  const branch = (
    action: "get" | "set" | "clear",
    properties: Record<string, unknown>,
    required: string[],
  ) => ({
    type: "object",
    properties: {
      action: { const: action },
      purpose,
      ...properties,
    },
    required: ["action", "purpose", ...required],
    additionalProperties: false,
  });
  return {
    type: "object",
    oneOf: [
      branch("get", {}, []),
      branch(
        "set",
        { text: { type: "string", minLength: 1, maxLength: 4096 } },
        ["text"],
      ),
      branch("clear", {}, []),
    ],
  };
}

function notificationToolParameters(): Record<string, unknown> {
  const notificationHandle = {
    type: "string",
    pattern: "^notification-[0-9a-f]{32}$",
  };
  const title = { type: "string", minLength: 1, maxLength: 80 };
  const message = { type: "string", minLength: 1, maxLength: 240 };
  const branch = (
    action: "status" | "post" | "list_active" | "update" | "cancel" | "open_settings",
    properties: Record<string, unknown>,
    required: string[],
  ) => ({
    type: "object",
    properties: {
      action: { const: action },
      ...properties,
    },
    required: ["action", ...required],
    additionalProperties: false,
  });
  return {
    type: "object",
    oneOf: [
      branch("status", {}, []),
      branch("post", { title, message }, ["title", "message"]),
      branch(
        "list_active",
        { limit: { type: "integer", minimum: 1, maximum: 20, default: 10 } },
        [],
      ),
      branch(
        "update",
        { notificationHandle, title, message },
        ["notificationHandle", "title", "message"],
      ),
      branch("cancel", { notificationHandle }, ["notificationHandle"]),
      branch("open_settings", {}, []),
    ],
  };
}

function mediaToolParameters(): Record<string, unknown> {
  const mediaHandle = {
    type: "string",
    pattern: "^media-[0-9a-f]{24}$",
  };
  const branch = (
    action: "set_favorite" | "set_trashed" | "delete",
    properties: Record<string, unknown>,
    required: string[],
  ) => ({
    type: "object",
    properties: {
      action: { const: action },
      mediaHandle,
      ...properties,
    },
    required: ["action", "mediaHandle", ...required],
    additionalProperties: false,
  });
  return {
    type: "object",
    oneOf: [
      branch(
        "set_favorite",
        { favorite: { type: "boolean" } },
        ["favorite"],
      ),
      branch(
        "set_trashed",
        { trashed: { type: "boolean" } },
        ["trashed"],
      ),
      branch("delete", {}, []),
    ],
  };
}

function filePrepareParameters(): Record<string, unknown> {
  const precondition = {
    type: "object",
    properties: {
      displayName: { type: "string", minLength: 1, maxLength: 240 },
      mimeType: { type: "string", minLength: 1, maxLength: 128 },
      byteCount: {
        anyOf: [{ type: "integer", minimum: 0 }, { type: "null" }],
      },
      lastModifiedMillis: {
        anyOf: [{ type: "integer", minimum: 0 }, { type: "null" }],
      },
    },
    required: ["displayName", "mimeType", "byteCount", "lastModifiedMillis"],
    additionalProperties: false,
  };
  const operationId = {
    type: "string",
    pattern:
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
  };
  const alias = { type: "string", pattern: "^doc-[0-9a-f]{24}$" };
  return {
    type: "object",
    properties: {
      grantId: { type: "string" },
      purpose: { type: "string", minLength: 1, maxLength: 1024 },
      operations: {
        type: "array",
        minItems: 1,
        maxItems: 16,
        items: {
          anyOf: [
            {
              type: "object",
              properties: {
                operationId,
                kind: { const: "create_file" },
                parentAlias: alias,
                displayName: { type: "string", minLength: 1, maxLength: 240 },
                mimeType: { type: "string", minLength: 1, maxLength: 128 },
                content: { type: "string", maxLength: 262144 },
              },
              required: [
                "operationId",
                "kind",
                "parentAlias",
                "displayName",
                "mimeType",
                "content",
              ],
              additionalProperties: false,
            },
            {
              type: "object",
              properties: {
                operationId,
                kind: { const: "create_directory" },
                parentAlias: alias,
                displayName: { type: "string", minLength: 1, maxLength: 240 },
              },
              required: ["operationId", "kind", "parentAlias", "displayName"],
              additionalProperties: false,
            },
            {
              type: "object",
              properties: {
                operationId,
                kind: { const: "rename" },
                sourceAlias: alias,
                displayName: { type: "string", minLength: 1, maxLength: 240 },
                expected: precondition,
              },
              required: ["operationId", "kind", "sourceAlias", "displayName", "expected"],
              additionalProperties: false,
            },
            {
              type: "object",
              properties: {
                operationId,
                kind: { const: "move" },
                sourceAlias: alias,
                targetParentAlias: alias,
                expected: precondition,
              },
              required: [
                "operationId",
                "kind",
                "sourceAlias",
                "targetParentAlias",
                "expected",
              ],
              additionalProperties: false,
            },
            {
              type: "object",
              properties: {
                operationId,
                kind: { const: "write_file" },
                sourceAlias: alias,
                mimeType: { type: "string", minLength: 1, maxLength: 128 },
                content: { type: "string", maxLength: 262144 },
                expected: precondition,
              },
              required: [
                "operationId",
                "kind",
                "sourceAlias",
                "mimeType",
                "content",
                "expected",
              ],
              additionalProperties: false,
            },
            {
              type: "object",
              properties: {
                operationId,
                kind: { const: "delete_file" },
                sourceAlias: alias,
                expected: precondition,
              },
              required: ["operationId", "kind", "sourceAlias", "expected"],
              additionalProperties: false,
            },
          ],
        },
      },
    },
    required: ["grantId", "purpose", "operations"],
    additionalProperties: false,
  };
}
