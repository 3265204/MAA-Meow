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

For comparison, a one-shot probe used [scrcpy's `--new-display` flag combination](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java) (`0xfdcb`) from a **non-Root shell process** while the phone remained asleep. MaaMeow's display 22 and the scrcpy-style display 23 both reported `displayGroupId=0`. The probe released both displays and did not launch an activity or inject input. The probe does not prove what would happen if either display were created before a fresh physical power press; the real MaaMeow task capture above supplies the power-key reproduction.

This differs from [AOSP's display grouping rule](https://android.googlesource.com/platform/frameworks/base/+/5182e4015cb1c320d5947604e8d91c812aeb7bdb/services/core/java/com/android/server/display/LogicalDisplayMapper.java), which assigns a new group when `FLAG_OWN_DISPLAY_GROUP` is present. The mismatch applies to the directly created virtual displays above; it does **not** mean that this phone cannot create an independent display group.

## VirtualDeviceManager group probe

On 2026-10-04, a separate one-shot probe used the framework's `VirtualDeviceManager` path from an ADB shell process. The probe created a temporary `com.android.shell` Companion Device association with the app-streaming profile, created a `VirtualDevice` with `LOCK_STATE_ALWAYS_UNLOCKED`, and attached a 128×128 trusted virtual display. It did not launch an Activity, inject input, issue a wake/unlock command, or change a system power setting. The display, virtual device, and association were removed after each run.

| Probe | Display | Actual group | State | Evidence |
| --- | --- | --- | --- | --- |
| First | 24 | 5 | ON for five 1-second samples | `VirtualDeviceGroupProbe` output |
| Second | 25 | 6 | ON for fifteen 1-second samples | `dumpsys display` showed Group 0 with display 0 and Group 6 with display 25 |
| Third | 26 | 7 | ON for fifteen 1-second samples | Group 0/display 0 and Group 7/display 26 existed simultaneously; `dumpsys display` reported physical `mState=OFF`, `mScreenState=OFF`, and virtual `mScreenState=ON` |

The second and third runs' output is in `../device-evidence-20261004/vdm-group-probe.txt` and `../device-evidence-20261004/vdm-group-probe-power.txt` in this workspace. `dumpsys power` reported global `mWakefulness=Awake` while the separate virtual device was ON, while the physical display remained OFF. After cleanup, `dumpsys display` had only Group 0, `mWakefulness=Dozing`, the temporary association was absent, and KernelSU Root still returned `uid=0`.

This proves a nonzero group can be created on this One UI build through `VirtualDeviceManager`, even though MaaMeow's direct `DisplayManager.createVirtualDisplay` path remains in Group 0. It does **not** yet prove that a game Activity can remain rendering in that group across a fresh physical power-key press. The next functional step is a VDM-backed MaaMeow display lifecycle and a real task test; changing direct virtual-display flags again is unlikely to address the observed grouping failure.

The existing **Screen Off** action is preserved. It turns off the physical panel while keeping the system awake; it does not itself request secure Keyguard. A genuine physical power key press invokes system sleep and turns this device's group-0 virtual display OFF. Code that merely re-pins the game task or restarts MaaCore cannot restore rendering while the display remains OFF.

Android's `VirtualDeviceManager.createVirtualDevice` requires a Companion Device Manager association owned by the caller. The probe used an association solely during the test and removed it. A production path must decide how to obtain and manage an association without leaving the app in a broken state; it must also reconnect the capture and input bridge to the VDM-created display ID.
