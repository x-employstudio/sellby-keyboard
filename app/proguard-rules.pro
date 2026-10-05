# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# The native library finds these classes and fields BY NAME (RegisterNatives / FindClass / GetFieldID in
# app/src/main/jni), e.g. WordInputEventForPersonalization.mTargetWord, which Java only ever writes - R8 would
# treat such a field as unused and remove it, and the native lookup would then fail at runtime. The whole
# package is tiny (5 classes), so keep all of it as is. (Release builds are the first minified+optimized
# ones; debug builds run with -dontoptimize.)
-keep class com.android.inputmethod.** { *; }

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
