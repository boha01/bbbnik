#!/usr/bin/env bash
# ============================================================
# 05 - Injection script:  bash inject.sh game.apk modmenu.apk keystore.jks alias pass
# يحتاج: apktool, zipalign, apksigner (Android SDK build-tools), unzip
# ============================================================
set -e
GAME="$1"; MOD="$2"; KS="${3:-debug.keystore}"; ALIAS="${4:-androiddebugkey}"; PASS="${5:-android}"
[ -f "$GAME" ] && [ -f "$MOD" ] || { echo "usage: bash inject.sh game.apk modmenu.apk [ks] [alias] [pass]"; exit 1; }

echo "[1/6] Decompiling game..."
apktool d -f "$GAME" -o game_out
echo "[2/6] Decompiling mod (no resources)..."
apktool d -f -r "$MOD" -o mod_out

echo "[3/6] Copying smali + libs..."
mkdir -p game_out/smali_classes2/com/bonika
cp -r mod_out/smali/com/bonika/modmenu game_out/smali_classes2/com/bonika/ 2>/dev/null || \
  cp -r mod_out/smali_classes*/com/bonika/modmenu game_out/smali_classes2/com/bonika/
for abi in arm64-v8a armeabi-v7a; do
  if [ -d "mod_out/lib/$abi" ]; then mkdir -p "game_out/lib/$abi"; cp mod_out/lib/$abi/*.so "game_out/lib/$abi/"; fi
done

echo "[4/6] Patch Manifest + Main Activity MANUALLY if not done:"
echo "  - Add FloatingService + SYSTEM_ALERT_WINDOW (see injection/PATCH_MAIN_ACTIVITY.smali.txt)"
echo "  - Add Loader.start(p0) in UnityPlayerActivity.onCreate"
read -p "Patched manually? [y/N] " ok; [ "$ok" = "y" ] || { echo "Patch then re-run from step: apktool b game_out"; exit 0; }

echo "[5/6] Rebuilding..."
apktool b game_out -o game_mod_unsigned.apk
zipalign -p -f 4 game_mod_unsigned.apk game_mod_aligned.apk
echo "[6/6] Signing..."
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" game_mod_aligned.apk
apksigner verify game_mod_aligned.apk && echo "DONE -> game_mod_aligned.apk (install: adb install -r game_mod_aligned.apk)"
echo "NOTE: احذف النسخة الأصلية أولاً إن اختلف التوقيع: adb uninstall <package>"
