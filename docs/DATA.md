# Data and storage

The probe has camera permission only. It records no audio and requests no Internet access. Camera acquisition begins only after an explicit Record tap; navigation to the background stops it. Screen stay-awake applies only while recording.

Capability reports and explicitly recorded MP4/JSON pairs are stored in the app's private `files/benchmarks` directory. Incomplete captures retain a `.partial.mp4` suffix and an unsuccessful sidecar, so failed evidence cannot look complete. No automatic media expiry is promised. Delete through the app or uninstall. Android/OS logs, installation metadata and APK files are separate from benchmark media.

Host collection copies these files into `benchmarks/raw/<collection>/`; it never deletes phone files. Raw media, review copies, scoring sheets and private source mappings are gitignored. Blind review makes deliberate additional copies; the tool reports the destination. Delete those directories when finished. Benchmark reports may reveal phone model/OS and recording settings; review before publishing. No serial number is written into public reports.

Telemetry uses fixed counters and bounded timing windows, not per-frame lists. The sidecar labels its frame-gap percentile as a recent bounded sample and reports overall counts/maximum separately. Java/native heap samples do not represent total process memory or prove a two-hour stability gate.
