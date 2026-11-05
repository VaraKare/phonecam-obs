# Camera-quality benchmark procedure

## Goal and fixed conditions

Determine whether normal rear-camera video approaches the stock app at a repeatable profile. There is no assumption of access to Vivo's proprietary processing. This experiment cannot establish glass-to-OBS latency; the USB/native source experiment is a later gate.

Confirm the physical phone's model/OS using doctor and capability report. Use a tripod, the same lens/field of view, landscape framing, normal SDR, and 30 fps. Start with 1080p30 to diagnose the path and test 4K30 when advertised. Preserve lighting and subject position between captures. Disable optional stock HDR/beauty/filters for this normal-video comparison; if they cannot be disabled, document the mismatch and mark the comparison inconclusive.

Make a stock baseline and a tuned direct-camera scrcpy baseline for each scene: `well_lit_detail`, `indoor_face`, `low_light_motion`, `backlit_subject`. Run the candidate probe FAST, then HIGH_QUALITY where supported; test available H.264/H.265 bitrate choices. Review the observed results before selecting a winner. Do not compare a tuned probe with a deliberately low-bitrate scrcpy baseline. Direct scrcpy camera capture needs Android 12+; on Android 10/11, direct-camera scrcpy is unavailable. Record this as missing baseline evidence and keep the benchmark inconclusive rather than substituting screen mirroring silently. The app itself still supports Android 10+.

## Baselines and evidence

```sh
python3 tools/phonecam.py baseline-command --camera 0 --size 1920x1080 --codec h264 --bitrate 10 --out benchmarks/raw/scrcpy-detail.mp4
```

This prints a command only. Verify camera ID/codec/bitrate against the probe and execute it yourself to start the intentional baseline recording. The command limits recording to 15 seconds, has no audio, and disables playback. Stock-app screen mirroring is a distinct experiment and does not qualify as the matched direct-camera baseline.

After selecting the winning profile, record each scene three independent times in the probe. Use the same hardware/OS/camera/encoder/resolution/bitrate/processing each time. Sidecar `profileId` binds these requested conditions. Avoid changing display orientation during a clip. Use at least 15-second timed captures; the evaluator requires at least 10 seconds of encoded material. Configure/exposure settling can make a clip longer than its nominal capture timer.

Collect clips with `python3 tools/phonecam.py collect`. Keep test footage private. No raw footage is committed to Git.

## Blind review

Create `benchmarks/raw/session.json` using this structure, adding all **20** required entries: four stock baselines, four scrcpy baselines, and twelve probe clips (four scenes × three repeats).

```json
{
  "schemaVersion": 1,
  "matchedConditionsConfirmed": true,
  "clips": [
    {"source": "stock", "scene": "well_lit_detail", "repeat": 1, "file": "stock-detail.mp4"},
    {"source": "scrcpy", "scene": "well_lit_detail", "repeat": 1, "file": "scrcpy-detail.mp4"},
    {"source": "probe", "scene": "well_lit_detail", "repeat": 1, "file": "collection/session-id.mp4", "sidecar": "collection/session-id.json"}
  ]
}
```

The shortened example is illustrative and deliberately cannot pass the complete-evidence check. Paths are relative to the manifest. Confirm matched conditions only after verifying them. ffprobe (from FFmpeg) must be on PATH; review preparation reads actual codec, size, frame count and rate, cross-checks probe sidecars, and rejects duplicate media used as repetitions. This is a local evidence tool, not a tamper-proof scientific instrument.

```sh
python3 tools/phonecam.py review-init benchmarks/raw/session.json --out benchmarks/reviews/reference-profile
```

The tool creates randomized `clips/clip-NNN.mp4` copies, `ratings.csv`, and `PRIVATE_sources.json`. Score the clips before opening the private mapping. This is filename blinding; encoder/container metadata is not sanitized and can reveal provenance. Copies consume additional disk space.

For each clip fill:

- `score`: 1–5. **4 = close enough to use without corrective processing** relative to the stock reference. 5 = no meaningful visible degradation; 3 = visible degradation requiring correction; 1–2 = unacceptable.
- `acceptable_focus`, `acceptable_motion`, `no_severe_artifacts`: `yes` or `no`.
- `notes`: concrete problems, such as focus pumping, smearing, oversharpening, clipped highlights or skin-tone shifts.

Use the stock references to calibrate your judgement; randomized playback is not a pixel-aligned PSNR/SSIM experiment. Repeated captures are not frame-identical scenes.

```sh
python3 tools/phonecam.py evaluate benchmarks/reviews/reference-profile --out benchmarks/results/reference-quality.json
```

## Decision policy

Pass requires every probe scene/repeat ≥4, all three artifact checks yes, actual encoded fps 29.7–30.3, at least 10 seconds, sufficient frame count, no gap >66,667 µs, monotonic timestamps, applied processing confirmed, intact media hashes, successful timed finalization, matched baselines and one stable profile. A low score/failed performance check is **fail**. Missing evidence is **inconclusive**. Neither permits advancing.

A quality pass allows only the USB-to-OBS research milestone. It always reports optical latency `not_measured`, overall feasibility `inconclusive`, and verified device `false`. Qualify quality and optical latency together at the same settings later.

Initial research is timeboxed to ten engineering days excluding hardware/tool setup. If quality fails, allow **one** tuning cycle of at most three engineering days, then publish pass/fail/inconclusive findings and stop this approach if unresolved. Older phones can later have explicitly limited profiles; do not rewrite a failed premium result as a pass.

## Later optical and stability tests

Once quality passes, use a separate high-frame-rate camera to record a visible real event/stopwatch and OBS preview together. Report reference-camera frame rate, monitor refresh, sample count, warm-up, median and p95; at least 100 events across repeated warmed runs. p95 must be below 100 ms at the quality-approved profile. Internal clocks alone cannot qualify optical latency.

Then test two-hour stability, 20 unplug/replug cycles, malformed transport input, unsupported settings, permission loss, locking, OBS source/scene lifecycle, actual hardware decode, fixed queue limits, thermal status and total-process memory after warm-up. Current short-recording heap samples do not satisfy these later gates. Audio follows, with one-hour drift/offset within 33 ms.
