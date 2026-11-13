# Filler words and disfluency cleanup: an inventory for Murmur

This is a reference for rule-based cleanup of English dictation after ASR. Each group below can become one rule with one safety level.

**Safety tags**
- **SAFE**: delete on every match. Missing a real-content use is very unlikely.
- **CONTEXT**: delete only when the listed heuristic fires. Otherwise keep the word.
- **KEEP**: never delete by default. Removing it changes the meaning. Can be offered as an opt-in "aggressive" mode.

**How I sourced it.** I verified the primary sources marked [V] in this session. I cite the rest from the established literature; those are marked [L]. The heuristics are my synthesis, built for dictation (one speaker, writing to be read). They are not taken from conversational-transcript conventions.

---

## 0. Background: the model every rule follows

- **Disfluency structure** (Shriberg 1994, after Levelt 1983). The pattern is `[reparandum] + {interregnum / editing term} repair`. The *reparandum* is what gets thrown away. The *interruption point* (+) is where the speaker stops. The optional *interregnum* holds filled pauses or editing terms. The *repair* replaces the reparandum. Reparanda are usually short, about 1–3 words. [L] Shriberg, *Preliminaries to a Theory of Speech Disfluencies*, PhD thesis, UC Berkeley, 1994. Record: https://bibbase.org/network/publication/shriberg-preliminariestoatheoryofspeechdisfluencies-1994. Summary of the structure: https://arxiv.org/pdf/2201.05041
  - Example: `Let's meet [Tuesday] + {uh, I mean} Wednesday` becomes `Let's meet Wednesday`.
- **Switchboard stylebook classes** (Meteer et al. 1995, rev. Taylor) [V]: https://www.cs.brandeis.edu/~cs140b/CS140b_docs/DyfluencyGuide.pdf
  - `{F ...}` **filled pauses**: "unrestricted distribution and no semantic content" (uh, um, huh). It notes "**uh-huh** (whether spelled as one word or two) is **not** a filler, but *huh* is."
  - `{D ...}` **discourse markers**: well, like, you know, I mean, so, actually, now, see, anyway.
  - `{E ...}` **explicit editing terms**: these "have some semantic content (e.g. *I mean, sorry, excuse me*) and usually occur between the restart and the repair."
  - `{C ...}` coordinating conjunctions used as turn glue: "and", "but", "so" at the start of an utterance.
  - `{A ...}` asides.
  - `[ RM + RR ]` restarts and repairs. `-/` marks an abandoned sentence.
- **Treebank addendum** (Taylor 1996): most discourse markers, like fillers, are labelled INTJ, "including the extremely common *well* and *like*, but also occasional others like *now, see, say, actually, anyway*." *You know* is labelled PRN (parenthetical), with its commas. [V via search excerpt] https://tekstlab.uio.no/LIA/pdf/BracketingSwitchboard.pdf
- **Frequencies** (Shriberg): filled pauses are the most common disfluency type and repetitions come second, across domains. Disfluencies hit about 5–10% of words in natural conversation. [V via abstracts] https://www.sri.com/publication/speech-natural-language-pubs/disfluencies-in-switchboard/ , https://sri.com/wp-content/uploads/2021/12/to_errr_is_human.pdf
- **uh vs um carry signal** (Clark & Fox Tree 2002, *Cognition* 84:73–111). They are conventional words: *uh* signals a minor delay coming and *um* a major one. In *transcribed dictation* they add nothing to the written text, so deleting them is safe. [V] https://gwern.net/doc/psychology/linguistics/2002-clark-2.pdf. Survey: Dinkar, Clavel & Vasilescu 2023, https://arxiv.org/abs/2301.10761
- **Product precedent.** Commercial tools only auto-strip the "uh/um" class. Lexical fillers are flagged for review or handled by an LLM.
  - Deepgram removes only `uh`, `um` by default. With `filler_words=true` it keeps `uh, um, mhmm, mm-mm, uh-uh, uh-huh, nuh-uh`. It always uses the canonical spelling ("uh", never "uhhhh"). [V] https://developers.deepgram.com/docs/filler-words
  - AssemblyAI has `disfluencies` (bool, default false), which keeps "um", "uh", etc. [V] https://www.assemblyai.com/docs/pre-recorded-audio/include-filler-words.md
  - Wispr Flow "removes fillers such as 'um,' false starts and repetitions unless they add clear meaning". It may drop a leading "so" or deliberate emphasis, which is a documented over-deletion. [V] https://docs.wisprflow.ai/articles/6568835559-why-wispr-flow-sometimes-removes-or-changes-words-on-android-smart-formatting
  - Wispr Backtrack triggers are "actually", "scratch that", "never mind", and restating. "2 actually 3" becomes "3", while "I actually enjoyed the movie" is preserved. Ambiguous corrections keep both versions. [V] https://docs.wisprflow.ai/articles/5373093536-how-do-i-use-smart-formatting-backtrack
  - Dragon has no automatic filler removal. "Scratch that" is a *command* that erases the last utterance, and "scratch word" erases the last word. [V] https://nuance.com/products/help/dragon/dragon-for-mac/enx/Content/Correction/TakeBack.htm , https://www.nuance.com/products/help/dragon/dragon-for-mac6/enx/Content/Correction/RevisingText.htm
  - Rev "clean verbatim" removes stutters, filler words like "um" and "like", and false starts. Full verbatim keeps "you know", "like", false starts and stutters. [V] https://www.rev.com/blog/resources/what-does-clean-verbatim-transcription-mean
  - The Tella guide says hesitation sounds are the safest to auto-remove. "Like" and "so" should be reviewed one by one. [V via search] https://www.tella.com/edit-with-ai/remove-filler-words.md
  - Otter: I found no official doc of a filler-strip setting. Treat it as unknown.

**Design consequence.** Make regex own the SAFE tier and the few high-precision CONTEXT patterns. Send everything else to the Claude cleanup pass, or leave it alone.

---

## 1. Filled pauses (non-lexical fillers)

ASR engines differ in spelling. Whisper and Apple/Google emit literal variants ("Umm", "Uhh", "Hmm."). Deepgram normalizes. Some engines drop fillers entirely. Match case-insensitively, at word boundaries, with optional trailing punctuation and elongation.

### 1a. SAFE: always delete
```
um, umm, ummm, um-m, uhm, uhmm, ehm, emm
uh, uhh, uhhh, uh-h
er, err, errr          # British-style; ASR may write "er" for "uh"
erm, errm, ermm        # British "um"
ah, ahh                # ONLY when standalone filler; see 1b
eh                     # ONLY mid-sentence with commas; see 1b
mm, mmm, mmmm          # hesitation hum (NOT "mm-hmm")
hm, hmm, hmmm          # ONLY sentence-initial thinking hum; see 1b
uh-uh-uh / um-um       # chained fillers
```
Elongation regex: `\b(u+h*m+|u+h+|e+r+m*|e+h*m+|m{2,}|h+m+)\b` (case-insensitive). Build it with a whitelist and test it. Note that `\bum\b` is safe in English. "Um" as a word only appears in proper nouns like "UM" (University of Michigan), so skip all-caps tokens.

Cleanup after deleting:
- Collapse the leftover punctuation: ", ," becomes ",", and ", ." becomes ".".
- Drop a leading comma.
- Re-capitalize the first letter of the sentence: "Um, so we..." becomes "So we...".

### 1b. CONTEXT: interjections that can carry meaning
| Token | Filler when | Content when | Heuristic |
|---|---|---|---|
| `ah`, `ahh` | Mid-sentence and comma-wrapped: "it's, ah, Tuesday" | Sentence-initial realization: "Ah, I see." "Ah! There it is." | Delete if preceded by a comma or word AND followed by a comma. Keep if sentence-initial or followed by `!`. |
| `oh` | Rarely a filler: "it was, oh, about five" (approximation) | Usually content: "Oh no", "Oh, I forgot", "zero" in numbers ("oh-two") | **KEEP by default**. Only delete the mid-sentence `, oh,` before a number. |
| `hmm`, `hm` | Thinking at the start of an utterance: "Hmm, let me think" | Reply meaning doubt: "Hmm." on its own; "hmm?" | Delete if sentence-initial and followed by a comma plus more words. Keep if it is the whole utterance or ends with `?`. |
| `eh` | Mid-sentence hesitation | Tag/opinion: "pretty good, eh?", "Eh, I don't care" (dismissive) | Keep if followed by `?` or sentence-initial. Otherwise delete. |
| `huh` | Filler per the Switchboard stylebook | Question tag or surprise: "Huh?", "weird, huh?" | Keep if followed by `?` or at the end of a sentence. Delete only mid-sentence `, huh,`. |
| `uh-huh`, `mhm`, `mm-hmm`, `mhmm`, `uh huh` | Backchannel (not a filler per Meteer et al.) | Means **yes** | **KEEP**. In dictation it is usually an answer ("Uh-huh, that works"). |
| `uh-uh`, `mm-mm`, `nuh-uh`, `unh-unh` | — | Means **no** | **KEEP**. Never confuse it with "uh uh" (a repeated filler). The hyphen matters. Delete "uh uh" without a hyphen. Keep "uh-uh". |
| `ooh`, `ow`, `ugh`, `whoa`, `wow`, `yay`, `aw` | — | Emotive interjections | **KEEP** |
| `ahem` | Throat-clearing transcribed | Sometimes deliberate/ironic | CONTEXT: delete when standalone |

---

## 2. Discourse markers / lexical fillers

These are real words with real meanings. Remove them only with a positional and punctuation heuristic. Fox Tree & Schrock 2002 argue each has one *basic meaning*: *you know* invites the listener to infer, and *I mean* warns that an adjustment is coming. So they are not random noise, but in written dictation the function is usually redundant. [V abstract] https://www.maryvillecollege.edu/wp-content/uploads/Faculty/BehavioralSciences/cschrock/Fox-Tree-and-Schrock-2002.pdf

General signals, strongest first:
1. **Comma-wrapped**: `, X,`. ASR punctuation models often put a comma around a parenthetical filler.
2. **Sentence-initial plus comma**: `^X,`.
3. **Directly before another filler** ("like, um, ...") or a repetition.
4. **The sentence stays grammatical with X removed**, and X is not the head of a phrase. Regex can't check this, so leave it to the LLM pass.

### 2a. "like"
Functions per sociolinguistics (D'Arcy 2017, *Discourse-pragmatic Variation in Context: 800 years of LIKE*; Tagliamonte & D'Arcy 2004 on quotative *be like*) [L]. Thesis summarizing the functions (gap-filling, quotative, approximative, metalinguistic): https://epublications.vu.lt/object/elaba:210542094/210542094.pdf [V abstract]

| Use | Example | Action |
|---|---|---|
| Verb | "I like it", "like the post" | KEEP |
| Preposition / comparison | "looks like rain", "a car like mine", "just like that" | KEEP |
| Conjunction | "like I said" | KEEP |
| Quotative (`be like`, `was like`, `were like`, `'s like`, `goes like`) | "She was like, 'no way.'" | KEEP (it marks a quote; removing it breaks the sentence) |
| Approximator before a number | "like 20 people", "like five minutes" | KEEP. It means "about". Optionally rewrite to "about" in a formal style. |
| Suffix/compound | "childlike", "the likes of", "likely" | KEEP (word boundary handles this) |
| Discourse filler, comma-wrapped | "It was, like, really good." | **CONTEXT → delete** |
| Sentence-initial filler | "Like, I don't even know." | **CONTEXT → delete** |
| Focuser before an adjective/verb without commas | "it was like really good" | CONTEXT: delete only if `like` is directly preceded by `was/is/'s/are/were/be` AND followed by an intensifier (`really, so, totally, super, very`). The negative-lookahead below handles quotatives. |

Regex sketch:
- delete `(?<=,\s)like,\s` (comma-wrapped)
- delete `^like,\s`
- never touch `\b(be|was|were|is|'s|am|are|goes|go|went)\s+like\b(?=\s*[,:"'])` (quotative)

### 2b. "you know"
| Use | Example | Action |
|---|---|---|
| Main verb plus object/clause | "You know the answer." "Do you know where it is?" "you know what I mean" when it is a real question | KEEP |
| "you know what?" (attention-getter) | "You know what? Let's go." | KEEP (it is rhetorical but intentional) |
| Parenthetical filler | "It's, you know, complicated." "We should, you know, ask her." | **CONTEXT → delete** if comma-wrapped |
| Utterance-final tag | "It's hard, you know." / "you know?" | CONTEXT: delete if final and preceded by a comma. Keep if it is followed by `?` and the sentence is a question to someone. Default to keep, because a final `you know?` in a message is tone. |
| Sentence-initial | "You know, I think we should go." | CONTEXT → delete if directly followed by a comma |

Heuristic: `you know` immediately followed by a noun phrase, a wh-word, `that`, `if`, or `how` is a verb, so KEEP. Comma-wrapped is DELETE.

### 2c. "I mean"
| Use | Example | Action |
|---|---|---|
| Literal "intend" | "I mean it." "That's not what I mean." "I mean to call her." | KEEP |
| Editing term inside a repair | "Tuesday, I mean Wednesday" | Handle as a repair (Section 3): delete the reparandum plus "I mean" |
| Sentence-initial elaboration | "I mean, it's fine, but..." | CONTEXT → delete if `^I mean,` |
| Parenthetical | "It's, I mean, not ideal." | CONTEXT → delete |

Heuristic: keep if followed by `it|that|to|what|this|business|well`. Delete if followed by a comma.

### 2d. "basically" / "literally" / "actually" / "honestly" / "seriously" / "totally" / "really" / "just"
These are adverbs with real meaning. As fillers they are emphasis that adds no information.

| Word | Content use | Filler use | Default |
|---|---|---|---|
| basically | "Basically, the plan is X" (= "in summary") – borderline | "It's basically, like, done" | CONTEXT: delete only if comma-wrapped mid-sentence |
| literally | "translate it literally" | "I literally died" (intensifier) | **KEEP**. Removing the intensifier changes the tone. Leave it to the user or LLM style. |
| actually | Contrast/correction: "It's actually free." "I actually enjoyed it." | "Actually, um, so..." | **KEEP** as a filler. But see Section 3: `X actually Y` is a **repair cue** when X and Y are the same type ("2 actually 3"). |
| honestly / seriously / truly / frankly | Stance | Habitual | KEEP |
| really / just / totally / pretty | Degree | Habitual | KEEP (meaning-bearing degree words; see Section 5) |

### 2e. "so"
| Use | Example | Action |
|---|---|---|
| Result connective | "It rained, so we stayed in." | KEEP |
| Intensifier | "so good", "so much" | KEEP |
| "so that" / "and so on" / "so-so" / "or so" | — | KEEP |
| Utterance-initial launch | "So, I was thinking we could..." "So I wanted to ask..." | CONTEXT. Wispr lists dropping a leading "so" as a known over-deletion. **Default KEEP**. Delete only `^So,\s+(um|uh|like|yeah|basically|anyway)` chains, or "Okay so", which is covered next. |
| Trailing "so..." | "I wasn't sure, so..." | KEEP (it implies something left unsaid) |

### 2f. "well"
| Use | Example | Action |
|---|---|---|
| Adverb/adjective/noun | "works well", "get well", "as well", "a well" | KEEP |
| Initial discourse marker | "Well, I don't know." | CONTEXT. Delete only in a chain: `^Well,\s+(um|uh)`. Otherwise keep, because it signals hedged disagreement. |
| Mid-sentence hesitation | "it's, well, complicated" | CONTEXT → delete if comma-wrapped |

### 2g. "right" / "okay" / "alright" / "yeah" / "now" / "anyway"
| Token | Filler pattern | Content | Default |
|---|---|---|---|
| right | Tag "..., right?" (seeks agreement); "Right, so..." (launch) | "turn right", "you're right", "right now", "the right one" | KEEP the tag `, right?` (it is a real question). CONTEXT: delete `^Right,\s+so\b` launches. |
| okay / OK / alright | "Okay so, ..." launch | "Is that okay?" "It's okay." Answer "Okay." | CONTEXT: delete `^(okay|ok|alright|all right)\s*,?\s+so\b` (keep "so" or drop both by style). Never delete a standalone "Okay." |
| yeah | "Yeah, so, ..." (launch) | Agreement | KEEP (it is an answer in replies) |
| now | "Now, the thing is" | Time | KEEP |
| anyway / anyways | Topic resumption | — | KEEP. Optionally normalize "anyways" to "anyway". |

### 2h. "kind of" / "sort of" / "kinda" / "sorta"
| Use | Example | Action |
|---|---|---|
| Noun + of (type) | "what kind of car", "a sort of tool", "all kinds of" | KEEP (preceded by a determiner: `a|the|what|this|that|some|any|every|which`) |
| Hedge (degree) | "it's kind of late", "I sort of agree" | **KEEP**: it softens a claim (Section 5) |
| Pure filler | "I was, kind of, like, going to" | CONTEXT: delete only if comma-wrapped |

### 2i. "you see" / "see" / "look" / "listen"
| Use | Example | Action |
|---|---|---|
| Perception verb | "Do you see it?" "See the attached." "Look at this." | KEEP |
| Initial discourse marker | "You see, the problem is..." "Look, I get it." | KEEP (rhetorical, the speaker's voice). CONTEXT-delete only when comma-wrapped mid-sentence: "the thing, you see, is..." |

### 2j. "I guess" / "I think" / "I suppose" / "I feel like"
**KEEP**. These are epistemic hedges and change commitment (Section 5). "I guess" can be a filler-ish tag ("it's fine, I guess"), but even there it conveys reluctance.

### 2k. General extenders (vague tags) — "or whatever", "and stuff", "and everything", "and things", "or something", "or anything", "and all that", "et cetera", "and so on", "and whatnot", "or something like that", "and all"
| Example | Content? | Default |
|---|---|---|
| "bring chips or something" | Yes: means "or a similar item". Removing it turns a loose suggestion into a precise one. | **KEEP** |
| "she was upset and stuff" | Weak; marks vagueness | KEEP (style) |
| "I'll call you or whatever" | Dismissive tone | KEEP |
| `and everything` utterance-final | Usually filler | CONTEXT (LLM only) |

Rationale: general extenders are a well-studied category that encodes approximation and set-marking (Overstreet 1999, *Whales, Candlelight, and Stuff Like That*) [L]. Regex cannot tell the difference reliably.

### 2l. Other lexical fillers seen in dictation
| Phrase | Default | Heuristic |
|---|---|---|
| "let me think" / "let me see" / "let's see" | CONTEXT | Delete if sentence-initial and followed by a comma or filler (a Wispr reviewer noted "let me think about this" slipped through: https://chrismenardtraining.com/post/wispr-flow-ai-dictation-removes-filler-words/) |
| "how do I put this" / "how should I say" / "what's the word" | CONTEXT | Delete as an interregnum when followed by content |
| "what do you call it" / "whatchamacallit" / "thingy" | KEEP | It is a placeholder for a missing word; deleting it loses the slot |
| "the thing is" | KEEP | Content framing |
| "at the end of the day", "to be honest" | KEEP | Style, not disfluency |
| "you know what I mean" / "know what I'm saying" (tag) | CONTEXT | Delete if utterance-final after a comma and not followed by `?` |
| "if that makes sense" | KEEP | Politeness hedge |

---

## 3. Editing terms and self-repair

### 3a. Structure
`[reparandum] + {editing term} repair`. Only delete the reparandum when a **repair follows** and it is **parallel** to the reparandum (same syntactic slot or type). Wispr's documented rules match this. A cue alone doesn't trigger removal, an unfinished fragment with no replacement stays, and ambiguous replacements keep both. (Sources: the Wispr docs above.)

Repair types (Levelt 1983; Shriberg 1994; Heeman & Allen 1999, https://www.cs.rochester.edu/research/cisd/pubs/1999/heeman-allen-cl99.pdf [L]):
- **Repetition**: reparandum = repair ("the the"). Section 4.
- **Substitution/replacement**: "at 2, no, 3". "send it to Bob, I mean Alice".
- **Insertion**: "we need the + the blue folder" (the repair adds words).
- **Deletion / fresh start**: "I was going to – can you call me?" The reparandum is abandoned with no parallel repair.

Noisy-channel and TAG disfluency models detect the reparandum by its alignment with the repair, through matching words or the same part of speech (Johnson & Charniak 2004, https://aclanthology.org/P04-1005/ [L]; Honnibal & Johnson 2014, https://aclanthology.org/Q14-1011/ [L]). For regex, approximate this as "same token class on both sides of the cue."

### 3b. Editing-term inventory
| Cue | Tag | Scope of deletion | Heuristic |
|---|---|---|---|
| `scratch that` | SAFE (as a command) | The previous clause or sentence, back to the last `.!?` or the start of the utterance | Dragon semantics: erase the last utterance. Delete the cue plus the prior clause. If nothing precedes it, delete just the cue. |
| `delete that`, `strike that`, `cancel that`, `forget that`, `never mind (that)`, `disregard that`, `ignore that` | CONTEXT | Same as "scratch that" | Only when the cue stands alone (its own clause, followed by `,.` or new content). "I'll never mind the cost" stays. "Ignore that email" stays (followed by a noun). |
| `no wait`, `wait no`, `wait,`, `no no`, `no,` | CONTEXT | The parallel phrase immediately before | Requires a parallel repair: "at 3, no wait, 4" becomes "at 4". "No, wait for me" stays. |
| `I mean` | CONTEXT | Parallel phrase before | "Bob, I mean Alice". Requires the same class on both sides (two capitalized names, two numbers, two times/days). |
| `sorry` | CONTEXT | Parallel phrase before | "the 15th, sorry, the 16th" becomes "the 16th". "Sorry I'm late" stays (sentence-initial, no parallel). |
| `or rather`, `rather` | CONTEXT | Parallel phrase before | "Monday, or rather Tuesday" becomes "Tuesday". *Arguably* keep it: in writing, "or rather" is a legitimate correction device. Default delete only if the user enables "apply corrections". |
| `actually` | CONTEXT | Parallel phrase before | Wispr's canonical case: "2 actually 3" becomes "3". Only when a number/time/day/name sits on both sides with no other words between. |
| `let me rephrase (that)`, `let me start over`, `let me try that again`, `start over` | CONTEXT | Whole previous sentence | Delete the cue plus the previous sentence when new content follows. |
| `what I meant was`, `what I mean is`, `I meant` | CONTEXT | Previous clause | "We need five – what I meant was six" becomes "We need six". Keep "what I meant was" if nothing parallel precedes it. |
| `excuse me`, `pardon me` | CONTEXT | Parallel phrase | Same as "sorry". Keep when it is addressed to someone ("Excuse me, can you..."). |
| `correction`, `make that`, `change that to` | CONTEXT | Parallel phrase | "send 10, make that 12" becomes "send 12" |
| `that is`, `i.e.`, `in other words` | KEEP | — | These are clarifications, not corrections |

### 3c. Parallel-class matchers (for repairs a regex can handle safely)
- Numbers: `\d+|one|two|...|twenty|hundred` (with optional units). Example: "5 no 6 people".
- Times: `\d{1,2}(:\d{2})?\s*(am|pm)?`, `noon`, `midnight`.
- Weekdays and months.
- Capitalized single names: low precision, leave to the LLM.
- Pronouns: "he, I mean she".

Everything else goes to the LLM. Shriberg's data say reparanda are short (1–3 words), so cap the regex deletion span at 3 tokens before the cue unless the cue is a whole-utterance command (`scratch that`, `let me start over`).

---

## 4. Repetitions, false starts, partial words

### 4a. Word repetitions
Most often these are repeated function words at the start of a phrase: pronouns, articles, prepositions, auxiliaries ("I I", "the the", "it's it's", "we we"). Plauché & Shriberg studied "I" and "the" repetitions specifically because they dominate. It is the *first* copy that is prosodically aberrant. [V abstract] https://www.sri.com/wp-content/uploads/2021/12/acoustic_properties_of_disfluent_repetitions.pdf

| Pattern | Tag | Rule |
|---|---|---|
| Repeated function word: `\b(I|the|a|an|to|of|and|but|in|on|it|it's|we|you|he|she|they|my|this|is|was|so|if|for|with|at)\s+\1\b` | SAFE | Collapse to one (case-insensitive; keep the second copy's case unless it is sentence-initial) |
| Repeated 2–3 word phrase: `\b(\w+ \w+)\s+\1\b` ("I think I think", "in the in the") | SAFE | Collapse |
| Triple-or-more repeat of any word: `\b(\w+)(\s+\1\b){2,}` | SAFE except for emphatic content words (see below) | Collapse to one, except in the emphatic list |
| Repeated content word ×2 ("very very", "really really", "no no", "bye bye", "so so") | KEEP | Deliberate intensification or reduplication. Wispr lists deliberate emphasis removal as a known error. |

### 4b. Grammatical doubles: NEVER collapse
| Double | Example | Why |
|---|---|---|
| `had had` | "She had had enough." | Past perfect of *have* https://en.wikipedia.org/wiki/James_while_John_had_had_had_had_had_had_had_had_had_had_had_a_better_effect_on_the_teacher |
| `that that` | "I know that that is wrong." | Complementizer + demonstrative |
| `is is` | "The problem is is that..." (double copula, widespread in speech) | https://en.wikipedia.org/wiki/Double_copula. Ungrammatical in writing, so **collapsing is the desired output**. Tag CONTEXT → collapse when `the (thing|problem|issue|point|question|reason) is is`. |
| `do do` | "What they do do is..." / "I do do that" | Emphatic *do* + main verb |
| `will will` | "Will Will come?" | Name. Skip if the second copy is capitalized mid-sentence. |
| `can can` | "you can can tomatoes" | Rare; verb "to can" |
| `in in`, `on on` | "log in in the morning", "turn on on Monday" | Particle + preposition |
| `there there` | "There there, it's ok." | Idiom |
| `so so` / `so-so` | "it was so-so" | Lexicalized |
| `bye bye`, `no no`, `yes yes`, `now now`, `well well`, `tut tut`, `knock knock`, `chop chop`, `night night`, `choo choo`, `ha ha` | — | Lexicalized reduplications |
| Contrastive focus reduplication | "I'll make the tuna salad and you make the salad-salad." "Is he LIKE-like you?" | https://en.wikipedia.org/wiki/Contrastive_focus_reduplication. Hyphenated or emphasized; keep hyphenated forms. |
| `what what` / `who who` | "Who's who", "what's what" | Idiom (only with 's) |
| `very very`, `really really`, `much much`, `many many`, `long long` | — | Intensification |
| Numbers `one one`, `five five` | "call 555 1212", "flight one one two" | Digit sequences. Never collapse number words. |

**Rule of thumb**: collapse repeats of pronouns, articles, prepositions, conjunctions, and contractions (`it's|I'm|we're|that's`), unless the pair is on the never-collapse list. Leave content-word and number-word repeats alone.

### 4c. Partial words (fragments) and false starts
- ASR rarely emits partial words. Whisper tends to complete or drop them. When they appear:
  - Hyphen- or dash-terminated: `\b\w{1,4}-\s` ("tr- try", "wh- what", "I w- I want"). Switchboard marks these as `tr-`.
  - **SAFE** if the next word starts with the same letters: `\b(\w{1,5})-\s+(?=\1)`. Delete the fragment.
  - Otherwise CONTEXT, because a hyphen can mean a compound or a list ("pre- and post-op", "first- and second-year"). Keep if followed by `and|or|to`.
  - Stutter spellings: "b-b-but", "I-I-I", "w-w-we". **SAFE**: `\b(\w)(-\1)+-?(?=\1)` becomes the full word.
- **Restarts / abandoned clauses** (`-/` in Switchboard): "I was going to – can you just call me?"
  - The ASR shows these as `--`, `—`, `…`, or nothing.
  - CONTEXT: if a dash or ellipsis is followed by a new capitalized clause, the fragment before it is a candidate for deletion. Only do this in the LLM pass. Regex has no idea whether the fragment carried information.
  - Rev clean verbatim removes false starts "when they are quickly reworded unless they add context." [V via search] https://www.rev.com/blog/resources/what-does-clean-verbatim-transcription-mean
- **Restart with repeated opener**: "We should – we should go" collapses to "We should go". This is the same as a phrase repeat across a dash: `\b((?:\w+\s){1,3})[—–-]+\s*\1` becomes `\1`. **SAFE**.

---

## 5. Hedges and softeners: KEEP by default

These change commitment, politeness, or truth conditions. Removing them makes the user sound more certain or blunt than they meant. Linguistics treats hedges as meaning-bearing (Lakoff 1973 "Hedges"; Hyland 1998) [L]. Product precedent: Wispr preserves "I actually enjoyed the movie" when it is not a correction.

```
KEEP: I think, I guess, I suppose, I believe, I feel like, I'd say, I reckon,
      maybe, perhaps, probably, possibly, likely, apparently, supposedly,
      kind of, sort of, kinda, sorta, a bit, a little, somewhat, fairly, pretty (degree),
      quite, rather (degree), almost, nearly, about, around, roughly, like + number (approx.),
      or so, or something, ish / -ish, more or less, in a way, to some extent,
      just (softener: "just checking in"), only, really, actually (contrast), honestly,
      not sure, I'm not sure but, if that makes sense, if you don't mind,
      I was wondering, could you, would you mind, no worries if not
      literally / seriously / totally / super (intensifiers – tone)
      the negations: not, never, no, don't, can't (obviously, but filler regexes must never touch "no" in "no wait" unless a repair follows)
```

Also KEEP:
- Answers and backchannels: `yes, yeah, yep, no, nope, uh-huh, mm-hmm, uh-uh, okay, sure, right` when they are a whole utterance or sentence-initial in a reply.
- Interjections with emotion: `oh, oh no, wow, ugh, oops, whoa, yay, aw, ouch, haha, lol`.
- Tags that are real questions: `, right?`, `, okay?`, `, yeah?`, `, you know?`, `, huh?`, `, eh?`.

---

## 6. ASR-specific artifacts

| Artifact | Example | Tag | Rule |
|---|---|---|---|
| Non-speech tags (brackets) | `[inaudible]`, `[INAUDIBLE]`, `[BLANK_AUDIO]`, `[Music]`, `[MUSIC PLAYING]`, `[Applause]`, `[Laughter]`, `[silence]`, `[noise]`, `[crosstalk]`, `[unintelligible]`, `[background noise]`, `[ Silence ]` | SAFE | `\[[^\]]{1,40}\]` delete. Whisper emits `[BLANK_AUDIO]`, `[Music]`, and similar; whisper.cpp prints `[BLANK_AUDIO]`. |
| Parenthetical sound descriptions | `(laughs)`, `(laughing)`, `(coughs)`, `(sighs)`, `(clears throat)`, `(upbeat music)`, `(inaudible)`, `(static)` | SAFE | Delete `\((?:laugh\w*|cough\w*|sigh\w*|clears throat|music|[a-z ]*music|inaudible|static|silence|noise|applause|chuckles?|giggles?|sniff\w*|breath\w*|exhales?|inhales?)\)`. Do not delete arbitrary parentheses, because users dictate real ones. |
| Music/emoji glyphs | `♪`, `♫`, `🎵` lines | SAFE | Delete |
| Hallucinated sign-offs on silence | "Thank you.", "Thanks for watching!", "Thank you for watching.", "Please subscribe", "Subtitles by the Amara.org community", "Sub by …", "you" (single token) | CONTEXT | Delete only when it is the **entire** transcript, or a trailing segment after a long silence (use VAD/segment timestamps). Never delete "thank you" mid-text. Sources: https://soniox.com/wiki/asr-hallucinations , https://community.openai.com/t/whisper-api-hallucinating-on-empty-sections/93646.md , Careless Whisper (Koenecke et al. 2024) https://www.news.cornell.edu/stories/2024/06/ai-speech-text-can-hallucinate-violent-language |
| Repetition loops (hallucination) | Same sentence ×3+ | SAFE | Collapse identical consecutive sentences: `(\b[^.!?]{8,}[.!?])(\s*\1)+` becomes `\1`. https://yage.ai/share/whisper-repetition-hallucination-en-20260526.html |
| Repeated punctuation | `,,`, `, ,`, `..` (not `...`), `?.`, ` .`, `!!` (keep if the user wants it) | SAFE | Normalize after every deletion pass: `\s+([,.!?;:])` becomes `\1`; `([,;:])\s*\1+` becomes `\1`; `,\s*([.!?])` becomes `\1`; `^[,;:\s]+` becomes nothing |
| Orphaned capitalization | "so, Um we" → after delete, "so, we" | SAFE | Re-capitalize the first letter of each sentence; lowercase leftover mid-sentence caps from deleted fillers |
| Double spaces | — | SAFE | `\s{2,}` becomes ` ` |
| Ellipsis from trailing pauses | "I was thinking... that we..." | CONTEXT | Whisper inserts `...` at pauses. In formal mode, replace a mid-sentence `...` with a space, but keep a trailing `...` |
| Leading lowercase / missing final period | — | SAFE | Style choice |
| Spoken punctuation not converted | "comma", "period", "full stop", "question mark", "exclamation point/mark", "colon", "semicolon", "new line", "new paragraph", "open/close quote", "dash", "hyphen", "open paren" | CONTEXT | Convert only when it sits at a clause boundary. Keep when it is discussed by name ("remove the comma"), per Wispr's rule. Apple and Google dictation do this natively, so skip if the engine already did. |
| Spelled-out web tokens | "dot com", "dot org", "dot io", "at gmail dot com", "w w w dot", "slash", "underscore", "dash" in URLs | CONTEXT | Join when an email or URL is detected: `(\w+)\s+at\s+(\w+)\s+dot\s+(com|org|net|io|edu|gov|co)` becomes `\1@\2.\3`; `\s+dot\s+(com|org|net|io|edu|gov|co|ai|app|dev)\b` becomes `.\1`. Require a domain TLD to avoid "connect the dots". |
| Number formatting | "two thirty pm", "twenty five percent" | CONTEXT | Out of scope for filler removal. The Wispr docs describe digit conversion. |
| ASR filler spellings in caps | "UM", "Uh," | SAFE | Handled by the case-insensitive filler rule, except for all-caps acronyms of 3+ letters |
| `<unk>`, `<|endoftext|>`, `<|nocaptions|>` tokens | Leaked special tokens | SAFE | `<\|?[a-z_]+\|?>` delete |
| Speaker labels | "Speaker 1:", ">>" | SAFE (single-speaker dictation) | `^(Speaker \d+:|>>)\s*` delete |

---

## 7. Paste-ready lists

```text
# SAFE_FILLED_PAUSES (case-insensitive, whole-word, allow elongation)
um umm ummm uhm uhmm uh uhh uhhh er err erm errm ehm emm mm mmm mmmm
# conditional (CONTEXT): ah ahh hmm hm hmmm eh huh oh ahem

# KEEP_BACKCHANNELS (never strip)
uh-huh uh huh mm-hmm mhm mhmm mm-mm uh-uh nuh-uh unh-unh yeah yep yes no nope okay ok sure right

# CONTEXT_DISCOURSE_MARKERS (strip only comma-wrapped or ^X, )
like | you know | I mean | basically | well | you see | kind of | sort of | let me think | let me see | let's see
| how do I put this | what's the word | you know what I mean

# CONTEXT_LAUNCH_CHAINS (strip at utterance start)
^(okay|ok|alright|all right|right|well|so|yeah)\s*,?\s+(so|um|uh|like|basically|anyway)\b  → keep last content word

# REPAIR_CUES (delete cue + parallel reparandum ≤3 tokens; must have repair)
scratch that | delete that | strike that | cancel that | forget that | never mind | disregard that
| no wait | wait no | no no | I mean | sorry | or rather | actually | I meant | what I meant was | what I mean is
| make that | correction | let me rephrase | let me start over | start over | excuse me | pardon me

# NEVER_COLLAPSE_DOUBLES
had had | that that | do do | will Will | can can | there there | so so | bye bye | no no | yes yes
| now now | well well | night night | knock knock | ha ha | very very | really really | much much | many many
| <number-word> <number-word>

# KEEP_HEDGES
I think | I guess | I suppose | I believe | I feel like | maybe | perhaps | probably | kind of (adv) | sort of (adv)
| a bit | a little | somewhat | pretty | quite | about | around | roughly | like + <number> | or so | or something
| -ish | just | actually (non-repair) | literally | honestly | if that makes sense
```

## 8. Suggested rule order
1. Strip ASR artifacts (Section 6: tags, sound descriptions, special tokens, hallucination loops).
2. Apply explicit commands (`scratch that`, `let me start over`) on the whole utterance.
3. Run parallel-class repairs (number/time/day across `no|no wait|sorry|I mean|actually|make that`).
4. Collapse partial words and stutters (`tr- try`, `b-b-but`).
5. Collapse function-word and phrase repetitions, minus the never-collapse list.
6. Delete SAFE filled pauses.
7. Delete CONTEXT discourse markers (comma-wrapped and utterance-initial only).
8. Normalize punctuation, spacing, and capitalization (run after every deletion step, or once at the end).
9. Hand off to the LLM pass for: abandoned clauses, ambiguous repairs, non-comma-wrapped "like", general extenders, and "or rather".

Steps 1–6 are high precision. Step 7 is the main source of risk, so ship it behind a setting or A/B test it on real dictations.
