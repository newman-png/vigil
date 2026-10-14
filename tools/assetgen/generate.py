#!/usr/bin/env python3
"""Generate VIGIL's placeholder pixel art and sounds.

Standard library only and deterministic: the same script always writes
byte-identical files. Run from anywhere:

    python3 tools/assetgen/generate.py            # everything
    python3 tools/assetgen/generate.py eye tone   # only these targets

Targets and the files they write (relative to the repo root):
    eye      app/src/main/res/drawable-nodpi/eye_sheet.png
             app/src/main/res/values/eye_sheet.xml
             tools/assetgen/preview/*.png (x10 previews, not shipped)
    drone, heartbeat, swell, tone, thud
             app/src/main/res/raw/<target>.wav (CC0 1.0, see README.md)

The VT323 font and its licence are committed as downloaded, not generated; the
script only checks that they are in place. See README.md next to this file.
"""

import io
import math
import os
import struct
import sys
import wave
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
MAIN = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(MAIN, "res")
PREVIEW = os.path.join(HERE, "preview")


def write_bytes(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    print("wrote", os.path.relpath(path, ROOT))

# ---------------------------------------------------------------------------
# PNG

CLEAR = (0, 0, 0, 0)
BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)
RED = (255, 43, 43, 255)  # #FF2B2B
PALETTE = {CLEAR, BLACK, WHITE, RED}


def write_png(path, pixels):
    """Write an 8-bit RGBA PNG. `pixels` is a list of rows of RGBA tuples."""
    height, width = len(pixels), len(pixels[0])
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type: none
        for px in row:
            raw.extend(px)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    write_bytes(
        path,
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b""),
    )


def upscale(pixels, factor):
    """Nearest-neighbour upscale."""
    out = []
    for row in pixels:
        wide = [px for px in row for _ in range(factor)]
        out.extend(list(wide) for _ in range(factor))
    return out


def on_black(pixels):
    return [[BLACK if px == CLEAR else px for px in row] for row in pixels]

# ---------------------------------------------------------------------------
# Eye sprite sheet
#
# One horizontal strip of FRAME x FRAME frames, transparent background:
#   opening   OPEN_FRAMES      frame 0 is the closed red slit, the last one the
#                              fully open eye at rest
#   idle      IDLE_FRAMES      the open eye at rest (the first is identical to
#                              the last opening frame); the others only move
#                              the glint. No blink.
#   contract  CONTRACT_FRAMES  the pupil narrowing from rest to a pinpoint,
#                              one step per frame
# The app plays the opening frames backwards to close the eye, and steps
# through the contract frames while a step is held.
#
# Everything is computed from the constants below: the lids are two curves
# that meet in pointed corners (an almond), scaled by how open the eye is.
# Coordinates are pixel centres; the eye is mirror-symmetric about CX.

FRAME = 64
CX = 31.5          # between columns 31 and 32
CY = 32            # the row of the closed slit
HALF_WIDTH = 30    # the eye spans the 60 columns 2 .. 61
UPPER = 16         # how far the lids sit above / below CY at the centre, fully open
LOWER = 11
UPPER_FULLNESS = 1.0   # exponent of the lid curves: < 1 rounder, > 1 more pointed
LOWER_FULLNESS = 1.3

# Iris and pupil are discs around (CX, IRIS_CY); radii are compared squared, so
# the values below pick clean pixel circles. The ring is a thin black circle
# inside the iris with four small gaps (right, down, left, up), like a lens.
IRIS_CY = 31.5
IRIS_R2 = 156.25           # radius 12.5
RING = (8.0, 9.0)          # inner and outer radius of the ring
RING_GAP = 0.2             # half-width of each gap, in radians
PUPIL_DILATED = 32.5       # 12 px wide: while the eye opens
PUPIL_REST = 22.5          # 10 px wide
PUPIL_CONTRACT = [14.5, 8.5, 2.5, 0.5]   # 8, 6, 4 and 2 px wide (the pinpoint)

# White glint: the top-left pixel of a 2 x 2 block on the upper left edge of the
# resting pupil. It is a reflection, so it stays put while the pupil changes.
GLINT = (28, 27)
GLINT_SHIFTED = (29, 27)   # the second idle frame

# Lid opening per frame, 0 = closed, 1 = fully open: even steps, so every frame
# moves the lids by one or two pixels.
OPEN_STEPS = 13
OPENING = [i / OPEN_STEPS for i in range(OPEN_STEPS + 1)]

OPEN_FRAMES = len(OPENING)
IDLE_FRAMES = 2
CONTRACT_FRAMES = len(PUPIL_CONTRACT)


def lid_curve(u, fullness):
    """Lid height at distance u (0 = centre, 1 = corner) as a fraction of the
    height at the centre."""
    return max(0.0, 1 - u * u) ** fullness


def lid_rows(openness):
    """Per column inside the eye: (top, bottom) rows of the lid outline."""
    rows = {}
    for x in range(FRAME):
        u = abs(x - CX) / HALF_WIDTH
        if u >= 1:
            continue
        top = CY - math.floor(openness * UPPER * lid_curve(u, UPPER_FULLNESS) + 0.5)
        bottom = CY + math.floor(openness * LOWER * lid_curve(u, LOWER_FULLNESS) + 0.5)
        rows[x] = (top, bottom)
    return rows


def lid_outline(rows):
    """Outline pixels: the top and bottom of every column, joined vertically
    where the lid is steep. Joins go on the inner column, which keeps the
    corners pointed."""
    pixels = set()
    for x, (top, bottom) in rows.items():
        pixels.add((x, top))
        pixels.add((x, bottom))
        outer = x - 1 if x < CX else x + 1
        if outer not in rows:
            continue
        for y, y_out in zip(rows[x], rows[outer]):
            step = 1 if y_out > y else -1
            for yy in range(y + step, y_out, step):
                pixels.add((x, yy))
    return pixels


def iris_colour(x, y, pupil):
    """What the open eye shows at (x, y) behind the lids."""
    dx, dy = x - CX, y - IRIS_CY
    d2 = dx * dx + dy * dy
    if d2 <= pupil:
        return BLACK
    if d2 > IRIS_R2:
        return BLACK
    inner, outer = RING
    if inner * inner <= d2 < outer * outer:
        # How far round the ring the pixel is from the nearest gap, in radians.
        quarter = math.atan2(dy, dx) % (math.pi / 2)
        if min(quarter, math.pi / 2 - quarter) > RING_GAP:
            return BLACK
    return RED


def eye_frame(openness, pupil, glint):
    grid = [[CLEAR] * FRAME for _ in range(FRAME)]
    rows = lid_rows(openness)
    if openness == 0:
        for x in rows:
            grid[CY][x] = RED
        return grid
    gx, gy = glint
    for x, (top, bottom) in rows.items():
        for y in range(top + 1, bottom):
            in_glint = 0 <= x - gx <= 1 and 0 <= y - gy <= 1
            grid[y][x] = WHITE if in_glint else iris_colour(x, y, pupil)
    for x, y in lid_outline(rows):
        grid[y][x] = WHITE
    return grid


def eye_frames():
    frames = []
    for openness in OPENING:
        # The pupil is wide while the eye opens and settles as it focuses.
        pupil = PUPIL_REST if openness >= 0.85 else PUPIL_DILATED
        frames.append(eye_frame(openness, pupil, GLINT))
    frames.append(eye_frame(1.0, PUPIL_REST, GLINT))
    frames.append(eye_frame(1.0, PUPIL_REST, GLINT_SHIFTED))
    for pupil in PUPIL_CONTRACT:
        frames.append(eye_frame(1.0, pupil, GLINT))
    assert len(frames) == OPEN_FRAMES + IDLE_FRAMES + CONTRACT_FRAMES
    assert frames[OPEN_FRAMES - 1] == frames[OPEN_FRAMES]
    return frames


def eye_width(frames):
    """Width of the open eye inside a frame: the columns the last opening frame uses."""
    used = [x for x in range(FRAME) if any(row[x] != CLEAR for row in frames[OPEN_FRAMES - 1])]
    return used[-1] - used[0] + 1


def strip(frames):
    return [sum((frame[y] for frame in frames), []) for y in range(FRAME)]


def gen_eye():
    frames = eye_frames()
    sheet = strip(frames)
    assert all(px in PALETTE for row in sheet for px in row)
    write_png(os.path.join(RES, "drawable-nodpi", "eye_sheet.png"), sheet)
    write_bytes(
        os.path.join(RES, "values", "eye_sheet.xml"),
        (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Generated by tools/assetgen/generate.py: layout of drawable-nodpi/eye_sheet.png.\n"
            "     Frames are square and side by side: the opening frames, then the idle frames, then the\n"
            "     contract frames. eye_width is how wide the open eye is inside a frame. -->\n"
            "<resources>\n"
            f'    <integer name="eye_frame_size">{FRAME}</integer>\n'
            f'    <integer name="eye_width">{eye_width(frames)}</integer>\n'
            f'    <integer name="eye_open_frames">{OPEN_FRAMES}</integer>\n'
            f'    <integer name="eye_idle_frames">{IDLE_FRAMES}</integer>\n'
            f'    <integer name="eye_contract_frames">{CONTRACT_FRAMES}</integer>\n'
            "</resources>\n"
        ).encode(),
    )
    # Previews are drawn on black, like the takeover screen: the sheet as one row
    # per part, and a few single frames. Old eye previews are removed first.
    if os.path.isdir(PREVIEW):
        for name in sorted(os.listdir(PREVIEW)):
            if name.startswith("eye_") and name.endswith(".png"):
                os.remove(os.path.join(PREVIEW, name))
    parts = [
        frames[:OPEN_FRAMES],
        frames[OPEN_FRAMES:OPEN_FRAMES + IDLE_FRAMES],
        frames[OPEN_FRAMES + IDLE_FRAMES:],
    ]
    blank = [[CLEAR] * FRAME for _ in range(FRAME)]
    rows = [strip(part + [blank] * (OPEN_FRAMES - len(part))) for part in parts]
    write_png(os.path.join(PREVIEW, "eye_sheet_x10.png"), upscale(on_black(sum(rows, [])), 10))
    last = len(frames) - 1
    for i in (0, 3, 7, OPEN_FRAMES - 1, last - 2, last):
        write_png(os.path.join(PREVIEW, f"eye_frame{i:02d}_x10.png"), upscale(on_black(frames[i]), 10))

# ---------------------------------------------------------------------------
# Sounds: 16-bit mono PCM, peak-normalised to -3 dBFS. Dedicated to the public
# domain (CC0 1.0); only CC0 audio may replace them (see README.md).
#
# The takeover is serious, not a game: no chimes, bleeps, arpeggios or clicks.
# Phone speakers play almost nothing below ~100 Hz, so every low sound pairs a
# sub-bass root (felt on headphones and bigger speakers) with harmonics at
# 80-250 Hz that a phone speaker does play; the ear fills in the missing root.

RATE = 22050
PEAK = 10 ** (-3 / 20)
TAU = 2 * math.pi


def write_wav(name, samples, fade_in=0.002, fade_out=0.006):
    """Fade the edges (no clicks), normalise and write res/raw/<name>.wav."""
    n = len(samples)
    samples = list(samples)
    for i in range(min(n, int(fade_in * RATE))):
        samples[i] *= i / (fade_in * RATE)
    for i in range(min(n, int(fade_out * RATE))):
        samples[n - 1 - i] *= i / (fade_out * RATE)
    scale = PEAK / max(abs(s) for s in samples)
    ints = [max(-32767, min(32767, round(s * scale * 32767))) for s in samples]
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack(f"<{n}h", *ints))
    write_bytes(os.path.join(RES, "raw", f"{name}.wav"), buf.getvalue())


def noise(seed):
    """Deterministic white noise in [-1, 1] (xorshift32; `seed` must not be 0)."""
    x = seed
    while True:
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        yield x / 0x7FFFFFFF - 1


def lowpass(cutoff):
    """Coefficient `a` of a one-pole low-pass filter at `cutoff` Hz: y += a * (x - y)."""
    return 1 - math.exp(-TAU * cutoff / RATE)


def loop(seconds, partials):
    """One loop of summed sines, `partials` = [(frequency, amplitude)]. Every
    partial must make a whole number of cycles in `seconds`, so the end runs
    straight into the start. All start at phase 0, so the first sample is 0."""
    for freq, _ in partials:
        assert (freq * seconds).is_integer(), f"{freq} Hz does not loop in {seconds} s"
    return [
        sum(a * math.sin(TAU * f * i / RATE) for f, a in partials)
        for i in range(int(seconds * RATE))
    ]


def thump(n, start, f_hi, f_lo, sweep, decay, rand, attack=1.2):
    """One low sine thump starting at `start` s in a buffer of `n` samples:
    the pitch drops from `f_hi` to `f_lo` Hz (time constant `sweep` s) and the
    level decays with time constant `decay` s. A few ms of low-passed noise
    soften the attack into a thud (it is not a click)."""
    out = [0.0] * n
    phase = 0.0
    dust = 0.0
    a = lowpass(350)
    for i in range(int(start * RATE), n):
        t = i / RATE - start
        phase += TAU * (f_lo + (f_hi - f_lo) * math.exp(-t / sweep)) / RATE
        dust += a * (next(rand) - dust)
        body = math.sin(phase) + attack * dust * math.exp(-t / 0.007)
        out[i] = (1 - math.exp(-t / 0.0015)) * math.exp(-t / decay) * body
    return out


def gen_drone():
    """Slow sub-bass drone on a 45 Hz root, 8 s loop. Its harmonics at
    90-270 Hz carry it on a phone speaker. Detuned pairs beat slowly (once
    every 2, 4 or 8 s) and the harmonics breathe once per loop, quietest at
    the loop point. Every partial and the breath make whole cycles in the 8 s
    and the file starts at 0, so it loops without a seam: no edge fades."""
    seconds = 8
    root = loop(seconds, [(45.0, 0.55), (45.125, 0.4)])  # beats once every 8 s
    # The 180 Hz pair is inverted: the same sound with a 2 dB lower peak, so
    # it comes out louder after normalising.
    harmonics = loop(seconds, [
        (90.0, 0.5), (90.25, 0.3),       # once every 4 s
        (135.0, 0.5), (135.125, 0.3),    # once every 8 s
        (180.0, -0.5), (180.5, -0.3),    # once every 2 s
        (225.0, 0.35), (225.25, 0.2),    # once every 4 s
        (270.0, 0.2),
    ])
    samples = []
    for i, (low, high) in enumerate(zip(root, harmonics)):
        breath = 0.75 - 0.25 * math.cos(TAU * i / (seconds * RATE))
        samples.append(low + breath * high)
    write_wav("drone", samples, fade_in=0, fade_out=0)


def gen_heartbeat():
    """A dry, low "lub-dub", 0.6 s: two short thumps, the "dub" 240 ms after
    the "lub" and softer (110 -> 46 Hz, then 125 -> 52 Hz), saturated so a
    phone speaker hears their overtones. Short decays and no reverb tail."""
    n = int(0.6 * RATE)
    rand = noise(0x13579BDF)
    lub = thump(n, 0.01, 110, 46, 0.035, 0.06, rand)
    dub = thump(n, 0.25, 125, 52, 0.03, 0.05, rand)
    write_wav("heartbeat", [math.tanh(2.8 * (a + 0.7 * b)) for a, b in zip(lub, dub)], fade_out=0.02)


def gen_swell():
    """A reversed swell, 1.9 s: a low, slightly inharmonic struck sound with a
    noise wash, decaying, then reversed. It rises out of nothing, brightens as
    the fast-decaying high partials arrive late, and ends abruptly where the
    hit was. The app cuts it at the eye's first frame, while it still rises."""
    seconds = 1.9
    n = int(seconds * RATE)
    # (frequency, amplitude, decay in s): inharmonic like a struck metal plate,
    # the low partials ringing longest.
    partials = [
        (55.0, 1.0, 0.60), (111.5, 0.7, 0.50), (163.0, 0.5, 0.42),
        (229.0, 0.4, 0.34), (301.0, 0.3, 0.27), (389.0, 0.22, 0.21),
        (517.0, 0.14, 0.15), (701.0, 0.1, 0.11),
    ]
    rand = noise(0x0BADF00D)
    dark_a, bright_a = lowpass(600), lowpass(1800)
    dark = bright_low = 0.0
    hit = []
    for i in range(n):
        t = i / RATE
        tones = sum(a * math.exp(-t / d) * math.sin(TAU * f * t) for f, a, d in partials)
        x = next(rand)
        dark += dark_a * (x - dark)
        bright_low += bright_a * (x - bright_low)
        wash = 1.6 * dark * math.exp(-t / 0.5) + 0.5 * (x - bright_low) * math.exp(-t / 0.18)
        hit.append((1 - math.exp(-t / 0.003)) * (tones + wash))
    top = max(abs(s) for s in hit)
    swell = [math.tanh(1.4 * s / top) for s in reversed(hit)]
    write_wav("swell", swell, fade_in=0.3, fade_out=0.004)


def gen_tone():
    """A thin, high 4 kHz sine with a faint partner 0.5 Hz above it, so it
    wavers slowly. 2 s loop: both make whole cycles and it starts at 0, so it
    loops without a seam (no edge fades). The app plays it quietly."""
    write_wav("tone", loop(2, [(4000.0, 1.0), (4000.5, 0.3)]), fade_in=0, fade_out=0)


def gen_thud():
    """A dry, low thud for a confirmed step, 0.18 s: one short thump falling
    from 140 to 50 Hz, saturated so a phone speaker hears it, and decayed to
    silence before the end: no tail."""
    n = int(0.18 * RATE)
    body = thump(n, 0.0, 140, 50, 0.025, 0.035, noise(0x2468ACE1), attack=1.5)
    write_wav("thud", [math.tanh(3.0 * s) for s in body], fade_out=0.01)

# ---------------------------------------------------------------------------

def check_font():
    """The font is committed as downloaded; just make sure it is in place."""
    font = os.path.join(RES, "font", "vt323_regular.ttf")
    licence = os.path.join(MAIN, "assets", "licenses", "OFL-VT323.txt")
    try:
        with open(font, "rb") as f:
            ok = f.read(4) == b"\x00\x01\x00\x00"
    except OSError:
        ok = False
    if not ok or not os.path.isfile(licence):
        print("warning: VT323 font or its licence is missing or invalid; see README.md", file=sys.stderr)


TARGETS = {
    "eye": gen_eye,
    "drone": gen_drone,
    "heartbeat": gen_heartbeat,
    "swell": gen_swell,
    "tone": gen_tone,
    "thud": gen_thud,
}


def main(args):
    unknown = [a for a in args if a not in TARGETS]
    if unknown:
        sys.exit(f"unknown target(s) {', '.join(unknown)}; choose from {', '.join(TARGETS)}")
    for name in args or TARGETS:
        TARGETS[name]()
    check_font()


if __name__ == "__main__":
    main(sys.argv[1:])
