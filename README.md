# Call Reminder

Native Android meeting alarms with read-only Google Calendar access. Reminders **ring, vibrate, and show meeting information**—there are no telephone calls, spoken announcements, or call-audio routing.

Calendar integration has been tested successfully with the owner's real account. New accounts/build signing certificates still require the [Google Cloud setup](docs/google-calendar-setup.md). See the [shareable-app readiness tracker](docs/shareable-app-readiness.md) for remaining release work.

## Run

Open this folder in Android Studio, or use JDK 17+ and an installed Android SDK:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `sdk.dir` in your own `local.properties` or configure `ANDROID_HOME`. The project uses AGP 9.0.1, Gradle 9.1.0, compile/target SDK 36, and Android 12+ (API 31).

## Reminder experience

Enable notifications, alarms & reminders, and full-screen reminders using the home-screen setup buttons. Connect Google Calendar and choose calendars. The app syncs a seven-day window and schedules reminders **15 and 2 minutes before** each eligible occurrence. Configurable intervals/counts remain follow-up work.

- **Dismiss this reminder** stops only this alert, leaving later reminders scheduled.
- **Not attending** stops this alert and cancels all remaining reminders for the same occurrence. This stays suppressed across resync and moving that occurrence; it does **not** change your Google Calendar RSVP or suppress the recurring series.
- The **final reminder** offers **Open meeting in browser** if a valid HTTPS meeting link exists. It opens a browser, not a meeting-app deep-link handler, and requests unlocking first when needed. Successful launch silences the current alert. If no browser is available, an error is shown and the reminder remains available.
- Unhandled alerts ring for at most **30 seconds**, then leave a missed-reminder notification. Missed reminders retain meeting details/actions, including the final browser button. Timing out never means “not attending.”

The details screen shows the meeting title, date/time, live remaining time, calendar, and next-reminder/final-reminder information. There is no swipe-to-answer, connected timer, speech engine, earpiece routing, or Speaker toggle.

### Presentation

- **App open:** opens the meeting reminder screen directly with a quiet foreground notification.
- **Locked/asleep:** Android opens the full-screen reminder when system settings allow it.
- **Using another app:** an alarm-style notification provides Dismiss / Not attending and, on the final reminder when linked, Open meeting in browser. Tap its body for details.

Full-screen access does not guarantee an overlay over another app while unlocked. Notification previews use private visibility with a generic public version; the full-screen reminder itself displays meeting information. Additional lock-screen privacy controls remain tracked release work.

Ringing follows alarm volume, DND, and system/manufacturer restrictions. No volume levels or communication audio routes are changed. Force-stop prevents alarms until reopening; powered-off devices and battery controls can prevent delivery. Currently a newly arriving reminder replaces an already ringing one; broader collision-policy work is tracked.

### Sample reminders

**Start two test reminders** rings after 10 and 50 seconds. Dismiss the first to test both, or choose Not attending to cancel the pair. **Schedule sample meeting in 20 min** uses the real 15-/2-minute offsets and rings in 5 and 18 minutes. Samples have no real meeting link and are isolated from Calendar reminders. Starting a new sample replaces the previous sample pair.

## Implementation

- `Reminders`: private JSON persistence, independent exact `AlarmManager.setAlarmClock` alarms, stable occurrence identity, cancellation/suppression, and restoration after reboot/time changes. Old stored speech states migrate to dismissed; obsolete speech/audio fields are ignored.
- `AlarmReceiver`: claims a scheduled reminder and starts `RingService`.
- `RingService`: alarm-category, expanded-text notifications, full-screen intents, bounded ringtone/vibration/wake locks, timeout, and cleanup. `ReminderActionReceiver` handles dismissal and occurrence suppression even after the service times out.
- `ReminderActivity` / `ReminderInfo`: scrollable information-first screen, live timing, accessible buttons, and final-reminder link eligibility.
- `MeetingLinks`: safe HTTPS links, browser-only targets, unlock gating, and launch-error handling.
- `MainActivity` / `ReminderPresentation`: permission readiness, sample controls/status, and visible-activity presentation.
- `CalendarActivity` / `GoogleCalendarAuth`: account picker, Google consent, calendar selection, manual sync, meetings, and disconnect/revocation.
- `CalendarApi` / `CalendarEvents`: paginated read-only REST requests, recurring occurrences, filtering, deduplication, and link extraction.
- `CalendarStore` / `CalendarSync` / `CalendarRefresh`: private cache, complete-snapshot reconciliation, stale-response generation checks, and WorkManager refresh.

Uses native Android Views, not Compose. No backend, API key, client secret, microphone, telephone permission, or TTS voice installation is required.

## Calendar sync

All pages of each selected calendar's seven-day window must succeed before replacing the schedule. Cancelled, declined, all-day, and non-default event types are excluded. Failed fetches preserve cached alarms. Dismissed alerts stay dismissed on unchanged occurrences; moved meetings receive replacement alarm IDs. Elapsed offsets are never fired immediately during a late sync.

Background refresh is requested every 15 minutes with network access. App-open requests are throttled to five minutes. Android can delay refresh, so last-minute additions/edits may arrive late; saved alarms work offline. Disconnect removes local Calendar meetings/reminders and attempts to revoke access, while leaving samples independent.

## Verification

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation coverage includes scheduler offsets, displayed-time boundaries, legacy speech-state migration, dismiss versus decline, stale IDs, final-reminder link visibility, late sync, missed-notification actions, foreground/background/lock-screen presentation, and unchanged communication audio mode. Calendar tests cover parsing, pagination, reconciliation, failed snapshots, and stale responses.

For this ringing-only revision, app/test APK compilation and lint have passed. Instrumentation execution and physical-device behavior still need validation; the previous call-style/TTS hardware checks do not validate the new presentation. No Android device/emulator was available in the implementation environment.
