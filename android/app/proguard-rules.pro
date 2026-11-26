# The libraries (Retrofit, kotlinx.serialization, Room, Hilt, Compose) bring their own keep rules.

# WorkManager makes its input mergers and workers by reflection, and R8 (full mode, AGP 9's
# default) drops the constructors it calls. Glance draws the home-screen widgets in a
# WorkManager job, so without these the widgets stay on their loading screen for good.
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
