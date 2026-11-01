# Kin

1. Create a Firebase project, add an Android app with package `com.kin.app`, download `google-services.json` into `app/`.
2. Enable Authentication > Google (add your SHA-1, then re-download `google-services.json`) and create Firestore, then deploy `firestore.rules`. Storage is not used.
3. Open in Android Studio (Ladybug+), let it generate the Gradle wrapper, run on a device.
4. Grant Notification access when prompted, then share your Tag with a friend.
