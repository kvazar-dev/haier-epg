package dev.kvazar.epg;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

/**
 * Switches the TV to a channel by handing its TvContract URI to the firmware's Live TV app, the way the stock
 * Google TV home did. No TvView of our own: the tuner, parental locks and the picture stay with Live TV.
 */
final class Tuner {
    static final String TAG = "EPG";
    static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";

    private Tuner() {}

    static boolean tune(Context ctx, long channelId) {
        Uri uri = TvContract.buildChannelUri(channelId);
        Intent aimed = new Intent(Intent.ACTION_VIEW, uri)
                .setPackage(LIVE_TV)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(ctx, aimed) || start(ctx, new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Prefs.setLastTuned(ctx, channelId);
            return true;
        }
        Log.w(TAG, "no app takes channel " + channelId);
        return false;
    }

    private static boolean start(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException e) {
            Log.w(TAG, "tune failed: " + e);
            return false;
        }
    }
}
