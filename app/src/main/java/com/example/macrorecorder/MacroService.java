package com.example.macrorecorder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating record / play / stop bar.
 * Recording: a transparent layer captures your taps and swipes, stores them, and passes them on to the app below.
 * Playback: the recorded gestures are replayed with the same timing, repeated as often as you set.
 */
public class MacroService extends AccessibilityService {

    static class Stroke {
        long start;      // ms since recording start
        long duration;   // ms
        final List<float[]> pts = new ArrayList<>(); // x, y, t(ms since stroke start)
    }

    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView status;
    private TextView repeatView;
    private Button speedBtn;
    private View capture;
    private WindowManager.LayoutParams captureLp;

    private final List<Stroke> strokes = new ArrayList<>();
    private List<Stroke> playList = new ArrayList<>();
    private Stroke cur;
    private boolean recording = false;
    private boolean playing = false;
    private long recStart;

    private int loops = 1;
    private int playTotal = 1;
    private int playRun = 0;
    private double playSpeed = 1.0;
    private final double[] speeds = {0.5, 1.0, 2.0, 4.0};
    private int speedIdx = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- lifecycle ----------
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (panel == null) {
            buildPanel();
        }
        updateStatus();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        playing = false;
        recording = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (capture != null) wm.removeView(capture);
        } catch (Exception ignored) {
        }
        capture = null;
        try {
            if (panel != null) wm.removeView(panel);
        } catch (Exception ignored) {
        }
        panel = null;
    }

    // ---------- floating bar ----------
    private Button mk(LinearLayout row, String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(62, 62, 70));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(b, lp);
        return b;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.argb(235, 32, 32, 36));
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setPadding(dp(4), dp(2), dp(4), dp(6));
        panel.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        mk(row1, "\u25CF Rec", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRecord();
            }
        });
        mk(row1, "\u25B6 Play", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        mk(row1, "\u25A0 Stop", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAll();
            }
        });
        Button close = mk(row1, "\u00D7", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cleanup();
                disableSelf();
            }
        });
        close.setBackgroundColor(Color.rgb(120, 40, 40));
        panel.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        mk(row2, "\u2212", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (loops > 10) loops -= 10;
                else if (loops > 0) loops -= 1;
                refreshRepeat();
            }
        });
        repeatView = new TextView(this);
        repeatView.setTextColor(Color.WHITE);
        repeatView.setTextSize(13);
        repeatView.setGravity(Gravity.CENTER);
        repeatView.setMinWidth(dp(78));
        row2.addView(repeatView);
        mk(row2, "+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loops += (loops >= 10) ? 10 : 1;
                if (loops > 9999) loops = 9999;
                refreshRepeat();
            }
        });
        speedBtn = mk(row2, "Speed 1x", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speedIdx = (speedIdx + 1) % speeds.length;
                double s = speeds[speedIdx];
                speedBtn.setText("Speed " + (s == (long) s ? String.valueOf((long) s) : String.valueOf(s)) + "x");
            }
        });
        panel.addView(row2);
        refreshRepeat();

        // drag the bar by its status text
        status.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = panelLp.x;
                        startY = panelLp.y;
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = startX + (int) (ev.getRawX() - downX);
                        panelLp.y = startY + (int) (ev.getRawY() - downY);
                        try {
                            wm.updateViewLayout(panel, panelLp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(8);
        panelLp.y = dp(120);
        wm.addView(panel, panelLp);
    }

    private void refreshRepeat() {
        repeatView.setText(loops == 0 ? "Repeat \u221E" : "Repeat " + loops);
    }

    private void updateStatus() {
        if (status == null) return;
        String t;
        int c;
        if (recording) {
            t = "\u25CF REC  " + strokes.size() + " actions";
            c = Color.rgb(255, 110, 110);
        } else if (playing) {
            t = playRun == 0 ? "Starting..."
                    : "\u25B6 Run " + playRun + (playTotal == 0 ? " / \u221E" : " / " + playTotal);
            c = Color.rgb(110, 220, 130);
        } else {
            t = "Ready  \u00B7  " + strokes.size() + " actions  (drag here)";
            c = Color.WHITE;
        }
        status.setText(t);
        status.setTextColor(c);
    }

    // ---------- recording ----------
    private void toggleRecord() {
        if (recording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (playing) return;
        strokes.clear();
        cur = null;
        recording = true;
        recStart = SystemClock.uptimeMillis();

        capture = new View(this);
        capture.setBackgroundColor(Color.argb(28, 255, 0, 0)); // faint red tint = recording
        capture.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                long now = SystemClock.uptimeMillis() - recStart;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cur = new Stroke();
                        cur.start = now;
                        cur.pts.add(new float[]{ev.getRawX(), ev.getRawY(), 0});
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (cur != null) {
                            cur.pts.add(new float[]{ev.getRawX(), ev.getRawY(), now - cur.start});
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (cur != null) {
                            cur.pts.add(new float[]{ev.getRawX(), ev.getRawY(), now - cur.start});
                            cur.duration = Math.max(1, now - cur.start);
                            Stroke done = cur;
                            cur = null;
                            strokes.add(done);
                            updateStatus();
                            replayToApp(done);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cur = null;
                        return true;
                    default:
                        return true;
                }
            }
        });

        captureLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(capture, captureLp);

        // put the control bar back on top of the capture layer
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        wm.addView(panel, panelLp);
        updateStatus();
    }

    private void stopRecording() {
        recording = false;
        cur = null;
        try {
            if (capture != null) wm.removeView(capture);
        } catch (Exception ignored) {
        }
        capture = null;
        updateStatus();
    }

    private void setCaptureTouchable(boolean touchable) {
        if (capture == null || captureLp == null) return;
        if (touchable) {
            captureLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            captureLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            wm.updateViewLayout(capture, captureLp);
        } catch (Exception ignored) {
        }
    }

    /** Pass the just-recorded gesture on to the app underneath, so it still reacts while recording. */
    private void replayToApp(Stroke s) {
        setCaptureTouchable(false);
        boolean ok = dispatchGesture(build(s, 1.0), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                setCaptureTouchable(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                setCaptureTouchable(true);
            }
        }, null);
        if (!ok) setCaptureTouchable(true);
    }

    // ---------- playback ----------
    private GestureDescription build(Stroke s, double speed) {
        Path p = new Path();
        float[] first = s.pts.get(0);
        p.moveTo(first[0], first[1]);
        for (int i = 1; i < s.pts.size(); i++) {
            float[] q = s.pts.get(i);
            p.lineTo(q[0], q[1]);
        }
        long dur = (long) Math.max(1, s.duration / speed);
        dur = Math.min(dur, GestureDescription.getMaxGestureDuration());
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        return b.build();
    }

    private void play() {
        if (playing || recording || strokes.isEmpty()) return;
        playList = new ArrayList<>(strokes);
        playTotal = loops;
        playSpeed = speeds[speedIdx];
        playRun = 0;
        playing = true;
        updateStatus();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startRun();
            }
        }, 800);
    }

    private void startRun() {
        if (!playing) return;
        playRun++;
        updateStatus();

        final double sp = playSpeed;
        final long base = playList.get(0).start;
        long end = 0;
        for (final Stroke s : playList) {
            long delay = (long) ((s.start - base) / sp);
            long len = (long) Math.max(1, s.duration / sp);
            end = Math.max(end, delay + len);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (playing) dispatchGesture(build(s, sp), null, null);
                }
            }, delay);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                if (playTotal == 0 || playRun < playTotal) {
                    startRun();
                } else {
                    stopAll();
                }
            }
        }, end + 200);
    }

    private void stopAll() {
        handler.removeCallbacksAndMessages(null);
        playing = false;
        if (recording) stopRecording();
        updateStatus();
    }
}
