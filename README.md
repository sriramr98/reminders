# Call Reminder

Personal Android meeting alarms for a OnePlus 13. Milestone 1 is a runnable native Kotlin prototype; Calendar integration is the next milestone.

## Run

Open this folder in Android Studio, or use JDK 17+ and an installed Android SDK:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `sdk.dir` in your own `local.properties` if Android Studio has not done so. This project uses AGP 9.0.1, Gradle 9.1.0, compile/target SDK 36, and Android 12+ (API 31).

On the device, open **Call Reminder** and enable notifications, alarms & reminders, and full-screen reminders using the setup buttons. Tap **Start two test calls**, then lock the screen. Reminders fire after 10 and 50 seconds, each ringing for at most 30 seconds. **Dismiss this call** leaves the second reminder in place; **Skip remaining reminders** cancels both. Starting a new sample replaces the existing pair.

**Schedule sample meeting in 20 min** uses the actual 15-minute and 2-minute offsets: it rings in 5 and 18 minutes. No real meeting link is attached to samples.

Android decides whether a full-screen notification opens the screen; while unlocked it may show a heads-up notification instead. Tap that notification to open the call screen. Audibility follows alarm volume and Do Not Disturb settings. Force-stopping the app prevents reminders until the app is opened again; permissions, a powered-off phone, or manufacturer battery controls can also prevent delivery. The OnePlus still needs physical-device validation.

## Implementation

- `Reminders`: persistent local occurrences, independent exact `AlarmManager.setAlarmClock` alarms, unique IDs across replacements, cancellation, and rescheduling after reboot/time changes.
- `AlarmReceiver`: looks up a still-scheduled reminder and starts `RingService`.
- `RingService`: foreground notification, full-screen intent, looping ringtone using alarm audio attributes, vibration, bounded wake lock, timeout, and missed-reminder notification.
- `ReminderActivity`: lock-screen-capable reminder screen with independent dismissal and cancellation of the pair.
- `MainActivity`: permission readiness, sample controls, and reminder status.

The prototype uses native Android views with no UI framework dependency. This keeps the first device-delivery experiment small; the scheduler and ringing service can remain when the UI moves to Compose.

## Verification

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation tests cover the production offsets, dismissal preserving the second reminder, restore without duplication, cancellation, and stale IDs from replaced meetings. The end-to-end test sleeps the emulator, verifies both reminder screens, dismisses the first call, and lets the second time out. Tests grant reminder permissions on their target device; use a dedicated emulator for the automated suite.

Validated on 27 October 2026 with the Pixel 9a Android 16 emulator: APK build passed, all four instrumentation tests passed, and lint passed with one tool-version advisory. The locked-screen test confirmed that the second call fired after the first was dismissed and stopped after 30 seconds. OnePlus 13 behavior and actual Calendar integration remain unverified.

## Next milestone: Calendar

1. Connect one Google account with read-only Calendar OAuth access and let the user select calendars. Configure a Google Cloud project and Android OAuth client for the app package and signing certificate.
2. Read timed event occurrences into local storage; handle recurrence exceptions, cancellations, declined invitations, time zones, and duplicate occurrences across calendars.
3. Reconcile changed occurrences with the scheduler using stable occurrence identities. Maintain both reminders unless the user skips that occurrence.
4. Add Join using the event conference URL, with unlocking as needed, and configurable ringtone/vibration.
5. Refresh on app open and via background work. Display last successful sync. Without a backend, prompt delivery of last-minute Calendar changes is not guaranteed. Already scheduled alarms work offline.

No Google account connection, Calendar access, backend, or actual telephone calls are included in milestone 1.
