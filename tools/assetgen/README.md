# assetgen: placeholder art and sounds

`generate.py` draws the eye sprite sheet and synthesises the sounds. It uses
only the Python 3 standard library and is deterministic: the same script always
writes byte-identical files, so a re-run shows no git diff unless the script
changed.

```sh
python3 tools/assetgen/generate.py              # everything
python3 tools/assetgen/generate.py drone tone   # only some targets
```

Targets: `eye`, `drone`, `heartbeat`, `swell`, `tone`, `thud`. The outputs are
committed, so nobody has to run the script to build the app.

| File (under `app/src/main/`) | Made by |
| --- | --- |
| `res/drawable-nodpi/eye_sheet.png` | `eye` |
| `res/values/eye_sheet.xml` | `eye` |
| `res/raw/drone.wav`, `heartbeat.wav`, `swell.wav`, `tone.wav`, `thud.wav` | one target each (CC0, see Sounds) |
| `res/font/vt323_regular.ttf` | downloaded once, committed (see Font) |
| `assets/licenses/OFL-VT323.txt` | downloaded once, committed (see Font) |

`tools/assetgen/preview/` holds x10 nearest-neighbour previews of the sheet (one
row per part) and of frames 0, 3, 7, 13, 17 and 19. They are drawn on black,
like the takeover screen, and are not shipped in the app.

## Eye sprite sheet

One horizontal strip of square frames, 64 x 64 px each, on a transparent
background. The current sheet is 1280 x 64 (20 frames):

| Frames | What | How the app plays it |
| --- | --- | --- |
| 0 - 13 | opening: frame 0 is a 1 px red slit, 60 px wide; the lids part in even steps until frame 13, fully open. The pupil is wide while the eye opens and settles at frame 12 | 0 -> 13 to open (the first frames flash in negative), 13 -> 0 slowly to close |
| 14 - 15 | idle: 14 is the open eye at rest (identical to 13), 15 moves only the glint 1 px. No blink | holds 14; flicks to 15 for a moment every few seconds |
| 16 - 19 | contract: the pupil narrows from rest (10 px) to 8, 6, 4 and a 2 px pinpoint | stepped through while a step is held to confirm it |

`res/values/eye_sheet.xml` tells the app the layout, so the app never
hard-codes it:

```xml
<integer name="eye_frame_size">64</integer>      <!-- frame width = height, px -->
<integer name="eye_width">60</integer>           <!-- the open eye's width inside a frame, px -->
<integer name="eye_open_frames">14</integer>     <!-- frames 0 .. 13 -->
<integer name="eye_idle_frames">2</integer>      <!-- the frames right after -->
<integer name="eye_contract_frames">4</integer>  <!-- the last frames -->
```

The app scales the eye by the largest whole number that keeps `eye_width`
within about 90% of the screen width.

Palette: only `#000000`, `#FFFFFF`, red `#FF2B2B` and fully transparent, with
no anti-aliasing (the script asserts this). Scale the frames up by an integer
factor with nearest-neighbour filtering (in Compose, `FilterQuality.None`), or
the pixels blur.

The design is original: an almond of white 1 px lids (two curves meeting in
pointed corners), black inside, a red iris disc with one thin black ring broken
by four small gaps, a round black pupil and a 2 x 2 white glint. Keep any
replacement free of marks arranged around the pupil (no tomoe or comma shapes,
no pinwheels) so it does not resemble a trademarked eye.

Everything is computed from the constants at the top of the eye section in
`generate.py` (`FRAME`, `HALF_WIDTH`, `UPPER` / `LOWER` and the lid
`*_FULLNESS`, `IRIS_R2`, `RING`, the pupil sizes, `GLINT`, `OPEN_STEPS`). Edit
them and run `generate.py eye`.

## Sounds

The takeover is serious, not a game: there are no chimes, bleeps, arpeggios or
UI clicks, and nothing plays when a directive is completed or abandoned (the
sound cuts to silence). All files are 16-bit mono PCM WAV at 22050 Hz,
peak-normalised to -3 dBFS; the one-shots have short fades at the edges so
they start and stop without clicks.

Phone speakers play almost nothing below about 100 Hz, so each low sound pairs
a sub-bass root (felt on headphones and bigger speakers) with harmonics at
roughly 80-270 Hz that a phone speaker does play.

| File | Length | Sound | When the takeover plays it |
| --- | --- | --- | --- |
| `heartbeat.wav` | 0.6 s | dry, low "lub-dub": two short thumps falling in pitch (110 -> 46 Hz, then 125 -> 52 Hz), the "dub" 240 ms after the "lub", saturated so a phone speaker hears its overtones, no tail | the heartbeats before the eye opens (2 on tier 3, 1 on tier 2, about 900 ms apart), with a matching vibration |
| `swell.wav` | 1.9 s | reversed swell: a low, inharmonic struck sound with a noise wash, decaying, played backwards; it rises out of nothing and ends abruptly | starts with the tone; cut at the eye's first frame, 1.8 s (tier 3) or 1.4 s (tier 2) later |
| `tone.wav` | 2.0 s loop | thin, high 4 kHz sine with a faint partner 0.5 Hz above it, so it wavers | loops quietly under the swell; cut at the eye's first frame |
| `drone.wav` | 8.0 s loop | slow sub-bass drone on a 45 Hz root with harmonics at 90-270 Hz; detuned pairs beat once every 2, 4 or 8 s and the harmonics breathe once per loop | fades in over about 6 s after the open eye has held in silence, and loops until the end (tier 3 full, tier 2 about half, tier 1 none) |
| `thud.wav` | 0.18 s | dry, low thud: one short thump falling from 140 to 50 Hz, decayed to silence before the end | each confirmed step, with a short low vibration |

The two loops make a whole number of cycles in their length and start at
zero, so they loop seamlessly with no edge fades. Each sound is one small
function in `generate.py` (`gen_drone` and so on) if you want to tweak it and
re-run its target. The playback volumes are constants at the bottom of
`app/src/main/java/dev/vigil/takeover/TakeoverFx.kt` (the tone plays at only
6 %, the drone at 45 %).

### Licence: CC0 only

The generated sounds are dedicated to the public domain under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). **Only CC0 audio
may be committed to this repository.** A replacement sound must be CC0 too:
your own recording or synthesis that you dedicate to CC0, or a file published
under CC0 (check the licence of each file; "royalty free", "free for personal
use", CC BY and CC BY-NC are not enough). Note where a downloaded replacement
came from in the commit message.

## Font

VT323 (Copyright 2011 The VT323 Project Authors), licensed under the SIL Open
Font License 1.1, taken from the Google Fonts repository:

- https://raw.githubusercontent.com/google/fonts/main/ofl/vt323/VT323-Regular.ttf
  -> `app/src/main/res/font/vt323_regular.ttf`
- https://raw.githubusercontent.com/google/fonts/main/ofl/vt323/OFL.txt
  -> `app/src/main/assets/licenses/OFL-VT323.txt` (ships with the app, as the
  licence requires)

The script does not download anything; it only warns if the font is missing or
does not start with the TrueType magic bytes `00 01 00 00`.

## Swapping assets

Every asset is referenced by its resource name only, so a replacement is a
file swap. After hand-replacing a file, do not run the generator target that
writes it (that would overwrite your file); run only the other targets.

- **Eye:** draw a new horizontal strip of square frames: the opening frames
  first (closed to open), then the idle frames, then the contract frames. Save
  it as `res/drawable-nodpi/eye_sheet.png` with a transparent background and set
  the integers in `res/values/eye_sheet.xml` to its frame size, eye width and
  frame counts. Any frame size works as long as the XML matches it.
- **Sounds:** replace `res/raw/<name>.wav` (`drone`, `heartbeat`, `swell`,
  `tone`, `thud`) with a CC0 file of the same name (see Licence above). Any
  format Android plays works (WAV, OGG, MP3), but keep exactly one file per name:
  `drone.ogg` next to `drone.wav` is a duplicate-resource build error. The app
  plays them with SoundPool, which decodes each file fully into memory: keep
  every sound well under 1 MB decoded (16-bit mono at 22050 Hz is 43 KB per
  second, stereo or 44.1 kHz doubles it). Keep the drone and the tone seamlessly
  loopable, the swell about 1.6-2 s and rising until it stops abruptly, the
  heartbeat under 0.9 s with its "dub" about 240 ms after the "lub" (the
  vibration follows that timing), and the thud short with no tail.
- **Font:** replace `res/font/vt323_regular.ttf` (keep the file name, or rename
  the references to `R.font.vt323_regular`) and put the new font's licence in
  `assets/licenses/` in place of `OFL-VT323.txt`.
