# Call Reminder

An Android app that turns Google Calendar meetings into ringing reminders. It shows meeting details on a full-screen alarm or notification, with a shortcut to open the meeting link when it is time to join.

Reminders are scheduled locally, so they can ring without an internet connection. Google Calendar access is read-only; dismissing or declining a reminder never changes the event or your RSVP.

## Features

- Choose which Google calendars generate reminders.
- Receive alarms **15 minutes and 2 minutes before** each meeting.
- View the meeting title, date, time, calendar, and live countdown.
- Dismiss an individual alert, or select **Not attending** to cancel the remaining reminders for that occurrence.
- Open the meeting link in a browser from the final reminder.
- Sync recurring meetings and calendar changes, with duplicate occurrences resolved across selected calendars.
- Try sample reminders without connecting a calendar.

## How it works

The app syncs the next seven days of timed events from selected calendars. Cancelled events, declined invitations, all-day entries, and special event types such as focus time are excluded.

Each meeting gets two independent alarms. **Dismiss** silences the current alert while leaving the next one scheduled. **Not attending** suppresses all reminders for that occurrence, including after a sync; other occurrences in a recurring series are unaffected.

Alerts ring and vibrate for up to 30 seconds. When the app is open, meeting details appear directly. On a locked device, Android can show the full-screen reminder; while using another app, an actionable notification is available instead. Unhandled alerts leave a missed-reminder notification.

The final reminder includes **Open meeting in browser** when a valid meeting link is available. Opening the link requires unlocking the device and silences the alert. There are no telephone calls or spoken announcements.

## Getting started

### Requirements

- Android 12 or later (API 31+).
- Google Play services and a Google account for Calendar integration.
- Android Studio, or JDK 17+ with the Android SDK, to build from source.

### Build and install

Open the repository in Android Studio, or configure `ANDROID_HOME` / `sdk.dir` in `local.properties` and run:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project targets Android SDK 36 and uses Android Gradle Plugin 9.0.1 and Gradle 9.1.0.

### Connect Calendar

Google Calendar authorization requires a Google Cloud project with the Calendar API enabled and an Android OAuth client registered for the app's package and signing certificate. Follow the [Google Calendar setup guide](docs/google-calendar-setup.md) before connecting an account from a locally built APK.

In the app:

1. Enable notifications and precise alarms. Allow full-screen reminders for lock-screen presentation.
2. Select **Connect Google Calendar** and grant the read-only permissions.
3. Choose calendars and apply the selection. The primary calendar is selected by default.
4. Use **Sync now** to refresh meetings manually.

### Try a reminder

**Start two test reminders** schedules alerts after 10 and 50 seconds. Dismiss the first to receive both, or choose **Not attending** to cancel the pair.

**Schedule sample meeting in 20 min** uses the normal offsets, ringing after 5 and 18 minutes. Samples have no meeting links and do not affect Calendar reminders.

## Sync and delivery limitations

- Background sync is requested every 15 minutes. Android may delay it, so last-minute calendar changes may not arrive before an alarm. Opening the home screen also requests a refresh, throttled to five minutes.
- Failed syncs retain the previous schedule. Already elapsed reminder offsets are not triggered retroactively.
- Full-screen presentation depends on Android permissions and device state; it is not a guaranteed overlay while another app is open.
- Alarm volume, Do Not Disturb, and manufacturer battery restrictions affect delivery. Force-stopping the app prevents reminders until it is reopened.
- Only one reminder rings at a time; a new alert replaces an active one.
- Reminder intervals are currently fixed at 15 and 2 minutes.

## Privacy

Calendar metadata and reminder state are stored privately on the device, with backup and transfer disabled. Access tokens are managed through Google Play services and are not persisted by the app. No backend is required.

Disconnecting Calendar removes its cached meetings and reminders and attempts to revoke Google access. Notification previews use private visibility, but the full-screen reminder displays meeting details on the lock screen when enabled.

## Development

The app is written in Kotlin using native Android Views.

| Component | Responsibility |
| --- | --- |
| `MainActivity`, `CalendarActivity` | Setup, calendar selection, upcoming meetings, and sample reminders |
| `GoogleCalendarAuth`, `CalendarApi` | Read-only authorization and paginated Calendar API requests |
| `CalendarStore`, `CalendarSync`, `CalendarRefresh` | Local cache, snapshot reconciliation, and WorkManager refresh |
| `Reminders` | Persistent alarm state, occurrence suppression, scheduling, and restoration |
| `RingService`, `ReminderActionReceiver` | Ringing, vibration, notifications, timeouts, and notification actions |
| `ReminderActivity`, `ReminderInfo`, `MeetingLinks` | Meeting details, countdown, and browser navigation |

### Build checks and tests

```sh
# Build app and instrumentation APKs, and run lint
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

# Run instrumentation tests on a connected device or emulator
./gradlew :app:connectedDebugAndroidTest
```

Tests cover Calendar parsing and reconciliation, alarm scheduling, dismissal and suppression, reminder presentation, notification actions, and meeting links.

See the [readiness tracker](docs/shareable-app-readiness.md) for planned refinements and release requirements.
