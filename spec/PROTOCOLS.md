# protocols.json reference

`core/src/main/resources/dev/vigil/core/protocols.json` is VIGIL v2's only rule book. Every behaviour of the app is a
fixed rule from this file plus the user's MY TIMES; there is no planner, no import and no in-app editor. The file is
edited only with Claude Code and ships inside the app.

**Before you commit an edit, run `./gradlew -p core test --console=plain`.** `BundledProtocolsTest` parses the file
with `ProtocolParser`; any error fails the test (and CI), warnings are printed and allowed. The parser's messages say
where the problem is (`protocols[2] (prayer-asr).lock.minutes: must be from 1 to 600 (found 0).`) and what to do.

Conventions:

- Keys starting with `_` are comments anywhere (`"_note": "..."`). Unknown keys are warnings and are ignored.
- Times are `HH:MM`, two digits each, 00:00 to 23:59. Minutes are whole numbers.
- Ids (protocols, windows) are lowercase letters, digits, `-` and `_`, starting with a letter or digit, at most 48.
- Package names look like `com.example.app` (two or more dot-separated parts, the first starting with a letter).

## Top level

| Field | Required | Meaning |
|---|---|---|
| `_readme` | no | Free text for the editor. |
| `version` | yes | Must be `2`. |
| `pool` | yes | The blocked apps and the friction rules ([pool](#pool)). |
| `apps_button` | no | The whitelist behind the takeover's APPS button ([apps_button](#apps_button)). |
| `day` | no | Day boundary settings ([day](#day)). |
| `takeover` | no | Takeover timings ([takeover](#takeover)). |
| `stages` | no | Stage promotion and demotion ([stages](#stages)). |
| `protocols` | yes | The protocols ([protocols](#protocols)). |

## pool

The pool is one shared counter for all its apps. Outside a lock, the pool can only be opened inside a free window,
after a wait, and every session is followed by a cooldown.

| Field | Default | Range | Meaning |
|---|---|---|---|
| `apps` | (required) | package names | The pool apps (TikTok `com.zhiliaoapp.musically` and `com.ss.android.ugc.trill`, Facebook `com.facebook.katana` and `com.facebook.lite`, YouTube, Instagram, Pinterest, Brave ...). Listing one twice is a warning. |
| `all_browsers` | `true` | | Also every installed browser (apps that open `https://` links, detected on the phone). |
| `wait_step_min` | `1` | 1-60 | The Nth open of the day waits N x this many minutes ... |
| `wait_cap_min` | `10` | 1-120, at least `wait_step_min` | ... but never longer than this. |
| `cooldown_factor` | `2` | 0-10 (decimals allowed) | After a session the pool locks for this many times the minutes used ... |
| `cooldown_cap_min` | `60` | 0-480 | ... but never longer than this. |
| `session_gap_s` | `60` | 0-600 | Leaving the pool for less than this keeps the session going. |
| `windows` | (required) | | The free windows ([windows](#windows)). An empty list is a warning: the pool can never open. |
| `heavy_use` | | | `continuous_min` (10, 1-240): minutes in one session; `opens` (3, 2-20) opens within `within_min` (30, 1-240) minutes. |
| `bail_out` | | | `extra_lock_min` (120, 0-720): pool lock added per bail-out (stacking); `daily_cap_min` (360, 0-1440): most penalty minutes added per day. |

The rules, exactly as the engine applies them (design section 3):

1. **Blocked** (in this order, every reason that applies is reported with its end time): a screen or pool lock of an
   active run; the friends or walking switch; the night lock ([sleep](#sleep-wind-down-night-lock-sleep-shifting));
   outside every free window; the open window's
   cap used up; a cooldown; a bail-out penalty; a heavy-use cooldown. While VIGIL is suspended (kill switch)
   everything is open and nothing counts: a live session ends when the suspension starts (its minutes and cooldown
   count as usual), and a pool app still in front without a session when the suspension ends gets the wait or block
   screen at once.
2. Not blocked and **no session**: wait `min(wait_cap_min, wait_step_min x (opens today + 1))` minutes on the wait
   screen. Leaving the wait screen abandons the wait; the next wait is the same length. A wait that would end after
   every open window with minutes left has closed is refused at once (outside the window); a wait already running
   opens the pool if nothing blocks when it ends.
3. A completed wait starts a **session** (opens today + 1). Switching between pool apps keeps the session; leaving the
   pool for less than `session_gap_s` keeps it; otherwise it ends at the moment the pool was left. Its minutes count
   for the day and for the window it was opened in, and the cooldown is `min(cooldown_cap_min, cooldown_factor x minutes
   used)`, counted from the moment the pool was left.
4. **Heavy use**: a session reaching `continuous_min` minutes (short gaps do not reset it), or the `opens`th open within
   `within_min` minutes, ends the session at once and closes the app. Its cooldown starts now and is
   `min(cooldown_cap_min, cooldown_factor x the pool minutes used in the last within_min minutes)`, shown as HEAVY USE.
   After the `opens` case it lasts at least until enough of those opens have left the `within_min` span that the next
   open would not be heavy use again (otherwise every wait would end in another kick).
5. A live session also ends (and the app closes) as soon as anything blocks: its window closes or reaches its cap, a
   lock starts, a switch goes on, a penalty is added.
6. **Bail-out penalty**: quitting a lock or run early adds `extra_lock_min` minutes of pool lock, stacking on a running
   penalty, at most `daily_cap_min` minutes added per day.
7. When the day ends, a live session ends with it (its minutes count for the day that ends, its cooldown starts; still
   in the app, the app closes), then the counters (opens, minutes, window minutes, penalty minutes added) reset.
   Running cooldowns and penalties keep their end times.

### windows

Each window: `{"id": "morning", "opens": "<anchor>", "closes": "<anchor>", "cap_min": 30}` (all four required).

| Field | Meaning |
|---|---|
| `id` | Unique among windows. |
| `opens` | An anchor (below); `next` is not allowed here. |
| `closes` | An anchor, or `next`. |
| `cap_min` | 1-600: pool minutes usable inside this window per day. |

A window is open from `opens` (inclusive) to `closes` (exclusive). If an anchor does not exist that day (no dinner in
MY TIMES, no class), the window does not open that day. When windows overlap, a new session uses the first open window
in file order that still has minutes left; a session stays tied to the window it was opened in.

## Anchors and time expressions

A **time expression** is resolved against the day's MY TIMES:

| Expression | Resolves to |
|---|---|
| `wake` | MY TIMES wake time for the day's weekday (always the morning of the day). |
| `woke` | The moment the wake alarm was dismissed that day (or the phone was unlocked after it was never answered); MY TIMES `wake` until then. |
| `bedtime` | Tonight's target bedtime: sleep shifting's target when the file has a sleep protocol ([sleep](#sleep-wind-down-night-lock-sleep-shifting)), else MY TIMES `bedtime_goal`. |
| `meal:breakfast`, `meal:lunch`, `meal:dinner` | The start of that MY TIMES meal on the day's weekday. |
| `class_start` | The start of the day's first class. |
| `class_end` | The end of the day's last class. |
| `HH:MM` | That local time. |

Each may be followed by `+N` or `-N` minutes (0-1440, no leading zeros): `wake+60`, `bedtime-60`, `meal:dinner+45`.

**Night edge**: an `HH:MM` value (also MY TIMES values such as `bedtime_goal`) earlier than `day.night_edge` (05:00)
belongs to the night after the day: `00:30` is half past midnight at the end of the day, not its start. `wake` is the
exception (always the day's morning). Times are DST-safe: a time skipped by the spring-forward gap happens at the end of
the gap, and a time that happens twice when the clocks go back happens once, the first time.

An **anchor** is one of:

| Anchor | Meaning |
|---|---|
| `<expr>` | The time expression. |
| `done:<protocol-id>\|<expr>` | The moment that protocol's run completed today (after the day's wake time: the night before it does not count); else (or if that protocol is not in the file) the expression. |
| `start:<protocol-id>\|<expr>` | The moment that protocol's run first started today, after the day's wake time; else (or if absent) the expression. |
| `max(<anchor>,<anchor>)` | The later of the two anchors (no spaces; neither may be `next` or another `max`). If either does not exist that day, the window does not open. The morning window: `max(done:morning\|woke+60,woke+60)` = when the morning routine is done, but not before an hour after waking. |
| `next` | (`closes` only) The next timeline start after the window opened (a time or slot trigger, or a prayer's adhan or its estimate), else the day's latest end (`fallback_end`). |

A `done:`/`start:` reference to a protocol that is not in the file is a warning (useful while later milestones are
not written yet; also catches typos).

## apps_button

The whitelist the takeover's APPS button offers in every lock (emergency calls always work). Each entry has exactly one
of:

| Key | Meaning |
|---|---|
| `role` | `dialer` (default phone app), `adhan` (the adhan app chosen in SETUP), `clock` (alarms). |
| `package` | A package name, e.g. `com.google.android.apps.maps`, `com.whatsapp`. |
| `label_contains` | The first launcher app whose label contains this text, case-insensitive (e.g. `PoliTO`). |

## day

| Field | Default | Range | Meaning |
|---|---|---|---|
| `idle_end_min` | `120` | 10-720 | Once wind-down has started, this many minutes of screen off end the day, at the moment the screen went off (or wind-down started, if later). Only unlocking the phone starts the count again: a wake that shows just the lock screen (a notification, lifting the phone) pauses it while the screen is on. Wind-down = the start of the sleep protocol's run after the day's wake time (a start in the night before it belongs to that night), or `bedtime-60` while the file has no sleep protocol. |
| `fallback_end` | `06:00` | HH:MM | The day ends at this time the morning after at the latest. |
| `night_edge` | `05:00` | HH:MM | See [night edge](#anchors-and-time-expressions). |

A day ends at the first of: GOING TO SLEEP, idle (above), the wake alarm the morning after, `fallback_end` the morning
after. A new day starts at once; its key is the calendar date of (end + 6 hours), always later than the day that
ended. Everything after midnight belongs to the day before until the day ends. GOING TO SLEEP before the current day's
own wake time does nothing but a `skipped` entry (home says why): that day began last night (an earlier tap, idle,
the first start), and ending it again would skip a whole day.

A `time:` or `slot:` start early on the next calendar date (e.g. `time:05:30`, or `time:wake` with a 05:30 wake, before
the 06:00 `fallback_end`) belongs to whichever day is running at that moment, so it fires once whether or not GOING TO
SLEEP came first.

## takeover

| Field | Default | Range | Meaning |
|---|---|---|---|
| `full_seconds` | `30` | 0-600 | `full_then_subtle` takeovers are full this long, then subtle. |
| `glyph_off_after_s` | `30` | 0-600 | The Glyph lights turn off after this long. |

## stages

| Field | Default | Range | Meaning |
|---|---|---|---|
| `promote_after_clean` | `10` | 1-100 | Clean completions in a row to move up one stage. |
| `demote_after_misses` | `3` | 1-`window` | Misses or bail-outs within the last `window` outcomes to move down one stage. |
| `window` | `10` | 1-100 | How many recent outcomes count. |

Stage 1 **forced**: all steps, step timers and locks; the screen lock comes back if left. Stage 2 **guided**: the
checklist shows once, no step min-timers (check `min`, timers and the done lock can be tapped through), no screen
re-assert, the pool is still blocked while its lock runs. Stage 3 **reminder**: one notification, no lock (a prayer
still needs CONFIRM within its window); a reminder not confirmed by the day end is a miss, so an ignored protocol moves
back a stage. A stage change starts the count again. Clean = completed in the full version with no step-down, no NOT
OKAY, no bail-out, no early release and no returning takeover. Step-downs are logged, cost nothing and are not misses,
but they are not clean. A protocol with `"stages": false` stays at stage 1.

## protocols

| Field | Required | Default | Meaning |
|---|---|---|---|
| `id` | yes | | Unique protocol id. |
| `kind` | yes | | One of the [kinds](#kinds): behaviour lives in code, numbers in `params`. |
| `title` | yes | | Shown big on the takeover (uppercase, at most 30 characters or it is a warning). |
| `trigger` | yes | | What starts it ([triggers](#triggers)). |
| `intensity` | no | `full_then_subtle` | `full` (dark screen, vibration, pixel eye, sound, Glyph), `subtle` (quiet screen, no sound, no Glyph), `full_then_subtle`. QUIET mode makes every takeover silent. |
| `lock` | no | `{"type": "none"}` | [Lock](#lock). |
| `steps` | yes | | The full version: a non-empty list of [steps](#steps). |
| `short`, `minimal` | no | | Step-down versions (non-empty lists), only where the kind allows a ladder. |
| `single` | no | | The last rung: a list of exactly one step, shown marked "minimum". |
| `weekdays` | no | | `{"thu": {"steps": [...], "short": [...]}}`: per-weekday replacements (`mon`..`sun`); a version missing there falls back to the protocol's own. |
| `params` | no | | Kind-specific numbers ([kinds](#kinds)). Unknown params are warnings. |
| `stages` | no | `true` | `false` pins the protocol at stage 1. |

### lock

| Field | Meaning |
|---|---|
| `type` | `screen`: the takeover stays up (and comes back at stage 1), only the whitelist is usable, the pool is blocked. `pool`: only the pool is blocked. `none`. |
| `minutes` | 1-600: the lock ends this long after the run starts. Without it, the lock lasts until the steps are done. |
| `done_after_min` | 0-`minutes`: the `done` step stays locked this long after the run starts (stage 1). Prayers: also the NOT OKAY minimum. |

Calls pause locks and timers: while a call is connected nothing ends, and when it ends every running lock, timer and
done lock is extended by the call's length. A call never ends a protocol. A call that reports no end within 3 hours
stops pausing then (as if it had ended), so a lost report cannot freeze the locks. Only one run owns the screen at a
time; another screen run waits (queued, its timers not started) and starts when the screen is free; prayers go first.
A prayer never waits behind another protocol: when its adhan comes (or its class ends, or its takeover returns) a
non-prayer run holding the screen pauses (`paused`: its lock, timers and done lock frozen, like a call) and goes on
where it was (`resumed`) as soon as the screen is free again, before any queued run.

### steps

`{"text": "Wudu, then pray.", "type": "check"}`

| `type` | Finished when | Extra fields |
|---|---|---|
| `check` (default) | Tapped. With `min`, locked for `min` minutes (1-240) from when it appeared (stage 1). | `min` |
| `timer` | It ends by itself after `min` minutes (1-240), or when the lock ends if it has no `min` (then the lock needs `minutes`; in an attention protocol it lasts the ramp length instead). Cannot be tapped through at stage 1. | `min`, `chime` |
| `text` | The user typed `lines` non-empty lines (1-10, default 1). The text goes into the run and the journal. With `todos` (sleep protocols): up to `lines` lines (at least one), and they become the next day's to-dos. | `lines`, `todos` |
| `done` | Tapped once the lock's `done_after_min` has passed (stage 1). It confirms the run (a prayer: confirmed). At most one per version. If it is the last step the run completes and its lock ends ("done unlock"); otherwise the remaining steps follow (e.g. a quiet timer until the lock ends). | |
| `rounds` | Study protocols only, at most one per version: work rounds of `min` minutes (required, 1-240) with breaks of `break_min` minutes (0-60, default 0) between them, filling the study block's length ([study](#study-block)). The run shows one timer per round and per break (ROUND 2/3); each ends by itself. | `min`, `break_min`, `break_text`, `chime` |
| `workout` | Gym protocols only, at most one per version: the sets are logged here and FINISH WORKOUT ends it ([gym](#gym)). `min` = the planned minutes (1-240); without it, the MY TIMES gym row's minutes (or 60). | `min` |

Optional on any step: `skip_on_class_days` (`true`): the step is left out on days with a MY TIMES class or commute
(the morning walk: the commute counts as the walk); a version may not consist only of such steps. `noise_toggle`
(`true`): the takeover offers the WHITE NOISE switch on this step (the wind-down's lights-low step). `todos` (`true`, a
text step of a sleep protocol): see `text` above. `optional` (`true`, a text step): it may be left empty (SKIP); the
reset's "name it". `walking_on` (`true`): finishing the step switches the walking switch on (Jummah's leave-by).
`arrival` (`true`, jummah protocols only): the I'M AT UNI step ([jummah](#jummah-friday)). `reenter` (`true`, reset
protocols only, the last step of its version): finishing it re-enters the day ([reset](#reset-and-its-offer)).
`break_text` (a rounds step): what each break says (default "Break: stand up, drink water, look out a window. The phone
stays locked."). `chime` (`true`, a timer or rounds step): a soft chime when it ends (each round and break), unless QUIET
is on. `unlock` (`true`): finishing the step ends the run's lock, logged as `lock_ended` with reason `unlock` (a meal's
cleanup: the pool opens); the steps after it go on without a lock.

A run completes when its last step is done. If its timed lock ends first, the screen and the pool are released and the
run stays open (home shows it; a prayer shows CONFIRM). Without a screen lock (pool or none) its notification shows the
current step and opens its takeover.

### Step-down ladder ("I'M NOT OKAY")

1st tap = `short`, 2nd = `minimal`, 3rd = `single` (marked "minimum"); a version that is not defined (for that
weekday) is skipped. Each tap restarts at the first step of the new version; the lock is unchanged. Logged as
`stepped_down`; not a bail-out, costs nothing, not a miss, not clean. Kinds without a ladder do what their kind says
(prayer: shorten the lock to `done_after_min`; the others: nothing).

### triggers

| Trigger | Starts the protocol |
|---|---|
| `event:adhan:<prayer>` | When the adhan app posts its notification for `fajr`, `dhuhr`, `asr`, `maghrib` or `isha` (a repeat within 30 minutes, or while that prayer's run is still open and less than 12 hours old, is ignored; a run of it 12 hours old or more is closed like any earlier prayer's). Prayer kind only, one protocol per prayer. |
| `event:wake_alarm` | When VIGIL's wake alarm is dismissed (or, if it was never answered, at the first unlock after it). See [morning](#morning-wake-alarm-morning-routine). |
| `event:after:<protocol-id>` | Right after that protocol's run completes or its lock ends (not after a bail-out or a miss). The id must exist and not be the protocol itself. On Fridays Jummah replaces Dhuhr: what waits for the Dhuhr protocol follows the Jummah run (its DONE, or its lock ending). An attention protocol does not start at once ([attention reset](#attention-reset)). |
| `event:misses:<n>` | After `n` (1-10) missed or bailed protocols in a row (any protocols, prayers and Jummah too; step-downs are neither; a completion resets the count): the protocol is **offered**, not started: a full-intensity card (home and a notification) with START and NOT NOW. NOT NOW costs nothing; the count starts again, so the next offer needs `n` more. The offer ends when answered, when the protocol starts any other way, when the day ends or VIGIL is suspended. |
| `slot:study`, `slot:meal`, `slot:gym`, `slot:call`, `slot:commute` | At the start of every MY TIMES row of that list on the day's weekday (the run carries the row's label, end and minutes). A meal protocol never starts at breakfast while the file has a morning protocol (the morning's breakfast step covers it). A call protocol must use `slot:call` ([call](#call)); the walk protocol starts at the commute rows by itself ([walk](#walk-and-commute)). |
| `slot:meal:<label>` | Like `slot:meal`, only the rows labelled `breakfast`, `lunch` or `dinner` (one meal protocol per meal: `slot:meal:lunch`). |
| `slot:jummah` | Fridays: the Jummah sequence, from its leave-by time ([jummah](#jummah-friday)). Jummah kind only, and a jummah protocol must use it. It still starts late (the phone was off at leave-by) at whatever part is due, until its missed time; skipped while VIGIL is suspended, it starts when the suspension ends. |
| `time:<expr>` | At the time the [expression](#anchors-and-time-expressions) resolves to each day (skipped on days its anchor does not exist). |
| `toggle:walking`, `toggle:friends` | When that home switch goes on; the run completes when it goes off. Walk protocols must use `toggle:walking` (it also goes on by itself at every MY TIMES commute row), friends protocols `toggle:friends`. The friends switch goes off by itself after the friends protocol's `cap_min` (240 without one); the walking switch when the steps stop ([walk](#walk-and-commute)). Both go off when the day ends. |
| `manual` | Only from a button (home or TEST). Any protocol can also be started from TEST as a preview that counts for nothing. Nudge protocols must use it: they come at their own times ([nudges](#wellness-nudges)). |

Nothing starts while a day ends: an `event:misses` or `event:after` trigger set off by the runs the day end closes (a
reset offer at GOING TO SLEEP) is logged as `skipped` with reason `day_end`, and the miss count starts again.

`time:` and `slot:` starts (and prayers, at their adhan or the estimate from the last adhan + 24 h) make up the day's
**timeline** on the home screen, with when each protocol last fired. A planned start more than 30 minutes late (phone
off) does not fire late; the daily self-check at the day's end logs every planned start that never fired, and every
expected adhan that never came, as `not_fired`.

### kinds

Every kind below has its behaviour in code and its numbers in `params`. Params show `name (default, range)`.

| Kind | Ladder | Params | Notes |
|---|---|---|---|
| `prayer` | none | `heads_up_min` (10, 0-60), `window_warn_min` (15, 0-120) | M1, see [prayers](#prayers). Trigger must be `event:adhan:<prayer>`; `lock.minutes` required; `done_after_min` recommended. |
| `sleep` | short, minimal, single | `shift_min` (20, 0-120), `hold_after_missed` (2, 0-7), `late_min` (30, 0-240), `night_lock_grace_min` (120, 10-600), `white_noise` (false) | M2, see [sleep](#sleep-wind-down-night-lock-sleep-shifting). Its run's start is wind-down. |
| `morning` | short, minimal, single | `ring_max_min` (10, 1-60), `snooze_min` (5, 1-60), `max_rings` (3, 1-10) | M3, see [morning](#morning-wake-alarm-morning-routine). |
| `jummah` | none | `leave_buffer_min` (15), `arrive_before_min` (15), `arrive_repeat_min` (10, 1-60), `card_before_end_min` (5), `lock_before_min` (5), `missed_after_min` (50), `prep_todos` (list of texts) | M4, see [jummah](#jummah-friday). Trigger must be `slot:jummah`; `lock.minutes` required. Always stage 1. |
| `study` | short, minimal, single | `default_min` (100, 5-600) | M6, see [study](#study-block). Its `rounds` step fills the block. |
| `attention` | short only | `offset_min` (0, 0-240), `start_min` (3, 1-30), `max_min` (10, 1-60, at least `start_min`), `ramp_every_days` (2, 1-30) | M6, see [attention reset](#attention-reset). A timer without `min` lasts the ramp length. |
| `meal` | short, minimal, single | `fallback` (list of texts, empty by default) | M7, see [meals](#meals). |
| `gym` | short, minimal, single | `rest_s` (90, 10-600), `exercises` (list of texts) | M7, see [gym](#gym). Its `workout` step logs the sets. |
| `walk` | none | `still_end_min` (10, 1-120), `commute_grace_min` (15, 0-120), `manual_max_min` (180, 10-720) | M8, see [walk](#walk-and-commute). Trigger `toggle:walking`, lock `pool`. Always stage 1; its outcomes count for nothing. |
| `friends` | none | `cap_min` (240, 10-720) | M8, see [friends](#friends-mode). Trigger `toggle:friends`, lock `pool`. Always stage 1; its outcomes count for nothing. |
| `call` | short only | `min_call_min` (3, 1-60), `return_after_min` (30, 1-240), `wrap_up_min` (20, 1-120) | M8, see [call](#call). Trigger `slot:call`, lock `none`, a done step in every version. |
| `nudge` | none | `nudges` (list of texts), `move_nudges` (list of texts), `seconds` (45, 10-120), `move_seconds` (60, 10-180), `per_day` (4, 0-12), `min_gap_min` (90, 15-600), `still_min` (90, 15-600), `after_wake_min` (90, 0-600) | M8, see [nudges](#wellness-nudges). Trigger `manual`, lock `screen`. Never a miss, never counted. |
| `promise` | none | `delays` (list of whole numbers 1-240), `snooze_min_min` (2, 1-60) | M5, see [promise](#the-10-minute-promise). Always stage 1. |
| `reset` | short, minimal, single | `next_within_min` (60, 0-240) | M5, see [reset](#reset-and-its-offer). |

Unlisted ranges for jummah are 0-120 (`card_before_end_min`, `lock_before_min`: 0-60; `missed_after_min`: 0-240). The
walk, friends, call, nudge, sleep and jummah kinds may each appear once in the file (the engine runs one of each).

Open runs (unconfirmed stage-3 reminders too) are missed when the day ends, except prayers: a prayer's window follows
the adhans (only Isha's window closes with the day, so a Fajr that came before the 06:00 fallback stays open into the
new day). A wind-down still running when the day ends without GOING TO SLEEP (idle, fallback) is missed too; the night
lock that follows belongs to no day. The walking and friends switches go off with the day (their runs complete), a nudge
just ends, and a call card still open is missed.

A run does not hold its protocol once its lock has ended unfinished (or as an unconfirmed reminder): the protocol's
next start (lunch after breakfast, the second study block, tonight's wind-down) replaces it, logged as `missed` with
reason `replaced`. A run still in its lock does hold it (the new start is `skipped`, `already_running`).

### prayers

All five prayers share one protocol shape (`kind: prayer`, trigger `event:adhan:<prayer>`), as bundled:

1. **Heads-up**: a subtle (silent) notification `heads_up_min` before the expected adhan = yesterday's adhan + 24 h
   (which follows the sun across a clock change). Not at stage 3.
2. **Adhan**: the takeover starts (`full_then_subtle`), lock `minutes` (20) on the screen and the pool.
3. **Steps**: "Wudu, then pray." (check), "Done." (done, unlocks after `done_after_min` = 8), the quiet timer until
   the lock ends. The run completes when the lock has ended and the prayer is confirmed.
4. **Lock ends unconfirmed**: the screen and the pool are released, the prayer stays open; home and a silent
   notification show CONFIRM.
5. **Window**: until the next prayer's adhan, estimated from its adhan yesterday (Isha: until the day ends). Without an
   estimate the window closes at the next adhan, or 12 hours later at most, without a warning.
6. **Warning**: `window_warn_min` (15) before the window closes, still unconfirmed, the takeover comes back (stage 3:
   a notification) until it is confirmed or the window closes.
7. **Missed**: the window closing, or the next adhan arriving, unconfirmed = missed (counts for stages and for
   `event:misses`). The next adhan closes the earlier prayers' windows only (Fajr closes last night's); a prayer
   started by hand after a later one came does not close that later one.
8. **Class rule**: when a MY TIMES class is in progress at the adhan, only a silent card shows; when the class (and any
   class straight after it) ends and the prayer is still unconfirmed in an open window, the takeover starts. Confirming
   on the card ends it. A warning during a class returns silently. At stage 3 the one notification (and its warning)
   is silent during a class.
9. **NOT OKAY** shortens the lock to the `done_after_min` minimum. Leaving (10 s hold) before that minimum is a
   bail-out (penalty; the prayer stays open for CONFIRM); leaving after it is a release (no penalty, not clean).

### sleep (wind-down, night lock, sleep shifting)

The protocol of kind `sleep` (bundled: `sleep`, trigger `time:bedtime-60`, screen lock until its steps are done):

1. **Sleep shifting**: tonight's target bedtime (`bedtime`) starts at MY TIMES `bedtime_start` and moves `shift_min`
   earlier each night until it reaches `bedtime_goal`; it is never later than `bedtime_start` (a start that is not
   later than the goal keeps the target at the start). A night is **missed** when the wind-down was left early (LEAVE),
   when the day ended without GOING TO SLEEP (idle, fallback, the wake alarm), or when GOING TO SLEEP came more than
   `late_min` after the target. After `hold_after_missed` missed nights in a row (0 = never) the target holds for the
   next night; any good night resets the count and shifting goes on. A MY TIMES change applies from the next day, and
   the target is kept inside the new range. Journal: `sleep_target` at each day start, `night_missed`, `sleep` (the
   GOING TO SLEEP moment or, for an idle end, when the phone was put down).
2. **Wind-down** steps: close the day (text), tomorrow (text with `todos`: the next day's to-dos, shown on home and on
   the morning takeover; each can be ticked off; unticked ones expire with their day), prep, hygiene, phone away,
   lights low (`noise_toggle`), and the last step, GOING TO SLEEP. Completing the last step is GOING TO SLEEP: the
   wind-down completes, the day ends and the night lock starts. A GOING TO SLEEP tap on home while the wind-down runs
   completes it the same way (not clean when steps were left); before wind-down it just ends the day. With Isha still
   open and unconfirmed, the app asks CONFIRM ISHA first; a yes counts (even inside its done lock).
3. **Night lock**: from GOING TO SLEEP (or an idle or fallback day end once wind-down had started) until the wake alarm
   is answered: the sleep protocol's lock as a run in phase `night` (screen: a silent, dark takeover with CALL, APPS and
   LEAVE; the pool blocked as `night_lock`). It belongs to no day's counters. A prayer at night (Fajr) takes the screen
   with its own takeover, and the night lock comes back after it. It ends when the wake alarm is dismissed, at the first
   unlock after an alarm that was never answered, or at wake + `night_lock_grace_min` at the latest; LEAVE ends it with
   the usual penalty. Not at stage 3, not while suspended, not with lock `none`, and only when the wake alarm is due
   within 16 hours (GOING TO SLEEP just after waking starts no night lock). With `white_noise: true` white noise starts
   with the night lock; it stops when the night ends.

### morning (wake alarm, morning routine)

1. **Wake alarm**: VIGIL rings it (full screen, on the alarm stream) at MY TIMES `wake` of the day it belongs to (the
   day that began with GOING TO SLEEP, else tomorrow) when the file has a sleep protocol or an `event:wake_alarm`
   protocol. Its numbers come from the morning protocol: unanswered after `ring_max_min` it stops and rings again
   `snooze_min` later, at most `max_rings` times; then it gives up (`wake_alarm_unanswered`) and the morning starts at
   the next unlock. More than 30 minutes late (the phone was off) it does not ring (`not_fired`; the day-end self-check
   also logs one that fell inside a day that ended before it could ring). Dismissing it (a 3 s hold) ends the day if it
   has not ended, ends the night lock, journals `wake` (minutes after the alarm) and starts the `event:wake_alarm`
   protocols. A dismissal while no alarm rings or waits to ring again (a stale alarm screen) changes nothing (`skipped`,
   reason `wake_alarm`). It rings whatever QUIET or the kill switch say: it is an alarm clock, not a takeover.
2. **Morning routine** (bundled `morning`, pool lock: the screen is free, the morning notification shows the current
   step and opens the takeover, which also shows the first to-do): get up, sun 5 min, bathroom and brush 5, skincare or
   shower 15, breakfast, chores 20, walk 15 (`skip_on_class_days`); weekday versions for Thursday (shorter) and Friday
   (a shower for Jummah). The pool stays blocked while it runs; the morning window opens at
   `max(done:morning|woke+60,woke+60)`. Left early (LEAVE): the usual penalty, and the window opens an hour after waking
   (the penalty still applies).

### jummah (Friday)

The protocol of kind `jummah` (bundled: `jummah`, trigger `slot:jummah`, intensity `full`, `stages: false`) replaces
the Dhuhr protocol on Fridays: a Friday Dhuhr adhan is journaled (and closes Fajr's window) but starts no Dhuhr run and
no heads-up (`skipped`, reason `jummah`), unless Jummah was missed. The class rule for prayers does not apply to it. All
times come from MY TIMES (`jummah_time`, `commute_min`, the Friday classes) and the params. The **target** is the first
Friday class that starts before the lock, else the lock itself.

1. **Thursday**: when the wind-down starts on the night before a Friday, `prep_todos` go onto Friday's to-dos (kept when
   the wind-down's own to-dos are written).
2. **Friday morning**: the morning protocol's `fri` version has the shower step.
3. **Leave-by** at target - `commute_min` - `leave_buffer_min` (13:00 class, 60 min: 11:45): a full takeover, screen
   lock, with the steps before the `arrival` step (shoes on, bag, leave, walking on: `walking_on` switches the walking
   switch on). Maps is in the APPS button. The takeover ends when those steps are done, or at the arrival time.
4. **Arrival** from target - `arrive_before_min` (12:45): a full takeover asks I'M AT UNI (the `arrival` step; it can
   also be confirmed early, or with the button on its notification). It does not lock the screen: NOT YET (or leaving
   it) closes it, and it asks again every `arrive_repeat_min` until confirmed or the lock starts. I'M AT UNI switches
   the walking switch off.
5. **Card**: `card_before_end_min` before the last Friday class that ends by `jummah_time` (14:25), a silent
   notification: "Class ends in 5 minutes. Go straight to the prayer place." (not when late or after the lock began).
6. **Lock** from `jummah_time` - `lock_before_min` for `lock.minutes` (14:35-15:20): Do Not Disturb on, the pool
   blocked, a subtle silent screen with the steps after the `arrival` step. No protocol pauses it; it waits behind a
   prayer's lock, and any other protocol holding the screen pauses for it. Do Not Disturb goes off again whatever ends
   the lock: DONE, its end, LEAVE, the kill switch, the day end.
7. **Done**: the done step unlocks `lock.done_after_min` after the lock's start (14:50); DONE confirms Jummah
   (`confirmed`, prayer `jummah`) and ends the run. The lock ending unconfirmed releases the screen and the phone;
   CONFIRM (home, notification) still counts until the missed time.
8. **Missed**: nothing confirmed by `jummah_time` + `missed_after_min` (15:30): `missed`, today's Dhuhr run starts with
   its window until Asr (not once Asr's adhan has come) and the reset is offered.

No step-down ("I'M NOT OKAY" changes nothing). LEAVE on the leave-by takeover or the lock is a bail-out (the usual
penalty, counted once); the run goes on (the lock still comes, CONFIRM still counts; what follows the lock, the
attention reset, still follows it unless the lock itself was left). LEAVE on the I'M AT UNI ask
closes it like NOT YET. The kill switch releases the screen and the phone; afterwards it goes on where the clock is.
Calls extend the lock like any lock. A validation error: no `arrival` step, more than one, or no done step after it.

### reset (and its offer)

The protocol of kind `reset` (bundled: `reset`, trigger `event:misses:2`, intensity `full`, screen lock, `stages:
false`) starts from home's RESET button, or from START on its offer (after 2 missed or bailed protocols in a row, and
after a missed Jummah). Starting it costs nothing and moves no protocol back a stage (pinned at stage 1, its own
outcome changes no stage); leaving it early is a normal bail-out. Steps: stop 1 min (timer), name it (an `optional`
text line: it goes into the report), body 3, space 5, move 5, re-enter. Short = stop, body, re-enter; minimal = water,
wash your face, re-enter; single = water, then re-enter.

**Re-enter** (the `reenter` step): today's waiting and missed runs are dropped (`dropped`, reason `reset`: queued runs,
runs left open after their lock, unconfirmed reminders; never prayers, Jummah or a promise) and the miss count starts
again. Then: an open, unconfirmed prayer comes first (its takeover starts as soon as the reset is over); else the next
planned start (time or slot) starts now when it is due within `next_within_min`; else the step says "NEXT: <TITLE> AT
<HH:MM>" (a prayer's adhan is never started early). The step shows that line before it is tapped; the journal's
`reentry` entry has it, with the named reason.

### the 10-minute promise

The protocol of kind `promise` (bundled: `promise`, trigger `manual`, `full`, pool lock, `stages: false`) starts only
from LATER (home, or VIGIL's always-on notification): one line and one of `delays` minutes. One promise at a time. The
timer runs in the background (`waiting`). When it is due: a full takeover with exactly the line (DONE, SNOOZE, ON IT),
and the pool is locked until DONE; the screen is not locked (the task may be on the phone), and the takeover is never
brought back once left. SNOOZE is offered once: half the delay, rounded up, at least `snooze_min_min`; the takeover and
the pool lock wait with it. ON IT closes the takeover; the pool lock stays, and its notification has DONE. DONE (also
before it is due) = kept. LEAVE = bailed, with the usual penalty. At the day end it expires
(`expired_at_day_end`, no penalty, no miss). No step-down. Journal: `promise` entries (promised, due, snoozed, on_it,
kept, bailed, expired_at_day_end).

### study block

The protocol of kind `study` (bundled: `study`, trigger `slot:study`, intensity `subtle`, screen lock: only the APPS
button's apps (PoliTO, WhatsApp, calls) are usable, the pool (Brave too) is blocked):

1. **Block length**: a MY TIMES study row's `minutes`, else its end - start (at most 600); home's START STUDY: 50, 100
   or 150 minutes (and `default_min`), with what to study typed in; TEST and other starts: `default_min`. The row's
   label (or what was typed) is what to study: the takeover shows it big.
2. **Steps**: go to your spot, notebook and pen out, open the book or laptop, open the one study material, the rounds,
   then close: one line on what you did (a text step).
3. **Rounds**: the `rounds` step (bundled: 50 minutes of work, 10 of break, `chime`) becomes work rounds and breaks that
   fill the block exactly: 100 min = 50, break 10, 40; 150 = 50, 10, 50, 10, 30. The last round is cut to fit; when less
   than `break_min` + 5 minutes would be left after a round, that round takes them instead (no break at the end, no tiny
   last round). Rounds and breaks are timers: at stage 1 they cannot be tapped through; calls and a prayer taking the
   screen pause them.
4. **Ends** when the rounds are over and the closing line is saved: `study` in the journal (detail = what, minutes =
   the work minutes of the version it finished in, text = the line).
5. **Step-down**: short = 25-minute rounds with 5-minute breaks, then the closing line; minimal = one 25-minute round.
   A step-down keeps only what is left of the block: the shorter rounds fill the minutes not yet used, not the whole
   block again. No single version (a third tap changes nothing).

QUIET is only the manual switch: nothing turns it on by itself.

### attention reset

The protocol of kind `attention` (bundled: `attention`, trigger `event:after:prayer-dhuhr`, intensity `full`, screen
lock):

1. **When**: right after the Dhuhr lock ends, confirmed or not (also when the lock is left after its minimum, and at
   stage 3 when the reminder is confirmed; Fridays: after the Jummah lock, at DONE or its end), it is triggered and
   waits (`deferred`): it starts `offset_min` later, once no MY TIMES class is in progress and no other protocol holds
   the screen. If it cannot start within 2 hours of that time, it is skipped (`skipped`, reason `too_late`): not a
   miss. Not after Dhuhr was left before its minimum (a bail-out), nor after the Jummah lock was left (leaving only
   Jummah's leave-by takeover does not stop it). Once per run: a Dhuhr whose lock comes again at a reset's re-entry, or
   the Dhuhr a missed Jummah brings after the Jummah lock had ended, brings no second one. Any attention protocol started
   by its trigger waits like this; a manual or TEST start, the reset's offer and its re-entry start at once. A wait
   still open when the day ends is `skipped` (reason `day_end`).
2. **Steps**: face a wall or a window, phone face-down, then look at one spot: a timer that ends with a soft chime
   (`chime`; QUIET silences it). VIGIL's own sounds (white noise) stop when it starts.
3. **Ramp**: a timer without `min` lasts `start_min` on the first `ramp_every_days` days the reset was completed in
   full, then one minute more every `ramp_every_days` such days, up to `max_min` (bundled: 3 minutes on days 1 and 2, 4
   on days 3 and 4 ... 10). It counts completed days, not calendar days (once per day, never for a step-down or a
   TEST), and is kept across days. The `fired` entry has the minutes.
4. **Step-down**: short = 3 minutes whatever the ramp says. No minimal version: a second NOT OKAY changes nothing.

### meals

Protocols of kind `meal`, one per meal (bundled: `lunch` with `slot:meal:lunch`, `dinner` with `slot:meal:dinner`; no
breakfast run, the morning routine has its breakfast step), intensity `subtle`, pool lock:

1. **Steps**: go to the kitchen, take out the ingredients, cook, eat at the table (phone face-down), clean up the
   kitchen (`unlock`: the pool lock ends here), walk 10 minutes (a timer). Without a screen lock the meal is a
   notification (POOL LOCKED until the cleanup) that opens its takeover.
2. **Ends** when the walk is over. Dinner's id is `dinner`, so the evening window's `done:dinner` opens when dinner is
   done.
3. **No dishes**: the app does not plan meals. `fallback` (a list of simple meals, empty by default) is shown under the
   steps of the short and minimal versions when it has something.
4. **Step-down**: short = a simple meal, eaten at the table, then the cleanup (no walk); minimal = eat something simple
   and clean up.

### gym

The protocol of kind `gym` (bundled: `gym`, trigger `slot:gym`, intensity `subtle`, pool lock: the phone stays usable,
and the run's notification leads back to the workout):

1. **Planned minutes**: the MY TIMES gym row's minutes (or its end - start); the `workout` step's own `min` wins.
2. **Steps**: change clothes and shoes on, pack the bag, leave (the gym is about 5 minutes away), the workout, back home
   and shower.
3. **Workout**: the list is the protocol's `exercises`, then every other name logged before (any name can be added in
   the app; a name the gym knows in another case is that one). Each exercise is pre-filled with its last logged reps and
   weight. LOG SET journals `gym_set` (exercise, reps, weight; detail: first time, new best, or the best so far) and
   starts the rest timer: the exercise's own rest (set in the app, kept per exercise), else `rest_s`. FINISH WORKOUT
   journals `workout` (minutes, sets) and the next step follows; nobody finishing it ends it by itself an hour after its
   planned minutes (reason `auto`). The last set, the best set and the rest are kept per exercise (not for a TEST).
4. **Ends** when the workout is logged and the last step is done. The report shows the sets per day and, per exercise,
   the best set of the last day it was trained against the best before.
5. **Step-down**: short = a 20-minute session (`workout` with `min` 20); minimal = a 10-minute walk.

### walk and commute

The protocol of kind `walk` (bundled: `walk`, trigger `toggle:walking`, intensity `subtle`, pool lock). The point is to
observe the world, not scroll.

1. **Starts** when the walking switch goes on: I'M WALKING on home; by itself at the start of every MY TIMES commute
   row on its weekdays (`walk_start` reason `commute`; a commute still starts late, the phone was off, until the
   commute's end); Jummah's `walking_on` step (reason `jummah`). The timeline shows the commute rows as planned starts.
2. **Only the pool is blocked** (`walking`): Maps, WhatsApp, calls and every app outside the pool stay usable. No
   prompts, no takeovers: a silent, ongoing notification "WALKING · POOL BLOCKED" with STOP. Audio only from VIGIL (its
   own sounds and your files); nothing else is enforced beyond the pool.
3. **Ends** when the switch goes off (home, STOP, I'M AT UNI), or after `still_end_min` without steps (the app sends the
   step counter's total; the count starts at the walk's start, so a walk never ends at once). Without a step counter or
   its permission: an automatic commute ends at the commute's end (the row's end, else its start + the row's minutes,
   else `commute_min`) + `commute_grace_min`, any other walk after `manual_max_min`. It also ends with the day. Journal: `walk_start` and
   `walk_end` (minutes, reason), and `toggle`.
4. A commute day counts as the morning walk (steps with `skip_on_class_days` are left out). No step-down.

### friends mode

The protocol of kind `friends` (bundled: `friends`, trigger `toggle:friends`, intensity `subtle`, pool lock): I'M WITH
FRIENDS on home. The pool is refused with "YOU'RE WITH FRIENDS" and no wait option (`friends`); calls, Maps, WhatsApp
and the PoliTO app stay usable. Subtle and quiet: no takeover, a silent ongoing notification with STOP. Ends when the
switch goes off, after `cap_min` (the switch turns itself off: `toggle` "off (the 240-minute cap)"), or with the day.
No step-down.

### call

The protocol of kind `call` (bundled: `call`, trigger `slot:call`, intensity `full`, lock `none`): one run per MY TIMES
calls row, each on its own (MOM and DAD are two runs). The row's `label` is who, `start` the card, `end` the deadline
(16:00 by default), `minutes` the wrap-up (20 by default).

1. **Card** at the row's start: a full-intensity takeover without a lock, with the name big, CALL (opens the dialer;
   no contact is stored) and NOT NOW (it closes; the notification stays). It gives way to any screen lock and is not
   brought back once left, except once: nothing done `return_after_min` after it appeared, the card comes back
   (`call_card` reason `returned`; not during a call; stage 1 only).
2. **DONE** unlocks after a call of `min_call_min` that happened after the card appeared (the part of a call after the
   card counts; any call the phone reports, also through other apps). When such a call ends the card comes back with
   DONE (`call` reason `called`). DONE (or CONFIRM on its notification) completes the run (`call` reason `done`,
   minutes = the longest call).
3. **Missed** when not done by the deadline (`call` reason `missed`), or when the day ends first. A call going on at
   the deadline (it may be this call) holds it: when the call ends, the deadline moves on by the call's length, like a
   lock, so DONE can still be tapped.
4. **Time to wrap up**: a call (any call) going on for the wrap-up minutes while a call card is up, or that started
   after today's first card appeared, gets a silent notification "TIME TO WRAP UP", once per call (`wrap_up`).
5. **Step-down**: short = send a message instead: the card shows MESSAGE (WhatsApp, else the SMS app) and DONE unlocks
   at once. No minimal version: a further NOT OKAY changes nothing (the card says so).
6. Stage 2: no returning card. Stage 3: one notification; CONFIRM completes it, until the deadline.

### wellness nudges

The protocol of kind `nudge` (bundled: `nudge`, trigger `manual`, intensity `full`, screen lock): short takeovers that
feel random. Each holds the screen for `seconds` (a movement nudge `move_seconds`) with one big line and a countdown,
then ends by itself (`nudge_done` reason `time`). NOT OKAY or LEAVE end it at once at no cost; any protocol that needs
the screen ends it (`gave_way`), so does a call. A nudge is never a miss, never a bail-out, never counted for stages,
resets or the report's protocol table.

1. **Timed**: `per_day` nudges (fewer when the span is short) between MY TIMES `wake` + `after_wake_min` and the planned
   wind-down (the sleep protocol's `time:` start, else `bedtime-60`), at least `min_gap_min` apart, at pseudo-random
   times fixed by the day (a restart does not reshuffle them). The lines of `nudges` take turns from a random one.
2. **Movement**: after `still_min` without steps while the phone is unlocked and in use (at least a minute), a line of
   `move_nudges` (none without a step counter or without lines).
3. **Skipped, not queued** (`nudge_skipped`, reason = why) when it cannot show at its time: VIGIL suspended, QUIET,
   a call, friends or walking on, the night lock, a class, another run going on (active, waiting, paused, a card or an
   asking takeover), the phone not in use (screen off or locked), another nudge less than `min_gap_min` ago, or more
   than 10 minutes late. Journal: `nudge_shown` (reason `timed` or `still`, detail = the line) and `nudge_done`.

### audio

VIGIL plays only its own sounds: `white_noise` and the `metronome` (generated in code; 40-208 beats per minute), and
audio files the user adds in the app (copied into the app). One sound at a time, through a media-playback service, so
it goes on with the screen locked (STOP on its notification). The AUDIO screen starts and stops them; protocols stop
them too: an attention reset stops every sound, the wake alarm stops every sound when it rings, the night lock's end
stops white noise, and a sleep protocol with `white_noise: true` starts white noise with the night lock. Journal:
`audio_start` and `audio_stop` (minutes played, reason). The walk and gym protocols allow only VIGIL's audio; nothing is
enforced beyond the pool.

## Validation rules (errors fail the build)

Known kinds, step types, lock types, intensities, triggers and anchors; unique protocol and window ids; references
that exist (`event:after:`); one protocol per prayer; prayer kind <-> adhan trigger; ladder versions only where the
kind allows them, `single` with exactly one step, at most one `done` step per version (a prayer: exactly one, since
tapping it confirms the prayer); `todos` only on text steps; `reenter` only in reset protocols, as the last step;
`arrival` only in jummah protocols, which need exactly one and a done step after it, `lock.minutes` and the
`slot:jummah` trigger (and `slot:jummah` only for them); not every step of a version `skip_on_class_days`; a timer
without `min` needs `lock.minutes` (except in attention protocols); `done_after_min` not above `minutes`; `rounds` only
in study protocols, with `min`, at most one per version; `workout` only in gym protocols, at most one per version; an
attention protocol's `max_min` at least its `start_min`; walk protocols with `toggle:walking` and a `pool` lock, friends
with `toggle:friends` and a `pool` lock, call with `slot:call`, a `none` lock and a done step in every version, nudge
with `manual` and a `screen` lock, and at most one protocol of each of these four kinds (and of the sleep and jummah
kinds);
every number in its range; package names that look like package names; each `apps_button` entry with exactly one key. Warnings: unknown fields and params, long titles and step texts,
`min`/`lines` on step types that ignore them, a `none` lock with minutes, `done:`/`start:` references to protocols not
in the file, an empty pool, an empty APPS button, a weekday entry that changes nothing, `todos` outside a sleep protocol,
`optional` on a step that is not a text step, `break_min`/`break_text` outside a rounds step, `chime` outside a timer or
rounds step, `unlock` in a protocol without a lock, a nudge protocol without `nudges`.
