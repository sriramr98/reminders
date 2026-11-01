# Shareable app readiness tracker

Status: implementation in progress; device validation pending
Updated: 2026-11-01

## Goal

Make the existing Android Calendar reminder app easy to set up, dependable, and ready to share without developer assistance. Include the agreed change from simulated calls to information-rich, full-screen reminders and configurable reminder schedules. Avoid unrelated feature expansion.

The owner has tested Calendar integration with a real account and reports that it works as expected. This validates the personal-account happy path, not yet release signing, public OAuth onboarding, or broad device compatibility.

### Ringing-only implementation update

The code now uses an information-first reminder screen and alarm-style notifications. TTS, swipe-to-answer, connected-call UI, earpiece routing, and Speaker controls have been removed. **Dismiss** silences only the current alert; **Not attending** suppresses the occurrence locally. The final reminder offers a browser-only meeting-link action when a valid link exists, including on its missed notification. Unlocking is required before launching the browser. App/test APK builds and lint pass; device/instrumentation execution remains pending. Configurable reminder counts/intervals are **not implemented yet**; the current 15-/2-minute schedule is unchanged.

Unchecked tasks below remain release acceptance checks; implementation alone is not a claim of device verification.

### Tracking conventions

- `[ ]` Not complete; mark `[x]` only after implementation and verification.
- **P0:** required before an invited beta can be trusted.
- **P1:** required before broad sharing / public release.
- **P2:** follow-up validation and launch operations.
- Decisions below are proposals or unresolved questions, not implemented behavior.

## 1. Confirm the revised product behavior — P0

### Agreed direction

- Use a full-screen, information-rich meeting reminder rather than a simulated incoming telephone call. A society visitor/gate notification is the interaction reference, not a requirement to integrate with those services.
- Declining the first reminder means the user is not attending; suppress the remaining reminders for that meeting occurrence.
- Make the number of reminders and their pre-meeting intervals configurable rather than hard-coding two reminders at 15 and 2 minutes.
- Ring and display meeting information only. **No spoken feedback or text-to-speech.**
- The final reminder in the configured schedule must include an **Open meeting in browser** button when the meeting has a valid link. Omit the button when no valid link exists.

### Decisions to settle before implementation

- [ ] Confirm the action labels and semantics. Suggested distinction: **Dismiss this reminder** silences only the current alert; **Not attending** suppresses every remaining reminder for this occurrence. Do not treat an incidental system-notification dismissal as an attendance decision.
- [ ] Confirm that declining **any** reminder suppresses all remaining reminders for that occurrence, not just declining the first.
- [ ] Confirm that “not attending” is local to this app. **Recommended:** do not change Google Calendar RSVP; retain read-only scopes. Changing the invitation response would be a separate scope/product decision.
- [ ] Define any acknowledge/view-details action on earlier reminders and whether it leaves future reminders scheduled. The final reminder's browser action is agreed; opening a link does not inherently prove attendance.
- [ ] Decide which meeting fields appear on the reminder. Candidate fields already available or retrievable through the existing Calendar API: title, start time, time remaining, calendar, location, organizer, and join link. Keep the initial set focused.
- [ ] Decide how much meeting information may appear before unlocking. Define lock-screen redaction separately from the unlocked details screen.
- [ ] Choose schedule scope: global default only, per-meeting overrides, or both. **Suggested first scope:** global default, unless per-meeting overrides are essential.
- [ ] Decide whether schedule edits apply to all future meetings, only newly synced meetings, or selected occurrences. Recommended: apply to future pending reminders, never replay already handled offsets.
- [ ] Define bounds: minimum/maximum lead time and reminder count, duplicate offsets, empty schedule, ordering, and whether reminders at meeting start are allowed. Proposed initial scope: pre-meeting offsets only.
- [ ] Define whether “not attending” survives moving the same occurrence to a new time. Recommended: preserve it for the same occurrence identity, never suppress the whole recurring series.
- [ ] Define an explicit collision policy when reminders for different meetings arrive together. The current service supports one active reminder and replaces it when another arrives.

Acceptance: action semantics, schedule scope, privacy behavior, and collision policy are written down without ambiguity.

## 2. Replace the call metaphor with meeting reminders — P0

- [ ] Replace swipe-to-answer/call-connected screens with a reminder layout that presents the agreed meeting information and clear actions.
- [ ] Replace `Notification.CallStyle`, caller identity, answer/decline terminology, connected timer, and phone visuals with appropriate alarm/reminder presentation.
- [ ] Audit notification actions, pending intents, accessibility labels, strings, channels, and service commands for leftover call semantics.
- [ ] Implement “not attending” as occurrence-wide suppression, immediately cancelling pending alarms and stopping any active alert for that occurrence.
- [ ] Preserve suppression across resync, process recreation, reboot, and the agreed meeting-move behavior.
- [ ] Keep actions isolated to one occurrence; do not affect other meetings, the whole recurring series, or sample reminders.
- [ ] Make duplicate/stale action taps harmless and prevent old notifications from mutating replacement reminders.
- [ ] Implement the agreed positive-action and dismiss-only behavior consistently on the full-screen UI and notification.
- [ ] Retain safe meeting-link validation and require unlocking before opening external meeting apps from a locked phone.
- [ ] Show **Open meeting in browser** on the final reminder's notification and full-screen view only when a valid HTTPS meeting link exists. Derive “final” from the configured schedule, not a fixed second-reminder assumption; a single-reminder schedule has one final reminder.
- [ ] Launch the link in a browser rather than relying on a generic link intent that may open a meeting app. Require unlocking first, handle missing browsers/launch failures clearly, and stop the current ringing when the browser opens successfully.
- [ ] Verify that late sync leaving only the final eligible offset still offers the browser action, and that schedule edits update final-reminder classification consistently.
- [ ] Define alert duration, timeout outcome, and missed-reminder presentation; ensure timeout does not count as declining attendance.
- [ ] Remove TTS initialization/playback, spoken transcripts, earpiece routing, Speaker controls, voice-call volume handling, and speech-specific states/errors. Retain only audio handling needed for ringing; audit foreground-service type and permissions against the resulting implementation.

### Android presentation constraints

- [ ] Treat full-screen presentation as permission- and system-dependent, not guaranteed. A visitor-app-like appearance does not bypass Android restrictions.
- [ ] App visible: present the reminder directly without a redundant intrusive banner.
- [ ] Locked/asleep: use full-screen intent where allowed; verify fallback when unavailable.
- [ ] Another app visible: provide an actionable high-priority notification; do not promise a forced full-screen takeover.
- [ ] Ensure denied full-screen access still leaves a useful, actionable reminder.
- [ ] Revalidate notification priority, channel settings, sound/vibration behavior, and any foreground-service notification after abandoning CallStyle.

Acceptance: users see meeting context immediately, every action has consistent semantics, and the app works without full-screen access.

## 3. Configurable reminder scheduling — P0

- [ ] Introduce a persisted, validated reminder schedule model instead of hard-coded `15` and `2` offsets.
- [ ] Provide an understandable editor for adding/removing intervals and showing the resulting order. Enforce the agreed limits and empty-schedule behavior.
- [ ] Add per-occurrence overrides only if selected in section 1; make inheritance from the default explicit.
- [ ] Generalize reminder identity, reconciliation, notification actions, and cancellation to any supported reminder count.
- [ ] Remove assumptions that `15 minutes` means sequence 1 and every other interval means sequence 2.
- [ ] Replace “first/second/final”, “two calls”, and “skip both” wording with data-driven, accurate wording.
- [ ] When a schedule changes, cancel obsolete pending alarms and arm eligible new ones without duplicate alerts or replaying handled reminders.
- [ ] Preserve occurrence-wide suppression when configuration changes.
- [ ] Continue to skip elapsed offsets during late sync; show remaining eligible reminders accurately.
- [ ] Handle offset arithmetic and input conversion safely, including invalid/extreme inputs.
- [ ] Keep sample reminders isolated and update their wording/behavior to reflect the revised experience.
- [ ] Add a migration for existing 15-/2-minute reminders and stored states. Decide the initial default; retaining the existing intervals avoids an unexpected change.

Acceptance: any supported schedule produces exactly the expected future alarms, edits remove old alarms, and decline suppresses every remaining reminder for that occurrence.

## 4. Guided onboarding and everyday home screen — P0

- [ ] Replace the prototype-first home screen with a guided setup flow: explanation → Calendar connection → calendar selection → permissions → test → readiness summary.
- [ ] Explain each permission before invoking Android UI; recheck actual state on return.
- [ ] Separate precise-alarm/notification readiness from optional full-screen presentation.
- [ ] Handle denial, cancellation, interrupted setup, and later permission revocation without dead ends or repeated nagging.
- [ ] Make actual upcoming meetings and their next reminder times the primary home experience; move sample controls to a secondary place.
- [ ] Display last successful sync, sync failures, and whether cached reminders remain armed.
- [ ] Distinguish no meetings, no selected calendars, reminders disabled, missing permission, and no eligible offsets left.
- [ ] Provide a short test using the same delivery path as real reminders; explain what the user should expect with the app open, backgrounded, and phone locked.
- [ ] Ensure “ready” is based on actual permissions/configuration, not simply having a connected account.

Acceptance: a new user can complete setup and test a reminder without external instructions, and can understand whether future reminders will work.

## 5. Lifecycle and delivery reliability — P0

- [ ] Add startup recovery for stale persisted active states (`ringing`, `answering`, `speaking`, or their replacements) after process death. Never reopen a phantom active reminder.
- [ ] Test process death during alerting and action handling, including cases where `onDestroy` never runs.
- [ ] Verify reboot, app update, date/time changes, time-zone changes, and exact-alarm permission restoration preserve the intended future schedule without duplicates.
- [ ] Handle notification/exact-alarm permission revocation accurately and recover when restored.
- [ ] Verify cancellation/rescheduling of a meeting while its alert is visible or ringing, including title/link-only changes and moved starts.
- [ ] Implement and test the chosen simultaneous/overlapping-reminder policy without silent accidental replacement.
- [ ] Make scheduling/storage failures recoverable; avoid marking reminders as armed when the corresponding alarm could not be registered.
- [ ] Bound alert duration, wake locks, background execution, audio focus, and service lifetime; clean up on every exit path.
- [ ] Keep force-stop, powered-off-device, DND, volume, and manufacturer battery limitations explicit. Do not claim guaranteed delivery.
- [ ] Show accurate future-reminder information based on current stored state, not a sequence-number assumption.

Acceptance: interruptions cannot leave stuck alerts, duplicate alarms, leaked resources, or misleading readiness.

## 6. Calendar authorization and sync recovery — P0/P1

- [ ] Test the connect/consent flow through rotation, backgrounding, activity recreation, cancellation, and slow responses.
- [ ] Ensure every loading state resolves and users can retry without reinstalling.
- [ ] Distinguish connectivity failures, expired/revoked authorization, organization policy restrictions, API errors, and developer configuration errors.
- [ ] Reconnect without unnecessarily deleting a valid cached schedule for the same account.
- [ ] Preserve the complete-snapshot rule: partial multi-calendar failures must not erase previously scheduled reminders.
- [ ] Preserve generation checks when account, connection, or calendar selection changes during a fetch.
- [ ] Verify calendar selection updates, removed calendars, duplicate calendar copies, recurring exceptions, and edited/deleted meetings.
- [ ] Confirm background retries and app-open throttling behave sensibly after failures and clock changes.
- [ ] Keep local disconnect immediate even when Google revocation fails; explain how to revoke access manually if necessary.
- [ ] Explain that saved alarms work offline but new/edited meetings depend on a successful refresh; background refresh is not instant change detection.
- [ ] Use real accounts beyond the owner's, including a second personal account and a managed account where available. Managed accounts may legitimately block access.

Acceptance: temporary failures preserve useful behavior, permanent access problems are actionable, and stale fetches cannot resurrect removed data.

## 7. Audio, privacy, and accessibility polish — P1

- [ ] Test ringtone/alert behavior with music playing, silent mode, DND, low alarm volume, and an existing phone/VoIP call.
- [ ] Test wired/Bluetooth devices and define expected output behavior for the revised reminder experience.
- [ ] Verify no reminder action invokes TTS, changes communication-device routing, or enters voice-call audio mode. No English voice installation should be required.
- [ ] Replace speech/earpiece-specific tests with ringing-only and browser-action coverage; verify final reminders with a link, without a link, with an invalid link, while locked, and with no browser available.
- [ ] Verify lock-screen redaction, notification visibility, external links, and task/recents behavior do not expose more meeting information than intended.
- [ ] Support large font/display scaling, small screens, long titles, long calendar names, and system-bar insets without clipped actions.
- [ ] Verify TalkBack labels, focus order, announcements, keyboard/accessibility activation, contrast, and touch-target sizes.
- [ ] Normalize empty, loading, disabled, error, and retry states across screens.
- [ ] Remove prototype/call-specific wording as appropriate; use consistent app naming and a release-quality icon.
- [ ] State the initial support scope explicitly, e.g. English and Android 12+. A Compose rewrite or broad localization is not a release prerequisite.

Acceptance: the existing experience is usable without precise gestures, on supported screen sizes, and without unexpected audio/privacy behavior.

## 8. Production Google authorization — P1; start early

- [ ] Choose production Google Cloud project ownership and separate development/production configuration where appropriate.
- [ ] Configure app branding, support email, public homepage, and privacy policy; verify domain ownership where required.
- [ ] Retain minimum read-only scopes unless an explicitly approved requirement changes them.
- [ ] Register the package and release signing SHA-1. For Play delivery, register the **Play app-signing certificate**, not only the upload/debug certificate.
- [ ] Decide when to move beyond OAuth Testing. Invited testers must be added while in Testing; the documented seven-day authorization expiry for this setup is not a seamless long-term user experience.
- [ ] Prepare and submit required sensitive-scope verification, including scope justification and a demonstration of authorization and Calendar use.
- [ ] Verify the authorization screen uses the correct app identity and the production-distributed build can connect.
- [ ] Test revoked access and any required reconnection flow with production configuration.

Notes: Calendar event reading is a sensitive use case. Moving publishing status to Production does not by itself complete verification or remove every warning/restriction. A backend is not inherently required by the current design.

Acceptance: intended users can authorize the distributed build without developer-managed test-user onboarding or unexpected testing-mode expiry.

## 9. Release engineering, privacy, and distribution — P1

- [ ] Choose the initial channel: signed invited-beta APK or Google Play testing track, then wider distribution.
- [ ] Establish a stable release signing key, secure backup, and controlled access. Do not distribute debug-signed builds as the long-term release channel.
- [ ] Configure a non-debuggable signed release build and monotonically increasing version codes.
- [ ] Define how testers/users receive updates and verify an update preserves Calendar selections, suppression, reminder settings, and alarm state.
- [ ] Add versioned storage migration and safe handling of malformed/incompatible persisted data.
- [ ] Review release logs and error reporting; avoid tokens, account identifiers, meeting contents, and join links in routine diagnostics.
- [ ] Publish an accurate privacy policy covering Google data access, on-device storage, retention, disconnect, and any diagnostic collection actually used.
- [ ] Provide a support contact and a privacy-safe way to report failures. Do not require Calendar contents to debug routine delivery issues.
- [ ] Update README/setup docs to reflect live Calendar validation and the revised reminder behavior; remove contradictory “unimplemented” claims.

### Additional Google Play tasks, if selected

- [ ] Produce an Android App Bundle and configure Play App Signing.
- [ ] Complete listing, screenshots, content rating, Data safety, privacy-policy link, and account-specific testing requirements.
- [ ] Review current target-SDK and other applicable release requirements at submission time.
- [ ] Declare and justify full-screen-intent and foreground-service usage according to the actual implementation.
- [ ] Describe the product truthfully as a meeting alarm/reminder, not as receiving real phone calls. Full-screen-intent eligibility requires review; approval is not assumed.
- [ ] Verify graceful degradation if full-screen intent is denied by the user or not granted by default.

Acceptance: users can install and update the correct signed build, authorize Google, understand data use, and contact support.

## 10. Verification and beta gate — P0/P1/P2

### Automated checks

- [ ] Update existing instrumentation tests for the new action semantics and removal of two-reminder assumptions.
- [ ] Add configurable-schedule tests: count, ordering, bounds, duplicate offsets, elapsed offsets, edits, migration, and replacement IDs.
- [ ] Add occurrence suppression tests across sync, schedule edits, moved occurrences, recurring instances, reboot, and disconnect/reconnect according to the agreed retention policy.
- [ ] Test notification actions, stale/duplicate taps, active-alert cancellation, collision handling, and denied full-screen access.
- [ ] Add process-death recovery and permission-revocation/restoration cases.
- [ ] Retain Calendar parsing, pagination, failed-snapshot, stale-response, safe-link, and sample-isolation coverage.
- [ ] Run build/lint and relevant automated tests for the signed release configuration; configure repeatable CI checks where feasible.

### Device validation

- [ ] Validate on OnePlus, Pixel, and Samsung where available, including the oldest supported Android version.
- [ ] Cover fresh installs and upgrades; permissions granted, denied, revoked, and restored.
- [ ] Cover app open, another app open, locked screen, screen off, battery saver, and manufacturer background restrictions.
- [ ] Confirm real Calendar edits/deletions and reminders on more than the owner's account.
- [ ] Run accessibility and large-font checks on physical devices or suitable emulators.

### Invited beta exit criteria

- [ ] Unassisted users can install, connect, choose calendars, grant permissions, configure reminders, and complete a test.
- [ ] Users can explain what dismissing versus declining does and correctly identify their next scheduled reminder.
- [ ] No unresolved issues involving wrong-meeting actions, meeting-information exposure, duplicated reminders, stuck alerts, or data loss on update.
- [ ] Common failures provide understandable recovery instructions rather than requiring reinstall/developer help.
- [ ] Record beta feedback, fix release blockers, then reassess readiness for broader distribution.

## Suggested delivery sequence

1. **Behavior contract:** resolve section 1, especially decline semantics, schedule scope, and lock-screen privacy. Ringing-only presentation and the final-reminder browser action are already agreed.
2. **Scheduler/state foundation:** generalized schedules, suppression, migration, process recovery, collision handling.
3. **Reminder presentation:** information-rich screen and notification fallback with consistent actions.
4. **Seamless setup:** guided onboarding, home/status refinement, connection recovery, accessibility.
5. **Release preparation:** signed build, production OAuth, privacy/support material. Start external OAuth work in parallel once behavior is stable.
6. **Invited beta:** cross-device validation, unassisted onboarding, release-blocker fixes, then broad distribution.

## Reference links

- [Google Calendar authorization scopes](https://developers.google.com/workspace/calendar/api/auth)
- [Google OAuth sensitive-scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification)
- [Google Play foreground-service and full-screen-intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Android full-screen-intent limits](https://source.android.com/docs/core/permissions/fsi-limits)
- [Repository Google Calendar setup](google-calendar-setup.md)

Policies and submission requirements should be rechecked when preparing the release.
