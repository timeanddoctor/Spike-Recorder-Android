/*
 * Backyard Brains Android App
 * Copyright (C) 2011 Backyard Brains
 * by Nathan Dotz <nate (at) backyardbrains.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.backyardbrains.data.processing;

import android.os.Handler;
import android.support.annotation.NonNull;
import com.backyardbrains.audio.HeartbeatHelper;
import com.backyardbrains.utils.AudioUtils;
import java.util.ArrayList;
import java.util.Iterator;

import static com.backyardbrains.utils.LogUtils.LOGD;
import static com.backyardbrains.utils.LogUtils.makeLogTag;

public class ThresholdProcessor implements SampleProcessor {

    private static final String TAG = makeLogTag(ThresholdProcessor.class);

    // We shouldn't process more than 2.4 seconds of samples in any given moment
    private static final double DEFAULT_MAX_PROCESSED_SECONDS = 1;
    // When threshold is hit we should have a dead period of 5ms before checking for next threshold hit
    private static final double DEFAULT_DEAD_PERIOD_SECONDS = 0.005;
    // Minimum number of seconds without a heartbeat before resetting the heartbeat helper
    private static final double DEFAULT_MIN_BPM_RESET_PERIOD_SECONDS = 3;
    // Default number of sample streams that should be summed to get averaged samples
    private static final int DEFAULT_AVERAGED_SAMPLE_COUNT = 1;
    // Default sample rate used when processing incoming data
    private static final int DEFAULT_SAMPLE_RATE = AudioUtils.SAMPLE_RATE;

    // Runnable that triggers updated of the trigger value
    private final UpdateTriggerValueRunnable updateTriggerValueRunnable = new UpdateTriggerValueRunnable();

    // Max number of seconds that can be processed at any given moment
    private double maxProcessedSeconds = DEFAULT_MAX_PROCESSED_SECONDS;
    // Dead period in seconds during which incoming samples shouldn't be processed
    private double deadPeriodSeconds = DEFAULT_DEAD_PERIOD_SECONDS;
    // Period in seconds that should pass without new heartbeat before resetting the heartbeat helper
    private double minBpmResetPeriodSeconds = DEFAULT_MIN_BPM_RESET_PERIOD_SECONDS;
    // Number of samples that needs to be summed to get the averaged sample
    private int averagedSampleCount = DEFAULT_AVERAGED_SAMPLE_COUNT;
    // Sample rate that should be used when processing incoming data
    private int sampleRate = DEFAULT_SAMPLE_RATE;

    // Number of samples that we collect for one sample stream
    private int sampleCount = (int) (sampleRate * maxProcessedSeconds);
    // We need to buffer half of samples total count up to the sample that hit's threshold
    private int bufferSampleCount = sampleCount / 2;
    // Dead period when we don't check for threshold after hitting one
    private int deadPeriodCount = (int) (sampleRate * deadPeriodSeconds);
    // Period without heartbeat that we wait for before reseting the heartbeat helper
    private int minBpmResetPeriodCount = (int) (sampleRate * minBpmResetPeriodSeconds);
    // Holds averages of all the saved samples by index
    private short[] averagedSamples;
    // Holds sums of all the saved samples by index
    private int[] summedSamples;
    // Holds samples counts summed at specified position
    private int[] summedSamplesCounts;

    // Buffer that holds most recent 680 ms of audio so we can prepend new sample buffers when threshold is hit
    private SampleBuffer buffer;
    private ArrayList<short[]> samplesForCalculation;
    private ArrayList<Samples> unfinishedSamplesForCalculation;
    private Handler handler;
    private int lastTriggeredValue;
    private double lastMaxProcessedSeconds;
    private double lastDeadPeriod;
    private int lastAveragedSampleCount;
    private int lastSampleRate;
    private short prevSample;
    // Threshold value that triggers the averaging
    @SuppressWarnings("WeakerAccess") int triggerValue = Integer.MAX_VALUE;
    // Holds reference to HeartbeatHelper that processes threshold hits as heart beats
    private HeartbeatHelper heartbeatHelper = new HeartbeatHelper(sampleRate);
    // Index of the sample that triggered the threshold hit
    private int lastTriggerSampleCounter;
    // Counts samples between two resets that need to be passed to heartbeat helper
    private int sampleCounter;
    private int deadPeriodSampleCounter;
    private boolean inDeadPeriod;
    private boolean processBpm;

    /**
     * Runnable that update the trigger value above which new averaging array is added
     */
    private class UpdateTriggerValueRunnable implements Runnable {

        private int threshold;

        @Override public void run() {
            triggerValue = threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }
    }

    /**
     * Creates new {@link ThresholdProcessor} that uses {@code size} number of sample sequences for average spike
     * calculation.
     */
    public ThresholdProcessor(int size, double maxProcessedSeconds, double deadPeriodSeconds) {
        // set initial max processing time
        setMaxProcessedSeconds(maxProcessedSeconds);
        // set initial number of seconds for dead period
        setDeadPeriodSeconds(deadPeriodSeconds);
        // set initial number of chunks to use for calculating average
        setAveragedSampleCount(size);
        // init buffers
        reset();
        // handler used for setting threshold
        handler = new Handler();
    }

    @NonNull @Override public short[] process(@NonNull short[] samples, int length) {
        if (samples.length > 0) {
            processIncomingData(samples, length);

            return averagedSamples;
        }

        return new short[0];
    }

    /**
     * Clears all data.
     */
    public void close() {
        reset();
    }

    /**
     * Sets the maximum number of seconds that can be processed at any given moment.
     */
    @SuppressWarnings("WeakerAccess") public void setMaxProcessedSeconds(double maxProcessedSeconds) {
        LOGD(TAG, "setMaxProcessedSeconds(" + maxProcessedSeconds + ")");

        if (maxProcessedSeconds > 0) this.maxProcessedSeconds = maxProcessedSeconds;
    }

    /**
     * Sets the number of seconds for dead period time during which incoming samples will not processed.
     */
    @SuppressWarnings("WeakerAccess") public void setDeadPeriodSeconds(double deadPeriodSeconds) {
        LOGD(TAG, "setDeadPeriodSeconds(" + deadPeriodSeconds + ")");

        if (deadPeriodSeconds > 0) this.deadPeriodSeconds = deadPeriodSeconds;
    }

    /**
     * Returns the number of sample sequences that should be summed to get the average spike value.
     */
    public int getAveragedSampleCount() {
        return averagedSampleCount;
    }

    /**
     * Sets the number of sample sequences that should be summed to get the average spike value.
     */
    public void setAveragedSampleCount(int averagedSampleCount) {
        LOGD(TAG, "setAveragedSampleCount(" + averagedSampleCount + ")");

        if (averagedSampleCount > 0) this.averagedSampleCount = averagedSampleCount;
    }

    /**
     * Sets sample rate to be used when processing incoming samples.
     */
    public void setSampleRate(int sampleRate) {
        LOGD(TAG, "setSampleRate(" + sampleRate + ")");

        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    /**
     * Set's the sample frequency threshold.
     */
    public void setThreshold(final float threshold) {
        updateTriggerValueRunnable.setThreshold((int) threshold);
        handler.post(updateTriggerValueRunnable);
    }

    /**
     * Starts/stops processing heartbeat
     */
    public void setBpmProcessing(boolean processBpm) {
        if (this.processBpm == processBpm) return;
        // reset BPM if we stopped processing heartbeat
        if (!processBpm) resetBpm();

        this.processBpm = processBpm;
    }

    // Resets all local variables used for the heartbeat processing
    private void resetBpm() {
        if (heartbeatHelper != null) heartbeatHelper.reset("resetBpm()");
        sampleCounter = 0;
        lastTriggerSampleCounter = 0;
    }

    // Resets all the fields used for calculations
    private void reset() {
        sampleCount = (int) (sampleRate * maxProcessedSeconds);
        bufferSampleCount = sampleCount / 2;
        deadPeriodCount = (int) (sampleRate * deadPeriodSeconds);
        minBpmResetPeriodCount = (int) (sampleRate * minBpmResetPeriodSeconds);

        buffer = new SampleBuffer(bufferSampleCount);
        samplesForCalculation = new ArrayList<>(averagedSampleCount * 2);
        summedSamples = null;
        summedSamplesCounts = null;
        averagedSamples = new short[sampleCount];
        unfinishedSamplesForCalculation = new ArrayList<>();
        prevSample = 0;
        heartbeatHelper.reset("reset()");
        heartbeatHelper.setSampleRate(sampleRate);
        lastTriggerSampleCounter = 0;
        sampleCounter = 0;
        deadPeriodSampleCounter = 0;
        inDeadPeriod = false;
    }

    // Processes the incoming data and triggers all necessary calculations.
    private void processIncomingData(short[] incomingSamples, int length) {
        boolean shouldReset = false;
        // reset buffers if threshold changed
        if (lastTriggeredValue != triggerValue) {
            LOGD(TAG, "Resetting because trigger value has changed");
            lastTriggeredValue = triggerValue;
            shouldReset = true;
        }
        // reset buffers if max processed seconds changed
        if (lastMaxProcessedSeconds != maxProcessedSeconds) {
            LOGD(TAG, "Resetting because max number of processed seconds has changed");
            lastMaxProcessedSeconds = maxProcessedSeconds;
            shouldReset = true;
        }
        // reset buffers if dead period changed
        if (lastDeadPeriod != deadPeriodSeconds) {
            LOGD(TAG, "Resetting because dead period has changed");
            lastDeadPeriod = deadPeriodSeconds;
            shouldReset = true;
        }
        // reset buffers if averages sample count changed
        if (lastAveragedSampleCount != averagedSampleCount) {
            LOGD(TAG, "Resetting because last averaged sample count has changed");
            lastAveragedSampleCount = averagedSampleCount;
            shouldReset = true;
        }
        // reset buffers if sample rate changed
        if (lastSampleRate != sampleRate) {
            LOGD(TAG, "Resetting because sample rate has changed");
            lastSampleRate = sampleRate;
            shouldReset = true;
        }

        if (shouldReset) reset();

        // append unfinished sample buffers whit incoming samples
        for (Samples samples : unfinishedSamplesForCalculation) {
            samples.append(incomingSamples, length);
        }

        short currentSample;
        int copyLength;
        // loop through incoming samples and listen for the threshold hit
        for (int i = 0; i < length; i++) {
            currentSample = incomingSamples[i];

            // heartbeat processing
            if (processBpm) {
                sampleCounter++;
                lastTriggerSampleCounter++;

                // check if minimum BPM reset period passed after last threshold hit and reset if necessary
                if (lastTriggerSampleCounter > minBpmResetPeriodCount) resetBpm();
            }
            // end of heartbeat processing

            if (!inDeadPeriod) {
                // check if we hit the threshold
                if ((triggerValue >= 0 && currentSample > triggerValue && prevSample <= triggerValue) || (
                    triggerValue < 0 && currentSample < triggerValue && prevSample >= triggerValue)) {
                    // we hit the threshold, turn on dead period of 5ms
                    inDeadPeriod = true;

                    LOGD(TAG, "THRESHOLD HIT!!! - " + triggerValue);

                    // create new samples for current threshold
                    final short[] centeredWave = new short[sampleCount];
                    copyLength = Math.min(bufferSampleCount + i, length);
                    System.arraycopy(buffer.getArray(), i, centeredWave, 0, buffer.getArray().length - i);
                    System.arraycopy(incomingSamples, 0, centeredWave, buffer.getArray().length - i, copyLength);

                    unfinishedSamplesForCalculation.add(
                        new Samples(centeredWave, buffer.getArray().length - i + copyLength));

                    // heartbeat processing
                    if (processBpm) {
                        // pass data to heartbeat helper
                        heartbeatHelper.beat(sampleCounter);
                        // reset the last triggered sample counter
                        // and start counting for next heartbeat reset period
                        lastTriggerSampleCounter = 0;
                    }
                    // end of heartbeat processing
                }
            } else {
                if (++deadPeriodSampleCounter > deadPeriodCount) {
                    deadPeriodSampleCounter = 0;
                    inDeadPeriod = false;
                }
            }

            prevSample = currentSample;
        }

        // add samples to local buffer
        buffer.add(incomingSamples, length);

        // add incoming samples to calculation of averages
        int len = unfinishedSamplesForCalculation.size();
        for (int i = 0; i < len; i++) {
            addSamplesToCalculations(unfinishedSamplesForCalculation.get(i), i);
        }

        // move filled sample buffers from unfinished samples collection to finished samples collection
        final Iterator<Samples> iterator = unfinishedSamplesForCalculation.iterator();
        while (iterator.hasNext()) {
            final Samples samples = iterator.next();
            if (samples.isPopulated()) {
                if (samplesForCalculation.size() >= averagedSampleCount) samplesForCalculation.remove(0);
                samplesForCalculation.add(samples.samples);
                iterator.remove();
            }
        }

        // TODO: 9/20/2017 We should dump unnecessary finished samples to release the memory
    }

    private void addSamplesToCalculations(@NonNull Samples samples, int samplesIndex) {
        // init summed samples array
        if (summedSamples == null || summedSamplesCounts == null) {
            summedSamples = new int[sampleCount];
            summedSamplesCounts = new int[sampleCount];

            for (int i = samples.lastAveragedIndex; i < samples.nextSampleIndex; i++) {
                summedSamples[i] = samples.samples[i];
                summedSamplesCounts[i]++;
                averagedSamples[i] = samples.samples[i];
            }
            samples.lastAveragedIndex = samples.nextSampleIndex;

            return;
        }

        for (int i = samples.lastAveragedIndex; i < samples.nextSampleIndex; i++) {
            // if we are calculating averagedSampleCount + 1. sample we should subtract the oldest one in the sum
            if (summedSamplesCounts[i] >= averagedSampleCount) {
                // subtract the value and decrease summed samples count for current position
                if (averagedSampleCount <= samplesIndex) { // we look for the oldest one in the unfinished samples
                    summedSamples[i] -=
                        unfinishedSamplesForCalculation.get(samplesIndex - averagedSampleCount).samples[i];
                } else { // we look for the oldest one in the already collected and calculated samples
                    summedSamples[i] -=
                        samplesForCalculation.get(samplesForCalculation.size() - averagedSampleCount + samplesIndex)[i];
                }
                summedSamplesCounts[i]--;
            }
            // add new value and increase summed samples count for current position
            summedSamples[i] += samples.samples[i];
            summedSamplesCounts[i]++;
            // calculate the average
            averagedSamples[i] = (short) (summedSamples[i] / summedSamplesCounts[i]);
        }
        samples.lastAveragedIndex = samples.nextSampleIndex;
    }

    // Represents a buffer for samples that are collected and included in averages calculation
    private class Samples {
        // Array of received samples
        short[] samples;
        // Index of last sample that was included in averages calculations before new samples arrived
        int lastAveragedIndex;
        // Index of first "empty slot" at which future arriving samples will be appended
        int nextSampleIndex;

        Samples(@NonNull short[] samples, int nextSampleIndex) {
            this.samples = samples;
            this.lastAveragedIndex = 0;
            this.nextSampleIndex = nextSampleIndex;
        }

        /**
         * Returns whether all sample slots are populated (whether the buffer is full).
         */
        boolean isPopulated() {
            return nextSampleIndex == samples.length;
        }

        /**
         * Appends newly received samples and returns {@code true} if buffer is full, {@code false} otherwise.
         */
        void append(short[] samples, int length) {
            final int samplesToCopy = Math.min(this.samples.length - nextSampleIndex, length);
            System.arraycopy(samples, 0, this.samples, nextSampleIndex, samplesToCopy);
            nextSampleIndex += samplesToCopy;
        }
    }
}
