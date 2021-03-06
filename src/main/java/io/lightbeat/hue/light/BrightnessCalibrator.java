package io.lightbeat.hue.light;

import io.lightbeat.config.Config;
import io.lightbeat.config.ConfigNode;
import io.lightbeat.util.TimeThreshold;
import io.lightbeat.util.DoubleAverageBuffer;

/**
 * Dynamically calibrates the brightness level after receiving amplitudes, based on the highest
 * amplitude received. Calling {@link #getBrightness(double)} returns a {@link BrightnessData}
 * object, which contains the relevant information for the next light update, and if a brightness
 * change is needed in the first place.
 */
public class BrightnessCalibrator {

    private final static double HISTORY_STARTING_VALUE = 0.2d;
    private final static long BRIGHTNESS_REDUCTION_MIN_DELAY_MILLIS = 5000L;

    private final int minBrightness;
    private final int maxBrightness;
    private final int medianBrightness;
    private final int transitionTime;
    private final int brightnessChangeThreshold;
    private final double brightnessMultiplier;

    private int lastBrightness = 0;

    private final TimeThreshold brightnessReductionThreshold = new TimeThreshold(0L);
    private final DoubleAverageBuffer amplitudeDifferenceHistory = new DoubleAverageBuffer(75);


    BrightnessCalibrator(Config config) {
        minBrightness = config.getInt(ConfigNode.BRIGHTNESS_MIN, 0);
        maxBrightness = config.getInt(ConfigNode.BRIGHTNESS_MAX, 254);
        medianBrightness = (maxBrightness + minBrightness) / 2;

        transitionTime = config.getInt(ConfigNode.LIGHTS_TRANSITION_TIME, 0);

        brightnessChangeThreshold = (maxBrightness - minBrightness) / 6;
        brightnessMultiplier = 1.0d + (config.getInt(ConfigNode.BRIGHTNESS_SENSITIVITY, 20)  / 100.0d);

        amplitudeDifferenceHistory.add(HISTORY_STARTING_VALUE);
    }

    /**
     * Get the brightness for the given amplitude difference from the average amplitude.
     *
     * @param amplitudeDifference difference from average amplitude (between 0 and 1)
     * @return BrightnessData object containing all information about the brightness
     */
    BrightnessData getBrightness(double amplitudeDifference) {

        amplitudeDifferenceHistory.add(amplitudeDifference);

        // multiplier is calibrated in regards to the currently highest amplitude, which will set brightness to max if received
        int amplitudeMultiplier = (int) ((medianBrightness / amplitudeDifferenceHistory.getMaxValue()) * brightnessMultiplier);
        int brightness = (int) (medianBrightness + (amplitudeDifference * amplitudeMultiplier));
        brightness = Math.max(Math.min(brightness, maxBrightness), minBrightness);

        int brightnessDifference = brightness - lastBrightness;
        boolean doBrightnessChange = Math.abs(brightnessDifference) > brightnessChangeThreshold;

        if (doBrightnessChange) {
            // brightnessReductionThreshold reduces unnecessary fluctuations and thus reduces latency
            if (brightness < lastBrightness && !brightnessReductionThreshold.isMet()) {
                doBrightnessChange = false;
                brightness = lastBrightness;
                brightnessDifference = 0;
            } else {
                setLastBrightness(brightness);
            }
        } else {
            brightness = lastBrightness;
            brightnessDifference = 0;
        }

        double brightnessPercentage = ((float) brightness - minBrightness) / (maxBrightness - minBrightness);
        return new BrightnessData(brightness, brightnessDifference, brightnessPercentage, doBrightnessChange);
    }

    BrightnessData getLowestBrightnessData() {
        setLastBrightness(minBrightness);
        return new BrightnessData(minBrightness, minBrightness - lastBrightness, 0.0f, true);
    }

    void clearHistory() {
        amplitudeDifferenceHistory.clear();
        amplitudeDifferenceHistory.add(HISTORY_STARTING_VALUE);
    }

    private void setLastBrightness(int brightness) {
        brightnessReductionThreshold.setCurrentThreshold(BRIGHTNESS_REDUCTION_MIN_DELAY_MILLIS);
        lastBrightness = brightness;
    }


    public class BrightnessData {

        private final int brightness;
        private final int brightnessDifference;
        private final double brightnessPercentage;
        private final boolean doBrightnessChange;


        private BrightnessData(int brightness, int brightnessDifference,
                               double brightnessPercentage, boolean doBrightnessChange) {
            this.brightness = brightness;
            this.brightnessDifference = brightnessDifference;
            this.brightnessPercentage = brightnessPercentage;
            this.doBrightnessChange = doBrightnessChange;
        }

        public int getBrightness() {
            return brightness;
        }

        public int getBrightnessDifference() {
            return brightnessDifference;
        }

        public double getBrightnessPercentage() {
            return brightnessPercentage;
        }

        public boolean isBrightnessChange() {
            return doBrightnessChange;
        }

        public int getTransitionTime() {
            return transitionTime;
        }
    }
}
