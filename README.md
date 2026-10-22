# VIGIL

A protocol app for Android, with a pixel eye. Every part of the day you care about is a **protocol**: a trigger that starts it, concrete steps, a lock that holds while it runs, and a rule for when it ends. The phone enforces what you decided while thinking clearly.

**The app never thinks.** Every behaviour is a fixed rule from one bundled file, [`protocols.json`](core/src/main/resources/dev/vigil/core/protocols.json), plus your own times (MY TIMES, set in the app). There is no AI inside the app, no planning, no import and no in-app editor. You change a rule by editing `protocols.json` with Claude Code and installing the new build.

Personal, sideloaded and open source. Built for a Nothing Phone (3a) Pro; needs Android 13 or later.

---

## Install

1. On the phone, open the repository's **Releases** page and download **`vigil.apk`** from the **VIGIL latest build** pre-release. GitHub Actions rebuilds it on every push to `main`.
2. Open it and allow installs from your browser when Android asks. Every build is signed with the same key, so a new one installs over the old one and keeps your data.
3. **Reinstalling** (after an uninstall, or on a new phone): Android's auto backup keeps VIGIL's state (days, stages, the pool), the journal, MY TIMES and the settings, and gives them back when VIGIL is installed again. It needs Google backup switched on in the phone's settings; Android backs up about once a day while the phone charges on Wi-Fi (`adb shell bmgr backupnow dev.vigil` forces one). The audio files you added and the exports are not backed up, and the permissions have to be granted again.

## First setup (Nothing Phone (3a) Pro)

VIGIL opens on **SETUP**.

1. **Allow restricted settings.** Android greys out accessibility and notification access for apps installed from a file. Try to turn one of them on once, then open *App info › ⋮ (top right) › Allow restricted settings* and confirm. **SETUP › RESTRICTED SETTINGS** has the App info button; the ⋮ menu only appears after that first try.
2. **PERMISSIONS:** grant every row (each has an OPEN or ALLOW button; the section header counts what is missing).

   | row | why |
   |---|---|
   | GUARD (ACCESSIBILITY) | Turn on **VIGIL Guard**. It keeps the pool apps closed, and it is how a takeover gets the whole screen while you use the phone. It only learns which app is in front, never what is on the screen. |
   | NOTIFICATIONS | The always-on notification, heads-ups, CONFIRM cards, and the way a takeover opens. |
   | FULL-SCREEN TAKEOVERS | A takeover (and the wake alarm) can open over the lock screen or on a dark screen. |
   | DISPLAY OVER OTHER APPS | A takeover can appear over the app in front, and come back if you leave it. |
   | BATTERY: UNRESTRICTED | Android does not put VIGIL to sleep, so protocols start on time. |
   | EXACT ALARMS | Starts, heads-ups and the ends of locks happen on the minute. |
   | DO NOT DISTURB ACCESS | The Jummah lock silences the phone and puts it back afterwards. |
   | PHYSICAL ACTIVITY (optional) | The step counter: a walk ends when you stop moving (and the movement nudge, when nudges are on). Only the step count is read. |
   | NOTIFICATION ACCESS | The prayer trigger: VIGIL reads your adhan app's notification. It also sees other apps' call notifications, so a takeover never covers a WhatsApp call. |
   | USAGE ACCESS | Lets a takeover come back when you leave it, and helps the Guard catch a pool app it missed. |
   | PHONE (CALL PAUSE) (optional) | A call pauses the running protocol for the call's length. |

3. **ADHAN:** once your adhan app has posted a prayer notification, tap it in the list. **TEST TRIGGER** shows whether a text would start a prayer; the default phrase `time for` matches "It's time for Isha".
4. Tap **DONE**. **MY TIMES** opens: set your wake times, bedtime, classes, meals and the rest, then **SAVE**. The very first save applies at once.
5. In **TEST**, tap **FIRE IN 60 S (LOCK THE PHONE)** and lock the phone: a prayer preview should take over the locked screen. Then **RING WAKE ALARM NOW**.

## The takeover

The screen a protocol takes: black, a red slit with a heartbeat, the eye opens, the protocol's title, then one step at a time. Hold a step 1.5 s to confirm it once its own lock is over; a timer counts down and ends by itself; a text step is typed. The eye slowly fills with red as the run goes on.

- **Locks.** *Screen*: the takeover stays up, only the APPS button's apps are usable, and the pool is blocked. *Pool*: only the pool is blocked; the phone stays free, and the protocol's notification (or home) shows the current step and opens the takeover. *None*.
- **Intensity.** *Full*: dark screen, vibration, sound and the Glyph lights (off after 30 s). *Subtle*: a quiet screen, no sound, no Glyph. *Full then subtle*: full for 30 s, then subtle.
- **CALL** (tap): the dialer (see [Safety](#safety)).
- **APPS** (tap): the whitelist, usable in every lock: the phone app, your adhan app and the Prayer Times app (any app named "Prayer", without picking it in SETUP), the clock, Google Maps, WhatsApp, the PoliTO Students app (found by its launcher name), Keep Notes and the calculator. Apps that are not installed are left out, and an app found twice is listed once.
- **NOT OKAY** (hold 3 s; a red bar under it fills while you hold, and empties if you let go): one rung down the step-down ladder: the short version, then the minimal one, then a single step marked "minimum". Logged, costs nothing, neither a miss nor a bail-out (but not "clean" for stages). Each protocol below says what NOT OKAY does for it.
- **LEAVE** (hold 10 s, with the same bar): the bail-out. Always allowed. It is logged and locks the pool for 2 more hours (stacking, at most 6 hours of penalty a day); while you hold it, the strip says what it costs.

**Full screen, also while you use the phone.** On a locked or dark phone the takeover opens through its notification. On an unlocked phone in use Android turns that into a small heads-up banner, so VIGIL starts the takeover itself, through the Guard (an accessibility service may open screens from the background): with the Guard on, it takes the whole screen. Every safety rule stays: never during a call or over the dialer, out of the way for 10 minutes after CALL, never back over the lock screen, and only a protocol that has just started may turn the screen on. With the Guard off, the notification is what you get (tap it).

Only one protocol holds the screen at a time; another waits its turn. A prayer never waits: whatever holds the screen pauses (its timers stop) and goes on after the prayer's lock. At stage 1, a screen takeover you leave for an app outside the whitelist comes back after about 2.5 s, on an unlocked phone.

## The protocols

What the bundled `protocols.json` does. Every number here lives in that file or in MY TIMES.

**Prayers (Fajr, Dhuhr, Asr, Maghrib, Isha).** Each starts when your adhan app posts its notification; VIGIL never calculates prayer times, and a repeat within 30 minutes is ignored. A silent heads-up comes 10 minutes before the expected adhan (yesterday's adhan + 24 hours, so none until VIGIL has seen that prayer once). At the adhan a full-then-subtle takeover locks the screen and the pool for 20 minutes: *Wudu, then pray*, then **Done** (it unlocks after 8 minutes, so it cannot be tapped through), then quiet time until the 20 minutes end. The prayer is complete when the lock has ended and you confirmed. If the lock ends unconfirmed, the phone is released but the prayer stays open (**CONFIRM** on home and in a silent notification) until the next prayer's adhan (estimated from yesterday; Isha: until the day ends). With 15 minutes left the takeover comes back; still unconfirmed when the window closes = missed. If a MY TIMES class is in progress at the adhan, only a silent card shows, and the takeover starts when the class ends. No ladder: NOT OKAY shortens the lock to the 8-minute minimum. LEAVE before those 8 minutes is a bail-out (the prayer stays open for CONFIRM); after them it costs nothing.

**Wind-down and the night lock (sleep).** Starts an hour before tonight's target bedtime. *Sleep shifting:* the target starts at MY TIMES *bedtime start* (00:30) and moves 20 minutes earlier each night until it reaches *bedtime goal* (23:00); after two missed nights in a row (wind-down left, the day ended without GOING TO SLEEP, or GOING TO SLEEP more than 30 minutes after the target) it holds for a night. A full-then-subtle takeover locks the screen and the pool: three lines on the day, tomorrow's top tasks and first block (up to four lines; they become tomorrow's to-dos on home), prep (10 min), hygiene (10 min), phone away, lights low (with a **WHITE NOISE** switch), and **GOING TO SLEEP**, which ends the day (if Isha is still unconfirmed it asks first). The **night lock** follows until you answer the wake alarm, two hours after it at the latest: a dark, silent, very dim screen with the time, the alarm time, tomorrow's first to-do, WHITE NOISE, CALL, APPS and LEAVE (the usual penalty), and the pool blocked. It shows when you use the phone and never lights the screen by itself; Fajr takes the screen with its own takeover and gives it back. NOT OKAY: short skips prep and hygiene; minimal is one line, tomorrow's first task, phone away and sleep; then GOING TO SLEEP alone.

**Wake alarm and morning routine.** VIGIL rings the wake alarm itself at your MY TIMES wake time for the day (04:00 to 11:00): a real alarm clock, full screen over the lock screen, a tone on the alarm stream (heard in silent mode and Do Not Disturb) rising over 30 s, with vibration. Hold **I'M UP** for 3 s to stop it; there is no snooze. Unanswered for 10 minutes, it stops and rings again 5 minutes later, three times at most; then the morning starts at your next unlock. QUIET and the kill switch do not silence it. Dismissing it ends the night lock and opens the morning (a pool lock: the screen stays free, and its notification and home show the step and **OPEN** the takeover): get up, sun in your eyes (5 min), bathroom and brush (5), skincare or shower (15), breakfast, chores (20), walk (15; left out on days with a class or a commute in MY TIMES, since the commute is the walk). Thursday is shorter; Friday has the Jummah shower. The pool stays blocked until every step is done. NOT OKAY: short drops skincare and cuts chores and the walk to 10 minutes; minimal is get up, bathroom, breakfast and sun; then 5 minutes in the sun. LEAVE costs the usual penalty.

**Jummah (Fridays).** Replaces the Dhuhr protocol on Fridays (Dhuhr's adhan starts nothing unless Jummah is missed). Its times come from MY TIMES: *jummah time* (14:40), *commute* (60 min) and your Friday classes. Thursday's wind-down puts *Jummah prep: clean clothes, bag packed* on Friday's to-dos. **Leave by** your first Friday class minus the commute minus 15 minutes (no class: jummah time − 5 − commute − 15): a full takeover with the screen locked: shoes on, bag, leave now (Maps is under APPS), walking on (it switches I'M WALKING on). **I'M AT UNI** from 15 minutes before that class (no class: jummah time − 20): a full takeover asks; NOT YET closes it and it asks again every 10 minutes until you confirm (also on its notification and home) or the lock starts; confirming switches walking off. A silent card 5 minutes before the last Friday class that ends by jummah time: *Class ends in 5 minutes. Go straight to the prayer place.* **The Jummah lock**, from jummah time − 5 for 45 minutes: Do Not Disturb (priority only: alarms and the callers your own Do Not Disturb settings allow still come), the pool blocked and a quiet, silent screen; **DONE** unlocks at jummah time + 10 and confirms Jummah. Do Not Disturb goes back to what you had however the lock ends. Nothing confirmed by jummah time + 50: missed, the Dhuhr prayer starts (window until Asr, unless Asr has already come) and the reset is offered. No step-down, always stage 1. LEAVE is the usual bail-out and Jummah goes on: the lock still comes and CONFIRM still counts. The leave-by takeover waits behind another protocol that holds the screen (it does not pause it as a prayer does), and the I'M AT UNI ask repeats at full intensity even during a class.

**Attention reset.** Right after the Dhuhr lock ends, confirmed or not (Fridays: after the Jummah lock); not after Dhuhr was left before its 8 minutes, nor after the Jummah lock was left. It waits while a MY TIMES class is in progress or another protocol holds the screen, and is skipped (not a miss) if it cannot start within 2 hours. A very plain full takeover with the screen and the pool locked: face a wall or a window, phone face-down, then **LOOK AT ONE SPOT**: only a countdown, and a soft chime at the end. VIGIL's own sound stops. It lasts 3 minutes on the first 2 days you complete it in full, then a minute more every 2 such days, up to 10. NOT OKAY: 3 minutes whatever the day; there is nothing shorter.

**Study block.** Each MY TIMES study row (FROM–TO, and WHAT to study), or **START STUDY** on home (what, and 50, 100 or 150 minutes). A subtle takeover locks the screen (APPS: PoliTO, WhatsApp, calls) and the pool, Brave included; what to study shows big. Go to your spot, notebook and pen out, open the book or laptop, open the one study material, then rounds that fill the block exactly: 50 minutes of work, a 10-minute break (the phone stays locked), again, the last round cut to fit, each with ROUND 2/3 or BREAK, a large countdown and a soft chime at its end. It ends with one line on what you did, which goes in the report. NOT OKAY: 25-minute rounds with 5-minute breaks for what is left of the block; then one 25-minute round.

**Meals (lunch, dinner).** At MY TIMES lunch and dinner (13:00 and 19:30 until you change them; breakfast is part of the morning). Subtle, with a pool lock: the screen stays free, and the notification (**POOL LOCKED**) shows the step and opens the takeover. Go to the kitchen, take out the ingredients, cook, eat at the table with the phone face-down, clean up the kitchen (the pool opens here), walk 10 minutes (the meal ends). VIGIL plans no dishes: simple meals written into the meal's `fallback` list in `protocols.json` show in the shorter versions. NOT OKAY: a simple meal at the table, then the cleanup (no walk); then eat something simple and clean up.

**Gym.** At each MY TIMES gym row (FROM and the WORKOUT minutes). Subtle, with a pool lock (the phone stays usable; the notification leads back). Change clothes, pack the bag, leave (the gym is about 5 minutes away), the workout, then back home and shower. The workout screen: the exercise pre-filled with last time's reps and weight, **REPS**, **KG** and **REST** steppers and a big **LOG SET**, then a rest countdown (90 s, or the rest you set for that exercise) that chimes; **+ ADD EXERCISE** takes any name; **FINISH WORKOUT** (tap twice) logs it, or it ends by itself an hour after the planned minutes. The default exercises are a simple full-body list in `protocols.json`. NOT OKAY: a 20-minute session; then a 10-minute walk.

**Walk and commute.** **I'M WALKING** on home, by itself at every MY TIMES commute row, and at Jummah's *walking on*. Only the pool is blocked: Maps, WhatsApp, calls and every other app stay usable. No prompts, no takeover: a silent notification **WALKING · POOL BLOCKED** with **STOP**. It ends when you switch it off (home, STOP, I'M AT UNI), after 10 minutes without steps, or with the day. Without a step counter (or its permission) a commute ends 15 minutes after its planned end, and any other walk after 3 hours. No step-down.

**Friends mode.** **I'M WITH FRIENDS** on home: the pool refuses with **YOU'RE WITH FRIENDS** and no wait, until you switch it off, for 4 hours at most, or until the day ends. Calls, Maps, WhatsApp and the PoliTO app stay usable. No takeover: a silent notification with STOP. No step-down.

**Calls.** Each MY TIMES call row (WHO, REMIND, BY, WRAP-UP) is its own: mom and dad are two. At REMIND a full card without a lock shows the name big, with **CALL** (opens the dialer; VIGIL stores no contacts) and **NOT NOW**. **DONE** unlocks after a call of at least 3 minutes made after the card appeared (any call the phone reports, to anyone, WhatsApp calls too); when that call ends the card comes back with DONE (also on its notification and home). Nothing done after 30 minutes: the card comes back once. Not done by BY (16:00 when empty): missed; a call still going on at that deadline moves it on by the call's length. A call that lasts the WRAP-UP minutes (20 when empty) gets a silent *time to wrap up*, once. Nothing is blocked. NOT OKAY: send a message instead (**MESSAGE** opens WhatsApp, or the SMS app, then DONE); nothing shorter.

**The 10-minute promise.** **LATER** on home or on the always-on notification: one line of what you will do, **5 / 10 / 15 / 30 MIN** and **START**. One at a time; home counts it down, with DONE. When it is due, a full takeover shows exactly your line, with **DONE**, **SNOOZE** (once: half the time, at least 2 minutes) and **ON IT** (the takeover closes). From then the pool is locked until DONE (home, its notification or the takeover); the screen stays free. LEAVE is the usual bail-out; a promise still open at the day end expires without a penalty. No step-down.

**Reset.** **RESET** on home (tap it twice), or offered after 2 missed or left protocols in a row (step-downs do not count) and after a missed Jummah: a red card on home and a notification with **START RESET** and **NOT NOW** (NOT NOW costs nothing; the next offer needs 2 more misses). A full takeover with the screen and the pool locked, about 15 minutes: stop (1 min, a glass of water), name it (one optional line on what derailed the day, **SKIP** to leave it out; it goes in the report), body (3 min: wash your face, wudu if a prayer is near), space (5 min: tidy the room), move (5 min), then **re-enter**: today's missed and waiting protocols are dropped (no catch-up) and the miss count starts again. An open prayer comes first; otherwise the next planned start within 60 minutes starts now; otherwise the screen says what comes next (*NEXT: ASR AT ~15:20*). Starting a reset costs nothing and never moves a stage; leaving early is the usual bail-out. NOT OKAY: stop, body and re-enter; then water, wash your face and re-enter; then a glass of water and re-enter.

**Wellness nudges (off since v2.1).** None fires by itself: the bundled file has `per_day` 0 and no movement lines; TEST can still show one. When they are on: up to four a day at times that feel random (fixed for the day), at least 90 minutes apart, from 90 minutes after your wake time until the wind-down: drink water, look at something far away for 20 seconds, stand up and stretch. After 90 minutes without steps while you use the phone: stand up and walk for a minute. Each is one big line and a countdown (45 s; the movement one 60 s), then it ends by itself. Only while you use the phone and nothing else is going on (no protocol, class, QUIET, night lock, call, friends or walking); otherwise it is skipped. NOT OKAY or LEAVE end one at once; a nudge is never a miss and never costs anything.

## The pool

TikTok, Facebook (and Lite), YouTube, Instagram, Pinterest, Brave, Reddit and every browser on the phone (any app that opens `https://` links; your home screen app is never closed) share one counter. **SETUP › POOL AND APPS** lists what VIGIL found.

- **90 minutes a day.** Use them at any time, in as many sessions as you like, except while something below blocks the pool. Home shows what is left (**POOL · 52 MIN LEFT TODAY**). Once they are used, the pool is blocked with **TODAY'S 90 MINUTES ARE USED** until the day ends.
- **A 1-minute wait** before each open, on a wait screen. Stay on it and the app opens at zero; leave it and the next wait is the same minute.
- **Sessions.** Switching between pool apps, or leaving the pool for less than 60 seconds, keeps the session going. Nothing follows a session: the next open only waits its minute.
- **Heavy use.** 30 minutes in one session closes the app (**ENOUGH FOR NOW**) for a 15-minute break.
- **Everything else that blocks:** a protocol's screen or pool lock, the walking and friends switches, the night lock and a bail-out penalty. A live session ends, and the app closes, the moment anything blocks. **A call does not open the pool:** it pauses a protocol's timers and lock, but whatever blocked the pool before the call still blocks it during the call (the app a call runs in, say a WhatsApp or Instagram call, is never closed, and nothing happens while the phone rings).

Every blocked screen says why and until when. The minutes reset when the day ends; running penalties and breaks keep their end times. While VIGIL is suspended (kill switch) the pool is open. All of these numbers live in `protocols.json` (`pool`).

## MY TIMES

Your week, set in the app; the rules stay in `protocols.json`. Wake time per weekday (the wake alarm, 04:00 to 11:00), bedtime start and goal (sleep shifting), and rows with weekday chips and **+ ADD**: **classes** (FROM–TO: the prayer class rule, the morning walk, Jummah's times), **meals** (AT: lunch and dinner start their protocol), **study** (FROM–TO, WHAT), **gym** (FROM, WORKOUT minutes), **calls** (WHO, REMIND, BY, WRAP-UP) and **commute** (LEAVE, WHERE, LENGTH: walking goes on by itself), plus the usual commute minutes and the Jummah time. A change **applies from tomorrow**; only the very first save applies at once.

## Home

At the top: the **VIGIL** title, the day (since when, and its latest end), the pool's status, flags (QUIET, SUSPENDED, IN A CALL, WALKING, WITH FRIENDS, PHONE SILENT (JUMMAH)), the prayers prayed and missed, the next start, tonight's bedtime and the next alarm. Then whatever needs you: a red **A PROTOCOL IS RUNNING · OPEN** (or **NIGHT LOCK**), the reset offer, **LATER** and **RESET**, **START STUDY**, the running promise, today's Jummah (**I'M AT UNI** or **CONFIRM**), a waiting attention reset, today's call cards, a running morning, meal or gym (**OPEN**), **TO-DOS** with tick boxes, **OPEN** prayers with **CONFIRM**, and **TODAY**: the day's timeline (prayers at their adhan, `~` when estimated) with each protocol's stage pips and when it last fired. Below:

| item | what it does |
|---|---|
| **QUIET** | See [QUIET](#quiet). |
| **I'M WALKING** | On or off (see the walk above); says since when and when it ends by itself. The first tap asks for the step counter. |
| **I'M WITH FRIENDS** | On or off (see friends mode above); says until when. |
| **GOING TO SLEEP** | Hold 2 s: the day ends now and the night lock starts (it asks about an unconfirmed Isha first). Before the day's own wake time it does nothing, and says so. |
| **AUDIO** | **WHITE NOISE**, a **METRONOME** (40 to 208 beats a minute, or **TAP** the beat) and **MY FILES** (**+ ADD** copies an audio file into VIGIL; **PLAY**, **RENAME**, **DELETE**, **LOOP**). One sound at a time; it keeps playing with the screen locked, with STOP in its notification. White noise stops after 8 hours; the wake alarm and the attention reset stop any sound. |
| **MY TIMES** | See [MY TIMES](#my-times). |
| **SETUP** | Permissions (with the count missing), restricted settings, battery, **POOL AND APPS**, **ADHAN**, **TAKEOVER** (switches that turn sound, vibration or the Glyph lights off for good) and **SAFETY**. |
| **TEST** | See [TEST](#test). |
| **EXPORT** | See [EXPORT](#export). |

## QUIET

A switch on home and on the always-on notification. While it is on, every takeover is silent (no sound, vibration or Glyph) whatever its intensity, chimes and VIGIL's notifications are silent, and nudges (when on) are skipped. Locks and the pool work as usual, and the wake alarm still rings. Nothing turns QUIET on by itself (a prayer during a class is a silent card anyway), and it stays on until you switch it off.

## The day

The day does not end at midnight. It ends at the first of:

- **GOING TO SLEEP** (the wind-down's last step, or held on home);
- 2 hours of screen off after wind-down started (the day then ends when the screen went off; only an unlock restarts the count);
- the wake alarm, if the day is still running then;
- 06:00 the next morning.

Anything after midnight belongs to the day before until it ends. A new day starts at once: the counters reset, a MY TIMES change applies, and what was left open is closed (missed, except a prayer still in its window; a promise expires; the walking and friends switches go off). The self-check at the day's end logs every planned start and every expected adhan that never came as `not_fired`.

## Stages

Every protocol starts fully forced and eases off as the habit sticks:

1. **Forced:** every step, timer and lock; a screen takeover comes back if you leave it.
2. **Guided:** a plain checklist shown once; step timers can be tapped through; it does not come back; the pool stays blocked while its lock runs.
3. **Reminder:** one notification and no lock. A prayer still needs CONFIRM within its window; a reminder not confirmed by the day's end is a miss.

Up one stage after 10 clean completions in a row (the full version, with no NOT OKAY, no LEAVE and no returning takeover); down one after 3 misses or bail-outs within its last 10 outcomes. The pips show each protocol's stage. Jummah, the reset and the promise stay at stage 1 (`"stages": false`); walks, friends mode and nudges never count.

## TEST

Everything started here is a **TEST preview**: stage 1, logged as a test, and left out of stages, penalties, prayer times and the report.

- **START NOW**: any protocol. One without a screen lock (the morning, a meal, the gym) opens its takeover at once; the promise row makes a 1-minute test promise.
- **JUMMAH · EACH PART NOW**: the leave-by takeover, I'M AT UNI, the class-end card, or the lock (it turns Do Not Disturb on; LEAVE ends a preview at no cost).
- **WALK · FRIENDS · CALL CARD · NUDGE**: the walk and friends show their notification for 2 minutes and block nothing (the home switches are the real thing); the call card is the real card (missed after an hour); a nudge shows now.
- **OPEN AUDIO** and **1-MINUTE PROMISE**.
- **FIRE IN 60 S (LOCK THE PHONE)**: a real exact alarm starts a preview of the next prayer, to try the whole path with the screen off. **CANCEL THE TEST ALARM** undoes it.
- **RING WAKE ALARM NOW**: the real alarm screen and sound; I'M UP starts a morning preview.
- **POOL CHECK**: what the pool would do right now for each pool app (it only asks).
- **LAST EVENTS**: the newest 30 journal entries.

**SETUP › ADHAN › FIRE TEST PREVIEW** starts the prayer the test text matches.

## EXPORT

Home › **EXPORT** sends two files through the share sheet: a progress report in markdown (totals, gym progress per exercise, then each day: prayers and Jummah, pool use, protocols, bail-outs, resets with what derailed the day, promises, study blocks with what you did, gym sets, walks, time with friends, calls, nudges, sleep and wake times, app exits, late alarms and stage changes) and the whole journal as CSV. Give them to Claude Code for the weekly review.

## Safety

- **Calls are never blocked.** Takeovers wait while a call or VoIP call is active or the phone is ringing; the Guard does nothing while the phone rings and never closes the phone, dialer, emergency apps or the app a call runs in. A call pauses the running protocol's lock and timers for its length; it never ends a protocol and costs nothing. It does not open the pool either: other pool apps stay blocked wherever they were blocked before the call.
- **CALL** is on every takeover from its first frame. One tap silences it, opens the dialer (the emergency dialer when the phone is locked) and keeps VIGIL out of the way for 10 minutes, even if Android restarts it.
- **Never over the lock screen.** A takeover you left comes back only on an unlocked phone, so the lock screen's emergency button always works. APPS, NOT OKAY and LEAVE are on every takeover.
- **Kill switch.** Tap the eye on a takeover, or the **VIGIL** title on home, 7 times in a row (each tap within 1.5 s of the last). A 5-minute wait screen follows; stay on it, because leaving it cancels. Then VIGIL is suspended for 1 hour: no takeovers, and the pool is open. **SETUP › SAFETY › RESUME NOW** ends it early.
- **adb escape.** From a computer; only adb and the system can send these, because the receiver is protected by `android.permission.DUMP`:
  ```sh
  adb shell am broadcast -a dev.vigil.action.SUSPEND -n dev.vigil/.engine.KillSwitchReceiver --ei minutes 60
  adb shell am broadcast -a dev.vigil.action.RESUME -n dev.vigil/.engine.KillSwitchReceiver
  ```
  SUSPEND is the kill switch without the wait: `minutes` defaults to 60 and is kept between 1 and one week. Use it rather than `am force-stop`: with notification access on, Android restarts VIGIL right after a force-stop.
- **Uninstalling works like any app.** VIGIL has no device admin and never covers Settings, force stop or the uninstall prompt. From a computer: `adb uninstall dev.vigil`.

## Reliability notes

- **Exact alarms.** VIGIL keeps one exact alarm at the next moment it must act (a start, a heads-up, the end of a lock or window, the day's end), at most 30 minutes ahead, delivered on the minute even in Doze. It is deliberately not an "alarm clock" alarm, which would show as your next alarm and end a bedtime Do Not Disturb schedule early; only the wake alarm is one.
- **Late is logged, not fired.** An alarm more than a minute late is journaled (`alarm_late`). A planned start, or the wake alarm, more than 30 minutes late (the phone was off) does not fire late; it is logged as `not_fired`.
- **Battery unrestricted**, and don't swipe VIGIL away in Recents: otherwise Android may put it to sleep.
- **The adhan listener is rebound.** Android sometimes disconnects a notification listener, which would silently stop the prayers. VIGIL asks Android to reconnect it and checks about once a minute. **SETUP › ADHAN** shows whether it is connected; if it stays disconnected, switch VIGIL's notification access off and on.
- **App exits are journaled.** On every start VIGIL records the exits Android saw since the last one (killed for memory, crashed, force-stopped ...) as `app_exit`, so the report shows when it was not running.
- **After a reboot** VIGIL starts once the phone has been unlocked for the first time.
- **Clock changes.** Times follow the local clock; the expected adhan (yesterday's + 24 hours) follows the sun across a clock change.

## Sounds and lights

Five WAV files in [`app/src/main/res/raw/`](app/src/main/res/raw/), played only while a takeover is at full intensity: `heartbeat.wav` (the lub-dub with the red slit), `swell.wav` and `tone.wav` (rising before the eye opens), `drone.wav` (a slow sub-bass once the eye is open) and `thud.wav` (each confirmed step). To swap one, replace the file with another of the same name. Full takeovers use the alarm stream; full-then-subtle ones follow the ringer like a notification. They are deterministic placeholders made by [`tools/assetgen/generate.py`](tools/assetgen/generate.py). The wake alarm's tone, the chime, the white noise and the metronome have no files: the app makes them as it plays them.

**Only CC0 audio may be committed to this repository.** The generated placeholders are dedicated to CC0, and any replacement must be CC0 too. Your own files added under AUDIO stay on the phone.

**Glyph lights (Nothing phones):** at full intensity the back lights pulse with the heartbeat for the first 30 seconds of a takeover. The Nothing Glyph SDK is downloaded at build time by [`tools/fetch-glyph-sdk.sh`](tools/fetch-glyph-sdk.sh), not committed (it has no open-source licence). On Android 16 the lights need no setup. On Android 15, enable Glyph debug mode from a computer (it lasts 48 hours):
```sh
adb shell settings put global nt_glyph_interface_debug_enable 1
```

## Changing the protocols

Every rule lives in [`core/src/main/resources/dev/vigil/core/protocols.json`](core/src/main/resources/dev/vigil/core/protocols.json), and [`spec/PROTOCOLS.md`](spec/PROTOCOLS.md) documents every field for Claude Code. Your times stay in MY TIMES.

1. Ask Claude Code for the change, e.g. "make the Asr lock 25 minutes". It edits `protocols.json` following `spec/PROTOCOLS.md` (and can read [`vision.md`](vision.md) for your reasons and tone).
2. It runs the core tests: `BundledProtocolsTest` parses the file and fails on any error, and so does CI, so a bad edit never ships.
3. Push to `main`. GitHub Actions publishes a new **VIGIL latest build**.
4. Install the new `vigil.apk` over the old one.

## Building

- **Core logic**, on any machine with a JDK (no Android SDK): `./gradlew -p core test`. Every decision lives in `core/` and is unit-tested there, including the bundled `protocols.json`.
- **The app** is built only on GitHub Actions: every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml) (core tests, the APK, lint, the APK attached as the `vigil-apk` artifact); on `main` it also publishes the **VIGIL latest build** pre-release.

```
app/       Android app (Kotlin, Jetpack Compose): screens, the takeover, the Guard, Android events wired to the core.
           No decisions.
core/      Plain Kotlin, no Android: protocols.json with its parser and validator, MY TIMES, the day, the timeline,
           the pool, runs and stages, the engine (pure functions over one persisted state), the journal and the report.
spec/      PROTOCOLS.md: every field of protocols.json, for Claude Code.
tools/     fetch-glyph-sdk.sh; assetgen/ (the placeholder eye sprite sheet and sounds).
vision.md  Your long-term picture (you write it); Claude Code reads it when it edits protocols.json.
```

The look: strictly black, white and red (red only for the eye and urgent states), uppercase monospace text, the VT323 pixel font only for the **VIGIL** title, hard cuts and no easing. The eye is an original design (`app/src/main/res/drawable-nodpi/eye_sheet.png`, made by `tools/assetgen/generate.py`).

## Credits

- [VT323](https://fonts.google.com/specimen/VT323) by Peter Hull, SIL Open Font License (`app/src/main/assets/licenses/OFL-VT323.txt`).
- Nothing [Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit), downloaded at build time, not redistributed here.

MIT licensed. See [LICENSE](LICENSE).
