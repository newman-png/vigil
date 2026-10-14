"""Regenerates the SchemaConformanceTest fixtures next to this file: python3 generate.py

Every fixture is BASE with one change. invalid/ must be rejected by both spec/schema.json and PlanParser (the
parser must report an error under "_expect_error_at"); valid-with-warnings/ must load with a warning under
"_expect_warning_at"; valid/ must load cleanly. Add a case to the tables below rather than editing the JSON files.
"""
import copy
import json
import os
import shutil

OUT = os.path.dirname(os.path.abspath(__file__))

BASE = {
    "schema_version": 1,
    "week_of": "2026-10-19",
    "expires": "2026-10-26T04:00",
    "day_boundary": "04:00",
    "directives": [
        {
            "id": "fajr",
            "tier": 3,
            "title": "FAJR",
            "trigger": {"type": "adhan", "prayer": "fajr"},
            "steps": [{"text": "Make wudu.", "min_seconds": 60}, {"text": "Pray Fajr.", "min_seconds": 180}],
            "why": "The day starts here.",
            "quiet": "override",
        },
        {
            "id": "water",
            "tier": 1,
            "title": "WATER",
            "trigger": {"type": "time", "at": "10:30", "days": ["mon", "wed", "fri"]},
            "steps": [{"text": "Drink a full glass of water.", "min_seconds": 10}],
            "why": "Small things, every day.",
            "quiet": "silent",
        },
        {
            "id": "scroll",
            "tier": 2,
            "title": "PAUSE",
            "trigger": {"type": "app_usage", "apps": ["com.zhiliaoapp.musically"], "opens": 6, "window_min": 30},
            "steps": [{"text": "Put the phone down.", "min_seconds": 10}, {"text": "Take three slow breaths.", "min_seconds": 20}],
            "why": "Make it a choice.",
            "quiet": "queue",
        },
    ],
    "bad_day": {"keep": ["fajr"]},
    "fallback_plan": {
        "directives": [
            {
                "id": "fb-sleep",
                "tier": 3,
                "title": "SLEEP",
                "trigger": {"type": "time", "at": "23:00"},
                "steps": [{"text": "Phone on charge across the room.", "min_seconds": 10}],
                "why": "Fajr is won the night before.",
                "quiet": "override",
            }
        ]
    },
}


def d(plan, i):
    return plan["directives"][i]


def trig(plan, i):
    return plan["directives"][i]["trigger"]


# name -> (problem, parser path prefix, mutation)
INVALID = {
    "schema-version-missing": ("schema_version is missing", "schema_version", lambda p: p.pop("schema_version")),
    "schema-version-2": ("schema_version 2 is newer than the app", "schema_version", lambda p: p.update(schema_version=2)),
    "week-of-slashes": ("week_of is not YYYY-MM-DD", "week_of", lambda p: p.update(week_of="2026/10/19")),
    "week-of-not-a-date": ("week_of is not a real date", "week_of", lambda p: p.update(week_of="2026-02-30")),
    "expires-missing": ("expires is missing", "expires", lambda p: p.pop("expires")),
    "expires-with-timezone": ("expires has a timezone", "expires", lambda p: p.update(expires="2026-10-26T04:00Z")),
    "expires-hour-24": ("expires has hour 24", "expires", lambda p: p.update(expires="2026-10-26T24:00")),
    "expires-date-not-real": ("date-only expires is not a real date", "expires", lambda p: p.update(expires="2026-02-30")),
    "day-boundary-no-leading-zero": ("day_boundary is 4:00, not 04:00", "day_boundary", lambda p: p.update(day_boundary="4:00")),
    "generated-by-number": ("generated_by is a number", "generated_by", lambda p: p.update(generated_by=5)),
    "directives-missing": ("directives is missing", "directives", lambda p: p.pop("directives")),
    "directives-empty": ("directives is empty", "directives", lambda p: p.update(directives=[])),
    "directive-not-object": ("a directive is a string", "directives[1]", lambda p: p["directives"].__setitem__(1, "water")),
    "id-uppercase": ("id has capital letters", "directives[1].id", lambda p: d(p, 1).update(id="Water")),
    "id-too-long": ("id is 49 characters", "directives[1].id", lambda p: d(p, 1).update(id="w" * 49)),
    "id-leading-dash": ("id starts with '-'", "directives[1].id", lambda p: d(p, 1).update(id="-water")),
    "tier-zero": ("tier 0", "directives[2].tier", lambda p: d(p, 2).update(tier=0)),
    "tier-four": ("tier 4", "directives[2].tier", lambda p: d(p, 2).update(tier=4)),
    "tier-string": ("tier written as text", "directives[2].tier", lambda p: d(p, 2).update(tier="2")),
    "tier1-two-steps": ("tier 1 with two steps", "directives[1]", lambda p: d(p, 1)["steps"].append({"text": "And another.", "min_seconds": 5})),
    "quiet-unknown": ("quiet is not one of the four values", "directives[1].quiet", lambda p: d(p, 1).update(quiet="loud")),
    "quiet-override-on-tier-2": ("quiet override on a tier 2 directive", "directives[2]", lambda p: d(p, 2).update(quiet="override")),
    "why-missing": ("why is missing", "directives[1].why", lambda p: d(p, 1).pop("why")),
    "why-blank": ("why is only spaces", "directives[1].why", lambda p: d(p, 1).update(why="   ")),
    "title-empty": ("title is an empty string", "directives[1].title", lambda p: d(p, 1).update(title="")),
    "steps-missing": ("steps is missing", "directives[1].steps", lambda p: d(p, 1).pop("steps")),
    "steps-empty": ("steps is empty", "directives[1].steps", lambda p: d(p, 1).update(steps=[])),
    "step-not-object": ("a step is a string", "directives[0].steps[0]", lambda p: d(p, 0)["steps"].__setitem__(0, "Make wudu.")),
    "step-text-blank": ("step text is only spaces", "directives[0].steps[0].text", lambda p: d(p, 0)["steps"][0].update(text="  ")),
    "step-min-seconds-missing": ("min_seconds is missing", "directives[0].steps[0].min_seconds", lambda p: d(p, 0)["steps"][0].pop("min_seconds")),
    "step-min-seconds-too-large": ("min_seconds is 3601", "directives[0].steps[0].min_seconds", lambda p: d(p, 0)["steps"][0].update(min_seconds=3601)),
    "step-min-seconds-fraction": ("min_seconds is 1.5", "directives[0].steps[0].min_seconds", lambda p: d(p, 0)["steps"][0].update(min_seconds=1.5)),
    "trigger-missing": ("trigger is missing", "directives[1].trigger", lambda p: d(p, 1).pop("trigger")),
    "trigger-not-object": ("trigger is a string", "directives[1].trigger", lambda p: d(p, 1).update(trigger="10:30")),
    "trigger-type-missing": ("trigger has no type", "directives[1].trigger", lambda p: trig(p, 1).pop("type")),
    "trigger-type-number": ("trigger type is a number", "directives[1].trigger.type", lambda p: trig(p, 1).update(type=1)),
    "time-at-missing": ("time trigger without at", "directives[1].trigger.at", lambda p: trig(p, 1).pop("at")),
    "time-at-25": ("time 25:00", "directives[1].trigger.at", lambda p: trig(p, 1).update(at="25:00")),
    "time-days-empty": ("days is an empty list", "directives[1].trigger.days", lambda p: trig(p, 1).update(days=[])),
    "time-days-full-name": ("day written as monday", "directives[1].trigger.days", lambda p: trig(p, 1).update(days=["monday"])),
    "adhan-prayer-unknown": ("prayer jumuah is not one of the five", "directives[0].trigger.prayer", lambda p: trig(p, 0).update(prayer="jumuah")),
    "adhan-prayer-missing": ("adhan trigger without prayer", "directives[0].trigger.prayer", lambda p: trig(p, 0).pop("prayer")),
    "app-usage-apps-missing": ("app_usage without apps", "directives[2].trigger.apps", lambda p: trig(p, 2).pop("apps")),
    "app-usage-apps-empty": ("apps is empty", "directives[2].trigger.apps", lambda p: trig(p, 2).update(apps=[])),
    "app-usage-app-not-package": ("app name instead of package name", "directives[2].trigger.apps", lambda p: trig(p, 2).update(apps=["TikTok"])),
    "app-usage-no-limit": ("neither opens+window_min nor continuous_min", "directives[2].trigger", lambda p: trig(p, 2).pop("opens") and trig(p, 2).pop("window_min")),
    "app-usage-both-limits": ("opens+window_min and continuous_min together", "directives[2].trigger", lambda p: trig(p, 2).update(continuous_min=20)),
    "app-usage-opens-without-window": ("opens without window_min", "directives[2].trigger", lambda p: trig(p, 2).pop("window_min")),
    "app-usage-window-without-opens": ("window_min without opens", "directives[2].trigger", lambda p: trig(p, 2).pop("opens")),
    "app-usage-opens-and-continuous": ("opens with continuous_min instead of window_min", "directives[2].trigger",
                                       lambda p: trig(p, 2).pop("window_min") and trig(p, 2).update(continuous_min=20)),
    "app-usage-opens-zero": ("opens 0", "directives[2].trigger.opens", lambda p: trig(p, 2).update(opens=0)),
    "app-usage-window-too-long": ("window_min 1441", "directives[2].trigger.window_min", lambda p: trig(p, 2).update(window_min=1441)),
    "app-usage-continuous-zero": ("continuous_min 0", "directives[2].trigger.continuous_min",
                                  lambda p: trig(p, 2).pop("opens") and trig(p, 2).pop("window_min") and trig(p, 2).update(continuous_min=0)),
    "app-usage-cooldown-too-large": ("cooldown_min 1441", "directives[2].trigger.cooldown_min", lambda p: trig(p, 2).update(cooldown_min=1441)),
    "bad-day-not-object": ("bad_day is a list", "bad_day", lambda p: p.update(bad_day=["fajr"])),
    "bad-day-keep-missing": ("bad_day without keep", "bad_day.keep", lambda p: p.update(bad_day={})),
    "bad-day-keep-not-list": ("bad_day.keep is a string", "bad_day.keep", lambda p: p.update(bad_day={"keep": "fajr"})),
    "bad-day-keep-not-an-id": ("bad_day.keep entry is not an id", "bad_day.keep[0]", lambda p: p.update(bad_day={"keep": ["Fajr prayer"]})),
    "fallback-not-object": ("fallback_plan is a list", "fallback_plan", lambda p: p.update(fallback_plan=[])),
    "fallback-directives-empty": ("fallback_plan.directives is empty", "fallback_plan.directives", lambda p: p["fallback_plan"].update(directives=[])),
    "fallback-directive-tier-five": ("a fallback directive has tier 5", "fallback_plan.directives[0].tier", lambda p: p["fallback_plan"]["directives"][0].update(tier=5)),
}

# name -> (what it shows, parser warning path prefix, mutation)
WARNINGS = {
    "unknown-trigger-type": ("a trigger type the app does not know", "directives[1].trigger.type", lambda p: d(p, 1).update(trigger={"type": "moon_phase", "phase": "full"})),
    "reserved-geofence": ("the reserved geofence trigger type", "directives[1].trigger.type",
                          lambda p: d(p, 1).update(trigger={"type": "geofence", "lat": 51.5, "lon": -0.12, "radius_m": 150})),
    "empty-trigger-type": ("an empty trigger type string", "directives[1].trigger.type", lambda p: trig(p, 1).update(type="")),
    "unknown-top-level-field": ("an unknown top-level field", "mood", lambda p: p.update(mood="hopeful")),
    "unknown-directive-field": ("an unknown field on a directive", "directives[1].colour", lambda p: d(p, 1).update(colour="blue")),
    "unknown-step-field": ("an unknown field on a step", "directives[0].steps[0].sound", lambda p: d(p, 0)["steps"][0].update(sound="chime")),
    "unknown-trigger-field": ("an unknown field on a time trigger", "directives[1].trigger.timezone",
                              lambda p: trig(p, 1).update(timezone="Europe/London")),
    "bad-day-missing": ("no bad_day at all", "bad_day", lambda p: p.pop("bad_day")),
    "title-too-long": ("a 41-character title", "directives[1].title", lambda p: d(p, 1).update(title="W" * 41)),
    "why-too-long": ("a 91-character why line", "directives[1].why", lambda p: d(p, 1).update(why="x" * 91)),
    "step-text-too-long": ("a 121-character step", "directives[1].steps[0].text", lambda p: d(p, 1)["steps"][0].update(text="y" * 121)),
    "tier2-nine-steps": ("a tier 2 checklist with 9 steps", "directives[2].steps",
                         lambda p: d(p, 2).update(steps=[{"text": f"Step {i}.", "min_seconds": 1} for i in range(1, 10)])),
    "days-repeated": ("a day listed twice", "directives[1].trigger.days", lambda p: trig(p, 1).update(days=["mon", "mon"])),
    "apps-repeated": ("an app listed twice", "directives[2].trigger.apps",
                      lambda p: trig(p, 2).update(apps=["com.zhiliaoapp.musically", "com.zhiliaoapp.musically"])),
    "bad-day-keep-repeated": ("an id listed twice in bad_day.keep", "bad_day.keep", lambda p: p.update(bad_day={"keep": ["fajr", "fajr"]})),
    "bad-day-keep-empty": ("an empty bad_day.keep", "bad_day.keep", lambda p: p.update(bad_day={"keep": []})),
}


def comments_everywhere(p):
    p["_readme"] = "Comments are allowed in every object."
    for obj in [p["bad_day"], p["fallback_plan"]] + p["directives"] + p["fallback_plan"]["directives"]:
        obj["_note"] = "a comment"
    for dd in p["directives"] + p["fallback_plan"]["directives"]:
        dd["trigger"]["_note"] = {"any": ["value", 1, None]}
        for s in dd["steps"]:
            s["_why"] = 42


def boundaries(p):
    d(p, 0)["id"] = "a" * 48
    d(p, 0)["steps"][0]["min_seconds"] = 0
    d(p, 0)["steps"][1]["min_seconds"] = 3600
    p["bad_day"]["keep"] = ["a" * 48]
    trig(p, 1).update(at="00:00", days=["mon", "tue", "wed", "thu", "fri", "sat", "sun"])
    trig(p, 2).update(opens=1000, window_min=1440, cooldown_min=0)
    p["fallback_plan"]["directives"][0]["trigger"]["at"] = "23:59"
    p["fallback_plan"]["directives"].append({
        "id": "0_fb-scroll",
        "tier": 1,
        "trigger": {"type": "app_usage", "apps": ["com.instagram.android"], "continuous_min": 1440, "cooldown_min": 1440},
        "steps": [{"text": "Close it.", "min_seconds": 1}],
        "why": "Enough.",
        "quiet": "ignore",
    })
    p["day_boundary"] = "23:59"


def nulls(p):
    p.update(generated_by=None, context=None, fallback_plan=None)
    d(p, 1)["title"] = None
    trig(p, 1)["days"] = None


def minimal(p):
    for k in ["day_boundary", "fallback_plan"]:
        p.pop(k)
    p["directives"] = [{
        "id": "water",
        "tier": 1,
        "trigger": {"type": "time", "at": "10:30"},
        "steps": [{"text": "Drink a glass of water.", "min_seconds": 10}],
        "why": "Small things, every day.",
        "quiet": "silent",
    }]
    p["bad_day"] = {"keep": ["water"]}


# name -> (what it shows, mutation)
VALID = {
    "base": ("the base plan every other fixture is made from", lambda p: None),
    "minimal": ("only the required fields and one directive", minimal),
    "expires-date-only": ("expires as a plain date (means that date at day_boundary)", lambda p: p.update(expires="2026-10-26")),
    "expires-with-seconds": ("expires with seconds", lambda p: p.update(expires="2026-10-26T04:00:00")),
    "continuous-limit": ("an app_usage trigger with continuous_min and a cooldown", lambda p: d(p, 2).update(
        trigger={"type": "app_usage", "apps": ["com.instagram.android", "com.google.android.youtube"], "continuous_min": 20, "cooldown_min": 60})),
    "boundaries": ("every number and time at the edge of its allowed range", boundaries),
    "nulls-for-optional": ("null for optional fields (same as leaving them out)", nulls),
    "comments-everywhere": ("'_' comment keys in every kind of object", comments_everywhere),
    "schema-link": ("a $schema link and generated_by/context", lambda p: p.update(
        {"$schema": "../../../../../spec/schema.json", "generated_by": "a test", "context": "Fixture."})),
}


def fmt(value, indent=0, prefix=0, width=120):
    """JSON with nested values kept on one line while they fit in [width] (prefix = key length on that line)."""
    one = json.dumps(value, ensure_ascii=False)
    if indent + prefix + len(one) <= width or not isinstance(value, (dict, list)) or not value:
        return one
    pad = " " * (indent + 2)
    if isinstance(value, dict):
        items = []
        for k, v in value.items():
            key = json.dumps(k) + ": "
            items.append(pad + key + fmt(v, indent + 2, len(key), width))
        return "{\n" + ",\n".join(items) + "\n" + " " * indent + "}"
    items = [pad + fmt(v, indent + 2, 0, width) for v in value]
    return "[\n" + ",\n".join(items) + "\n" + " " * indent + "]"


def write(folder, name, header, mutate):
    plan = copy.deepcopy(BASE)
    mutate(plan)
    out = dict(header)
    if "_readme" in plan:
        out["_readme"] = plan.pop("_readme")
    out.update(plan)
    os.makedirs(os.path.join(OUT, folder), exist_ok=True)
    with open(os.path.join(OUT, folder, name + ".json"), "w") as f:
        f.write(fmt(out) + "\n")


for folder in ["invalid", "valid-with-warnings", "valid"]:
    shutil.rmtree(os.path.join(OUT, folder), ignore_errors=True)
for name, (problem, expect, mutate) in INVALID.items():
    write("invalid", name, {"_problem": problem, "_expect_error_at": expect}, mutate)
for name, (shows, expect, mutate) in WARNINGS.items():
    write("valid-with-warnings", name, {"_shows": shows, "_expect_warning_at": expect}, mutate)
for name, (shows, mutate) in VALID.items():
    write("valid", name, {"_shows": shows}, mutate)
print(len(INVALID), len(WARNINGS), len(VALID))
