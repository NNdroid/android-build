# AutoSpeaker

AutoSpeaker is an experimental vivo X60 / OriginOS helper that turns on speakerphone after an **incoming** call is manually answered. It does not auto-answer and does not force speakerphone for outgoing calls.

## Backend order

1. Android `AudioManager`
2. Shizuku daemon UserService running with shell/root identity
3. Root `su` + `app_process`
4. Accessibility fallback that clicks the visible `免提` / `扬声器` / `Speaker` control

The app waits about 700 ms after the incoming call enters OFFHOOK before starting the fallback chain.

## Background reliability

The Shizuku UserService runs in daemon mode. It can survive the AutoSpeaker app process being killed, but it is still stopped when the Shizuku server itself is stopped or restarted. AutoSpeaker tracks Shizuku binder death/return and re-binds the UserService when Shizuku comes back.

## Diagnostics

AutoSpeaker v0.3.0 writes a persistent internal log (rotated at about 256 KiB). The in-app log viewer shows the latest 300 lines and provides Refresh, Copy and Clear actions.

Logged events include:

- App startup and permission state
- `PHONE_STATE` transitions and incoming-call detection
- Delayed speaker-routing trigger
- AudioManager request and confirmation
- Shizuku permission, binder lifecycle, UserService binding, route result
- Root backend execution/result
- Accessibility service lifecycle and speaker button discovery/click result
- Final backend and failure information

## Build

```bash
gradle --no-daemon :app:assembleDebug
```

GitHub Actions uploads the debug APK as the `AutoSpeaker-debug` artifact.
