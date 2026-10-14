# VIGIL

A pixel-eye commitment device for Android. You plan your week calmly with an AI, import the plan as a JSON file, and VIGIL takes over your phone until you do the thing.

**The app never thinks.** It reads a static plan, fires triggers, shows the takeover, and records what happened. All the intelligence lives in the weekly plan, which is made outside the app. The only choices VIGIL makes are between options the plan wrote in advance.

Personal, sideloaded and open source. It is not on the Play Store, and it uses whatever Android permissions make it reliable.

---

## The weekly loop

1. **Plan.** Give an AI chat [`spec/HANDOFF.md`](spec/HANDOFF.md), your `vision.md` and last week's bundle, `vigil-week-YYYY-MM-DD.json` (none in week 1). It answers with a new `plan-YYYY-MM-DD.json` (format: [`spec/schema.json`](spec/schema.json)).
2. **Import.** In VIGIL open **PLAN** › **IMPORT PLAN** and pick the file. If something is wrong you get a plain-language list of problems, and your previous plan stays active.
3. **Live it.** VIGIL fires each directive when its trigger happens.
4. **Hand back.** When the plan expires, VIGIL shows **PLAN COMPLETE** (and posts a notification) with one button: **EXPORT**. It saves the weekly bundle `vigil-week-<week_of>.json`: the plan that ran, the log, the difficulty ratings, the morning check-ins and a summary. Give it to the AI for the next plan. Until you import one, the plan's `fallback_plan` (or a built-in minimal plan) keeps the essentials running. Keep both files in [`plans/`](plans/).

**PLAN** › **EXPORT BUNDLE** writes the same file mid-week. Bundles are the only export; there is no separate log file any more.

## Home

The home screen is the **VIGIL** title, a status line (week and expiry, **PLAN COMPLETE · FALLBACK RUNNING** or **NO PLAN · MINIMAL PLAN RUNNING**), a flags line when something is on (quiet, not okay today, minimum day, suspended), a red **A DIRECTIVE IS WAITING · OPEN** line when a takeover is waiting, and exactly four items:

| item | what it holds |
|---|---|
| **SETUP** | Permissions (with the count missing), battery, adhan, app usage, the takeover (sound, vibration, Glyph, tier-3 override in quiet mode) and safety (**I'M NOT OKAY TODAY** / **I'M OKAY AGAIN**, how CALL, the kill switch and the adb escape work). |
| **PLAN** | The running plan's status and warnings, **IMPORT PLAN**, **EXPORT BUNDLE** and the running directives (**EASIER** marks one that stepped down). |
| **QUIET** | Tap to switch quiet mode on or off; it shows how many directives it is holding. |
| **TEST** | Previews and test fires (see below). |

Back always returns home.

## The takeover

Serious, slow and quiet, closer to a genjutsu than a game. One element at a time, with hard cuts and long holds:

1. **Black and silence.** Then a red slit appears with each heartbeat (tier 1: one soft vibration instead), and (tiers 2–3) a reversed swell and a thin high tone rise.
2. **The eye opens** into total silence and holds. Then a low drone fades in (tiers 2–3).
3. **The directive line:** the title, with its `why` under it.
4. **One step at a time.** A step stays dim while it is locked for its `min_seconds`; then it turns white. **Press and hold anywhere above the bottom strip for 1.5 s** to confirm it: the pupil contracts while you hold, and letting go early cancels. Each confirmed step lands with a dry thud.
5. **After the last step,** everything goes silent and the eye closes slowly. If the plan asks for it, three words follow: **EASY · OK · HARD**, one tap (or a faint **SKIP**; it skips by itself after 30 s).

No checkboxes, progress bars or counters. The **tier** sets the weight: tier 1 is a short nudge with a quick open; tier 2 adds a heartbeat, the swell and a quieter drone; tier 3 the longest intro and the full drone. Red pips around the eye show the tier (at tier 3 they fuse into a turning triangle).

A faint strip runs along the bottom: **CALL** from the first black frame, then, once the directive line appears, **NOT OKAY** (hold 3 s) and **LEAVE** (hold 10 s, logged as `directive_dismissed`, never dropped silently). While a hold runs it shows only a number counting down.

**Glyph lights.** On Nothing phones the back lights pulse with the heartbeat and breathe while a step waits.

## The feedback loop

- **Ratings.** After a completed takeover VIGIL may ask **EASY / OK / HARD**, as the directive's `rating` says (`always`, `sample` = every third completion, or `never`). The answers go into the bundle and are the planner's main signal: mostly EASY or OK means it may step up a notch next week; often HARD means it shrinks or gets an easier version.
- **Easier versions and automatic step-down.** The planner can write a lighter version of a directive in advance (`variants.easier`) with a rule (`adapt.step_down_after_hard`: N). After N HARD ratings in a row, VIGIL switches that directive to the easier version for the rest of the plan (logged `stepped_down`). It never steps back up by itself; only the next plan does.
- **"I'm not okay today."** A 3-second hold in **SETUP** › **SAFETY**, or **NOT OKAY** on any takeover. Until the day boundary (default 04:00) only the plan's `bad_day.keep` directives fire. **I'M OKAY AGAIN** ends it early.
- **The morning check-in.** If the plan has `after_bad_day`, the first time you unlock the phone (or open VIGIL) the morning after, a short takeover asks *YESTERDAY WAS HARD. HOW IS TODAY?* **BETTER · SAME · WORSE**. The plan maps each answer to the day: normal, another bad day, or a minimum day. Until you answer, the "same" answer applies.
- **Minimum day.** The lightest day there is: only `minimum.keep` fires, usually just the prayers.

None of these counts as a failure, in the app or in the bundle, and the wording stays firm but never punishing.

## Modes and triggers

- **Quiet mode** (QUIET on the home screen, or the notification). Each directive's `quiet` field decides what happens while it is on:
  - `queue`: held until quiet mode ends;
  - `silent`: shown without sound or vibration;
  - `ignore`: skipped;
  - `override`: tier 3 only, rings anyway. You can switch overrides off in **SETUP** › **TAKEOVER**.
- **Expiry.** When the plan's `expires` passes, VIGIL runs the plan's `fallback_plan`, or a built-in minimal plan (five prayers, water, sleep), and shows PLAN COMPLETE. It never goes silent.

| trigger | fires when |
|---|---|
| `time` | a wall-clock time, on chosen days |
| `adhan` | your adhan app posts its prayer notification (e.g. *"It's time for Isha"*). VIGIL never calculates prayer times. |
| `app_usage` | listed apps are opened N times within M minutes, or used M minutes without a break |

Unknown trigger types (`geofence`, `rule`, `if_then` and `lockdown` are reserved) load with a warning and never fire.

## TEST

- **TIER PREVIEW 1 / 2 / 3:** a built-in demo takeover at each weight.
- **CHECK-IN PREVIEW:** the morning check-in; answering it changes nothing.
- **FIRE IN 60 S (LOCK THE PHONE):** fires the tier-2 demo through a real alarm a minute later, so you can lock the phone and check that the takeover arrives with the screen off.
- **FIRE** next to every running directive.
- **LAST EVENTS:** the last 12 log lines.
- **KILL SWITCH** (after 7 taps on the home title, or while suspended): **SUSPEND 1 H / 24 H** and **RESUME**.

Test fires are logged with the trigger `debug`, and the bundle's summary, ratings and check-ins leave them out.

## Sounds

Five WAV files in `app/src/main/res/raw/`. To swap one, replace the file with another of the same name.

| file | what it is | when it plays |
|---|---|---|
| `heartbeat.wav` | a dry, low lub-dub | with the red slit before the eye opens (tiers 2–3; tier 1 only vibrates) |
| `swell.wav` | a reversed swell that ends abruptly | rising before the eye opens (tiers 2–3) |
| `tone.wav` | a thin high tone, looped | under the swell; cut with it the moment the eye opens |
| `drone.wav` | a slow sub-bass drone, looped | fades in once the eye is open (tier 3 full, tier 2 quieter, tier 1 none) |
| `thud.wav` | a dry low thud with no tail | each confirmed step |

Completing or leaving a takeover is silence. There are no chimes, bleeps or UI clicks. The files are deterministic placeholders made by [`tools/assetgen/generate.py`](tools/assetgen/generate.py) (see [`tools/assetgen/README.md`](tools/assetgen/README.md)). Tier 3 plays on the alarm stream, lower tiers on the notification stream; silent takeovers and the SETUP › TAKEOVER switches turn the matching output off.

## Audio licence

Only CC0 audio may be committed to this repository. The generated placeholders are dedicated to CC0, and any replacement must be CC0 too.

## Safety

- **Calls are never blocked.**
  - Takeovers wait while a call or VoIP call is active or the phone is ringing (VoIP calls are recognised by their call notification).
  - They never push themselves over the phone, dialer or emergency apps.
  - Every takeover has **CALL**, from its first black frame. It silences the takeover, opens the dialer (or the emergency dialer when the phone is locked) and keeps VIGIL out of the way for 10 minutes, even if Android restarts it.
- **Always a way out.** **NOT OKAY** (hold 3 s) makes today a bad day: a takeover that isn't on the essentials list closes, one that is stays and says so. **LEAVE** (hold 10 s) closes it and logs it.
- **Hard maximum.** A takeover closes itself after 90 minutes (`directive_timed_out`).
- **It never nags its way back.**
  - Only a newly fired directive may turn the screen on.
  - An unfinished takeover you leave comes back after ~2.5 s only on an unlocked, awake phone, and only when VIGIL can see (usage access) that the phone app isn't in front.
  - It never comes back over the lock screen, so the lock screen's own emergency button always works; after unlocking it returns.
  - While it is not on screen it makes no sound, vibration or light.
- **Kill switch.** Tap the eye on any takeover 7 times to reveal **SUSPEND 1 HOUR**. Or tap the **VIGIL** title on the home screen 7 times: TEST opens with **SUSPEND 1 H / 24 H** and **RESUME**.
- **adb escape** (only adb/the system can send it; it is protected by `android.permission.DUMP`):
  ```sh
  adb shell am broadcast -a dev.vigil.action.SUSPEND -n dev.vigil/.engine.KillSwitchReceiver --ei minutes 60
  adb shell am broadcast -a dev.vigil.action.RESUME  -n dev.vigil/.engine.KillSwitchReceiver
  adb uninstall dev.vigil                # the last resort
  ```
  Use the SUSPEND broadcast rather than `am force-stop`: with notification access on, Android restarts VIGIL right after a force-stop.

## Install

1. On your phone, open the repository's **Releases** page and download **`vigil.apk`** from the **"VIGIL latest build"** pre-release. GitHub Actions rebuilds it from the `main` branch on every push there. (Every run, on any branch, also attaches the APK as the `vigil-apk` artifact.)
2. Open it and allow installs from your browser or file manager when Android asks. Updates install over the old version, because every build is signed with the same key.
3. VIGIL opens on **SETUP**. Grant each item under **PERMISSIONS**:
   - notifications;
   - full-screen takeovers;
   - display over other apps;
   - exact alarms;
   - notification access (for adhan);
   - usage access (for app usage);
   - optionally accessibility (instant app detection).

   Then set **BATTERY** to unrestricted (Nothing OS: *Settings › Apps › VIGIL › Battery › Unrestricted*).
4. **Sideloading blocks two of those pages at first.** Android greys out notification access and accessibility for apps installed from a file. Fix it in *App info › ⋮ (top right) › Allow restricted settings*, then try again.
5. In **SETUP** › **ADHAN**, after your adhan app has posted one notification, tap it in the list. Check the result with **TEST TRIGGER**. The default phrase `time for` already matches "It's time for Isha".
6. In **TEST**, try **FIRE IN 60 S** and lock the phone.

### Reliability notes

- **The alarm clock icon is VIGIL.** Directive times are set as alarm clock alarms, the one kind Android always delivers on time, even in Doze with the screen off. So Android shows its alarm clock icon in the status bar and lists the next directive as your next alarm.
- **Notification access is rebound.** Android sometimes disconnects a notification listener, which would silently stop the adhan trigger. VIGIL asks Android to reconnect it at once and checks again about every minute. SETUP › ADHAN shows whether it is connected; if it stays disconnected, switch VIGIL's notification access off and on.
- **Known limit:** after a reboot, VIGIL starts once the phone has been unlocked for the first time (Android keeps app data locked until then), so a directive due more than 30 minutes before the first unlock is logged as `missed`.

### Glyph lights (Nothing phones)

The Nothing Glyph SDK is downloaded at build time by [`tools/fetch-glyph-sdk.sh`](tools/fetch-glyph-sdk.sh) rather than committed, because it has no open-source licence. On Android 16 it needs no setup. On Android 15, enable Glyph debug mode from a computer (it lasts 48 hours):
```sh
adb shell settings put global nt_glyph_interface_debug_enable 1
```

## Building

- **Everything:** push to GitHub. [`.github/workflows/build.yml`](.github/workflows/build.yml) runs the core tests, builds the APK and runs lint; on `main` it also publishes the pre-release.
- **Core logic only** (any machine with a JDK; no Android SDK needed): `./gradlew -p core test`.
- **APK locally** (needs the Android SDK): `./tools/fetch-glyph-sdk.sh && ./gradlew :app:assembleDebug`. Without the Glyph SDK the app still builds; the Glyph lights are simply switched off.

## Repository layout

```
app/      Android app (Kotlin, Jetpack Compose). Shows things and wires Android events to the core; no decisions.
core/     Plain Kotlin, no Android: plan model, parser + validator, trigger maths, the engine (quiet, day modes,
          check-in, ratings and step-down, the takeover queue, as pure functions), log and bundle format.
          All unit tests live here.
spec/     schema.json (plan format), HANDOFF.md (planner prompt), sample-plan.json, sample-bundle.json
plans/    your weekly history: plan-YYYY-MM-DD.json and vigil-week-YYYY-MM-DD.json
tools/    fetch-glyph-sdk.sh, assetgen/ (generates the placeholder eye sprite sheet and sounds)
vision.md your long-term plan (you write it)
```

### Swapping the art

The eye is `app/src/main/res/drawable-nodpi/eye_sheet.png`, a horizontal strip of square frames (opening, idle, pupil contracting) whose layout is described in `app/src/main/res/values/eye_sheet.xml`. Replace it with a sheet in the same layout, or edit and re-run `tools/assetgen/generate.py`. See [`tools/assetgen/README.md`](tools/assetgen/README.md).

### Extending (not in v1.1 on purpose)

Geofences, if-then rules, a lockdown tier and eye progression are reserved:
- **Trigger types.** Add a `Trigger` subtype in `core/.../plan/Model.kt`, parse it in `PlanParser`, and add a watcher in `app/.../trigger/`. The engine already handles anything that calls `Vigil.trigger(ids, type)`.
- **Lockdown.** Any blocking feature must have a hard maximum duration, keep the kill switch and the adb escape, and never block calls.

## Design

Minimal, retro-futuristic, serious:
- strictly black, white and red, with red rationed for the eye, the tier pips and urgent states;
- uppercase monospace text with wide letter spacing; the VT323 pixel font only for the **VIGIL** title;
- hard cuts and long holds: no easing, no bounce, no ripples;
- faint scanlines, and hard pixel scaling with no anti-aliasing on sprites.

The eye is an original design, not the Sharingan or any other existing mark.

## Credits

- [VT323](https://fonts.google.com/specimen/VT323) by Peter Hull, SIL Open Font License (`app/src/main/assets/licenses/OFL-VT323.txt`).
- Nothing [Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit), downloaded at build time, not redistributed here.

MIT licensed. See [LICENSE](LICENSE).
