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
#   0 .. OPEN_FRAMES-1    opening: closed red slit -> fully open eye
#   then IDLE_FRAMES      idle loop on the open eye
# The app plays the opening frames backwards to close the eye.

FRAME = 32
OPEN_FRAMES = 12
IDLE_FRAMES = 4

CX = 15.5  # horizontal centre, between columns 15 and 16
CY = 16    # centre row; the closed slit sits on it

# Fully open lid shape, hand-tuned for clean pixel steps. Entry i is how far the
# lid sits from CY in the two columns i steps out from the centre (15 and 16,
# then 14 and 17, ...); the last entry is the corner. 10 entries: 20 px wide.
UPPER_LID = [7, 7, 7, 6, 6, 5, 4, 3, 2, 0]
LOWER_LID = [5, 5, 5, 4, 4, 3, 3, 2, 1, 0]
IRIS_R = 6.6
GLINT_A = (12, 13)
GLINT_B = (13, 13)

# Lid opening per frame, 0 = closed, 1 = fully open. Hand-picked for the lid
# shapes above so every frame moves a similar number of pixels (re-pick them if
# you change the lids); the app can ease the frame timing. Frame 1 already
# shows a red gap between the lids.
OPENING = [0.0, 0.11, 0.19, 0.27, 0.365, 0.45, 0.54, 0.67, 0.77, 0.85, 0.91, 1.0]

# Pupils: for each row offset from CY, the pupil covers the columns with
# |x - CX| < value, so 1 -> 2 px wide and 2 -> 4 px wide. Mirrored above and below.
PUPIL_WIDE = [2, 2, 2, 1, 1, 1]
PUPIL_SLIT = [1, 1, 1, 1, 1, 1]


def lid_rows(openness):
    """Per column inside the eye: (top, bottom) rows of the lid outline."""
    rows = {}
    for i, (up, down) in enumerate(zip(UPPER_LID, LOWER_LID)):
        top = CY - math.floor(openness * up + 0.5)
        bottom = CY + math.floor(openness * down + 0.5)
        rows[15 - i] = rows[16 + i] = (top, bottom)
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


def in_pupil(x, y, pupil):
    dy = abs(y - CY)
    return dy < len(pupil) and abs(x - CX) < pupil[dy]


def eye_frame(openness, pupil, glint):
    grid = [[CLEAR] * FRAME for _ in range(FRAME)]
    rows = lid_rows(openness)
    if openness == 0:
        for x in rows:
            grid[CY][x] = RED
        return grid
    for x, (top, bottom) in rows.items():
        for y in range(top + 1, bottom):
            if (x, y) == glint:
                colour = WHITE
            elif in_pupil(x, y, pupil):
                colour = BLACK
            elif (x - CX) ** 2 + (y - CY) ** 2 <= IRIS_R ** 2:
                colour = RED
            else:
                colour = BLACK
            grid[y][x] = colour
    for x, y in lid_outline(rows):
        grid[y][x] = WHITE
    return grid


def eye_frames():
    frames = []
    for i, openness in enumerate(OPENING):
        # The pupil stays wide while the eye opens and narrows as it focuses.
        pupil = PUPIL_SLIT if i == OPEN_FRAMES - 1 else PUPIL_WIDE
        frames.append(eye_frame(openness, pupil, GLINT_A))
    # Idle loop: each step changes one thing (glint or pupil), so it cycles smoothly.
    frames.append(eye_frame(1.0, PUPIL_SLIT, GLINT_A))
    frames.append(eye_frame(1.0, PUPIL_SLIT, GLINT_B))
    frames.append(eye_frame(1.0, PUPIL_WIDE, GLINT_B))
    frames.append(eye_frame(1.0, PUPIL_WIDE, GLINT_A))
    assert len(frames) == OPEN_FRAMES + IDLE_FRAMES
    return frames


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
            "     Frames are square and side by side: opening frames first, then the idle loop. -->\n"
            "<resources>\n"
            f'    <integer name="eye_frame_size">{FRAME}</integer>\n'
            f'    <integer name="eye_open_frames">{OPEN_FRAMES}</integer>\n'
            f'    <integer name="eye_idle_frames">{IDLE_FRAMES}</integer>\n'
            "</resources>\n"
        ).encode(),
    )
    # Previews are drawn on black, like the takeover screen.
    write_png(os.path.join(PREVIEW, "eye_sheet_x10.png"), upscale(on_black(sheet), 10))
    for i in (0, 3, 7, 11, 14):
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
