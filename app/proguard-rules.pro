# The app has no third-party libraries and no reflection, so the default
# optimize rules are enough. Strip logging calls from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
