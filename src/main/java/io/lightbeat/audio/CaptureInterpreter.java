package io.lightbeat.audio;

import com.sun.istack.internal.Nullable;
import io.lightbeat.config.ConfigNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.lightbeat.config.Config;
import io.lightbeat.util.DoubleAverageBuffer;
import io.lightbeat.util.TimeThreshold;

/**
 * Interprets amplitudes of audio data and returns {@link BeatEvent}'s if the current beat is
 * an amplitude, no beat was read for {@link #NO_BEAT_RECEIVED_MILLIS} or no audible audio
 * data was detected for {@link #SILENCE_MILLIS}. There is a minimum time between beats
 * defined by {@link #timeBetweenBeatsMillis}.
 */
class CaptureInterpreter {

    private static final Logger logger = LoggerFactory.getLogger(CaptureInterpreter.class);

    private static final long NO_BEAT_RECEIVED_MILLIS = 2000L;
    private static final long SILENCE_MILLIS = 1000L;

    private final double beatThresholdReductionMultiplier;
    private final long timeBetweenBeatsMillis;

    // calculate amplitude averages
    private final DoubleAverageBuffer amplitudeHistory = new DoubleAverageBuffer(800, false);
    private double beatThreshold;
    private boolean isSilent = true;

    private final TimeThreshold nextBeatThreshold = new TimeThreshold(1000L); // adds calibration phase
    private final TimeThreshold noBeatThreshold = new TimeThreshold();
    private final TimeThreshold silenceThreshold = new TimeThreshold();


    CaptureInterpreter(Config config) {
        this.beatThresholdReductionMultiplier = config.getInt(ConfigNode.BEAT_SENSITIVITY, 5) / 1000.0d;
        this.timeBetweenBeatsMillis = config.getInt(ConfigNode.BEAT_MIN_TIME_BETWEEN, 350);
    }

    @Nullable
    BeatEvent interpretValue(double amplitude) {

        amplitudeHistory.add(amplitude);
        double average = amplitudeHistory.getCurrentAverage();

        if (amplitude > beatThreshold && amplitude > 0d) {

            // beat was received
            noBeatThreshold.setCurrentThreshold(NO_BEAT_RECEIVED_MILLIS);
            silenceThreshold.disable();

            beatThreshold = amplitude;

            if (nextBeatThreshold.isMet()) {
                nextBeatThreshold.setCurrentThreshold(timeBetweenBeatsMillis);
                logger.info("Beat detected at {} (avg: {})", amplitude, average);
                return new BeatEvent(amplitude, average);
            }

        } else {

            // reduce beat threshold
            if (average < beatThreshold) {
                double difference = beatThreshold - average;
                beatThreshold -= difference * beatThresholdReductionMultiplier;
            }

            // check for silence and no beat
            if (amplitude == 0d) {

                if (silenceThreshold.isEnabled()) {

                    if (silenceThreshold.isMet()) {
                        silenceThreshold.disable();
                        isSilent = true;
                        logger.info("Silence detected");
                        return new BeatEvent();
                    }
                } else if (!isSilent) {
                    silenceThreshold.setCurrentThreshold(SILENCE_MILLIS);
                }
            } else {
                silenceThreshold.disable();
                if (isSilent) {
                    isSilent = false;
                }

                // check if no beat was received for a while
                if (noBeatThreshold.isMet()) {
                    noBeatThreshold.disable();
                    logger.info("No beat detected");
                    return new BeatEvent(average);
                }
            }
        }

        return null;
    }
}
