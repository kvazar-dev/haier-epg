package dev.kvazar.epg;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;

/** "Starting now: switch?" with a countdown; silence switches, Back or Cancel leaves the TV where it is. */
public final class PromptActivity extends Activity {
    private static final int COUNTDOWN_S = 15;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int left = COUNTDOWN_S;
    private long channelId;
    private Button switchNow;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            left--;
            if (left <= 0) {
                doSwitch();
                return;
            }
            switchNow.setText(getString(R.string.prompt_switch, left));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt);
        channelId = getIntent().getLongExtra(Reminders.EXTRA_CHANNEL, -1);
        String title = getIntent().getStringExtra(Reminders.EXTRA_TITLE);
        String name = getIntent().getStringExtra(Reminders.EXTRA_CHANNEL_NAME);
        String number = getIntent().getStringExtra(Reminders.EXTRA_CHANNEL_NUMBER);
        long start = getIntent().getLongExtra(Reminders.EXTRA_START, 0);
        String channel = (number == null ? "" : number + " · ") + (name == null ? "" : name) + ", " + TimeFmt.hm(start);
        ((TextView) findViewById(R.id.text)).setText(getString(R.string.prompt_text, title == null ? "" : title, channel));
        switchNow = findViewById(R.id.switch_now);
        switchNow.setText(getString(R.string.prompt_switch, left));
        switchNow.setOnClickListener(v -> doSwitch());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
        switchNow.requestFocus();
        handler.postDelayed(tick, 1000L);
    }

    private void doSwitch() {
        handler.removeCallbacks(tick);
        if (channelId >= 0) Tuner.tune(this, channelId);
        finish();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
