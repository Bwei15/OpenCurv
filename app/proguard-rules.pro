# BRouter loads model classes ("---model:btools.router.KinematicModel") by name.
-keep class btools.router.** { *; }
-keep class btools.expressions.** { *; }
-keep class btools.mapaccess.** { *; }
-keep class btools.codec.** { *; }
-keep class btools.util.** { *; }

# Mapsforge instantiates render-theme handlers reflectively via XmlPull.
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**
-dontwarn com.caverock.androidsvg.**

# Kotlin coroutines / Compose defaults are handled by their own rules.
-dontwarn org.xmlpull.v1.**
