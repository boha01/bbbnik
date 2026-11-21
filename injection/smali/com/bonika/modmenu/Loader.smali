.class public Lcom/bonika/modmenu/Loader;
.super Ljava/lang/Object;
.source "Loader.java"

.field private static started:Z

.method public static start(Landroid/content/Context;)V
    .locals 2
    sget-boolean v0, Lcom/bonika/modmenu/Loader;->started:Z
    if-eqz v0, :cond_0
    return-void
    :cond_0
    const/4 v0, 0x1
    sput-boolean v0, Lcom/bonika/modmenu/Loader;->started:Z
    :try_start_0
    const-string v0, "modmenu"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
    :catch_0
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/bonika/modmenu/FloatingService;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    sget v1, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v1, 0x1a
    # ملاحظة: المقارنة الكاملة موجودة في Loader.java - هذا الملف مرجع للحقن اليدوي
    invoke-virtual {p0, v0}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;
    return-void
.end method
