# ProGuard rules for OpenFolio

# Android & Coroutines
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# OpenCSV
-keep class com.opencsv.** { *; }
-dontwarn com.opencsv.**

# PDFBox Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Kotlinx Serialization
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Data Entities and Models
-keep class com.openfolio.core.data.local.entity.** { *; }
-keep class com.openfolio.core.data.local.model.** { *; }
-keep class com.openfolio.core.domain.model.** { *; }

# WorkManager & Hilt Workers
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.openfolio.core.worker.** { *; }
-keep class androidx.hilt.work.** { *; }
-dontwarn androidx.work.**
