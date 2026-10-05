# AA-Bridge capability / adapter architecture

AA-Bridge expresses what execution capability an agent needs separately from how Android provides it.

## Core flow

Agent intent -> Policy / approval -> ExecutionCapability -> ExecutionAdapterRegistry -> adapter -> normalized ExecutionResult -> agent.

Adapters can include Termux RUN_COMMAND, Termux local bridge, Android shell, Android Intent, Accessibility, and future Shizuku or SSH paths. Product names such as Termux must not leak into the agent's intent.

## Termux compatibility

LINUX_SHELL has two planned providers. RUN_COMMAND is preferred when the installed Termux distribution exposes the protected service. A local bridge is the fallback for distributions such as Google Play builds that do not expose RUN_COMMAND. The bridge runs inside Termux and listens only on loopback.

The existing authenticated ClosePaw bridge remains the execution protocol. The fallback must retain loopback-only binding, strong per-install authentication, explicit shell approval, bounded timeouts/output, and audit logging.

## Pairing rule

Android apps share the loopback network namespace, so localhost alone is not an authentication boundary. A Google Play Termux fallback therefore needs explicit pairing. The ClosePaw token must never be committed or logged. The setup UI should provide a one-time bootstrap/pairing flow; after pairing, health and exec use the same authenticated protocol as the RUN_COMMAND path.

## Migration

1. Add capability/adapter contracts and selection tests.
2. Wrap the existing RUN_COMMAND path as a LINUX_SHELL adapter.
3. Add authenticated local-bridge pairing/bootstrap for Google Play Termux.
4. Change agent tooling to request capabilities instead of selecting shell/termux_shell by product name.
5. Add Android Intent, Accessibility and other adapters behind the same policy gateway.

No existing Termux installation needs to be removed merely to adopt this architecture.
