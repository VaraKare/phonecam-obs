# Phase tracker

Update evidence and status together. A build or synthetic test cannot pass a physical gate.

| Milestone | Status | Evidence required |
|---|---|---|
| Repository and tooling | In progress | Reproducible APK, tests and lint |
| Capability scanner | In progress | Actual phone report and supported capture |
| Camera-quality research | In progress | Four scenes, three winning repeats, blind review, frame timing |
| USB to OBS feasibility | Blocked by quality gate | Same-profile optical p95 below 100 ms |
| Video reliability | Not started | Two hours, 20 reconnects, no accumulating resources |
| Audio | Not started | Within 33 ms for one hour |
| Broader device qualification | Not started | Vivo plus two manufacturers |
| Public alpha | Not started | Clean-Mac install and distribution tests |

Current gate decision: **inconclusive — physical phone and comparison footage unavailable**. Do not build later milestones around an assumed pass.
