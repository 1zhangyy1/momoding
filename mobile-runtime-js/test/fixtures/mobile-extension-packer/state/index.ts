import { Type } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { deleteState, getState, setState } from "@momoding/sdk";

export default function stateFixture(pi: ExtensionAPI): void {
  pi.registerTool({
    name: "fixture_counter",
    label: "Fixture counter",
    description: "Increment package-scoped bounded state.",
    parameters: Type.Object({
      reset: Type.Optional(Type.Boolean()),
    }, { additionalProperties: false }),
    async execute(_toolCallId, params) {
      if (params.reset === true) await deleteState("count");
      const previous = Number(await getState("count") ?? 0);
      const count = previous + 1;
      await setState("count", count);
      return {
        content: [{ type: "text", text: `Count: ${count}` }],
        details: { count },
      };
    },
  });
}
