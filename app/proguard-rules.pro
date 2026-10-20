-allowaccessmodification
-repackageclasses

# Restliche Kotlin-Null-Checks entfernen
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
}
