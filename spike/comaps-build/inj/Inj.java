import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * Multitouch injector for `app_process` (shell user): 1-finger pan, pinch-zoom and 2-finger rotation, at 120 Hz with
 * local timing. Usage: app_process -cp /data/local/tmp/inj.dex /system/bin Inj <pan|zoom|rotate> <segundos> [hz]
 * Injects with InputManagerGlobal.injectInputEvent via reflection (same as scrcpy / the `input` command).
 */
public class Inj {
    static Object im;
    static Method inject;

    static void init() throws Exception {
        Class<?> c;
        try {
            c = Class.forName("android.hardware.input.InputManagerGlobal");
            im = c.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            c = Class.forName("android.hardware.input.InputManager");
            im = c.getMethod("getInstance").invoke(null);
        }
        inject = c.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    static MotionEvent.PointerProperties[] props(int n) {
        MotionEvent.PointerProperties[] p = new MotionEvent.PointerProperties[n];
        for (int i = 0; i < n; i++) {
            p[i] = new MotionEvent.PointerProperties();
            p[i].id = i;
            p[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        return p;
    }

    static MotionEvent.PointerCoords[] coords(float[] xy) {
        int n = xy.length / 2;
        MotionEvent.PointerCoords[] c = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            c[i] = new MotionEvent.PointerCoords();
            c[i].x = xy[2 * i];
            c[i].y = xy[2 * i + 1];
            c[i].pressure = 1f;
            c[i].size = 1f;
        }
        return c;
    }

    static long downTime;

    static void send(int action, float[] xy) throws Exception {
        int n = xy.length / 2;
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, n, props(n), coords(xy),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject.invoke(im, e, 0);
        e.recycle();
    }

    public static void main(String[] a) throws Exception {
        init();
        String k = a[0];
        double secs = Double.parseDouble(a[1]);
        double hz = a.length > 2 ? Double.parseDouble(a[2]) : 120.0;
        double CX = 540, CY = 1250, dt = 1.0 / hz;
        int n = (int) (secs * hz);
        boolean two = !k.equals("pan");
        downTime = SystemClock.uptimeMillis();
        if (!two) {
            send(MotionEvent.ACTION_DOWN, new float[]{(float) CX, (float) CY});
        } else {
            send(MotionEvent.ACTION_DOWN, new float[]{(float) (CX - 60), (float) CY});
            send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new float[]{(float) (CX - 60), (float) CY, (float) (CX + 60), (float) CY});
        }
        long t0 = System.nanoTime();
        float[] last = null;
        for (int i = 0; i < n; i++) {
            double t = i * dt;
            float[] xy;
            if (k.equals("pan")) {
                double ph = t % 1.0;
                xy = new float[]{(float) (CX + 200 * Math.sin(Math.PI * ph)), (float) (CY + 500 * Math.sin(2 * Math.PI * ph))};
            } else if (k.equals("zoom")) {
                double ph = (t % 2.0) / 2.0;
                double d = 120 + 480 * (0.5 - 0.5 * Math.cos(2 * Math.PI * ph));
                xy = new float[]{(float) (CX - d / 2), (float) CY, (float) (CX + d / 2), (float) CY};
            } else {
                double ang = 2 * Math.PI * (t % 3.0) / 3.0, r = 250;
                xy = new float[]{(float) (CX - r * Math.cos(ang)), (float) (CY - r * Math.sin(ang)),
                        (float) (CX + r * Math.cos(ang)), (float) (CY + r * Math.sin(ang))};
            }
            send(MotionEvent.ACTION_MOVE, xy);
            last = xy;
            long target = t0 + (long) ((i + 1) * dt * 1e9);
            long now;
            while ((now = System.nanoTime()) < target) {
                long rem = target - now;
                if (rem > 2_000_000) Thread.sleep(0, (int) Math.min(rem - 1_500_000, 999_999));
            }
        }
        if (!two) {
            send(MotionEvent.ACTION_UP, last);
        } else {
            send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), last);
            send(MotionEvent.ACTION_UP, new float[]{last[0], last[1]});
        }
        System.out.println("ok " + k + " " + n + " moves");
    }
}
