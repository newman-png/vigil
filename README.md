# VIGIL

A protocol app for Android, with a pixel eye. Every part of the day you care about is a **protocol**: a trigger that starts it, concrete steps, a lock that holds while it runs, and a rule for when it ends. The phone enforces what you decided while thinking clearly.

**The app never thinks.** Every behaviour is a fixed rule from one bundled data file, [`protocols.json`](core/src/main/resources/dev/vigil/core/protocols.json), plus your own times (MY TIMES, set in the app). There is no AI inside the app, no planning, no import and no in-app editor. To change a rule, you edit the file with Claude Code and install the new build.

Personal, sideloaded and open source. It is not on the Play Store, and it uses whatever Android permissions make it reliable. It is built for a Nothing Phone (3a) Pro and needs Android 13 or later.

---

## Install

1. On your phone, open the repository's **Releases** page and download **`vigil.apk`** from the **"VIGIL latest build"** pre-release. GitHub Actions rebuilds it from the `main` branch on every push there. (Every run, on any branch, also attaches the APK as the `vigil-apk` artifact.)
2. Open it and allow installs from your browser or file manager when Android asks. Updates install over the old version, because every build is signed with the same key.

## First setup (Nothing Phone (3a) Pro)

VIGIL opens on **SETUP**.

1. **Allow restricted settings.** Android greys out accessibility and notification access for apps installed from a file. Try to turn one of them on once, then open *App info › ⋮ (top right) › Allow restricted settings* and confirm. (The ⋮ menu only appears after that first try; **SETUP › RESTRICTED SETTINGS** has a button for App info.)
2. **GUARD (ACCESSIBILITY):** turn on **VIGIL Guard** under Accessibility. It keeps the pool apps closed, and it only learns which app is in front, never what is on the screen.
3. **NOTIFICATION ACCESS:** turn VIGIL on. It reads your adhan app's prayer notification, and other apps' call notifications, so a takeover never covers a WhatsApp call.
4. Grant the other rows under **PERMISSIONS**: notifications, full-screen takeovers, display over other apps, battery unrestricted (*Settings › Apps › VIGIL › Battery › Unrestricted*), exact alarms, usage access (lets a takeover come back when you leave it) and, optionally, phone (call pause).
5. **ADHAN:** once your adhan app has posted a notification, tap it in the list. Check the result with **TEST TRIGGER**; the default phrase `time for` already matches "It's time for Isha".
6. Tap **DONE**. **MY TIMES** opens: set your wake time, bedtime goal, classes and meals, then **SAVE**. The first save applies at once.
7. In **TEST**, tap **FIRE IN 60 S (LOCK THE PHONE)** and lock the phone. A test preview of a prayer should take over the locked screen.

## What M1 does today

M1 is the engine and the five prayers. The bundled `protocols.json` holds the prayer protocols; the other protocols come with later milestones.

### Prayers

Each prayer starts when your adhan app posts its notification. VIGIL never calculates prayer times, and it ignores a repeat within 30 minutes.

1. **Heads-up:** a silent notification 10 minutes before the expected adhan, which is yesterday's adhan + 24 hours (so none until VIGIL has seen that adhan once).
2. **Lock at the adhan:** the takeover appears, full for 30 seconds, then a quiet screen. The screen is locked and the pool blocked for 20 minutes.
3. **Steps:** *Wudu, then pray*, then *Done* (it unlocks after 8 minutes, so it cannot be tapped through), then quiet time until the 20 minutes end. The prayer is complete when the lock has ended and you have confirmed.
4. **Lock over, not confirmed:** the screen and the pool are released, but the prayer stays open: home and a silent notification show **CONFIRM**.
5. **Window:** until the next prayer's adhan, estimated from yesterday (Isha: until the day ends). With 15 minutes left and still unconfirmed, the takeover comes back.
6. **Missed:** the window closes, or the next adhan arrives, before you confirm. It counts against the prayer's stage.
7. **Class rule:** when a MY TIMES class is in progress at the adhan, only a silent card shows. When the class ends, if the prayer is still unconfirmed and its window open, the takeover starts.
8. **No ladder:** NOT OKAY shortens a prayer's lock to the 8-minute minimum. LEAVE before those 8 minutes is a bail-out; after them it costs nothing.

### The takeover

Black, a red slit with the heartbeat, the eye opens into silence, the protocol's title, then one step at a time. Hold anywhere above the bottom strip for 1.5 s to confirm a step once its lock is over; a timer step counts down and ends by itself; a text step is typed. The eye slowly fills with red as the run goes on.

Each protocol has an intensity: **full** (dark screen, vibration, sound, Glyph lights, which turn off after 30 s), **subtle** (a quiet screen with no sound and no Glyph), or **full then subtle** (full for 30 s, then subtle). Prayers are full then subtle.

The faint strip at the bottom:

- **CALL** (tap): opens the dialer. See [Safety](#safety).
- **APPS** (tap): the whitelist, usable in every lock: the phone app, your adhan app, the clock, Google Maps, WhatsApp and the PoliTO Students app (found by its launcher name). Apps that are not installed are left out.
- **NOT OKAY** (hold 3 s): one rung down the step-down ladder: the short version, then the minimal one, then a single step marked "minimum". Logged, costs nothing, and is neither a miss nor a bail-out.
- **LEAVE** (hold 10 s): the **bail-out**. You may always quit early; it is logged and locks the pool for 2 more hours (stacking, at most 6 hours a day). While you hold it, the strip says what leaving costs.

At stage 1, a takeover you leave for an app that is not on the whitelist comes back after about 2.5 seconds, on an unlocked phone.

### The pool

The distracting apps share one counter: TikTok, Facebook (and Facebook Lite), YouTube, Instagram, Pinterest, Brave, and every browser installed on the phone (any app that opens `https://` links; your home screen app is never closed). **SETUP › POOL AND APPS** lists what VIGIL found on your phone.

- **Free windows.** The pool opens only inside a free window; outside one it is blocked outright. *Morning:* from 60 minutes after waking (from M3: once the morning routine is done) until the next planned start, usually Dhuhr. *Evening:* from 45 minutes after dinner in MY TIMES (from M7: once the dinner protocol is done) until wind-down, which is your bedtime goal minus 60 minutes until M2. Each window allows 30 minutes of use a day.
- **Waits.** The Nth open of the day waits N minutes (1, 2, 3 ..., at most 10) on a wait screen. Stay on it and the app opens at zero; leave it and the next wait is the same length.
- **Sessions and cooldowns.** Switching between pool apps, or leaving the pool for less than 60 seconds, keeps the session going. When it ends, the pool locks for twice the minutes you used (at most 60).
- **Heavy use.** 10 minutes in one session, or the 3rd open within 30 minutes, closes the app (**ENOUGH FOR NOW**) and starts the cooldown early.
- **Locks and penalties.** A protocol's lock and a bail-out penalty block the pool too. A live session ends, and the app closes, as soon as anything blocks.

Every blocked screen says why and until when. The counters reset when the day ends; running cooldowns and penalties keep their end times.

### The day

The day does not end at midnight. It ends at the first of:

- **GOING TO SLEEP** on home (hold 2 s);
- 2 hours of screen off after wind-down started (your bedtime goal minus 60 minutes until M2); the day then ends at the moment the screen went off;
- 06:00 the next morning;
- from M3, the wake alarm.

A new day starts at once and the counters reset. Anything after midnight belongs to the day before until it ends. GOING TO SLEEP before the day's own wake time does nothing.

### Stages

Every protocol starts fully forced and eases off as the habit sticks:

1. **Forced:** every step, step timer and lock; the takeover comes back if you leave it.
2. **Guided:** a plain checklist shown once, whose step timers can be tapped through; it does not come back, and the pool stays blocked while its lock runs.
3. **Reminder:** one notification and no lock (a prayer still needs CONFIRM within its window).

A protocol moves up a stage after 10 clean completions in a row (the full version, with no NOT OKAY, no LEAVE and no returning takeover) and down one after 3 misses or bail-outs within its last 10 outcomes. The pips next to each protocol show its stage. A protocol with `"stages": false` stays at stage 1.

### Home

The **VIGIL** title, the day (since when, and when it ends at the latest), the pool's status, flags (quiet, suspended, in a call), the prayers prayed and missed today, the next planned start, a red **A PROTOCOL IS RUNNING · OPEN** while a takeover owns the screen, open prayers with **CONFIRM**, and **TODAY**: the day's timeline (prayers at their adhan, `~` when estimated) with each protocol's stage and when it last fired. Below that:

| item | what it does |
|---|---|
| **QUIET** | Tap to make every takeover silent (no sound, vibration or Glyph), whatever its intensity. Also on the always-on notification. |
| **GOING TO SLEEP** | Hold 2 s: the day ends now. |
| **MY TIMES** | Your week: wake time per weekday, bedtime goal, classes, meals, study, gym, calls, commute and the Jummah time, with **+ ADD** rows and weekday chips. A change **applies from tomorrow** (only the very first save applies at once). M1 uses wake, dinner, the bedtime goal and classes; the rest is for later milestones. |
| **SETUP** | Permissions (with the count missing), restricted settings, battery, **POOL AND APPS** (the pool, the browsers found, the rules, the APPS button), **ADHAN**, **TAKEOVER** (switches for sound, vibration and the Glyph lights) and **SAFETY**. |
| **TEST** | **START** any protocol as a test preview: stage 1, logged as a test, and left out of stages, penalties, prayer times and the report. **FIRE IN 60 S (LOCK THE PHONE)**: a real exact alarm starts a preview of the next prayer, to try the whole path with the screen off. **POOL CHECK**: what the pool would do right now for each pool app. **LAST EVENTS**: the newest 30 journal entries. |
| **EXPORT** | A progress report (markdown: totals, then each day's prayers, pool use, protocols, bail-outs, app exits, late alarms and stage changes) and the whole journal as CSV, through the share sheet. |

## Coming next

Each milestone adds its protocols to `protocols.json` (with core, app and tests) and ships through the same release.

- **M2 Sleep wind-down** (coming): the night lock until the wake alarm, sleep shifting (20 minutes earlier per night until the goal), close-the-day lines that become tomorrow's to-dos, optional white noise.
- **M3 Morning routine** (coming): VIGIL rings the wake alarm itself; the pool stays blocked until every step is done and 60 minutes have passed; weekday versions.
- **M4 Jummah** (coming): on Fridays it replaces Dhuhr: prep the night before, a leave-by takeover, "I'M AT UNI" before class, then the phone silent and the pool blocked for 45 minutes.
- **M5 Reset and the 10-minute promise** (coming): a way back after a derailed day, and "I'll do it in 10" held to account.
- **M6 Study block and attention reset** (coming): timed work and break rounds, and a few still minutes facing a wall after Dhuhr.
- **M7 Gym and meals** (coming): workout logging with a rest timer and your last weights pre-filled; cook, eat, clean up.
- **M8 Walk and commute, friends mode, calls, audio and wellness nudges** (coming): switches that block the pool, the daily call, white noise, a metronome and your own audio files.

## Changing the protocols

Every rule lives in [`core/src/main/resources/dev/vigil/core/protocols.json`](core/src/main/resources/dev/vigil/core/protocols.json). [`spec/PROTOCOLS.md`](spec/PROTOCOLS.md) documents every field for Claude Code. Your times stay in MY TIMES.

1. Ask Claude Code for the change, e.g. "make the Asr lock 25 minutes". It edits `protocols.json` following `spec/PROTOCOLS.md`, and it can read [`vision.md`](vision.md) for your tone and reasons.
2. Run the core tests (`./gradlew -p core test`). `BundledProtocolsTest` parses the file and fails on any error, and so does CI, so a bad edit never ships.
3. Push to `main`. GitHub Actions publishes a new **VIGIL latest build**.
4. Install the new `vigil.apk` over the old one.

## Safety

- **Calls are never blocked.**
  - Takeovers and the Guard wait while a call or VoIP call is active or the phone is ringing, and they never cover the phone, dialer or emergency apps.
  - A call pauses the running protocol's lock and timers for its length. It never ends the protocol and costs nothing.
  - Every takeover has **CALL**, from its first frame. One tap silences it, opens the dialer (the emergency dialer when the phone is locked) and keeps VIGIL out of the way for 10 minutes, even if Android restarts it.
- **Never over the lock screen.** A takeover you left comes back only on an unlocked phone, so the lock screen's own emergency button always works.
- **Always a way out.** APPS, NOT OKAY and LEAVE are on every takeover (see [The takeover](#the-takeover)).
- **Kill switch.** Tap the eye on a takeover, or the **VIGIL** title on home, 7 times in a row (each tap within 1.5 s of the last). A 5-minute wait screen follows; stay on it, because leaving it cancels. Then VIGIL is suspended for 1 hour: no takeovers, and the pool is open. **SETUP › SAFETY › RESUME NOW** ends it early.
- **adb escape.** Only adb and the system can send these, because the receiver is protected by `android.permission.DUMP`:
  ```sh
  adb shell am broadcast -a dev.vigil.action.SUSPEND -n dev.vigil/.engine.KillSwitchReceiver --ei minutes 60
  adb shell am broadcast -a dev.vigil.action.RESUME -n dev.vigil/.engine.KillSwitchReceiver
  ```
  SUSPEND is the kill switch without the wait: `minutes` defaults to 60 and is capped at one week. Use it rather than `am force-stop`: with notification access on, Android restarts VIGIL right after a force-stop.
- **Uninstalling works like any app.** VIGIL has no device admin and never covers Settings, force stop or the uninstall prompt. From a computer: `adb uninstall dev.vigil`.

## Reliability notes

- **Exact alarms.** VIGIL keeps one exact alarm at the next moment it must act (a prayer heads-up, the end of a lock, a window, the day's end), at most 30 minutes ahead. Android delivers it on the minute even in Doze with the screen off. It is deliberately not an "alarm clock" alarm: that would show as your next alarm, and a bedtime Do Not Disturb schedule set to end at the next alarm would switch off early. An alarm more than a minute late is journaled (`alarm_late`).
- **Battery unrestricted.** Without it Android may put VIGIL to sleep. If protocols stop starting after a while, don't swipe VIGIL away in Recents.
- **Notification access is rebound.** Android sometimes disconnects a notification listener, which would silently stop the prayers. VIGIL asks Android to reconnect it at once and checks again about every minute. **SETUP › ADHAN** shows whether the listener is connected; if it stays disconnected, switch VIGIL's notification access off and on.
- **The journal records app exits.** On every start, VIGIL journals the exits Android recorded since the last one (killed for memory, crashed, force-stopped ...) as `app_exit`, so the report shows when and why it was not running.
- **Late starts are logged, not fired.** A planned start more than 30 minutes late (the phone was off) does not fire late. The self-check at the end of each day logs every planned start that never fired, and every expected adhan that never came, as `not_fired`.
- **After a reboot**, VIGIL starts once the phone has been unlocked for the first time (Android keeps app data locked until then).

## Sounds

Five WAV files in [`app/src/main/res/raw/`](app/src/main/res/raw/). To swap one, replace the file with another of the same name.

| file | what it is | when it plays |
|---|---|---|
| `heartbeat.wav` | a dry, low lub-dub | with the red slit before the eye opens (two beats when full, one when full then subtle) |
| `swell.wav` | a reversed swell that ends abruptly | rising before the eye opens |
| `tone.wav` | a thin high tone, looped | under the swell, cut with it the moment the eye opens |
| `drone.wav` | a slow sub-bass drone, looped | fades in once the eye is open (half as loud when full then subtle, and it fades out when the takeover turns subtle) |
| `thud.wav` | a dry low thud with no tail | each confirmed step |

Sounds play only while a takeover is at full intensity. Full takeovers use the alarm stream (heard with the ringer on vibrate); full-then-subtle ones follow the ringer like a notification. QUIET silences every takeover, the **SETUP › TAKEOVER** switches turn sound, vibration or the Glyph lights off for good, and completing or leaving a takeover is silence. The files are deterministic placeholders made by [`tools/assetgen/generate.py`](tools/assetgen/generate.py) (see [`tools/assetgen/README.md`](tools/assetgen/README.md)).

## Audio licence

Only CC0 audio may be committed to this repository. The generated placeholders are dedicated to CC0, and any replacement must be CC0 too.

## Glyph lights (Nothing phones)

At full intensity the back lights pulse with the heartbeat and breathe while a step waits, for the first 30 seconds of a takeover. The Nothing Glyph SDK is downloaded at build time by [`tools/fetch-glyph-sdk.sh`](tools/fetch-glyph-sdk.sh) rather than committed, because it has no open-source licence. On Android 16 the lights need no setup. On Android 15, enable Glyph debug mode from a computer (it lasts 48 hours):
```sh
adb shell settings put global nt_glyph_interface_debug_enable 1
```

## Building

- **Core logic** (any machine with a JDK; no Android SDK needed): `./gradlew -p core test`. Every decision lives in `core/` and is unit-tested there, including the bundled `protocols.json`.
- **The app** is built only on GitHub Actions: push, and [`.github/workflows/build.yml`](.github/workflows/build.yml) runs the core tests, builds the APK and runs lint; on `main` it also publishes the pre-release.

## Repository layout

```
app/       Android app (Kotlin, Jetpack Compose): the screens, the takeover, the Guard, and Android events wired to
           the core. No decisions.
core/      Plain Kotlin, no Android: protocols.json (in resources) with its parser and validator, MY TIMES, the day,
           the timeline, the pool rules, runs and stages, the engine (pure functions over one persisted state), the
           journal and the report. All unit tests live here.
spec/      PROTOCOLS.md: every field of protocols.json, for Claude Code
tools/     fetch-glyph-sdk.sh, assetgen/ (generates the placeholder eye sprite sheet and sounds)
vision.md  your long-term picture (you write it); Claude Code reads it when it edits protocols.json for you
```

The eye is `app/src/main/res/drawable-nodpi/eye_sheet.png`, a horizontal strip of square frames described in `app/src/main/res/values/eye_sheet.xml`. Replace it with a sheet in the same layout, or edit and re-run `tools/assetgen/generate.py`.

## Design

Minimal, retro-futuristic, serious: strictly black, white and red, with red rationed for the eye and urgent states; uppercase monospace text with wide letter spacing, and the VT323 pixel font only for the **VIGIL** title; hard cuts and long holds, with no easing, bounce or ripples. The eye is an original design, not the Sharingan or any other existing mark.

## Credits

- [VT323](https://fonts.google.com/specimen/VT323) by Peter Hull, SIL Open Font License (`app/src/main/assets/licenses/OFL-VT323.txt`).
- Nothing [Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit), downloaded at build time, not redistributed here.

MIT licensed. See [LICENSE](LICENSE).
