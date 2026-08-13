import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function officialRegisterTool(pi: ExtensionAPI): void {
  pi.registerTool({
    name: "fixture_echo",
    label: "Fixture echo",
    description: "Return one bounded text value.",
    parameters: Type.Object({
      text: Type.String({ maxLength: 160 }),
    }, { additionalProperties: false }),
    async execute(_toolCallId, params) {
      return {
        content: [{ type: "text", text: String(params.text) }],
        details: { echoed: true },
      };
    },
  });
}
