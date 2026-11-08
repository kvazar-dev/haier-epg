package dev.kvazar.epg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The guide screen: info panel on top, the grid below, the remote drives both. */
public final class GuideActivity extends Activity implements EpgRepository.Listener, GuideView.Listener {
    private static final int REQ_PERMISSION = 1;
    /** A @SystemApi name on the SDK side, but a plain dangerous permission on the TV. */
    private static final String PERM_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final long NUMBER_ENTRY_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EpgRepository repo;
    private GuideView guide;
    private TextView channelText, titleText, timeText, descText, clockText, dateText, message, numberEntry;
    private final StringBuilder number = new StringBuilder();
    private String syncStatus;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            guide.tick();
            updateClock();
            updateInfo();
            handler.postDelayed(this, 30_000L - System.currentTimeMillis() % 30_000L);
        }
    };

    private final Runnable applyNumber = this::applyNumber;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        guide = findViewById(R.id.guide);
        channelText = findViewById(R.id.channel);
        titleText = findViewById(R.id.title);
        timeText = findViewById(R.id.time);
        descText = findViewById(R.id.desc);
        clockText = findViewById(R.id.clock);
        dateText = findViewById(R.id.date);
        message = findViewById(R.id.message);
        numberEntry = findViewById(R.id.number_entry);

        repo = EpgRepository.get(this);
        guide.setPrograms(repo::programsFor);
        guide.setListener(this);
        guide.setCurrentChannel(Prefs.lastTuned(this));
        updateClock();
        // am start -n dev.kvazar.epg/.GuideActivity --ez dump true : channel and program diagnostics to logcat (tag EPG).
        if (getIntent().getBooleanExtra("dump", false)) repo.debugDump();
    }

    @Override protected void onStart() {
        super.onStart();
        repo.setListener(this);
        // An app update or a force stop drops the alarms while the list survives: arm them again on every start.
        Reminders.rescheduleAll(this);
        guide.setScheduled(Reminders.keys(this));
        if (checkSelfPermission(PERM_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showMessage(getString(R.string.no_permission));
            requestPermissions(new String[] { PERM_TV_LISTINGS }, REQ_PERMISSION);
        } else {
            showMessage(repo.channels().isEmpty() ? getString(R.string.loading) : null);
            if (!repo.channels().isEmpty()) onChannels(repo.channels());
            repo.start();
            startSyncIfStale();
        }
        handler.post(clockTick);
    }

    private void startSyncIfStale() {
        EpgSyncJob.schedule(this);
        if (repo.needsSync()) repo.syncInBackground();
    }

    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        repo.setListener(null);
        repo.stop();
        Channel ch = guide.selectedChannel();
        if (ch != null) Prefs.setLastSelected(this, ch.id);
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showMessage(getString(R.string.loading));
            repo.start();
            startSyncIfStale();
        } else {
            showMessage(getString(R.string.no_permission));
        }
    }

    // EpgRepository.Listener

    @Override public void onChannels(List<Channel> channels) {
        showMessage(channels.isEmpty() ? getString(R.string.no_channels) : null);
        long preferred = Prefs.lastTuned(this);
        if (preferred < 0) preferred = Prefs.lastSelected(this);
        guide.setChannels(channels, preferred);
    }

    @Override public void onPrograms() {
        guide.onProgramsChanged();
    }

    @Override public void onError(String msg) {
        showMessage(getString(R.string.no_permission));
    }

    @Override public void onSyncStatus(String message) {
        syncStatus = message;
        updateClock();
        if (message != null && message.startsWith("Программа не загружена")) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            syncStatus = null;
            updateClock();
        }
    }

    // GuideView.Listener

    @Override public void onSelectionChanged() {
        updateInfo();
    }

    @Override public void onNeedPrograms(List<Channel> visible, long from, long to) {
        repo.ensure(visible, from, to);
    }

    private void updateInfo() {
        Channel ch = guide.selectedChannel();
        Program p = guide.selectedProgram();
        if (ch == null) {
            channelText.setText("");
            titleText.setText("");
            timeText.setText("");
            descText.setText("");
            return;
        }
        channelText.setText(getString(R.string.channel_fmt, ch.number, ch.name));
        if (p == null) {
            titleText.setText(getString(R.string.no_data));
            timeText.setText("");
            descText.setText("");
            return;
        }
        titleText.setText(p.title);
        long now = guide.now();
        StringBuilder t = new StringBuilder(TimeFmt.range(p.start, p.end));
        String day = TimeFmt.dayLabel(p.start, now);
        if (!"сегодня".equals(day)) t.append(", ").append(day);
        if (p.covers(now)) {
            long leftMin = Math.max(0, (p.end - now + 59_999L) / 60_000L);
            t.append(" · осталось ").append(leftMin).append(" мин");
        }
        if (!p.genre.isEmpty()) t.append(" · ").append(p.genre);
        if (Reminders.isScheduled(this, p)) t.append(" · ").append(getString(R.string.scheduled_mark)).append(" запланировано");
        timeText.setText(t);
        descText.setText(p.description);
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        clockText.setText(TimeFmt.hm(now));
        String line2 = syncStatus;
        if (line2 == null) {
            String summary = repo.syncSummary();
            line2 = summary == null ? null : getString(R.string.sync_source, summary);
        }
        dateText.setText(capitalize(fullDate(now)) + (line2 == null ? "" : "\n" + line2));
    }

    private static String fullDate(long now) {
        return new java.text.SimpleDateFormat("EEEE, d MMMM", new java.util.Locale("ru")).format(new java.util.Date(now));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void showMessage(String text) {
        if (text == null) {
            message.setVisibility(View.GONE);
        } else {
            message.setText(text);
            message.setVisibility(View.VISIBLE);
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (number.length() < 4) number.append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
            numberEntry.setText(number);
            numberEntry.setVisibility(View.VISIBLE);
            handler.removeCallbacks(applyNumber);
            handler.postDelayed(applyNumber, NUMBER_ENTRY_MS);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (number.length() > 0) { handler.removeCallbacks(applyNumber); applyNumber(); return true; }
                activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_PROG_YELLOW:
                // Manual refresh of the downloaded schedule.
                if (!repo.isSyncing()) {
                    syncStatus = getString(R.string.sync_manual);
                    updateClock();
                    repo.syncInBackground();
                }
                return true;
            default:
                if (guide.onKey(keyCode)) return true;
                return super.onKeyDown(keyCode, event);
        }
    }

    private void applyNumber() {
        String n = number.toString();
        number.setLength(0);
        numberEntry.setVisibility(View.GONE);
        if (!n.isEmpty() && !guide.selectNumber(n)) {
            // A number without a leading zero: "7" for "07", and the other way round.
            String trimmed = n.replaceFirst("^0+(?=\\d)", "");
            if (!trimmed.equals(n)) guide.selectNumber(trimmed);
        }
    }

    private void activate() {
        Channel ch = guide.selectedChannel();
        if (ch == null) return;
        Program p = guide.selectedProgram();
        long now = guide.now();
        if (p == null || p.start <= now) {
            if (Tuner.tune(this, ch.id)) finish();
            return;
        }
        boolean scheduled = Reminders.isScheduled(this, p);
        String when = TimeFmt.hm(p.start);
        String day = TimeFmt.dayLabel(p.start, now);
        if (!"сегодня".equals(day)) when = when + ", " + day;
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(scheduled ? getString(R.string.schedule_remove) + "?" : getString(R.string.schedule_title))
                .setMessage(getString(R.string.schedule_text, p.title, when, getString(R.string.channel_fmt, ch.number, ch.name)))
                .setNegativeButton(R.string.schedule_cancel, null);
        if (scheduled) {
            b.setPositiveButton(R.string.schedule_remove, (d, w) -> {
                Reminders.remove(this, ch.id, p.start);
                guide.setScheduled(Reminders.keys(this));
                updateInfo();
                Toast.makeText(this, R.string.unscheduled_toast, Toast.LENGTH_SHORT).show();
            });
        } else {
            final String whenText = when;
            b.setPositiveButton(R.string.schedule_yes, (d, w) -> {
                Reminders.add(this, ch, p);
                guide.setScheduled(Reminders.keys(this));
                updateInfo();
                Toast.makeText(this, getString(R.string.scheduled_toast, ch.name, whenText), Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }
}
