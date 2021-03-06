package com.backyardbrains.data.persistance;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.backyardbrains.data.SpikeValueAndIndex;
import com.backyardbrains.data.persistance.entity.Spike;
import com.backyardbrains.data.persistance.entity.SpikeAnalysis;
import com.backyardbrains.data.persistance.entity.Train;
import com.backyardbrains.data.persistance.source.AnalysisLocalDataSource;
import com.backyardbrains.utils.AppExecutors;
import com.backyardbrains.utils.ThresholdOrientation;

/**
 * @author Tihomir Leka <tihomir at backyardbrains.com>
 */
public class AnalysisRepository {

    private static AnalysisRepository INSTANCE = null;

    private final AnalysisDataSource analysisDataSource;

    // Prevent direct instantiation.
    private AnalysisRepository(@NonNull SpikeRecorderDatabase db) {
        analysisDataSource =
            AnalysisLocalDataSource.get(db.spikeAnalysisDao(), db.spikeDao(), db.trainDao(), db.spikeTrainDao(),
                new AppExecutors());
    }

    /**
     * Returns the single instance of this class, creating it if necessary.
     *
     * @param db Application database
     * @return Singleton instance of the {@link AnalysisRepository}
     */
    public static AnalysisRepository get(@NonNull SpikeRecorderDatabase db) {
        if (INSTANCE == null) {
            synchronized (AnalysisRepository.class) {
                if (INSTANCE == null) INSTANCE = new AnalysisRepository(db);
            }
        }
        return INSTANCE;
    }

    /**
     * Used to force {@link #get(SpikeRecorderDatabase)}  to create a new instance next time it's called.
     */
    @SuppressWarnings("unused") public static void destroy() {
        INSTANCE = null;
    }

    //=================================================
    //  SPIKE ANALYSIS
    //=================================================

    /**
     * Checks whether spike analysis for the file with specified {@code filePath} exists. The result is passed to
     * specified {@code callback}.
     *
     * @param filePath Path to the file for which existence of the analysis is checked.
     * @param callback Callback that's invoked when check is preformed.
     */
    public void spikeAnalysisExists(@NonNull String filePath,
        @Nullable AnalysisDataSource.SpikeAnalysisCheckCallback callback) {
        analysisDataSource.spikeAnalysisExists(filePath, callback);
    }

    /**
     * Saves spike analysis for the file with specified {@code filePath} and all associated spikes and spike trains.
     *
     * @param filePath Path to the file for which analysis is being saved.
     * @param spikesAnalysis {@link Spike} objects that make the analysis.
     */
    public void saveSpikeAnalysis(@NonNull String filePath, @NonNull Spike[] spikesAnalysis) {
        analysisDataSource.saveSpikeAnalysis(filePath, spikesAnalysis);
    }

    /**
     * Returns id of the {@link SpikeAnalysis} for audio file located at specified {@code filePath}.
     *
     * @param filePath Absolute path of the audio file for which we want to retrieve the spike analysis id.
     */
    public long getSpikeAnalysisId(@NonNull String filePath) {
        return analysisDataSource.getSpikeAnalysisId(filePath);
    }

    /**
     * Returns collection of spikes for audio file located at specified {@code filePath} by invoking specified {@code
     * callback} and passing in the results.
     *
     * @param filePath Absolute path of the audio file for which we want to retrieve the spikes.
     * @param callback Callback that's invoked when spikes are retrieved from the database.
     */
    public void getSpikeAnalysisSpikes(@NonNull String filePath,
        @Nullable AnalysisDataSource.GetAnalysisCallback<Spike[]> callback) {
        analysisDataSource.getSpikeAnalysisSpikes(filePath, callback);
    }

    /**
     * Returns collection of {@link SpikeValueAndIndex} objects which contain values and indices for spikes belonging
     * to {@link SpikeAnalysis} with specified {@code analysisId} and are positioned between {@code startIndex} and
     * {@code endIndex}.
     *
     * @param analysisId Id of the spike analysis returned spike values and indexes belong to.
     * @param startIndex Start index from which values and indexes of spikes should be returned.
     * @param endIndex End index till which values and indexes of spikes should be returned.
     */
    @NonNull public SpikeValueAndIndex[] getSpikeAnalysisValuesAndIndicesForRange(long analysisId, int startIndex,
        int endIndex) {
        return analysisDataSource.getSpikeAnalysisValuesAndIndicesForRange(analysisId, startIndex, endIndex);
    }

    /**
     * Returns collection of {@link SpikeValueAndIndex} objects which contain values and indices for spikes belonging
     * to {@link Train} with specified {@code trainId} and are positioned between {@code startIndex} and
     * {@code endIndex}.
     *
     * @param trainId Id of the train returned spike values and indexes belong to.
     * @param startIndex Start index from which values and indexes of spikes should be returned.
     * @param endIndex End index till which values and indexes of spikes should be returned.
     */
    @NonNull public SpikeValueAndIndex[] getSpikesByTrainForRange(long trainId, int startIndex, int endIndex) {
        return analysisDataSource.getSpikeAnalysisValuesAndIndicesByTrainForRange(trainId, startIndex, endIndex);
    }

    /**
     * Returns collection of spike times collections sorted by the spike analysis train they belong to. Result is
     * returned by invoking specified {@code callback} and passing it in.
     *
     * @param filePath Absolute path of the audio file for which we want to retrieve the spike trains.
     * @param callback Callback that's invoked when spike train times are retrieved from the database.
     */
    public void getSpikeAnalysisTimesByTrains(@NonNull String filePath,
        @Nullable AnalysisDataSource.GetAnalysisCallback<float[][]> callback) {
        analysisDataSource.getSpikeAnalysisTimesByTrains(filePath, callback);
    }

    /**
     * Returns collection of spike indices collections sorted by the spike analysis train they belong to. Result is
     * returned by invoking specified {@code callback} and passing it in.
     *
     * @param filePath Absolute path of the audio file for which we want to retrieve the spike trains.
     * @param callback Callback that's invoked when spike trains indices are retrieved from the database.
     */
    public void getSpikeAnalysisIndicesByTrains(String filePath,
        AnalysisDataSource.GetAnalysisCallback<int[][]> callback) {
        analysisDataSource.getSpikeAnalysisIndicesByTrains(filePath, callback);
    }

    //=================================================
    //  SPIKE TRAINS
    //=================================================

    /**
     * Returns collection of threshold ranges for audio file with specified {@code filePath} that filter collection of
     * spikes for one of existing analysis by invoking specified {@code callback} and passing in the results.
     *
     * @param filePath Absolute path of the audio file for which trains should be retrieved.
     * @param callback Callback that's invoked when trains are retrieved from database.
     */
    public void getSpikeAnalysisTrains(@NonNull String filePath,
        @Nullable AnalysisDataSource.GetAnalysisCallback<Train[]> callback) {
        analysisDataSource.getSpikeAnalysisTrains(filePath, callback);
    }

    /**
     * Adds new spike train for the spike analysis of file at specified {@code filePath}.
     *
     * @param filePath Absolute path of the audio file for which we want to add new spike train.
     * @param callback Callback that's invoked when spike trains is added to database.
     */
    public void addSpikeAnalysisTrain(@NonNull String filePath,
        @Nullable AnalysisDataSource.AddSpikeAnalysisTrainCallback callback) {
        analysisDataSource.addSpikeAnalysisTrain(filePath, callback);
    }

    /**
     * Updates spike train's threshold defined by specified {@code orientation} for the file at specified {@code
     * filePath} with specified {@code value}.
     *
     * @param filePath Absolute path of the audio file for which we want to update spike train.
     * @param orientation {@link ThresholdOrientation} of the threshold that was updated.
     * @param value New threshold value.
     * @param order Order of the spike train that needs to be updated.
     */
    public void saveSpikeAnalysisTrain(@NonNull String filePath, @ThresholdOrientation int orientation, int value,
        int order) {
        analysisDataSource.saveSpikeAnalysisTrain(filePath, orientation, value, order);
    }

    /**
     * Removes spike train at specified {@code order} for the file at specified {@code filePath}. New number of spike
     * trains is returned to caller by invoking specified {@code callback} and passing it as parameter.
     *
     * @param filePath Absolute path of the audio file for which we want to remove spike train.
     * @param trainOrder Order of the spike train that needs to be removed.
     * @param callback Callback that's invoked when spike trains is removed from database.
     */
    public void removeSpikeAnalysisTrain(@NonNull String filePath, int trainOrder,
        @Nullable AnalysisDataSource.RemoveSpikeAnalysisTrainCallback callback) {
        analysisDataSource.removeSpikeAnalysisTrain(filePath, trainOrder, callback);
    }
}
