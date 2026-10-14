# plans/

The archive of weekly plans and their bundles. VIGIL itself never reads this folder; it is here so the planner
(and you) can look back over past weeks.

## Naming

Both files are named after the plan's `week_of` date (the Monday the week starts):

| File                         | What it is                                                                                  |
|------------------------------|---------------------------------------------------------------------------------------------|
| `plan-YYYY-MM-DD.json`       | The plan.json imported for that week, e.g. `plan-2026-10-19.json`.                          |
| `vigil-week-YYYY-MM-DD.json` | The weekly bundle VIGIL exported for that same plan, e.g. `vigil-week-2026-10-19.json`.     |

A plan and its bundle share the date, so `plan-2026-10-19.json` and `vigil-week-2026-10-19.json` belong together.
VIGIL already suggests the right bundle name when you export. The bundle holds the plan that ran, its log, the
difficulty ratings, the morning check-ins and a summary, so it is the one file the planner needs from the week.

Older weeks may also have a `log-YYYY-MM-DD.json` from VIGIL 1.0. Bundles replaced it.

## The weekly loop

1. **End of the week:** when the plan expires, VIGIL shows PLAN COMPLETE. Tap **EXPORT** and save the bundle here as
   `vigil-week-<this week_of>.json`. (PLAN › EXPORT BUNDLE writes one before that, too.)
2. **Plan:** open the planning chat and give it [`spec/HANDOFF.md`](../spec/HANDOFF.md), your `vision.md` and that
   bundle, and an honest word on how the week went. In week 1 there is no bundle yet.
3. **Save** the planner's output here as `plan-<next week_of>.json`.
4. **Import** it in VIGIL (PLAN › IMPORT PLAN). The import screen lists every error and warning; send those back to the
   planner and re-import until it loads cleanly.

If a week goes by without a new plan, nothing breaks: after `expires` VIGIL runs the old plan's `fallback_plan`, or
its built-in minimal plan (prayers, water, sleep), until the next import.

The app only executes. If something should behave differently next week, that change goes in the next plan.
