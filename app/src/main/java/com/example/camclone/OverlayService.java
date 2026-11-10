package com.example.camclone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

public class OverlayService extends Service {

    // ---- Настройки геометрии (в долях диаметра настоящей камеры) ----
    static final float DY = 0.30f;     // насколько копии ниже настоящей камеры
    static final float PITCH = 1.60f;  // шаг между центрами копий (по горизонтали)
    static final int PER_SIDE = 2;     // копий с каждой стороны

    private WindowManager wm;
    private View overlay;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        buildOverlay();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        buildOverlay();
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "overlay", "Overlay", NotificationManager.IMPORTANCE_MIN));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "overlay")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("CamClone работает")
                .addAction(new Notification.Action.Builder(null, "Выключить", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void removeOverlay() {
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
    }

    /** Находит настоящую камеру: берём самый верхний/маленький вырез из DisplayCutout. */
    private RectF findCamera(int screenW, int screenH) {
        try {
            Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            DisplayCutout c = d.getCutout();
            if (c != null) {
                List<Rect> rects = c.getBoundingRects();
                Rect best = null;
                for (Rect r : rects) {
                    if (best == null || r.width() * r.height() < best.width() * best.height()) best = r;
                }
                if (best != null && best.width() > 0) {
                    return new RectF(best);
                }
            }
        } catch (Exception ignored) {}
        // Запасной вариант (оценка для Galaxy S25, 1080x2340): по центру сверху
        float dia = screenW * 0.052f;
        float cx = screenW / 2f;
        float cy = screenW * 0.036f + dia / 2f;
        return new RectF(cx - dia / 2, cy - dia / 2, cx + dia / 2, cy + dia / 2);
    }

    private void buildOverlay() {
        removeOverlay();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        final int sw = bounds.width();
        final int sh = bounds.height();

        final RectF cam = findCamera(sw, sh);
        final float dia = Math.max(cam.width(), cam.height());
        final float cx = cam.centerX();
        final float cy = cam.centerY();

        final Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.front_camera);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        final int winH = (int) Math.ceil(cy + dia * (DY + 1.5f));

        View v = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                for (int side = -1; side <= 1; side += 2) {
                    for (int k = 1; k <= PER_SIDE; k++) {
                        float x = cx + side * k * PITCH * dia;
                        float y = cy + DY * dia;
                        canvas.drawBitmap(bmp, null,
                                new RectF(x - dia / 2, y - dia / 2, x + dia / 2, y + dia / 2), paint);
                    }
                }
            }
        };

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                sw, winH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);

        wm.addView(v, lp);
        overlay = v;
    }
}
