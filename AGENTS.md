# AGENTS.md — PhotosXT

You are working in the PhotosXT project folder (the PhotoXT and PhotoXTPro Android apps). Read in this order:

1. Shared workspace rules: [hub AGENTS.md](../../../AGENTS.md), [conventions.md](../../../conventions.md), [skills.md](../../../skills.md).
2. This folder's [memory.md](memory.md): decisions already made. Keep it current. Project memory lives in that file, never in an app's private memory panel.
3. The spec for the edition you are building: [photoxt-build-prompt.md](photoxt-build-prompt.md) (PhotoXT, no media boards) or [photoxtpro-build-prompt.md](photoxtpro-build-prompt.md) (PhotoXTPro, with boards). Both use the same section and phase numbers. Build **one phase per session**. A phase is finished only when its *Done when* list passes.

Before you call a phase done, run the check command in [README.md](README.md#build-and-check); it is exactly what CI runs.

Log every session as one file in [.session/](.session/) named `YYYY-MM-DD--NNN-slug.md` (format: hub conventions.md).
