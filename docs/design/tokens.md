# Design tokens

SpaceFlux uses a single dark theme modeled on an operations console: a near black blue base, slightly lifted blue grey surfaces, a cool cyan accent reserved for live telemetry, and an alert ramp keyed to the five levels of the NOAA Space Weather Scales. Every color below has a computed WCAG 2.2 contrast ratio against each background it is allowed to sit on (see [Contrast ratios](#contrast-ratios)).

Component code never hardcodes a color, font size, spacing value, radius, shadow, or duration. It references these tokens by name.

## Color

### Backgrounds and surfaces

Surfaces step up in lightness as they rise toward the viewer. Nothing sits on a surface lighter than `surface-3`.

| Token | Hex | Use |
|---|---|---|
| `bg-base` | `#0A0F17` | Page background, the canvas behind every panel. |
| `surface-1` | `#111A26` | Default panel and card fill. |
| `surface-2` | `#172233` | Nested panel, table header row, input fill. |
| `surface-3` | `#1E2B3F` | Hover row, selected row (with its left border, see below), popover and menu fill. |

### Borders

| Token | Hex | Use |
|---|---|---|
| `border-subtle` | `#26364D` | Decorative dividers between rows and panel sections. Never the only visible edge of an interactive control. |
| `border-strong` | `#647A98` | Input outlines, checkbox and toggle outlines, and any border that identifies a control. Meets 3:1 on every surface. |

### Text

| Token | Hex | Use |
|---|---|---|
| `text-primary` | `#E6EDF5` | Headings, body copy, primary numeric readouts. |
| `text-secondary` | `#A9B7C9` | Labels, table cells that are not the focus, supporting copy. |
| `text-muted` | `#8595AB` | Timestamps, units, captions, placeholder text. Still meets 4.5:1 on every surface, so it may carry real information. |
| `on-fill` | `#06111A` | Text and icons drawn on top of a solid accent or alert fill. |

### Accent (live telemetry)

The cyan accent means "this value is live". It marks streaming readouts, the live indicator, primary actions, and links. It is never used for an alert, and no alert color is ever used as an accent.

| Token | Hex | Use |
|---|---|---|
| `accent` | `#3CCFE6` | Live readouts, the live indicator, links, primary button fill. |
| `accent-hover` | `#6ADCEE` | Hover state for links and primary buttons. |
| `accent-active` | `#22B3CA` | Pressed state for primary buttons; selected tab underline; 2 px left border on a selected row. |
| `focus-ring` | `#8FE6F5` | Keyboard focus outline on every focusable element. |

### Alert ramp

Space weather alerts follow the NOAA Space Weather Scales, which rate geomagnetic storms (G), solar radiation storms (S), and radio blackouts (R) on the same five levels. The level names below are NOAA's own, taken from the SWPC scales explanation at <https://www.spaceweather.gov/noaa-scales-explanation>.

| Token | Hex | Level | NOAA name | Icon shape |
|---|---|---|---|---|
| `alert-none` | `#9AA8BA` | none | (no alert) | Hollow circle |
| `alert-1` | `#F5EE4C` | 1 | Minor | Circle with one bar |
| `alert-2` | `#FFD28C` | 2 | Moderate | Triangle with one bar |
| `alert-3` | `#FDA44A` | 3 | Strong | Triangle with exclamation mark |
| `alert-4` | `#FB7472` | 4 | Severe | Octagon with exclamation mark |
| `alert-5` | `#D65BFF` | 5 | Extreme | Filled octagon with double exclamation mark |

Lightness falls steadily from level 1 to level 5, so the order of the levels survives in grayscale and under every common color vision deficiency. Level 5 is a violet rather than a deeper red: it keeps level 4 and level 5 apart in hue as well as lightness, and it keeps the ramp from sliding into dark reds.

#### Color vision and grayscale check

The ramp was chosen against simulated protanopia, deuteranopia, and tritanopia and against grayscale. The numbers below are printed by [`tools/alert_palette_check.py`](tools/alert_palette_check.py) (run `python3 docs/design/tools/alert_palette_check.py`), which reads the colors from this file. It simulates each deficiency with the severity 1.0 matrices of Machado, Oliveira and Fernandes (2009), compares colors with CIEDE2000 (Sharma, Wu and Dalal, 2005), and checks itself against the published CIEDE2000 test pairs before it reports anything. The review cutoffs it uses, a CIEDE2000 difference under 10 and a luminance ratio under 1.15, are working values I chose for review, not published standards.

Smallest CIEDE2000 difference between any two of `alert-none`, `alert-1` to `alert-5`, and `stale`:

| View | Smallest difference | Pair | Pairs under the cutoff of 10 |
|---|---|---|---|
| Normal vision | 12.81 | `alert-none` and `stale` | none |
| Protanopia | 9.97 | `alert-none` and `stale` | `alert-none` and `stale` 9.97 |
| Deuteranopia | 8.60 | `alert-none` and `stale` | `alert-none` and `stale` 8.60; `alert-1` and `alert-2` 9.04; `alert-2` and `alert-3` 9.06 |
| Tritanopia | 10.02 | `alert-3` and `alert-4` | none |

Grayscale, as WCAG relative luminance: `alert-1` 0.811, `alert-2` 0.692, `alert-3` 0.479, `alert-4` 0.342, `alert-5` 0.290. Luminance ratios between neighboring levels, rounded to two decimals, are 1.16 (1 and 2), 1.40 (2 and 3), 1.35 (3 and 4), and 1.15 (4 and 5); to three decimals they are 1.159, 1.403, 1.350, and 1.153. Unlike the contrast tables below, which truncate, these ratios are rounded. `alert-none` (0.384) and `stale` (0.371) sit near `alert-4` in grayscale (ratios 1.11, 1.03, and 1.07), which is one more reason every state also carries its own icon and text.

The ramp used until 2026-10-06 (`#E6D56A`, `#F2B84B`, `#F7923F`, `#FF6A55`, and a magenta `#F46BC8` for level 5) measured 3.56 between level 5 and `alert-none` under deuteranopia, 4.26 to 5.78 between neighboring levels 1 to 4 under deuteranopia, and a grayscale ratio of 1.05 between levels 4 and 5. The script prints that ramp as a comparison.

Still open: `stale` and `accent-active` measure 2.16 under deuteranopia. They never mark the same thing (a stale value always shows the clock icon and the word "Stale"), so this is recorded rather than fixed.

### Data freshness

| Token | Hex | Use |
|---|---|---|
| `stale` | `#A99CD6` | Any readout or alert whose source feed has not updated within its expected interval. |

What a stale source looks like depends on what the API still serves:

* **A value the API still returns, marked stale** (a screening run more than 24 hours past its window start, which `GET /api/screening/current` returns with `stale: true`). The value keeps its number, switches its color to `stale`, gains a clock icon, and shows the text "Stale" with the age of the data (for example "Stale, 14 min"). A stale alert keeps its level icon and label so the last known severity stays readable, but its badge switches to the `stale` treatment so nobody mistakes an old reading for a current one.
* **A value the API replaces with no data** (a space weather scale past its age limit, which `GET /api/space-weather/current` returns as `no_data` with reason `age_limit` and no value). The panel shows the [no data treatment](#states-without-a-level) and the time of the newest record (`freshness_reference`), never the last level. The feed's stale banner carries the `stale` treatment.

Each feed whose newest record is past its expected interval gets a [stale banner](#states-without-a-level) at the top of the page.

### States without a level

These treatments reuse the colors above; they add no new hex values.

| Token | Built from | Icon | Text | Use |
|---|---|---|---|---|
| `badge-no-data` | `text-secondary` text and icon, 1 px dashed `border-strong` outline, no fill | Dashed circle with a diagonal slash | "No data", or "No longer primary" for a series in state `ended` | A space weather scale with no valid current value, for any reason (`rejected`, `zero_run_edge`, `age_limit`, `no_series`). The same treatment with the text "No longer primary" marks an R or S series that is no longer SWPC's primary series because another satellite took over; it reads as no data, never as the last level held. The same treatment, with the short label of its status as the text, marks a watchlist object whose passes were not computed: "No element set", "Element set cannot be used", "Element set too old", "Deep space orbit, not computed", "Cannot be propagated" or "Treated as decayed" ("Not computed ({code})" for a status code the page does not know), followed by the API's reason as plain text. Never drawn like "none", which is a valid value below level 1. A panel in no data also shows its large readout as the words "No data" in `text-secondary` and states the reason and, when there is one, the newest record time. |
| `badge-approach` | `text-primary` text and icon, 1 px solid `border-strong` outline, no fill | Two overlapping circles | "Close approach" | A close approach from a screening run. It never takes an alert ramp color: the screening has one report distance and no levels. |
| `badge-clipped` | `text-secondary` text and icon, 1 px solid `border-strong` outline, no fill | A vertical edge with an arc cut off by it | "Clipped" | A pass cut by its window, in the Rise column when it was already in progress at the window start ("In progress at the window start") or in the Set column when it was still in progress where the search ended ("In progress where the search ended"), each followed by the direction and elevation at that edge. It is neither an alert nor missing data, so it takes neither the ramp nor the dashed outline. |
| `banner-stale` | `surface-1` fill, 1 px `border-subtle` border with a 4 px `stale` left border, `radius-md` | Clock, in `stale` | A lead in `stale`, then the body in `text-primary`. "Stale, <age>." is followed by the feed, the newest record time, and what the page shows meanwhile; "Not updating." by the parts whose refresh failed and when; "Paused." by what was received and that nothing is rechecked until updates resume | One banner per feed whose newest record is older than its expected interval, above the panels. The same treatment with the lead "Not updating." marks a failed refresh: "A refresh of {parts} failed at <time> UTC. What is shown for {it or them} is from the last refresh that succeeded.", where {parts} lists in page order whichever of "the space weather levels", "the screening run" and "recent alerts" failed, and, only when the space weather levels failed and an earlier answer for them exists, the banner adds "The space weather levels were received at <time> UTC." The same treatment with the lead "Paused." comes first while updates are paused. The banners are not a live region: one visually hidden status line speaks only when the set of banners changes, never as an alert. Its lines are "{measurement} is stale; the {scale} scale reads no data.", "The screening run is stale.", "A refresh of {parts} failed.", "Updates are paused.", and "No stale notices remain." once the last banner clears after a successful refresh. |

Contrast: `text-secondary` and `text-primary` are cleared on every surface in the tables below (including `surface-3`, the hover row a Clipped badge can sit on), `border-strong` clears 3:1 as a control edge on every surface (the dashed outline is decorative there, since the word "No data" carries the state), and `stale` clears 4.5:1 as text on `surface-1` (7.01).

### Alert views

The recent alerts list, the alert page (`/alerts/{event_id}`) and one object's close approaches (`/objects/{norad_cat_id}/alerts`) add treatments built only from the tokens above. **No new color, size, spacing, radius, shadow or duration token was needed**; each row says which existing tokens it uses.

| Treatment | Built from | Use |
|---|---|---|
| `badge-new` | `accent` text and a filled dot icon, 1 px solid `accent` outline, no fill, `radius-sm`, badge type | The word "New" next to the kind badge of an item that joined the recent alerts list while the page was open, from a live notice or from a refresh that listed it above the previous newest. Never on items of the first answer, and never on the object page, which takes no live notices. It clears, with the item's left border, 10 minutes after the item joined the list; marks made before leaving the console do not come back. Alerts that arrived while another page was shown, and that match the filter, are marked New for 10 minutes when the console is shown again, and the New alerts log sums them up once, naming what the list holds under its filter: "1 {one} arrived while you were away." or "{N} {many} arrived while you were away.", where {one} and {many} are "alert" and "alerts", "close approach" and "close approaches", or "space weather alert" and "space weather alerts". When the count can be short it reads "{N} or more {many} arrived while you were away.": a refresh while away could not count everything it added, the console comes back under another kind than the one kind it was left under, or more than 20 alerts of a kind the list shows arrived, since only the newest page of each kind is kept. Nothing is logged while another page is shown. It is not announced, since the New alerts log already spoke the item. `accent` is used because the badge marks a live arrival, the accent's one meaning; it is never a severity. |
| `link-title` | `text-primary`, underlined with a 3 px offset; hover `accent-hover`; `scroll-margin-block: space-2` | An alert item's title, which links to the alert's own page. The scroll margin keeps room for the focus ring when focus or a return from an alert page scrolls the link to the window's edge. It stays `text-primary` rather than `accent` so a list of titles does not read as a column of live readouts; the underline identifies it as a link (color usage rule 6). Every other link, including the object names, uses `accent` and an underline as before. |
| `filter-option` | Native radio inside its label; unselected: `text-secondary` text, 1 px `border-strong` outline, no fill; hover `surface-3`; selected: `surface-3` fill, `text-primary` text, 1 px `accent-active` outline and the check icon; `radius-md`, at least 32 px tall, `space-3` side padding, `space-1` gaps, wrapping | The kind filter on recent alerts: a fieldset with the legend "Show" (label type) and three options, "All", "Close approaches", "Space weather". The check icon carries the selection, so it never rests on color. The focus ring is drawn on the label when its radio has keyboard focus. |
| `list-end` | `text-caption` count line, a secondary button, and a persistent `text-secondary` status line | The end of a paged alert list. While older items exist: "{n} shown, newest first" and a "Load older" button. At the end: "All {n} stored {kind} are shown, newest first.", where {kind} is "alerts", "close approaches" or "space weather alerts" ("The one stored close approach is shown." for one). The status line reads "Loading older {kind}.", "{k} older {kind} loaded." ("1 older close approach loaded." for one), "{k} older {kind} loaded. No older {kind} are stored.", "No older {kind} are stored." when the last press added none, or "Older {kind} could not be loaded. Use Load older to try again." While a request is out the button follows [Disabled controls](#disabled-controls). |
| `held-bar` | `surface-2` fill, 1 px `border-strong` border, `radius-md`, `text-primary` text, a secondary button | Above the list while new alerts are held because focus is inside the list or an item's form is open: "{N} new alerts are waiting. They join the list when focus leaves it." ("1 new alert is waiting. It joins the list when focus leaves it."), and a "Show new alerts" button that adds them and moves focus to the first one's title link. Not a live region: the New alerts log speaks each held alert once, worded as waiting, "New alert waiting above the list, {rest of its sentence}" for one and "{N} new alerts waiting above the list. The top one, {rest of its sentence}" for several. An entry while others already wait gives the total the bar shows: "{k} more new alerts waiting above the list, {N} in all. The top one, {rest of its sentence}", or "1 more new alert waiting above the list, {N} in all. The new one, {rest of its sentence}" for one. A level that was left keeps its own lead: "New alert waiting above the list. Below G1: {rest of its sentence}", "1 more new alert waiting above the list, {N} in all. The new one. Below G1: {rest of its sentence}". Alerts listed within 2 s of the first one waiting to be spoken are spoken together; when some joined the list before the hold began and the rest wait in the bar, the log adds two entries, the listed ones first in their usual words ("New alert, {rest of its sentence}" or "{N} new alerts. Top of the list, {rest of its sentence}"), then the waiting ones. When the bar appears while focus is on an item below it, the window scrolls by the height the bar added, so the focused item stays where it was on screen. |
| `back-link` | `accent` link, underlined, a CSS chevron pointing left, at least 32 px tall | "Back to the console" at the top of each alert page, linking to `/`, inside a `nav` landmark named "Page" just before `main`. |
| `page-title` | `text-display` (`text-h1` under 640 px), wrapping anywhere | The one `h1` of an alert page. On these pages the header keeps its layout but shows the name SpaceFlux as plain text, not as a heading. |
| `detail-facts` | Description list; terms `text-muted` caption size, values `text-primary` with `text-muted` sub lines; two columns (terms at least 144 px) from 640 px up, one column below | The fields of one alert and of one catalog entry. A field the answer does not have leaves its row out; no row reads "null". Event ids use `font-mono` and wrap anywhere. |
| `slot-reserved` | 1 px dashed `border-strong`, `radius-md`, `text-muted` text, label type for its word | A design marker only, in the design file: where the acknowledge action and its note go on the alert page once sign in exists. It is not built. |

Contrast, from the tables below: `accent` text and outline 8.57:1 on `surface-2` (the alert item fill) and 9.38:1 on `surface-1`; `text-primary` 13.55:1 on `surface-2`; the selected option's `text-primary` 12.08:1 and `accent-active` outline 5.68:1 on `surface-3`; an unselected option's `text-secondary` 8.58:1 and `border-strong` 3.98:1 on `surface-1`; the held bar's `border-strong` 3.63:1 on `surface-2`; `text-muted` 5.73:1 on `surface-1`.

**Recent alerts under a filter.** Choosing an option replaces the list with the first page of that kind, and the panel's status line reads "Loading recent alerts.", "Loading close approaches." or "Loading space weather alerts.". Once the first page for the new filter is drawn, the same line gives the result, visually hidden since the list or its empty state already shows it: the end line's words when the whole stored list is shown ("All 7 stored close approaches are shown, newest first."), "{n} {kind} shown, newest first. Older ones can be loaded." while older ones exist, or the empty state's lead ("No close approaches are stored yet."). It is emptied without a word as soon as the list shown changes (a live notice or a refresh adds an alert, Load older adds a page, or the filter changes again), so it never stays behind as a count that is no longer true and a repeated result is spoken again; the first list the panel shows says nothing, and the end line itself is not live. While no list is shown the line is visible, for loading and failure. A failure reads "{Recent alerts, Close approaches or Space weather alerts} could not be loaded. The page tries again at the next refresh." ("... when updates resume." while updates are paused). Empty: "No close approaches are stored yet." with "A close approach is listed when a screening run finds a searched pair within its report distance. Only searched pairs are covered, so an empty list does not mean nothing came close.", or "No space weather alerts are stored yet." with the explanation the unfiltered empty state uses. Only matching live notices join the list and are announced. The summary for alerts the refresh after a pause adds names what the list holds too: "1 {one} arrived while paused.", "{N} {many} arrived while paused.", or "{N} or more {many} arrived while paused." when that refresh could not count them all.

**Alert page.** Badge, title sentence and caption first (the same ones the list uses, a level badge in its outline form, with every time carrying its date), then three panels in this order: Acknowledgement (the state line, then the reserved slot), the kind's own fields ("Close approach" or "Space weather level"), and Record (event id, rules version, produced, received). Close approach rows: closest approach, miss distance, relative speed, watchlist object and other object (each name links to that object's close approaches, with its catalog number and element age under it), screening run with its window. A space weather page title follows the state: "{label} level, {measurement}", "Below {scale}1, {measurement}", "No data, {measurement}", "No longer primary, {measurement}", or "Restated as no data, {measurement}" for a restatement, whose sentence is "The {measurement} sample at {time} UTC, published as below {scale}1, is restated as no data." State words: "Level {code}, {NOAA name}", "Below {scale}1", "No data", "No longer SWPC's primary series"; a missing level number reads "Level {scale}, level number missing" and a number outside 1 to 5 "Level {code}, outside the NOAA scale's 1 to 5". Change: "State change", "Revised Kp value", "Restated as no data" or "Refresh, same state", with the sub line "No earlier state was held, for example after a restart." when there is no Before row. Space weather rows, each only when set: State, Before, Change, Measurement, Value, Sample or Interval, Estimated (G only), Newest record, Reason and No data since (left out for a restatement), Restated because of ("the zero run starting {time} UTC"), Taken over by, Timer tick, and Source (product, the source address as plain text and never a link, and the fetch time). An object with no catalog name is "Object {number}" where it starts a heading or a title and "object {number}" in the middle of one: page title "Object 57036 and object 27958", document title "Close approach, object 57036 and object 27958, SpaceFlux", list title "Object 57036 and object 27958, miss distance ..." (only a catalog name takes the number in brackets, "OBJECT AJ (57036) and object 27958, ..."), and the New alerts log keeps the list title ("New alert, close approach: Object 57036 and object 27958, ..."). A screening run shows its title, the Record panel and a pointer to the console's screening panel, and no acknowledgement panel.

**Object page.** Title "Close approaches of {name}" ("object {number}", lower case, when the catalog has no name; the document title adds the number in brackets after a name), a caption with the catalog number and "Listed whether it was the watchlist object or the other object, newest first.", a Catalog entry panel (name, international designator, newest element set epoch and last fetch, and "This is the newest element set seen for the object, not necessarily the one a screening run used."), then the close approach note ("Two public element sets propagated with SGP4 that come within the report distance of the screening run that found them. Not a collision probability or a conjunction assessment; distances carry an uncertainty of kilometres.", the same on the alert page; an alert does not carry its run's report distance, so these pages name none) and the list with Load older.

**States of both pages.** Each page loads once on entry and is not refreshed by the one minute poll. One persistent status line under the title reads "Loading the alert." or "Loading object {number}.", then on failure "The alert could not be loaded." or "Object {number} could not be loaded." with a "Try again" button. Not stored: "Alert not found" with "No alert with this event id is stored." and the event id as text; no catalog row: "No catalog entry for object {number}" with "Object {number} has no catalog entry, so this page cannot list its close approaches. Any that are stored still appear in the console's recent alerts." (a missing row does not mean the object does not exist or has no close approaches, so it never reads as not found or as no alerts) Refused input: "Not an event id" with "This address does not hold an event id the API accepts: it is empty or longer than 512 characters."; "Not a catalog number" with "A catalog number is a whole number from 0 to 999999999." No close approaches: "No close approaches of {name} are stored." with "Close approaches are stored only for pairs a screening run searched, so an empty list does not mean nothing came close." The document title is the page title followed by ", SpaceFlux"; a close approach page puts "Close approach, " in front, since its page title names only the two objects. The footer disclaimer appears on every page.

## Color usage rules

1. **Color is never the only signal.** Every alert shows three things together: the icon shape for its level, the scale and level code (for example "G3"), and the NOAA name (for example "Strong"). Stale data always carries the clock icon and the word "Stale". A reader with no color vision loses nothing.
2. **Alert badges come in two forms.** The solid form uses the alert color as the fill with `on-fill` text and icon. The outline form uses `border-strong` or the alert color as a 1 px outline on a surface, with the text and icon drawn in the alert color. Both forms are cleared in the contrast tables.
3. **Accent means live, alerts mean severity.** Cyan never marks a warning, and alert colors never mark a link or button.
4. **Alert colors are for alerts.** Charts do not borrow alert colors for ordinary series. When a chart plots an alert level over time, it uses the matching alert token for that level only.
5. **Close approaches stay out of the ramp.** The alert ramp and the NOAA names belong to the space weather scales. A close approach uses the neutral `badge-approach` treatment, because the screening has a single report distance and no severity levels to map onto the ramp.
6. **Links in running text are always underlined.** Cyan against body text is too close in lightness to tell a link apart by color, so the underline carries that job. Links that stand alone as navigation or buttons are identified by their shape and position instead.
7. **A selected row is more than a color change.** Hover and selected rows share the `surface-3` fill, so a selected row also gets a 2 px `accent-active` left border (at least 5.68:1 on every surface). Hover stays fill only, which keeps the two states distinct.
8. **Nothing here claims operational authority.** Alert styling signals severity within a public data demonstration and is never presented as an official warning.

## Typography

Both families are from the IBM Plex superfamily and are licensed under the SIL Open Font License 1.1, with the Reserved Font Name "Plex" (IBM's license text, copyright 2017 IBM Corp.).

**The fonts are self hosted.** The dashboard serves the font files from its own origin; it never loads fonts from a font CDN or any other third party. The files come from IBM's own packages, checked on the npm registry and in the [IBM/plex](https://github.com/IBM/plex) repository on 2026-10-06:

| Package | Version | License | Files used |
|---|---|---|---|
| `@ibm/plex-sans` | 1.1.0 | OFL-1.1 | `fonts/split/woff2/IBMPlexSans-{Regular,Medium,SemiBold}-{Latin1,Pi}.woff2` |
| `@ibm/plex-mono` | 2.5.0 | OFL-1.1 | `fonts/split/woff2/IBMPlexMono-{Regular,Medium}-{Latin1,Pi}.woff2` |

* Only the weights the type scale uses ship: Sans 400, 500, and 600; Mono 400 and 500.
* The files are IBM's own Latin1 and Pi subsets, used unmodified, each declared with the `unicode-range` from IBM's CSS in the same package, so the Pi file (which holds characters such as the greater than or equal sign) downloads only when a page uses one of its characters. I do not cut subsets of my own: the license's Reserved Font Name terms govern modified versions, and IBM's subsets already cover the interface.
* Together the ten files are 151,456 bytes (Latin1: Sans 65,204, Mono 35,412; Pi: Sans 23,092, Mono 27,748), measured from the package tarballs.
* `LICENSE.txt` from each package ships next to the font files.
* The files are added with the dashboard build, not committed ahead of it, and the versions are pinned there.
* The fallbacks in the tokens below stay in place for any file that fails to load.

| Token | Value | Use |
|---|---|---|
| `font-ui` | `"IBM Plex Sans", system-ui, sans-serif` | All interface text: headings, labels, body, buttons. |
| `font-mono` | `"IBM Plex Mono", ui-monospace, monospace` | Every numeric readout, timestamp, NORAD catalog number, and coordinate. Always with `font-variant-numeric: tabular-nums`. |

| Token | Size / line height | Weight | Use |
|---|---|---|---|
| `text-display` | 28 px / 36 px | 600 | Page title. One per view. |
| `text-h1` | 22 px / 30 px | 600 | Panel group heading. |
| `text-h2` | 18 px / 26 px | 600 | Panel heading. |
| `text-body` | 14 px / 22 px | 400 | Default body and table text. |
| `text-label` | 12 px / 16 px | 500 | Field labels, column headers, badge text. Letter spacing 0.04 em, uppercase allowed. |
| `text-caption` | 12 px / 16 px | 400 | Timestamps, units, footnotes. |
| `text-readout` | 24 px / 28 px | 500 | Large live numeric readouts (Kp index, solar wind speed). Uses `font-mono`. |

Rules:

* Text never goes below 12 px.
* Contrast in this file is checked at the normal text threshold of 4.5:1, so every text color is safe at every size above. Nothing relies on the large text allowance.
* Numbers that update live always use `font-mono` with tabular figures so digits do not shift width as they change.

## Spacing

A 4 px base grid.

| Token | Value |
|---|---|
| `space-0` | 0 |
| `space-1` | 4 px |
| `space-2` | 8 px |
| `space-3` | 12 px |
| `space-4` | 16 px |
| `space-5` | 24 px |
| `space-6` | 32 px |
| `space-7` | 48 px |
| `space-8` | 64 px |

Rules: padding inside panels is `space-4`; gaps between panels are `space-4` on narrow screens and `space-5` on wide ones; table cell padding is `space-2` vertical by `space-3` horizontal. Interactive targets are at least 24 by 24 px, and primary controls are at least 32 px tall.

## Radius

| Token | Value | Use |
|---|---|---|
| `radius-none` | 0 | Table cells, chart plot areas. |
| `radius-sm` | 2 px | Badges, inputs, small buttons. |
| `radius-md` | 4 px | Buttons, cards, panels. |
| `radius-lg` | 8 px | Popovers, dialogs. |
| `radius-full` | 9999 px | The live indicator dot and circular icon buttons. |

Corners stay tight on purpose. A console reads as precise, not soft.

## Elevation

On a dark theme, shadows barely read, so elevation comes mainly from the surface steps above, with a shadow only for layers that float over content.

| Token | Surface | Shadow | Use |
|---|---|---|---|
| `elevation-0` | `bg-base` | none | Page canvas. |
| `elevation-1` | `surface-1` | none | Panels and cards. |
| `elevation-2` | `surface-2` | none | Nested content inside a panel. |
| `elevation-3` | `surface-3` | `0 8px 24px rgba(0, 0, 0, 0.45)` | Popovers, menus, dialogs, toasts. |

## Focus

Every focusable element shows a 2 px solid `focus-ring` outline with a 2 px offset, drawn only on keyboard focus (`:focus-visible`). The offset leaves a gap of the underlying surface between the control and the ring, so the ring is measured against the surface (at least 10:1) rather than against the control. That matters for primary buttons, whose `accent` fill sits too close in lightness to `focus-ring` (1.31:1) to separate them without the gap.

Two rules follow from that:

* Never draw an inset focus ring on an accent filled control.
* Where a container would clip the offset ring (a scrolling list, a table cell, a toolbar with tight padding), give the container enough padding to keep the 2 px gap rather than dropping the offset.

## Disabled controls

A button that cannot be used right now (Refresh passes while its request is out, marked with the `disabled` attribute or with `aria-disabled="true"`) keeps its shape and loses its emphasis: no fill, `text-muted` text, a `border-subtle` outline, and no hover or press change. Its label does not change; its disabled state shows the request is out, and the panel's status line reports the result. WCAG does not require contrast for an inactive control, but `text-muted` still clears 4.5:1 on every surface, so the label stays readable.

## Motion

| Token | Value | Use |
|---|---|---|
| `duration-instant` | 80 ms | Hover and press color changes. |
| `duration-fast` | 160 ms | Menus, popovers, tooltips opening and closing. |
| `duration-base` | 240 ms | Panel expand and collapse, toast entry. |
| `duration-slow` | 400 ms | New alert row border fading in. |
| `ease-standard` | `cubic-bezier(0.2, 0, 0, 1)` | Elements that move or resize. |
| `ease-exit` | `cubic-bezier(0.4, 0, 1, 1)` | Elements that leave the screen. |

Rules:

1. **Live values do not animate their digits.** A readout swaps its value in place; counting or rolling animations misstate how fast the data really changed.
2. **The live indicator always carries a label.** The dot sits next to a visible "Live" text label in `text-secondary`, so the state never depends on the dot alone.
3. **The live indicator pulses only on first load.** When the dashboard first loads, the dot pulses on a 2 s opacity cycle between 100 and 50 percent for at most 5 s, then holds steady at full opacity. Later refreshes never restart the pulse. The 50 percent floor keeps the dot at 3:1 or better on every surface (see the contrast tables). When its feed goes stale, the dot switches to `stale` and the label changes to "Stale".
   * **Updates can be paused.** The header carries a toggle button labelled "Pause updates" that stops polling; while paused it reads "Resume updates". While paused, the dot is hollow (a 2 px `border-strong` ring, no fill) and the label reads "Paused" next to the time of the last update, so a paused page never reads as live. A "Paused." banner leads the banners: "Updates are paused. The space weather levels below were received at <time> UTC. Nothing below is checked against the age limits again until updates resume.", or "Updates are paused. Nothing has been received yet." before the first answer. When a refresh had failed at the moment of the pause, it ends with "A refresh of {parts} failed at <time> UTC before the pause."
4. **New alerts are marked with a word badge and a left border, not a tint.** A newly arrived alert row gets the [`badge-new`](#alert-views) word badge and a 2 px left border in its alert color (`border-strong` for a row with no level color: a close approach, a level that was left, no data, ended), which may fade in over `duration-slow`. The row background never takes a tint, because any tint over `surface-3` pulls `text-muted` below 4.5:1. Nothing flashes, and nothing flashes more than three times per second anywhere in the interface.
5. **Reduced motion is honored.** Under `prefers-reduced-motion: reduce`, every duration becomes 0 ms, the live indicator never pulses and stays at full opacity, and the new alert border appears without fading in.

## Favicon

[`favicon.svg`](favicon.svg) is an orbit mark: a light body in `text-primary` circled by a tilted orbit and a satellite dot in `accent`, on a `bg-base` tile with corners of `radius-md` at 16 px. It uses only these tokens, with no text, so it reads at 16 px.

* The tile gives the mark its own dark ground, so it reads the same on light and dark browser chrome. On a dark tab the tile's edge fades into the chrome and the cyan and light shapes carry the mark.
* On the tile, `accent` measures 10.29:1 and `text-primary` 16.27:1 (the `bg-base` column of the tables below).
* A thin `bg-base` ring around the satellite dot keeps it separate from the orbit line at small sizes.
* The cyan here marks the product, not a live value; inside the interface the accent keeps its meaning of "live".

## Contrast ratios

Ratios use the WCAG 2.2 relative luminance formula and are truncated (never rounded up) to two decimals. Thresholds: 4.5:1 for text, 3:1 for non text UI such as control borders and focus indicators.

### Foreground on backgrounds

| Token | bg-base | surface-1 | surface-2 | surface-3 | Required |
|---|---|---|---|---|---|
| `text-primary` | 16.27 | 14.83 | 13.55 | 12.08 | 4.5 |
| `text-secondary` | 9.42 | 8.58 | 7.84 | 6.99 | 4.5 |
| `text-muted` | 6.29 | 5.73 | 5.24 | 4.67 | 4.5 |
| `accent` | 10.29 | 9.38 | 8.57 | 7.64 | 4.5 |
| `accent-hover` | 11.96 | 10.90 | 9.96 | 8.88 | 4.5 |
| `accent-active` | 7.65 | 6.97 | 6.37 | 5.68 | 4.5 |
| `alert-none` | 7.94 | 7.23 | 6.61 | 5.89 | 4.5 |
| `alert-1` | 15.74 | 14.35 | 13.11 | 11.69 | 4.5 |
| `alert-2` | 13.57 | 12.37 | 11.30 | 10.08 | 4.5 |
| `alert-3` | 9.67 | 8.82 | 8.06 | 7.18 | 4.5 |
| `alert-4` | 7.17 | 6.53 | 5.97 | 5.32 | 4.5 |
| `alert-5` | 6.21 | 5.66 | 5.17 | 4.61 | 4.5 |
| `stale` | 7.69 | 7.01 | 6.40 | 5.71 | 4.5 |
| `focus-ring` | 13.53 | 12.34 | 11.27 | 10.05 | 3.0 |
| `border-strong` | 4.37 | 3.98 | 3.63 | 3.24 | 3.0 |
| `border-subtle` | 1.57 | 1.43 | 1.30 | 1.16 | decorative only |

`border-strong` was first drafted as `#5A6E8A`, which measured 2.73:1 on `surface-3`. It was lightened to `#647A98` to clear 3:1 on every surface. `border-subtle` is intentionally low contrast and is only allowed for decorative dividers, which WCAG does not require to meet a ratio.

### Text on solid fills

| Pair | Ratio | Required |
|---|---|---|
| `on-fill` on `accent` | 10.20 | 4.5 |
| `on-fill` on `accent-hover` | 11.86 | 4.5 |
| `on-fill` on `accent-active` | 7.58 | 4.5 |
| `on-fill` on `alert-none` | 7.87 | 4.5 |
| `on-fill` on `alert-1` | 15.61 | 4.5 |
| `on-fill` on `alert-2` | 13.46 | 4.5 |
| `on-fill` on `alert-3` | 9.59 | 4.5 |
| `on-fill` on `alert-4` | 7.11 | 4.5 |
| `on-fill` on `alert-5` | 6.16 | 4.5 |
| `on-fill` on `stale` | 7.62 | 4.5 |

### Live indicator at its dimmest

The dot is `accent` composited at 50 percent opacity over the background, and it must clear 3:1 as non text UI.

| Pair | bg-base | surface-1 | surface-2 | surface-3 | Required |
|---|---|---|---|---|---|
| `accent` at 50 percent opacity | 3.33 | 3.27 | 3.17 | 3.02 | 3.0 |

Every pair clears its threshold. Any new token, or any change to an existing hex value, is rechecked with the script below before it lands.

### Reproducing the ratios

```python
import math

def channel(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def luminance(hex_color):
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)

def ratio(a, b):
    hi, lo = sorted((luminance(a), luminance(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)

def blend(fg, bg, alpha):
    f, b = fg.lstrip("#"), bg.lstrip("#")
    mixed = (round(alpha * int(f[i:i + 2], 16) + (1 - alpha) * int(b[i:i + 2], 16)) for i in (0, 2, 4))
    return "#" + "".join(f"{c:02X}" for c in mixed)

def floor2(v):
    return f"{math.floor(v * 100) / 100:.2f}"

BACKGROUNDS = {"bg-base": "#0A0F17", "surface-1": "#111A26", "surface-2": "#172233", "surface-3": "#1E2B3F"}
FOREGROUNDS = {
    "text-primary": "#E6EDF5", "text-secondary": "#A9B7C9", "text-muted": "#8595AB",
    "accent": "#3CCFE6", "accent-hover": "#6ADCEE", "accent-active": "#22B3CA",
    "alert-none": "#9AA8BA", "alert-1": "#F5EE4C", "alert-2": "#FFD28C", "alert-3": "#FDA44A",
    "alert-4": "#FB7472", "alert-5": "#D65BFF", "stale": "#A99CD6",
    "focus-ring": "#8FE6F5", "border-strong": "#647A98", "border-subtle": "#26364D",
}
ON_FILL = "#06111A"
FILLS = ["accent", "accent-hover", "accent-active", "alert-none", "alert-1", "alert-2",
         "alert-3", "alert-4", "alert-5", "stale"]

print("| Token | " + " | ".join(BACKGROUNDS) + " |")
for name, color in FOREGROUNDS.items():
    print(f"| `{name}` | " + " | ".join(floor2(ratio(color, b)) for b in BACKGROUNDS.values()) + " |")
print()
for name in FILLS:
    print(f"| `on-fill` on `{name}` | {floor2(ratio(ON_FILL, FOREGROUNDS[name]))} |")
print()
LIVE_DOT_MIN_OPACITY = 0.5
print("| `accent` at 50 percent opacity | " + " | ".join(
    floor2(ratio(blend(FOREGROUNDS["accent"], b, LIVE_DOT_MIN_OPACITY), b)) for b in BACKGROUNDS.values()) + " |")
```
