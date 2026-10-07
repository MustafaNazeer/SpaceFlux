"""Color vision deficiency and grayscale check for the alert ramp.

Reads the current colors from ../tokens.md, simulates protanopia, deuteranopia
and tritanopia, and reports for the current ramp and each candidate palette:

* CIEDE2000 color difference between every pair of state colors (alert-none,
  alert-1 to alert-5, stale) under normal vision and each simulation;
* WCAG relative luminance of each color and the luminance ratio between pairs,
  which is what is left in grayscale;
* WCAG contrast of each candidate color on every surface and under on-fill text.

Simulation: Machado, Oliveira and Fernandes (2009), "A Physiologically-based
Model for Simulation of Color Vision Deficiency", IEEE TVCG 15(6), severity 1.0
matrices as published at
https://www.inf.ufrgs.br/~oliveira/pubs_files/CVD_Simulation/CVD_Simulation.html,
applied to linear RGB, the result clamped and re-encoded as sRGB.
Lab uses the D65 white point. CIEDE2000 follows Sharma, Wu and Dalal (2005).

Run from anywhere: python3 docs/design/tools/alert_palette_check.py
Standard library only. Paths derive from this file's location.
"""

import itertools
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOKENS = os.path.join(os.path.dirname(HERE), "tokens.md")

MACHADO = {
    "protanopia": [[0.152286, 1.052583, -0.204868],
                   [0.114503, 0.786281, 0.099216],
                   [-0.003882, -0.048116, 1.051998]],
    "deuteranopia": [[0.367322, 0.860646, -0.227968],
                     [0.280085, 0.672501, 0.047413],
                     [-0.011820, 0.042940, 0.968881]],
    "tritanopia": [[1.255528, -0.076749, -0.178779],
                   [-0.078411, 0.930809, 0.147602],
                   [0.004733, 0.691367, 0.303900]],
}

STATES = ["alert-none", "alert-1", "alert-2", "alert-3", "alert-4", "alert-5", "stale"]
LEVELS = ["alert-1", "alert-2", "alert-3", "alert-4", "alert-5"]
SURFACES = ["bg-base", "surface-1", "surface-2", "surface-3"]
NON_ALERT = ["accent", "accent-active", "focus-ring", "text-primary", "text-secondary"]

# Working review threshold, not a standard: pairs below it are listed for review.
REVIEW_DE = 10.0
REVIEW_LUM_RATIO = 1.15

# The ramp in use before 2026-10-06, kept for comparison.
CANDIDATES = {
    "previous ramp (before 2026-10-06)": {
        "alert-1": "#E6D56A", "alert-2": "#F2B84B", "alert-3": "#F7923F",
        "alert-4": "#FF6A55", "alert-5": "#F46BC8"},
}

# Test data published with Sharma, Wu and Dalal (2005), all 34 pairs:
# https://hajim.rochester.edu/ece/sites/gsharma/ciede2000/dataNprograms/ciede2000testdata.txt
# Columns: L1, a1, b1, L2, a2, b2, expected CIEDE2000.
SHARMA_PAIRS = [
    ((50.0000, 2.6772, -79.7751), (50.0000, 0.0000, -82.7485), 2.0425),
    ((50.0000, 3.1571, -77.2803), (50.0000, 0.0000, -82.7485), 2.8615),
    ((50.0000, 2.8361, -74.0200), (50.0000, 0.0000, -82.7485), 3.4412),
    ((50.0000, -1.3802, -84.2814), (50.0000, 0.0000, -82.7485), 1.0000),
    ((50.0000, -1.1848, -84.8006), (50.0000, 0.0000, -82.7485), 1.0000),
    ((50.0000, -0.9009, -85.5211), (50.0000, 0.0000, -82.7485), 1.0000),
    ((50.0000, 0.0000, 0.0000), (50.0000, -1.0000, 2.0000), 2.3669),
    ((50.0000, -1.0000, 2.0000), (50.0000, 0.0000, 0.0000), 2.3669),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0009), 7.1792),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0010), 7.1792),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0011), 7.2195),
    ((50.0000, 2.4900, -0.0010), (50.0000, -2.4900, 0.0012), 7.2195),
    ((50.0000, -0.0010, 2.4900), (50.0000, 0.0009, -2.4900), 4.8045),
    ((50.0000, -0.0010, 2.4900), (50.0000, 0.0010, -2.4900), 4.8045),
    ((50.0000, -0.0010, 2.4900), (50.0000, 0.0011, -2.4900), 4.7461),
    ((50.0000, 2.5000, 0.0000), (50.0000, 0.0000, -2.5000), 4.3065),
    ((50.0000, 2.5000, 0.0000), (73.0000, 25.0000, -18.0000), 27.1492),
    ((50.0000, 2.5000, 0.0000), (61.0000, -5.0000, 29.0000), 22.8977),
    ((50.0000, 2.5000, 0.0000), (56.0000, -27.0000, -3.0000), 31.9030),
    ((50.0000, 2.5000, 0.0000), (58.0000, 24.0000, 15.0000), 19.4535),
    ((50.0000, 2.5000, 0.0000), (50.0000, 3.1736, 0.5854), 1.0000),
    ((50.0000, 2.5000, 0.0000), (50.0000, 3.2972, 0.0000), 1.0000),
    ((50.0000, 2.5000, 0.0000), (50.0000, 1.8634, 0.5757), 1.0000),
    ((50.0000, 2.5000, 0.0000), (50.0000, 3.2592, 0.3350), 1.0000),
    ((60.2574, -34.0099, 36.2677), (60.4626, -34.1751, 39.4387), 1.2644),
    ((63.0109, -31.0961, -5.8663), (62.8187, -29.7946, -4.0864), 1.2630),
    ((61.2901, 3.7196, -5.3901), (61.4292, 2.2480, -4.9620), 1.8731),
    ((35.0831, -44.1164, 3.7933), (35.0232, -40.0716, 1.5901), 1.8645),
    ((22.7233, 20.0904, -46.6940), (23.0331, 14.9730, -42.5619), 2.0373),
    ((36.4612, 47.8580, 18.3852), (36.2715, 50.5065, 21.2231), 1.4146),
    ((90.8027, -2.0831, 1.4410), (91.1528, -1.6435, 0.0447), 1.4441),
    ((90.9257, -0.5406, -0.9208), (88.6381, -0.8985, -0.7239), 1.5381),
    ((6.7747, -0.2908, -2.4247), (5.8714, -0.0985, -2.2286), 0.6377),
    ((2.0776, 0.0795, -1.1350), (0.9033, -0.0636, -0.5514), 0.9082),
]


def read_tokens(path):
    colors = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\|\s*`([a-z0-9-]+)`\s*\|\s*`(#[0-9A-Fa-f]{6})`", line)
            if m:
                colors[m.group(1)] = m.group(2).upper()
    needed = STATES + SURFACES + NON_ALERT + ["on-fill"]
    missing = [n for n in needed if n not in colors]
    if missing:
        sys.exit(f"tokens.md is missing {missing}; refusing to guess")
    return colors


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return [int(h[i:i + 2], 16) for i in (0, 2, 4)]


def to_linear(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def to_srgb(v):
    v = min(1.0, max(0.0, v))
    return (12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055) * 255


def simulate(c, matrix):
    lin = [to_linear(x) for x in c]
    return [to_srgb(sum(matrix[i][j] * lin[j] for j in range(3))) for i in range(3)]


def luminance(c):
    r, g, b = (to_linear(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(y1, y2):
    hi, lo = max(y1, y2), min(y1, y2)
    return (hi + 0.05) / (lo + 0.05)


def lab(c):
    r, g, b = (to_linear(x) for x in c)
    x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b

    def f(t):
        return t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116

    fx, fy, fz = f(x / 0.95047), f(y / 1.0), f(z / 1.08883)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def ciede2000(lab1, lab2):
    l1, a1, b1 = lab1
    l2, a2, b2 = lab2
    c_bar = (math.hypot(a1, b1) + math.hypot(a2, b2)) / 2
    g = 0.5 * (1 - math.sqrt(c_bar ** 7 / (c_bar ** 7 + 25 ** 7)))
    a1p, a2p = (1 + g) * a1, (1 + g) * a2
    c1p, c2p = math.hypot(a1p, b1), math.hypot(a2p, b2)

    def hue(b, a):
        return 0.0 if a == 0 and b == 0 else math.degrees(math.atan2(b, a)) % 360

    h1p, h2p = hue(b1, a1p), hue(b2, a2p)
    dl, dc = l2 - l1, c2p - c1p
    if c1p * c2p == 0:
        dh = 0.0
    else:
        dh = h2p - h1p
        if dh > 180:
            dh -= 360
        elif dh < -180:
            dh += 360
    d_h = 2 * math.sqrt(c1p * c2p) * math.sin(math.radians(dh / 2))
    l_bar, cp_bar = (l1 + l2) / 2, (c1p + c2p) / 2
    if c1p * c2p == 0:
        h_bar = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        h_bar = (h1p + h2p) / 2
    elif h1p + h2p < 360:
        h_bar = (h1p + h2p + 360) / 2
    else:
        h_bar = (h1p + h2p - 360) / 2
    t = (1 - 0.17 * math.cos(math.radians(h_bar - 30)) + 0.24 * math.cos(math.radians(2 * h_bar))
         + 0.32 * math.cos(math.radians(3 * h_bar + 6)) - 0.20 * math.cos(math.radians(4 * h_bar - 63)))
    d_theta = 30 * math.exp(-((h_bar - 275) / 25) ** 2)
    r_c = 2 * math.sqrt(cp_bar ** 7 / (cp_bar ** 7 + 25 ** 7))
    s_l = 1 + 0.015 * (l_bar - 50) ** 2 / math.sqrt(20 + (l_bar - 50) ** 2)
    s_c = 1 + 0.045 * cp_bar
    s_h = 1 + 0.015 * cp_bar * t
    r_t = -math.sin(math.radians(2 * d_theta)) * r_c
    return math.sqrt((dl / s_l) ** 2 + (dc / s_c) ** 2 + (d_h / s_h) ** 2 + r_t * (dc / s_c) * (d_h / s_h))


def self_test():
    for a, b, expected in SHARMA_PAIRS:
        got = ciede2000(a, b)
        assert abs(got - expected) < 1e-4, (a, b, got, expected)
    for name, m in MACHADO.items():
        for row in m:
            assert abs(sum(row) - 1) < 1e-5, (name, row)


def views(c):
    out = {"normal": c}
    for name, m in MACHADO.items():
        out[name] = simulate(c, m)
    return out


def floor2(v):
    return f"{math.floor(v * 100) / 100:.2f}"


def report(title, palette, colors):
    print(f"## {title}\n")
    print("| Token | Hex | Luminance | " + " | ".join(f"on {s}" for s in SURFACES) + " | on-fill on it |")
    print("|---|---|---|" + "---|" * (len(SURFACES) + 1))
    on_fill_y = luminance(rgb(colors["on-fill"]))
    worst_contrast = 99.0
    for name in STATES:
        c = rgb(palette[name])
        y = luminance(c)
        on_surfaces = [ratio(y, luminance(rgb(colors[s]))) for s in SURFACES]
        fill = ratio(y, on_fill_y)
        worst_contrast = min(worst_contrast, fill, *on_surfaces)
        print(f"| `{name}` | `{palette[name]}` | {y:.3f} | " + " | ".join(floor2(v) for v in on_surfaces)
              + f" | {floor2(fill)} |")
    print(f"\nLowest contrast of any state color as text on a surface or under on-fill: {floor2(worst_contrast)}"
          f" ({'meets' if worst_contrast >= 4.5 else 'FAILS'} 4.5:1)\n")

    vs = {n: views(rgb(palette[n])) for n in STATES}
    labs = {n: {k: lab(v) for k, v in vs[n].items()} for n in STATES}
    print("Smallest CIEDE2000 difference per view (all state pairs, alert-none and stale included):\n")
    print("| View | Smallest dE2000 | Pair | Pairs under " + f"{REVIEW_DE:g} |")
    print("|---|---|---|---|")
    for view in ["normal"] + list(MACHADO):
        pairs = []
        for a, b in itertools.combinations(STATES, 2):
            pairs.append((ciede2000(labs[a][view], labs[b][view]), a, b))
        pairs.sort()
        low = [f"{a}/{b} {d:.2f}" for d, a, b in pairs if d < REVIEW_DE]
        d, a, b = pairs[0]
        print(f"| {view} | {d:.2f} | {a} / {b} | {'; '.join(low) or 'none'} |")

    print("\nSmallest dE2000 between an alert or stale color and a non alert color"
          " (accent, accent-active, focus-ring, text-primary, text-secondary), any view:\n")
    worst = None
    for n in STATES:
        for o in NON_ALERT:
            ov = views(rgb(colors[o]))
            for view in ov:
                d = ciede2000(labs[n][view], lab(ov[view]))
                if worst is None or d < worst[0]:
                    worst = (d, n, o, view)
    print(f"{worst[0]:.2f} ({worst[1]} against {worst[2]}, {worst[3]})\n")

    print("Grayscale: luminance ratio between level pairs (1.00 means identical in grayscale):\n")
    print("| Pair | Ratio |")
    print("|---|---|")
    ys = {n: luminance(rgb(palette[n])) for n in STATES}
    flagged = []
    for a, b in itertools.combinations(STATES, 2):
        r = ratio(ys[a], ys[b])
        adjacent = a in LEVELS and b in LEVELS and abs(LEVELS.index(a) - LEVELS.index(b)) == 1
        if adjacent or r < REVIEW_LUM_RATIO:
            print(f"| {a} / {b}{' (adjacent)' if adjacent else ''} | {r:.2f} |")
        if r < REVIEW_LUM_RATIO:
            flagged.append(f"{a}/{b}")
    order = sorted(LEVELS, key=lambda n: -ys[n])
    print(f"\nLevels from lightest to darkest: {', '.join(order)}")
    print(f"Pairs under a luminance ratio of {REVIEW_LUM_RATIO:g}: {', '.join(flagged) or 'none'}\n")


def main():
    self_test()
    colors = read_tokens(TOKENS)
    current = {n: colors[n] for n in STATES}
    print("# Alert ramp check\n")
    print(f"Source: {os.path.relpath(TOKENS, os.getcwd())}. Review thresholds: dE2000 under {REVIEW_DE:g}, "
          f"luminance ratio under {REVIEW_LUM_RATIO:g} (working values for review, not standards).\n")
    report("Current tokens", current, colors)
    for title, change in CANDIDATES.items():
        report(f"Comparison: {title}", {**current, **change}, colors)


if __name__ == "__main__":
    main()
