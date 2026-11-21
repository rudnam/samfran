# Android app

The Android app is a native app (Kotlin, Jetpack Compose, Material 3; Android's recommended architecture): signing in and the four tabs, Places, Shifts, Ride and Us, with every page the website has for them. It talks to the same server as the website (`web/`), which stays for browsers; nothing of the website is inside the app. It's built like RudWallet's native app, scaled down. See **Native app** below for how.

It also does what the website can't: the ride alarm (for the bus or the train), asking "Start the ride alarm?" when a shift ends, notifications about the other person's changes, and adding a place shared from Google Maps.

- Package `com.rudnam.samfran` (permanent once published), talking to https://samfran.rudnam.com (`samfran.server` in `gradle.properties`, or `-Psamfran.server=…`).
- App changes reach phones only with a new app build. Server changes reach them at once, so keep the API compatible with the last release and with older builds still in use (add fields; don't rename or remove them).
- Signing key: `keystore/samfran.jks` (alias `samfran`), with its passwords in `keystore/keystore.properties`. The folder is git-ignored and exists only on the owner's machine (and in the `android-release` GitHub environment): **back it up somewhere safe**. Without it no update installs over the app.
- Versions 1–9 were the Capacitor build (the website in a web view). The native app replaces it under the same package and key, so it installs as an update: the session (see **How the app signs in**), "Ask when a shift ends", "Tell me about changes", and alarms and checks already set on the phone all carry on. The theme and language picked in the old build lived in its web view and start over at Auto.

## Build

You need a JDK 21 (`JAVA_HOME`) and the Android SDK with platform 37 (`ANDROID_HOME`; `build.sh` uses `~/Android/Sdk` if it's unset). Install on a phone over USB (USB debugging on), or run the emulator (**Emulator** below).

```sh
./android/build.sh            # android/samfran.aab (Play Store) and android/samfran.apk
./android/build.sh install    # and install the APK on the phone plugged in
```

It runs the unit tests, then builds and signs both files with the key.

CI builds the app on every push and pull request (the `android` job in `.github/workflows/ci.yml`): the unit tests (which also draw every screen; the pictures are kept a week as the `native-screenshots` artifact, and on a pull request `./ci-results.sh <number>` downloads them), Android Lint, and a release APK without a signature (which checks R8's shrinking), kept a week as the `android-apk` artifact. It also builds the `preview` build type and keeps it as the `samfran-preview-apk` artifact, and on a pull request also as a pre-release asset (`android-pr-<number>`) with a comment linking to it, which a phone can open (the link needs the browser signed in to GitHub); `android-preview-cleanup.yml` deletes it when the pull request closes: an APK that installs on a phone as **SamFran Preview**, beside the real app. It's signed with `android/preview.jks`, which is in the repo with the password `android` because it protects nothing; every build has the same key, so each installs over the last without losing the sign-in. A machine without the memory for Gradle can sign that one instead of building:

```sh
./android/build.sh sign app-release-unsigned.apk   # -> android/samfran.apk
```

On this machine (WSL, about 8 GB), `~/.gradle/gradle.properties` gives Gradle less memory than the project asks for (2 GB, two workers). If the Kotlin compiler's own daemon hangs under memory pressure, `-Pkotlin.compiler.execution.strategy=in-process` compiles inside Gradle instead.

While working on the app, open `android/` in Android Studio, or `cd android && ./gradlew installDebug`. A debug build needs no signing key. It installs beside the real app as **SamFran Preview** (`com.rudnam.samfran.preview`, named in `app/src/debug`) and talks to the same server; Google sign-in returns to the real app (the server names its package), so sign in to the preview with email and password. `./gradlew installPreview` is the same as a release build (R8, not debuggable), which runs as fast as the real app; a debuggable Compose app is several times slower.

Every release needs a higher `versionCode`, because the app offers a build with a higher one as an update. `build.sh` takes it from `version.sh`: one above the APK that samfran.rudnam.com offers, and never below the floor in `app/build.gradle.kts` (`SAMFRAN_VERSION_CODE` and `SAMFRAN_VERSION_NAME` override both). The name is `versionName` there plus the code, e.g. `2.0.10`.

### Emulator

To reproduce a phone bug without the phone. WSL's KVM works for anyone in the `kvm` group (`sudo usermod -aG kvm $USER`, then restart WSL, or until then wrap commands in `sg kvm -c "…"`). The emulator takes about 2 GB of memory, so run only one Gradle build beside it (`--max-workers=4`).

```sh
sdkmanager emulator "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n samfran-test -k "system-images;android-36;google_apis;x86_64" -d pixel_7
emulator -avd samfran-test -no-window -no-audio -no-boot-anim -memory 2048 -gpu swiftshader_indirect &
adb shell getprop sys.boot_completed   # 1 once it has booted (several minutes)
```

To use it against `./preview.sh` instead of the real server:

- **Build:** `./gradlew installDebug -Psamfran.server=http://10.0.2.2:8081` (10.0.2.2 is this machine as the emulator sees it).
- **Allow plain HTTP:** for that build only, add a debug network security config that lets it reach 10.0.2.2 (`app/src/debug`, don't commit it).
- **Sign in:** as the preview user.
- **Reading a photo of the sheet:** the button shows only if the server has a reader, so give the preview's server any `ANTHROPIC_API_KEY`. With a fake key the read fails, but you see it start.

Drive the app with `adb shell uiautomator dump` (what's on screen, with bounds), `adb shell input tap X Y` and `adb shell input text …`. Take screenshots with `adb exec-out screencap -p > shot.png`. To add a photo, `adb push` it to `/sdcard/Pictures`.

## Releases and updates

The site offers the APK at `/download/samfran.apk`, and `GET /api/downloads` tells the app its `versionCode`, read from the APK itself. The app checks when it starts and, at most hourly, when it comes back into view (`UpdateRepository` in `core/data`). When there's a newer build, a notice above every tab offers **Update**, which downloads the APK in the phone's browser, or **Later**, which hides it until a still newer build. Copies from the Play Store are left to the Play Store (by the installer Android reports).

**Publishing** puts the APK in the deploy bucket's `downloads/samfran/` and copies it onto the server (rudnam-infra's `rudnam-downloads`), with no restart:

```sh
./android/build.sh && ./android/publish.sh    # by hand, signed in with aws login --profile rudnam-ec2
```

**GitHub Actions does it for you**: `.github/workflows/android-release.yml` builds, signs and publishes a release once CI passes on `main` for a pull request labelled `android-release` (`android-release-label.yml` adds the label to every pull request that changes the app; remove it to merge without a release), or when you run it by hand (Actions › Android release). It keeps the `.apk` and `.aab` as a GitHub release, `android-<versionCode>`. It needs, once, an **`android-release` environment** limited to the `main` branch, with:

| Kind | Name | Value |
|---|---|---|
| Secret | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 android/keystore/samfran.jks` |
| Secret | `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` | `storePassword` and `keyPassword` from `keystore/keystore.properties` |
| Secret | `ANDROID_MAPS_KEY` | The Google Maps key ("Google Maps key" below). Optional |
| Variable | `ANDROID_UPLOAD_CERT_SHA256` | The key's certificate, 64 hex digits without colons: `keytool -list -v -keystore android/keystore/samfran.jks -alias samfran` (the SHA256 line) |

The release checks the APK is signed with that certificate before publishing it.

## Native app

The structure follows Android's [architecture guide](https://developer.android.com/topic/architecture), as RudWallet's does: one activity, Compose screens with a ViewModel each, data flowing one way, an offline-first data layer, and Hilt putting it together.

| Module | What it is |
|---|---|
| `app` | The activity (`MainActivity`: edge to edge, the splash screen, and what opens the app: Google sign-in coming back, its own notifications' links, a place shared from Maps), the scaffold with the tabs and the notices above them (`ui/SamFranApp.kt`; a newer build, the demo, confirming the email), navigation (`ui/SamFranAppState.kt`), and what runs while the app is closed: the ride alarm (`BusTripService`, `StopBuzzReceiver`), "Start the ride alarm?" (`ShiftPrompt`) and the changes check (`ActivityJob`), with `background/` connecting them to the screens. |
| `feature/signin` | Signing in (`SignInRoute`, the first page with nobody signed in): email and password, creating an account, a forgotten password (the emailed link opens the website), Google in a Custom Tab, and the demo. A language picker, as on the website's top bar. |
| `feature/food` | The Food tab: Been there and Want to go (latest visit first, newest first), search through names, areas, food, notes and visits' notes, pull to refresh. A place's page: both ratings, notes, every visit, logging one (Material's date picker, today at the latest), removing one with Undo, and deleting the place. Adding (been there with a first visit and your rating, or want to go) and editing: a Google Maps link fills in the name, and so does a place shared from Maps. |
| `feature/shifts` | The Shifts tab: the month as a calendar or a list (the arrows change month in place), the next shift, who's on the same shift, reading a photo of the sheet (taken with the camera, or chosen with Android's photo picker; shrunk to 2400 px first, as the website does), and checking or fixing a month by hand, with the common codes as one-tap chips that fill the day and move on. Shift settings: whose shifts, the name on the sheet, each letter's length, and the calendar link (copy, share, a new one, off). |
| `feature/bus` | The Ride tab (the website's ride alarm), in three pages. The ride: where to get off (a chip for each stop, the shift's picked), a map of the stop, **I'm on the bus** (or **I'm on the train**, to a station), **Try it now** (a six-second practice that really buzzes), the ride with a progress line, and one big **Stop**. **Settings**: the stops, and how it works (with OpenStreetMap's credit). A stop's page: a small map, then a row for each setting, each opening its choices from the bottom of the screen (an overlay in the page, `Sheet`): where it is (the search, on its own page: by name and town from the OpenStreetMap search, each result's icon saying bus stop or station; or a Google Maps link, or where the phone is), how early (stations offer 1 to 5 km), how it buzzes there, a switch for the sound (with **Hear the sound**), the shifts it's for, and whether to ask when they end. A saved stop saves each change as it's made; a new one starts with the search and is added with **Add stop**. The first stop starts with the search, under how it works. The map is Google's keyless embed, as on the website, in a web view (`StopMap.kt`). |
| `feature/us` | The Us tab: the two of you, inviting the other one (the link goes out through Android's share sheet), your name, recent changes with what's new marked, and this phone: notifications about changes, language, appearance, signing out (or ending the demo). |
| `core/model` | The app's types and the website's logic in plain Kotlin: shift codes (`ShiftCodes`, as `web/src/features/shifts/codes.ts`), reading text shared from Maps (`Shared`, as `web/src/share.ts`), distances. |
| `core/network` | The API with Retrofit and kotlinx.serialization (`SamFranNetwork`); `AuthInterceptor` signs requests in as the app. |
| `core/database` | Room: the phone's copy of the server's answers, one row per answer under the website's page-cache keys (`restaurants`, `restaurant:12`, `shifts:2026-11`, `space`, `bus-stop`, `activity`). Every screen shows only whole answers and the data is small, so it keeps them as they came rather than in tables of rows; a new database version can always start empty. |
| `core/data` | Repositories, offline first: screens read Room's flows, and `refresh` brings Room up to date from the server. A change goes to the server first; its answer replaces the copy (there's no outbox: a change made offline says it can't reach the server). `Outcome` says how a call went. `Reminders` and `BusAlarm` are what the screens ask of the background parts in `app`. |
| `core/datastore` | The session (`EncryptedSessionStore`: the token sealed with an Android Keystore key, AES-GCM; the Capacitor build's token, in its preferences, is moved there once) and the user's preferences (DataStore: appearance, what's been seen, the shift view). |
| `core/designsystem` | SamFran on Material 3: the colours (`theme/Theme.kt`, the same tokens as `web/src/styles.css`, light and dark, including the two people's, the tiles' and the shifts'), Plus Jakarta Sans (`res/font`, converted from the website's by `android/fonts.py`), the line icons, cards, buttons and notices like the website's. |
| `core/ui` | Pieces that know the models: dates in the app's language, the people's badges and rating chips, the star picker, the date field, the pages' frames (`TabPage`, `SubPage`, which keep content clear of the system bars), `ServerText` (the server's English messages in Japanese: the website's table, which `ServerTextTest` keeps the same). |
| `core/common`, `core/testing` | Dispatchers and the clock, injected; fakes of the repositories and sample data for tests. |

Build setup: versions in `gradle/libs.versions.toml` (RudWallet's), shared module setup in `build-logic` (convention plugins `samfran.android.*`). Release builds are shrunk with R8. Strings are resources in English and Japanese (`values-ja`), worded as the website's; the app's language is Android's per-app language: Us › Language, or the phone's settings for the app.

**Tests** run on the JVM: `./gradlew testDebugUnitTest` in `android/`. ViewModels against fake repositories (`core/testing`), the repositories against a mock server and an in-memory database, the session's move from the Capacitor build, the website's logic (`core/model`), and screenshots of every screen with Robolectric and Roborazzi (light, dark, Japanese) in each module's `build/outputs/roborazzi` with `-Proborazzi.test.record=true`. `./verify.sh` doesn't run them: it tests the server and the website.

To add a page: a route and its screen in its tab's `feature/*` module, its calls in `core/network` and `core/data`, and its strings in both languages.

## Starting up, and working offline

The splash screen stays up until the session is known, 3 seconds at most. Every screen opens on the phone's copy at once and asks the server meanwhile; offline, it shows what it has and says the server can't be reached. Changes need a connection. Signing out empties the phone's copy.

Everything draws edge to edge: the tab pages keep their content clear of the status bar (the Capacitor build's top bar sat under it on Android 16), pages opened over a tab keep clear of the navigation bar and the keyboard too, and the bars' icons follow the app's appearance.

## How the app signs in

The app signs in with a bearer token rather than the website's cookie: it sends `X-App-Client` when signing in, keeps the token it gets back (`EncryptedSessionStore`), and sends it with every request (`AuthInterceptor`), exactly as the Capacitor build did, so the server needed nothing new. App sessions last a year. A 401 on a request made with the session means it has ended: the app forgets it and goes back to signing in. `internal/account/app.go` explains the server's side. (The server still allows the Capacitor build's `https://localhost` origin, for phones not updated yet.)

**Sign in with Google** can't happen inside an app (Google refuses), so the app opens the phone's browser in a Custom Tab, like OAuth for native apps (RFC 8252, RFC 7636). It keeps a random secret and opens `/api/auth/google?app_challenge=<its hash>`; when Google sends the browser back, the server redirects it to `com.rudnam.samfran://auth?code=…`, which Android hands to the app, and the app trades the code and the secret for a session token (`POST /api/auth/app-token`). This needs `ANDROID_PACKAGE` set on the server, which is also what publishes `/.well-known/assetlinks.json`.

Some things stay on the website, because they need its sign-in in the browser: the emailed links (confirming an email, choosing a new password, an invite), Claude's connect page, and exporting.

## The ride alarm

The **Ride** tab's "I'm on the bus" (or "I'm on the train") starts a trip (`BusTripService`): a foreground service, with its notification showing how far there is to go, that watches the phone's position (GPS, the network as a fallback) every few seconds. Within the stop's alert distance the phone **vibrates on repeat**, the way the stop's "How it buzzes" says (`Buzz` in `core/model`: gentle is the phone's usual strength; strong and strongest are its full strength, where the phone can set it). If that isn't stopped within the stop's `sound_after` (a minute, unless switched off on its page; the server also allows 30 s and 2 min), the phone's **alarm sound** joins in, rising from quiet to the alarm volume over 20 seconds: it plays as an alarm, so on silent too, through the speaker (Android plays alarms there even with earphones in), and at the phone's alarm volume, so not at all if that's turned right down. It goes on until **Stop** is tapped (in the app or on the notification), the notification is swiped away, or SamFran is opened (ten minutes at most; `StopBuzzReceiver`, `MainActivity.onResume`). A trip ends when the buzz stops, or after three hours. Positions never leave the phone; the server only stores the stop (`/api/bus/stop`). "Try it now" rides for six seconds and then really buzzes, the same way but without the sound; **Hear the sound**, on a stop's page, plays six seconds of it.

The stop's map (`StopMap`) is Google's native map (Maps SDK for Android through maps-compose) in lite mode: a still map with a pin, as Google suggests for a small map in a scrolling page; tapping it opens Google Maps. That needs a Google Maps key, below. A build without one shows Google's keyless web map instead, in a WebView, inside an iframe as the website does (Google refuses to draw it as a whole page).

### Google Maps key

The key isn't in the repository (it's public). Make it once in a Google Cloud project with billing turned on: enable **Maps SDK for Android**, create an API key, and restrict it to Android apps, adding `com.rudnam.samfran` with the release key's SHA-1 (`keytool -list -v -keystore android/keystore/samfran.jks -alias samfran`) and, for debug builds, `com.rudnam.samfran.preview` with `~/.android/debug.keystore`'s SHA-1 (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`). Maps on Android cost nothing. Then put it in `~/.gradle/gradle.properties` as `samfran.mapsKey=…` (or `SAMFRAN_MAPS_KEY` in the environment) and in the `android-release` environment's `ANDROID_MAPS_KEY` secret. Pull-request builds don't get it, so their screenshots and preview APKs show the web map.

Permissions: location (asked on the first trip, or when choosing the stop by standing at it), notifications (Android 13+), and a location foreground service.

## "Start the ride alarm?" when a shift ends

Off until switched on for a stop, on its page in the Ride tab's **Settings** ("Ask when these end"; it asks for notification permission). Stops are each person's but the shifts are one person's, so only the stops of whoever's shifts they are should ask. (It used to be one switch for the phone; a phone that had it on switches it on for its person's stops, once, when it updates.) The app gives `ShiftPrompt` the times the coming shifts end (last, this and next month's; `AndroidReminders` in `app/background`), again each time the app opens and whenever shifts or the stop are saved. `ShiftPrompt` sets an alarm for the next one; when it goes off it shows a notification, **Start the ride alarm?**, naming the stop for that kind of shift (or else the one used last), whose button opens `com.rudnam.samfran://bus?start=1&stop=<id>`: the Ride tab, which starts the trip to that stop as "I'm on the bus" does. With more than one stop, **Other stop** opens `com.rudnam.samfran://bus?pick=1`, to choose where to get off and start from there. A tap elsewhere on it opens the Ride tab without starting.

- The alarm is inexact (`setAndAllowWhileIdle`), so Android may show it a few minutes late; in return it needs no exact-alarm permission. A shift that ended more than 45 minutes ago (the phone was off) isn't asked about.
- The phone knows the shifts as of the last time the app was open on it. If the sheet changes on the other phone, this one catches up when SamFran is next opened.
- Alarms don't survive a restart of the phone, so the same receiver sets them again on `BOOT_COMPLETED`.

## Notifications about the other person's changes

Off until switched on, on the **Us** tab ("Tell me about changes"). About every half hour, while the phone is online, `ActivityJob` (a `JobService`) asks the server for the recent changes (`GET /api/activity`, with the app's session) and shows a notification for the other person's new places, visits, ratings and saved shifts. It tells about the other person starting a bus alarm too: the job asks with `?bus=1`, which includes those (`bus_started`), though Recent changes on the Us tab leaves them out. Android runs the job when it suits the battery, so it can be later than that. The phone asks rather than being pushed to, which needs no Firebase project or Google services; real push would be faster and would need both.

Both need notification permission (asked when one is switched on), and `RECEIVE_BOOT_COMPLETED` to carry on after a restart. Opening SamFran clears their notifications. Their settings live in the `reminders` preferences, under the names the Capacitor build used, and the classes keep its names, so what a phone had switched on keeps working after the update.

## Adding a place from Google Maps

In Google Maps, open a place, tap **Share**, and pick **SamFran**. Android hands the app the text Maps shares (the place's name and a `maps.app.goo.gl` link; `AndroidManifest.xml` declares the app as a share target), and the app opens Add a place with the name and the link filled in (`Shared` in `core/model`). When Maps shares only the link, the server reads the place's name from where the link leads (`POST /api/restaurants/lookup`).

## Icons

The launcher icon is adaptive (Android 8+, the app's minimum): the penguin and the bear from `web/public/icon.svg` as a picture with a transparent background (`res/drawable-xxxhdpi/ic_launcher_foreground.png`, 432 px, drawn inside the adaptive icon's safe zone) on burnt orange (`res/values/ic_launcher_background.xml`), plus a one-colour vector for themed icons (`res/drawable/ic_launcher_monochrome.xml`). The foreground is a picture rather than a vector because its soft shadows and plush edges need SVG filters, which Android vectors don't have; to change it, edit the SVG and render it again in a browser. The splash screen shows the same pair (`res/values/styles.xml`), and so does the sign-in page.
