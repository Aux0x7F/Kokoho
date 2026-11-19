# Aggressive R8 minification, obfuscation, and package flattening for release builds.
-repackageclasses 'o'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
-keepattributes Signature,*Annotation*

# Strip debug and verbose log calls from release bytecode.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Preserve enum value lookup & Gson enum constant names while obfuscating enum class names.
-keepclassmembers,allowoptimization enum org.tucsoncommunity.kokoho.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Gson generic TypeToken superclasses (allow class-name obfuscation so outer names do not leak).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken

# Gson-serialized models: obfuscate all DTO class names into 'o.*' while keeping constructors & exact JSON field names.
-keep,allowobfuscation class
    org.tucsoncommunity.kokoho.alpr.KnownCamera,
    org.tucsoncommunity.kokoho.alpr.CameraArea,
    org.tucsoncommunity.kokoho.alpr.CctvCamera,
    org.tucsoncommunity.kokoho.data.AlertLog$Entry,
    org.tucsoncommunity.kokoho.data.FalseAlerts$Entry,
    org.tucsoncommunity.kokoho.data.ProbeBook$Network,
    org.tucsoncommunity.kokoho.data.ProbeBook$Spot,
    org.tucsoncommunity.kokoho.data.ProbeBook$Device,
    org.tucsoncommunity.kokoho.data.TrackerMutes$Mute,
    org.tucsoncommunity.kokoho.data.TrustRules$Rule,
    org.tucsoncommunity.kokoho.data.TrustedNetworks$Rule,
    org.tucsoncommunity.kokoho.data.DetectionEntity,
    org.tucsoncommunity.kokoho.data.WhitelistEntity,
    org.tucsoncommunity.kokoho.detect.RemoteId$Info,
    org.tucsoncommunity.kokoho.nav.Place,
    org.tucsoncommunity.kokoho.online.Wigle$Cached,
    org.tucsoncommunity.kokoho.online.Wigle$Net,
    org.tucsoncommunity.kokoho.online.WiglePins$Pin,
    org.tucsoncommunity.kokoho.oui.OuiEntry,
    org.tucsoncommunity.kokoho.posture.WitnessReportRecord,
    org.tucsoncommunity.kokoho.posture.DeviceTrustManager$SeatIdentity,
    org.tucsoncommunity.kokoho.posture.DeviceTrustManager$AuthResponseDto,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$RfChannelSig,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$TalkgroupSig,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$FleetOuiSig,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$CatalogJson,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$LearnedMacIntel,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$LearnedMacBlock,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteFlightTrack,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteWitnessDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$SyncedPlateRecord,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$BugReportSubmission,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteEntityDetailsDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteConfidence,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteLocation,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteEvidence,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteClassification,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteThreatObject,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteTrendEntity,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteHeatmapCell,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteLearnedMacDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteMacBlockDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$TrendsSnapshot,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$StateSnapshot,
    org.tucsoncommunity.kokoho.sdr.FreqNames$Name,
    org.tucsoncommunity.kokoho.service.CellTowerStore$Tower {
    <init>(...);
}

-keepclassmembers class
    org.tucsoncommunity.kokoho.alpr.KnownCamera,
    org.tucsoncommunity.kokoho.alpr.CameraArea,
    org.tucsoncommunity.kokoho.alpr.CctvCamera,
    org.tucsoncommunity.kokoho.data.AlertLog$Entry,
    org.tucsoncommunity.kokoho.data.FalseAlerts$Entry,
    org.tucsoncommunity.kokoho.data.ProbeBook$Network,
    org.tucsoncommunity.kokoho.data.ProbeBook$Spot,
    org.tucsoncommunity.kokoho.data.ProbeBook$Device,
    org.tucsoncommunity.kokoho.data.TrackerMutes$Mute,
    org.tucsoncommunity.kokoho.data.TrustRules$Rule,
    org.tucsoncommunity.kokoho.data.TrustedNetworks$Rule,
    org.tucsoncommunity.kokoho.data.DetectionEntity,
    org.tucsoncommunity.kokoho.data.WhitelistEntity,
    org.tucsoncommunity.kokoho.detect.RemoteId$Info,
    org.tucsoncommunity.kokoho.nav.Place,
    org.tucsoncommunity.kokoho.online.Wigle$Cached,
    org.tucsoncommunity.kokoho.online.Wigle$Net,
    org.tucsoncommunity.kokoho.online.WiglePins$Pin,
    org.tucsoncommunity.kokoho.oui.OuiEntry,
    org.tucsoncommunity.kokoho.posture.WitnessReportRecord,
    org.tucsoncommunity.kokoho.posture.DeviceTrustManager$SeatIdentity,
    org.tucsoncommunity.kokoho.posture.DeviceTrustManager$AuthResponseDto,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$RfChannelSig,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$TalkgroupSig,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$FleetOuiSig,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$CatalogJson,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$LearnedMacIntel,
    org.tucsoncommunity.kokoho.posture.KokohoSignatures$LearnedMacBlock,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteFlightTrack,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteWitnessDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$SyncedPlateRecord,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$BugReportSubmission,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteEntityDetailsDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteConfidence,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteLocation,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteEvidence,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteClassification,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteThreatObject,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteTrendEntity,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteHeatmapCell,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteLearnedMacDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$RemoteMacBlockDto,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$TrendsSnapshot,
    org.tucsoncommunity.kokoho.posture.KokohoHubClient$StateSnapshot,
    org.tucsoncommunity.kokoho.sdr.FreqNames$Name,
    org.tucsoncommunity.kokoho.service.CellTowerStore$Tower {
    <fields>;
}

# Room database generated implementation lookup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep,allowobfuscation @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}

# Lite protobuf (Waze direct backend) reads its message fields by reflection; allow class-name obfuscation.
-keep,allowobfuscation class * extends com.google.protobuf.GeneratedMessageLite { *; }
