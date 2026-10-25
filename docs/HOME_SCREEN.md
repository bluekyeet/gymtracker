# Home Screen Specification

Repository: https://github.com/bluekyeet/gymtracker

This document expands section 4.1.1 of `DESIGN.md`. For the home screen, if the two differ, this file wins.

> **Implementation status.** Built: date + active split header, giant today's-day card, exercise preview, full-width START WORKOUT, all-split-days list with per-day start, in-progress banner with Resume, and the one-workout-at-a-time dialog. Not built: the week strip, the marked/done/missed states, rest-day marking, and showing last session's numbers per exercise. `split_days` stores `weekdays` (a list), not a single `weekday` — a day may fall on several days of the week.

## 1. Purpose

The home screen is the first thing you see. Its job is to get you into today's workout with one tap. It is a full-screen design built around the current weekday, a very large Start workout button, and a clear fallback when there is no workout today.

It works fully offline and reads only from the local database.

## 2. Definitions

| Term | Meaning |
| --- | --- |
| Today | The current date in the device's time zone |
| Week | Monday to Sunday, containing the selected date |
| Active split | The one split marked `is_active` |
| Scheduled day | A split day in the active split whose `weekdays` list contains today's ISO weekday (1 = Mon … 7 = Sun) |
| Selected day | The day chip currently chosen in the week strip. Defaults to today |
| In progress | A workout session with a start time and no end time |
| Completed | A workout session with an end time |
| Done this week | A completed session linked to that split day, started within the current week |
| Missed | A scheduled day earlier in the current week than today that is not done this week |
| Rest day | A selected weekday with no scheduled day in the active split |

Notes on these rules:

- Today's own scheduled day is never "missed" until the day has passed.
- Future days are never missed.
- Split days with no weekday are never scheduled and never missed. They are only reachable through "Start another day".
- A workout started on a different day than scheduled still counts as that split day being done this week.

## 3. Screen states

The screen shows one of these states. The week strip is always visible at the top.

| State | When | Main content |
| --- | --- | --- |
| A. Workout day | Selected day has a scheduled day, not started, not done | Day header, exercise preview, giant Start workout button |
| B. In progress | An unfinished workout exists | Same layout, button reads Resume workout |
| C. Completed | Selected day is done this week | Day header, short summary of that session, Do it again action |
| D. Rest day | Selected weekday has no scheduled day | Stats view and Start another day list |
| E. No active split | No split exists or none is active | Prompt to create a split |

A workout in progress takes priority everywhere. If it belongs to a different day than the one selected, a banner at the top says "Workout in progress: Legs" with a Resume button, in every state.

## 4. Layout

### 4.1 Week strip

- Seven chips, Monday to Sunday, each showing the short day name and the date number.
- Today is highlighted. The selected day has a stronger outline or fill.
- A small dot means that day's workout is done this week.
- A "Missed" mark (for example a warning color dot) appears on a missed day.
- Tapping a chip selects that day. Selecting a day other than today shows "Viewing Wednesday" and a "Back to today" action under the strip.
- The strip does not scroll. The week is always the current week.

### 4.2 Day header

- The selected day's name in very large type, such as "Monday".
- The full date in smaller type.
- The workout name for that day, such as "Push", in a clearly different style.

### 4.3 Exercise preview (state A and B)

- A compact list of the day's exercises, in order.
- Each row has the exercise name and the best set from the last session of that exercise, such as "80 kg × 8".
- If the list is long, it scrolls inside its own area or shows the first few with "and 4 more". It must never push the button off screen or shrink it below its minimum size.

### 4.4 Start workout button (state A and B)

- Fills all the remaining vertical space below the preview and spans the full width.
- Minimum height is 40 percent of the screen height.
- Label "Start workout" in large text. In state B the label is "Resume workout".
- Sits in the lower part of the screen so a thumb reaches it without stretching.
- One tap starts or resumes. No confirmation dialog.

### 4.5 Completed view (state C)

- Shows the finished session: duration, number of sets, total volume.
- A "Do it again" button, smaller than the Start button, starts a new session for the same day.
- A "View details" action opens the session in history.

### 4.6 Rest day view (state D)

The big button is replaced by two blocks.

**Stats**

- Workouts completed this week.
- Total volume this week compared with last week, shown as a percentage change. If last week had no volume, show the number without a percentage.
- Last workout: name and date.
- Recent personal records from the last 30 days, up to three.
- Body weight trend over the last 30 days as a small line, only when at least two entries exist.

**Start another day**

- A list of the active split's workout days.
- Missed days from this week come first, each with a "Missed" label.
- Then the remaining days, in weekday order, then days with no weekday.
- Each row shows the day name, the workout name and the number of exercises.
- Tapping a row starts that workout immediately.

### 4.7 No active split (state E)

- A short message and a button to create a split.
- The stats block still shows if there is any history.

## 5. Behavior

### 5.1 Tracking the current day

- The screen reads today's date from the device clock and time zone.
- It re-evaluates when the app returns to the foreground, when the date changes (a date-change broadcast), and when the time zone changes.
- On app open and on a date change, the selected day resets to today.
- If the date changes while the screen is open, the strip, header and state update without a restart.

### 5.2 Starting a workout

Starting any workout does the following in one local transaction:

1. Creates a `workout_sessions` row with today's date as the start time and the chosen split day.
2. Copies the day's exercises into `workout_exercises`, with names and order at that moment.
3. Writes the change to the outbox.
4. Opens the workout screen.

Starting a day other than the scheduled one:

- Is recorded on today's date.
- Counts as that split day being done this week, so the missed mark clears.
- Does not move or change any other day in the schedule.

### 5.3 Only one workout at a time

- If a workout is in progress, Start is replaced by Resume in every state.
- Choosing a different day from the rest day list while one is in progress asks: "Finish Push first?" with Resume, Finish and discard options. It never silently starts a second workout.

### 5.4 Stale in-progress workouts

- If an unfinished workout started on a previous date, the app asks on open: "Finish or discard Monday's Push?"
- Finish ends the session at the time of its last logged set (or its start if there are none). Discard soft-deletes the session and its sets.
- A workout that crosses midnight keeps its original start date.

### 5.5 Selecting other days

- Selecting a future day with a workout allows starting it early. It is recorded on today's date.
- Selecting a past day that is missed allows starting it. It is recorded on today's date, not the past date.
- Selecting a day that is already done shows state C.

### 5.6 First launch

- No split: state E.
- A split exists but nothing is logged: state A or D with empty stats showing zeros and "No workouts yet".

## 6. Data queries

Written for the local database. The same logic must be shared in `shared/` where it does not need database access.

- **Selected day's scheduled day:** the active split's `split_days` row where `weekday` matches the selected weekday and `deleted_at` is null.
- **Done this week:** exists a `workout_sessions` row with that `split_day_id`, a non-null end time, a start time in the current week, and `deleted_at` null.
- **Missed:** scheduled, weekday number below today's weekday number, not done this week.
- **In progress:** a session with `ended_at` null and `deleted_at` null. At most one may exist.
- **Last best set per exercise:** from the most recent completed session containing that exercise, the set with the highest estimated 1-rep max, using the formula in `DESIGN.md`.
- **Week boundaries:** Monday 00:00 to Sunday 23:59:59 in the user's time zone.

The pure date and week logic (week boundaries, missed rule, state selection) goes in `shared/` so it is testable without Android.

## 7. Watch version

The Wear OS home screen follows the same state and missed-day rules with a smaller layout.

- Opens on today's day name and workout name.
- A full-width Start workout (or Resume workout) button takes most of the screen.
- A swipe or tap on the day name opens a short list of days to pick another one.
- On a rest day it shows the workout count for the week and the Start another day list. It does not show graphs.
- The in-progress banner becomes a Resume button at the top.

## 8. Accessibility and polish

- The Start button text and the day chips meet standard contrast and touch target sizes.
- State is never shown by color alone. Missed and done use an icon or label as well.
- Large font settings must not hide or clip the Start button.
- Landscape and small screens keep the button at least a comfortable minimum size, with the preview area scrolling instead.

## 9. Tests

Unit tests in `shared/`:

- Week boundaries on Monday start, across month and year edges.
- Missed rule: today is not missed, future days are not missed, unscheduled days are never missed.
- State selection for each of A to E, including in-progress taking priority.
- Weekly stats and the percentage change when last week is zero.

Android tests:

- Opening the app on each weekday shows the right selected day.
- The date changing while the app is open updates the screen.
- Starting a missed day records today's date and clears the missed mark.
- A second workout cannot be started while one is in progress.
- A stale in-progress workout prompts on open.
- Killing the app during a workout and reopening shows Resume.
- Everything works in airplane mode.

## 10. Open items

- Whether the missed marker should also appear for the previous week at the start of a new week. Current design shows only the current week.
- Whether a user can schedule more than one workout on the same weekday. Current design allows one per weekday per split.
