# ProGuard rules for Calendar Add AI

# Keep LLM Engine classes
-keep class com.calendaradd.model.LlmEngine { *; }
-keep class com.calendaradd.service.TextAnalysisService { *; }
-keep class com.calendaradd.service.EventExtraction { *; }
-keep class com.calendaradd.service.ExtractionService { *; }

# LiteRT-LM native code looks up Java classes and accessors by exact JNI names.
-keep class com.google.ai.edge.litertlm.** { *; }

# Keep Room database classes
-keep class com.calendaradd.usecase.EventDatabase { *; }
-keep class com.calendaradd.usecase.EventDao { *; }
-keep class com.calendaradd.usecase.Event { *; }

# Keep data classes
-keep class com.calendaradd.usecase.InputContext { *; }
-keep class com.calendaradd.usecase.UserPreferences { *; }
-keep class com.calendaradd.usecase.EventResult { *; }
-keep class com.calendaradd.model.ModelInfo { *; }

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keep class kotlinx.coroutines.CoroutineDispatcher { *; }

# Keep asset paths
-keep @android.webkit.ResourceLoader* class ** { *; }

# Allow reflection for AndroidX and Jetpack Compose
-keepnames class kotlinx.coroutines.MainDispatcher { *; }
-keep class * implements kotlin.coroutines.CoroutineScope

# Keep navigation components
-keep class androidx.navigation.NavHostController { *; }
-keep class androidx.navigation.NavGraph { *; }
-keep class androidx.navigation.NavController { *; }

# Keep Material Design components
-keep class androidx.compose.material3.* { *; }
-keep class androidx.compose.foundation.* { *; }

# Keep Jetpack Compose runtime
-keep class androidx.compose.runtime.* { *; }
-keep class androidx.compose.ui.* { *; }

# Keep Room compiler
-keep class androidx.room.* { *; }
-keepnames class androidx.room.RoomDatabase { *; }

# Keep asset manager
-keep class android.content.res.AssetManager { *; }

# Keep Java NIO classes
-keep class java.nio.* { *; }

# Keep URL classes
-keep class java.net.* { *; }

# Keep coroutines-test
-keep class kotlinx.coroutines.test.* { *; }
