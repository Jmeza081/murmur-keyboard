# Design gaps: keyboard vs. mockups

Phase 6, step 1. Each gap names the step that fixes it. Compared against `design/mockups.html` (dark frames).

## Typing view (dark, empty field) — screenshot 2026-11-11

| # | Area | Now | Mockup | Step |
|---|---|---|---|---|
| 1 | Strip, empty field | Blank strip with a chevron in a filled circle; tools hidden behind it | Tools row shown directly: clipboard, emoji, split, settings, plain stroke icons | 4 |
| 2 | Strip, typing | (not captured) | Three suggestions, middle one bold, thin separators, plain grid icon on the left | 4 |
| 3 | Mic pill | Fills the strip's full height; light icon on a light accent | 56 × 40 dp with breathing room; dark icon (`#0D1336`) on the light accent in dark mode | 3, 4 |
| 4 | Enter key | Light icon on the light accent: low contrast | Dark icon on the accent in dark mode, white in light mode | 2, 3 |
| 5 | Key hints | Symbol hints on every letter (`% \ \| = [ ]`…), plus duplicate tiny digits on the number row | Only digit hints on q–p, and none when the number row is on | 3 |
| 6 | Function keys | Shift and backspace use the letter-key color | Shift, backspace, ?123, comma and period all use the darker function-key color | 3 |
| 7 | Space bar | Blank | "English" label in the hint color | 3 |
| 8 | Key shape | Flat keys, borders only | 8 dp radius with a 1 dp bottom shadow | 3 |
| 9 | Icons | HeliBoard's filled shift and backspace | Stroke icons (1.8 px) matching the rest of the mockups | 3 |
| 10 | Type | System font | Roboto Flex, letter labels ~21 px on 46 dp keys | 2, 3 |
| 11 | Below the keys | Keyboard-switch grid and hide chevron | Gesture bar only | System (see note) |

**Note on 11:** Android draws the keyboard-switch and hide buttons below a keyboard when gesture navigation is on and more than one keyboard is enabled. Murmur can't remove them. Disabling unused keyboards (Samsung, Gboard, the debug builds) under Settings › Keyboard list makes the switch button go away.

## Typing with suggestions, clipboard, listening (dark) — screenshots 2026-11-11

| # | Area | Now | Mockup | Step |
|---|---|---|---|---|
| 12 | Strip, typing | Three suggestions, middle one bold with "•••", chevron in a filled circle on the left | No dots under the middle word; plain grid icon instead of the filled-circle chevron | 4 |
| 13 | Emoji access | No emoji key or toolbar button by default | Emoji in the tools row, and on long-press of the comma | 4 |
| 14 | Toolbar | Expands to gear and clipboard only, with a back chevron in a filled circle | Tools row: clipboard, emoji, split, settings; plain icons | 4, 6 |
| 15 | Clipboard panel | Row of 11 editing icons (clear, arrows, undo, cut, copy, paste, select, close), shrug face when empty, ABC / space / backspace row at the bottom | Strip shows ABC, "Clipboard" and Clear; "Pinned" and "Recent" sections of 2-column cards with source and age; friendly empty state | 5 |
| 16 | Listening panel | Toolbar icons stay visible in the strip above; Cancel sits on top of Android's keyboard-switch button | Strip shows "Listening…" and the keyboard icon; Cancel and Stop clear of the bottom edge | 5 |
| 17 | Listening panel | No live words; waveform fine; chip and timer fine | Live words in grey in the field; same chip and timer | 5 (live words later) |
| 18 | Settings defaults | Changed defaults only apply to settings never touched before | Murmur owns the strip, tools and panels, so they don't depend on old HeliBoard settings | 6 |

**Theme note:** the dark screenshots are Murmur dark. "Colors" in Appearance is the daytime theme; with the phone in dark mode, "Colors (night)" applies.

## Still to capture

The emoji panel and the light theme.
