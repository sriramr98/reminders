# Google Calendar setup for this personal Android APK

The code is ready for Google authorization, but the app must be registered in a Google Cloud project before your Google account can connect. This setup does not create or modify Calendar events.

## 1. Create a project and enable Calendar

1. Open [Google Cloud Console](https://console.cloud.google.com/) with the Google account you want to use to own the app.
2. Create a project named **Call Reminder** (or choose your own name), and select it.
3. Open **APIs & Services → Library**, search for **Google Calendar API**, and enable it.

Google's [Calendar quickstart](https://developers.google.com/workspace/calendar/api/quickstart/java) documents enabling the API. We use the Android authorization flow below rather than that quickstart's desktop credentials.

## 2. Configure consent for personal testing

Open **Google Auth Platform** in the selected project.

1. Under **Branding**, set the app name to **Call Reminder** and use your email for user support and developer contact.
2. Under **Audience**, choose **External** for a personal Gmail account and leave the app in **Testing**. Add the Google account you will connect on the phone as a **test user**. If you use a managed Workspace account, organization policies may require administrator approval.
3. Under **Data Access**, add these two scopes:

```text
https://www.googleapis.com/auth/calendar.calendarlist.readonly
https://www.googleapis.com/auth/calendar.events.readonly
```

These allow listing subscribed calendars and reading events. Neither allows creating or editing events. See [Calendar scopes](https://developers.google.com/workspace/calendar/api/auth).

Google's [Testing audience rules](https://support.google.com/cloud/answer/15549945) state that test-user authorizations expire after seven days. Expect to reconnect while the app remains in Testing. We can revisit personal-use publishing once the flow works; a Play Store release is not part of this milestone.

## 3. Register the Android client

Under **Google Auth Platform → Clients → Create client**, choose **Android**.

| Field | Value for this APK |
|---|---|
| Client name | Call Reminder local debug |
| Package name | `com.sriram.callreminder` |
| SHA-1 certificate fingerprint | `DC:D9:40:67:2B:D0:42:3F:88:B9:88:0A:EF:0D:09:55:CD:DC:AA:A5` |

The SHA-1 above was obtained from this checkout's Gradle signing report on 28 October 2026. It matches APKs built with this machine's current Android debug signing key. Register the new certificate separately if the signing key or distribution method changes.

To verify it again with Android Studio's bundled Java runtime:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:signingReport
```

Google's [Android authorization setup](https://developer.android.com/identity/authorization) describes registering the package name and signing SHA-1. The optional Play ownership verification is separate from OAuth consent testing; no Play Store publication is requested here.

The app uses Google Play services `AuthorizationClient` with the installed Android package/signing certificate. You do not need to put an API key, client secret, desktop OAuth JSON, web client ID, or service-account key into this repository. No backend or offline server authorization code is used.

## 4. Connect on the phone

1. Install the current APK and open **Call Reminder**.
2. Enable notifications, precise alarms, and full-screen reminders on the home screen.
3. Tap **Connect Google Calendar**, then choose your Google account.
4. Grant both read-only Calendar permissions on Google's consent screen.
5. After the first sync, the primary calendar is selected by default. Choose any additional calendars and tap **Apply calendar selection**.
6. Check **Last synced** and the upcoming meetings list. Use **Sync now** after making a test edit in Google Calendar.

Do not enter Google credentials into the app itself. Authentication/account selection and consent are handled by Android/Google Play services.

## 5. Verify with a real meeting

Use an event you create yourself in Google Calendar on a selected calendar, starting at least 20 minutes ahead. Give it a title you recognize and a Meet link if you want to test Join.

- Sync and verify the meeting's displayed date/time.
- The app schedules two independent alarms at 15 and 2 minutes before its start.
- Dismiss the first reminder; the second should remain scheduled. Separately test **Not attending**: it must cancel all remaining reminders for that occurrence without changing your Calendar RSVP.
- Change the start time in Google Calendar, sync, and confirm that the old alarm IDs are replaced.
- Delete/cancel the event, sync, and confirm that its pending reminders disappear.
- Check a recurring meeting, a moved occurrence, an all-day event, and a declined invitation.
- Tap **Not attending** for one occurrence; a subsequent sync must not restore its reminders, and other meetings must remain armed.
- On the final reminder, verify **Open meeting in browser** appears when a valid link exists, requests unlocking when locked, and opens a browser. No link means no button; no spoken feedback is used.
- Disconnect Calendar and verify that its meetings/reminders are removed while sample test reminders remain independent.

The repository's automated tests exercise these reconciliation rules with fixtures. The owner has successfully tested the Calendar integration with a real account. Other users/signing configurations and the new ringing-only reminder presentation still need validation.

## Sync behavior and limits

The first version refreshes a complete seven-day window, with recurring events expanded by Google (`singleEvents=true`), rather than maintaining an incremental sync token. It reads all response pages and applies alarm changes only after every selected calendar has fetched successfully. This makes edits, deletions, and selection changes explicit and testable for the first version.

Background WorkManager refresh is requested every 15 minutes, with network access required. Android can delay it. Opening the home screen requests a refresh when the last successful sync/attempt is older than five minutes. Sync-now is immediate when network and Google authorization are available. Already cached alarms ring offline; late additions/edits may not arrive before a reminder deadline. No overdue reminder is fired immediately during sync.

Access tokens are kept in memory for API calls and managed by Google Play services; the app does not persist access or refresh tokens. Meeting metadata, selected calendars, and alarm state are stored privately on the phone, with backup/transfer disabled. Disconnect removes local Calendar data, cancels its alarms/work, and attempts to revoke Google's grant. If revocation cannot reach Google, the app explains how to remove access in Google Account connections.

## Troubleshooting

- **Google setup incomplete / developer error (10):** check the Android package and SHA-1, the selected Cloud project, and whether Google Calendar API is enabled. Allow time for registration changes to propagate.
- **Access blocked / app not verified:** check that the exact phone account is listed under Audience → Test users. Follow Google's consent flow only for your own project; organization policies can still block access.
- **Permission incomplete:** grant both requested scopes, not only calendar names or only events.
- **HTTP 403:** verify Calendar API is enabled and that the account may read the selected calendars.
- **Needs attention / expired access:** tap **Reconnect / sync now**. Failed syncs retain the previous schedule and show the last successful sync time.
- **No events:** check calendar selection, timed starts in the next seven days, declined status, and whether the event is a meeting rather than focus time, working location, or an all-day entry.
- **No reminders despite visible meetings:** enable precise alarms and notifications. A meeting synced after an offset has passed only gets the remaining future offsets.
