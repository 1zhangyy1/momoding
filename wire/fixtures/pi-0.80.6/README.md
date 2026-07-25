# Pi 0.80.6 contract fixtures

`core-contract.json` contains representative framing-only envelopes for native `agent_start`,
`message_update`, `tool_execution_start`, `tool_execution_end`, and `agent_settled` events plus one
bounded full task snapshot.

The fixture keeps native event field names and nesting. Volatile metadata, timestamps, tool and
session IDs, provider identity, token usage, prompt/result text, and delta chunk boundaries are
replaced with fixed synthetic values. The six envelopes use fixture-local sequence `1..6`; this is
contract data, not replay evidence.

No credentials, user prompts, phone data, Android URI, host path, or raw user file belongs here.
Changes to fixtures must be reviewed together with the TypeScript schemas and Kotlin contract tests.

`reliability-contract.json` freezes ACK/history/device client frames and
replay/resync/chunk/page/device server frames. `generatedCases` binds oversized event/page chunk
starts and paged snapshot begin/end frames to deterministic recipes in the byte fixture. Its page
messages remain raw Pi-compatible JSON. It is a schema fixture, not evidence of production traffic
or device behavior.

`byte-domains.json` is the shared raw-byte oracle. Representative logical frames are stored as
base64 with exact UTF-8 lengths and SHA-256 digests. Boundary recipes cover complete `pi.event`,
logical `task.snapshot.page`, and pre-pagination `task.snapshot` domains without committing giant
payload blobs.

`attention-client-contract.json` freezes the four Android-to-host attention frame families and the exact attention-only capability manifest. Its reconcile items intentionally contain no `operationId` because both attention tools are `sideEffect=false`. It is an encoder/schema oracle only; it does not claim sender durability, Room ownership, Android UI, SAF, Provider, file side effects or real-device behavior.
