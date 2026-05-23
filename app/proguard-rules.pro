# Keep VpnService classes
-keep class com.multipathvpn.MultiPathVpnService { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep AndroidX
-keep class androidx.core.app.** { *; }
