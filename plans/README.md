# plans/

The archive of weekly plans and their logs. VIGIL itself never reads this folder; it is here so the planner
(and you) can look back over past weeks.

## Naming

Both files are named after the plan's `week_of` date (the Monday the week starts):

| File                   | What it is                                                                  |
|------------------------|-----------------------------------------------------------------------------|
| `plan-YYYY-MM-DD.json` | The plan.json imported for that week, e.g. `plan-2026-10-19.json`.          |
| `log-YYYY-MM-DD.json`  | The log.json VIGIL exported for that same plan, e.g. `log-2026-10-19.json`. |

A plan and its log share the date, so `plan-2026-10-19.json` and `log-2026-10-19.json` belong together. Use the
plan's `week_of`, not the day you exported the log (the log repeats it under `plan.week_of`).

## The weekly loop

1. **End of the week** (Sunday evening, before the plan's `expires`): in VIGIL, export log.json and save it here as
   `log-<this week_of>.json`.
2. **Plan**: open the planning chat and give it [`spec/schema.json`](../spec/schema.json), this week's plan and log,
   and an honest word on how the week went. [`spec/sample-plan.json`](../spec/sample-plan.json) shows the shape.
3. **Save** the planner's output here as `plan-<next week_of>.json`.
4. **Import** it in VIGIL. The import screen lists every error and warning; send those back to the planner and
   re-import until it loads cleanly.

If a week goes by without a new plan, nothing breaks: after `expires` VIGIL runs the old plan's `fallback_plan`, or
its built-in minimal plan (prayers, water, sleep), until the next import.

The app only executes. If something should behave differently next week, that change goes in the next plan.
