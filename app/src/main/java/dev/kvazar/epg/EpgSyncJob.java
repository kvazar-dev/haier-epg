package dev.kvazar.epg;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/** The nightly schedule download: a persisted periodic job that needs a network, run every twelve hours. */
public final class EpgSyncJob extends JobService {
    private static final int JOB_ID = 1;
    private static final long PERIOD_MS = 12 * 3600_000L;
    private Thread worker;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, EpgSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build();
        js.schedule(job);
        Log.i(Tuner.TAG, "sync job scheduled every " + PERIOD_MS / 3600_000L + " h");
    }

    @Override public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            try {
                EpgRepository.get(this).syncNow(null);
            } finally {
                jobFinished(params, false);
            }
        }, "epg-sync-job");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
