package com.termux.view;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.RemoteTerminalSession;
import com.termux.terminal.TerminalSessionClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/**
 * Real touch events on a real TerminalView with 1000 lines of history: how many rows a drag and a flick move
 * at different scroll sensitivities.
 */
@RunWith(AndroidJUnit4.class)
public class ScrollSensitivityTest {
    private static final String TAG = "ScrollTest";
    private Instrumentation inst;
    private TerminalView view;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (p, m, args) -> {
            Class<?> r = m.getReturnType();
            if (m.getName().equals("onScale")) return args[0];
            if (r == boolean.class) return false;
            if (r == float.class) return 0f;
            if (r == int.class) return 0;
            return null;
        });
    }

    @Before
    public void setUp() {
        inst = InstrumentationRegistry.getInstrumentation();
        Intent i = new Intent(Intent.ACTION_MAIN)
            .setClassName(inst.getTargetContext().getPackageName(), ScrollTestActivity.class.getName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ScrollTestActivity act = (ScrollTestActivity) inst.startActivitySync(i);
        inst.runOnMainSync(() -> {
            view = new TerminalView(act, null);
            view.setTextSize(36);
            view.setTerminalViewClient(stub(TerminalViewClient.class));
            RemoteTerminalSession s = new RemoteTerminalSession(3000, stub(TerminalSessionClient.class), new CountingTransport());
            view.attachSession(s);
            act.setContentView(view);
        });
        inst.waitForIdleSync();
        inst.runOnMainSync(() -> {
            StringBuilder b = new StringBuilder();
            for (int n = 0; n < 1500; n++) b.append("line ").append(n).append("\r\n");
            byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
            view.mTermSession.appendRemoteOutput(bytes, 0, bytes.length);
            view.onScreenUpdated();
        });
        inst.waitForIdleSync();
    }

    private void reset(float sensitivity) {
        inst.runOnMainSync(() -> {
            view.mScroller.forceFinished(true);
            view.setTopRow(0);
            view.setScrollSensitivity(sensitivity);
        });
        SystemClock.sleep(800); // past the carry window
    }

    private void send(MotionEvent e) {
        inst.runOnMainSync(() -> view.dispatchTouchEvent(e));
        e.recycle();
    }

    /** Finger moves down by {@code dist} px (towards older lines) in {@code steps} moves {@code ms} apart. */
    private void swipe(float dist, int steps, int ms, boolean holdBeforeUp) {
        long t0 = SystemClock.uptimeMillis();
        float x = view.getWidth() / 2f, y0 = view.getHeight() * 0.2f;
        send(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y0, 0));
        long t = t0;
        for (int k = 1; k <= steps; k++) {
            t = t0 + (long) k * ms;
            send(MotionEvent.obtain(t0, t, MotionEvent.ACTION_MOVE, x, y0 + dist * k / steps, 0));
        }
        if (holdBeforeUp) {
            t += 300;
            send(MotionEvent.obtain(t0, t, MotionEvent.ACTION_MOVE, x, y0 + dist, 0));
        }
        send(MotionEvent.obtain(t0, t + 5, MotionEvent.ACTION_UP, x, y0 + dist, 0));
    }

    private int rowsBack() {
        int[] r = new int[1];
        inst.runOnMainSync(() -> r[0] = -view.getTopRow());
        return r[0];
    }

    private int drag(float sensitivity) {
        reset(sensitivity);
        swipe(view.getHeight() * 0.5f, 40, 16, true);
        SystemClock.sleep(500);
        return rowsBack();
    }

    private int flick(float sensitivity) {
        reset(sensitivity);
        swipe(view.getHeight() * 0.3f, 6, 10, false);
        SystemClock.sleep(4000);
        return rowsBack();
    }

    /** Counts writes to the PC (mouse wheel reports, arrow keys). */
    static final class CountingTransport implements RemoteTerminalSession.Transport {
        static int count;
        @Override public void sendInput(byte[] data) { count++; }
        @Override public void onResize(int columns, int rows) {}
    }

    /** External console: the client's history, counted as rows asked for. */
    private int remoteRows;

    private void useRemote() {
        TerminalViewClient base = stub(TerminalViewClient.class);
        TerminalViewClient c = (TerminalViewClient) Proxy.newProxyInstance(TerminalViewClient.class.getClassLoader(),
            new Class[]{TerminalViewClient.class}, (p, m, args) -> {
                if (m.getName().equals("onRemoteScroll")) {
                    int d = (Integer) args[0];
                    if (d < 0) remoteRows++; else if (d > 0) remoteRows--;
                    return true;
                }
                if (m.getName().equals("canRemoteScroll")) return true;
                return m.invoke(base, args);
            });
        inst.runOnMainSync(() -> view.setTerminalViewClient(c));
    }

    private void output(String s) {
        inst.runOnMainSync(() -> {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            view.mTermSession.appendRemoteOutput(b, 0, b.length);
            view.onScreenUpdated();
        });
    }

    @Test
    public void externalConsoleFollowsSensitivity() {
        useRemote();
        int[] d = new int[3];
        int[] f = new int[3];
        float[] s = {0.5f, 1f, 3f};
        for (int k = 0; k < 3; k++) {
            reset(s[k]); remoteRows = 0;
            swipe(view.getHeight() * 0.5f, 40, 16, true);
            SystemClock.sleep(500);
            d[k] = remoteRows;
            reset(s[k]); remoteRows = 0;
            swipe(view.getHeight() * 0.3f, 6, 10, false);
            SystemClock.sleep(4000);
            f[k] = remoteRows;
        }
        Log.i(TAG, "remote drag 0.5/1/3 = " + d[0] + "/" + d[1] + "/" + d[2] + "  flick = " + f[0] + "/" + f[1] + "/" + f[2]);
        assertTrue(d[2] >= d[1] * 2.5 && f[2] > f[1] * 1.5 && d[0] <= d[1] * 0.6);
    }

    @Test
    public void appWithMouseOrFullScreenFollowsSensitivity() {
        String[] modes = {"\033[?1000h\033[?1006h", "\033[?1049h"};
        String[] names = {"mouse", "altscreen"};
        for (int m = 0; m < 2; m++) {
            output(modes[m]);
            int[] d = new int[3];
            int[] f = new int[3];
            float[] s = {0.5f, 1f, 3f};
            for (int k = 0; k < 3; k++) {
                reset(s[k]);
                CountingTransport.count = 0;
                swipe(view.getHeight() * 0.5f, 40, 16, true);
                SystemClock.sleep(500);
                d[k] = CountingTransport.count;
                reset(s[k]);
                CountingTransport.count = 0;
                swipe(view.getHeight() * 0.3f, 6, 10, false);
                SystemClock.sleep(4000);
                f[k] = CountingTransport.count;
            }
            Log.i(TAG, names[m] + " drag events 0.5/1/3 = " + d[0] + "/" + d[1] + "/" + d[2] + "  flick = " + f[0] + "/" + f[1] + "/" + f[2]);
            assertTrue(names[m] + " drag", d[2] >= d[1] * 2.5 && d[0] <= d[1] * 0.6);
            if (m == 0) assertTrue("mouse flick " + f[0] + "/" + f[1] + "/" + f[2], f[2] > f[1] * 1.5 && f[0] < f[1]);
            output(m == 0 ? "\033[?1000l\033[?1006l" : "\033[?1049l");
        }
    }

    @Test
    public void sensitivityChangesDragAndFlick() {
        int line = view.mRenderer.mFontLineSpacing;
        int dHalf = drag(0.5f), d1 = drag(1f), d3 = drag(3f);
        int fHalf = flick(0.5f), f1 = flick(1f), f3 = flick(3f);
        Log.i(TAG, "view h=" + view.getHeight() + " line=" + line + " drag rows 0.5/1/3 = " + dHalf + "/" + d1 + "/" + d3
            + "  flick rows 0.5/1/3 = " + fHalf + "/" + f1 + "/" + f3);
        assertTrue("drag 1x moved " + d1, d1 > 0);
        assertTrue("drag 3x " + d3 + " vs 1x " + d1, d3 >= d1 * 2.5);
        assertTrue("drag 0.5x " + dHalf + " vs 1x " + d1, dHalf <= d1 * 0.6);
        assertTrue("flick 3x " + f3 + " vs 1x " + f1, f3 > f1 * 1.5);
        assertTrue("flick 0.5x " + fHalf + " vs 1x " + f1, fHalf < f1 * 0.8);
    }
}
