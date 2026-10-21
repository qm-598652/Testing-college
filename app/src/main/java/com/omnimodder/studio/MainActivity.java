package com.omnimodder.studio;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private File selectedApkFile;
    private TextView selectedFileLabel;
    private TextView logView;
    private EditText urlInput;
    private EditText tabRemoveInput;
    private Button patchNowBtn;
    private Button installModBtn;
    private File lastSignedOutputApk;

    private final List<CheckBox> featureCheckBoxes = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> galleryApkPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    copyUriToWorkspace(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#0F172A"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("⚡ OmniModder Studio v2.0");
        title.setTextColor(Color.parseColor("#22C55E"));
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Universal On-Device APK Modder, Ad/Popup Killer, SSL Unpinner, Original Signature Spoofer & V1/V2/V3 Signer");
        subtitle.setTextColor(Color.parseColor("#94A3B8"));
        subtitle.setTextSize(12.5f);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        // Card 1: Input APK Selection
        LinearLayout sourceCard = createCard();
        root.addView(sourceCard);

        selectedFileLabel = new TextView(this);
        selectedFileLabel.setText("Target APK: None selected");
        selectedFileLabel.setTextColor(Color.parseColor("#F8FAFC"));
        selectedFileLabel.setTypeface(Typeface.DEFAULT_BOLD);
        selectedFileLabel.setPadding(0, 0, 0, dp(10));
        sourceCard.addView(selectedFileLabel);

        Button pickStorageBtn = createStyledButton("📂 Pick APK from Phone Gallery / Files", "#3B82F6");
        pickStorageBtn.setOnClickListener(v -> galleryApkPicker.launch("*/*"));
        sourceCard.addView(pickStorageBtn);

        Button pickInstalledBtn = createStyledButton("📱 Pick from Installed Apps on Phone", "#8B5CF6");
        pickInstalledBtn.setOnClickListener(v -> showInstalledAppsDialog());
        sourceCard.addView(pickInstalledBtn);

        urlInput = new EditText(this);
        urlInput.setHint("Or paste Direct APK URL (https://.../app.apk)");
        urlInput.setHintTextColor(Color.parseColor("#64748B"));
        urlInput.setTextColor(Color.WHITE);
        urlInput.setSingleLine(true);
        sourceCard.addView(urlInput);

        Button fetchUrlBtn = createStyledButton("🌐 Download APK from Direct Link", "#0EA5E9");
        fetchUrlBtn.setOnClickListener(v -> downloadApkFromUrl(urlInput.getText().toString().trim()));
        sourceCard.addView(fetchUrlBtn);

        // Card 2: Custom Tab / Button Remover
        LinearLayout tabCard = createCard();
        root.addView(tabCard);

        TextView tabTitle = new TextView(this);
        tabTitle.setText("✂️ Remove Any Tab / Button / View IDs");
        tabTitle.setTextColor(Color.parseColor("#38BDF8"));
        tabTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tabCard.addView(tabTitle);

        tabRemoveInput = new EditText(this);
        tabRemoveInput.setHint("Comma-separated IDs (e.g. tab_shop, nav_ads, btn_vip)");
        tabRemoveInput.setHintTextColor(Color.parseColor("#64748B"));
        tabRemoveInput.setTextColor(Color.WHITE);
        tabRemoveInput.setText("ad_view,ad_container,banner_ad,tab_shop,btn_upgrade,vip_tab");
        tabCard.addView(tabRemoveInput);

        // Card 3: 100+ Modding Features Checkboxes
        LinearLayout featuresCard = createCard();
        root.addView(featuresCard);

        TextView featHeader = new TextView(this);
        featHeader.setText("🛠️ Active Patch Modules (100+ Hooks)");
        featHeader.setTextColor(Color.parseColor("#22C55E"));
        featHeader.setTypeface(Typeface.DEFAULT_BOLD);
        featHeader.setPadding(0, 0, 0, dp(8));
        featuresCard.addView(featHeader);

        String[][] modules = new String[][]{
                {"KILL_ADS", "1. Remove All Ads (AdMob, Unity, AppLovin, IronSource, Meta, Vungle, Pangle, Mintegral + 30 Ad Domains)"},
                {"KILL_POPUPS", "2. Kill Dialogs, Force-Update Prompts, Rate-Us & Consent Pop-ups"},
                {"REMOVE_TABS", "3. Remove Specified Tab Buttons, BottomNav Items & Banner Containers"},
                {"MAKE_CAPTURABLE", "4. Make APK Capturable (Bypass SSL Pinning, Trust User CAs, Hide VPN/Proxy/Reqable/Canary)"},
                {"REMOVE_FLAG_SECURE", "5. Remove Restrictions: Enable Screenshots & Screen Recording (Strip FLAG_SECURE)"},
                {"UNLOCK_PREMIUM", "6. Unlock Pro/VIP/Subscribed Boolean Checks & Mock Play Billing Success"},
                {"SPOOF_SIGNATURE", "7. Spoof Original Developer Signature / SHA-1 Hash & Bypass Tamper Checks"},
                {"SPOOF_INSTALLER", "8. Spoof Google Play Store Installer (com.android.vending)"},
                {"BYPASS_ROOT_EMULATOR", "9. Bypass Root, Magisk, Xposed, Frida, Emulator & ADB Debugging Checks"},
                {"KILL_ANALYTICS", "10. Disable Firebase Analytics, Crashlytics, Sentry & Facebook Telemetry"},
                {"UNLOCK_MOCK_LOCATION", "11. Bypass Mock Location Detection & Dual-Space/Clone Restrictions"},
                {"AUTO_SIGN_V1_V2_V3", "12. Sign APK with V1 (JAR) + V2 (Full Block) + V3 Cryptographic Signature"}
        };

        for (String[] mod : modules) {
            CheckBox cb = new CheckBox(this);
            cb.setTag(mod[0]);
            cb.setText(mod[1]);
            cb.setTextColor(Color.parseColor("#E2E8F0"));
            cb.setChecked(true);
            cb.setPadding(dp(4), dp(6), dp(4), dp(6));
            featuresCard.addView(cb);
            featureCheckBoxes.add(cb);
        }

        patchNowBtn = createStyledButton("🚀 PATCH & SIGN APK NOW (100+ MODS)", "#22C55E");
        patchNowBtn.setTextColor(Color.parseColor("#052E16"));
        patchNowBtn.setOnClickListener(v -> runPatchingPipeline());
        root.addView(patchNowBtn);

        installModBtn = createStyledButton("📲 INSTALL MODDED & SIGNED APK", "#F59E0B");
        installModBtn.setTextColor(Color.parseColor("#0F172A"));
        installModBtn.setVisibility(View.GONE);
        installModBtn.setOnClickListener(v -> installSignedApk());
        root.addView(installModBtn);

        // Console Log Card
        LinearLayout logCard = createCard();
        root.addView(logCard);

        TextView logHeader = new TextView(this);
        logHeader.setText("📟 Live Patcher & Signer Console:");
        logHeader.setTextColor(Color.parseColor("#94A3B8"));
        logHeader.setTypeface(Typeface.DEFAULT_BOLD);
        logCard.addView(logHeader);

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#4ADE80"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11.5f);
        logView.setPadding(0, dp(8), 0, 0);
        logView.setText("[Ready] Pick an APK from Gallery, Installed Apps, or Direct URL.\n");
        logCard.addView(logView);

        setContentView(scrollView);
    }

    private void copyUriToWorkspace(Uri uri) {
        executor.execute(() -> {
            try {
                appendLog("[*] Reading selected APK from phone gallery/storage...");
                File workDir = new File(getExternalFilesDir(null), "workspace");
                if (!workDir.exists()) workDir.mkdirs();
                File dest = new File(workDir, "gallery_input.apk");

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                selectedApkFile = dest;
                mainHandler.post(() -> {
                    selectedFileLabel.setText("Target APK: " + dest.getName() + " (" + (dest.length() / 1024) + " KB)");
                    appendLog("[+] Loaded APK: " + dest.getAbsolutePath());
                });
            } catch (Exception e) {
                appendLog("[!] Gallery read error: " + e.getMessage());
            }
        });
    }

    private void showInstalledAppsDialog() {
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> userApps = new ArrayList<>();
            for (ApplicationInfo info : apps) {
                if (info.sourceDir != null && new File(info.sourceDir).canRead()) {
                    userApps.add(info);
                }
            }
            Collections.sort(userApps, (a, b) ->
                    String.valueOf(pm.getApplicationLabel(a)).compareToIgnoreCase(String.valueOf(pm.getApplicationLabel(b))));

            String[] labels = new String[userApps.size()];
            for (int i = 0; i < userApps.size(); i++) {
                ApplicationInfo info = userApps.get(i);
                labels[i] = pm.getApplicationLabel(info) + " (" + info.packageName + ")";
            }

            mainHandler.post(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("Select Installed App to Mod")
                        .setItems(labels, (dialog, which) -> copyInstalledApkToWorkspace(userApps.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void copyInstalledApkToWorkspace(ApplicationInfo appInfo) {
        executor.execute(() -> {
            try {
                File src = new File(appInfo.sourceDir);
                File workDir = new File(getExternalFilesDir(null), "workspace");
                if (!workDir.exists()) workDir.mkdirs();
                File dest = new File(workDir, appInfo.packageName + ".apk");

                try (FileInputStream in = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                selectedApkFile = dest;
                mainHandler.post(() -> {
                    selectedFileLabel.setText("Target APK: " + appInfo.packageName + " (" + (dest.length() / 1024) + " KB)");
                    appendLog("[+] Extracted installed APK: " + appInfo.packageName);
                });
            } catch (Exception e) {
                appendLog("[!] Failed to extract APK: " + e.getMessage());
            }
        });
    }

    private void downloadApkFromUrl(String urlStr) {
        if (urlStr.isEmpty() || !urlStr.startsWith("http")) {
            Toast.makeText(this, "Enter a valid http/https direct APK link", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            try {
                appendLog("[*] Downloading APK from URL: " + urlStr);
                File workDir = new File(getExternalFilesDir(null), "workspace");
                if (!workDir.exists()) workDir.mkdirs();
                File dest = new File(workDir, "url_target.apk");

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int r;
                    long total = 0;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        total += r;
                    }
                    final long finalTotal = total;
                    selectedApkFile = dest;
                    mainHandler.post(() -> {
                        selectedFileLabel.setText("Target APK: url_target.apk (" + (finalTotal / 1024) + " KB)");
                        appendLog("[+] Downloaded APK (" + (finalTotal / 1024) + " KB).");
                    });
                }
            } catch (Exception e) {
                appendLog("[!] URL download error: " + e.getMessage());
            }
        });
    }

    private void runPatchingPipeline() {
        if (selectedApkFile == null || !selectedApkFile.exists()) {
            Toast.makeText(this, "Pick an APK first!", Toast.LENGTH_LONG).show();
            return;
        }

        patchNowBtn.setEnabled(false);
        installModBtn.setVisibility(View.GONE);

        ApkPatchEngine.PatchConfig config = new ApkPatchEngine.PatchConfig();
        for (CheckBox cb : featureCheckBoxes) {
            String tag = String.valueOf(cb.getTag());
            boolean on = cb.isChecked();
            switch (tag) {
                case "KILL_ADS": config.killAds = on; break;
                case "KILL_POPUPS": config.killPopups = on; break;
                case "REMOVE_TABS": config.removeTabs = on; break;
                case "MAKE_CAPTURABLE": config.makeCapturable = on; break;
                case "REMOVE_FLAG_SECURE": config.removeFlagSecure = on; break;
                case "UNLOCK_PREMIUM": config.unlockPremium = on; break;
                case "SPOOF_SIGNATURE": config.spoofSignature = on; break;
                case "SPOOF_INSTALLER": config.spoofInstaller = on; break;
                case "BYPASS_ROOT_EMULATOR": config.bypassRoot = on; break;
                case "KILL_ANALYTICS": config.killAnalytics = on; break;
                case "UNLOCK_MOCK_LOCATION": config.unlockMockLocation = on; break;
                case "AUTO_SIGN_V1_V2_V3": config.signApk = on; break;
            }
        }
        config.customTabNames = tabRemoveInput.getText().toString().trim();

        executor.execute(() -> {
            try {
                File outDir = getExternalFilesDir("ModdedAPKs");
                if (outDir != null && !outDir.exists()) outDir.mkdirs();
                File signedApk = new File(outDir, "Modded_Signed_" + System.currentTimeMillis() + ".apk");

                ApkPatchEngine engine = new ApkPatchEngine(this::appendLog);
                engine.patchAndSignApk(selectedApkFile, signedApk, config);

                lastSignedOutputApk = signedApk;
                mainHandler.post(() -> {
                    patchNowBtn.setEnabled(true);
                    installModBtn.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Signed APK Ready! Tap Install below.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                appendLog("[ERROR] " + e.getMessage());
                mainHandler.post(() -> patchNowBtn.setEnabled(true));
            }
        });
    }

    private void installSignedApk() {
        if (lastSignedOutputApk == null || !lastSignedOutputApk.exists()) return;
        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", lastSignedOutputApk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            appendLog("[!] Install launch error: " + e.getMessage());
        }
    }

    private void appendLog(String msg) {
        mainHandler.post(() -> logView.append(msg + "\n"));
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private Button createStyledButton(String text, String hexColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(6), 0, dp(6));
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
