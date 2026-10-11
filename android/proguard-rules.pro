# smbj / BouncyCastle: 리플렉션으로 보안 알고리즘을 찾는다.
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.mbassy.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**

# 압축 라이브러리
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.net.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.lhcomics.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.lhcomics.** {
    kotlinx.serialization.KSerializer serializer(...);
}
