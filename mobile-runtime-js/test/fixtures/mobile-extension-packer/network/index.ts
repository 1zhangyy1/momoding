import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function networkFixture(pi: ExtensionAPI): void {
  pi.registerTool({
    name: "fixture_status",
    label: "Fixture status",
    description: "Fetch bounded JSON from one declared HTTPS origin.",
    parameters: Type.Object({}, { additionalProperties: false }),
    async execute() {
      const response = await fetch("https://status.example.test/v1/status", {
        method: "GET",
        headers: { accept: "application/json" },
      });
      if (!response.ok) throw new Error(`HTTP_${response.status}`);
      const status = await response.json();
      return {
        content: [{ type: "text", text: "Status fetched." }],
        details: { status },
      };
    },
  });
}
