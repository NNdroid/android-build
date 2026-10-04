# android-build

rk3528-hinlink-ht2

## AutoSpeaker (vivo X60 / OriginOS)

Experimental incoming-call AutoSpeaker app lives in `autospeaker/`.

When an incoming call is manually answered, it tries speaker routing in this order:

1. Android `AudioManager`
2. Shizuku UserService (shell/root identity)
3. Root `su` + `app_process`
4. Accessibility fallback that clicks the visible speakerphone control

GitHub Actions builds `AutoSpeaker-debug` for pull requests and `main`.
