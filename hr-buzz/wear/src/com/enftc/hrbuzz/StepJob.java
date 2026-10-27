package com.enftc.hrbuzz;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Counts the day's steps once an hour, so "steps today" stays right when the app is not open. */
public class StepJob extends JobService {

    private static final int JOB_ID = 21;

    /** Schedules the hourly read, once; it survives reboots. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) {
            return;
        }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, StepJob.class))
                .setPeriodic(60 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        StepLog.snapshot(this, () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
