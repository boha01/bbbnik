package com.bonika.modmenu;

public class NativeBridge {
    static {
        // تحميل المحمل فقط
        System.loadLibrary("loader"); 
        // استدعاء دالة التهيئة التي ستبدأ الخيط في C++ لتحميل المكتبة المموهة
        loadModMenu(); 
    }

    public static native void loadModMenu();

    // PLAYER / ESP
    public static native void setEspEnabled(boolean v);
    public static native void setEspDistance(float meters);
    public static native void setSkeletonEsp(boolean v);
    public static native void setShowNpcs(boolean v);
    public static native void setShowVehicles(boolean v);
    public static native void setEspBoxColor(int argb);

    // COMBAT
    public static native void setGodMode(boolean v);
    public static native void setInfiniteAmmo(boolean v);
    public static native void setRapidFire(boolean v);
    public static native void setNoRecoil(boolean v);

    // VEHICLE
    public static native void setVehicleSpeedEnabled(boolean v);
    public static native void setVehicleSpeedMultiplier(float v);
    public static native void teleportToWaypoint();
    public static native void setInstantBrake(boolean v);

    // VISUALS
    public static native void setFov(float v);
    public static native void setNightVision(boolean v);
    public static native void setFreeFly(boolean v);

    // حالة اللاعب
    public static native float[] getPlayerPosition();
    public static native boolean isHooked();
}
