/*
 * ******************************************************************************
 * Copyright (C) 2015-2019 Dennis Sheirer
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * *****************************************************************************
 */

package jmbe.codec;

import org.jtransforms.fft.FloatFFT_1D;

import java.util.Arrays;

/**
 * Base Multi-Band Excitation (MBE) synthesizer
 */
public abstract class MBESynthesizer
{
    private static final float TWO_PI = (float)Math.PI * 2.0f;
    private static final float TWO56_OVER_TWO_PI = 256.0f / TWO_PI;
    private static final float AUDIO_SCALAR_16_BITS_SIGNED = 1.00f / Short.MAX_VALUE;
    // Final output trim to keep synthesized audio below full scale before clipping. The AMBE synthesis chain routinely
    // produces near-int16-range raw samples, so a small uniform trim reduces frequent edge clipping without altering
    // the gain tables or spectral amplitude math.
    private static final float OUTPUT_GAIN = 0.85f;
    private static final float MAXIMUM_AUDIO_AMPLITUDE = 0.95f;
    protected static final int SAMPLES_PER_FRAME = 160;
    private static final int MAX_HARMONIC = 56;
    private static final float WHITE_NOISE_SCALAR = TWO_PI / 53125.0f;
    private static final int PREVIOUS_VOICED = 0x1;
    private static final int CURRENT_VOICED = 0x2;
    private static final int BOTH_VOICED = PREVIOUS_VOICED | CURRENT_VOICED;

    // Algorithm 121 - unvoiced scaling coefficient (yw) from synthesis window (ws) and pitch refinement window (wr)
    private static final float UNVOICED_SCALING_COEFFICIENT = 146.17696f;
    private static final float[] SYNTHESIS_WINDOW = new float[]{
        0.00f, 0.02f, 0.04f, 0.06f, 0.08f, 0.10f, 0.12f, 0.14f, 0.16f, 0.18f,
        0.20f, 0.22f, 0.24f, 0.26f, 0.28f, 0.30f, 0.32f, 0.34f, 0.36f, 0.38f,
        0.40f, 0.42f, 0.44f, 0.46f, 0.48f, 0.50f, 0.52f, 0.54f, 0.56f, 0.58f,
        0.60f, 0.62f, 0.64f, 0.66f, 0.68f, 0.70f, 0.72f, 0.74f, 0.76f, 0.78f,
        0.80f, 0.82f, 0.84f, 0.86f, 0.88f, 0.90f, 0.92f, 0.94f, 0.96f, 0.98f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f,
        0.98f, 0.96f, 0.94f, 0.92f, 0.90f, 0.88f, 0.86f, 0.84f, 0.82f, 0.80f,
        0.78f, 0.76f, 0.74f, 0.72f, 0.70f, 0.68f, 0.66f, 0.64f, 0.62f, 0.60f,
        0.58f, 0.56f, 0.54f, 0.52f, 0.50f, 0.48f, 0.46f, 0.44f, 0.42f, 0.40f,
        0.38f, 0.36f, 0.34f, 0.32f, 0.30f, 0.30f, 0.28f, 0.26f, 0.24f, 0.22f,
        0.20f, 0.18f, 0.16f, 0.14f, 0.12f, 0.10f, 0.08f, 0.06f, 0.04f, 0.02f, 0.0f};
    // Derived from the fixed synthesis window table above for zero-based audio sample indexes in one frame
    // (n = 0..SAMPLES_PER_FRAME - 1). This is not harmonic/band indexing. If that table changes, these values must be
    // recalculated to keep the weighted overlap-add denominator correct.
    private static final float[] UNVOICED_OVERLAP_DENOMINATORS = new float[]{
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 0.96040000f,
        0.92200000f, 0.88520000f, 0.85000000f, 0.81640000f, 0.78440000f, 0.75400000f, 0.72520000f, 0.69800000f,
        0.67240000f, 0.64840000f, 0.62600000f, 0.60520000f, 0.58600000f, 0.56840000f, 0.55240000f, 0.53800000f,
        0.52520000f, 0.51400000f, 0.50440000f, 0.49640000f, 0.49000000f, 0.48520000f, 0.48200000f, 0.48040000f,
        0.48040000f, 0.48200000f, 0.48520000f, 0.49000000f, 0.49640000f, 0.50440000f, 0.51400000f, 0.52520000f,
        0.53800000f, 0.55240000f, 0.58000000f, 0.59680000f, 0.61520000f, 0.63520000f, 0.65680000f, 0.68000000f,
        0.70480000f, 0.73120000f, 0.75920000f, 0.78880000f, 0.82000000f, 0.85280000f, 0.88720000f, 0.92320000f,
        0.96080000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
        1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f, 1.00000000f,
    };

    private final WhiteNoiseGenerator mWhiteNoiseGenerator = new WhiteNoiseGenerator();
    private final MBENoiseSequenceGenerator mMBENoiseSequenceGenerator = new MBENoiseSequenceGenerator();
    private final FloatFFT_1D mFFT = new FloatFFT_1D(256);
    private final float[] mNoiseSamples = new float[256];
    private final float[] mDftBinScalor = new float[128];
    private float[] mPreviousPhaseO = new float[57];
    private float[] mPreviousPhaseV = new float[57];
    private float[] mCurrentPhaseO = new float[57];
    private float[] mCurrentPhaseV = new float[57];
    private float[] mPreviousUw = new float[256];

    protected MBESynthesizer()
    {
    }

    /**
     * Resets all phase-history / WOLA state to match mbelib's cold-init
     * behavior invoked from {@code mbe_initMbeParms} (mbelib.c:95+):
     * {@code prev_mp->PSIl[l] = M_PI/2; prev_mp->PHIl[l] = 0;}.
     *
     * <p>JMBE stores the same logical state as {@code mPreviousPhaseV}
     * (PSIl analogue) and {@code mPreviousPhaseO} (PHIl analogue). Without
     * reinitializing these arrays, calls to {@code reset()} leave stale
     * phase rolls from prior segments leaking into the next segment's
     * first voiced frame, producing phase discontinuities at squelch
     * boundaries (especially relevant for short EDACS ProVoice segments).</p>
     */
    protected void resetPhaseState()
    {
        float halfPi = (float)Math.PI / 2.0f;
        for(int l = 0; l < mPreviousPhaseV.length; l++)
        {
            mPreviousPhaseV[l] = halfPi;
        }
        Arrays.fill(mPreviousPhaseO, 0.0f);
        Arrays.fill(mCurrentPhaseV, 0.0f);
        Arrays.fill(mCurrentPhaseO, 0.0f);
        Arrays.fill(mPreviousUw, 0.0f);
    }

    /**
     * Access previous frame's MBE model parameters
     */
    protected abstract MBEModelParameters getPreviousFrame();

    /**
     * Returns the speech synthesis window coefficient from appendix I
     */
    private static float synthesisWindow(int n)
    {
        if(n < -105 || n > 105)
        {
            return 0.0f;
        }

        return SYNTHESIS_WINDOW[n + 105];
    }

    /**
     * Generates 160 samples (20 ms) of voice audio using the model parameters
     *
     * @param parameters to use in generating the voice frame
     * @return samples scaled to -1.0 <> 1.0
     */
    protected float[] getVoice(MBEModelParameters parameters)
    {
        //Alg #117 - generate white noise samples.
        float[] u = mMBENoiseSequenceGenerator.nextBuffer(mNoiseSamples);

        float[] voiced = getVoiced(parameters, u);

        for(int x = 0; x < u.length; x++)
        {
            u[x] *= synthesisWindow(x - 128);
        }

        float[] unvoiced = getUnvoicedFromWindowed(parameters, u);

        //Alg #142 - combine voiced and unvoiced audio samples to form the completed audio samples.
        for(int x = 0; x < SAMPLES_PER_FRAME; x++)
        {
            voiced[x] = clip((voiced[x] + unvoiced[x]) * AUDIO_SCALAR_16_BITS_SIGNED * OUTPUT_GAIN);
        }

        return voiced;
    }

    /**
     * Clips the audio to within -MAX <-> MAX amplitude
     * @param value to clip
     * @return clipped value
     */
    private static float clip(float value)
    {
        if(value > MAXIMUM_AUDIO_AMPLITUDE)
        {
            return MAXIMUM_AUDIO_AMPLITUDE;
        }
        else if(value < -MAXIMUM_AUDIO_AMPLITUDE)
        {
            return -MAXIMUM_AUDIO_AMPLITUDE;
        }

        return value;
    }

    private static float normalizePhase(float phase)
    {
        phase %= TWO_PI;

        if(phase > Math.PI)
        {
            phase -= TWO_PI;
        }
        else if(phase < -Math.PI)
        {
            phase += TWO_PI;
        }

        return phase;
    }

    /**
     * Generates 160 samples (20 ms) of white noise
     *
     * @return samples
     */
    protected float[] getWhiteNoise()
    {
        return mWhiteNoiseGenerator.getSamples(SAMPLES_PER_FRAME, 0.003f);
    }

    private float[] getUnvoicedFromWindowed(MBEModelParameters parameters, float[] Uw)
    {
        //Alg #122 and #123 - generate the 256 FFT bins to L frequency band mapping from the fundamental frequency
        boolean[] voicedBands = parameters.getVoicingDecisions();
        float[] M = parameters.getEnhancedSpectralAmplitudes();

        //Alg 118 - perform 256-point DFT against samples.  We use the JTransforms library to calculate an FFT against
        // the 256 element sample array that contains zeros for all elements greater than 209
        mFFT.realForward(Uw);
        //NOTE: from this point forward, Uw contains the DFT frequency bins (uw)

        float[] dftBinScalor = getUnvoicedBandScalars(parameters, voicedBands, M, Uw);

        // Alg 119, 120 & 124 - scale the DFT bins in the a-b min/max bin ranges.  Since the binScalor array is
        // initialized to zero, this also zeroizes any of lowest and highest frequency DFT bins per Alg 124 that weren't
        // explicitly listed in the a-b DFT bin ranges for each L frequency band.
        for(int bin = 0; bin < 128; bin++)
        {
            int dftBinIndex = 2 * bin;

            Uw[dftBinIndex] *= dftBinScalor[bin];
            Uw[dftBinIndex + 1] *= dftBinScalor[bin];
        }

        //Alg #125 - calculate inverse DFT of scaled dft bins to recreate the white noise, notched for voiced bands
        mFFT.realInverse(Uw, true);
        float[] unvoiced = combineUnvoicedSamples(Uw);

        mPreviousUw = Uw;

        return unvoiced;
    }

    private float[] getUnvoicedBandScalars(MBEModelParameters parameters, boolean[] voicedBands, float[] amplitudes,
        float[] dftBins)
    {
        //Alg 120 - determine band-level scaling value for each DFT bin for unvoiced samples and zeroize all voiced and
        // out-of-band bins.  The denominator in this algorithm is the average bin energy per band calculated by summing
        // the squared dft real and the squared dft imaginary values, dividing by the number of bins in the band to get
        // the average, and then taking the square root to get the amplitude average (a^2 + b^2 = c^2).  Calculate this
        // value for each of the unvoiced bands and apply the unvoiced scaling coefficient and the decoded amplitude for
        // the band.
        Arrays.fill(mDftBinScalor, 0.0f);
        float multiplier = TWO56_OVER_TWO_PI * parameters.getFundamentalFrequency();

        for(int l = 1; l <= parameters.getL(); l++)
        {
            if(!voicedBands[l])
            {
                int minimum = (int)Math.ceil((l - 0.5f) * multiplier);
                int maximum = (int)Math.ceil((l + 0.5f) * multiplier);
                float scalor = getUnvoicedBandScalar(amplitudes[l], minimum, maximum, dftBins);

                for(int n = minimum; n < maximum; n++)
                {
                    if(n < 128)
                    {
                        mDftBinScalor[n] = scalor;
                    }
                }
            }
        }

        return mDftBinScalor;
    }

    private float getUnvoicedBandScalar(float amplitude, int minimum, int maximum, float[] dftBins)
    {
        if(amplitude <= 0.0f || minimum >= 128)
        {
            return 0.0f;
        }

        int upperBound = Math.min(maximum, 128);
        float numerator = 0.0f;

        for(int n = minimum; n < upperBound; n++)
        {
            int dftBinIndex = 2 * n;

            // Real component
            numerator += (dftBins[dftBinIndex] * dftBins[dftBinIndex]);

            dftBinIndex++;

            // Imaginary component
            numerator += (dftBins[dftBinIndex] * dftBins[dftBinIndex]);
        }

        if(numerator <= 0.0f)
        {
            return 0.0f;
        }

        return UNVOICED_SCALING_COEFFICIENT * amplitude /
            (float)Math.sqrt(numerator / (maximum - minimum));
    }

    private float[] combineUnvoicedSamples(float[] uw)
    {
        //Note: from this point forward, uw contains the inverse DFT results

        /* Algorithm #126 - use Weighted Overlap Add algorithm to combine previous
         * Uw and the current Uw inverse DFT results to form final unvoiced set */
        float[] unvoiced = new float[SAMPLES_PER_FRAME];

        for(int n = 0; n < SAMPLES_PER_FRAME; n++)
        {
            float previousWindow = synthesisWindow(n);
            float currentWindow = synthesisWindow(n - SAMPLES_PER_FRAME);

            //Uw samples index is in range 0<>255 and must be translated to -128 <> 127 for this algorithm, recognizing
            //that previousUw needs samples for indexes 0<>159 and currentUw needs samples -160<>-1
            float previousUw = (n < 128 ? mPreviousUw[n + 128] : 0.0f); //n
            float currentUw = (n >= 32 ? uw[n - 32] : 0.0f);  //n - N

            unvoiced[n] = ((previousWindow * previousUw) + (currentWindow * currentUw)) /
                UNVOICED_OVERLAP_DENOMINATORS[n];
        }

        return unvoiced;
    }

    /**
     * Reconstructs the voiced audio components using the model parameters from both the current and previous imbe frames.
     *
     * @param currentFrame - voice parameters
     * @param u = white noise samples from algorithm #117
     * @return - 160 samples of voiced audio component
     */
    private float[] getVoiced(MBEModelParameters currentFrame, float[] u)
    {
        MBEModelParameters previousFrame = getPreviousFrame();
        float currentFrequency = currentFrame.getFundamentalFrequency();
        float previousFrequency = previousFrame.getFundamentalFrequency();
        float averageFrequency = (previousFrequency + currentFrequency) / 2.0f;
        float phaseOffsetPerFrame = averageFrequency * SAMPLES_PER_FRAME;

        //Alg #139 - calculate current phase angle for each harmonic
        float[] currentPhaseV = mCurrentPhaseV;

        //Update each of the phase values
        for(int l = 1; l <= MAX_HARMONIC; l++)
        {
            //Unwrap the previous phase before updating to avoid overflow
            mPreviousPhaseV[l] = normalizePhase(mPreviousPhaseV[l]);

            //Alg #139 - calculate current phase v values
            currentPhaseV[l] = normalizePhase(mPreviousPhaseV[l] + (phaseOffsetPerFrame * l));
        }

        //Short circuit if there are no voiced bands and return an array of zeros
        if(!previousFrame.hasVoicedBands() && !currentFrame.hasVoicedBands())
        {
            mCurrentPhaseV = mPreviousPhaseV;
            mPreviousPhaseV = currentPhaseV;
            return new float[SAMPLES_PER_FRAME];
        }

        int currentL = currentFrame.getL();
        int previousL = previousFrame.getL();
        int maxL = Math.max(currentL, previousL);

        boolean[] currentVoicing = currentFrame.getVoicingDecisions();
        boolean[] previousVoicing = previousFrame.getVoicingDecisions();

        //Alg #128 & #129 - enhanced spectral amplitudes for current and previous frames outside range of 1 - L are set
        // to zero.  Below, in the audio generation loop, we control access to these arrays through the voicing
        // decisions array.  Thus, we don't have to resize the enhanced spectral amplitudes arrays to the max L of
        // current or previous.

        //Alg #140 partial - number of unvoiced spectral amplitudes (Luv) in current frame */
        int unvoicedBandCount = currentFrame.getUnvoicedBandCount();

        //Alg #139 - calculate current phase angle for each harmonic
        float[] currentPhaseO = mCurrentPhaseO;
        int threshold = (int)Math.floor(currentL / 4.0f);

        //Update each of the phase values
        for(int l = 1; l <= MAX_HARMONIC; l++)
        {
            //Alg #140 - calculate current phase o values
            if(l <= threshold)
            {
                currentPhaseO[l] = currentPhaseV[l];
            }
            else if(l <= maxL)
            {
                float pl = WHITE_NOISE_SCALAR * u[l] - (float)Math.PI;
                currentPhaseO[l] = normalizePhase(currentPhaseV[l] + ((unvoicedBandCount * pl) / currentL));
            }
        }

        float[] currentM = currentFrame.getEnhancedSpectralAmplitudes();
        float[] previousM = previousFrame.getEnhancedSpectralAmplitudes();
        float[] voiced = new float[SAMPLES_PER_FRAME];

        //Alg #127 - reconstruct 160 voice samples using each of the l harmonics that are common between this frame and
        // the previous frame, using one of four algorithms selected by the combination of the voicing decisions of the
        // current and previous frames for each harmonic.
        boolean exceedsThreshold = Math.abs(currentFrequency - previousFrequency) >= (0.1 * currentFrequency);

        for(int l = 1; l <= maxL; l++)
        {
            switch((l <= currentL  && currentVoicing[l]  ? CURRENT_VOICED  : 0) |
                   (l <= previousL && previousVoicing[l] ? PREVIOUS_VOICED : 0))
            {
                case BOTH_VOICED:
                    if(l >= 8 || exceedsThreshold)
                    {
                        addWindowedOscillator(voiced, previousM[l], normalizePhase(mPreviousPhaseO[l]),
                            previousFrequency * l, 0);
                        addWindowedOscillator(voiced, currentM[l],
                            normalizePhase(currentPhaseO[l] - (currentFrequency * SAMPLES_PER_FRAME * l)),
                            currentFrequency * l, -SAMPLES_PER_FRAME);
                    }
                    else
                    {
                        float amplitude = previousM[l];
                        float amplitudeStep = (currentM[l] - previousM[l]) / SAMPLES_PER_FRAME;
                        float ol = currentPhaseO[l] - mPreviousPhaseO[l] - (phaseOffsetPerFrame * l);
                        float wl = (ol - (TWO_PI * (float)Math.floor((ol + (float)Math.PI) / TWO_PI))) /
                            SAMPLES_PER_FRAME;

                        double cosPhase = Math.cos(mPreviousPhaseO[l]);
                        double sinPhase = Math.sin(mPreviousPhaseO[l]);
                        double phaseStep = (previousFrequency * l) + wl +
                            ((currentFrequency - previousFrequency) * l / (2.0 * SAMPLES_PER_FRAME));
                        double phaseStepIncrement = (currentFrequency - previousFrequency) * l / SAMPLES_PER_FRAME;
                        double cosStep = Math.cos(phaseStep);
                        double sinStep = Math.sin(phaseStep);
                        double cosStepIncrement = Math.cos(phaseStepIncrement);
                        double sinStepIncrement = Math.sin(phaseStepIncrement);
                        for(int n = 0; n < SAMPLES_PER_FRAME; n++)
                        {
                            voiced[n] += 2.0f * amplitude * (float)cosPhase;
                            amplitude += amplitudeStep;

                            double nextCosPhase = (cosPhase * cosStep) - (sinPhase * sinStep);
                            sinPhase = (sinPhase * cosStep) + (cosPhase * sinStep);
                            cosPhase = nextCosPhase;

                            double nextCosStep = (cosStep * cosStepIncrement) - (sinStep * sinStepIncrement);
                            sinStep = (sinStep * cosStepIncrement) + (cosStep * sinStepIncrement);
                            cosStep = nextCosStep;
                        }

                    }
                    break;
                case PREVIOUS_VOICED:
                    //Alg #131
                    addWindowedOscillator(voiced, previousM[l], normalizePhase(mPreviousPhaseO[l]),
                        previousFrequency * l, 0);
                    break;
                case CURRENT_VOICED:
                    //Alg #132
                    addWindowedOscillator(voiced, currentM[l],
                        normalizePhase(currentPhaseO[l] - (currentFrequency * SAMPLES_PER_FRAME * l)),
                        currentFrequency * l, -SAMPLES_PER_FRAME);
                    break;
                default:
                    //Alg #130 - both harmonics unvoiced, so no voiced contribution is added.
                    break;
            }
        }

        mCurrentPhaseV = mPreviousPhaseV;
        mPreviousPhaseV = currentPhaseV;
        mCurrentPhaseO = mPreviousPhaseO;
        mPreviousPhaseO = currentPhaseO;

        return voiced;
    }

    private static void addWindowedOscillator(float[] voiced, float amplitude, float initialPhase, float phaseStep,
        int windowOffset)
    {
        double cosPhase = Math.cos(initialPhase);
        double sinPhase = Math.sin(initialPhase);
        double cosStep = Math.cos(phaseStep);
        double sinStep = Math.sin(phaseStep);
        float scaledAmplitude = 2.0f * amplitude;

        for(int n = 0; n < SAMPLES_PER_FRAME; n++)
        {
            voiced[n] += scaledAmplitude * synthesisWindow(n + windowOffset) * (float)cosPhase;

            double nextCosPhase = (cosPhase * cosStep) - (sinPhase * sinStep);
            sinPhase = (sinPhase * cosStep) + (cosPhase * sinStep);
            cosPhase = nextCosPhase;
        }
    }
}
