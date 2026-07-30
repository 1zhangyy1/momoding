import type {
  Api,
  AssistantMessageEventStream,
  Context,
  Model,
  SimpleStreamOptions,
  Tool,
  ToolCall,
} from "@earendil-works/pi-ai";

export { EventStream } from "@earendil-works/pi-ai";

/**
 * pi-agent-core 0.80.6 retains a fallback import from pi-ai/compat, whose aggregate entrypoint
 * eagerly bundles every desktop provider SDK. AgentHarness always supplies its Models stream
 * function, so the fallback must never run in the phone-local composition.
 */
export function streamSimple<TApi extends Api>(
  _model: Model<TApi>,
  _context: Context,
  _options?: SimpleStreamOptions,
): AssistantMessageEventStream {
  throw new Error("PI_MOBILE_MODELS_STREAM_REQUIRED");
}

/**
 * Pi's current TypeBox compiler pulls in a BigInt-based hash implementation at module
 * initialization, while Zipline QuickJS is intentionally built without BigInt. This narrow
 * validator supports the JSON Schema vocabulary used by phone-local tools and fails closed when
 * a schema adds an unsupported keyword. It intentionally does not coerce model output.
 */
export function validateToolArguments(
  tool: Tool,
  toolCall: ToolCall,
): unknown {
  assertSupportedSchema(tool.parameters, "$schema");
  const args = structuredClone(toolCall.arguments);
  const error = validateValue(tool.parameters as JsonSchema, args, "$");
  if (error !== null) {
    throw invalidArgumentsError(tool.parameters as JsonSchema, toolCall.arguments);
  }
  return args;
}

type JsonSchema = Record<string, unknown>;

function invalidArgumentsError(
  schema: JsonSchema,
  argumentsValue: unknown,
): Error {
  const action = supportedAction(schema, argumentsValue);
  return new Error(JSON.stringify({
    ok: false,
    action,
    error: {
      code: "INVALID_ARGUMENTS",
      message: "Tool arguments do not match the schema.",
      retryable: true,
    },
  }));
}

function supportedAction(
  schema: JsonSchema,
  argumentsValue: unknown,
): string | null {
  if (!isRecord(argumentsValue) || typeof argumentsValue.action !== "string") return null;
  const candidate = argumentsValue.action;
  return schemaContainsAction(schema, candidate) ? candidate : null;
}

function schemaContainsAction(schema: JsonSchema, candidate: string): boolean {
  const properties = isRecord(schema.properties) ? schema.properties : null;
  const action = properties && isRecord(properties.action) ? properties.action : null;
  if (action?.const === candidate) return true;
  if (Array.isArray(action?.enum) && action.enum.includes(candidate)) return true;
  return ["oneOf", "anyOf"].some((key) =>
    Array.isArray(schema[key]) &&
    (schema[key] as unknown[]).some((child) =>
      isRecord(child) && schemaContainsAction(child, candidate)));
}

const COMMON_SCHEMA_KEYS = new Set([
  "$id",
  "$schema",
  "default",
  "description",
  "examples",
  "title",
  "type",
  "const",
  "enum",
  "anyOf",
  "oneOf",
]);

const KEYS_BY_TYPE: Record<string, ReadonlySet<string>> = {
  object: new Set(["properties", "required", "additionalProperties", "minProperties", "maxProperties"]),
  array: new Set(["items", "minItems", "maxItems"]),
  string: new Set(["minLength", "maxLength", "pattern"]),
  number: new Set(["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"]),
  integer: new Set(["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"]),
  boolean: new Set(),
  null: new Set(),
};

function assertSupportedSchema(schemaValue: unknown, path: string): asserts schemaValue is JsonSchema {
  if (!isRecord(schemaValue)) {
    throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path} must be an object`);
  }
  const types = schemaTypes(schemaValue);
  if (types.length === 0 && !("const" in schemaValue) && !("enum" in schemaValue) &&
      !("anyOf" in schemaValue) && !("oneOf" in schemaValue)) {
    throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path} has no supported type`);
  }
  for (const type of types) {
    if (!(type in KEYS_BY_TYPE)) {
      throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path} type=${type}`);
    }
  }
  const typeKeys = new Set(types.flatMap((type) => [...KEYS_BY_TYPE[type]]));
  for (const key of Object.keys(schemaValue)) {
    if (!COMMON_SCHEMA_KEYS.has(key) && !typeKeys.has(key)) {
      throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.${key}`);
    }
  }
  for (const unionKey of ["anyOf", "oneOf"] as const) {
    if (unionKey in schemaValue) {
      const alternatives = schemaValue[unionKey];
      if (!Array.isArray(alternatives) || alternatives.length === 0) {
        throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.${unionKey}`);
      }
      alternatives.forEach((candidate, index) =>
        assertSupportedSchema(candidate, `${path}.${unionKey}[${index}]`));
    }
  }
  if ("enum" in schemaValue && (!Array.isArray(schemaValue.enum) || schemaValue.enum.length === 0)) {
    throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.enum`);
  }
  if (types.includes("object")) {
    const properties = schemaValue.properties;
    if (properties !== undefined) {
      if (!isRecord(properties)) {
        throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.properties`);
      }
      Object.entries(properties).forEach(([key, child]) =>
        assertSupportedSchema(child, `${path}.properties.${key}`));
    }
    const required = schemaValue.required;
    if (required !== undefined &&
        (!Array.isArray(required) || required.some((key) => typeof key !== "string"))) {
      throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.required`);
    }
    const additional = schemaValue.additionalProperties;
    if (additional !== undefined && typeof additional !== "boolean") {
      assertSupportedSchema(additional, `${path}.additionalProperties`);
    }
  }
  if (types.includes("array") && schemaValue.items !== undefined) {
    if (Array.isArray(schemaValue.items)) {
      throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.items tuple`);
    }
    assertSupportedSchema(schemaValue.items, `${path}.items`);
  }
  if (types.includes("string") && schemaValue.pattern !== undefined) {
    if (typeof schemaValue.pattern !== "string") {
      throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.pattern`);
    }
    try {
      new RegExp(schemaValue.pattern);
    } catch {
      throw new Error(`PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA ${path}.pattern`);
    }
  }
}

function validateValue(schema: JsonSchema, value: unknown, path: string): string | null {
  if ("const" in schema && !jsonEqual(value, schema.const)) {
    return `${path}: must equal the schema constant`;
  }
  if (Array.isArray(schema.enum) && !schema.enum.some((candidate) => jsonEqual(value, candidate))) {
    return `${path}: must match one enum value`;
  }
  if (Array.isArray(schema.anyOf) &&
      !schema.anyOf.some((candidate) => validateValue(candidate as JsonSchema, value, path) === null)) {
    return `${path}: must match at least one anyOf schema`;
  }
  if (Array.isArray(schema.oneOf)) {
    const matches = schema.oneOf.filter(
      (candidate) => validateValue(candidate as JsonSchema, value, path) === null,
    ).length;
    if (matches !== 1) return `${path}: must match exactly one oneOf schema`;
  }

  const types = schemaTypes(schema);
  if (types.length > 0 && !types.some((type) => matchesType(value, type))) {
    return `${path}: expected ${types.join(" or ")}`;
  }
  if (types.includes("object") && isRecord(value)) {
    const keys = Object.keys(value);
    const min = schema.minProperties;
    const max = schema.maxProperties;
    if (typeof min === "number" && keys.length < min) return `${path}: too few properties`;
    if (typeof max === "number" && keys.length > max) return `${path}: too many properties`;
    const required = Array.isArray(schema.required) ? schema.required as string[] : [];
    const missing = required.find((key) => !Object.hasOwn(value, key));
    if (missing !== undefined) return `${path}.${missing}: is required`;
    const properties = isRecord(schema.properties) ? schema.properties : {};
    for (const [key, childValue] of Object.entries(value)) {
      const childSchema = properties[key];
      if (childSchema !== undefined) {
        const error = validateValue(childSchema as JsonSchema, childValue, `${path}.${key}`);
        if (error !== null) return error;
      } else if (schema.additionalProperties === false) {
        return `${path}.${key}: additional property is not allowed`;
      } else if (isRecord(schema.additionalProperties)) {
        const error = validateValue(
          schema.additionalProperties,
          childValue,
          `${path}.${key}`,
        );
        if (error !== null) return error;
      }
    }
  }
  if (types.includes("array") && Array.isArray(value)) {
    if (typeof schema.minItems === "number" && value.length < schema.minItems) {
      return `${path}: too few items`;
    }
    if (typeof schema.maxItems === "number" && value.length > schema.maxItems) {
      return `${path}: too many items`;
    }
    if (isRecord(schema.items)) {
      for (let index = 0; index < value.length; index += 1) {
        const error = validateValue(schema.items, value[index], `${path}[${index}]`);
        if (error !== null) return error;
      }
    }
  }
  if (types.includes("string") && typeof value === "string") {
    if (typeof schema.minLength === "number" && value.length < schema.minLength) {
      return `${path}: string is too short`;
    }
    if (typeof schema.maxLength === "number" && value.length > schema.maxLength) {
      return `${path}: string is too long`;
    }
    if (typeof schema.pattern === "string" && !new RegExp(schema.pattern).test(value)) {
      return `${path}: string does not match pattern`;
    }
  }
  if ((types.includes("number") || types.includes("integer")) && typeof value === "number") {
    if (typeof schema.minimum === "number" && value < schema.minimum) return `${path}: below minimum`;
    if (typeof schema.maximum === "number" && value > schema.maximum) return `${path}: above maximum`;
    if (typeof schema.exclusiveMinimum === "number" && value <= schema.exclusiveMinimum) {
      return `${path}: below exclusive minimum`;
    }
    if (typeof schema.exclusiveMaximum === "number" && value >= schema.exclusiveMaximum) {
      return `${path}: above exclusive maximum`;
    }
  }
  return null;
}

function schemaTypes(schema: JsonSchema): string[] {
  if (typeof schema.type === "string") return [schema.type];
  if (Array.isArray(schema.type) && schema.type.every((type) => typeof type === "string")) {
    return schema.type as string[];
  }
  return [];
}

function matchesType(value: unknown, type: string): boolean {
  switch (type) {
    case "object": return isRecord(value);
    case "array": return Array.isArray(value);
    case "string": return typeof value === "string";
    case "number": return typeof value === "number" && Number.isFinite(value);
    case "integer": return typeof value === "number" && Number.isInteger(value);
    case "boolean": return typeof value === "boolean";
    case "null": return value === null;
    default: return false;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function jsonEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}
