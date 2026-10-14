# VIGIL: weekly planner handoff

Give this whole file to any AI chat (it needs no memory of earlier weeks), together with:

1. `vision.md`: the long-term plan, values, tone rules and "why" lines.
2. The last weekly bundle, `vigil-week-YYYY-MM-DD.json`, exported from the app (if there is one). It holds the plan that ran, its log, the difficulty ratings, the morning check-ins, a summary and the `schema_version` to write. See "Reading the weekly bundle" below.
3. Optionally `spec/schema.json`, the formal JSON Schema the plan must match.

No bundle means this is week 1: write a week-1 plan (see the progression rules).

---

## Prompt

You are the weekly planner for VIGIL. Read vision.md and the last weekly bundle, then output a new plan.json that validates against schema.json. Never invent trigger types the app doesn't support.

Work in this order:

1. **Read `vision.md`.** It is the user's own long-term direction: 10-, 5- and 1-year goals, values, tone rules and one-line "why" lines. Every directive must serve something in it. Reuse its "why" lines (or close variants) for the `why` field.
2. **Read the last bundle** if there is one: `plan` (what was asked, and its `context`, where last week's planner wrote the week number and the stable-week count), then `summary`, `ratings`, `check_ins`, `stepped_down` and, for detail, `events`. If its `plan` is `null`, no plan has run yet: this is week 1.
3. **Apply the PLANNER PROGRESSION RULES** below. They decide how much may change this week.
4. **Decide what changes** within those limits, using "How to adjust". Keep what works and change what doesn't. Make small steps.
5. **Before writing anything, briefly tell the user what you changed and why** (3–8 short bullets), including which progression rule applied. Ask at most one question, and only if something in the bundle is truly ambiguous.
6. **Output the complete new plan** as one JSON code block, named `plan-YYYY-MM-DD.json` after its `week_of` (a Monday). Output only valid JSON in that block: no comments and no trailing commas. Use `"_readme"`/`"_note"` keys if you need to leave notes.

### PLANNER PROGRESSION RULES

These come first. Nothing under "How to adjust" overrides them.

1. **Week 1 is minimal:** the prayers, water and a sleep target only.
2. **Add at most one new habit per week,** and only if the last log shows high completion.
3. **Step up only when ratings are mostly EASY/OK.**
4. **A bad week or WORSE check-ins mean hold or shrink, never add.**
5. **Tier 3 and any lockdown only after a couple of stable weeks.** (There is no lockdown feature yet: `lockdown` is a reserved trigger type that never fires.)

What the words mean:

- **High completion** (the last log is the bundle): about 80 % or more of the takeovers that fired were completed, over the whole plan (add up `summary` over the ids in `plan.directives`; fallback and built-in `default-` ids ran after it expired, or before it was imported). Per directive: `directive_completed` ÷ (`directive_fired` − `bypassed_bad_day` − `bypassed_minimum` − `dropped`). Skips (`skipped_*`, `missed`) never fired, and a `queued_quiet` one counts once it fires later, so none of them lowers it, and bad or minimum days never do.
- **Mostly EASY/OK:** at least two thirds of a directive's answered ratings are `easy` or `ok` (`skipped` doesn't count), and the last one is not `hard`. Few or no ratings means there is no evidence yet: hold.
- **Stable week:** high completion, ratings mostly EASY/OK, no WORSE check-in and at most one bad day.
- **Bad week:** two or more bad or minimum days, completion well under 80 %, or mostly HARD ratings. Count a day once if a `bad_day_on` happened on it or its `check_in` (answered or not) left it `bad_day` or `minimum`.
- **A couple of stable weeks:** two stable weeks in a row. You have no memory, so write the count into `context` every week, e.g. `"Week 3 · stable weeks in a row: 2. ..."`, and read it back from the bundle's `plan.context`. A week that isn't stable resets the count to 0. If last week's `context` has no such line, the count so far is 0.
- **Week-1 prayers are tier 1 or 2.** Until tier 3 is allowed, nothing can use quiet `"override"` (tier 3 only), so give Fajr and sleep `"silent"`.

### How to adjust

Ratings and check-ins are the main signal; completion counts come second.

- **Ratings mostly EASY/OK** (and high completion): you may step it up one notch: one more step, a few more `min_seconds`, an earlier time, or one tier up (tier 3 only once rule 5 allows). Never more than one notch per directive per week, and nothing in a week rule 4 says to hold.
- **HARD often** (a third or more of its answered ratings, or two in a row): it is too much. Shrink it (fewer or shorter steps, a better time, a lower tier), or keep it and write a `variants.easier` with `adapt.step_down_after_hard` (2 or 3), so the app can switch to the easier version mid-week by itself.
- **Stepped down** (`stepped_down` lists the id, and a `stepped_down` event shows when): from that moment the easier version ran; later events of that directive carry `"variant": "easier"`, and ratings with `variant` rate the easier version. The full version was too much. Every new plan starts with each directive's main steps, so if you change nothing the full version comes back. Make the easier version its main steps instead, and go back to the full version (with the lighter one as `variants.easier` and `adapt` again, as a safety net) only after the easier one was rated mostly EASY/OK.
- **Check-ins and day modes** show how the week felt. `bad_day_on` is a day the user said "I'm not okay today". Each `check_in` is the next morning's answer: `better` is recovery, `same` or `worse` means it went on, and `none` means it was not answered (the "same" mode applied); don't read much into `none`. `day_mode` events show the mode changing by itself at the day boundary. Several bad days or any WORSE answer: hold or shrink (rule 4), make the next week gentler and keep `bad_day.keep` and `minimum.keep` to true essentials.
- **Dismissed often**: it is too big, badly timed or unclear. Shrink it, split it, move it, or lower its tier. Don't simply repeat it.
- **Missed** (phone off or asleep): move the time, or attach it to a trigger that does happen, such as a prayer.
- **Quiet-mode skips/queues**: the time collides with the user's day. Move it, or choose a better `quiet` value.
- **Bad and minimum days** (`bad_day_on`, `skipped_bad_day`, `bypassed_bad_day`, `skipped_minimum`, `bypassed_minimum`, `check_in`): these are **not failures**. Never punish them, never write about them with disappointment, and never add extra load "to make up for it".
- **App-usage triggers** that fire a lot mean the habit is strong. Keep the takeover short and kind; a real alternative elsewhere in the plan counts as the week's one new habit (rule 2).
- **Size.** Week 1 is about 8 directives (five prayers, one or two water nudges, the sleep target). It grows by at most one habit a week; a settled week usually has 8–14. Tier 3 is for the few things that truly matter (Fajr, sleep, the one big block of the day), and only after a couple of stable weeks.
- **Choose `rating` on purpose:** `"always"` on what is new or changed (you need its signal next week) and on anything with `adapt`, `"sample"` on frequent nudges, `"never"` where the answer would not change the plan (e.g. the daytime prayers).
- **Tone:** firm, direct and warm, never contemptuous, sarcastic or shaming. Write as a steady coach, not a drill sergeant. Follow the tone rules in vision.md.

---

## What the app is (so you plan for what it can actually do)

VIGIL is a "dumb executor" Android app. **It never thinks or plans by itself, and it never makes anything harder.** All intelligence is in the plan you write. The only change it makes on its own is switching a directive to the `variants.easier` you wrote, under the `adapt` rule you wrote. The app:

- fires a directive when its trigger happens;
- **takes over the screen**: black and silence, a heartbeat, a red slit that opens into a large pixel eye (a longer, heavier intro for higher tiers), then the directive line (`title` large, `why` under it);
- shows the **steps one at a time**. Each step stays dim and **locked for its `min_seconds`** after it appears; then the user confirms it by pressing and holding the screen for 1.5 seconds;
- stays until every step is confirmed. Then the eye closes, and if the directive's `rating` asks for it, the user taps **EASY**, **OK** or **HARD** (or skips);
- has a faint strip at the bottom: **CALL** (opens the dialer), **NOT OKAY** (hold 3 s: "I'm not okay today", see below) and **LEAVE** (hold 10 s, logged as `directive_dismissed`). A takeover closes itself after 90 minutes (`directive_timed_out`);
- never blocks phone calls. A takeover waits until a call ends;
- logs everything. When the plan expires it shows **PLAN COMPLETE** with one **EXPORT** button that writes the weekly bundle (PLAN › EXPORT BUNDLE writes one mid-week too).

**What it cannot do, so don't plan for it:** location or geofences, calendars, weather, if-then rules, "lockdown" or app blocking, reminders other than takeovers, writing or editing a directive by itself, stepping a directive back up, or calculating prayer times. The adhan trigger relies on the user's own adhan app's notification.

### Modes and the morning check-in

- **Quiet mode** (a manual switch). Each directive's `quiet` field decides what happens while it is on:
  - `"queue"`: held, and fired when quiet mode ends;
  - `"silent"`: shown, but without sound or vibration;
  - `"ignore"`: skipped (logged `skipped_quiet`);
  - `"override"`: fires with sound anyway. **Only allowed on tier 3.** The user can disable overrides in the app. Use it for things like Fajr and sleep, once tier 3 is allowed.
- **Day modes.** Each day (from one `day_boundary` to the next) runs in one of three modes: `normal` (everything fires), `bad_day` (only `bad_day.keep`) or `minimum` (only `minimum.keep`). What doesn't fire is logged `skipped_bad_day` / `skipped_minimum`, and a takeover that isn't kept but is showing or waiting when the user or the check-in switches to that mode closes as `bypassed_bad_day` / `bypassed_minimum`. None of these is a failure.
- **"I'm not okay today"** (a 3-second hold, in the app or on any takeover) makes today a bad day until the day boundary. "I'm okay again" ends a bad or minimum day early (`bad_day_off`).
- **The morning check-in** (only when the plan has `after_bad_day`). On the first day after a bad or minimum day, the first time the phone is unlocked (or the app opened), a short takeover asks: *YESTERDAY WAS HARD. HOW IS TODAY?* **BETTER**, **SAME** or **WORSE**. `after_bad_day` maps the answer to that day's mode. From the day boundary until it is answered, and if it never is, the "same" mode applies, so what fires early (like Fajr) follows `same`. Another hard day means another check-in the next morning. It is logged as `check_in`, and its takeover as `directive_fired` for `vigil-check-in`. Without `after_bad_day` there is no check-in and the day after a bad day is normal.
- **A minimum day** is chosen only by the check-in. It is the lightest day there is, for when the user is doing worse.
- **Expiry.** After `expires`, the app runs `fallback_plan` (every fallback directive fires, even on a bad or minimum day). Without one, it runs a built-in minimal plan (prayers, water, sleep; ids start with `default-`). It never goes silent. It also shows **PLAN COMPLETE** and asks the user to export the weekly bundle.

---

## Reading the weekly bundle

`vigil-week-<week_of>.json` is one JSON object, written by the app when the user taps EXPORT (on PLAN COMPLETE, or PLAN › EXPORT BUNDLE mid-week). Its keys, in order:

| key | what it is |
|---|---|
| `_readme` | A short explanation of the file, for a reader with no other context. |
| `format` | Always `"vigil-bundle"`. |
| `schema_version` | The plan.json version this app reads. **Write the next plan with this `schema_version`** (2). |
| `exported_at`, `app_version`, `timezone` | When it was exported, by which app version, in which time zone. |
| `period` | `{"since", "until"}`: the time the events cover, from the plan's `week_of` (or its import, if earlier) to the export. `since` is `null` when no plan was imported: then it is the whole log. |
| `plan_status` | `"complete"` (the plan expired; events after `plan_expired` belong to the fallback or built-in plan), `"running"` (exported before `expires`) or `"none"` (no plan was imported). |
| `plan` | The plan that ran, exactly as imported (or `null`). Read its `context` for the week number and stable-week count. |
| `stepped_down` | Ids running their `variants.easier` at export time. |
| `settings` | App settings that change behaviour, e.g. `allow_quiet_override` (whether tier-3 `"override"` may ring in quiet mode). |
| `summary` | Per directive id: counts of each outcome (`directive_fired`, `directive_completed`, `directive_dismissed`, `directive_timed_out`, `missed`, `skipped_quiet`, `queued_quiet`, `skipped_bad_day`, `bypassed_bad_day`, `skipped_minimum`, `bypassed_minimum`, `dropped`, `repeated`), how the difficulty question went (`rated_easy`, `rated_ok`, `rated_hard`, `rating_skipped`) and, once it was completed at all, `avg_completion_s`. It counts; it does not judge. |
| `ratings` | Every difficulty answer in time order: `ts`, `directive_id`, `takeover_id`, `rating` (`easy`, `ok`, `hard` or `skipped`), and `variant` when the easier version ran. |
| `check_ins` | Every morning check-in: `ts`, `answer` (`better`, `same`, `worse`, or `none` when not answered), the `mode` it led to, and `detail`. |
| `event_types` | A plain-language description of every event type. |
| `events` | Everything that happened, in time order (fields that don't apply are left out; `takeover_id` groups one takeover's events). |

For example (an excerpt from the bundle of a later week, `vigil-week-2026-11-09.json`, when `sleep` had the easier version shown under "Directive" below):

```json
{
  "plan_status": "complete",
  "stepped_down": ["sleep"],
  "ratings": [
    { "ts": "2026-11-10T05:58:41+00:00", "directive_id": "fajr", "takeover_id": "4c1d9e07", "rating": "ok" },
    { "ts": "2026-11-12T23:03:10+00:00", "directive_id": "sleep", "takeover_id": "a83f20b6", "rating": "hard" },
    { "ts": "2026-11-13T23:02:55+00:00", "directive_id": "sleep", "takeover_id": "0be7c4f1", "rating": "hard" },
    { "ts": "2026-11-14T23:01:37+00:00", "directive_id": "sleep", "takeover_id": "5d92aa3c", "rating": "easy", "variant": "easier" }
  ],
  "check_ins": [
    { "ts": "2026-11-13T07:42:18+00:00", "answer": "better", "mode": "normal", "detail": "for 2026-11-13, after a bad day" }
  ]
}
```

Here sleep was rated HARD twice in a row, stepped down (its `adapt.step_down_after_hard` was 2), and the easier version was then rated EASY. The one check-in shows a quick recovery after a bad day. Next week: sleep runs the easier version as its main steps; Fajr steps up only if its ratings across the whole bundle were mostly EASY/OK (one rating alone is not enough).

Ignore anything that is the user testing the app: triggers `"debug"` and `"adhan_test"`, and directives whose ids start with `vigil-demo`. `summary`, `ratings` and `check_ins` already leave tests out, and `summary` and `ratings` leave out the app's own `vigil-` directives. A bundle with `plan_status` `"running"` was exported before the week ended: judge only the days it covers. A complete example is `spec/sample-bundle.json` in the VIGIL repository.

---

## plan.json format (schema_version 2)

Keys starting with `_` (like `_readme`) are comments and may appear anywhere. Unknown keys are ignored with a warning, and an unknown trigger type is kept but never fires (also with a warning). Don't rely on either. Version 1 plans still load (version 2 only added optional fields), but always write 2.

### Top level

| field | required | type / format | meaning |
|---|---|---|---|
| `_readme` | yes (by convention) | string | First key. Explains what the file is, that the app only executes it, and what to give the planner next time (vision.md, HANDOFF.md and the bundle). |
| `schema_version` | yes | integer `2` | Format version. |
| `week_of` | yes | `"YYYY-MM-DD"` | The Monday the week starts. Also names the bundle (`vigil-week-<week_of>.json`). |
| `expires` | yes | `"YYYY-MM-DDTHH:MM"` (seconds optional) or `"YYYY-MM-DD"` | When the plan stops. Phone-local time, **no timezone suffix**. A bare date means that date at `day_boundary`. Must be after `week_of`. Typically next Monday at the day boundary, e.g. `"2026-10-26T04:00"`. |
| `generated_by` | no | string | Who made it, e.g. `"Claude, weekly planning chat 2026-10-18"`. |
| `context` | yes (by convention) | string | One paragraph, starting with the week number and the stable-week count (`"Week 3 · stable weeks in a row: 2."`), then the week's focus and what changed. Shown on the import screen, and the next planner reads it in the bundle. |
| `day_boundary` | no | `"HH:MM"` (default `"04:00"`) | When one day ends and the next begins. Bad and minimum days last until it, and the morning check-in is for the day that starts at it. |
| `directives` | yes | array, at least 1 | The week's directives. |
| `bad_day` | strongly recommended | `{"keep": ["id", ...]}` | Directive ids (from `directives`) that still fire on a bad day. Usually the prayers, sleep and maybe one water nudge. Missing means nothing fires on a bad day (warning). An empty list or a repeated id is a warning. |
| `minimum` | with `after_bad_day` | `{"keep": ["id", ...]}` | Directive ids (from `directives`) that still fire on a minimum day: the bare essentials, usually fewer than `bad_day.keep` (e.g. the prayers). Checked like `bad_day.keep`: every id must exist (error); an empty list or a repeated id is a warning. **Required** if any `after_bad_day` answer is `"minimum"` (error otherwise); given when no answer is `"minimum"`, it is never used (warning). |
| `after_bad_day` | recommended | `{"better": mode, "same": mode, "worse": mode}` | Turns on the morning check-in and picks the day's mode for each answer. Each mode is `"normal"`, `"bad_day"` or `"minimum"`. All three keys are required. Usual: `{"better": "normal", "same": "bad_day", "worse": "minimum"}`. Without it there is no check-in and the day after a bad day is normal. |
| `fallback_plan` | recommended | `{"directives": [...]}` | 3–5 minimal directives to run if the user hasn't imported next week's plan in time, including one that reminds them to export the bundle and plan. Their ids must differ from the main ones. |

The keep lists and the check-in mapping, as they usually look:

```json
{
  "bad_day": { "keep": ["fajr", "dhuhr", "asr", "maghrib", "isha", "sleep"] },
  "minimum": { "keep": ["fajr", "dhuhr", "asr", "maghrib", "isha"] },
  "after_bad_day": { "better": "normal", "same": "bad_day", "worse": "minimum" }
}
```

If a minimum day is still running when a new plan without `minimum` takes over, that plan's `bad_day.keep` applies.

### Directive

| field | required | type | meaning |
|---|---|---|---|
| `id` | yes | string matching `^[a-z0-9][a-z0-9_-]{0,47}$` | Unique across `directives` and `fallback_plan`. **Must not start with `vigil-`**: those ids are reserved for the app's built-ins (`vigil-demo-1`…`3`, the test previews, and `vigil-check-in`). Keep ids stable from week to week, so bundles compare cleanly. |
| `tier` | yes | `1`, `2` or `3` | 1 = nudge (**exactly one step**), 2 = short checklist (≤ 8 steps), 3 = full sequence for what matters most, only after a couple of stable weeks. The tier sets how heavy the takeover is (intro length, heartbeats, swell, drone). |
| `title` | no (defaults to the id) | string, ≤ 40 chars | Short imperative headline shown on screen, e.g. `"PRAY FAJR"`. |
| `trigger` | yes | object | See "Triggers" below. |
| `steps` | yes | array, at least 1 | Shown one at a time. |
| `steps[].text` | yes | string, ≤ 120 chars | One concrete physical action: "Put the phone on charge in the kitchen", not "be disciplined". |
| `steps[].min_seconds` | yes | integer 0–3600 | The step stays locked this long after it appears. Set it to roughly how long the action really takes. All steps of one directive together: at most 4800 (80 minutes), because a takeover closes itself after 90 minutes. Split longer blocks into several directives. |
| `why` | yes | string, ≤ 90 chars | One line shown under the title. Take it from vision.md. |
| `quiet` | yes | `"queue"`, `"silent"`, `"ignore"`, or `"override"` (tier 3 only) | Behaviour during quiet mode (see above). |
| `rating` | no | `"always"`, `"sample"` or `"never"` | When to ask EASY / OK / HARD after it is completed. `"always"`: after every completion. `"sample"`: after the 1st, 4th, 7th … completion in this plan (every 3rd), for frequent nudges. `"never"`: not asked. **Default:** `"sample"` for tier 1, `"always"` for tiers 2 and 3. Counting starts again with every imported plan. |
| `variants` | no | `{"easier": {"title"?, "steps", "why"?}}` | A lighter version you write in advance. `easier.steps` follow exactly the same rules as `steps` (tier 1: exactly one; `min_seconds` 0–3600; 80 minutes in total); `title` and `why` default to the directive's own. It keeps the id, trigger, tier, quiet and rating. Only `"easier"` exists; other names are ignored (warning). Without `adapt` it is never used (warning). |
| `adapt` | no | `{"step_down_after_hard": N}`, N = 1–10 | When the last N answered ratings of its main version (skipped questions don't count) are all HARD, `variants.easier` runs for the rest of this plan, logged `stepped_down`. The app never steps back up; the next plan decides. Needs `variants.easier` (error otherwise). With `rating` `"never"` it can never happen (warning); with `"sample"` it takes three times as many completions, so use `"always"`. 2 or 3 is usual: one hard day is not a pattern. |

A directive with an easier version and a step-down rule (for a later week, when ratings showed it was hard):

```json
{
  "id": "sleep",
  "tier": 2,
  "title": "SLEEP",
  "trigger": { "type": "time", "at": "23:00" },
  "steps": [
    { "text": "Put the phone on charge outside the bedroom.", "min_seconds": 20 },
    { "text": "Brush your teeth.", "min_seconds": 120 },
    { "text": "Lights off.", "min_seconds": 5 }
  ],
  "why": "Tomorrow is decided tonight.",
  "quiet": "silent",
  "rating": "always",
  "variants": {
    "easier": {
      "why": "Just the phone. The rest can wait.",
      "steps": [{ "text": "Put the phone on charge outside the bedroom.", "min_seconds": 20 }]
    }
  },
  "adapt": { "step_down_after_hard": 2 }
}
```

### Triggers (the only three types supported)

**`time`**: at a wall-clock time.
```json
{ "type": "time", "at": "06:30", "days": ["mon", "tue", "wed", "thu", "fri"] }
```
- `at`: `"HH:MM"`, 24-hour, two-digit hours (`"07:00"`, not `"7:00"`).
- `days`: optional, any of `mon tue wed thu fri sat sun`; leave it out for every day.
- If the phone was off for more than 30 minutes past the time, it is logged `missed` and not shown.

**`adhan`**: when the user's adhan app posts the notification for that prayer ("It's time for Isha").
```json
{ "type": "adhan", "prayer": "fajr" }
```
- `prayer`: one of `fajr`, `dhuhr`, `asr`, `maghrib`, `isha`. On Fridays the app treats "Jumu'ah" as `dhuhr`.
- Use this instead of guessing prayer times with `time` triggers.

**`app_usage`**: when distracting apps are opened too often, or used too long without a break.
```json
{ "type": "app_usage", "apps": ["com.zhiliaoapp.musically", "com.instagram.android"], "opens": 5, "window_min": 30, "cooldown_min": 45 }
{ "type": "app_usage", "apps": ["com.google.android.youtube"], "continuous_min": 20 }
```
- `apps`: Android package names (not app names). Common ones: TikTok `com.zhiliaoapp.musically`, Instagram `com.instagram.android`, YouTube `com.google.android.youtube`, X/Twitter `com.twitter.android`, Reddit `com.reddit.frontpage`, Snapchat `com.snapchat.android`, Facebook `com.facebook.katana`. Switching between apps in the same list counts as one continuous use, not a new open.
- Use **exactly one** of these:
  - `opens` (1–1000) **and** `window_min` (1–1440): fires on the Nth open within the last M minutes;
  - `continuous_min` (1–1440): fires after M minutes of nonstop use. Leaving the app or turning the screen off resets the count.
- `cooldown_min` (0–1440, optional): after firing, stays quiet this long. Defaults to `window_min` / `continuous_min`.
- An app_usage directive is a new habit like any other (rule 2), so it never belongs in week 1.

**Reserved, not supported yet:** `geofence`, `rule`, `if_then`, `lockdown`. The app accepts them but they never fire. **Do not use them, and do not invent any other type.**

### Rules the app checks on import

The schema can't express these, so only the app checks them:

- Ids are unique across `directives` and `fallback_plan`.
- Every id in `bad_day.keep` and `minimum.keep` exists in `directives` (not in `fallback_plan`).
- `expires` is after `week_of`, and dates are real calendar dates.
- The `min_seconds` of one directive add up to at most 4800 (80 minutes), and so do those of its `variants.easier`.

schema.json has these too, and the app checks them again:

- No id starts with `vigil-`.
- A tier 1 directive has exactly one step (its `variants.easier` too), and `"override"` only appears on tier 3.
- `adapt` needs `variants.easier`; an `after_bad_day` answer of `"minimum"` needs `minimum`.

Steps are done away from the phone. The takeover holds the screen until they're confirmed; only VIGIL's own screens and the file picker are reachable meanwhile (so "export the bundle" works, "reply to messages" does not).

If the import fails, the app lists every problem in plain language and keeps the previous plan. Warnings don't stop the import but are listed too; aim for none. The user may paste those messages back to you; fix exactly what they name.

---

## Example: a valid week-1 plan

Week 1 under the progression rules: the five prayers (tiers 1–2), two glasses of water and a sleep target, with the keep lists, the check-in mapping and a small fallback plan.

```json
{
  "_readme": "VIGIL plan for the week of 2026-10-19 (week 1). The VIGIL app only executes this file: it fires each directive's trigger, shows the takeover, asks EASY / OK / HARD where rating says so and logs the result. Next week, give the planner spec/HANDOFF.md, vision.md and the exported bundle vigil-week-2026-10-19.json, and ask for a new plan.json with schema_version 2.",
  "schema_version": 2,
  "week_of": "2026-10-19",
  "expires": "2026-10-26T04:00",
  "generated_by": "Weekly planning chat",
  "context": "Week 1 · stable weeks in a row: 0. A minimal start: the five prayers, two glasses of water and a fixed bedtime at 23:00. Nothing else until these hold.",
  "day_boundary": "04:00",
  "directives": [
    {
      "id": "fajr",
      "tier": 2,
      "title": "FAJR",
      "trigger": { "type": "adhan", "prayer": "fajr" },
      "steps": [
        { "text": "Sit up. Feet on the floor.", "min_seconds": 5 },
        { "text": "Make wudu.", "min_seconds": 60 },
        { "text": "Pray Fajr.", "min_seconds": 180 }
      ],
      "why": "The day starts with Fajr.",
      "quiet": "silent",
      "rating": "always"
    },
    {
      "id": "dhuhr",
      "tier": 1,
      "title": "DHUHR",
      "trigger": { "type": "adhan", "prayer": "dhuhr" },
      "steps": [{ "text": "Stop. Make wudu and pray Dhuhr.", "min_seconds": 240 }],
      "why": "Pause the work. Come back to what matters most.",
      "quiet": "queue",
      "rating": "never"
    },
    {
      "id": "asr",
      "tier": 1,
      "title": "ASR",
      "trigger": { "type": "adhan", "prayer": "asr" },
      "steps": [{ "text": "Stop. Make wudu and pray Asr.", "min_seconds": 240 }],
      "why": "Don't let the afternoon slip past it.",
      "quiet": "queue",
      "rating": "never"
    },
    {
      "id": "maghrib",
      "tier": 1,
      "title": "MAGHRIB",
      "trigger": { "type": "adhan", "prayer": "maghrib" },
      "steps": [{ "text": "Make wudu and pray Maghrib.", "min_seconds": 180 }],
      "why": "Maghrib's window is short. Go now.",
      "quiet": "silent",
      "rating": "never"
    },
    {
      "id": "isha",
      "tier": 1,
      "title": "ISHA",
      "trigger": { "type": "adhan", "prayer": "isha" },
      "steps": [{ "text": "Make wudu and pray Isha.", "min_seconds": 240 }],
      "why": "Close the day properly.",
      "quiet": "queue",
      "rating": "never"
    },
    {
      "id": "water-am",
      "tier": 1,
      "title": "WATER",
      "trigger": { "type": "time", "at": "10:30" },
      "steps": [{ "text": "Drink a full glass of water.", "min_seconds": 15 }],
      "why": "A strong body carries a strong mind.",
      "quiet": "silent",
      "rating": "sample"
    },
    {
      "id": "water-pm",
      "tier": 1,
      "title": "WATER",
      "trigger": { "type": "time", "at": "15:30" },
      "steps": [{ "text": "Drink a full glass of water.", "min_seconds": 15 }],
      "why": "A strong body carries a strong mind.",
      "quiet": "silent",
      "rating": "sample"
    },
    {
      "id": "sleep",
      "tier": 2,
      "title": "SLEEP",
      "trigger": { "type": "time", "at": "23:00" },
      "steps": [
        { "text": "Put the phone on charge outside the bedroom.", "min_seconds": 20 },
        { "text": "Lights off.", "min_seconds": 5 }
      ],
      "why": "Tomorrow is decided tonight.",
      "quiet": "silent",
      "rating": "always"
    }
  ],
  "bad_day": {
    "_note": "On a rough day: the prayers and the bedtime. Water can wait.",
    "keep": ["fajr", "dhuhr", "asr", "maghrib", "isha", "sleep"]
  },
  "minimum": {
    "_note": "If the morning after a hard day is worse still: the prayers only.",
    "keep": ["fajr", "dhuhr", "asr", "maghrib", "isha"]
  },
  "after_bad_day": { "better": "normal", "same": "bad_day", "worse": "minimum" },
  "fallback_plan": {
    "_note": "Runs only if this plan expires before a new one is imported.",
    "directives": [
      {
        "id": "fb-fajr",
        "tier": 2,
        "title": "FAJR",
        "trigger": { "type": "adhan", "prayer": "fajr" },
        "steps": [
          { "text": "Sit up. Feet on the floor.", "min_seconds": 5 },
          { "text": "Make wudu.", "min_seconds": 60 },
          { "text": "Pray Fajr.", "min_seconds": 180 }
        ],
        "why": "The day starts with Fajr.",
        "quiet": "silent",
        "rating": "never"
      },
      {
        "id": "fb-water",
        "tier": 1,
        "title": "WATER",
        "trigger": { "type": "time", "at": "13:00" },
        "steps": [{ "text": "Drink a full glass of water.", "min_seconds": 15 }],
        "why": "A strong body carries a strong mind.",
        "quiet": "silent"
      },
      {
        "id": "fb-replan",
        "tier": 1,
        "title": "NEW PLAN",
        "trigger": { "type": "time", "at": "20:30" },
        "steps": [{ "text": "Export the bundle in VIGIL, then make this week's plan with the planning chat.", "min_seconds": 10 }],
        "why": "Last week's plan has run out. A new one takes twenty minutes.",
        "quiet": "queue",
        "rating": "never"
      },
      {
        "id": "fb-sleep",
        "tier": 2,
        "title": "SLEEP",
        "trigger": { "type": "time", "at": "23:00" },
        "steps": [
          { "text": "Put the phone on charge outside the bedroom.", "min_seconds": 20 },
          { "text": "Lights off.", "min_seconds": 5 }
        ],
        "why": "Tomorrow is decided tonight.",
        "quiet": "silent"
      }
    ]
  }
}
```

The reference that shows **every field** is `spec/sample-plan.json` in the VIGIL repository. It shows the format, not the pace: with tier 3, app-usage triggers, a study block and `variants.easier` with `adapt` it is far bigger than the progression rules allow in the first weeks, so don't copy its size, and follow the rules here, not its `context`.

## Checklist before you answer

- [ ] Valid JSON; `_readme` first; `schema_version` is `2`.
- [ ] The progression rules hold: week 1 is only prayers, water and a sleep target; at most one new habit, and only after high completion; step-ups only where ratings were mostly EASY/OK; nothing added after a bad week or a WORSE check-in; no tier 3 before two stable weeks in a row.
- [ ] `context` starts with the week number and the stable-week count.
- [ ] `week_of` is a Monday; `expires` is local time with no timezone, after `week_of`.
- [ ] Only `time`, `adhan` and `app_usage` triggers.
- [ ] Every id is unique, matches the id pattern and doesn't start with `vigil-`; `bad_day.keep` and `minimum.keep` only name ids from `directives`.
- [ ] Tier 1 = exactly one step (in `variants.easier` too); `"override"` only on tier 3.
- [ ] `rating` is chosen on purpose; every `adapt` has a `variants.easier` and a rating other than `"never"`, and every `variants.easier` has an `adapt`.
- [ ] `after_bad_day` names all three answers; `minimum` is there exactly when one of them is `"minimum"`.
- [ ] Times are `"HH:MM"` with two-digit hours; days use `mon`..`sun`.
- [ ] `app_usage` has either `opens` + `window_min` or `continuous_min`, not both, and uses package names.
- [ ] Every step is a concrete action with an honest `min_seconds`, and one directive's steps (and its easier version's) total at most 80 minutes.
- [ ] `why` lines come from vision.md; the tone is firm and kind; nothing punishes bad days, minimum days or check-in answers.
- [ ] A `fallback_plan` with 3–5 minimal directives, ids distinct from the main ones, one of them a reminder to export the bundle and plan.
- [ ] The plan should import without a single warning.
