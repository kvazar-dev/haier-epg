package dev.kvazar.epg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The guide grid: a channel column on the left, programs by time on the right, twelve rows a page, one cursor.
 * Everything is drawn on one Canvas, so a page costs a few dozen rectangles and strings rather than a few hundred
 * views. Navigation is a state machine over (selected row, selected time): Up/Down keep the time, Left/Right walk
 * the programs of the row, and the time window slides in half-hour steps when the cursor reaches its edge.
 */
public final class GuideView extends View {
    interface Listener {
        void onSelectionChanged();
        void onNeedPrograms(List<Channel> visible, long from, long to);
    }

    interface Programs {
        List<Program> get(long channelId);
    }

    static final int ROWS = 12;
    static final long STEP_MS = 30 * 60 * 1000L;
    static final long WINDOW_MS = 3 * 60 * 60 * 1000L;
    /** How far back and forward the cursor may go. DVB schedules carry about a week. */
    private static final long BACK_MS = 6 * 60 * 60 * 1000L;
    private static final long FORWARD_MS = 8 * 24 * 60 * 60 * 1000L;

    private final float density;
    private final TextPaint numberPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint nowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int colorText, colorDim, colorFaint, colorAccent, colorAccentText, colorRowLine, colorBlock, colorBlockPast,
            colorBlockCurrent, colorChannelCurrent, colorNow, colorHeader;

    private List<Channel> channels = Collections.emptyList();
    private Programs programs = id -> null;
    private Set<String> scheduled = Collections.emptySet();
    private Listener listener;

    private int firstRow;
    private int selRow;
    private long selTime;
    private long windowStart;
    private long now;
    private long currentChannelId = -1;
    private final String noData, loadingText, nowText;

    public GuideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        colorText = color(R.color.text);
        colorDim = color(R.color.text_dim);
        colorFaint = color(R.color.text_faint);
        colorAccent = color(R.color.accent);
        colorAccentText = color(R.color.accent_text);
        colorRowLine = color(R.color.row_line);
        colorBlock = color(R.color.block);
        colorBlockPast = color(R.color.block_past);
        colorBlockCurrent = color(R.color.block_current);
        colorChannelCurrent = color(R.color.channel_current);
        colorNow = color(R.color.now_line);
        colorHeader = color(R.color.header_text);
        noData = context.getString(R.string.no_data);
        loadingText = context.getString(R.string.loading);
        nowText = context.getString(R.string.now);

        numberPaint.setTextSize(sp(13));
        numberPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        numberPaint.setColor(colorText);
        namePaint.setTextSize(sp(12));
        namePaint.setColor(colorText);
        titlePaint.setTextSize(sp(12.5f));
        titlePaint.setColor(colorText);
        headerPaint.setTextSize(sp(11));
        headerPaint.setColor(colorHeader);
        hintPaint.setTextSize(sp(11));
        hintPaint.setColor(colorFaint);
        line.setColor(colorRowLine);
        line.setStrokeWidth(Math.max(1f, density * 0.5f));
        nowPaint.setColor(colorNow);
        nowPaint.setStrokeWidth(density * 1.5f);

        now = System.currentTimeMillis();
        selTime = now;
        windowStart = alignDown(now);
        setFocusable(false);
    }

    private int color(int res) {
        return getResources().getColor(res, getContext().getTheme());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    private float dp(float v) {
        return v * density;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setPrograms(Programs p) {
        programs = p;
    }

    void setScheduled(Set<String> keys) {
        scheduled = keys == null ? Collections.emptySet() : keys;
        invalidate();
    }

    void setCurrentChannel(long id) {
        currentChannelId = id;
        invalidate();
    }

    /** New channel list; the cursor stays on the same channel when it is still there, else on the given one. */
    void setChannels(List<Channel> list, long preferredId) {
        long keepId = selectedChannel() != null ? selectedChannel().id : preferredId;
        channels = list == null ? Collections.emptyList() : list;
        selRow = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == keepId) { selRow = i; break; }
        }
        firstRow = Math.max(0, Math.min(selRow - ROWS / 2, channels.size() - ROWS));
        if (firstRow < 0) firstRow = 0;
        request();
        notifySelection();
        invalidate();
    }

    void tick() {
        now = System.currentTimeMillis();
        invalidate();
    }

    Channel selectedChannel() {
        return selRow >= 0 && selRow < channels.size() ? channels.get(selRow) : null;
    }

    Program selectedProgram() {
        Channel ch = selectedChannel();
        if (ch == null) return null;
        List<Program> list = programs.get(ch.id);
        return list == null ? null : programAt(list, selTime);
    }

    long now() {
        return now;
    }

    /** Puts the cursor on the channel with this display number, if there is one. */
    boolean selectNumber(String number) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number.equals(number)) {
                selRow = i;
                keepRowVisible();
                request();
                notifySelection();
                invalidate();
                return true;
            }
        }
        return false;
    }

    boolean onKey(int keyCode) {
        if (channels.isEmpty()) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: moveRow(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveRow(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP: moveRow(-ROWS); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN: moveRow(ROWS); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: moveLeft(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveRight(); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: shiftWindow(-WINDOW_MS); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: shiftWindow(WINDOW_MS); return true;
            default: return false;
        }
    }

    private void moveRow(int delta) {
        int target = Math.max(0, Math.min(channels.size() - 1, selRow + delta));
        if (target == selRow) return;
        if (Math.abs(delta) >= ROWS) {
            firstRow = Math.max(0, Math.min(firstRow + delta, Math.max(0, channels.size() - ROWS)));
        }
        selRow = target;
        keepRowVisible();
        request();
        notifySelection();
        invalidate();
    }

    private void keepRowVisible() {
        if (selRow < firstRow) firstRow = selRow;
        if (selRow >= firstRow + ROWS) firstRow = selRow - ROWS + 1;
        firstRow = Math.max(0, Math.min(firstRow, Math.max(0, channels.size() - ROWS)));
    }

    private void moveRight() {
        Channel ch = selectedChannel();
        List<Program> list = ch == null ? null : programs.get(ch.id);
        Program p = list == null ? null : programAt(list, selTime);
        long target;
        if (p != null) {
            target = p.end;
        } else {
            Program n = list == null ? null : nextAfter(list, selTime);
            target = n != null ? n.start : alignDown(selTime) + STEP_MS;
        }
        if (target > now + FORWARD_MS) return;
        selTime = target;
        while (selTime >= windowStart + WINDOW_MS - STEP_MS / 2) windowStart += STEP_MS;
        request();
        notifySelection();
        invalidate();
    }

    private void moveLeft() {
        Channel ch = selectedChannel();
        List<Program> list = ch == null ? null : programs.get(ch.id);
        Program p = list == null ? null : programAt(list, selTime);
        long target;
        if (p != null) {
            target = p.start - 1;
        } else {
            Program v = list == null ? null : prevBefore(list, selTime);
            target = v != null ? v.end - 1 : alignDown(selTime) - 1;
        }
        if (target < now - BACK_MS) return;
        selTime = target;
        while (selTime < windowStart + STEP_MS / 2 && windowStart > now - BACK_MS) windowStart -= STEP_MS;
        request();
        notifySelection();
        invalidate();
    }

    private void shiftWindow(long delta) {
        long ws = windowStart + delta;
        if (ws < alignDown(now - BACK_MS) || ws > now + FORWARD_MS) return;
        windowStart = ws;
        selTime += delta;
        request();
        notifySelection();
        invalidate();
    }

    private void request() {
        if (listener == null || channels.isEmpty()) return;
        int end = Math.min(channels.size(), firstRow + ROWS);
        listener.onNeedPrograms(channels.subList(firstRow, end), windowStart - STEP_MS, windowStart + WINDOW_MS + STEP_MS);
    }

    private void notifySelection() {
        if (listener != null) listener.onSelectionChanged();
    }

    /** The repository loaded or dropped programs: redraw, and ask again if the visible rows are still empty. */
    void onProgramsChanged() {
        invalidate();
        notifySelection();
        boolean missing = false;
        int end = Math.min(channels.size(), firstRow + ROWS);
        for (int i = firstRow; i < end; i++) {
            if (programs.get(channels.get(i).id) == null) { missing = true; break; }
        }
        if (missing) request();
    }

    static long alignDown(long t) {
        return t - Math.floorMod(t, STEP_MS);
    }

    private static Program programAt(List<Program> list, long t) {
        for (int i = 0, n = list.size(); i < n; i++) {
            Program p = list.get(i);
            if (p.start > t) break;
            if (t < p.end) return p;
        }
        return null;
    }

    private static Program nextAfter(List<Program> list, long t) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i).start > t) return list.get(i);
        }
        return null;
    }

    private static Program prevBefore(List<Program> list, long t) {
        Program found = null;
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i).end <= t) found = list.get(i); else break;
        }
        return found;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        request();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float colW = dp(150);
        float headerH = dp(22);
        float gridX = colW + dp(6);
        float gridW = w - gridX;
        float rowH = (h - headerH) / ROWS;
        long windowEnd = windowStart + WINDOW_MS;
        float pxPerMs = gridW / WINDOW_MS;

        // Time ruler; a tick label that the "now" pill would cover is left out.
        boolean nowVisible = now >= windowStart && now < windowEnd;
        float nowX = gridX + (now - windowStart) * pxPerMs;
        String nowLabel = TimeFmt.hm(now);
        float nowW = headerPaint.measureText(nowLabel) + dp(12);
        for (long t = windowStart; t < windowEnd; t += STEP_MS) {
            float x = gridX + (t - windowStart) * pxPerMs;
            line.setColor(colorRowLine);
            canvas.drawLine(x, headerH, x, h, line);
            float lx = x + dp(4);
            float lw = headerPaint.measureText(TimeFmt.hm(t));
            if (nowVisible && lx < nowX + nowW / 2 + dp(6) && lx + lw > nowX - nowW / 2 - dp(6)) continue;
            canvas.drawText(TimeFmt.hm(t), lx, headerH - dp(7), headerPaint);
        }

        int end = Math.min(channels.size(), firstRow + ROWS);
        for (int i = firstRow; i < end; i++) {
            Channel ch = channels.get(i);
            float top = headerH + (i - firstRow) * rowH;
            float bottom = top + rowH;
            boolean selectedRow = i == selRow;

            if (ch.id == currentChannelId) {
                fill.setColor(colorChannelCurrent);
                canvas.drawRect(0, top, w, bottom, fill);
            }
            line.setColor(colorRowLine);
            canvas.drawLine(0, bottom, w, bottom, line);

            // Channel cell
            if (selectedRow) {
                fill.setColor(colorBlock);
                rect.set(dp(2), top + dp(2), colW - dp(2), bottom - dp(2));
                canvas.drawRoundRect(rect, dp(6), dp(6), fill);
            }
            float textY = top + rowH / 2 + numberPaint.getTextSize() * 0.35f;
            float numW = numberPaint.measureText(ch.number) + dp(10);
            numberPaint.setColor(selectedRow ? colorText : colorDim);
            canvas.drawText(ch.number, dp(10), textY, numberPaint);
            CharSequence name = TextUtils.ellipsize(ch.name, namePaint, colW - numW - dp(18), TextUtils.TruncateAt.END);
            namePaint.setColor(selectedRow ? colorText : colorDim);
            canvas.drawText(name, 0, name.length(), dp(10) + numW, textY, namePaint);

            // Programs
            List<Program> list = programs.get(ch.id);
            Program sel = selectedRow && list != null ? programAt(list, selTime) : null;
            if (list == null) {
                canvas.drawText(loadingText, gridX + dp(8), textY, hintPaint);
            } else {
                boolean any = false;
                for (int k = 0, n = list.size(); k < n; k++) {
                    Program p = list.get(k);
                    if (p.end <= windowStart) continue;
                    if (p.start >= windowEnd) break;
                    any = true;
                    float x1 = gridX + Math.max(0, p.start - windowStart) * pxPerMs;
                    float x2 = gridX + Math.min(WINDOW_MS, p.end - windowStart) * pxPerMs;
                    boolean isSel = p == sel;
                    rect.set(x1 + dp(1), top + dp(2), x2 - dp(1), bottom - dp(2));
                    if (isSel) fill.setColor(colorAccent);
                    else if (p.end <= now) fill.setColor(colorBlockPast);
                    else if (p.start <= now) fill.setColor(colorBlockCurrent);
                    else fill.setColor(colorBlock);
                    canvas.drawRoundRect(rect, dp(4), dp(4), fill);
                    float avail = rect.width() - dp(12);
                    if (avail > dp(14)) {
                        String t = scheduled.contains(p.key()) ? "⏰ " + p.title : p.title;
                        CharSequence title = TextUtils.ellipsize(t, titlePaint, avail, TextUtils.TruncateAt.END);
                        titlePaint.setColor(isSel ? colorAccentText : (p.end <= now ? colorDim : colorText));
                        canvas.drawText(title, 0, title.length(), rect.left + dp(6), textY, titlePaint);
                    }
                }
                if (!any) canvas.drawText(noData, gridX + dp(8), textY, hintPaint);
            }
            if (selectedRow && sel == null) {
                // The cursor sits in a gap or on a row without data: outline the half hour it points at.
                long cell = alignDown(Math.max(selTime, windowStart));
                float x1 = gridX + Math.max(0, cell - windowStart) * pxPerMs;
                float x2 = gridX + Math.min(WINDOW_MS, cell + STEP_MS - windowStart) * pxPerMs;
                rect.set(x1 + dp(1), top + dp(2), x2 - dp(1), bottom - dp(2));
                fill.setColor(colorAccent);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(dp(2));
                canvas.drawRoundRect(rect, dp(4), dp(4), fill);
                fill.setStyle(Paint.Style.FILL);
            }
        }

        // Now line, with the clock in a red pill on the ruler
        if (nowVisible) {
            canvas.drawLine(nowX, headerH, nowX, h, nowPaint);
            float left = Math.max(gridX, Math.min(nowX - nowW / 2, w - nowW));
            rect.set(left, dp(1), left + nowW, headerH - dp(2));
            fill.setColor(colorNow);
            canvas.drawRoundRect(rect, dp(9), dp(9), fill);
            headerPaint.setColor(colorAccentText);
            canvas.drawText(nowLabel, left + dp(6), headerH - dp(7), headerPaint);
            headerPaint.setColor(colorHeader);
        }
    }
}
