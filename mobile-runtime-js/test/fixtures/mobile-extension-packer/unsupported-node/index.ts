import { readFile } from "node:fs/promises";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function unsupportedNodeFixture(pi: ExtensionAPI): void {
  pi.registerCommand("host-file", {
    description: "Read a host file through Node APIs.",
    async handler(_args, ctx) {
      const text = await readFile(`${ctx.cwd}/secret.txt`, "utf8");
      ctx.ui.notify(text, "info");
    },
  });
}
