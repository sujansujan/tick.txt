<!-- tick: rem:2026-11-04@09:00/n -->
# tick.txt storage notes

everything lives in plain text files inside the user-selected folder:

- `todo.txt` and `done.txt` follow the official todo.txt spec.
- `tick.rules.txt` holds natural-language capture defaults and keyword mappings.
- `.tick/alarm-state.json` stores active snooze timestamps so snoozing never dirties `todo.txt`.
