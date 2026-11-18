# Murmur

## Installing on the phone

The phone runs the release app, `dev.murmur.keyboard`. Its keyboard and its power-button assistant both point there.

- To put a change on the phone, run `./gradlew :app:installRelease`. It updates the real app in place and keeps its settings.
- Do not use `./gradlew :app:installDebug` for this. It installs a separate app, "Murmur Dev" (`dev.murmur.keyboard.debug`), with its own settings. The keyboard and power button never use it, so the change looks like it did nothing.
- If unsure which app is in use, check before installing:
  `adb shell settings get secure default_input_method` and `adb shell settings get secure voice_interaction_service`.
- Remove a stray debug copy with `adb uninstall dev.murmur.keyboard.debug`.
