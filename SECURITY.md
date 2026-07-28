# Security policy

## Supported versions

Momoding is currently a developer preview. Security fixes are made only on the latest `main`
revision; there is no supported production release line yet.

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** for this repository. Do not open a public issue,
discussion, or pull request for a suspected vulnerability.

Include:

- affected commit and Android version;
- impact and required preconditions;
- minimal reproduction steps;
- whether credentials, authorized files, attachments, or consent boundaries are involved.

Do not send real API keys, private user files, full diagnostics archives, or destructive proof of
concepts. Use synthetic data and redact device identifiers.

The maintainers will acknowledge a complete report when it is reviewed and will coordinate a fix
and disclosure timeline based on severity. No response-time guarantee is made during the developer
preview.

## Security boundary reminders

- Provider credentials belong only in the in-app encrypted vault.
- SAF, shared storage, MediaProjection, accessibility, and Shizuku are separate authorities.
- File content reads and file writes have separate approval boundaries.
- PRoot project commands are not a hostile-code sandbox.
- The public source excludes the remote-host service but does not silently remove product features.
