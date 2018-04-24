package com.backyardbrains.utils;

import android.support.annotation.NonNull;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Executor that runs a task on a new background thread.
 *
 * @author Tihomir Leka <ticapeca at gmail.com.
 */
public class DiskIOThreadExecutor implements Executor {

    private final Executor mDiskIO;

    public DiskIOThreadExecutor() {
        mDiskIO = Executors.newSingleThreadExecutor();
    }

    @Override public void execute(@NonNull Runnable command) {
        mDiskIO.execute(command);
    }
}