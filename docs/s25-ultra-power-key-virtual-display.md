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

This proves a nonzero group can be created on this One UI build through `VirtualDeviceManager`, even though MaaMeow's direct `DisplayManager.createVirtualDisplay` path remains in Group 0. It does **not** yet prove that a game Activity can remain rendering in that group across a fresh physical power-key press. Changing direct virtual-display flags again is unlikely to address the observed grouping failure.

The existing **Screen Off** action is preserved. It turns off the physical panel while keeping the system awake; it does not itself request secure Keyguard. A genuine physical power key press invokes system sleep and turns this device's group-0 virtual display OFF. Code that merely re-pins the game task or restarts MaaCore cannot restore rendering while the display remains OFF.

Android's `VirtualDeviceManager.createVirtualDevice` requires a Companion Device Manager association owned by the caller. The initial probe used an association solely during the test and removed it. The implementation below now owns this association for the lifetime of its VDM display.

## MaaMeow implementation and device checks

The experimental branch now detects when the existing `DisplayManager` virtual display actually lands in group 0. On Android 14+ it releases that display and creates a VDM-owned display using the same native capture `Surface`. The VDM's `LOCK_STATE_ALWAYS_UNLOCKED` applies to the virtual device; the code does not send a power key, wake, unlock, or PIN event. The Screen Off action remains a separate fallback. A short-lived `com.android.shell` companion association is removed when the display stops; a stale association from a prior process death is reclaimed at the next start. If VDM creation fails, the code logs the failure and falls back to the old display path.

The Root service runs with UID 0, but this One UI build rejects `createVirtualDevice` when the call claims `com.android.shell` from UID 0: `Package name com.android.shell does not belong to calling uid 0`. The implementation temporarily changes the Root service **main thread's** effective UID to shell (2000) for the VDM Binder calls and restores it in `finally`. The real and saved UID stay Root, and normal capture/input calls continue under UID 0. The same VDM path also works directly from shell UID 2000, so VDM itself does not require Root on this phone. An ordinary app UID cannot use this silent shell association path; Shizuku must actually provide shell identity for a non-Root deployment.

The updated `.vdtest` APK was installed and tested while the physical display was already asleep. Root created display 36 in group 11 for 10 seconds; the same process reported effective UID 0 after creation and release. During that run `dumpsys display` showed Display 0 OFF and the VDM display ON. The evidence is `../device-evidence-20261004/vdm-root-lifecycle.txt`.

A debug-only animated Activity was then launched on the VDM display, without starting the game. Root created display 38/group 12; `dumpsys activity` reported the Activity resumed on display 38 while Display 0 was OFF, and the native bridge frame count grew from 0 to 172. In a separate Root run, `InputControlUtils.down/up` returned true and the Activity logged both touch events at (100,100) on display 40/group 13. A shell UID 2000 run independently created display 42/group 14, captured frames, and delivered the same touch pair. Evidence: `../device-evidence-20261004/vdm-root-rendering.txt`, `../device-evidence-20261004/vdm-root-input.txt`, and `../device-evidence-20261004/vdm-shell-input.txt`. Every run removed its association and display; afterward only group 0 remained, `mWakefulness=Dozing`, and KernelSU Root still returned UID 0.

These tests establish independent display power, Activity rendering, native capture, and targeted input while the physical panel is OFF. They have **not** yet tested an active Arknights/MaaCore task across a fresh physical power-key press, or confirmed a PIN-required Keyguard state on this phone. That remains the end-to-end acceptance test.

## Follow-up after a real MaaMeow task failed

The first VDM implementation passed the standalone probes above but failed in a real `.vdtest` task at 20:16 on this phone. Its Root service handled the app's Binder request on a Binder worker thread. The VDM permission check still saw caller UID 0, rejected the `com.android.shell` package, and MaaMeow fell back to a group-0 display. During the following screen-off intervals, that display also became OFF. MaaCore later reported `StartUp TaskChainError`. The standalone probes had invoked the VDM code on the process main thread, masking this difference. The original capture is `../device-evidence-20261004/black-screen-followup-logcat.txt`.

The fix dispatches VDM creation and teardown to the privileged service's main Looper before temporarily changing its effective UID. A debug-only broadcast probe then called `startVirtualDisplay()` through the **real app-to-Root Binder connection**. At 20:27, the service created display 50 in group 17, with `FLAG_TRUSTED`, `FLAG_ALWAYS_UNLOCKED`, and `FLAG_OWN_FOCUS`; `dumpsys display` showed the built-in panel OFF while display 50 was ON. The Root service reported `Using VDM-backed independent display group`, the probe stopped and removed the display after eight seconds, and `su -c id` still returned UID 0. `dumpsys window policy` reported `KeyguardServiceDelegate showing=true`. Evidence: `../device-evidence-20261004/vdm-real-binder-followup.txt`.

This validates the corrected Binder path and independent display power while the panel is off. A real Arknights task with MaaCore across a fresh physical power-key press has not yet been rerun on this APK, so the final end-to-end behavior remains unconfirmed.
