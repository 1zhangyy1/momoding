# Momoding Pi runtime bundle

This build-only module packages pinned Pi `0.80.6` AgentHarness code into one QuickJS-compatible
asset consumed by the Android application.

```bash
npm ci
npm run check
```

`npm run check`:

1. type-checks the TypeScript source;
2. builds the Android JavaScript asset;
3. scans the bundle for secrets, local paths, unsupported imports, and source maps;
4. verifies byte-for-byte reproducibility; and
5. runs the real AgentHarness tests in a Node VM.

Node.js is a build and test dependency. It is not the Android runtime.

Generated and tracked assets:

- `../android-app/app/src/main/assets/pi-runtime/pi-mobile.js`
- `../android-app/app/src/main/assets/pi-runtime/manifest.json`

## Native boundary

Pi owns the agent loop inside QuickJS. Android owns the OpenRouter credential and HTTP requests,
then exchanges provider chunks and strictly typed tool requests through native mailboxes. Project
commands, file access, media, screen capture, accessibility, package inspection, approvals, goals,
skills, and child-agent effects are executed or authorized by Android-side code.

The generated bundle must be rebuilt after an intentional source commit so its manifest can record
the exact source revision. Verification must leave a clean worktree.
