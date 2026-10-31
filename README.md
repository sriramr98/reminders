# Call Reminder

Personal Android meeting alarms for a OnePlus 13, with read-only Google Calendar authorization, calendar selection, seven-day meeting sync, and independent 15-minute/2-minute reminder calls. Complete the [one-time Google Cloud setup](docs/google-calendar-setup.md) before connecting a real account. Live Calendar authorization has not yet been verified.

## Run

Open this folder in Android Studio, or use JDK 17+ and an installed Android SDK:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `sdk.dir` in your own `local.properties` if Android Studio has not done so. This project uses AGP 9.0.1, Gradle 9.1.0, compile/target SDK 36, and Android 12+ (API 31).

On the device, open **Call Reminder** and enable notifications, alarms & reminders, and full-screen reminders using the setup buttons. Tap **Start two test calls**, then lock the screen. Reminders fire after 10 and 50 seconds, each ringing for at most 30 seconds. Drag the green phone all the way right to answer, or use **Decline** to silence this call. Answering or declining the first leaves the second reminder in place; **Skip both reminders** cancels both. Starting a new sample replaces the existing pair.

Answering stops the ringtone and vibration and speaks: “You have a call, [meeting title], to join in [time remaining].” Remaining time is calculated when you answer, rounded up to minutes (or seconds when less than a minute remains). The connected screen shows the spoken text, an elapsed timer, and **End call**. It closes shortly after playback completes. This uses Android’s English text-to-speech engine, preferring an installed offline voice. If no supported voice is available, the screen explains the problem and shows the meeting information.

Ringing uses alarm audio. Answered speech uses voice-communication audio and call volume, selecting the built-in earpiece before playback starts. Use **Speaker** to explicitly switch to loudspeaker and back. If the earpiece cannot be selected, playback stops with an audio error rather than falling back to the speaker. The app clears its communication route and mode when the call ends, errors, is cancelled, or is replaced. It does not change your volume levels and does not need microphone or telephone permissions. An existing phone/VoIP audio mode prevents the reminder from taking over call audio.

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
- `CallAudio`: earpiece-first communication routing, confirmed route changes before speech, explicit Speaker switching, route-loss handling, and cleanup of audio mode/selection.
- `MainActivity`: permission readiness, sample controls, and reminder status.
- `CalendarActivity` / `GoogleCalendarAuth`: account picker, Google consent, calendar selection, manual sync, upcoming meetings, and disconnect/revocation.
- `CalendarApi` / `CalendarEvents`: paginated read-only REST requests, expanded occurrences, filters, duplicate resolution, and meeting links.
- `CalendarStore` / `CalendarSync` / `CalendarRefresh`: private cache, complete-snapshot reconciliation, generation checks for stale responses, and background refresh.

The prototype uses native Android views with no UI framework dependency. This keeps the first device-delivery experiment small; the scheduler and ringing service can remain when the UI moves to Compose.

## Verification

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation tests cover the production offsets, remaining-time speech boundaries, dismissal preserving the second reminder, restore without duplication, cancellation, and stale IDs from replaced meetings. Gesture tests cover partial/cancelled swipes and accessibility activation. The locked-screen test checks the earpiece speech path on supported hardware, or the audio-unavailable path on emulators without an earpiece, then verifies the second call times out. Additional tests verify automatic presentation while the app is open and the real notification Answer control while the app is in the background. Tests grant reminder permissions on their target device. Use the Pixel 9a API 36 emulator at its default 1080×2424 resolution/font scale with an English TTS voice installed; the System UI Answer tap uses that visual fixture.

`CallAudioTest` checks actual hardware route selection, Speaker switching in both directions, and mode cleanup; its missing-earpiece test runs on the emulator. `PhonePlaybackTest` starts a real reminder through the earpiece, explicitly switches to Speaker during playback, then verifies speech completion and audio cleanup. These hardware tests skip cases whose device capabilities do not apply. Run them explicitly on a connected phone only when spoken playback is wanted; avoid running the emulator UI fixture on a real phone.

Validated on 27 October 2026: APK build and lint passed. Pixel 9a Android 16 emulator checks cover scheduling, gesture handling, foreground presentation, native notification Answer, and the missing-earpiece error path. Earlier prototype tests verified locked-screen ringing and speech completion. On the OnePlus 13, hardware tests verified earpiece selection by default, Speaker switching in both directions, real spoken reminder playback from earpiece to Speaker, speech completion, and restoration of normal audio mode. Physical-device notification/battery behavior has not been exhaustively tested, and Calendar integration remains unimplemented.

## Google Calendar

Follow [docs/google-calendar-setup.md](docs/google-calendar-setup.md) to enable Calendar API, configure your test user, and register the Android package/signing SHA-1. No API key, client secret, backend, or actual telephone calls are used.

The app fetches all pages of a seven-day window for selected calendars and expands recurring occurrences. Cancelled, declined, all-day, and non-meeting event types are excluded. A complete successful snapshot updates/cancels alarms, while failed fetches preserve the previous schedule. Dismissed reminders stay dismissed on unchanged occurrences; moved meetings receive new alarm IDs. Skipping an occurrence survives resync, and sample calls cannot cancel Calendar calls.

WorkManager requests refresh every 15 minutes with network access, and app-open refreshes are throttled to five minutes. Last successful sync and failures are visible. Android can delay background work; prompt delivery of last-minute changes requires further work. Live OAuth consent and real-calendar testing remain the next external validation step.

Calendar validation on 28 October 2026: ten focused data/scheduler tests and two Calendar screen tests passed. The broader emulator suite also completed without failures (hardware-only earpiece tests skip on the emulator). Build and lint passed; lint retains tool-version and Kotlin-style advisories. Calendar account consent and live Google responses have not been tested because the Cloud project is not registered yet.
