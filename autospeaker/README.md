# AutoSpeaker

AutoSpeaker is an experimental vivo X60 / OriginOS helper that turns on speakerphone after an **incoming** call is manually answered. It does not auto-answer and does not force speakerphone for outgoing calls.

## Event-driven routing

Instead of racing Telecom with fixed delays, AutoSpeaker listens for `AudioManager` mode changes and (on Android 12+) communication-device changes:

- The chain starts as soon as the audio mode settles into `MODE_IN_CALL` (bounded 2.5 s fallback if the event never arrives).
- The route is then watched for a 5 s window. When Telecom resets it back to the earpiece, the request is retried, at most 3 times in total.
- A route to an external device (Bluetooth / wired / USB) is always respected and stops the automation.

## Route modes

- **Shizuku-first (default)** — routing does not require the accessibility service:
  1. `AudioManager` / `setCommunicationDevice`
  2. Mode-owner backend (Android 12+): briefly takes `MODE_IN_COMMUNICATION`, which makes the app's own communication-device request authoritative instead of the mode owner's, then re-requests the speaker and verifies. Restored at call end. Note: while the app owns the audio mode, manual route toggles inside the dialer may not take effect until hangup.
  3. Shizuku UI backend: on the first call it finds the speaker control via `uiautomator dump` (keyword match), taps it, and **auto-learns** the control so later calls take the sub-second `dumpsys`-verified fast path — the accessibility service is never involved
  4. Shizuku audio route (shell-identity `IAudioService` / `AudioSystem.setForceUse`, verified against the real route)
  5. Root audio route (`su` + `app_process`, process held alive for 10 s so the per-client request survives binder death, cleaned up with an explicit off after the call)
- **Accessibility-first** — the proven vivo path:
  1. `AudioManager`
  2. Accessibility clicks the visible `免提` / `扬声器` / `Speaker` control
  3. Mode-owner, Shizuku UI / audio, then Root as fallback

The speaker-button fingerprint is shared by both modes. The Shizuku backend auto-learns it after the first successful tap; manual learning ("学习免提按钮", which temporarily enables the accessibility service) is only needed when the in-call speaker button has no text, description or view id at all.

## Background reliability

The Shizuku UserService runs in daemon mode. It can survive the AutoSpeaker app process being killed, but it is still stopped when the Shizuku server itself is stopped or restarted. AutoSpeaker tracks Shizuku binder death/return and re-binds the UserService when Shizuku comes back. The UserService is bound with `version(VERSION_CODE)`, so every APK update restarts the daemon with the new code. Every privileged binder call is bounded by a timeout so a hung daemon cannot stall the fallback chain.

## Diagnostics

AutoSpeaker writes a persistent internal log (rotated at about 256 KiB). The in-app log viewer shows the latest 300 lines and provides Refresh, Copy and Clear actions.

Logged events include:

- App startup and permission state
- `PHONE_STATE` transitions and incoming-call detection
- Mode / communication-device change events and bounded route retries
- AudioManager request and confirmation
- Shizuku permission, binder lifecycle, UserService binding, route result, UI dump and tap result
- Root backend execution/result
- Accessibility service lifecycle and speaker button discovery/click result
- Final backend and failure information

## Build

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub Actions uploads the debug APK as the `AutoSpeaker-debug` artifact.
