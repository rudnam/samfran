# Android app

The Android app is the web app (`web/`) packaged with [Capacitor](https://capacitorjs.com), the same way as RudWallet's: the React build ships inside the app, runs in a web view at `https://localhost`, and talks to the server's API. Native code adds what the web can't do: receiving a place shared from Google Maps (`ShareTargetPlugin.java`), the bus-stop alarm, and notifications while the app is closed.

- Package `com.rudnam.samfran` (permanent once published), talking to https://samfran.rudnam.com (`web/.env.android`).
- Web app changes reach phones only with a new app build. Server changes reach them at once, so keep the API compatible with the last release (add fields; don't rename or remove them).
- Signing key: `keystore/samfran.jks` (alias `samfran`), with its passwords in `keystore/keystore.properties`. The folder is git-ignored and exists only on this machine: **back it up somewhere safe**. Without it you can't publish updates.

## Build

You need a JDK 21, the Android SDK (`JAVA_HOME` and `ANDROID_HOME`, or the paths in `~/.bubblewrap/config.json` on this machine) and Node.

```sh
./android/build.sh            # android/samfran.aab (Play Store) and android/samfran.apk
./android/build.sh install    # and install the APK on the phone plugged in (USB debugging on)
```

It builds the web app for the app (`npm run android` in `web/`, which also copies it in with `cap sync`), runs the unit tests, then signs both files with the key.

CI builds the app on every push and pull request (the `android` job in `.github/workflows/ci.yml`): unit tests, and a release APK without a signature, kept for a week as the `android-apk` artifact. A machine without the memory for Gradle can sign that one instead of building:

```sh
./android/build.sh sign app-release-unsigned.apk   # -> android/samfran.apk
```

Every release needs a higher `versionCode`, because the app offers a build with a higher one as an update. `build.sh` takes it from `version.sh`: one above the APK that samfran.rudnam.com offers, and never below the floor in `app/build.gradle` (`SAMFRAN_VERSION_CODE` and `SAMFRAN_VERSION_NAME` override both).

## Releases and updates

The site offers the APK at `/download/samfran.apk` (the Us tab links to it), and `GET /api/downloads` tells the app its `versionCode`, read from the APK itself. The app checks when it starts and, at most hourly, when it comes back into view (`web/src/updates.ts`). When there's a newer build, it shows **Update**, which downloads the APK in the phone's browser, or **Later**, which hides it until a still newer build. Copies from the Play Store are left to the Play Store (`UpdatesPlugin.java` reports which app installed this one).

**Publishing** puts the APK in the deploy bucket's `downloads/samfran/` and copies it onto the server (rudnam-infra's `rudnam-downloads`), with no restart:

```sh
./android/build.sh && ./android/publish.sh    # by hand, signed in with aws login --profile rudnam-ec2
```

**GitHub Actions does it for you**: `.github/workflows/android-release.yml` builds, signs and publishes a release once CI passes on `main` for a pull request labelled `android-release`, or when you run it by hand (Actions › Android release). It keeps the `.apk` and `.aab` as a GitHub release, `android-<versionCode>`. It needs, once, an **`android-release` environment** limited to the `main` branch, with:

| Kind | Name | Value |
|---|---|---|
| Secret | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 android/keystore/samfran.jks` |
| Secret | `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` | `storePassword` and `keyPassword` from `keystore/keystore.properties` |
| Variable | `ANDROID_UPLOAD_CERT_SHA256` | The key's certificate, 64 hex digits without colons: `keytool -list -v -keystore android/keystore/samfran.jks -alias samfran` (the SHA256 line) |

The release checks the APK is signed with that certificate before publishing it.

While working on the app, `cd web && npm run android`, then open `android/` in Android Studio, or `cd android && ./gradlew installDebug`. A debug build needs no signing key.

## How the app signs in

The app's requests come from `https://localhost`, another origin, so the browser's cookie doesn't apply. The server allows that origin (CORS), and the app signs in with a bearer token instead: it sends `X-App-Client` when signing in, keeps the token it gets back in Capacitor's preferences, and sends it with every request. App sessions last a year. `web/src/native.ts` and `internal/account/app.go` explain the details.

**Sign in with Google** can't happen inside the app's web view (Google refuses), so the app opens the phone's browser instead, like OAuth for native apps (RFC 8252, RFC 7636). It keeps a random secret and opens `/api/auth/google?app_challenge=<its hash>`; when Google sends the browser back, the server redirects it to `com.rudnam.samfran://auth?code=…`, which Android hands to the app, and the app trades the code and the secret for a session token (`POST /api/auth/app-token`). This needs `ANDROID_PACKAGE` set on the server, which is also what publishes `/.well-known/assetlinks.json`.

Export isn't offered in the app (the download needs the browser's sign-in); use the website.

## The bus-stop alarm

The **Bus** tab's "I'm on the bus" starts a trip (`BusAlarmPlugin.java` → `BusTripService.java`): a foreground service, with its notification showing how far there is to go, that watches the phone's position (GPS, the network as a fallback) every few seconds. Within the stop's alert distance the phone **vibrates on repeat, with no sound**, until "I'm up" on the notification is tapped, the notification is swiped away, or samfran is opened (ten minutes at most; `StopBuzzReceiver.java`, `MainActivity.onResume`). A trip ends when the buzz stops, or after three hours. Positions never leave the phone; the server only stores the stop (`/api/bus/stop`).

Permissions: location (asked on the first trip, or when choosing the stop by standing at it), notifications (Android 13+), and a location foreground service.

## "Start bus alarm?" when a shift ends

Off until switched on, on the **Bus** tab ("Ask when a shift ends"), on the phone of whoever's shifts they are. The web app gives the phone the times the coming shifts end (`web/src/background.ts` → `RemindersPlugin.java`), again each time the app opens and whenever shifts or the stop are saved. `ShiftPrompt.java` sets an alarm for the next one; when it goes off it shows a notification, **Start bus alarm?**, whose button opens `com.rudnam.samfran://bus?start=1`: the Bus tab, which starts the trip as "I'm on the bus" does. A tap elsewhere on it opens the Bus tab without starting.

- The alarm is inexact (`setAndAllowWhileIdle`), so Android may show it a few minutes late; in return it needs no exact-alarm permission. A shift that ended more than 45 minutes ago (the phone was off) isn't asked about.
- The phone knows the shifts as of the last time the app was open on it. If the sheet changes on the other phone, this one catches up when samfran is next opened.
- Alarms don't survive a restart of the phone, so the same receiver sets them again on `BOOT_COMPLETED`.

## Notifications about the other person's changes

Off until switched on, on the **Us** tab ("Tell me about changes"). About every half hour, while the phone is online, `ActivityJob.java` (a `JobService`) asks the server for the recent changes (`GET /api/activity`, with the app's own session token) and shows a notification for the other person's new places, visits, ratings and saved shifts. Android runs the job when it suits the battery, so it can be later than that. The phone asks rather than being pushed to, which needs no Firebase project or Google services; real push would be faster and would need both.

Both need notification permission (asked when one is switched on), and `RECEIVE_BOOT_COMPLETED` to carry on after a restart. Opening samfran clears their notifications.

## Adding a place from Google Maps

In Google Maps, open a place, tap **Share**, and pick **samfran**. Android hands the app the text Maps shares (the place's name and a `maps.app.goo.gl` link; `ShareTargetPlugin.java` declares the app as a share target in `AndroidManifest.xml`), and the app opens Add a place with the name and the link filled in (`web/src/share.ts`). When Maps shares only the link, the server reads the place's name from where the link leads (`POST /api/restaurants/lookup`).

## Icons

The launcher icon is adaptive (Android 8+, the app's minimum): the penguin and the bear from `web/public/icon.svg` as a picture with a transparent background (`res/drawable-xxxhdpi/ic_launcher_foreground.png`, 432 px, drawn inside the adaptive icon's safe zone) on cream (`res/values/ic_launcher_background.xml`), plus a one-colour vector for themed icons (`res/drawable/ic_launcher_monochrome.xml`). The foreground is a picture rather than a vector because its soft shadows and plush edges need SVG filters, which Android vectors don't have; to change it, edit the SVG and render it again in a browser. The splash screen shows the same pair (`res/values/styles.xml`).
