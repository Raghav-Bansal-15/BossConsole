# Filesystem service boundaries

Operations use held native directory handles rather than re-resolving a checked path.
This is an application boundary, not a same-user OS sandbox.

## Watching and renaming

Recursive watches retain authorized descendant handles until the collector closes.
On Linux and macOS, registrations follow renamed directories and update their visible
paths. On Windows, NTFS can refuse a parent directory rename while descendant
notification handles are open. Stop the affected watch before renaming and reconnect
it afterward; a refused rename does not stop the existing watch or modify files.
Cancellation closes the handles and permits the rename again.

This follows Microsoft's [directory rename rules](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/ns-ntifs-_file_rename_information#remarks).
The cross-platform regression checks both live-watching rename behavior on POSIX and
Windows refusal, continued delivery, and rename after cancellation.
