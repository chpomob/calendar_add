# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in your gradle build file.

# Keep TF Lite classes
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
