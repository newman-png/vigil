#!/usr/bin/env python3
"""Generate VIGIL's placeholder pixel art and sounds.

Standard library only and deterministic: the same script always writes
byte-identical files. Run from anywhere:

    python3 tools/assetgen/generate.py            # everything
    python3 tools/assetgen/generate.py eye blip   # only these targets

Targets and the files they write (relative to the repo root):
    eye      app/src/main/res/drawable-nodpi/eye_sheet.png
             app/src/main/res/values/eye_sheet.xml
             tools/assetgen/preview/*.png (x10 previews, not shipped)
    drone, thump, sting, blip, dismiss
             app/src/main/res/raw/<target>.wav

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
# Sounds: 16-bit mono PCM, peak-normalised to -3 dBFS.

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


def square(phase, harmonics, rolloff=1.0):
    """Band-limited square wave: the first `harmonics` odd harmonics, each at
    1/k**rolloff. rolloff 1 is a true square; higher is duller."""
    return sum(math.sin(k * phase) / k ** rolloff for k in range(1, 2 * harmonics, 2))


def pulse(phase, freq, duty):
    """Band-limited pulse wave (no DC), harmonics up to 9 kHz."""
    top = int(9000 / freq)
    return sum(
        2 / (k * math.pi) * math.sin(k * math.pi * duty) * math.cos(k * phase)
        for k in range(1, top + 1)
    )


def gen_drone():
    """Low drone around 55 Hz. Every partial (and the swell) makes a whole
    number of cycles in the 4 s, so the file loops without a seam. All
    partials start at phase 0, so the first sample is silent: no edge fades."""
    seconds = 4
    partials = [
        (55.0, 1.0), (55.25, 0.8),    # root, slowly beating against itself
        (77.75, 0.12),                # a quiet tritone for unease
        (110.0, 0.45), (110.5, 0.3),  # octave, beating twice as fast
        (165.0, 0.22), (220.25, 0.14), (330.0, 0.05),
    ]
    for freq, _ in partials:
        assert (freq * seconds).is_integer()
    samples = []
    for i in range(seconds * RATE):
        t = i / RATE
        swell = 0.8 + 0.2 * math.sin(TAU * t / seconds)
        samples.append(swell * sum(a * math.sin(TAU * f * t) for f, a in partials))
    write_wav("drone", samples, fade_in=0, fade_out=0)


def gen_thump():
    """Heartbeat "lub-dub": two low sine thumps with a falling pitch and a
    little filtered noise for the attack."""
    n = int(0.7 * RATE)
    out = [0.0] * n
    rng = 0x12345678

    def beat(start, f_hi, f_lo, amp, decay):
        nonlocal rng
        phase = 0.0
        noise = 0.0
        for i in range(int(start * RATE), n):
            t = i / RATE - start
            freq = f_lo + (f_hi - f_lo) * math.exp(-t / 0.03)
            phase += TAU * freq / RATE
            rng ^= (rng << 13) & 0xFFFFFFFF
            rng ^= rng >> 17
            rng ^= (rng << 5) & 0xFFFFFFFF
            noise += 0.08 * (rng / 0x7FFFFFFF - 1 - noise)  # one-pole low-pass
            body = math.sin(phase) * math.exp(-t / decay)
            click = 2.0 * noise * math.exp(-t / 0.01)
            out[i] += amp * (1 - math.exp(-t / 0.002)) * (body + click)

    beat(0.02, 120, 50, 1.0, 0.05)
    beat(0.27, 140, 58, 0.75, 0.04)
    write_wav("thump", [math.tanh(1.6 * s) for s in out], fade_out=0.02)


def gen_sting():
    """Completion sting: a quick 25% pulse arpeggio up C5 E5 G5, landing on a
    held C6 that rings out."""
    step = 0.07
    notes = [523.25, 659.26, 783.99, 1046.50]
    samples = []
    phase = 0.0
    for i in range(int(0.8 * RATE)):
        t = i / RATE
        index = min(int(t / step), len(notes) - 1)
        local = t - index * step
        freq = notes[index]
        phase += TAU * freq / RATE
        if index == len(notes) - 1:
            env = math.exp(-local / 0.18)
        else:
            # Short notes, each faded out just before the next one starts.
            env = (1 - 0.3 * local / step) * min(1.0, (step - local) / 0.003)
        env *= min(1.0, local / 0.002)
        samples.append(env * pulse(phase, freq, 0.25))
    write_wav("sting", samples, fade_out=0.01)


def gen_blip():
    """Very soft typing blip: 40 ms of a dull 1.1 kHz tone with a fast decay."""
    samples = []
    for i in range(int(0.04 * RATE)):
        t = i / RATE
        samples.append(math.exp(-t / 0.008) * square(TAU * 1100 * t, 3, rolloff=2))
    write_wav("blip", samples, fade_in=0.0015, fade_out=0.005)


def gen_dismiss():
    """Directive abandoned: a low, dull square tone gliding down an octave
    (220 -> 110 Hz) and fading out. Few harmonics so it is not harsh."""
    n = int(0.6 * RATE)
    samples = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        freq = 110 * 2 ** (1 - min(1.0, t / 0.45))
        phase += TAU * freq / RATE
        env = min(1.0, t / 0.005) * (1 - t / 0.6) ** 1.5
        samples.append(env * square(phase, 6, rolloff=1.3))
    write_wav("dismiss", samples, fade_out=0.01)

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
    "thump": gen_thump,
    "sting": gen_sting,
    "blip": gen_blip,
    "dismiss": gen_dismiss,
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
