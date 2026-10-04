# S25 Ultra: physical power key stops the background virtual display

Observed on SM-S9380, Android 16 / One UI 8.5 with MaaMeow 0.22.0 using the Root backend. The user started a real task and pressed the physical power key. No reboot, unlock, input injection, or system setting change was part of this capture. Full logs are in the workspace's `device-evidence-20261004/manual-lock-run/` directory.

| Device time | Observation |
| --- | --- |
| 17:45:18.107 | MaaCore started a task using virtual display 10. |
| 17:45:19.337 | Physical power key sent the system to sleep. |
| 17:45:19.730–19.739 | DisplayManager switched display 10 from ON to OFF; WindowManager added a `Display-off` sleep token. |
| 17:45:19.934–20.635 | Game task stayed on display 10, but its Activity became STOPPED and the Unity surface was destroyed. |
| 17:46:55.053 | MaaCore reported `StartUp TaskChainError`; later captured frames were black. |

The game process, MaaMeow process, and Root service stayed alive. The virtual display also stayed allocated, but became `DOZE_SUSPEND` with no content. This is a display power and activity lifecycle failure, not task migration, process death, or Core loading.

The requested virtual display flags include `TRUSTED`, `OWN_DISPLAY_GROUP`, `ALWAYS_UNLOCKED`, and `OWN_FOCUS`. The actual display group reported on this device is **0**, the same group as the primary display. Removing `DEVICE_DISPLAY_GROUP` did not change that. A separate private display with `FLAG_NEVER_BLANK` was also in group 0 and OFF while the phone slept.

The existing **Screen Off** action is preserved. It turns off the physical panel while keeping the system awake; it does not itself request secure Keyguard. A genuine physical power key press invokes system sleep and turns this device's group-0 virtual display OFF. Code that merely re-pins the game task or restarts MaaCore cannot restore rendering while the display remains OFF.

An independent VirtualDevice display group might need a different lifecycle, but Android's `VirtualDeviceManager.createVirtualDevice` requires a Companion Device Manager association owned by the caller. Neither MaaMeow nor `com.android.shell` currently has one on this phone. This branch does not create an association or modify device security settings.
