# tick.txt

plain-text tasks, notes, and exact reminders for android.

everything lives as plain text files in a folder you choose on your device (`todo.txt`, `done.txt`, `tick.rules.txt`, and `notes/`). there is no proprietary database as the source of truth, no cloud account, and no network permission. any local search index or snooze state is disposable and rebuildable from the files.

## stack and architecture

| layer | implementation | why |
| :--- | :--- | :--- |
| **platform** | native android (kotlin 2.0 + jetpack compose, minSdk 26, targetSdk 35) | direct access to `AlarmManager.setAlarmClock`, `setExactAndAllowWhileIdle`, full-screen lock-screen intents, storage access framework (`DocumentFile`), and boot/timezone receivers without cross-platform bridge overhead. |
| **storage** | atomic temp-file swap (`<file>.tmp` -> `fsync` -> atomic rename) + 3-way line merge | prevents file truncation on crash and merges concurrent external edits from syncthing, dropbox, or a text editor without silent data loss. |
| **index & cache** | in-memory state + rebuildable sqlite/room fts4 cache + `.tick/alarm-state.json` | plain text files remain the sole source of truth. `.tick/alarm-state.json` persists active snooze timestamps across app kills and reboots so snoozing never mutates `due:` in `todo.txt`. |
| **ui & aesthetic** | monospace typography + ascii box-drawing structure (`plaintextsports.com` aesthetic) | zero decorative cards, zero elevation shadows, high-density tabular alignment, and wcag aa calibrated **dracula** (dark) and **alucard** (light) themes. |

## storage layout

when you pick a folder via android's storage picker, **tick.txt** reads and writes this layout:

```text
<your-chosen-folder>/
├── todo.txt              # active tasks (strict todo.txt specification)
├── done.txt              # archived completed tasks
├── tick.rules.txt        # human-editable natural-language rules and defaults
├── notes/                # plain text (.txt) and markdown (.md) notes
│   ├── architecture.md
│   └── packing-list.txt
└── .tick/                # ephemeral runtime state (safe to ignore or delete)
    └── alarm-state.json  # active snooze timestamps, snooze counts, ack flags
```

## todo.txt compatibility and extensions

**tick.txt** follows the [official todo.txt specification](https://github.com/todotxt/todo.txt) strictly:
- one task per line; completed tasks start with `x YYYY-MM-DD `.
- optional priority `(A)` through `(Z)` at the start of an incomplete line (preserved as `pri:A` when completed so unchecking restores it).
- optional creation date `YYYY-MM-DD` immediately after priority.
- `+project`, `@context`, and `key:value` metadata tags anywhere in the line body.
- **lossless round-tripping:** unmodified lines are written back verbatim. when a task is edited in the UI, only the targeted token is updated in place; unknown `key:value` tags, URLs (`https://...`), and custom tokens round-trip untouched.

### due dates, times, and recurrence

| tag | format | description |
| :--- | :--- | :--- |
| **`due:`** | `due:YYYY-MM-DD` | standard due date compatible with all todo.txt tools. |
| **`duetime:`** | `duetime:HH:mm` | 24-hour due time (`00:00`–`23:59`). stored as a separate tag so strict third-party parsers expecting `due:YYYY-MM-DD` never break. (`due:YYYY-MM-DDTHH:mm` is also accepted on read.) |
| **`t:`** | `t:YYYY-MM-DD` | threshold (defer) date; task is hidden from the "due / active" filter until this date. |
| **`dur:`** | `dur:<N>m` | optional task duration in minutes extracted from ranges like `2 to 3pm` or `for 45 min`. |
| **`rec:`** | `rec:[+]<N><d\|b\|w\|m\|y>` | recurrence rule. **flexible** (`rec:1w`, `rec:2m`, `rec:3b`) advances from the completion date; **strict** (`rec:+1w`, `rec:+1m`) advances from the previous `due:` date. `b` counts weekdays (mon–fri). |
| **`note:`** | `note:<filename>` | links a task to a file inside `notes/` (e.g. `note:architecture.md`), creating a bidirectional backlink in the note view. |

## reminder tag syntax (`rem:`)

reminders live directly inside the `todo.txt` line as one or more `rem:` tags. other todo.txt clients treat them as standard `key:value` pairs and ignore them.

```text
rem:<trigger>[/<type>][/<flags>]
```

### 1. `<trigger>` (required)
- **relative to due timestamp:** `-<N><m|h|d|w>` or `0m`
  - `rem:-15m` — 15 minutes before due time
  - `rem:-2h` — 2 hours before due time
  - `rem:-1d` — 1 day before due time (for date-only tasks without `duetime:`, fires at the configured `morning` default, `09:00`)
  - `rem:0m` — exactly at due time
- **absolute date and time:** `YYYY-MM-DD@HH:mm`
  - `rem:2026-11-03@09:00` — fires on 2026-11-03 at 09:00 local time regardless of `due:`
- **clock time anchored to due date:** `HH:mm`
  - `rem:07:00` — fires at 07:00 on the task's `due:` date (or today/tomorrow if the task has no `due:`)

### 2. `/<type>` (optional, defaults to `n`)
- `/n` — **notification:** standard high-priority notification with quick-snooze and `[x mark done]` action buttons.
- `/a` — **alarm:** loud, persistent alarm scheduled via `AlarmManager.setAlarmClock`, wakes the device over Doze, presents a full-screen lock-screen UI (`FullScreenAlarmActivity`), and can ring over silent/DND when permitted.
- `/s` — **silent:** silent status notification / badge with no sound or vibration.

### 3. `/<flags>` (optional)
- `s` — sound enabled (default on for `/n` and `/a`)
- `v` — vibration enabled (default on for `/n` and `/a`)
- `r` — repeat ringing/re-alerting every 60 seconds until acknowledged
- `m<N>` — maximum snooze count for this reminder (e.g. `m3` caps snoozing at 3 times)

### examples
- `2026-11-01 coffee with mum +family @home due:2026-11-03 duetime:14:00 rem:-15m/n`
- `(A) 2026-11-01 board flight +travel @airport due:2026-11-05 duetime:06:30 rem:-1d/n rem:-2h/a/svrm2`

### snooze and mark-done behavior
- **snoozing never edits `due:`:** tapping `+5m`, `+15m`, `+1h`, `+tomorrow morning`, or picking a custom time writes the snoozed epoch timestamp and incremented `snoozeCount` to `.tick/alarm-state.json`. your `todo.txt` file is not rewritten on snooze, preventing unnecessary sync churn.
- **mark done from notification:** tapping `[x mark done]` on a firing notification or lock-screen alarm atomically marks the line `x YYYY-MM-DD ...` in `todo.txt`, spawns the next recurring line if `rec:` is present, and clears all active snoozes for that task.

## natural-language capture (smart add)

the smart-add parser (`SmartAddParser.kt`) is a deterministic, multi-pass offline grammar that runs in **under 1 ms** per input with zero network calls.

### pipeline order and precedence
1. **quick note mode:** starting an input with `note: ...` switches capture to create or append to a plain-text/markdown note in `notes/`.
2. **explicit token lock (highest precedence):** raw todo.txt syntax typed by the user (`(A)`, `+project`, `@context`, `due:2026-11-03`, `duetime:14:00`, `t:2026-11-01`, `rec:+1w`, `rem:07:00/a`) is locked first and never overridden by inferred values.
3. **title protection heuristic:** quoted strings (`"..."`), possessive proper nouns (`meeting at Tuesday's cafe`, `May's notebook`), and non-calendar idioms (`may I ask`, `march madness`) are masked so they are never stripped as dates.
4. **reminder intents:** `alarm at 7am` (`rem:07:00/a`), `remind me 30 min before` (`rem:-30m/n`), `notify me the day before` (`rem:-1d/n`), `don't remind me` (suppresses automatic default reminder).
5. **recurrence:** `daily`, `every day`, `every Tuesday`, `every 2 weeks`, `monthly on the 1st` -> `rec:1d`, `rec:1w`, `rec:2w`, `rec:1m`.
6. **ranges and durations:** `2 to 3pm` (`duetime:14:00 dur:60m`), `for 45 min` (`dur:45m`), `for 2 hours` (`dur:120m`).
7. **dates and times:** `2pm`, `14:00`, `noon`, `midnight`, `tonight`, `after lunch`, `today`, `tomorrow`, `tomorrow morning`, `Tuesday`, `next Tuesday`, `in 3 days`, `this weekend`, `next week`, `end of month`, `the 15th`, `Nov 15`.
8. **priority keywords:** `urgent`, `asap`, `!!` -> `(A)`; `important`, `!` -> `(B)`.
9. **context phrases & project keywords:** prepositional phrases (`at home` -> `@home`, `at the office` -> `@office`, `at the shop` -> `@errands`, `on phone` -> `@phone`) are extracted and stripped from the title; project trigger words (`mum` -> `+family`, `invoice` -> `+work`) attach the project while keeping the word in the title.

### documented ambiguity rules

| input pattern | resolution rule |
| :--- | :--- |
| **bare weekday** (`Tuesday`) | resolves to the next occurrence of that weekday. if today is already Tuesday, resolves to **today** only if a due time is specified and still in the future; otherwise resolves to **next Tuesday (+7 days)**. |
| **`next <weekday>`** (`next Tuesday`) | always resolves to the occurrence in the following 7-day cycle (`+1` to `+7` days ahead). |
| **bare clock time** (`at 2pm`, `14:00`) | resolves to **today** if that clock time is still in the future; otherwise rolls over to **tomorrow** at that time. |
| **named time of day** (`morning`, `afternoon`, `evening`, `tonight`, `after lunch`) | uses the configurable defaults in `tick.rules.txt` (`09:00`, `14:00`, `19:00`, `20:00`, `13:00`). |
| **ordinal day** (`the 15th`) | resolves to the 15th of the current month if `>= today`, otherwise the 15th of next month (clamped to month length for 29th–31st). |
| **ambiguous words** (`may`, `march`) | treated as month names **only** when paired with a day number (`May 5`, `15 March`). bare `may` or `march` in prose is left untouched in the task title. |

## `tick.rules.txt` format

stored in the root of your selected folder, `tick.rules.txt` is a plain-text configuration file you can edit in any text editor or update from the app when you tweak a smart-add chip:

```ini
# tick.rules.txt — plain text, human-editable rules for tick.txt

[defaults]
timed_reminder = -15m/n
date_only_reminder = 09:00/n
morning = 09:00
afternoon = 14:00
evening = 19:00
tonight = 20:00
after_lunch = 13:00
snooze_presets = 5m, 15m, 1h, tomorrow_morning
max_snoozes = 5

[projects]
mum, mom, dad, sister, brother, parents, family -> +family
invoice, client, standup, deploy, sprint -> +work
milk, bread, eggs, groceries -> +groceries
dentist, doctor, gym -> +health

[contexts]
"at home" -> @home
"at the office", "in the office", "at work" -> @office
"at the shop", "at the store" -> @errands
"on phone", "by phone", "on a call" -> @phone
```

## notes and task linking

- **files:** each note is an individual `.md` or `.txt` file inside `notes/` with a clean, human-readable filename (e.g. `notes/architecture.md`, `notes/packing-list.txt`).
- **markdown & plain text:** `.md` files offer a rendered monospace preview (`#` headers, lists, code blocks, inline links) and a raw plain-text editor. `.txt` files stay strictly unformatted plain text.
- **task <-> note links:** adding `note:architecture.md` to any task in `todo.txt` creates a clickable badge on the task and lists that task under `backlinks` inside `architecture.md`.
- **note reminders:** notes with reminders store a single plain-text directive on line 1 (`<!-- tick: rem:2026-11-04@09:00/n -->` in `.md` or `@tick rem:2026-11-05@07:30/a/svr` in `.txt`). notes without reminders have zero metadata lines.

## logo and visual identity

- **mark (`t█`):** a hand-authored SVG built on a 24x24 geometric grid pairing a letterpress lowercase `t` (with a deliberate top-left chamfer and notched left crossbar) and a solid terminal block cursor (`█`) resting at the baseline.
- **flat 2-color palettes:**
  - **dracula (dark):** background `#282A36`, foreground `#F8F8F2`, cursor `#BD93F9`.
  - **alucard (light):** background `#FFFBEB`, foreground `#1F1F1F`, cursor `#644AC9`.
- **deliverables in `assets/logo/` and `app/src/main/res/`:**
  - `assets/logo/tick-logo-dracula.svg` & `assets/logo/tick-logo-alucard.svg` (full horizontal lockups)
  - `assets/logo/tick-icon-dracula.svg` & `assets/logo/tick-icon-alucard.svg` (app icon marks)
  - `assets/logo/tick-icon-adaptive-foreground.svg` & `assets/logo/tick-icon-adaptive-background.svg`
  - `assets/logo/tick-icon-monochrome.svg` (single-color silhouette for android 13+ themed icons)
  - `app/src/main/res/drawable/ic_launcher_*.xml` & `mipmap-anydpi-v26/ic_launcher.xml`

## github actions ci (`.github/workflows/android-build.yml`)

pushing to `main`/`master`, opening a pull request, or triggering `workflow_dispatch` automatically:
1. provisions **jdk 17 (temurin)**, **android sdk**, and **gradle 8.9** via `gradle/actions/setup-gradle@v4`.
2. runs the complete **junit 5** unit test suite (`gradle testDebugUnitTest --stacktrace`) covering `todo.txt` lossless round-tripping, recurrence, reminder scheduling, and the offline natural-language parser.
3. compiles both debug and release apks (`gradle assembleDebug assembleRelease`) and uploads `tick-txt-debug-apk`, `tick-txt-release-apk`, and the html unit test report as downloadable workflow artifacts.
