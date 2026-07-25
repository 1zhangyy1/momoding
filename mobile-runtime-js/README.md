# Momoding Pi runtime

This build-only module packages the pinned Pi `0.80.6` `AgentHarness` for the Android QuickJS
runtime. Node.js is not shipped in the APK.

```bash
npm ci
npm run check
```

`npm run check` performs TypeScript validation, verifies the tracked bundle and manifest, rebuilds
both artifacts twice in temporary directories, compares them byte-for-byte, and runs the Node VM
tests. The checkout is not modified.

To intentionally update the generated Android assets:

```bash
npm run build
npm run check
```

Commit both generated files together:

- `../android-app/app/src/main/assets/pi-runtime/pi-mobile.js`
- `../android-app/app/src/main/assets/pi-runtime/manifest.json`

The public Android integration disables `run_command` and `run_tests`. File reads and writes remain
Android-owned tools with explicit authorization and review boundaries.
