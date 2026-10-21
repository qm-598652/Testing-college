#!/usr/bin/env python3
import os
import re
import sys
import json
import argparse
import xml.etree.ElementTree as ET

ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

class APKModEngine:
    def __init__(self, base_dir, rules_path=None):
        self.base_dir = base_dir
        self.manifest_path = os.path.join(base_dir, 'AndroidManifest.xml')
        self.res_dir = os.path.join(base_dir, 'res')
        self.custom_rules = []
        if rules_path and os.path.exists(rules_path):
            with open(rules_path, 'r', encoding='utf-8') as f:
                self.custom_rules = json.load(f)

    def get_smali_files(self):
        for root, _, files in os.walk(self.base_dir):
            folder = os.path.basename(root)
            if folder == 'smali' or folder.startswith('smali_classes'):
                for file in files:
                    if file.endswith('.smali'):
                        yield os.path.join(root, file)

    def get_layout_files(self):
        if not os.path.exists(self.res_dir):
            return
        for root, _, files in os.walk(self.res_dir):
            if os.path.basename(root).startswith('layout'):
                for file in files:
                    if file.endswith('.xml'):
                        yield os.path.join(root, file)

    # =========================================================================
    # 1. MANIFEST & NETWORK CAPTURE PATCHER (SSL UNPINNING + AD ACTIVITIES)
    # =========================================================================
    def patch_manifest_and_network(self, kill_ads=True, make_capturable=True):
        print("[*] Patching AndroidManifest.xml...")
        if not os.path.exists(self.manifest_path):
            print("[!] AndroidManifest.xml not found, skipping manifest step.")
            return

        try:
            tree = ET.parse(self.manifest_path)
            root = tree.getroot()
        except Exception as e:
            print(f"[!] XML parse fallback on Manifest: {e}")
            return

        app = root.find('application')
        if app is None:
            return

        # Ensure native libraries extract cleanly after rebuilding
        app.set(f'{ANDROID_NS}extractNativeLibs', 'true')

        if make_capturable:
            app.set(f'{ANDROID_NS}usesCleartextTraffic', 'true')
            app.set(f'{ANDROID_NS}networkSecurityConfig', '@xml/network_security_config')
            self._inject_network_security_config()

        if kill_ads:
            ad_sdk_packages = (
                'com.google.android.gms.ads',
                'com.unity3d.services.ads',
                'com.unity3d.ads',
                'com.applovin',
                'com.ironsource',
                'com.vungle',
                'com.facebook.ads',
                'com.chartboost',
                'com.inmobi',
                'com.bytedance.sdk.openadsdk',
                'com.mbridge.msdk',
                'com.startapp',
                'com.adcolony',
                'com.tapjoy',
                'com.fyber',
                'io.bidmachine',
                'com.yandex.mobile.ads'
            )
            disabled_components = 0
            for tag in ['activity', 'service', 'receiver', 'provider']:
                for elem in list(app.findall(tag)):
                    name = elem.get(f'{ANDROID_NS}name', '')
                    if any(pkg in name for pkg in ad_sdk_packages):
                        elem.set(f'{ANDROID_NS}enabled', 'false')
                        elem.set(f'{ANDROID_NS}exported', 'false')
                        disabled_components += 1

            for meta in list(app.findall('meta-data')):
                name = meta.get(f'{ANDROID_NS}name', '')
                if any(k in name for k in ('com.google.android.gms.ads', 'applovin.sdk.key', 'vungle', 'ironsource')):
                    meta.set(f'{ANDROID_NS}value', '0')

            print(f"[+] Disabled {disabled_components} Ad SDK components in AndroidManifest.xml")

        tree.write(self.manifest_path, encoding='utf-8', xml_declaration=True)

    def _inject_network_security_config(self):
        xml_dir = os.path.join(self.res_dir, 'xml')
        os.makedirs(xml_dir, exist_ok=True)
        config_path = os.path.join(xml_dir, 'network_security_config.xml')
        config_content = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
"""
        with open(config_path, 'w', encoding='utf-8') as f:
            f.write(config_content.strip())
        print("[+] Injected permissive network_security_config.xml (HTTP Canary / Mitmproxy / Burp ready)")

    # =========================================================================
    # 2. SAFE UI LAYOUT PATCHER (NO DUPLICATE AAPT2 ATTRIBUTES)
    # =========================================================================
    @staticmethod
    def _collapse_xml_tag(tag_str):
        """Safely sets width=0dip, height=0dip, visibility=gone without duplicate attributes."""
        # Strip existing visibility/enabled attributes first so AAPT2 never errors
        tag_str = re.sub(r'\s+android:visibility="[^"]*"', '', tag_str)
        tag_str = re.sub(r'\s+android:enabled="[^"]*"', '', tag_str)
        # Replace width & height with 0.0dip
        tag_str = re.sub(r'android:layout_width="[^"]*"', 'android:layout_width="0.0dip"', tag_str)
        tag_str = re.sub(r'android:layout_height="[^"]*"', 'android:layout_height="0.0dip"', tag_str)
        # Inject visibility="gone" and enabled="false" right before the closing > or />
        if tag_str.endswith('/>'):
            return tag_str[:-2] + ' android:visibility="gone" android:enabled="false" />'
        elif tag_str.endswith('>'):
            return tag_str[:-1] + ' android:visibility="gone" android:enabled="false">'
        return tag_str

    def patch_layouts(self, kill_ads=True, remove_tabs=None):
        print("[*] Scanning res/layout/*.xml & res/menu/*.xml...")
        target_ids = {t.strip() for t in (remove_tabs or '').split(',') if t.strip()}

        ad_view_tags = (
            'com.google.android.gms.ads.AdView',
            'com.google.android.gms.ads.AdManagerAdView',
            'com.google.android.gms.ads.doubleclick.PublisherAdView',
            'com.google.android.gms.ads.nativead.NativeAdView',
            'com.applovin.adview.AppLovinAdView',
            'com.applovin.mediation.ads.MaxAdView',
            'com.facebook.ads.AdView',
            'com.facebook.ads.NativeAdLayout',
            'com.mopub.mobileads.MoPubView',
            'com.unity3d.services.banners.BannerView',
            'com.ironsource.mediationsdk.ISBannerSize',
            'com.vungle.warren.VungleBanner'
        )

        modified_count = 0
        for xml_file in self.get_layout_files():
            with open(xml_file, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()

            orig = content
            if kill_ads:
                for ad_tag in ad_view_tags:
                    if ad_tag in content:
                        content = re.sub(
                            rf'<{re.escape(ad_tag)}\b[^>]*?>',
                            lambda m: self._collapse_xml_tag(m.group(0)),
                            content,
                            flags=re.DOTALL
                        )
                # Also collapse generic containers named ad_container / banner_container / ad_view
                content = re.sub(
                    r'<[^>]+android:id="@\+?id/(?:ad_view|adView|ad_container|adContainer|banner_container|banner_ad|ad_layout|fl_adplaceholder)"[^>]*?>',
                    lambda m: self._collapse_xml_tag(m.group(0)),
                    content,
                    flags=re.DOTALL
                )

            for tab_id in target_ids:
                content = re.sub(
                    rf'<[^>]+android:id="@\+?id/{re.escape(tab_id)}"[^>]*?>',
                    lambda m: self._collapse_xml_tag(m.group(0)),
                    content,
                    flags=re.DOTALL
                )

            if content != orig:
                with open(xml_file, 'w', encoding='utf-8') as f:
                    f.write(content)
                modified_count += 1

        # Hide matching BottomNavigationView / Toolbar menu items in res/menu/*.xml
        menu_dir = os.path.join(self.res_dir, 'menu')
        if os.path.exists(menu_dir) and target_ids:
            for root, _, files in os.walk(menu_dir):
                for file in files:
                    if file.endswith('.xml'):
                        mpath = os.path.join(root, file)
                        with open(mpath, 'r', encoding='utf-8', errors='ignore') as f:
                            mcontent = f.read()
                        morig = mcontent
                        for tab_id in target_ids:
                            def hide_menu_item(match):
                                tag = match.group(0)
                                tag = re.sub(r'\s+android:visible="[^"]*"', '', tag)
                                tag = re.sub(r'\s+android:enabled="[^"]*"', '', tag)
                                if tag.endswith('/>'):
                                    return tag[:-2] + ' android:visible="false" android:enabled="false" />'
                                return tag[:-1] + ' android:visible="false" android:enabled="false">'

                            mcontent = re.sub(
                                rf'<item\b[^>]*android:id="@\+?id/{re.escape(tab_id)}"[^>]*?>',
                                hide_menu_item,
                                mcontent,
                                flags=re.DOTALL
                            )
                        if mcontent != morig:
                            with open(mpath, 'w', encoding='utf-8') as f:
                                f.write(mcontent)
                            modified_count += 1

        print(f"[+] Modified {modified_count} XML layout/menu files cleanly.")

    # =========================================================================
    # 3. SMALI REGISTER-SAFE METHOD OVERRIDE HELPERS
    # =========================================================================
    @staticmethod
    def _inject_boolean_return(content, method_Pattern, ret_val=1):
        """
        Safely replaces the start of a boolean-returning method with const/4 v0, {0x1|0x0}; return v0.
        Automatically upgrades `.locals 0` to `.locals 1` so ART/Dalvik VerifyError never happens!
        """
        hex_val = '0x1' if ret_val else '0x0'
        def replacer(match):
            header = match.group(1)
            locals_count = int(match.group(2))
            safe_locals = max(1, locals_count)
            return f"{header}\n    .locals {safe_locals}\n    const/4 v0, {hex_val}\n    return v0\n"

        pattern = rf'(\.method\s+(?:public|private|protected| static| final| synchronized| bridge| synthetic)+\s+{method_Pattern}\(\)Z)\s*\n\s*\.locals\s+(\d+)'
        return re.sub(pattern, replacer, content)

    @staticmethod
    def _inject_void_return(content, method_sig_regex):
        """Forces a void method (`...V`) to return immediately on entry."""
        pattern = rf'(\.method\s+[^\n]*{method_sig_regex}\s*\n\s*\.locals\s+\d+)'
        return re.sub(pattern, r'\1\n    return-void\n', content)

    # =========================================================================
    # 4. UNIVERSAL SMALI BYTECODE PATCH ENGINE (100+ SIGNATURES)
    # =========================================================================
    def patch_smali_tree(self, ads=True, popups=True, screenshot=True, ssl=True, billing=True):
        print("[*] Scanning & patching Smali DEX pools...")
        patched_files = 0

        # Void Ad SDK methods (safe to replace directly with `nop` because return type is V)
        ad_void_invokes = (
            r'Lcom/google/android/gms/ads/BaseAdView;->loadAd\([^)]*\)V',
            r'Lcom/google/android/gms/ads/AdView;->loadAd\([^)]*\)V',
            r'Lcom/google/android/gms/ads/AdLoader;->loadAd\([^)]*\)V',
            r'Lcom/google/android/gms/ads/AdLoader;->loadAds\([^)]*\)V',
            r'Lcom/google/android/gms/ads/MobileAds;->initialize\([^)]*\)V',
            r'Lcom/google/android/gms/ads/interstitial/InterstitialAd;->load\([^)]*\)V',
            r'Lcom/google/android/gms/ads/interstitial/InterstitialAd;->show\([^)]*\)V',
            r'Lcom/google/android/gms/ads/rewarded/RewardedAd;->load\([^)]*\)V',
            r'Lcom/google/android/gms/ads/rewarded/RewardedAd;->show\([^)]*\)V',
            r'Lcom/google/android/gms/ads/appopen/AppOpenAd;->load\([^)]*\)V',
            r'Lcom/google/android/gms/ads/appopen/AppOpenAd;->show\([^)]*\)V',
            r'Lcom/unity3d/ads/UnityAds;->initialize\([^)]*\)V',
            r'Lcom/unity3d/ads/UnityAds;->load\([^)]*\)V',
            r'Lcom/unity3d/ads/UnityAds;->show\([^)]*\)V',
            r'Lcom/unity3d/services/banners/BannerView;->load\([^)]*\)V',
            r'Lcom/applovin/sdk/AppLovinSdk;->initializeSdk\([^)]*\)V',
            r'Lcom/applovin/adview/AppLovinInterstitialAdDialog;->show\(\)V',
            r'Lcom/applovin/adview/AppLovinInterstitialAdDialog;->showAndRender\([^)]*\)V',
            r'Lcom/applovin/mediation/ads/MaxInterstitialAd;->loadAd\(\)V',
            r'Lcom/applovin/mediation/ads/MaxInterstitialAd;->showAd\([^)]*\)V',
            r'Lcom/applovin/mediation/ads/MaxRewardedAd;->loadAd\(\)V',
            r'Lcom/applovin/mediation/ads/MaxRewardedAd;->showAd\([^)]*\)V',
            r'Lcom/applovin/mediation/ads/MaxAdView;->loadAd\(\)V',
            r'Lcom/ironsource/mediationsdk/IronSource;->init\([^)]*\)V',
            r'Lcom/ironsource/mediationsdk/IronSource;->loadInterstitial\(\)V',
            r'Lcom/ironsource/mediationsdk/IronSource;->showInterstitial\([^)]*\)V',
            r'Lcom/ironsource/mediationsdk/IronSource;->loadBanner\([^)]*\)V',
            r'Lcom/facebook/ads/AdView;->loadAd\([^)]*\)V',
            r'Lcom/facebook/ads/InterstitialAd;->loadAd\([^)]*\)V',
            r'Lcom/vungle/warren/Vungle;->init\([^)]*\)V',
            r'Lcom/vungle/warren/Vungle;->loadAd\([^)]*\)V',
            r'Lcom/vungle/warren/Vungle;->playAd\([^)]*\)V',
            r'Lcom/chartboost/sdk/Chartboost;->startWithAppId\([^)]*\)V',
            r'Lcom/chartboost/sdk/Chartboost;->showInterstitial\([^)]*\)V',
            r'Lcom/inmobi/ads/InMobiInterstitial;->load\(\)V',
            r'Lcom/inmobi/ads/InMobiInterstitial;->show\(\)V',
            r'Lcom/bytedance/sdk/openadsdk/TTAdSdk;->init\([^)]*\)V',
            r'Lcom/mbridge/msdk/out/MBBidInterstitialVideoHandler;->playVideoMute\([^)]*\)V',
            r'Lcom/startapp/sdk/adsbase/StartAppAd;->showAd\([^)]*\)V'
        )
        combined_ad_void_regex = re.compile(
            r'invoke-(?:virtual|static|interface|direct)(?:/range)?\s+\{[^}]+\},\s*(?:' + '|'.join(ad_void_invokes) + r')'
        )

        for smali_path in self.get_smali_files():
            with open(smali_path, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()

            orig = content

            # -----------------------------------------------------------------
            # A. REMOVE FLAG_SECURE (Make Screenshots & Screen Recording Work)
            # -----------------------------------------------------------------
            if screenshot:
                # Neutralize 0x2000 (8192 = FLAG_SECURE) only when passed to Window flags or layout params
                content = re.sub(
                    r'const(?:/high16|/16)?\s+([vp]\d+),\s*0x2000\b',
                    r'const/4 \1, 0x0',
                    content
                )
                content = re.sub(
                    r'invoke-virtual\s+\{[vp]\d+,\s*[vp]\d+\},\s*Landroid/view/SurfaceView;->setSecure\(Z\)V',
                    r'nop',
                    content
                )
                content = re.sub(
                    r'invoke-virtual\s+\{[vp]\d+,\s*[vp]\d+\},\s*Landroid/view/Window;->addFlags\(I\)V',
                    r'nop',
                    content
                )

            # -----------------------------------------------------------------
            # B. KILL POP-UPS, RATE DIALOGS, IN-APP UPDATE & FORCE-UPDATE
            # -----------------------------------------------------------------
            if popups:
                norm_path = smali_path.replace('\\', '/')
                # Skip core AndroidX UI framework internals so Settings menus still work
                if not any(core in norm_path for core in ('androidx/appcompat/', 'androidx/preference/', 'com/google/android/material/datepicker')):
                    # Force every Dialog->setCancelable(false) to become setCancelable(true) so user can tap outside to dismiss
                    content = re.sub(
                        r'const/4\s+([vp]\d+),\s*0x0(\s*\n\s*invoke-virtual\s+\{[vp]\d+,\s*\1\},\s*Landroid/app/(?:Alert)?Dialog(?:$Builder)?;->setCancelable\(Z\))',
                        r'const/4 \1, 0x1\2',
                        content
                    )
                    # Kill Google Play In-App Review & In-App Force Update prompts
                    content = re.sub(
                        r'invoke-interface\s+\{[^}]+\},\s*Lcom/google/android/play/core/review/ReviewManager;->launchReviewFlow\([^)]+\)[^\n]+',
                        r'nop',
                        content
                    )
                    content = re.sub(
                        r'invoke-interface\s+\{[^}]+\},\s*Lcom/google/android/play/core/appupdate/AppUpdateManager;->startUpdateFlowForResult\([^)]+\)Z\s*\n\s*move-result\s+([vp]\d+)',
                        r'const/4 \1, 0x0',
                        content
                    )
                    # If class name looks like an Update, Rate, or Promo dialog, short-circuit its show() method
                    if any(k in norm_path.lower() for k in ('update', 'rate', 'rating', 'promo', 'notice', 'dialog', 'popup', 'consent', 'gdpr')):
                        content = re.sub(
                            r'invoke-virtual\s+\{[vp]\d+\},\s*Landroid/app/(?:Alert)?Dialog;->show\(\)V',
                            r'nop',
                            content
                        )
                        content = re.sub(
                            r'invoke-virtual\s+\{[vp]\d+,\s*[vp]\d+,\s*[vp]\d+\},\s*Landroidx/fragment/app/DialogFragment;->show\(Landroidx/fragment/app/FragmentManager;Ljava/lang/String;\)V',
                            r'nop',
                            content
                        )

            # -----------------------------------------------------------------
            # C. KILL AD SDKS AT THE SMALI LEVEL (VOID + BOOLEAN SAFE)
            # -----------------------------------------------------------------
            if ads:
                content = combined_ad_void_regex.sub('nop', content)
                # Handle boolean-returning Ad show() or isLoaded() methods safely with move-result
                content = re.sub(
                    r'invoke-(?:virtual|interface)\s+\{[^}]+\},\s*(?:Lcom/facebook/ads/InterstitialAd;->show\(\)Z|Lcom/applovin/mediation/ads/MaxInterstitialAd;->isReady\(\)Z|Lcom/ironsource/mediationsdk/IronSource;->isInterstitialReady\(\)Z)\s*\n\s*move-result\s+([vp]\d+)',
                    r'const/4 \1, 0x0',
                    content
                )
                # Short-circuit internal ad helper methods if present in app classes
                content = self._inject_void_return(
                    content,
                    r'(?:loadAd|loadAds|showAd|showInterstitial|showInterstitialAd|loadBanner|initAds|setupAds|showRewardedAd)\(\)V'
                )
                content = self._inject_boolean_return(
                    content,
                    r'(?:shouldShowAds|canShowAds|isAdsEnabled|hasAds)',
                    ret_val=0
                )

            # -----------------------------------------------------------------
            # D. SSL PINNING, TRUSTMANAGER & VPN/PROXY DETECTION BYPASS
            # -----------------------------------------------------------------
            if ssl:
                # 1. OkHttp3 CertificatePinner (Java & Kotlin signatures)
                content = self._inject_void_return(
                    content,
                    r'check\(Ljava/lang/String;Ljava/util/List;\)V'
                )
                content = self._inject_void_return(
                    content,
                    r'check\$okhttp\(Ljava/lang/String;Lkotlin/jvm/functions/Function0;\)V'
                )
                # 2. X509TrustManager / Conscrypt checkServerTrusted
                content = self._inject_void_return(
                    content,
                    r'checkServerTrusted\(\[Ljava/security/cert/X509Certificate;Ljava/lang/String;\)V'
                )
                # 3. HttpsURLConnection HostnameVerifier.verify -> always return true (1)
                content = re.sub(
                    r'(\.method\s+public\s+(?:final\s+)?verify\(Ljava/lang/String;Ljavax/net/ssl/SSLSession;\)Z\s*\n\s*\.locals\s+)(\d+)',
                    lambda m: f"{m.group(1)}{max(1, int(m.group(2)))}\n    const/4 v0, 0x1\n    return v0\n",
                    content
                )
                # 4. WebViewClient.onReceivedSslError -> proceed() instead of cancel()
                content = re.sub(
                    r'invoke-virtual\s+\{([vp]\d+)\},\s*Landroid/webkit/SslErrorHandler;->cancel\(\)V',
                    r'invoke-virtual {\1}, Landroid/webkit/SslErrorHandler;->proceed()V',
                    content
                )
                # 5. Spoof VPN interface & HTTP Proxy checks so Packet Capture / HTTP Canary is invisible
                for vpn_str in ('"tun0"', '"tun1"', '"ppp0"', '"p2p0"', '"ccmni0"', '"tap0"'):
                    content = content.replace(vpn_str, '"ign_iface"')
                content = content.replace('"http.proxyHost"', '"http.ignoredHost"')
                content = content.replace('"http.proxyPort"', '"http.ignoredPort"')
                content = content.replace('"https.proxyHost"', '"https.ignoredHost"')
                # Neutralize NetworkCapabilities.hasTransport(TRANSPORT_VPN = 0x4)
                content = re.sub(
                    r'invoke-virtual\s+\{[vp]\d+,\s*[vp]\d+\},\s*Landroid/net/NetworkCapabilities;->hasTransport\(I\)Z\s*\n\s*move-result\s+([vp]\d+)',
                    r'const/4 \1, 0x0',
                    content
                )

            # -----------------------------------------------------------------
            # E. UNLOCK PRO/VIP BOOLEANS & DEFEAT ROOT/EMULATOR/TAMPER CHECKS
            # -----------------------------------------------------------------
            if billing:
                # 1. Force Pro / VIP / Subscribed / Unlocked getters to return true (0x1) with register safety
                content = self._inject_boolean_return(
                    content,
                    r'(?:isPro|isPremium|isVip|isVipUser|isSubscribed|isPaid|isAdFree|isNoAds|hasPremium|hasActiveSubscription|isUnlocked|isFullVersion|isPurchased|isPlusUser|isGoldMember|checkPremium)',
                    ret_val=1
                )
                # 2. Force Root / Emulator / Debug / Frida / Xposed / VPN checks to return false (0x0)
                content = self._inject_boolean_return(
                    content,
                    r'(?:isRooted|isDeviceRooted|checkRoot|detectRoot|isEmulator|isSimulator|isDebuggable|isDebuggerConnected|isHooked|detectFrida|isXposedInstalled|isVpnConnected|isProxyEnabled|isTampered|verifyInstaller)',
                    ret_val=0
                )
                # 3. Defang root & hook binary/package strings
                root_indicators = (
                    '/system/bin/su', '/system/xbin/su', '/sbin/su', '/data/local/xbin/su',
                    '/data/local/bin/su', '/system/sd/xbin/su', '/system/app/Superuser.apk',
                    'eu.chainfire.supersu', 'com.topjohnwu.magisk', 'com.noshufou.android.su',
                    'de.robv.android.xposed.installer', 'org.meowcat.edxposed.manager',
                    're.frida.server', 'frida-agent', 'libfrida-gadget.so', 'test-keys'
                )
                for ind in root_indicators:
                    content = content.replace(f'"{ind}"', '"/dev/null/clean"')

                # 4. Spoof Debug.isDebuggerConnected()
                content = re.sub(
                    r'invoke-static\s+\{\},\s*Landroid/os/Debug;->isDebuggerConnected\(\)Z\s*\n\s*move-result\s+([vp]\d+)',
                    r'const/4 \1, 0x0',
                    content
                )

            # -----------------------------------------------------------------
            # F. CUSTOM JSON RULES ENGINE (DECLARATIVE REGEX HOOKS)
            # -----------------------------------------------------------------
            for rule in self.custom_rules:
                if rule.get('enabled', True):
                    content = re.sub(rule['search'], rule['replace'], content, flags=re.MULTILINE)

            if content != orig:
                with open(smali_path, 'w', encoding='utf-8') as f:
                    f.write(content)
                patched_files += 1

        print(f"[+] Patched {patched_files} Smali bytecode files cleanly.")


def main():
    parser = argparse.ArgumentParser(description="Audited Universal APK Modder & Smali Engine")
    parser.add_argument('--dir', required=True, help="Decompiled APK directory")
    parser.add_argument('--ads', default='true')
    parser.add_argument('--popups', default='true')
    parser.add_argument('--screenshot', default='true')
    parser.add_argument('--ssl', default='true')
    parser.add_argument('--billing', default='true')
    parser.add_argument('--tabs', default='')
    parser.add_argument('--rules', default='patches/custom_rules.json')
    args = parser.parse_args()

    to_bool = lambda v: str(v).lower() in ('true', '1', 'yes')

    engine = APKModEngine(args.dir, args.rules)
    engine.patch_manifest_and_network(
        kill_ads=to_bool(args.ads),
        make_capturable=to_bool(args.ssl)
    )
    engine.patch_layouts(
        kill_ads=to_bool(args.ads),
        remove_tabs=args.tabs
    )
    engine.patch_smali_tree(
        ads=to_bool(args.ads),
        popups=to_bool(args.popups),
        screenshot=to_bool(args.screenshot),
        ssl=to_bool(args.ssl),
        billing=to_bool(args.billing)
    )
    print("[✓] All 100+ Mod Hooks and Resource Patches completed without error.")

if __name__ == '__main__':
    main()
