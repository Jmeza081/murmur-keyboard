# Murmur: Plan

Name: **Murmur** (repo: `murmur-keyboard`).

A personal Android keyboard with on-device dictation using the same speech model as FluidVoice, plus a power-button dictation shortcut. It's built for one user and installed directly from the APK, not through the Play Store.

## Goals

1. A full keyboard: emoji, clipboard history, swipe typing, and a split layout on tablets.
2. A dictation key that transcribes on the device with NVIDIA Parakeet TDT v3, the model FluidVoice uses.
3. Optional text cleanup: remove filler words, fix self-corrections, tidy formatting.
4. Holding the power button starts dictation from anywhere. The text goes into the focused field, or to the clipboard if no field has focus.
5. A settings app for theme, personal dictionary, dictation options and model management.

## Non-goals (for now)

- Play Store release and its policy review.
- iOS.
- Cloud transcription. Everything stays on the device.
- GIF search (GIPHY or Tenor). Later, optional: it needs an API key, attribution and network access.
- Reusing FluidVoice's code. It's Swift and CoreML on macOS, so only the model carries over.

## Key decisions

| Area | Decision | Why |
|---|---|---|
| Keyboard base | Fork **HeliBoard** (GPL-3.0, AOSP LatinIME lineage) | Already has emoji, clipboard history, split keyboard, themes and a dictionary. Verify the current feature set before forking. |
| Speech engine | **sherpa-onnx** running Parakeet TDT 0.6B v3 (INT8 ONNX) with Silero VAD | A proven Android route; see `mtib/android-local-transcribe`. Model is about 640 MB. |
| Cleanup | Stage 1: rules. Stage 2: a small on-device LLM (llama.cpp with a ~1B model, or Gemini Nano through ML Kit GenAI if the phone supports it) | Rules cover most filler words. Use the LLM only if latency and RAM allow. |
| Power-button trigger | Register as the default digital assistant through `VoiceInteractionService` | Officially supported. Replaces Google Assistant on the long-press. |
| Text injection | Order: (1) commit through the active IME, (2) `AccessibilityService` `ACTION_SET_TEXT` or paste, (3) copy to the clipboard and show a toast | Covers every case. Accessibility is fine because the app is sideloaded. |
| Language | Kotlin. Native code only through sherpa-onnx and llama.cpp | Matches HeliBoard and the Android tooling. |
| License | GPL-3.0, inherited from HeliBoard | Fine for personal use. Check FluidVoice's license only if its code is copied, which the plan avoids. |

## Known risks

- **Swipe typing.** HeliBoard's gesture typing needs a closed-source Google library (`libjni_latinime`) that the user supplies. Sideloading it is fine for personal use. A fully open replacement is a large project.
- **Memory and latency.** The 640 MB speech model plus an LLM may be too heavy for mid-range phones. Measure on your own phone early.
- **Assistant APIs.** `VoiceInteractionService` is poorly documented. Samsung's side key has its own setting, which must be set to "Digital assistant."
- **Storage.** Install the model on first launch (download or import a file) rather than bundling it in the APK.

## Phases

Each phase ends with something installed and usable on your phone, and with the README updated. From Phase 2 on, each phase replaces the matching mockup GIF in the README with a real screen recording (`adb shell screenrecord`, converted to GIF), so the README shows what has actually been built.

| Phase | Name | Outcome |
|---|---|---|
| 0 | Spike (1–2 days) | Parakeet runs fast enough on your phone |
| 1 | Repo and fork | Your fork is your daily keyboard; README shows the mockup GIFs |
| 2 | Dictation key | Dictate into any app from the keyboard |
| 3 | Power button | Hold power, speak, release: text lands where you're typing |
| 4 | Cleanup | Filler words gone with no noticeable delay |
| 5 | Keyboard extras | Emoji, clipboard and swipe match what you use in SwiftKey |
| 6 | Settings app | Everything configurable without rebuilding |

### Phase 0: Spike (1–2 days)
- Build and install `mtib/android-local-transcribe` unchanged. Measure transcription speed, RAM and accuracy on your phone.
- Build and install HeliBoard unchanged. Confirm the split keyboard on a tablet or a large emulator.
- **Exit:** both run, and Parakeet's speed on your phone is acceptable. If it isn't, try a smaller model before going further.
- **Result (2026-11-11): passed.** Target device: Galaxy Z Fold 8 (SM-F971U, Snapdragon SM8850, Android 17, ~12 GB RAM).
  - Parakeet TDT v3 (INT8, sherpa-onnx 1.13.3): accurate, with text appearing promptly after stopping. About 1.4 GB memory with the model loaded, and about 1.4 GB storage. Exact timings get logged in Phase 2.
  - HeliBoard (4.3-beta1, debug build): works well as the daily keyboard, including the split layout on the inner screen.
  - Build notes: HeliBoard uses Gradle 9.7 and NDK 28. The test app's Gradle 8.11 needs JDK 17, not Android Studio's bundled Java 25.

### Phase 1: Repo and fork
- The repo is already set up with git, `.gitignore`, the README and the mockup GIFs (`docs/media/`, built by `design/capture/`).
- Fork HeliBoard into `app/`. Rename the package and app ID so it installs alongside HeliBoard.
- Set up a release signing key (kept out of git), a build script and a one-command install (`./gradlew installRelease`).
- Fill in the README's build and install section.
- **Exit:** your fork installs and works as your daily keyboard, and the README renders with every GIF.
- **Result (2026-11-11): passed.** HeliBoard 4.3-beta1 (`3e91b4f`) vendored as one commit; Murmur changes are in a separate commit on top, so `git diff` against the vendor commit shows every change. App ID `dev.murmur.keyboard`, release key in `~/.murmur/`.

### Phase 2: Dictation key
- Add a `speech` module wrapping sherpa-onnx: load the model, record with VAD, transcribe.
- Add the mic key to the strip and the listening panel (level meter, cancel, stop).
- Commit the transcribed text through `InputConnection.commitText`.
- Model manager: import or download the model, check its checksum, show its storage size.
- README: replace `dictation.gif` with a recording.
- **Exit:** you can dictate into any app with the keyboard.

### Phase 3: Power-button dictation
- Add `VoiceInteractionService`, `VoiceInteractionSessionService` and the listening sheet.
- Delivery order: the active keyboard (through a bound service in the same app), then the accessibility service, then the clipboard.
- Add an optional `AccessibilityService` for text fields that aren't using your keyboard.
- README: replace `power-button.gif` with a recording.
- **Exit:** holding the power button, speaking and releasing puts the text where you're typing.

### Phase 4: Cleanup
- Rules: a filler-word list, collapsed repeats, simple handling of "scratch that" and "I mean," spacing and capitalization fixes, and dictionary replacements.
- Optional LLM pass behind a setting, with a strict timeout that falls back to the rule output.
- The "Cleaned up" preview with Undo cleanup.
- **Exit:** cleaned text arrives with no noticeable extra delay.

### Phase 5: Keyboard extras
- Review clipboard history and emoji (HeliBoard has them). Fill any gaps against SwiftKey, such as pinned clips and the 1-hour expiry.
- Swipe typing: sideload the Google library, or put it off.
- Check the tablet split layout against the mockup.
- README: replace `keyboard.gif` and `panels.gif` with recordings.
- **Exit:** feature parity with what you use in SwiftKey, minus GIF search.

### Phase 6: Settings app
- Setup checklist, then settings for dictation (model, cleanup level, language, silence timeout), power button, theme, typing, dictionary and clipboard.
- Backup and restore of settings and the dictionary to a file.
- README: replace `settings.gif` with a recording.
- **Exit:** everything can be configured without rebuilding.

## Open questions

- Hold-to-talk or tap-to-start/tap-to-stop for the power-button flow?
- Languages: English only, or Spanish too? Parakeet v3 covers about 25 European languages.

## References

- Mockups: https://claude.ai/artifact/3c7LcyVueGcn2Z2qZ5Qbam (source: `design/mockups.html`)

- FluidVoice: https://github.com/altic-dev/FluidVoice
- Parakeet TDT v3: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- Example Android app: https://github.com/mtib/android-local-transcribe
- HeliBoard: https://github.com/Helium314/HeliBoard
- VoiceInteractionService: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
