# VIGIL

A pixel-eye commitment device for Android. You plan your week calmly with an AI, import the plan as a JSON file, and VIGIL takes over your phone until you do the thing.

**The app never thinks.** It reads a static plan, fires triggers, shows the takeover, and records what happened. All the intelligence lives in the weekly plan, which is made outside the app.

Personal, sideloaded and open source. It is not on the Play Store, and it uses whatever Android permissions make it reliable.

---

## The weekly loop

1. **Plan.** Give an AI chat [`spec/HANDOFF.md`](spec/HANDOFF.md), your `vision.md`, last week's `log.json` and last week's plan. It answers with a new `plan.json` (format: [`spec/schema.json`](spec/schema.json)).
2. **Import.** In VIGIL tap **IMPORT PLAN** and pick the file. If something is wrong you get a plain-language list of problems, and your previous plan stays active.
3. **Live it.** VIGIL fires each directive when its trigger happens.
4. **Hand back.** At the end of the week open **LOG & EXPORT** › **EXPORT THIS WEEK** and give `log.json` to the AI with `vision.md` and the plan. Keep both files in [`plans/`](plans/) as `plan-YYYY-MM-DD.json` and `log-YYYY-MM-DD.json`.

## What a takeover does

The screen cuts to black. After a silent beat, the phone vibrates with a heartbeat thump, a red slit opens into an eye, the directive types out, and its checklist appears **one step at a time**. Each step's square checkbox stays locked for that step's `min_seconds`. The takeover stays until every step is ticked. The completion sting then plays and a short feedback screen follows.

- **Tier** is shown around the eye: 1 or 2 red pips orbit it; at tier 3 three pips fuse into a turning triangle. Higher tiers also get a longer, heavier intro (heartbeats, a low drone).
- **Leaving early** means holding **HOLD 10S TO ABANDON**. It is logged as `directive_dismissed`, never dropped silently.
- **Glyph lights.** On Nothing phones the back lights pulse with the heartbeat and breathe while the checklist waits.

### Triggers (v1)

| type | fires when |
|---|---|
| `time` | a wall-clock time, on chosen days |
| `adhan` | your adhan app posts its prayer notification (e.g. *"It's time for Isha"*). VIGIL never calculates prayer times. |
| `app_usage` | listed apps are opened N times within M minutes, or used M minutes without a break |

Unknown trigger types (`geofence`, `rule`, `if_then` and `lockdown` are reserved) load with a warning and never fire.

### Modes

- **Quiet mode** is a manual switch on the home screen or in the notification. Each directive's `quiet` field decides what happens while it is on:
  - `queue`: held until quiet mode ends;
  - `silent`: shown without sound or vibration;
  - `ignore`: skipped;
  - `override`: tier 3 only, rings anyway. You can switch overrides off in Settings.
- **I'm not okay today** is a 3-second hold, on the home screen or on any takeover. Until the day boundary (default 04:00) only the plan's `bad_day.keep` directives fire. It is **not** logged as a failure, and the wording stays firm but never punishing.
- **Expiry.** When the plan's `expires` passes, VIGIL runs the plan's `fallback_plan`, or a built-in minimal plan (five prayers, water, sleep). It never goes silent.

## Safety

- **Calls are never blocked.**
  - Takeovers wait while a call or VoIP call is active or the phone is ringing (VoIP calls are recognised by their call notification).
  - They never push themselves over the phone, dialer or emergency apps.
  - Every takeover has a **CALL** button. It silences the takeover, opens the dialer (or the emergency dialer when the phone is locked) and keeps VIGIL out of the way for 10 minutes, even if Android restarts it.
- **Hard maximum.** A takeover closes itself after 90 minutes (`directive_timed_out`).
- **It never nags its way back.**
  - Only a newly fired directive may turn the screen on.
  - An unfinished takeover you leave comes back after ~2.5 s only on an unlocked, awake phone, and only when VIGIL can see (usage access) that the phone app isn't in front.
  - It never comes back over the lock screen, so the lock screen's own emergency button always works; after unlocking it returns.
  - While it is not on screen it makes no sound, vibration or light.
- **Developer kill switch.** Tap the eye on any takeover 7 times, or the **VIGIL** title on the home screen 7 times, to suspend all takeovers.
- **adb escape** (only adb/the system can send it; it is protected by `android.permission.DUMP`):
  ```sh
  adb shell am broadcast -a dev.vigil.action.SUSPEND -n dev.vigil/.engine.KillSwitchReceiver --ei minutes 60
  adb shell am broadcast -a dev.vigil.action.RESUME  -n dev.vigil/.engine.KillSwitchReceiver
  adb uninstall dev.vigil                # the last resort
  ```
  Use the SUSPEND broadcast rather than `am force-stop`: with notification access on, Android restarts VIGIL right after a force-stop.
- **Known limit:** after a reboot, VIGIL starts once the phone has been unlocked for the first time (Android keeps app data locked until then), so a directive due before the first unlock is logged as `missed`.

## Install

1. On your phone, open the repository's **Releases** page and download **`vigil.apk`** from the **"VIGIL latest build"** pre-release. GitHub Actions rebuilds it on every push. (The same APK is also attached to each Actions run as `vigil-apk`.)
2. Open it and allow installs from your browser or file manager when Android asks. Updates install over the old version, because every build is signed with the same key.
3. VIGIL opens on **SETUP**. Grant each item:
   - notifications;
   - full-screen takeovers;
   - display over other apps;
   - battery: unrestricted (Nothing OS: *Settings › Apps › VIGIL › Battery › Unrestricted*);
   - exact alarms;
   - notification access (for adhan);
   - usage access (for app usage);
   - optionally accessibility (instant app detection).
4. **Sideloading blocks two of those pages at first.** Android greys out notification access and accessibility for apps installed from a file. Fix it in *App info › ⋮ (top right) › Allow restricted settings*, then try again.
5. In **SETTINGS › ADHAN**, after your adhan app has posted one notification, tap it in the list. Check the result with **TEST TRIGGER**. The default phrase `time for` already matches "It's time for Isha".

### Glyph lights (Nothing phones)

The Nothing Glyph SDK is downloaded at build time by [`tools/fetch-glyph-sdk.sh`](tools/fetch-glyph-sdk.sh) rather than committed, because it has no open-source licence. On Android 16 it needs no setup. On Android 15, enable Glyph debug mode from a computer (it lasts 48 hours):
```sh
adb shell settings put global nt_glyph_interface_debug_enable 1
```

## Building

- **Everything:** push to GitHub. [`.github/workflows/build.yml`](.github/workflows/build.yml) runs the core tests, builds the APK, runs lint and publishes the pre-release.
- **Core logic only** (any machine with a JDK; no Android SDK needed): `./gradlew -p core test`.
- **APK locally** (needs the Android SDK): `./tools/fetch-glyph-sdk.sh && ./gradlew :app:assembleDebug`. Without the Glyph SDK the app still builds; the Glyph lights are simply switched off.

## Repository layout

```
app/      Android app (Kotlin, Jetpack Compose). Shows things and wires Android events to the core; no decisions.
core/     Plain Kotlin, no Android: plan model, parser + validator, trigger maths, the engine (quiet / bad-day /
          takeover queue as pure functions), log format. All unit tests live here.
spec/     schema.json (plan format), HANDOFF.md (planner prompt), sample-plan.json, sample-log.json
plans/    your weekly history: plan-YYYY-MM-DD.json and log-YYYY-MM-DD.json
tools/    fetch-glyph-sdk.sh, assetgen/ (generates the placeholder eye sprite sheet and sounds)
vision.md your long-term plan (you write it)
```

### Swapping the art and sounds

The eye is `app/src/main/res/drawable-nodpi/eye_sheet.png`, a horizontal strip of 32×32 frames whose layout is described in `app/src/main/res/values/eye_sheet.xml`. Sounds are WAV files in `app/src/main/res/raw/` (`drone`, `thump`, `sting`, `blip`, `dismiss`). Replace any of them with files of the same name, or edit and re-run `tools/assetgen/generate.py`. See [`tools/assetgen/README.md`](tools/assetgen/README.md).

### Extending (not in v1 on purpose)

Geofences, if-then rules, a lockdown tier and eye progression are reserved:
- **Trigger types.** Add a `Trigger` subtype in `core/.../plan/Model.kt`, parse it in `PlanParser`, and add a watcher in `app/.../trigger/`. The engine already handles anything that calls `Vigil.trigger(ids, type)`.
- **Lockdown.** Any blocking feature must have a hard maximum duration, keep the kill switch and the adb escape, and never block calls.

## Design

Minimal, pixelated, retro-futuristic:
- strictly black, white and red, with red rationed for the eye, tier pips and urgent states;
- the VT323 pixel font;
- faint scanlines;
- hard pixel scaling with no anti-aliasing on sprites.

The eye is an original design, not the Sharingan or any other existing mark.

## Credits

- [VT323](https://fonts.google.com/specimen/VT323) by Peter Hull, SIL Open Font License (`app/src/main/assets/licenses/OFL-VT323.txt`).
- Nothing [Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit), downloaded at build time, not redistributed here.

MIT licensed. See [LICENSE](LICENSE).
