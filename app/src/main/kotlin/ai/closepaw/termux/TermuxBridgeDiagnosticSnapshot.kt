package ai.closepaw.termux

/** Observations only. Neither package metadata nor HTTP failure proves Android process death. */
internal data class TermuxBridgeDiagnosticSnapshot(
    val install: TermuxInstallState,
    val health: HealthProbe,
)
