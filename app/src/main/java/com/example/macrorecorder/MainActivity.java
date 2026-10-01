package com.example.macrorecorder;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView state;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Macro Recorder");
        title.setTextSize(24);
        root.addView(title);

        state = new TextView(this);
        state.setTextSize(16);
        state.setPadding(0, dp(12), 0, dp(12));
        root.addView(state);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText(
                "Permissions (setup once):\n"
                + "Android does not show an install-time prompt for Accessibility access. "
                + "This app needs it to capture your taps and swipes and replay them. "
                + "Accessibility access lets the app observe screen interactions and perform gestures.\n\n"
                + "On a Galaxy Fold (Android 13 or later), Android may block this for apps "
                + "installed from an APK. First tap \"Open app info\", tap the \u22EE menu, "
                + "then tap \"Allow restricted settings\" and confirm with your screen lock. "
                + "Return here, tap \"Open Accessibility settings\", then Installed apps > "
                + "Macro Recorder and turn it on. If Android says the setting is restricted, "
                + "allow restricted settings in App info before trying again.\n\n"
                + "Use:\n"
                + "\u2022 A floating bar appears on top of every app. Drag it by its status text.\n"
                + "\u2022 \u25CF Rec: do your taps and swipes, then press Stop.\n"
                + "\u2022 \u25B6 Play: replays them. Use \u2212 / + to set how many times "
                + "(0 = forever) and Speed to go faster or slower.\n"
                + "\u2022 \u25A0 Stop: stops recording or playback at any time.\n"
                + "\u2022 \u00D7 turns the service off.\n\n"
                + "Note: while recording, your actions are captured and then passed on to the app, "
                + "so they run a moment after you lift your finger. Keep the same screen layout "
                + "when you play back.");
        root.addView(help);

        addButton(root, "Open Accessibility settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        addButton(root, "Open app info", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        root.addView(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.contains(getPackageName() + "/");
        state.setText(on ? "Status: ON \u2013 the floating bar should be visible."
                : "Status: OFF \u2013 enable it in Accessibility settings.");
    }
}
