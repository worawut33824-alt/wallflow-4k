# WallpaperService is instantiated by the system from the manifest.
-keep class com.admrichest.wallflow.wallpaper.WallFlowWallpaperService { *; }
-keep class com.admrichest.wallflow.wallpaper.RotationReceiver { *; }

# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }
