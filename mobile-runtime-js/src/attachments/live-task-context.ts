import {
  InMemorySessionStorage,
  type AgentToolResult,
  type Session,
  type SessionMetadata,
  type SessionTreeEntry,
} from "@earendil-works/pi-agent-core";
import type { ImageContent } from "@earendil-works/pi-ai";
import { sha256 } from "../sha256.js";
import {
  CLIPBOARD_TOOL_NAME,
  LOCATION_TOOL_NAME,
  SCREEN_CAPTURE_TOOL_NAME,
  type NativeToolRequest,
} from "../tools/android-tool-registry.js";

export interface PiRuntimeImageInput {
  attachmentId: string;
  mimeType: string;
  data: string;
}

export interface PiRuntimeTextAttachmentInput {
  attachmentId: string;
  displayName: string;
  mimeType: string;
  byteSize: number;
}

interface LiveToolImageDescriptor {
  contentSha256: string;
  width: number;
  height: number;
  mimeType: "image/png" | "image/jpeg";
}

type LiveToolTextDescriptor =
  | {
      dataClass: "location";
      contentSha256: string;
      precision: "approximate" | "precise";
    }
  | {
      dataClass: "clipboard";
      contentSha256: string;
    };

export interface LiveTaskContextState {
  imageAttachmentIdsByData: Map<string, string[]>;
  liveToolImagesByData: Map<string, LiveToolImageDescriptor>;
  consumedLiveToolImageData: Set<string>;
  liveToolTextsByText: Map<string, LiveToolTextDescriptor>;
  consumedLiveToolTexts: Set<string>;
}

export const TEXT_ATTACHMENT_CONTROL_ENTRY_TYPE = "pi_mobile_text_attachments";

const MAX_RUNTIME_IMAGES = 5;
const MAX_RUNTIME_TEXT_ATTACHMENTS = 5;
const MAX_RUNTIME_IMAGE_BASE64_CHARS = 1_500_000;
const MAX_RUNTIME_IMAGES_BASE64_CHARS = 7_500_000;
const MAX_LIVE_TOOL_IMAGE_BASE64_CHARS = 2_800_000;
const ATTACHMENT_IMAGE_REFERENCE =
  /^attachment:([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/;
const ATTACHMENT_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BASE64 =
  /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const SUPPORTED_RUNTIME_IMAGE_MIME_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
]);

export class LiveOnlySessionStorage<TMetadata extends SessionMetadata>
  extends InMemorySessionStorage<TMetadata> {
  constructor(
    options: { entries?: SessionTreeEntry[]; metadata: TMetadata },
    private readonly liveContext: LiveTaskContextState,
  ) {
    super(options);
  }

  override async appendEntry(entry: SessionTreeEntry): Promise<void> {
    await super.appendEntry(
      expireLiveToolContext(entry, this.liveContext) as SessionTreeEntry,
    );
  }
}

export function createLiveTaskContext(
  images: PiRuntimeImageInput[],
): LiveTaskContextState {
  return {
    imageAttachmentIdsByData: runtimeImageReferenceMap(images),
    liveToolImagesByData: new Map(),
    consumedLiveToolImageData: new Set(),
    liveToolTextsByText: new Map(),
    consumedLiveToolTexts: new Set(),
  };
}

export function requireRuntimeImageInputs(value: unknown): PiRuntimeImageInput[] {
  if (!Array.isArray(value) || value.length > MAX_RUNTIME_IMAGES) {
    throw new Error("PI_MOBILE_IMAGE_INPUTS_INVALID");
  }
  let totalChars = 0;
  const attachmentIds = new Set<string>();
  return value.map((candidate) => {
    if (!isRecord(candidate)) throw new Error("PI_MOBILE_IMAGE_INPUT_INVALID");
    const attachmentId = candidate.attachmentId;
    const mimeType = candidate.mimeType;
    const data = candidate.data;
    if (
      typeof attachmentId !== "string" ||
      !ATTACHMENT_ID.test(attachmentId) ||
      attachmentIds.has(attachmentId)
    ) {
      throw new Error("PI_MOBILE_IMAGE_ATTACHMENT_ID_INVALID");
    }
    if (
      typeof mimeType !== "string" ||
      !SUPPORTED_RUNTIME_IMAGE_MIME_TYPES.has(mimeType)
    ) {
      throw new Error("PI_MOBILE_IMAGE_MIME_INVALID");
    }
    if (
      typeof data !== "string" ||
      data.length < 4 ||
      data.length > MAX_RUNTIME_IMAGE_BASE64_CHARS ||
      !BASE64.test(data)
    ) {
      throw new Error("PI_MOBILE_IMAGE_DATA_INVALID");
    }
    totalChars += data.length;
    if (totalChars > MAX_RUNTIME_IMAGES_BASE64_CHARS) {
      throw new Error("PI_MOBILE_IMAGE_INPUTS_TOO_LARGE");
    }
    attachmentIds.add(attachmentId);
    return { attachmentId, mimeType, data };
  });
}

export function requireRuntimeTextAttachmentInputs(
  value: unknown,
): PiRuntimeTextAttachmentInput[] {
  if (!Array.isArray(value) || value.length > MAX_RUNTIME_TEXT_ATTACHMENTS) {
    throw new Error("PI_MOBILE_TEXT_ATTACHMENTS_INVALID");
  }
  const attachmentIds = new Set<string>();
  return value.map((candidate) => {
    if (!isRecord(candidate) || Object.keys(candidate).length !== 4) {
      throw new Error("PI_MOBILE_TEXT_ATTACHMENT_INVALID");
    }
    const { attachmentId, displayName, mimeType, byteSize } = candidate;
    if (
      typeof attachmentId !== "string" ||
      !ATTACHMENT_ID.test(attachmentId) ||
      attachmentIds.has(attachmentId)
    ) {
      throw new Error("PI_MOBILE_TEXT_ATTACHMENT_ID_INVALID");
    }
    if (
      typeof displayName !== "string" ||
      displayName.length < 1 ||
      displayName.length > 240 ||
      displayName.includes("\u0000")
    ) {
      throw new Error("PI_MOBILE_TEXT_ATTACHMENT_NAME_INVALID");
    }
    if (
      typeof mimeType !== "string" ||
      mimeType.length < 1 ||
      mimeType.length > 128 ||
      mimeType.includes("\u0000")
    ) {
      throw new Error("PI_MOBILE_TEXT_ATTACHMENT_MIME_INVALID");
    }
    if (
      typeof byteSize !== "number" ||
      !Number.isSafeInteger(byteSize) ||
      byteSize < 1 ||
      byteSize > 4 * 1024 * 1024
    ) {
      throw new Error("PI_MOBILE_TEXT_ATTACHMENT_SIZE_INVALID");
    }
    attachmentIds.add(attachmentId);
    return { attachmentId, displayName, mimeType, byteSize };
  });
}

export function requireTaskInput(
  text: string,
  images: PiRuntimeImageInput[],
  textAttachments: PiRuntimeTextAttachmentInput[] = [],
): void {
  if (
    typeof text !== "string" ||
    text.length > 65_536 ||
    text.includes("\u0000") ||
    (text.trim().length === 0 &&
      images.length === 0 &&
      textAttachments.length === 0)
  ) {
    throw new Error("PI_MOBILE_PROMPT_INVALID");
  }
}

export function requireTextAttachmentControlData(value: unknown): {
  kind: "text_attachments";
  originalText: string;
  attachments: PiRuntimeTextAttachmentInput[];
} {
  if (
    !isRecord(value) ||
    Object.keys(value).length !== 3 ||
    value.kind !== "text_attachments"
  ) {
    throw new Error("PI_MOBILE_TEXT_ATTACHMENT_CONTROL_INVALID");
  }
  if (
    typeof value.originalText !== "string" ||
    value.originalText.length > 65_536 ||
    value.originalText.includes("\u0000")
  ) {
    throw new Error("PI_MOBILE_TEXT_ATTACHMENT_CONTROL_INVALID");
  }
  return {
    kind: "text_attachments",
    originalText: value.originalText,
    attachments: requireRuntimeTextAttachmentInputs(value.attachments),
  };
}

export async function promptWithTextAttachments(
  session: Session,
  originalText: string,
  attachments: PiRuntimeTextAttachmentInput[],
): Promise<string> {
  if (attachments.length === 0) return originalText;
  const data = requireTextAttachmentControlData({
    kind: "text_attachments",
    originalText,
    attachments,
  });
  const controlId = await session.appendCustomEntry(
    TEXT_ATTACHMENT_CONTROL_ENTRY_TYPE,
    data,
  );
  const decorated = [
    `[momoding:text-attachments control=${controlId}]`,
    "The user explicitly attached the files listed below. Use attachment_read with an exact attachmentId before relying on file content.",
    "<user_message>",
    originalText,
    "</user_message>",
    `attachments=${JSON.stringify(attachments)}`,
  ].join("\n");
  if (decorated.length > 70_000) {
    throw new Error("PI_MOBILE_ATTACHMENT_PROMPT_TOO_LARGE");
  }
  return decorated;
}

export function toPiImages(images: PiRuntimeImageInput[]): ImageContent[] {
  return images.map(({ data, mimeType }) => ({ type: "image", data, mimeType }));
}

export function registerRuntimeImages(
  state: LiveTaskContextState,
  images: PiRuntimeImageInput[],
): void {
  images.forEach((image) =>
    appendRuntimeImageReference(state.imageAttachmentIdsByData, image)
  );
}

export function requireNativeToolContent(
  value: unknown,
): AgentToolResult<unknown>["content"] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 2) {
    throw new Error("PI_MOBILE_NATIVE_TOOL_CONTENT_INVALID");
  }
  let imageCount = 0;
  return value.map((candidate) => {
    if (!isRecord(candidate)) {
      throw new Error("PI_MOBILE_NATIVE_TOOL_CONTENT_INVALID");
    }
    if (
      candidate.type === "text" &&
      typeof candidate.text === "string" &&
      candidate.text.length <= 65_536 &&
      !candidate.text.includes("\u0000")
    ) {
      return { type: "text" as const, text: candidate.text };
    }
    if (
      candidate.type === "image" &&
      typeof candidate.data === "string" &&
      candidate.data.length > 0 &&
      candidate.data.length <= MAX_LIVE_TOOL_IMAGE_BASE64_CHARS &&
      BASE64.test(candidate.data) &&
      (candidate.mimeType === "image/png" ||
        candidate.mimeType === "image/jpeg")
    ) {
      imageCount += 1;
      if (imageCount > 1) {
        throw new Error("PI_MOBILE_NATIVE_TOOL_IMAGE_LIMIT");
      }
      return {
        type: "image" as const,
        data: candidate.data,
        mimeType: candidate.mimeType,
      };
    }
    throw new Error("PI_MOBILE_NATIVE_TOOL_CONTENT_INVALID");
  });
}

export function registerLiveToolResult(
  state: LiveTaskContextState,
  request: NativeToolRequest,
  content: AgentToolResult<unknown>["content"],
  details: unknown,
  isError: boolean,
): void {
  registerLiveToolImages(state, request, content, details, isError);
  registerLiveToolTexts(state, request, content, details, isError);
}

export function expireLiveToolContext(
  value: unknown,
  state: LiveTaskContextState,
): unknown {
  return expireLiveToolTexts(
    expireLiveToolImages(value, state.liveToolImagesByData),
    state.liveToolTextsByText,
  );
}

export function rehydrateLiveToolContext(
  value: unknown,
  state: LiveTaskContextState,
): unknown {
  return rehydrateLiveToolTexts(
    rehydrateLiveToolImages(
      value,
      state.liveToolImagesByData,
      state.consumedLiveToolImageData,
    ),
    state.liveToolTextsByText,
    state.consumedLiveToolTexts,
  );
}

export function consumeLiveToolContext(
  providerMessages: unknown[],
  state: LiveTaskContextState,
): void {
  consumeLiveToolImages(
    providerMessages,
    state.liveToolImagesByData,
    state.consumedLiveToolImageData,
  );
  consumeLiveToolTexts(
    providerMessages,
    state.liveToolTextsByText,
    state.consumedLiveToolTexts,
  );
}

export function clearLiveToolContext(state: LiveTaskContextState): void {
  state.liveToolImagesByData.clear();
  state.consumedLiveToolImageData.clear();
  state.liveToolTextsByText.clear();
  state.consumedLiveToolTexts.clear();
}

export function sanitizeImagesForAndroid(
  value: unknown,
  references: Map<string, string[]>,
  orderedOccurrences = false,
): unknown {
  const totalOccurrencesByData = new Map<string, number>();
  const countRawImageOccurrences = (candidate: unknown): void => {
    if (Array.isArray(candidate)) {
      candidate.forEach(countRawImageOccurrences);
      return;
    }
    if (!isRecord(candidate)) return;
    if (candidate.type === "image") {
      const data = candidate.data;
      if (
        typeof data === "string" &&
        ATTACHMENT_IMAGE_REFERENCE.exec(data) === null
      ) {
        totalOccurrencesByData.set(
          data,
          (totalOccurrencesByData.get(data) ?? 0) + 1,
        );
      }
      return;
    }
    Object.values(candidate).forEach(countRawImageOccurrences);
  };
  countRawImageOccurrences(value);
  const occurrenceByData = new Map<string, number>();
  const visit = (candidate: unknown): unknown => {
    if (Array.isArray(candidate)) return candidate.map(visit);
    if (!isRecord(candidate)) return candidate;
    if (candidate.type === "image") {
      const data = candidate.data;
      const mimeType = candidate.mimeType;
      if (typeof data !== "string" || typeof mimeType !== "string") {
        throw new Error("PI_MOBILE_IMAGE_SESSION_CONTENT_INVALID");
      }
      const persistedReference = ATTACHMENT_IMAGE_REFERENCE.exec(data);
      if (persistedReference !== null) return { ...candidate };
      const attachmentIds = references.get(data);
      if (attachmentIds === undefined || attachmentIds.length === 0) {
        throw new Error("PI_MOBILE_IMAGE_SESSION_REFERENCE_MISSING");
      }
      const occurrence = occurrenceByData.get(data) ?? 0;
      const firstOccurrenceIndex = orderedOccurrences
        ? 0
        : Math.max(
            0,
            attachmentIds.length - (totalOccurrencesByData.get(data) ?? 1),
          );
      const attachmentId = attachmentIds[firstOccurrenceIndex + occurrence];
      if (attachmentId === undefined) {
        throw new Error("PI_MOBILE_IMAGE_SESSION_REFERENCE_MISSING");
      }
      occurrenceByData.set(data, occurrence + 1);
      return { ...candidate, data: `attachment:${attachmentId}` };
    }
    return Object.fromEntries(
      Object.entries(candidate).map(([key, item]) => [key, visit(item)]),
    );
  };
  return visit(value);
}

export function rehydrateImageReferences(
  value: unknown,
  images: PiRuntimeImageInput[],
): unknown {
  const byAttachmentId = new Map(
    images.map((image) => [image.attachmentId, image]),
  );
  const visit = (candidate: unknown): unknown => {
    if (Array.isArray(candidate)) return candidate.map(visit);
    if (!isRecord(candidate)) return candidate;
    if (candidate.type === "image") {
      const data = candidate.data;
      const mimeType = candidate.mimeType;
      if (typeof data !== "string" || typeof mimeType !== "string") {
        throw new Error("PI_MOBILE_IMAGE_SESSION_CONTENT_INVALID");
      }
      const reference = ATTACHMENT_IMAGE_REFERENCE.exec(data);
      if (reference === null) {
        throw new Error("PI_MOBILE_IMAGE_SESSION_RAW_DATA_FORBIDDEN");
      }
      const image = byAttachmentId.get(reference[1]);
      if (image === undefined || image.mimeType !== mimeType) {
        throw new Error("PI_MOBILE_IMAGE_SESSION_REFERENCE_MISSING");
      }
      return { ...candidate, data: image.data };
    }
    return Object.fromEntries(
      Object.entries(candidate).map(([key, item]) => [key, visit(item)]),
    );
  };
  return visit(value);
}

function runtimeImageReferenceMap(
  images: PiRuntimeImageInput[],
): Map<string, string[]> {
  const references = new Map<string, string[]>();
  images.forEach((image) => appendRuntimeImageReference(references, image));
  return references;
}

function appendRuntimeImageReference(
  references: Map<string, string[]>,
  image: PiRuntimeImageInput,
): void {
  const attachmentIds = references.get(image.data) ?? [];
  if (!attachmentIds.includes(image.attachmentId)) {
    attachmentIds.push(image.attachmentId);
  }
  references.set(image.data, attachmentIds);
}

function registerLiveToolImages(
  state: LiveTaskContextState,
  request: NativeToolRequest,
  content: AgentToolResult<unknown>["content"],
  details: unknown,
  isError: boolean,
): void {
  if (
    request.kind === "android_image_generation_tool" &&
    request.toolName === "image_generate"
  ) {
    if (isError) return;
    const attachmentId = isRecord(details) ? details.attachmentId : undefined;
    const mimeType = isRecord(details) ? details.mimeType : undefined;
    if (
      content.length !== 1 ||
      content[0].type !== "text" ||
      !isRecord(details) ||
      details.kind !== "generated_image_artifact" ||
      details.persistent !== true ||
      typeof attachmentId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
        attachmentId,
      ) ||
      (mimeType !== "image/png" &&
        mimeType !== "image/jpeg" &&
        mimeType !== "image/webp")
    ) {
      throw new Error("PI_MOBILE_GENERATED_IMAGE_DETAILS_INVALID");
    }
    return;
  }
  const images = content.filter(
    (block): block is ImageContent => block.type === "image",
  );
  if (images.length === 0) return;
  if (!isRecord(details)) {
    throw new Error("PI_MOBILE_LIVE_IMAGE_DETAILS_INVALID");
  }
  const contentSha256 = details.contentSha256;
  const width = details.width;
  const height = details.height;
  const mimeType = details.mimeType;
  if (
    request.kind !== "android_screen_tool" ||
    request.toolName !== SCREEN_CAPTURE_TOOL_NAME ||
    isError ||
    content.length !== 2 ||
    content[0].type !== "text" ||
    content[1].type !== "image" ||
    details.liveOnly !== true ||
    (details.source !== "accessibility" &&
      details.source !== "media_projection") ||
    typeof contentSha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(contentSha256) ||
    !Number.isSafeInteger(width) ||
    (width as number) < 1 ||
    (width as number) > 16_384 ||
    !Number.isSafeInteger(height) ||
    (height as number) < 1 ||
    (height as number) > 16_384 ||
    (mimeType !== "image/png" && mimeType !== "image/jpeg") ||
    images[0].mimeType !== mimeType
  ) {
    throw new Error("PI_MOBILE_LIVE_IMAGE_DETAILS_INVALID");
  }
  state.liveToolImagesByData.set(images[0].data, {
    contentSha256,
    width: width as number,
    height: height as number,
    mimeType,
  });
}

function registerLiveToolTexts(
  state: LiveTaskContextState,
  request: NativeToolRequest,
  content: AgentToolResult<unknown>["content"],
  details: unknown,
  isError: boolean,
): void {
  const hasLocationIdentity =
    request.kind === "android_location_tool" ||
    request.toolName === LOCATION_TOOL_NAME;
  const hasClipboardIdentity =
    request.kind === "android_clipboard_tool" ||
    request.toolName === CLIPBOARD_TOOL_NAME;
  if (!hasLocationIdentity && !hasClipboardIdentity) {
    if (
      isRecord(details) &&
      (details.dataClass === "location" ||
        details.dataClass === "clipboard")
    ) {
      throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
    }
    return;
  }
  if (
    hasLocationIdentity &&
    (request.kind !== "android_location_tool" ||
      request.toolName !== LOCATION_TOOL_NAME)
  ) {
    throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
  }
  if (
    hasClipboardIdentity &&
    (request.kind !== "android_clipboard_tool" ||
      request.toolName !== CLIPBOARD_TOOL_NAME)
  ) {
    throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
  }
  if (isError) return;
  if (hasClipboardIdentity) {
    const action = request.arguments.action;
    if (action !== "get") {
      if (isRecord(details) && details.dataClass === "clipboard") {
        throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
      }
      return;
    }
    if (!isRecord(details) || details.dataClass !== "clipboard") {
      throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
    }
    const text =
      content.length === 1 && content[0].type === "text"
        ? content[0].text
        : null;
    const payload = typeof text === "string" ? parseJsonRecord(text) : null;
    const data = isRecord(payload?.data) ? payload.data : null;
    const verification = isRecord(payload?.verification)
      ? payload.verification
      : null;
    if (
      details.liveOnly !== true ||
      typeof text !== "string" ||
      typeof details.contentSha256 !== "string" ||
      !/^[0-9a-f]{64}$/.test(details.contentSha256) ||
      sha256(text) !== details.contentSha256 ||
      payload?.ok !== true ||
      payload.action !== "get" ||
      data?.state !== "text" ||
      typeof data.text !== "string" ||
      data.text.length < 1 ||
      data.text.length > 8192 ||
      !Number.isSafeInteger(data.characterCount) ||
      data.characterCount !== data.text.length ||
      verification?.status !== "observed" ||
      typeof verification.observedAt !== "string" ||
      verification.observedAt.length < 20 ||
      verification.observedAt.length > 40
    ) {
      throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
    }
    state.liveToolTextsByText.set(text, {
      dataClass: "clipboard",
      contentSha256: details.contentSha256,
    });
    return;
  }
  if (!isRecord(details) || details.dataClass !== "location") {
    throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
  }
  const text =
    content.length === 1 && content[0].type === "text"
      ? content[0].text
      : null;
  const payload = typeof text === "string" ? parseJsonRecord(text) : null;
  const data = isRecord(payload?.data) ? payload.data : null;
  const verification = isRecord(payload?.verification)
    ? payload.verification
    : null;
  const precision = details.precision;
  const latitude = data?.latitude;
  const longitude = data?.longitude;
  const accuracyMeters = data?.accuracyMeters;
  const ageMillis = data?.ageMillis;
  if (
    details.liveOnly !== true ||
    typeof text !== "string" ||
    typeof details.contentSha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(details.contentSha256) ||
    sha256(text) !== details.contentSha256 ||
    (precision !== "approximate" && precision !== "precise") ||
    payload?.ok !== true ||
    payload.action !== "get_current" ||
    verification?.status !== "observed" ||
    typeof verification.observedAt !== "string" ||
    verification.observedAt.length < 20 ||
    verification.observedAt.length > 40 ||
    data?.precision !== precision ||
    typeof latitude !== "number" ||
    !Number.isFinite(latitude) ||
    latitude < -90 ||
    latitude > 90 ||
    typeof longitude !== "number" ||
    !Number.isFinite(longitude) ||
    longitude < -180 ||
    longitude > 180 ||
    typeof accuracyMeters !== "number" ||
    !Number.isFinite(accuracyMeters) ||
    accuracyMeters < 0 ||
    accuracyMeters > 100_000 ||
    typeof data?.capturedAt !== "string" ||
    data.capturedAt.length < 20 ||
    data.capturedAt.length > 40 ||
    !Number.isSafeInteger(ageMillis) ||
    (ageMillis as number) < 0 ||
    (ageMillis as number) > 300_000 ||
    !["satellite", "network", "passive", "system"].includes(
      data?.providerCategory as string,
    )
  ) {
    throw new Error("PI_MOBILE_LIVE_TEXT_DETAILS_INVALID");
  }
  state.liveToolTextsByText.set(text, {
    dataClass: "location",
    contentSha256: details.contentSha256,
    precision,
  });
}

function expireLiveToolTexts(
  value: unknown,
  texts: Map<string, LiveToolTextDescriptor>,
): unknown {
  const visit = (candidate: unknown): unknown => {
    if (Array.isArray(candidate)) return candidate.map(visit);
    if (!isRecord(candidate)) return candidate;
    if (
      candidate.type === "text" &&
      typeof candidate.text === "string" &&
      texts.has(candidate.text)
    ) {
      return {
        ...candidate,
        text: liveTextExpiredText(texts.get(candidate.text)!),
      };
    }
    return Object.fromEntries(
      Object.entries(candidate).map(([key, item]) => [key, visit(item)]),
    );
  };
  return visit(value);
}

function rehydrateLiveToolTexts(
  value: unknown,
  texts: Map<string, LiveToolTextDescriptor>,
  consumedTexts: Set<string>,
): unknown {
  const textByPlaceholder = new Map(
    [...texts.entries()]
      .filter(([text]) => !consumedTexts.has(text))
      .map(([text, descriptor]) => [liveTextExpiredText(descriptor), text]),
  );
  const visit = (candidate: unknown): unknown => {
    if (Array.isArray(candidate)) return candidate.map(visit);
    if (!isRecord(candidate)) return candidate;
    if (candidate.type === "text" && typeof candidate.text === "string") {
      const text = textByPlaceholder.get(candidate.text);
      if (text !== undefined) return { ...candidate, text };
    }
    return Object.fromEntries(
      Object.entries(candidate).map(([key, item]) => [key, visit(item)]),
    );
  };
  return visit(value);
}

function consumeLiveToolTexts(
  providerMessages: unknown[],
  texts: Map<string, LiveToolTextDescriptor>,
  consumedTexts: Set<string>,
): void {
  const serialized = JSON.stringify(providerMessages);
  for (const text of texts.keys()) {
    if (serialized.includes(text)) consumedTexts.add(text);
  }
}

function liveTextExpiredText(descriptor: LiveToolTextDescriptor): string {
  if (descriptor.dataClass === "clipboard") {
    return `[live Android clipboard expired sha256=${descriptor.contentSha256}]`;
  }
  return `[live Android location expired sha256=${descriptor.contentSha256} precision=${descriptor.precision}]`;
}

function expireLiveToolImages(
  value: unknown,
  images: Map<string, LiveToolImageDescriptor>,
): unknown {
  const visit = (candidate: unknown): unknown => {
    if (Array.isArray(candidate)) return candidate.map(visit);
    if (!isRecord(candidate)) return candidate;
    if (
      candidate.type === "image" &&
      typeof candidate.data === "string" &&
      images.has(candidate.data)
    ) {
      const descriptor = images.get(candidate.data)!;
      return {
        type: "text",
        text: liveImageExpiredText(descriptor),
      };
    }
    return Object.fromEntries(
      Object.entries(candidate).map(([key, item]) => [key, visit(item)]),
    );
  };
  return visit(value);
}

function rehydrateLiveToolImages(
  value: unknown,
  images: Map<string, LiveToolImageDescriptor>,
  consumedImageData: Set<string>,
): unknown {
  const imageByPlaceholder = new Map(
    [...images.entries()]
      .filter(([data]) => !consumedImageData.has(data))
      .map(([data, descriptor]) => [
        liveImageExpiredText(descriptor),
        { type: "image", data, mimeType: descriptor.mimeType },
      ]),
  );
  const visit = (candidate: unknown): unknown => {
    if (Array.isArray(candidate)) return candidate.map(visit);
    if (!isRecord(candidate)) return candidate;
    if (candidate.type === "text" && typeof candidate.text === "string") {
      const image = imageByPlaceholder.get(candidate.text);
      if (image !== undefined) return { ...image };
    }
    return Object.fromEntries(
      Object.entries(candidate).map(([key, item]) => [key, visit(item)]),
    );
  };
  return visit(value);
}

function consumeLiveToolImages(
  providerMessages: unknown[],
  images: Map<string, LiveToolImageDescriptor>,
  consumedImageData: Set<string>,
): void {
  const serialized = JSON.stringify(providerMessages);
  for (const data of images.keys()) {
    if (serialized.includes(data)) consumedImageData.add(data);
  }
}

function liveImageExpiredText(
  descriptor: LiveToolImageDescriptor,
): string {
  return [
    "[live screen image expired",
    `sha256=${descriptor.contentSha256}`,
    `${descriptor.width}x${descriptor.height}`,
    descriptor.mimeType,
    "]",
  ].join(" ");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseJsonRecord(value: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(value) as unknown;
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}
