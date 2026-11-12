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
| Cleanup | Rules only (`TextCleanup`) | Measured in Phase 4: Gemini Nano is blocked for keyboards, and a 1B model added ~1.2 s for one real fix in ten. |
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

Each phase ends with something installed and usable on your phone, and with the README updated.  The README shows the mockup GIFs from `design/mockups.html` throughout; they're rebuilt with `design/capture/` when the mockups change.

| Phase | Name | Outcome |
|---|---|---|
| 0 | Spike (1–2 days) | Parakeet runs fast enough on your phone |
| 1 | Repo and fork | Your fork is your daily keyboard; README shows the mockup GIFs |
| 2 | Dictation key | Dictate into any app from the keyboard |
| 3 | Power button | Hold power, speak, release: text lands where you're typing |
| 4 | Cleanup | Filler words gone with no noticeable delay |
| 5 | Keyboard extras | Emoji, clipboard and swipe match what you use in SwiftKey |
| 6 | Apply the design | The keyboard looks and behaves like the mockups; HeliBoard's extra features and settings are gone |
| 7 | Settings app | Everything configurable without rebuilding, built to the mockups |
| 8 | Bugs and refinements | Known rough edges fixed, measured on the phone |

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
- **Exit:** you can dictate into any app with the keyboard.
- **Result (2026-11-11): passed.** Tested on the release build on the Fold 8: model download through the new screen, then dictation into Notes. Model load 1.7 s (in the background when the keyboard opens); 7.8 s of speech transcribed in 319 ms (RTF 0.04).
  - The mic key is HeliBoard's toolbar voice key, now dictating in place. The listening panel is pinned to the keyboard's height.
  - Not done yet: live grey words in the field while listening, and the prominent mic pill from the mockup. Both are polish for later.

### Phase 3: Power-button dictation
- Add `VoiceInteractionService`, `VoiceInteractionSessionService` and the listening sheet.
- Delivery order: the active keyboard (through a bound service in the same app), then the accessibility service, then the clipboard.
- Add an optional `AccessibilityService` for text fields that aren't using your keyboard.
- **Exit:** holding the power button, speaking and releasing puts the text where you're typing.
- **Result (2026-11-11): passed.** Murmur is the digital assistant; holding the side button opens the listening sheet over any app. Text goes into the focused field through Murmur's keyboard, else through the optional accessibility service (other keyboards), else to the clipboard. Tested in Notes, Amazon and with Samsung Keyboard.
  - Deviation: it stops when you pause or tap Stop, not when you release the button. Android doesn't tell an assistant when the side button is released. Hold-to-talk on volume-down was offered and declined for now.
  - The sheet takes window focus, so it closes first and types once the app's field regains focus (within 2 s, else clipboard).
  - Murmur also provides Android's speech recognizer (required for an assistant), backed by the same on-device model.

### Phase 4: Cleanup
- Rules: a filler-word list, collapsed repeats, simple handling of "scratch that" and "I mean," spacing and capitalization fixes, and dictionary replacements.
- Optional LLM pass behind a setting, with a strict timeout that falls back to the rule output.
- The "Cleaned up" preview with Undo cleanup.
- **Exit:** cleaned text arrives with no noticeable extra delay.
- **Result (2026-11-11): passed, rules only.** Light cleanup (`TextCleanup`, 13 unit tests) removes fillers, stutters and restarted phrases, and applies "scratch that" and short corrections ("5, I mean 6", "Wednesday, wait no, on Thursday"). Instant. Cleanup level is Off or Light on the Dictation screen.
  - **Gemini Nano (ML Kit Prompt API) can't be used:** AICore rejects calls from a keyboard or assistant sheet with "Background usage is blocked" (error 30). It only serves an app whose own screen is in front.
  - **llama.cpp + Gemma 3 1B (Q4_K_M) was built and measured on the Fold 8:** 0.8–1.7 s per dictation (same at 6 or 8 threads), 1.4 GB memory with Parakeet. Unguarded it changed the meaning in 3 of 10 test dictations (dropped "you", "2" to "two"); behind a strict guard it made one real fix in ten, a restarted phrase. That fix is now a rule, so the model was removed.
  - Revisit if AICore opens to keyboards, or a much faster small model appears. The removed code is in git history (`1d7bf53` and its parent).

### Phase 5: Keyboard extras
- Review clipboard history and emoji (HeliBoard has them). Fill any gaps against SwiftKey, such as pinned clips and the 1-hour expiry.
- Swipe typing: sideload the Google library, or put it off.
- Check the tablet split layout against the mockup.
- **Exit:** feature parity with what you use in SwiftKey, minus GIF search.
- **Result (2026-11-11): passed.** Number row on by default; space-bar cursor control and pinned clips were already in HeliBoard. Clips Android marks sensitive (passwords, one-time codes) are no longer saved, and clips are kept for 60 minutes (was 10).
  - Swipe typing uses Google's closed `libjni_latinime.so` (arm64, SHA-256 `b1049983…754f`, the checksum HeliBoard expects), loaded once under Settings › Advanced. It is never committed.
  - Emoji prediction uses HeliBoard's `emoji_en.dict` (Settings › Dictionaries › Add dictionary from file). Emoji compete with words for the 3 strip slots, so they rarely show without expanding the strip; Phase 6 reserves a slot.

### Phase 6: Apply the design
Keep HeliBoard's engine (input logic, autocorrect, dictionaries, swipe, layouts) and replace everything you see and touch with Murmur's own, built from `design/mockups.html`. Remove what the mockups don't have.

**Done so far:** Murmur light and dark color themes as defaults, rounded keys with borders (`00f1054`); the mic pill at the right edge of the strip (`8ef71f7`).

Steps, each installed and compared against the mockups before the next:
1. **Gap list.** Screenshots of the keyboard, strip, panels and sheet next to the mockup frames; every visible difference listed and assigned to a step below.
2. **Design tokens.** One Kotlin source of truth for colors, radii, spacing, type sizes and motion from the mockups, used by every Murmur view. The bundled font (Roboto Flex) loads through it.
3. **Keys.** A Murmur key renderer: key size, gap, 8 dp radius and 1 dp bottom shadow, label type, number hints, function-key and Enter styling, shift and backspace stroke icons, key preview popup.
4. **Strip.** 48 dp strip with three suggestions, the middle one bold, thin separators; the mic pill styled as in the mockup; tools row when the field is empty (clipboard, emoji, split, settings); one slot reserved for the best emoji suggestion, as SwiftKey does.
5. **Panels.** Listening panel, emoji panel (search, recents, category chips), clipboard panel (pinned and recent cards) and the power-button sheet, matched to the mockups.
6. **Strip the bloat.** Remove what the mockups don't have and set Murmur's defaults. Code that only existed for removed features is deleted, not just hidden. Decisions (2026-11-11):
   - **HeliBoard's settings:** off the gear and the launcher; one "Advanced (temporary)" row on Murmur's screen until Phase 7 replaces it. One launcher icon, "Murmur".
   - **Delete:** toolbar customizing (key picker, pinned keys, custom codes, toolbar modes), layout editors (custom layouts, custom icons, custom currency key), gesture data gathering, the D-pad key and touchpad mode. **Keep** one-handed and floating modes.
   - **Feedback:** light haptics, no key sound. Key popup on press: on.
   - **Gestures on by default:** swipe space to move the cursor, swipe left from backspace to delete words, long-press space for the keyboard picker.
   - **Autocorrect:** "Aggressive" (0.7), raised from HeliBoard's "Modest" after typo feedback.
   - **Suggestions:** learn from typing (on the phone only), installed app names, just-copied text as a paste chip; contacts off. Offensive words allowed.
   - **Languages:** English and Spanish in one keyboard (multilingual typing, no switching); emoji skin tone default yellow.
   - **Split (step 7):** on the inner screen in landscape only.
7. **Tablet split.** The split layout with the mic in the middle, per the mockup.
8. **Motion and polish.** 200 ms ease-out for the panel swap and sheet, no animation on key presses, reduced-motion respected; light and dark both checked; touch targets at least 44 dp.

- **Exit:** side by side with the mockups at phone and tablet sizes, in light and dark, the keyboard matches, and nothing on screen comes from a feature the mockups don't have.

### Phase 7: Settings app
- Setup checklist, then settings for dictation (model, cleanup level, language, silence timeout), power button, theme, typing, dictionary and clipboard.
- Backup and restore of settings and the dictionary to a file.
- **Exit:** everything can be configured without rebuilding.

### Phase 8: Bugs and refinements
Smaller fixes collected along the way, each measured on the Fold before and after.
- **Emoji and clipboard panels overlap Android's keyboard-switch and hide buttons.** The main keyboard leaves about 164 px under its keys on the Fold 8; HeliBoard's bottom padding explains only ~33 px of it, and the rest matches the 126 px IME navigation area. The panels' bottom row is a separate one-row keyboard that doesn't get the same space. A first fix (main-keyboard bottom padding on the bottom row) wasn't enough. Next: measure the real view bounds on the phone (layout inspector or a debug overlay) and give the panels the same gap as the main keyboard.
- **Listening panel:** check that Cancel and Stop clear the same buttons (now padded by the main keyboard's measured gap below its keys).
- Live words in grey in the field while listening (mockup), not yet built.
- **Exit:** every item verified on the phone in light and dark.

## Open questions

- None right now. (Languages: English and Spanish, decided in Phase 6 step 6.)

## References

- Mockups: https://claude.ai/artifact/3c7LcyVueGcn2Z2qZ5Qbam (source: `design/mockups.html`)

- FluidVoice: https://github.com/altic-dev/FluidVoice
- Parakeet TDT v3: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- Example Android app: https://github.com/mtib/android-local-transcribe
- HeliBoard: https://github.com/Helium314/HeliBoard
- VoiceInteractionService: https://developer.android.com/reference/android/service/voice/VoiceInteractionService
