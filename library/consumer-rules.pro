# kotlinx.serialization - preserve @Serializable classes for consumer apps using R8
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.music961.pintocore.log.model.**$$serializer { *; }
-keepclassmembers class com.music961.pintocore.log.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.music961.pintocore.log.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room — entity/DAO 보존 (라이브러리에서 정의되는 경우)
-keep class com.music961.pintocore.log.**.*Entity { *; }
-keep class com.music961.pintocore.log.**.*Dao { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Hilt — 생성 클래스 보존
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
