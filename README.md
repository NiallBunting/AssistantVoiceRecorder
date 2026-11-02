# Meeting Recorder

Native Kotlin Android recorder designed to be selected as the device's Digital Assistant. Invoking the selected assistant starts an AAC-in-M4A recording through a microphone foreground service; the persistent notification provides the Stop action.

## Build and install

Open this directory in Android Studio (JDK 17 and Android SDK Platform 35), then run the `app` configuration, or use:

```sh
./gradlew :app:assembleDebug
```

The resulting debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Install it, grant microphone access, and use **Set as default Assistant** in the app. Android/GrapheneOS will show the system role picker only when the Assistant role is available.

## Use

1. Choose a recordings folder. The selected SAF tree permission is persisted. Until then, recordings use the app's external-files directory.
2. Optionally configure the API URL and independently enable metadata notification and audio upload. Audio uses multipart form data and streams from storage; metadata is JSON.
3. Choose Wi-Fi-only audio uploads if desired. WorkManager retries transient failures. Local recordings are never removed for a failed, pending, or disabled upload.
4. Enable automatic deletion and set the retention period. Cleanup deletes records only when they are older than the period and either their **audio** upload state is `SUCCESS` or audio sending was disabled. Pending or failed audio uploads remain protected; metadata success does not count.

The provisional endpoint accepts both `POST` JSON metadata and `POST multipart/form-data` audio at the same configured URL. Change `NotifyWorker` and `AudioUploadWorker` when the real server supplies separate endpoints or authentication.

## GrapheneOS notes

The app registers the normal Android `VoiceInteractionService` architecture and requests `ROLE_ASSISTANT`; it does not intercept buttons or use accessibility services. Whether the Assistant gesture can be assigned and invoked is controlled by the GrapheneOS build and device policy, so it must be tested on the Pixel 6a. Recordings are designed to continue while locked/backgrounded via the microphone foreground service, subject to the OS's microphone privacy controls and battery policy.

## License

Copyright © 2026 Niall Bunting. Licensed under the GNU General Public License,
version 3 or later (GPL-3.0-or-later). See [LICENSE](LICENSE).
