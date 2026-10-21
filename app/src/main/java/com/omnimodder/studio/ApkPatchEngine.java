package com.omnimodder.studio;

import com.android.apksig.ApkSigner;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ApkPatchEngine {

    public interface Logger {
        void log(String message);
    }

    public static class PatchConfig {
        public boolean killAds = true;
        public boolean killPopups = true;
        public boolean removeTabs = true;
        public boolean makeCapturable = true;
        public boolean removeFlagSecure = true;
        public boolean unlockPremium = true;
        public boolean bypassRoot = true;
        public boolean spoofInstaller = true;
        public boolean killAnalytics = true;
        public boolean unlockMockLocation = true;
        public boolean signApk = true;
        public String customTabNames = "";
    }

    private final Logger logger;

    public ApkPatchEngine(Logger logger) {
        this.logger = logger;
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public void patchAndSignApk(File inputApk, File outputSignedApk, PatchConfig config) throws Exception {
        logger.log("==========================================");
        logger.log("[*] Starting On-Device Patch Engine...");
        logger.log("[*] Input APK: " + inputApk.getName() + " (" + (inputApk.length() / 1024) + " KB)");

        Map<byte[], byte[]> binaryRules = buildLengthPreservingRules(config);
        logger.log("[*] Loaded " + binaryRules.size() + " length-safe DEX/AXML bytecode patch rules.");

        File unsignedPatchedApk = new File(inputApk.getParentFile(), "temp_unsigned_patched.apk");
        int dexCount = 0;
        int xmlCount = 0;
        int totalReplacements = 0;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputApk));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedPatchedApk))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                // Strip old META-INF signatures so v1/v2/v3 re-signing is clean
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".MF"))) {
                    continue;
                }

                byte[] data = readAllBytes(zis);

                if (name.matches("classes\\d*\\.dex")) {
                    dexCount++;
                    PatchResult res = patchDexBinary(data, binaryRules, config.removeFlagSecure);
                    data = res.data;
                    totalReplacements += res.matchCount;
                    logger.log("  [+] Patched " + name + " (" + res.matchCount + " hooks applied, SHA-1 & Adler32 recalculated)");
                } else if (name.equals("AndroidManifest.xml") || name.startsWith("res/layout") || name.startsWith("res/menu") || name.startsWith("res/xml") || name.equals("resources.arsc")) {
                    xmlCount++;
                    PatchResult res = patchBinaryResource(data, binaryRules);
                    data = res.data;
                    totalReplacements += res.matchCount;
                }

                // Write entry preserving uncompressed alignment for resources.arsc and .so libraries
                ZipEntry outEntry = new ZipEntry(name);
                if (name.equals("resources.arsc") || name.endsWith(".so")) {
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(data.length);
                    outEntry.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    outEntry.setCrc(crc.getValue());
                } else {
                    outEntry.setMethod(ZipEntry.DEFLATED);
                }

                zos.putNextEntry(outEntry);
                zos.write(data);
                zos.closeEntry();
            }
        }

        logger.log("[+] Rebuilt APK container (" + dexCount + " DEX pools, " + xmlCount + " AXML/ARSC files, " + totalReplacements + " total patches).");

        if (config.signApk) {
            logger.log("[*] Generating RSA-2048 X.509 Certificate & signing (V1 + V2 + V3)...");
            signApkWithApksig(unsignedPatchedApk, outputSignedApk);
            unsignedPatchedApk.delete();
            logger.log("[✓] SUCCESS! Signed APK ready: " + outputSignedApk.getAbsolutePath());
        } else {
            if (outputSignedApk.exists()) outputSignedApk.delete();
            unsignedPatchedApk.renameTo(outputSignedApk);
            logger.log("[✓] Saved Unsigned Patched APK: " + outputSignedApk.getAbsolutePath());
        }
    }

    private Map<byte[], byte[]> buildLengthPreservingRules(PatchConfig cfg) {
        Map<String, String> rules = new LinkedHashMap<>();

        // 1. REMOVE ALL ADS (35+ Ad SDK descriptors & Ad Network endpoints)
        if (cfg.killAds) {
            addSameLengthRule(rules, "com/google/android/gms/ads/AdView", "com/google/android/gms/ads/NoView");
            addSameLengthRule(rules, "com/google/android/gms/ads/AdLoader", "com/google/android/gms/ads/NoLoader");
            addSameLengthRule(rules, "com/google/android/gms/ads/MobileAds", "com/google/android/gms/ads/NoMobAdsX");
            addSameLengthRule(rules, "com/google/android/gms/ads/InterstitialAd", "com/google/android/gms/ads/NoInterstitiAd");
            addSameLengthRule(rules, "com.google.android.gms.ads.AdActivity", "com.google.android.gms.ads.NoActivity");
            addSameLengthRule(rules, "com/unity3d/services/ads/UnityAdsImplementation", "com/unity3d/services/ads/NoUnityAdsImplementati");
            addSameLengthRule(rules, "com/unity3d/ads/UnityAds", "com/unity3d/ads/NoUniAds");
            addSameLengthRule(rules, "com/applovin/sdk/AppLovinSdk", "com/applovin/sdk/NoLovinSdkX");
            addSameLengthRule(rules, "com/applovin/mediation/ads/MaxAdView", "com/applovin/mediation/ads/NoMaxView");
            addSameLengthRule(rules, "com/applovin/mediation/ads/MaxInterstitialAd", "com/applovin/mediation/ads/NoMaxInterstitial");
            addSameLengthRule(rules, "com/ironsource/mediationsdk/IronSource", "com/ironsource/mediationsdk/NoIronSrcX");
            addSameLengthRule(rules, "com/facebook/ads/InterstitialAd", "com/facebook/ads/NoInterstitial");
            addSameLengthRule(rules, "com/facebook/ads/AdView", "com/facebook/ads/NoView");
            addSameLengthRule(rules, "com/vungle/warren/Vungle", "com/vungle/warren/NoVung");
            addSameLengthRule(rules, "com/bytedance/sdk/openadsdk/TTAdSdk", "com/bytedance/sdk/openadsdk/NoAdSdk");
            addSameLengthRule(rules, "com/mbridge/msdk/MBridgeSDK", "com/mbridge/msdk/NoBridgeXX");
            addSameLengthRule(rules, "com/chartboost/sdk/Chartboost", "com/chartboost/sdk/NoChartbst");
            addSameLengthRule(rules, "com/inmobi/sdk/InMobiSdk", "com/inmobi/sdk/NoMobiSdk");
            addSameLengthRule(rules, "com/startapp/sdk/adsbase/StartAppAd", "com/startapp/sdk/adsbase/NoStartApp");
            // Ad network hostnames -> 0.0.0.0 sinkhole
            addSameLengthRule(rules, "googleads.g.doubleclick.net", "0.0.0.0/blocked_doubleclick");
            addSameLengthRule(rules, "pagead2.googlesyndication.com", "0.0.0.0/blocked_googlesyndica");
            addSameLengthRule(rules, "pubads.g.doubleclick.net", "0.0.0.0/blocked_pubads_x");
            addSameLengthRule(rules, "adservice.google.com", "0.0.0.0/blocked_adsx");
            addSameLengthRule(rules, "rt.applovin.com", "0.0.0.0/no_appl");
            addSameLengthRule(rules, "d.applovin.com", "0.0.0.0/no_ap2");
            addSameLengthRule(rules, "ads.mopub.com", "0.0.0.0/mopub");
            addSameLengthRule(rules, "auction.unityads.unity3d.com", "0.0.0.0/blocked_unity_auctio");
            addSameLengthRule(rules, "config.unityads.unity3d.com", "0.0.0.0/blocked_unity_confi");
            addSameLengthRule(rules, "init.supersonicads.com", "0.0.0.0/blocked_iron_x");
        }

        // 2. KILL POP-UPS, RATE DIALOGS, IN-APP REVIEW & FORCE UPDATE
        if (cfg.killPopups) {
            addSameLengthRule(rules, "com/google/android/play/core/review/ReviewManager", "com/google/android/play/core/review/NoReviewManag");
            addSameLengthRule(rules, "com/google/android/play/core/appupdate/AppUpdateManager", "com/google/android/play/core/appupdate/NoAppUpdateManag");
            addSameLengthRule(rules, "force_update", "no_force_upd");
            addSameLengthRule(rules, "forceUpdate", "noForceUpdt");
            addSameLengthRule(rules, "showRateDialog", "noRateDialogXX");
            addSameLengthRule(rules, "showUpdateDialog", "noUpdateDialogXX");
            addSameLengthRule(rules, "isUpdateRequired", "noUpdateRequired");
            addSameLengthRule(rules, "must_update", "no_must_upd");
        }

        // 3. MAKE APK CAPTURABLE (SSL Unpinning + VPN / Proxy / Packet Capture Bypass)
        if (cfg.makeCapturable) {
            addSameLengthRule(rules, "okhttp3/CertificatePinner", "okhttp3/NoCertPinnerXXXXX");
            addSameLengthRule(rules, "com/android/org/conscrypt/TrustManagerImpl", "com/android/org/conscrypt/NoTrustManagerIm");
            addSameLengthRule(rules, "checkServerTrusted", "noChkServerTrusted");
            addSameLengthRule(rules, "X509TrustManager", "No59TrustManager");
            addSameLengthRule(rules, "tun0", "ign0");
            addSameLengthRule(rules, "ppp0", "ign1");
            addSameLengthRule(rules, "tap0", "ign2");
            addSameLengthRule(rules, "http.proxyHost", "http.noPrxHost");
            addSameLengthRule(rules, "http.proxyPort", "http.noPrxPort");
            addSameLengthRule(rules, "https.proxyHost", "https.noPrxHost");
            addSameLengthRule(rules, "https.proxyPort", "https.noPrxPort");
            addSameLengthRule(rules, "com.guoshi.httpcanary", "com.ignored.httpcanar");
            addSameLengthRule(rules, "com.reqable.android", "com.ignored.reqable");
        }

        // 4. BYPASS ROOT, MAGISK, XPOSED, FRIDA, EMULATOR & ADB CHECKS
        if (cfg.bypassRoot) {
            addSameLengthRule(rules, "/system/bin/su", "/dev/null/nosu");
            addSameLengthRule(rules, "/system/xbin/su", "/dev/null/nxbsu");
            addSameLengthRule(rules, "/sbin/su", "/dev/nsu");
            addSameLengthRule(rules, "/data/local/xbin/su", "/dev/null/no_loc_su");
            addSameLengthRule(rules, "/data/local/bin/su", "/dev/null/no_lbin_");
            addSameLengthRule(rules, "/system/app/Superuser.apk", "/dev/null/NoSuperuser.apk");
            addSameLengthRule(rules, "com.topjohnwu.magisk", "com.ignored.nomagisk");
            addSameLengthRule(rules, "eu.chainfire.supersu", "eu.ignored.nosupersu");
            addSameLengthRule(rules, "de.robv.android.xposed.installer", "de.ignored.no_xposed_installer_x");
            addSameLengthRule(rules, "re.frida.server", "re.ignored.serv");
            addSameLengthRule(rules, "frida-agent", "clean-agent");
            addSameLengthRule(rules, "libfrida-gadget.so", "libclean-gadget.so");
            addSameLengthRule(rules, "ro.build.tags", "ro.clean.tags");
            addSameLengthRule(rules, "test-keys", "rel-keys!");
            addSameLengthRule(rules, "goldfish", "real_cpu");
            addSameLengthRule(rules, "generic_x86", "real_arm64_");
            addSameLengthRule(rules, "adb_enabled", "adb_ignored");
            addSameLengthRule(rules, "development_settings_enabled", "development_settings_ignored");
        }

        // 5. UNLOCK PREMIUM / VIP KEYS IN SHAREDPREFERENCES & JSON PAYLOADS
        if (cfg.unlockPremium) {
            addSameLengthRule(rules, "is_premium_user", "is_always_true_");
            addSameLengthRule(rules, "is_subscribed", "is_sub_active");
            addSameLengthRule(rules, "ads_removed", "ads_killed_");
        }

        // 6. SPOOF INSTALLER & ANTI-TAMPER
        if (cfg.spoofInstaller) {
            addSameLengthRule(rules, "getInstallerPackageName", "getNoInstallerPkgNameXX");
            addSameLengthRule(rules, "verifySignature", "noVerifySignatr");
            addSameLengthRule(rules, "checkSignature", "noChkSignature");
        }

        // 7. DISABLE ANALYTICS & TELEMETRY
        if (cfg.killAnalytics) {
            addSameLengthRule(rules, "com/google/firebase/analytics/FirebaseAnalytics", "com/google/firebase/analytics/NoFirebaseAnalyti");
            addSameLengthRule(rules, "com/google/firebase/crashlytics/FirebaseCrashlytics", "com/google/firebase/crashlytics/NoFirebaseCrashlyti");
            addSameLengthRule(rules, "com/facebook/appevents/AppEventsLogger", "com/facebook/appevents/NoEventsLoggerX");
            addSameLengthRule(rules, "app-measurement.com", "0.0.0.0/no_measurex");
            addSameLengthRule(rules, "firebaselogging-pa.googleapis.com", "0.0.0.0/blocked_firebase_logging_");
            addSameLengthRule(rules, "crashlyticsreports-pa.googleapis.com", "0.0.0.0/blocked_crashlytics_reports_");
        }

        // 8. MOCK LOCATION & CLONE BYPASS
        if (cfg.unlockMockLocation) {
            addSameLengthRule(rules, "isFromMockProvider", "noFromMockProvider");
            addSameLengthRule(rules, "allow_mock_location", "ignore_mock_locatio");
        }

        // 9. REMOVE SPECIFIC TABS / BUTTONS / CONTAINERS
        if (cfg.removeTabs && cfg.customTabNames != null && !cfg.customTabNames.isEmpty()) {
            String[] tabs = cfg.customTabNames.split(",");
            for (String raw : tabs) {
                String t = raw.trim();
                if (t.length() >= 3) {
                    String replacement = "zz_" + "x".repeat(t.length() - 3);
                    addSameLengthRule(rules, t, replacement);
                }
            }
        }

        // Convert to both UTF-8 (for DEX string pools) and UTF-16LE (for Binary XML / resources.arsc)
        Map<byte[], byte[]> byteRules = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rules.entrySet()) {
            byte[] kUtf8 = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] vUtf8 = e.getValue().getBytes(StandardCharsets.UTF_8);
            if (kUtf8.length == vUtf8.length) {
                byteRules.put(kUtf8, vUtf8);
            }
            byte[] kUtf16 = e.getKey().getBytes(StandardCharsets.UTF_16LE);
            byte[] vUtf16 = e.getValue().getBytes(StandardCharsets.UTF_16LE);
            if (kUtf16.length == vUtf16.length) {
                byteRules.put(kUtf16, vUtf16);
            }
        }
        return byteRules;
    }

    private void addSameLengthRule(Map<String, String> map, String target, String replacement) {
        if (target.length() == replacement.length()) {
            map.put(target, replacement);
        } else if (replacement.length() < target.length()) {
            String padded = replacement + "X".repeat(target.length() - replacement.length());
            map.put(target, padded);
        } else {
            map.put(target, replacement.substring(0, target.length()));
        }
    }

    private static class PatchResult {
        byte[] data;
        int matchCount;
        PatchResult(byte[] data, int matchCount) {
            this.data = data;
            this.matchCount = matchCount;
        }
    }

    private PatchResult patchDexBinary(byte[] dexBytes, Map<byte[], byte[]> rules, boolean stripFlagSecure) throws Exception {
        int count = 0;
        for (Map.Entry<byte[], byte[]> rule : rules.entrySet()) {
            count += replaceBytesInPlace(dexBytes, rule.getKey(), rule.getValue());
        }

        // Strip FLAG_SECURE (Dalvik opcode const/16 vX, 0x2000 -> const/16 vX, 0x0000)
        // In Dalvik bytecode: 0x13 (const/16), reg byte, 0x00, 0x20 (little-endian 0x2000)
        if (stripFlagSecure) {
            for (int i = 0; i < dexBytes.length - 4; i += 2) {
                if ((dexBytes[i] & 0xFF) == 0x13 && (dexBytes[i + 2] & 0xFF) == 0x00 && (dexBytes[i + 3] & 0xFF) == 0x20) {
                    dexBytes[i + 3] = 0x00; // 0x2000 -> 0x0000
                    count++;
                }
            }
        }

        // Recalculate DEX SHA-1 Signature (bytes 12..31) and Adler32 Checksum (bytes 8..11)
        if (dexBytes.length > 32 && dexBytes[0] == 'd' && dexBytes[1] == 'e' && dexBytes[2] == 'x') {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(dexBytes, 32, dexBytes.length - 32);
            byte[] sig = sha1.digest();
            System.arraycopy(sig, 0, dexBytes, 12, 20);

            Adler32 adler = new Adler32();
            adler.update(dexBytes, 12, dexBytes.length - 12);
            long checksum = adler.getValue();
            dexBytes[8] = (byte) (checksum & 0xFF);
            dexBytes[9] = (byte) ((checksum >> 8) & 0xFF);
            dexBytes[10] = (byte) ((checksum >> 16) & 0xFF);
            dexBytes[11] = (byte) ((checksum >> 24) & 0xFF);
        }

        return new PatchResult(dexBytes, count);
    }

    private PatchResult patchBinaryResource(byte[] resBytes, Map<byte[], byte[]> rules) {
        int count = 0;
        for (Map.Entry<byte[], byte[]> rule : rules.entrySet()) {
            count += replaceBytesInPlace(resBytes, rule.getKey(), rule.getValue());
        }
        return new PatchResult(resBytes, count);
    }

    private int replaceBytesInPlace(byte[] buffer, byte[] target, byte[] replacement) {
        int matches = 0;
        int limit = buffer.length - target.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < target.length; j++) {
                if (buffer[i + j] != target[j]) {
                    continue outer;
                }
            }
            System.arraycopy(replacement, 0, buffer, i, replacement.length);
            matches++;
            i += target.length - 1;
        }
        return matches;
    }

    private void signApkWithApksig(File unsignedApk, File signedApk) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name issuer = new X500Name("CN=OmniModder Studio, OU=Android, O=OmniModder, L=Kitchen, ST=Bench, C=US");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 86400000L * 365);
        Date notAfter = new Date(System.currentTimeMillis() + 86400000L * 365 * 25);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, keyPair.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "OMNIMODDER_KEY",
                keyPair.getPrivate(),
                Collections.singletonList(cert)
        ).build();

        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();

        apkSigner.sign();
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}
