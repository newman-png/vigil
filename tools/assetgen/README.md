# assetgen: placeholder art and sounds

`generate.py` draws the eye sprite sheet and synthesises the sounds. It uses
only the Python 3 standard library and is deterministic: the same script always
writes byte-identical files, so a re-run shows no git diff unless the script
changed.

```sh
python3 tools/assetgen/generate.py              # everything
python3 tools/assetgen/generate.py drone blip   # only some targets
```

Targets: `eye`, `drone`, `thump`, `sting`, `blip`, `dismiss`. The outputs are
committed, so nobody has to run the script to build the app.

| File (under `app/src/main/`) | Made by |
| --- | --- |
| `res/drawable-nodpi/eye_sheet.png` | `eye` |
| `res/values/eye_sheet.xml` | `eye` |
| `res/raw/drone.wav`, `thump.wav`, `sting.wav`, `blip.wav`, `dismiss.wav` | one target each |
| `res/font/vt323_regular.ttf` | downloaded once, committed (see Font) |
| `assets/licenses/OFL-VT323.txt` | downloaded once, committed (see Font) |

`tools/assetgen/preview/` holds x10 nearest-neighbour previews of the sheet and
of frames 0, 3, 7, 11 and 14. They are drawn on black, like the takeover
screen, and are not shipped in the app.

## Eye sprite sheet

One horizontal strip of square frames, 32 x 32 px each, on a transparent
background. The current sheet is 512 x 32 (16 frames):

| Frames | What | How the app plays it |
| --- | --- | --- |
| 0 - 11 | opening: frame 0 is a 1 px red slit, 20 px wide; the lids part until frame 11, fully open | 0 -> 11 to open, 11 -> 0 to close |
| 12 - 15 | idle loop on the open eye: the glint shifts 1 px, the pupil widens and narrows | loop 12 -> 15 |

Frame 12 is identical to frame 11, so opening flows straight into the idle
loop, and each idle step changes one thing so the loop has no jump. The opening
frames are spaced evenly in pixels; ease the timing in the app if wanted.

`res/values/eye_sheet.xml` tells the app the layout, so the app never
hard-codes it:

```xml
<integer name="eye_frame_size">32</integer>   <!-- frame width = height, px -->
<integer name="eye_open_frames">12</integer>  <!-- frames 0 .. 11 -->
<integer name="eye_idle_frames">4</integer>   <!-- the frames right after -->
```

Palette: only `#000000`, `#FFFFFF`, red `#FF2B2B` and fully transparent, with
no anti-aliasing (the script asserts this). Scale the frames up by an integer
factor with nearest-neighbour filtering (in Compose, `FilterQuality.None`), or
the pixels blur.

The design is original: an almond outline of white 1 px lids, black inside,
a plain red iris disc with a black vertical-slit pupil and one white glint
pixel. Keep any replacement free of marks arranged around the pupil (no
tomoe or comma shapes, no pinwheels) so it does not resemble a trademarked eye.

To tweak the drawn eye, edit the constants at the top of the eye section in
`generate.py` (`UPPER_LID` / `LOWER_LID` lid shapes, `IRIS_R`, the pupils,
the glint positions, `OPENING`) and run `generate.py eye`.

## Sounds

All are 16-bit mono PCM WAV at 22050 Hz, peak-normalised to -3 dBFS, with
short fades at the edges so they start and stop without clicks.

| File | Length | Sound | Use |
| --- | --- | --- | --- |
| `drone.wav` | 4.0 s | soft low drone on 55 Hz with slowly beating, detuned partials | loops under the takeover; every partial makes a whole number of cycles in 4 s, so it loops seamlessly (no edge fades, it starts at zero) |
| `thump.wav` | 0.7 s | heartbeat "lub-dub": two low sine thumps with falling pitch | can be repeated as a pulse |
| `sting.wav` | 0.8 s | chiptune pulse-wave arpeggio C5 E5 G5, landing on a ringing C6 | directive completed |
| `blip.wav` | 40 ms | very soft, dull 1.1 kHz blip | typewriter text; play it quietly |
| `dismiss.wav` | 0.6 s | low, dull square tone gliding down an octave and fading | directive abandoned |

Stop the drone with a short fade in the app rather than cutting it mid-cycle.
Each sound is one small function in `generate.py` (`gen_drone` and so on) if
you want to tweak it and re-run its target.

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
  first (closed to open), then the idle loop. Save it as
  `res/drawable-nodpi/eye_sheet.png` with a transparent background and set the
  three integers in `res/values/eye_sheet.xml` to its frame size and frame
  counts. Any frame size works as long as the XML matches it.
- **Sounds:** replace `res/raw/<name>.wav` with a file of the same name. Any
  format Android plays works (WAV, OGG, MP3), but keep exactly one file per name:
  `drone.ogg` next to `drone.wav` is a duplicate-resource build error. Keep the
  drone seamlessly loopable and the other sounds short.
- **Font:** replace `res/font/vt323_regular.ttf` (keep the file name, or rename
  the references to `R.font.vt323_regular`) and put the new font's licence in
  `assets/licenses/` in place of `OFL-VT323.txt`.
