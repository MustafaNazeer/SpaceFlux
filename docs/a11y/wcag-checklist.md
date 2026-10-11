# WCAG 2.2 AA checklist, operator dashboard

This is the accessibility checklist for the SpaceFlux operator dashboard, checked against WCAG 2.2 at levels A and AA. It covers two things that must agree: the canonical design in [`docs/design/dashboard.html`](../design/dashboard.html) and the Angular app in [`dashboard/src`](../../dashboard/src), which is built from it. Colors, type, focus and motion rules come from [`docs/design/tokens.md`](../design/tokens.md).

First checked on 2026-10-06 and rechecked twice on 2026-10-07 against the working tree of that date, after the fixes listed under [Findings](#findings). In the first recheck the local API was an older build than the app's queries, so the screening panel could only be checked in the design file. In the second recheck the API was built from the same tree and answered every query: the space weather panels showed live data (three scales at "none"), the screening panel showed a current run with no close approach and 8 suppressed pairs, and no alerts were stored. The close approach table with rows and the alert list were therefore still checked in the design file only. Acknowledgement controls and the sign in form are drawn in the design but not built in the app yet, so their rows are checked against the design only.

The Watchlist passes panel was added on 2026-10-10 and checked the same day, in both files, against an API built from the same tree. The live watchlist held one object (ISS (ZARYA), 25544) with two computed passes, neither clipped. The other states (a pass clipped at the window start, one clipped where the search ended, one with two maxima, an object whose computation failed, an element set too old, and an empty list) were shown by adding them to a real answer inside the browser, so the app rendered every state with its own code.

## How it was checked

* **Automated rules.** axe-core in headless Google Chrome, tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`, plus `best-practice`, on both the design file and the running app, at 320 and 1280 CSS px. The first recheck used axe-core 4.14.0 with playwright-core 1.63.0, the second axe-core 4.12.1 with playwright-core 1.62.1. Result both times: no violations on either. One "needs review" item remains on the design only (`color-contrast` on table cells whose background axe could not resolve; the colors involved are `text-secondary` on `surface-1` and `surface-3`, 8.58 and 6.99).
* **Keyboard walk.** A script pressed Tab through each page at 1280, 640 and 320 CSS px wide and recorded, for every focused element, its outline and whether its center or corners were covered by any other element (`document.elementFromPoint`).
* **Reflow and zoom.** Horizontal overflow measured at 320 CSS px (reflow) and at 640 CSS px, which is the layout a 1280 px window shows at 200 percent zoom.
* **Text spacing.** The WCAG 1.4.12 values (line height 1.5, letter spacing 0.12 em, word spacing 0.16 em, paragraph spacing 2 em) injected as a style sheet at 320 and 640 px, then every element with hidden overflow checked for clipped content and the page checked for sideways overflow.
* **Reduced motion.** Computed animation and transition values read with `prefers-reduced-motion: reduce` emulated and without it.
* **Live regions and pausing.** A mutation observer recorded every change to each `role="status"` element while the page refreshed, paused and resumed. A request counter confirmed that no GraphQL request leaves the page during 75 s of pause (20 s in the second recheck), and that resuming refreshes all three queries at once. To see the failure paths without stopping any service, the browser answered one query at a time with HTTP 503 while the others reached the API, then let it through again.
* **Color.** Contrast, color vision simulation and CIEDE2000 recomputed with a separate implementation written for this check, not with the script in `docs/design/tools/`. The CIEDE2000 code reproduces all 34 published test pairs of Sharma, Wu and Dalal (2005), downloaded from the author's site, to four decimals. Every contrast ratio, every color vision figure and every grayscale figure in `tokens.md` was reproduced.

* **Passes panel.** axe-core 4.12.1 with playwright-core 1.62.1, same tags, at 320, 640 and 1280 CSS px, on the design file, on the app with the live answer, on the app with every object state, and on the app with the passes request answered with HTTP 503 from the first load. The accessibility tree was read from Chrome. The Refresh passes button was pressed from the keyboard with the request held for 2.5 s, to read its state, focus and look while the request was out, and pressed again four times during it (Enter, Enter, Space, a mouse click) while a request counter ran. A mutation observer recorded the panel's status line through two refreshes that succeeded and two that failed. A request counter watched 65 s of the one minute refresh and a pause and resume.

* **Live alert announcements.** Added 2026-10-11 and so far checked by reading the code and its unit tests only, not in a browser or with a screen reader: the recent alerts panel template, the announcement wording, the list merge, the data layer's pause and resume, and the subscription client. Rows that rest on this review say "static". Checks M9 and M10 settle them.

Automated tools cannot settle everything. The checks that need a person are listed under [Checks that need a person](#checks-that-need-a-person).

## Status key

* **Pass**: checked and met, with the evidence named.
* **Fail**: not met; the finding says what to change.
* **Open**: needs a person (see the numbered checks) or a decision before it can be marked.
* **N/A**: the dashboard has no content of that kind.

## Criteria

### Perceivable

| Criterion | Level | Status | Evidence |
|---|---|---|---|
| 1.1.1 Non text Content | A | Pass | Every icon is an inline SVG with `aria-hidden="true"` next to visible text that carries the same meaning (level code and NOAA name, "No data", "Ended", "Stale", "Close approach", acknowledgement words, "Clipped"). The icon sprite is hidden from assistive technology. The live dot is decorative next to its "Live", "Stale", "Paused" or "Connecting" label. |
| 1.2.1 to 1.2.5 Time based media | A, AA | N/A | No audio or video. |
| 1.3.1 Info and Relationships | A | Pass | Landmarks `header`, `main`, `aside` and `footer`; one `h1`, `h2` per section, `h3` per panel and per alert. Fact lists use `dl`. Tables use `th scope="col"`, with names from `aria-labelledby` or `aria-label` and notes tied with `aria-describedby`. Disclosures are native `details` and `summary`. Passes: one `h3` per watchlist object holding its name and catalog number ("ISS (ZARYA) 25544"), which names both the scrolling region and the table, as the screening tables do with one name for region and table; the shared note is tied to each table with `aria-describedby`; window and observer are a `dl`; every time is a `time` element with a machine readable `datetime`. New alerts (static): the visually hidden `role="log"` region named "New alerts" holds one `p` per announced alert, so each entry is its own node and its times keep their `time` elements. |
| 1.3.2 Meaningful Sequence | A | Pass | DOM order is banners, space weather, screening, watchlist passes, recent alerts, footer, which matches the visual order at every width checked. |
| 1.3.3 Sensory Characteristics | A | Pass | No instruction refers to shape, position or color. |
| 1.3.4 Orientation | AA | Pass | No orientation lock; the layout reflows to one column. |
| 1.3.5 Identify Input Purpose | AA | Pass (design) | The sign in form uses `autocomplete="username"` and `autocomplete="current-password"`. Not built in the app yet. |
| 1.4.1 Use of Color | A | Pass | Every alert shows its icon shape, code and NOAA name; stale data shows the clock icon and the word "Stale"; no data shows its own icon and words; the live indicator always has a text label, and the paused dot also changes shape (a hollow ring); disclosures show a chevron; links in running text are underlined. A stale screening table changes its cell color, and the same run also shows a "Stale" badge in the table heading and a stale banner. A clipped pass shows the Clipped icon, the word and a sentence saying which edge cut it; an object without passes shows the no data icon and its status label in words, and a failed object a sentence. The Refresh passes button's busy state changes lightness (label from `text-primary` to `text-muted`, 2.58 between them), not hue alone, and is exposed as disabled. |
| 1.4.2 Audio Control | A | N/A | No audio. |
| 1.4.3 Contrast (Minimum) | AA | Pass | Lowest text pair in use is `alert-5` on `surface-3` at 4.61 and `text-muted` on `surface-3` at 4.67 (rows hover to `surface-3`). Every pair in the `tokens.md` tables was recomputed and matches. Nothing relies on the large text allowance. The Clipped badge text is `text-secondary`, 8.58 on `surface-1` and 6.99 on the `surface-3` hover row. |
| 1.4.4 Resize Text | AA | Pass, see check M3 | At 640 CSS px (200 percent of a 1280 px window) nothing overflows the page and nothing is clipped, in either file. Type is set in px, so text only zoom depends on the browser; check M3 covers it. |
| 1.4.5 Images of Text | AA | Pass | No images of text. The favicon has no text. |
| 1.4.10 Reflow | AA | Pass, see check M4 | At 320 CSS px neither file scrolls sideways; tables scroll inside their own focusable region, which the criterion allows for two dimensional data. The passes table (523 px of content in a 262 px region, 551 px with a clipped row) does the same in both files, and the arrow keys scroll it once it has focus (240 px after six presses) while the page stays put. In the app this was checked with a loaded screening run and both disclosures open: the suppressed pairs table (787 px of content in a 262 px region) scrolls inside its region and the page stays 320 px wide. The hidden skip links now sit fully above the window (measured from minus 68 to minus 30 px) and are clipped until focused, so nothing shows over the header. |
| 1.4.11 Non text Contrast | AA | Pass | `border-strong` is 3.24 or more on every surface, which covers control outlines and the paused ring (3.98 on the header's `surface-1`); the focus ring is 10.05 or more against every surface it sits on; the live dot at its dimmest is 3.02 or more; the disclosure chevron is drawn in `text-primary`. The dashed no data outline is decorative because the words carry the state. The Clipped outline (`border-strong`, 3.98 and 3.24) is likewise backed by the word. The Refresh passes button while its request is out is an inactive control, which the criterion exempts; its `border-subtle` outline is 1.43 on `surface-1`. |
| 1.4.12 Text Spacing | AA | Pass | With the spacing values applied, nothing clips and neither file scrolls sideways at 320, 640 or 1280 px, including the app's loaded screening panel with both disclosures open, and the passes panel in every object state (the table region grows from 523 to 594 px of content and still scrolls inside itself). |
| 1.4.13 Content on Hover or Focus | AA | N/A | No tooltips, popovers or hover revealed content. |

### Operable

| Criterion | Level | Status | Evidence |
|---|---|---|---|
| 2.1.1 Keyboard | A | Pass, see check M1 | Every control is a native link, button, summary, input or textarea, including the Pause updates toggle. Each scrolling table region has `tabindex="0"` so it can be scrolled from the keyboard. Refresh passes is a native button. |
| 2.1.2 No Keyboard Trap | A | Pass | The Tab walk reached the last focusable element and returned to the document every time. |
| 2.1.4 Character Key Shortcuts | A | N/A | No keyboard shortcuts. |
| 2.2.1 Timing Adjustable | A | N/A | No time limits. The 10 s session check only decides which read only view to show. |
| 2.2.2 Pause, Stop, Hide | A | Pass | The header has a "Pause updates" button that stops all polling (no request in 75 s of pause) and reads "Resume updates" while paused; resuming refreshes at once. The live dot pulses only on first load, for 4 s (2 cycles of 2 s), under the 5 s limit, and never again on later refreshes. The passes panel does not update on its own: it loads once with the page and again only from Refresh passes. No passes request left the page during 65 s of the one minute refresh, nor on pause and resume. Live alerts (static, see check M10): Pause updates also closes the alert subscription and cancels any reconnect waiting to run, so no alert is added to the list while paused; resuming opens it again and refreshes the list at once. An alert listed less than 2 s before the pause is spoken at the pause, not during it (F26). |
| 2.3.1 Three Flashes or Below Threshold | A | Pass | The only animation is an opacity pulse on a 10 px dot at one cycle per 2 s, on first load only. Nothing flashes. |
| 2.4.1 Bypass Blocks | A | Pass | Two skip links (to `main` and to the alerts `aside`, both with `tabindex="-1"` so focus moves) plus landmarks. |
| 2.4.2 Page Titled | A | Pass | "SpaceFlux operator console". |
| 2.4.3 Focus Order | A | Pass | App: skip links, Pause updates, then the screening controls when a run is loaded (the table region and the two disclosures), then Refresh passes and one table region per object with passes, then the footer link. Design: skip links, Pause updates, Sign out, the table region, the disclosures, the alert actions, then the gallery samples and the footer link. Order follows the reading order. A new alert is inserted at the top of the list without moving focus; the app's alert items hold no focusable element yet, so trimming the list to its page size cannot drop focus today (finding F21 covers the controls still to come). |
| 2.4.4 Link Purpose (In Context) | A | Pass | "spaceweather.gov" in a sentence that says what it is for; the skip links name their target. |
| 2.4.5 Multiple Ways | AA | N/A | The dashboard is a single page. |
| 2.4.6 Headings and Labels | AA | Pass | Headings name each section and panel; form labels name each field; the toggle names its action. |
| 2.4.7 Focus Visible | AA | Pass | Every focused element in the walk matched `:focus-visible` and drew a 2 px solid `#8FE6F5` outline at a 2 px offset. |
| 2.4.11 Focus Not Obscured (Minimum) | AA | Pass | No sticky or fixed element exists. In the walk at 1280, 640 and 320 px, no focused element had its center or corners covered by another element. The two skip links share one spot, and each is visible only while it has focus. Recheck if a sticky header, toast or banner is ever added. |
| 2.5.1 Pointer Gestures | A | N/A | No path or multipoint gestures. |
| 2.5.2 Pointer Cancellation | A | Pass | Only native controls, which act on the up event. |
| 2.5.3 Label in Name | A | Pass | Every control's accessible name is its visible text. |
| 2.5.4 Motion Actuation | A | N/A | No device motion input. |
| 2.5.7 Dragging Movements | AA | N/A | No dragging. |
| 2.5.8 Target Size (Minimum) | AA | Pass | Buttons and summaries are 32 px tall (Refresh passes is 123 by 32 px), skip links 38 px; the footer link is inline in a sentence, which the criterion exempts. |

### Understandable

| Criterion | Level | Status | Evidence |
|---|---|---|---|
| 3.1.1 Language of Page | A | Pass | `lang="en"` on both pages. |
| 3.1.2 Language of Parts | AA | N/A | No text in another language. |
| 3.2.1 On Focus | A | Pass | Focus never changes context. |
| 3.2.2 On Input | A | Pass | No input changes context. The pause toggle changes only the refresh and the alert subscription. A new alert added to the list is a change of content, not of context: focus, the viewport's purpose and the page stay the same. |
| 3.2.3 Consistent Navigation | AA | N/A | Single page. |
| 3.2.4 Consistent Identification | AA | Pass | Each state has one badge treatment wherever it appears. |
| 3.2.6 Consistent Help | A | N/A | No help mechanism. |
| 3.3.1 Error Identification | A | **Open** | The design still has no state for a failed sign in or a failed acknowledgement. Needs designing before those controls are built. Finding F6. |
| 3.3.2 Labels or Instructions | A | Pass (design) | Every field has a visible `label`; the note field states its limit. |
| 3.3.3 Error Suggestion | AA | **Open** | As 3.3.1. |
| 3.3.4 Error Prevention (Legal, Financial, Data) | AA | N/A | No legal or financial transactions; an acknowledgement can be undone. |
| 3.3.7 Redundant Entry | A | N/A | No multi step process. |
| 3.3.8 Accessible Authentication (Minimum) | AA | Pass (design) | Username and password with autocomplete, so a password manager can fill them; no cognitive test, no paste blocking. |

### Robust

| Criterion | Level | Status | Evidence |
|---|---|---|---|
| 4.1.2 Name, Role, Value | A | Pass | Native elements throughout; disclosures expose their expanded state; the toggle's name states the action it will take. Refresh passes keeps focus and its name while its request is out and carries `aria-disabled="true"`, which Chrome exposes as disabled; further presses send nothing (four presses during one request sent no second request). No ARIA name sits on an element that does not allow one. |
| 4.1.3 Status Messages | AA | Pass, see checks M2, M7 and M9 | In both files the visible banners are not a live region. One visually hidden `role="status"` element before the banners speaks a short line only when the set of banners changes: "{measurement} is stale; the {S} scale reads no data.", "The screening run is stale.", "A refresh of {parts} failed.", "Updates are paused." while paused, and "No stale notices remain." once the last banner clears. Each section has its own persistent `role="status"` element for its loading and failed load text, present from first render. All are polite; nothing uses `role="alert"`. Observed: a refresh that keeps failing is announced once, pausing says "Updates are paused." once and nothing more while paused, and "No stale notices remain." comes only after a refresh that succeeded (F11, F14). The passes panel has its own persistent `role="status"` line: nothing on a first load that succeeds; "Passes updated at {HH:MM:SS} UTC." after each refresh that succeeds; "Passes could not be refreshed. The passes shown are for the 24 hours from {HH:MM:SS} UTC." after one that fails, with the tables kept; "Passes could not be loaded. Use Refresh passes to try again." when the first load fails. The line empties while a request is out, so a repeated result is spoken again for each press. New alerts (static, see check M9): an alert newly listed, whether the live subscription or the minute refresh added it, is spoken once through a visually hidden `role="log"` region with `aria-live="polite"` (additions only, not atomic) named "New alerts". The region is the panel's last child, after the list, and is in the page from first render. Alerts listed within 2 s of the first one waiting become one entry: a single alert keeps its own sentence ("New alert, close approach: ...", "New alert, G4 Severe, changed from G3: ...", "New alert. Below G1: ..., after G3."), several read "{N} new alerts. Top of the list, {the top alert's sentence after its opening}", for example "2 new alerts. Top of the list, R1 Minor: ...". So a single alert is spoken about 2 s after it is listed. Nothing is spoken while no list is shown; waiting alerts are held and spoken once the list shows them, and an alert the list does not hold is never spoken. The first refresh after a resume is summed up instead of read out: "1 alert arrived while paused.", "{N} alerts arrived while paused.", or "{N} or more alerts arrived while paused." when the previous top alert has left the page and older ones exist, so the count may be short. Pausing speaks any group still waiting at once, in the same update as "Updates are paused.", so nothing is spoken during the pause. An alert covered by the summary is never spoken again by a waiting group. Each alert is spoken at most once, however it arrives again. The last 5 entries are kept, and an entry is removed only after the page has drawn it, so no entry is lost before it is spoken; removals are silent. The panel's own status line and the page's stale notice line do not change for a new alert, and the visible list is not a live region, so nothing is said twice through two regions. |

4.1.1 Parsing is obsolete in WCAG 2.2 and is not listed.

## Colors, rechecked

* **C1. Contrast.** Every ratio in the `tokens.md` contrast tables, the on fill table and the live dot table reproduces exactly (truncated to two decimals). Extra pairs checked here: `focus-ring` on `surface-3` (the skip link fill) 10.05; `border-strong` on `surface-1` (the paused ring) 3.98; `accent` against `text-primary` 1.58 and against `text-secondary` 1.09, which is why links in running text must stay underlined.
* **C2. Color vision.** With the Machado, Oliveira and Fernandes (2009) severity 1.0 matrices applied in linear RGB, the smallest CIEDE2000 differences are 12.81 normal, 9.97 protanopia, 8.60 deuteranopia and 10.02 tritanopia, with the same pairs `tokens.md` lists. The pairs under 10 (`alert-none` and `stale`; `alert-1` and `alert-2`; `alert-2` and `alert-3`, all under deuteranopia) are acceptable because no state is ever told apart by color alone (1.4.1). The `stale` and `accent-active` pair at 2.16 under deuteranopia never marks the same thing.
* **C3. Grayscale.** Neighbor luminance ratios are 1.159, 1.403, 1.350 and 1.153, matching `tokens.md`, which now states that these are rounded while its contrast tables truncate. Level 4 and level 5 clear the review cutoff of 1.15 by 0.003.
* **C4. New treatments.** `badge-no-data` (`text-secondary` 8.58 on `surface-1`, outline `border-strong` 3.98), `badge-approach` (`text-primary` 14.83, outline 3.98), `banner-stale` (`stale` 7.01 and `text-primary` 14.83 on `surface-1`) and the paused indicator (ring 3.98, label `text-secondary` 8.58) all clear their thresholds. `badge-clipped` (`text-secondary` 8.58 on `surface-1` and 6.99 on `surface-3`, outline `border-strong` 3.98 and 3.24) clears them too. The disabled button look needs no contrast (inactive control), yet its `text-muted` label still reads 5.73 on `surface-1` and 4.67 on `surface-3`.

## Findings

Ranked: blocker (fails an AA criterion), should fix (meets the criterion but works badly with assistive technology, or needs a decision), nit. Fixed findings stay listed with how the fix was verified.

### Fixed

* **F1. Hidden skip link showed at narrow widths (1.4.10).** Fixed in both files: the links hide with `transform` and `clip-path`, stay on one line, and share one position. At 320 px they measure from minus 68 to minus 30 px when unfocused and appear in full when focused.
* **F2. The stale banner region re-announced every banner each minute.** Fixed in the app: the banners are no longer a live region, and a separate status line changes only with the set of banners. The design file followed later, see below.
* **F3. Auto updating content and the pulse (2.2.2).** Fixed in both: a Pause updates and Resume updates toggle, and a pulse on first load only. Polling stop verified by request count.
* **F4. `aria-label` on a paragraph.** Removed in both files; axe no longer flags it.
* **F5. Disclosures showed no expand cue.** Fixed in both: a CSS chevron that turns when the disclosure opens.
* **F8. Design gallery overflowed at 320 px.** Fixed: the design no longer scrolls sideways at 320 px.
* **F9. Panels widened under text spacing at 320 px.** Fixed: no sideways overflow at 320 px with the spacing values applied, in either file. Rechecked 2026-10-07 in the app with a loaded screening run and both disclosures open.
* **F10. Loading messages might not be announced.** Fixed in the app: each panel's status element is in the page from first render, and its text switches from loading to the failed load text without being reinserted.
* **F2, design file.** Fixed, rechecked 2026-10-07: the design no longer puts `role="status"` on any banner, and the main view has the one visually hidden status line before the banners, with the same text the app uses.
* **F11. Pausing announced a false recovery.** Fixed in the paused logic, rechecked 2026-10-07 in the app (specs and live render) and in the design: while paused a "Paused." banner comes first, the "Not updating." banner is folded into it ("A refresh of {parts} failed at {t} UTC before the pause."), and the status line says "Updates are paused." once. Pausing and resuming no longer clear the failure on their own; it clears only when a refresh succeeds. A false recovery on resume while the refresh still failed came from a separate cause in the data layer, fixed under F14.
* **F12. Failed load texts and persistent status regions were not in the design.** Fixed, rechecked 2026-10-07: each section in the design's main view has an empty persistent status region, and the gallery shows the first load and failed first load texts, word for word as in the app, including "The page tries again when updates resume." while paused.
* **F14. A refresh that kept failing announced a false recovery at every poll (4.1.3).** Found 2026-10-07: with one query answered with HTTP 503, each later poll changed the status line to "No stale notices remain." and back to "A refresh of ... failed." within 8 to 311 ms, and the "Not updating." banner blinked out and back; resuming while the failure stood did the same. Cause: each poll first emits a loading result that still carries the cached answer (Apollo Client 4 notifies on network status changes by default), and `dashboard/src/app/data/dashboard-data.ts` took it as a success. Fixed: a loading result is now ignored, with a test for it. Rechecked live the same day with the screening query and then the space weather query failing over three polls each, a pause and a resume while still failing, then a refresh that succeeded. Each time the status line said "A refresh of {parts} failed." once and did not change at the later failing polls; the banner set did not change between polls; pausing said "Updates are paused." once; resuming while still failing went straight back to "A refresh of {parts} failed." and the "Not updating." banner, with no recovery line in between; "No stale notices remain." came only with the refresh that succeeded. No request left the page during the pause, and the header read "Stale" for a screening only failure as well as for a space weather failure.
* **F13. "Not updating." wording when the API did answer.** Fixed: the banner now names what failed, "A refresh of {parts} failed at {t} UTC. What is shown for {it or them} is from the last refresh that succeeded.", and makes no claim about the API.

* **F18. A burst of notices could be cut or flood the queue.** Fixed, checked in code 2026-10-11: alerts listed within 2 s are one entry ("{N} new alerts. Top of the list, ..."), and an entry is trimmed only after the page reports it drawn, so 7 alerts before one render are covered by one entry (unit test). Check M9 step 4 confirms by ear.
* **F19. A notice could be spoken while the list was not shown.** Fixed, checked in code 2026-10-11: with no list shown nothing is spoken and the waiting alerts are held; they are spoken once the first answer shows them, and only those it holds (unit tests).
* **F20. Alerts that arrived through the minute refresh were never announced.** Fixed, checked in code 2026-10-11: alerts a refresh lists above the newest one the previous answer listed are announced like notices, never on the first answer, and the first refresh after a resume is summed up ("{N} alerts arrived while paused.", or "{N} or more ..." when it cannot count them all) (unit tests). Check M10 step 2 confirms by ear.
* **F22. Explicit `aria-live="polite"` on the log.** Fixed: the attribute is on the log element.
* **F24. The log repeated the list when read line by line.** Fixed: the log is now the panel's last child, after the list and its "Showing the {n} newest." line, and still in the page from first render (unit test).
* **F25. Doubled colons in some wordings.** Fixed with the reworded announcements: a level left now reads "New alert. Below G1: ..., after G3." with one label.
* **F26. Pausing within 2 s of a new alert spoke it during the pause (2.2.2, 4.1.3).** Found and fixed 2026-10-11, checked in code: `pause()` now clears the grouping timer and flushes the waiting group at once, before it sets the paused state, so the entry and "Updates are paused." come in the same update and nothing is added to the log while paused. Check M10 step 4 confirms by ear.
* **F27. An alert could be counted in the resume summary and then spoken again.** Found and fixed 2026-10-11, checked in code: `flush` skips alerts already spoken, and the resume summary removes the alerts it covers from the waiting group (unit tests).

### Should fix

* **F6. No error states designed for sign in and acknowledgement (3.3.1, 3.3.3).** Unchanged. Needed before those controls are built: an inline message tied to the field or form with `aria-describedby`, focus moved to it or announced through `role="alert"`, and text that says what to do next.
* **F7. No announcement for new alerts yet.** Built in the app and reviewed in code on 2026-10-11 (see 4.1.3): newly listed alerts are spoken through a polite `role="log"` region with their level code, NOAA name and times, never through the stale notice line. Stays listed until check M9 passes.
* **F21. Controls inside alert items must survive a prepend.** Deferred until alert items gain controls, and not a failure today, since items hold no focusable element. Once they gain controls (detail, acknowledge), a new alert pushes the last item out of the page; if focus is on that item's control, focus falls to the page (2.4.3) and a half typed acknowledgement note could be lost. Fix when those controls land: while focus is inside the list, or an item's form is open, do not trim or reorder the visible list; hold new alerts in front, still announce them, and add them when focus leaves the list. Add a test that focuses a control in the last item, sends a notice, and checks `document.activeElement` is unchanged.

### Nits

* **F15. The disabled controls note in `tokens.md` says the status line explains a busy Refresh passes button.** It says "the panel's status line says what is happening", but while a refresh with passes on screen is out the line is empty; it speaks only the result. Nothing fails, since the button is exposed as disabled and the result is announced. Change the sentence to: "Its label does not change; its disabled state shows the request is out, and the panel's status line reports the result."
* **F16. Two landmarks share one name on the design page (axe `landmark-unique`, best practice, not a WCAG criterion).** The main view's passes region and the clipped pass example in the gallery are both named "ISS (ZARYA) 25544". The app is not affected: each object appears once. Fix in the gallery item only: on its `div.table-wrap`, replace `aria-labelledby="g-passes-clipped"` with `aria-label="ISS (ZARYA) 25544, clipped pass example"`, keeping the table's own `aria-labelledby`.
* **F17. Compass points and the day unit are abbreviations ("from SE", "E at 11.7°", "0.7 to 1.1 d").** Expanding abbreviations is criterion 3.1.4, level AAA, so this is not an AA failure, and compass points are a common convention. Screen readers may spell them letter by letter; check M7 step 4 records how Orca reads them. If that reading is judged unclear, the fix is to show the full words to everyone ("from southeast", "to east", "east at 11.7°") rather than to add hidden text, so that what is seen, copied and heard stays the same.

* **F23. No visible cue for a new alert.** Deferred until alert views are built. A sighted viewer sees an item appear at the top with nothing marking it as new, while a listener hears "New alert". Not an AA failure. Fix: add the "New" marker as a word badge (never color alone) on items added since the page loaded, cleared by the next refresh or after a set time.

## Checks that need a person

Each check below is written so it either passes or fails. M7 and M8 cover the Watchlist passes panel; M9 and M10 cover live alert announcements. Run them on the dashboard served locally with the core stack up and the API built from the same tree as the dashboard (`npm start` in `dashboard/`, then open `http://localhost:4200/`).

**M1. Keyboard only.** In Chrome, with the mouse untouched:
1. Load the page and press Tab once. Pass if "Skip to space weather" appears fully inside the window with a visible cyan outline.
2. Press Tab again. Pass if "Skip to recent alerts" appears in the same spot and the first link is hidden again.
3. Press Enter. Pass if the page scrolled to Recent alerts and the next Tab lands on the first control after the Recent alerts heading (or on the footer link when no alert has a control).
4. Reload, press Tab three times to reach "Pause updates", and press Enter. Pass if the button now reads "Resume updates", the header reads "Paused" with a hollow ring, and focus stayed on the button. Press Space. Pass if it reads "Pause updates" again and the header returns to "Live".
5. Reload, press Tab, press Enter on "Skip to space weather", then keep pressing Tab. Pass if every focused element shows the outline, none is hidden behind anything, and each disclosure opens and closes with Enter and with Space while its chevron turns.
6. Pass if Shift+Tab walks the same elements in reverse and focus never gets stuck.

**M2. Screen reader.** On Linux, Orca with Firefox (start Orca with Super+Alt+S, or `orca` in a terminal):
1. Load the page. Pass if Orca reads the page title "SpaceFlux operator console".
2. Press H repeatedly. Pass if Orca reads "SpaceFlux", then "Space weather, derived levels", the three scale headings, "Latest screening run", and "Recent alerts", each with a heading level.
3. On each scale panel, read line by line. Pass if the badge is read as words (for example "none", "R1 Minor" or "No data") and no icon is read as "image" or an unlabelled graphic.
4. Tab to "Suppressed pairs". Pass if Orca says it is collapsed, then expanded after Enter.
5. With a stale banner on screen, wait three minutes without touching anything. Pass if Orca speaks the stale line at most once (when the page loads), not once a minute.
6. Tab to "Pause updates" and press Enter. Pass if Orca reads the new name "Resume updates", then says "Updates are paused." once, and does not say "No stale notices remain." Press Enter again. Pass if Orca reads "Pause updates" and says no recovery unless a refresh then succeeds.
7. Optional, only if a refresh fails on its own (do not stop any service for this): pass if Orca says "A refresh of {parts} failed." once, without moving focus, says nothing more while the same parts keep failing, and says "No stale notices remain." only after the next refresh that succeeds. If you pause and resume while it still fails, pass if Orca says "Updates are paused." and then "A refresh of {parts} failed." again, and nothing about a recovery.

**M3. Text only zoom.** In Firefox, turn on View, Zoom, Zoom Text Only, then press Ctrl and plus until the zoom reads 200 percent. Pass if all text is larger, nothing overlaps, and nothing is cut off; sideways scrolling inside a table is allowed.

**M4. Page zoom at 400 percent.** In Chrome on a 1280 px wide window, press Ctrl and plus until 400 percent. Pass if the page is one column, no sideways scrolling is needed for anything except the tables, and no skip link text is visible at the top until you press Tab.

**M5. Reduced motion.** Turn on the system setting (GNOME: Settings, Accessibility, Reduce Animation; or `gsettings set org.gnome.desktop.interface enable-animations false`), then reload. Pass if the live dot does not pulse on load and the disclosure chevron turns without animating.

**M6. Color vision, by eye.** In Chrome DevTools, Rendering panel, Emulate vision deficiencies, open `docs/design/dashboard.html` and try each of protanopia, deuteranopia, tritanopia and achromatopsia. Pass if every badge in the "Other states" gallery, and the Live and Paused indicators, can still be told apart by icon, shape and text.

**M7. Passes panel with a screen reader.** Orca with Firefox, as in M2:
1. Press H until Orca reads "Watchlist passes", heading level 2, then press H again. Pass if it reads each watchlist object's name and catalog number as a level 3 heading (for example "ISS (ZARYA) 25544").
2. Press Tab from the object heading. Pass if Orca reads the table region by the same name, and table navigation (Orca's T key, then Alt+Shift with the arrow keys) reads each cell with its column header ("Rise (UTC)", "Peak elevation" and so on).
3. Listen to the page load without touching anything. Pass if Orca says nothing about passes being updated.
4. Read a rise cell and a set cell. Write down how Orca speaks "from SE", "to E" and any degree value. Pass if the direction can be understood without seeing the screen (this decides F17).
5. Tab to "Refresh passes" and press Enter. Pass if focus stays on the button, Orca says it is unavailable or dimmed while the request is out (or at least does not read a different name), and then says "Passes updated at {time} UTC." once. Press Enter again after it finishes. Pass if Orca says the line again.

**M8. Passes panel by eye.** In Chrome at 1280 px, then at text only zoom 200 percent in Firefox:
1. Press Refresh passes. Pass if you can see that the button is busy for the moment the request is out (dimmer label and outline, no hover change), and that it looks the same as before once the times update.
2. Find a Clipped badge (one shows when a pass is in progress at the window start; if none is on screen, use the clipped example in `docs/design/dashboard.html`). Pass if the badge and the sentence under it say which edge cut the pass, and the badge still reads as Clipped under the protanopia, deuteranopia and achromatopsia emulations of M6.
3. At 200 percent text only zoom, pass if nothing in the passes panel overlaps or is cut off, the table scrolls sideways inside its own box, and the page itself does not scroll sideways.

M9 and M10 need new alerts stored while the page is open. They run on a throwaway local Compose project with its own volumes (see the throwaway stack commands in `docs/setup-guide.md`), never on the stack you keep, and store alerts with `deploy/replay-alerts.sh`. Only three fixtures are safe to replay: one close approach, one R level and one S level. Each is a new alert only once per fresh database, so the checks are split into two runs, each on a fresh throwaway project, and each run stores at most those three. The script's `again` mode sends a fixture already stored, which the API drops as a repeat; it is not a new alert. Before each run, `deploy/replay-alerts.sh status` must show none of the three stored; if any is, the run is not fresh and must not start. Use Orca with Firefox as in M2, with the page loaded and idle for 10 s first. Two behaviors cannot be reached with three fixtures and rest on unit tests only: the "{N} or more alerts arrived while paused." wording, and an alert announced through the minute refresh while the subscription is down.

**M9. New alert announcements (run 1: close approach, then R and S together).**
1. Put focus on "Pause updates" and leave it there. Run the script's `one` mode (it stores the close approach first). Pass if, within about 5 s (the page waits 2 s to group alerts), Orca speaks one sentence that starts "New alert, close approach:" and matches the title of the item now at the top of Recent alerts, and focus is still on "Pause updates".
2. Wait one full minute (one refresh) without touching anything. Pass if Orca says nothing more about that alert.
3. Run the script's `again` mode. Pass if Orca says nothing in the next 30 s and the list shows the close approach once.
4. Run the script's `burst 2` mode (the R and the S level, sent together). Pass if Orca speaks one sentence that starts "2 new alerts. Top of the list," and continues with the title of the item now at the top of the list, both new items are in the list, and Orca says nothing more about either in the next minute.
5. Press H until Orca reads "Recent alerts", then read the whole panel line by line with the down arrow. Pass if every list item is read before any of the announced sentences, and the announced sentences, if Orca reads them at all, come only after the last list item.

**M10. Pausing, resuming and reading position (run 2: close approach while paused, then R, then S).**
1. Press "Pause updates". Pass if Orca says "Updates are paused." once. Run the script's `one` mode (the close approach) and wait 30 s. Pass if Orca says nothing about it and the Recent alerts list does not change.
2. Press "Resume updates". Pass if within about 5 s the close approach shows at the top of the list, focus stays on the button, Orca says "1 alert arrived while paused." once and does not then read the close approach's own sentence, and Orca does not say "No stale notices remain." unless a stale notice was showing before.
3. Press H until Orca reads "Recent alerts", then press the down arrow twice so Orca is reading inside the first list item. Run the script's `one` mode (the R level) and keep reading with the down arrow. Pass if Orca announces the R level once, starting "New alert, R", the next lines you read continue from where you were rather than jumping to the top of the panel, and, watching the screen, the list grew by one item at the top while the page did not scroll and nothing flashed or animated.
4. Tab to "Pause updates". Run the script's `one` mode (the S level) and press Enter on "Pause updates" within one second, before Orca speaks the alert. Pass if Orca speaks the S level's sentence no later than "Updates are paused.", both are spoken once, and Orca says nothing more during the next 10 s of pause.
5. Press "Resume updates". Pass if Orca says nothing about the S level again (it was already spoken) and the list shows it once.
