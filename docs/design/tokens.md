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
| `alert-1` | `#E6D56A` | 1 | Minor | Circle with one bar |
| `alert-2` | `#F2B84B` | 2 | Moderate | Triangle with one bar |
| `alert-3` | `#F7923F` | 3 | Strong | Triangle with exclamation mark |
| `alert-4` | `#FF6A55` | 4 | Severe | Octagon with exclamation mark |
| `alert-5` | `#F46BC8` | 5 | Extreme | Filled octagon with double exclamation mark |

Level 5 is a hot magenta rather than a deeper red. That keeps level 4 and level 5 clearly separable at a glance and keeps the ramp from sliding into dark reds.

### Data freshness

| Token | Hex | Use |
|---|---|---|
| `stale` | `#A99CD6` | Any readout or alert whose source feed has not updated within its expected interval. |

A stale value keeps its last known number, switches its color to `stale`, gains a clock icon, and shows the text "Stale" with the age of the data (for example "Stale, 14 min"). A stale alert keeps its level icon and label so the last known severity stays readable, but its badge switches to the `stale` treatment so nobody mistakes an old storm reading for a current one.

## Color usage rules

1. **Color is never the only signal.** Every alert shows three things together: the icon shape for its level, the scale and level code (for example "G3"), and the NOAA name (for example "Strong"). Stale data always carries the clock icon and the word "Stale". A reader with no color vision loses nothing.
2. **Alert badges come in two forms.** The solid form uses the alert color as the fill with `on-fill` text and icon. The outline form uses `border-strong` or the alert color as a 1 px outline on a surface, with the text and icon drawn in the alert color. Both forms are cleared in the contrast tables.
3. **Accent means live, alerts mean severity.** Cyan never marks a warning, and alert colors never mark a link or button.
4. **Alert colors are for alerts.** Charts do not borrow alert colors for ordinary series. When a chart plots an alert level over time, it uses the matching alert token for that level only.
5. **Orbital alerts share the ramp but not the names.** The five levels and their colors are shared with orbital alerts so severity reads the same everywhere, but the NOAA names belong to space weather. Orbital alerts use their own labels, which are defined alongside the screening thresholds.
6. **Links in running text are always underlined.** Cyan against body text is too close in lightness to tell a link apart by color, so the underline carries that job. Links that stand alone as navigation or buttons are identified by their shape and position instead.
7. **A selected row is more than a color change.** Hover and selected rows share the `surface-3` fill, so a selected row also gets a 2 px `accent-active` left border (at least 5.68:1 on every surface). Hover stays fill only, which keeps the two states distinct.
8. **Nothing here claims operational authority.** Alert styling signals severity within a public data demonstration and is never presented as an official warning.

## Typography

Both families are from the IBM Plex superfamily and are licensed under the SIL Open Font License.

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
3. **The live indicator pulses briefly, then holds.** After each update the dot pulses on a 2 s opacity cycle between 100 and 50 percent for at most 5 s, then holds steady at full opacity until the next update. The 50 percent floor keeps the dot at 3:1 or better on every surface (see the contrast tables). When its feed goes stale, the dot stops pulsing, switches to `stale`, and the label changes to "Stale".
4. **New alerts are marked with a left border, not a tint.** A newly arrived alert row gets a 2 px left border in its alert color, which may fade in over `duration-slow`. The row background never takes a tint, because any tint over `surface-3` pulls `text-muted` below 4.5:1. Nothing flashes, and nothing flashes more than three times per second anywhere in the interface.
5. **Reduced motion is honored.** Under `prefers-reduced-motion: reduce`, every duration becomes 0 ms, the live indicator never pulses and stays at full opacity, and the new alert border appears without fading in.

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
| `alert-1` | 12.88 | 11.74 | 10.73 | 9.56 | 4.5 |
| `alert-2` | 10.72 | 9.78 | 8.93 | 7.96 | 4.5 |
| `alert-3` | 8.35 | 7.61 | 6.95 | 6.20 | 4.5 |
| `alert-4` | 6.80 | 6.20 | 5.66 | 5.05 | 4.5 |
| `alert-5` | 7.11 | 6.48 | 5.92 | 5.28 | 4.5 |
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
| `on-fill` on `alert-1` | 12.77 | 4.5 |
| `on-fill` on `alert-2` | 10.63 | 4.5 |
| `on-fill` on `alert-3` | 8.28 | 4.5 |
| `on-fill` on `alert-4` | 6.75 | 4.5 |
| `on-fill` on `alert-5` | 7.05 | 4.5 |
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
    "alert-none": "#9AA8BA", "alert-1": "#E6D56A", "alert-2": "#F2B84B", "alert-3": "#F7923F",
    "alert-4": "#FF6A55", "alert-5": "#F46BC8", "stale": "#A99CD6",
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
