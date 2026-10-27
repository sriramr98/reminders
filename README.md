# Call Reminder

Personal Android meeting alarms for a OnePlus 13. Milestone 1 is a runnable native Kotlin prototype; Calendar integration is the next milestone.

## Run

Open this folder in Android Studio, or use JDK 17+ and an installed Android SDK:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `sdk.dir` in your own `local.properties` if Android Studio has not done so. This project uses AGP 9.0.1, Gradle 9.1.0, compile/target SDK 36, and Android 12+ (API 31).

On the device, open **Call Reminder** and enable notifications, alarms & reminders, and full-screen reminders using the setup buttons. Tap **Start two test calls**, then lock the screen. Reminders fire after 10 and 50 seconds, each ringing for at most 30 seconds. Drag the green phone all the way right to answer, or use **Decline** to silence this call. Answering or declining the first leaves the second reminder in place; **Skip both reminders** cancels both. Starting a new sample replaces the existing pair.

Answering stops the ringtone and vibration and speaks: “You have a call, [meeting title], to join in [time remaining].” Remaining time is calculated when you answer, rounded up to minutes (or seconds when less than a minute remains). The connected screen shows the spoken text, an elapsed timer, and **End call**. It closes shortly after playback completes. This uses Android’s English text-to-speech engine, preferring an installed offline voice. If no supported voice is available, the screen explains the problem and shows the meeting information. Speech uses alarm volume and does not require microphone or telephone permissions.

**Schedule sample meeting in 20 min** uses the actual 15-minute and 2-minute offsets: it rings in 5 and 18 minutes. No real meeting link is attached to samples.

Presentation depends on what the user is doing:

- **App open:** the call screen opens automatically. A quiet foreground notification avoids covering it with a banner.
- **Device locked/asleep:** Android's full-screen intent opens the call screen when permission allows.
- **Using another app while unlocked:** Android shows a native incoming-call banner with **Answer** and **Decline**. **Answer** opens the connected screen and speaks the reminder; tapping the notification body opens the swipe screen instead.

Full-screen notification access does not force an activity over another app on an unlocked device. Audibility follows alarm volume and Do Not Disturb settings. Force-stopping the app prevents reminders until the app is opened again; permissions, a powered-off phone, or manufacturer battery controls can also prevent delivery. The OnePlus still needs physical-device validation.

## Implementation

- `Reminders`: persistent local occurrences, independent exact `AlarmManager.setAlarmClock` alarms, unique IDs across replacements, cancellation, and rescheduling after reboot/time changes.
- `AlarmReceiver`: looks up a still-scheduled reminder and starts `RingService`.
- `RingService`: Android `Notification.CallStyle` with Answer/Decline actions, full-screen intent, ringtone and vibration, bounded wake locks, and missed-reminder timeout. When an activity is visibly resumed it opens the call screen directly and uses a quiet notification channel. Answering cancels ringing and starts text-to-speech with audio focus, completion/error callbacks, and a bounded speech timeout. Ending or replacing a call releases audio and speech resources.
- `ReminderActivity`: phone-style lock-screen view, connected-call timer, transcript, decline/end controls, and cancellation of the pair.
- `SwipeAnswerView`: animated slider with deliberate swipe threshold, return animation for partial/cancelled gestures, and accessibility/keyboard activation.
- `Announcement`: title and remaining-time sentence shared by the spoken reminder and transcript.
- `MainActivity`: permission readiness, sample controls, and reminder status.

The prototype uses native Android views with no UI framework dependency. This keeps the first device-delivery experiment small; the scheduler and ringing service can remain when the UI moves to Compose.

## Verification

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation tests cover the production offsets, remaining-time speech boundaries, dismissal preserving the second reminder, restore without duplication, cancellation, and stale IDs from replaced meetings. Gesture tests cover partial/cancelled swipes and accessibility activation. The locked-screen test answers the first call through the swipe control, requires speech-engine completion, and lets the second call time out. Additional tests verify automatic presentation while the app is open and the real notification Answer control while the app is in the background. Tests grant reminder permissions on their target device. Use the Pixel 9a API 36 emulator at its default 1080×2424 resolution/font scale with an English TTS voice installed; the System UI Answer tap uses that visual fixture.

Validated on 27 October 2026 with the Pixel 9a Android 16 emulator: APK build and lint passed. The six core instrumentation checks passed, including full swipe-to-answer with real speech-engine completion. The locked-screen test confirmed that answering the first call preserved the second reminder, which rang and stopped after 30 seconds. Focused unlocked tests verify foreground presentation and tapping Answer in the actual Android notification shade. OnePlus 13 behavior and actual Calendar integration remain unverified.

## Next milestone: Calendar

1. Connect one Google account with read-only Calendar OAuth access and let the user select calendars. Configure a Google Cloud project and Android OAuth client for the app package and signing certificate.
2. Read timed event occurrences into local storage; handle recurrence exceptions, cancellations, declined invitations, time zones, and duplicate occurrences across calendars.
3. Reconcile changed occurrences with the scheduler using stable occurrence identities. Maintain both reminders unless the user skips that occurrence.
4. Add Join using the event conference URL, with unlocking as needed, and configurable ringtone/vibration.
5. Refresh on app open and via background work. Display last successful sync. Without a backend, prompt delivery of last-minute Calendar changes is not guaranteed. Already scheduled alarms work offline.

No Google account connection, Calendar access, backend, or actual telephone calls are included in milestone 1.
