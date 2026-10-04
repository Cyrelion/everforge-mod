# Player command audit retention

The mod records submitted in-game commands from permission level 2 and above.
Console/RCON and ordinary player commands remain excluded. This change does
not alter who can submit commands or access the Web audit view.

The writer owns daily UTC rotation and timestamp-based 30-day expiry:

- Current file: `everforge/audit/commands.jsonl`.
- Closed daily files: `commands-YYYY-MM-DD.jsonl`; a numeric suffix prevents
  replacing an existing archive for the same day.
- Retention uses each JSONL entry's `timestamp`, not a file's modification date.
- Old records in the previous single-file log are also processed.
- Maintenance runs when the Minecraft server starts and hourly while running,
  even without commands. An append also checks for UTC day rollover.
- Appends, rotation and pruning share the same store lock. Rewrites use a
  same-directory temporary file and atomic replacement. Cleanup failure does
  not suppress a new audit event if the active file remains writable.
- Unknown/corrupt timestamps remain for manual review. Symlinks, unrelated
  filenames and other server directories are not pruned. Closed empty archives
  are removed. No backup copies are changed.
- During downtime no cleanup runs; restart catches up. With an uninterrupted
  server, expiry is processed at the next hourly run after 30 days.

Everforge-Web must read both the active file and known daily archives, keeping
the newest 200 events in its audit response. Its Minecraft mount stays read-only.

Activation requires the updated Everforge mod JAR in a tested server release.
Merging this PR or updating Everforge-Web alone does not activate the mod-side
retention. Use the normal candidate/TEST/GHCR/LIVE release flow; do not replace
the running server's mod files manually. Existing retention statements must
reflect whether that release has actually reached LIVE.

Regression checks: `bash scripts/test-command-audit.sh` (JDK 17+ for the isolated
store test; the actual mod build continues to require JDK 21). CI runs these
checks and then the complete NeoForge Gradle build. Use a temporary TEST world
to verify one privileged command, server restart, and Web archive display.

The standalone regression executable lives under `scripts/tests`, outside the
Gradle JUnit test source set. The required CI regression step invokes it explicitly
and fails the workflow on any failed assertion. Gradle test discovery remains
unchanged for future framework-based tests.
