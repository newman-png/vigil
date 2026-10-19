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
4. Grant the other rows under **PERMISSIONS**: notifications, full-screen takeovers, display over other apps, battery unrestricted (*Settings › Apps › VIGIL › Battery › Unrestricted*), exact alarms, Do Not Disturb access (the Jummah lock silences the phone), usage access (lets a takeover come back when you leave it) and, optionally, physical activity (the step counter: a walk ends when you stop moving) and phone (call pause).
5. **ADHAN:** once your adhan app has posted a notification, tap it in the list. Check the result with **TEST TRIGGER**; the default phrase `time for` already matches "It's time for Isha".
6. Tap **DONE**. **MY TIMES** opens: set your wake times, bedtime start and goal, classes and meals, then **SAVE**. The first save applies at once.
7. In **TEST**, tap **FIRE IN 60 S (LOCK THE PHONE)** and lock the phone. A test preview of a prayer should take over the locked screen.
8. In **TEST**, tap **RING WAKE ALARM NOW**: VIGIL's wake alarm rings on the alarm stream (also in silent mode); hold **I'M UP** for 3 s to stop it, and a preview of the morning starts.

## What it does today

M1 is the engine and the five prayers, M2 the sleep wind-down and the night lock, M3 the wake alarm and the morning routine, M4 Jummah, M5 the reset and the 10-minute promise, M6 the study block and the attention reset, M7 the gym and meals, M8 the walk and the commute, friends mode, the call protocol, audio and wellness nudges.

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

A prayer never waits behind another protocol: when its adhan comes, a protocol holding the screen pauses (its timers stop) and goes on where it was after the prayer's lock.

### Sleep wind-down and the night lock

- **Sleep shifting:** tonight's target bedtime starts at MY TIMES *bedtime start* (00:30) and moves 20 minutes earlier each night until it reaches *bedtime goal* (23:00). A night is missed when you leave the wind-down early, when the day ends without GOING TO SLEEP, or when GOING TO SLEEP comes more than 30 minutes after the target. After two missed nights in a row the target holds; a good night moves it on again. Home shows tonight's target.
- **Wind-down:** an hour before the target, a takeover (full for 30 s, then quiet): *close the day* (three lines on how it went), *tomorrow* (up to four lines: your top 3 tasks and the first block; they become tomorrow's to-dos), *prep* (10 min), *hygiene* (10 min), *phone away*, *lights low* (with a **WHITE NOISE** switch), and **GOING TO SLEEP**, which ends the day. NOT OKAY: short skips prep and hygiene; minimal is one line, tomorrow's first task, phone away and sleep; then GOING TO SLEEP alone, marked "minimum".
- **GOING TO SLEEP** (the last step, or hold it on home): with Isha still unconfirmed it asks first (I PRAYED ISHA counts it). Before the day's own wake time it does nothing, and home says why.
- **Night lock:** from GOING TO SLEEP (or the screen-off day end after wind-down) until you answer the wake alarm, two hours after it at the latest: a dark, silent, very dim screen with the time, SLEEP, the alarm time, tomorrow's first to-do, WHITE NOISE, CALL, APPS and LEAVE (the usual penalty). The pool is blocked. It shows when you use the phone and never lights up the screen by itself. Fajr takes the screen with its own takeover, and the night lock comes back after it.
- **To-dos:** tomorrow's lines show on home with tick boxes, and the first one on the morning takeover. Unticked ones expire with their day.
- **White noise** is made by the app (no sound file), keeps playing with the screen locked, has STOP in its notification, and stops at the wake alarm or after 8 hours (see [Audio](#audio)).

### Wake alarm and morning routine

- **Wake alarm:** VIGIL rings it at your MY TIMES wake time for the day (04:00 to 11:00), as a real alarm clock (the alarm icon shows in the status bar): a full-screen alarm over the lock screen with a tone made by the app on the alarm stream (heard in silent mode and Do Not Disturb), its volume rising over 30 s, with vibration. Hold **I'M UP** for 3 s to stop it; there is no snooze. Unanswered for 10 minutes, it stops and rings again 5 minutes later, three times at most; then the morning starts at your next unlock. QUIET and the kill switch do not silence it.
- **Morning:** dismissing the alarm ends the night lock and opens the morning takeover: *get up*, *sun in your eyes* (5 min timer), *bathroom and brush* (5), *skincare or shower* (15), *breakfast*, *chores* (20 min timer), *walk* (15 min; left out on days with a class or commute in MY TIMES, since the commute is the walk). Thursday is shorter (shower, no chores or walk); Friday has a shower for Jummah. The screen stays free: the morning notification shows the current step, and **OPEN** (or home) brings the takeover back. The pool stays blocked until every step is done, and the morning free window opens then, but not before an hour after you got up. NOT OKAY: short drops skincare and shortens chores and the walk to 10 minutes; minimal is get up, bathroom, breakfast and sun; then 5 minutes in the sun, marked "minimum".

### Jummah (Fridays)

On Fridays Jummah replaces the Dhuhr protocol: Dhuhr's adhan is journaled but starts nothing, unless Jummah is missed. Every time comes from MY TIMES: *jummah time* (14:40), *commute* (60 min) and your Friday classes.

1. **The night before:** Thursday's wind-down puts *Jummah prep: clean clothes, bag packed* on Friday's to-dos. Friday's morning has the shower.
2. **Leave by** your first Friday class minus the commute minus 15 minutes (no class: jummah time − 5 − commute − 15): a full takeover with *shoes on*, *bag*, *leave now* and *walking on* (it switches WALKING on, which blocks the pool on the way). Google Maps is under APPS.
3. **I'M AT UNI:** from 15 minutes before that class (no class: jummah time − 20) a full takeover asks; **NOT YET** closes it and it asks again every 10 minutes until you confirm or the lock starts. It is also on its notification and on home. Confirming switches WALKING off.
4. **Class-end card:** a silent notification 5 minutes before the last Friday class that ends by jummah time: *Class ends in 5 minutes. Go straight to the prayer place.*
5. **The Jummah lock:** from jummah time − 5 for 45 minutes the phone is silent (Do Not Disturb, priority only: alarms and the callers you allow in your own Do Not Disturb settings still come), the pool is blocked, and a quiet, silent screen counts down. **DONE** unlocks from jummah time + 10. Do Not Disturb goes back to what you had when the lock ends, however it ends (DONE, its time, LEAVE, the kill switch, the day end, or the next start of the app if it was killed). Without Do Not Disturb access (SETUP) the lock shows all the same, and the journal says so.
6. **Missed:** nothing confirmed by jummah time + 50: Jummah is missed, the Dhuhr prayer starts as usual (its window until Asr), and the reset is offered.

Jummah has no shorter version (NOT OKAY says so) and always stays at stage 1. LEAVE is the usual bail-out, and Jummah goes on: the lock still comes, and CONFIRM on home still counts until the missed time. The class rule for prayers does not apply to it.

### Reset

A way back after a derailed day. **RESET** on home (tap it twice) starts it; after 2 missed or left protocols in a row (step-downs do not count), or a missed Jummah, it is offered instead: a full notification and a red card on home with **START RESET** and **NOT NOW**. NOT NOW costs nothing, and the next offer needs 2 more misses.

The reset is a full takeover with the screen locked and the pool blocked: *stop* (1 min: phone down, a glass of water), *name it* (one optional line on what derailed the day, **SKIP** to leave it out; it goes in the report), *body* (3 min: wash your face, wudu if a prayer is near), *space* (5 min: tidy the room), *move* (5 min: walk or stand outside), then **re-enter**: today's missed and waiting protocols are dropped (no catch-up) and the count of misses starts again. An open, unconfirmed prayer comes first; otherwise the next planned protocol starts now if it is due within 60 minutes; otherwise the screen says what comes next, e.g. *NEXT: ASR AT ~15:20*.

Starting a reset costs nothing and never moves a protocol back a stage; leaving early is the usual bail-out. NOT OKAY: short is stop, body and re-enter; minimal is water, wash your face and re-enter.

### The 10-minute promise

"I'll do it in 10", held to account. **LATER** on home, or on the always-on notification, opens a small sheet: one line of what you will do, **5 / 10 / 15 / 30 MIN** and **START**. One promise at a time; home shows it counting down, with **DONE**.

When it is due a full takeover shows exactly your line, with **DONE**, **SNOOZE** (once per promise: half the time, at least 2 minutes) and **ON IT** (the takeover closes). From then on the pool stays locked until DONE (on home, the promise's notification or the takeover); the screen stays free, since the task may be on the phone. LEAVE is the usual bail-out. A promise that is still open when the day ends expires without a penalty. There is no shorter version.

### Study block

Each MY TIMES study row (FROM-TO, and WHAT to study), or **START STUDY** on home (what to study, optional, and 50, 100 or 150 minutes). A quiet takeover with the screen locked (APPS: PoliTO, WhatsApp, calls) and the pool blocked, Brave included. What to study shows big on every step.

1. *Go to your spot* (desk or library), *notebook and pen out*, *open the book or laptop*, *open the one study material for this block*.
2. **Rounds** fill the block exactly: 50 minutes of work, a 10-minute break (*stand up, drink water, look out a window*; the phone stays locked), again, the last round cut to fit (100 min = 50, break, 40). Each shows **ROUND 2/3** or **BREAK** with a large countdown, and ends with a soft chime (silent under QUIET).
3. *Close:* one line on what you did. It goes in the report with what you studied.

NOT OKAY: 25-minute rounds with 5-minute breaks for what is left of the block, then one 25-minute round. A prayer pauses the block and it goes on after. QUIET is only the switch on home: nothing turns it on by itself.

### Attention reset

Right after the Dhuhr lock ends (Fridays: the Jummah lock), unless you left Dhuhr before its minimum. If a MY TIMES class is in progress, or another protocol is on the screen, it waits; if it cannot start within 2 hours it is skipped (not a miss). A very plain full takeover: *face a wall or a window*, *phone face-down* (a tap each), then **LOOK AT ONE SPOT** and the countdown, nothing else to touch, and a soft chime at the end (silent under QUIET). VIGIL's white noise stops when it starts.

It lasts 3 minutes on the first 2 days you complete it in full, then a minute more every 2 such days, up to 10 minutes. NOT OKAY makes it 3 minutes whatever the day; there is nothing shorter.

### Meals

Lunch and dinner at their MY TIMES time (breakfast is part of the morning routine): *go to the kitchen*, *take out the ingredients*, *cook*, *eat at the table, phone face-down*, *clean up the kitchen*, *walk* (10 min). The screen stays free: the notification (**POOL LOCKED** until the cleanup) shows the step and opens the takeover. The pool opens at the cleanup; the meal is done after the walk, and dinner done opens the evening free window. VIGIL plans no dishes. NOT OKAY: a simple meal, eaten at the table, then the cleanup (no walk); then eat something simple and clean up. Simple meals written into the meal's `fallback` list in `protocols.json` show in those versions.

### Gym

At each MY TIMES gym time (FROM and the workout's minutes): *change clothes, shoes on*, *pack the bag*, *leave now* (the gym is about 5 minutes away), the **workout**, then *back home: shower*. The pool is blocked; the phone stays usable, and the gym notification brings the takeover back.

The workout screen is made for sweaty hands: the exercise on top, pre-filled with the reps and weight you logged last time, **REPS**, **KG** and **REST** steppers and a big **LOG SET**. After each set a rest screen counts down (90 seconds, or the rest you set for that exercise, which is kept) and chimes at the end (silent under QUIET); **SKIP REST** goes back. The list shows every exercise with its last set and today's sets; **+ ADD EXERCISE** takes any name. **FINISH WORKOUT** (tap twice) logs it; forgotten, it ends by itself an hour after the planned minutes. The default exercises are a simple full-body list in `protocols.json`. NOT OKAY: a 20-minute session; then a 10-minute walk. Every set goes into the report and the CSV, with each exercise's progress.

### Walk and commute

**I'M WALKING** on home, or by itself at every MY TIMES commute time (and at Jummah's *walking on* step). The point is to look at the world, not the feed: only the pool is blocked; Google Maps, WhatsApp, calls and every other app stay usable. No prompts and no takeovers, just a silent notification **WALKING · POOL BLOCKED** with **STOP**. It ends when you switch it off, at I'M AT UNI, or after 10 minutes without steps (the phone's step counter, with the *physical activity* permission). Without a step counter a commute ends 15 minutes after its length, and any other walk after 3 hours. A commute day counts as the morning walk. The report shows each walk's minutes and why it ended.

### Friends mode

**I'M WITH FRIENDS** on home: the pool refuses with **YOU'RE WITH FRIENDS** and no wait, for up to 4 hours (then it switches itself off). Calls, Maps, WhatsApp and the PoliTO app stay usable. Quiet: no takeover, a silent notification with **STOP**.

### Calls

Each MY TIMES call (WHO, REMIND, BY) is its own: mom and dad are two. At the reminder time a full takeover shows the name big, with **CALL** (the dialer; VIGIL stores no contacts) and **NOT NOW**. **DONE** appears after a call of at least 3 minutes made after the card appeared (any call the phone reports, WhatsApp calls too); when such a call ends, the card comes back with DONE (also on its notification and on home). Nothing done after 30 minutes: the card comes back once. Not done by the deadline (16:00 when empty): missed. A call that goes on for 20 minutes (WRAP-UP) gets a silent **TIME TO WRAP UP**, once. NOT OKAY: send a message instead (**MESSAGE** opens WhatsApp, then DONE); there is nothing shorter.

### Audio

**AUDIO** on home: **WHITE NOISE**, a **METRONOME** (40 to 208 beats a minute, with -, +, -10, +10 or **TAP** to tap the beat) and **MY FILES**: **+ ADD** copies an audio file into VIGIL (it plays even if the original goes), each with **PLAY**, **RENAME** and **DELETE**, and **LOOP** for all of them. One sound at a time; it keeps playing with the screen locked, with **STOP** in its notification. White noise and the metronome are made by the app. The wake alarm and the attention reset stop VIGIL's sound; the journal records every start and stop.

### Wellness nudges

Up to four short takeovers a day at times that feel random (fixed for the day), at least 90 minutes apart, from 90 minutes after your wake time until the wind-down: *drink a glass of water*, *look at something far away for 20 seconds*, *stand up and stretch*. Each is one big line and a countdown of 45 seconds, then it ends by itself. After 90 minutes without steps while you use the phone, *stand up and walk for a minute* (60 seconds). Only while you use the phone and nothing else is going on (no protocol, class, QUIET, night lock, call, friends or walking); otherwise it is skipped. NOT OKAY or LEAVE end one at once; a nudge is never a miss and never costs anything.

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

- **Free windows.** The pool opens only inside a free window; outside one it is blocked outright. *Morning:* once the morning routine is done and at least 60 minutes after you got up, until the next planned start, usually Dhuhr. *Evening:* once dinner is done (45 minutes after dinner in MY TIMES at the latest, if dinner did not run) until wind-down. Each window allows 30 minutes of use a day.
- **Waits.** The Nth open of the day waits N minutes (1, 2, 3 ..., at most 10) on a wait screen. Stay on it and the app opens at zero; leave it and the next wait is the same length.
- **Sessions and cooldowns.** Switching between pool apps, or leaving the pool for less than 60 seconds, keeps the session going. When it ends, the pool locks for twice the minutes you used (at most 60).
- **Heavy use.** 10 minutes in one session, or the 3rd open within 30 minutes, closes the app (**ENOUGH FOR NOW**) and starts the cooldown early.
- **Locks and penalties.** A protocol's lock and a bail-out penalty block the pool too. A live session ends, and the app closes, as soon as anything blocks.

Every blocked screen says why and until when. The counters reset when the day ends; running cooldowns and penalties keep their end times.

### The day

The day does not end at midnight. It ends at the first of:

- **GOING TO SLEEP** (the wind-down's last step, or hold it 2 s on home);
- 2 hours of screen off after wind-down started; the day then ends at the moment the screen went off;
- the wake alarm, if the day is still running then (a wake time before 06:00);
- 06:00 the next morning.

A new day starts at once and the counters reset. Anything after midnight belongs to the day before until it ends. GOING TO SLEEP before the day's own wake time does nothing.

### Stages

Every protocol starts fully forced and eases off as the habit sticks:

1. **Forced:** every step, step timer and lock; the takeover comes back if you leave it.
2. **Guided:** a plain checklist shown once, whose step timers can be tapped through; it does not come back, and the pool stays blocked while its lock runs.
3. **Reminder:** one notification and no lock (a prayer still needs CONFIRM within its window).

A protocol moves up a stage after 10 clean completions in a row (the full version, with no NOT OKAY, no LEAVE and no returning takeover) and down one after 3 misses or bail-outs within its last 10 outcomes. The pips next to each protocol show its stage. A protocol with `"stages": false` stays at stage 1.

### Home

The **VIGIL** title, the day (since when, and when it ends at the latest), the pool's status, flags (quiet, suspended, in a call, walking, with friends, the phone silent for Jummah), the prayers prayed and missed today (and Jummah), the next planned start, tonight's target bedtime and the next wake alarm (with missed nights in a row), a red **A PROTOCOL IS RUNNING · OPEN** (or **NIGHT LOCK**) while a takeover owns the screen, the reset offer (**START RESET** / **NOT NOW**), **LATER** and **RESET**, **START STUDY**, the running promise with **DONE**, today's Jummah (where it is, with **I'M AT UNI** or **CONFIRM**), an attention reset waiting to start, today's call cards (with **DONE** once a call unlocked it), a running morning, meal or gym with **OPEN**, today's **TO-DOS** with tick boxes, open prayers with **CONFIRM**, and **TODAY**: the day's timeline (prayers at their adhan, `~` when estimated; study blocks with what to study, gym sessions with their minutes, commutes, calls) with each protocol's stage and when it last fired. Below that:

| item | what it does |
|---|---|
| **QUIET** | Tap to make every takeover silent (no sound, vibration or Glyph), whatever its intensity. Also on the always-on notification. |
| **I'M WALKING** | Tap to switch it on or off (see [Walk and commute](#walk-and-commute)); it says since when and when it ends by itself. The first tap asks for the step counter. |
| **I'M WITH FRIENDS** | Tap to switch it on or off (see [Friends mode](#friends-mode)); it says until when (4 hours at most). |
| **GOING TO SLEEP** | Hold 2 s: the day ends now and the night lock starts (CONFIRM ISHA first while Isha is open). |
| **AUDIO** | White noise, the metronome and your own files (see [Audio](#audio)); it shows what plays. |
| **MY TIMES** | Your week: wake time per weekday (the wake alarm), bedtime start and goal (sleep shifting), classes, meals, study, gym, calls, commute and the Jummah time, with **+ ADD** rows and weekday chips. A change **applies from tomorrow** (only the very first save applies at once). |
| **SETUP** | Permissions (with the count missing, Do Not Disturb access included), restricted settings, battery, **POOL AND APPS** (the pool, the browsers found, the rules, the APPS button), **ADHAN**, **TAKEOVER** (switches for sound, vibration and the Glyph lights) and **SAFETY**. |
| **TEST** | **START** any protocol as a test preview: stage 1, logged as a test, and left out of stages, penalties, prayer times and the report. **FIRE IN 60 S (LOCK THE PHONE)**: a real exact alarm starts a preview of the next prayer, to try the whole path with the screen off. **RING WAKE ALARM NOW**: the real wake alarm as a test; I'M UP starts a morning preview. **JUMMAH · EACH PART NOW**: the leave-by takeover, I'M AT UNI, the class-end card or the lock (with Do Not Disturb; LEAVE ends a preview at no cost). **WALK · FRIENDS · CALL · NUDGE**: previews (the walk and friends show their notification for 2 minutes and block nothing; the call card is the real card; a nudge shows now). **OPEN AUDIO**. **1-MINUTE PROMISE**: a test promise due in a minute. **POOL CHECK**: what the pool would do right now for each pool app. **LAST EVENTS**: the newest 30 journal entries. |
| **EXPORT** | A progress report (markdown: totals, gym progress per exercise, then each day's prayers, Jummah, pool use, protocols, bail-outs, resets with what derailed the day, promises kept, snoozed or left, study blocks with what you did, gym sets, walks and time with friends, calls done or missed, nudges, app exits, late alarms and stage changes) and the whole journal as CSV (gym sets with exercise, reps and weight), through the share sheet. |

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

- **Exact alarms.** VIGIL keeps one exact alarm at the next moment it must act (a prayer heads-up, the end of a lock, a window, the day's end), at most 30 minutes ahead. Android delivers it on the minute even in Doze with the screen off. It is deliberately not an "alarm clock" alarm: that would show as your next alarm, and a bedtime Do Not Disturb schedule set to end at the next alarm would switch off early. Only the wake alarm is a real alarm clock (the most reliable kind; it shows as your next alarm, as it should). An alarm more than a minute late is journaled (`alarm_late`); a wake alarm that never rang (the phone was off) is logged as `not_fired`.
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

Sounds play only while a takeover is at full intensity. Full takeovers use the alarm stream (heard with the ringer on vibrate); full-then-subtle ones follow the ringer like a notification. QUIET silences every takeover, the **SETUP › TAKEOVER** switches turn sound, vibration or the Glyph lights off for good, and completing or leaving a takeover is silence. The files are deterministic placeholders made by [`tools/assetgen/generate.py`](tools/assetgen/generate.py) (see [`tools/assetgen/README.md`](tools/assetgen/README.md)). The wake alarm's beeps, the white noise and the metronome have no files: the app makes them as it plays them.

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
