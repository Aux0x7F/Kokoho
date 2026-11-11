# R8 shrinks the libraries; Kokoho's own classes stay whole and keep their names:
# Gson reads and writes many of them by field name (exports, stores, WiGLE, watchlists),
# Room and the Waze protobuf classes are looked up by name, and crash traces stay readable.
-keep class org.tucsoncommunity.kokoho.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# Lite protobuf (Waze direct backend) reads its message fields by reflection.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
