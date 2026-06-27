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

package jmbe.codec.imbe;

import jmbe.binary.BinaryFrame;
import jmbe.edac.Golay23;
import jmbe.edac.Hamming15;

import java.nio.ByteOrder;
import java.util.Arrays;


class IMBEFrame
{
    public static final float LOG_2 = (float)Math.log(2.0);

    private static final int[] RANDOMIZER_SEED = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] VECTOR_B0 = {0, 1, 2, 3, 4, 5, 141, 142};

    /**
     * P25/IMBE frame bit positions for each harmonic's voiced/unvoiced decision (index 1..56).
     * Each harmonic maps directly to the frame bit that carries its K-band voicing flag.
     * Index 0 is unused; harmonics 1..L are read here by the standard P25 decode path.
     */
    private static final int[] VOICE_DECISION_INDEX = new int[]{0, 92, 92, 92, 93, 93, 93, 94, 94, 94, 95, 95, 95, 96,
        96, 96, 97, 97, 97, 98, 98, 98, 99, 99, 99, 100, 100, 100, 101, 101, 101, 102, 102, 102, 107, 107, 107, 107,
        107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107, 107};

    /**
     * ProVoice/mbelib K-band frame bit positions in ascending K order (K=0..11).
     * Used only when mMbelibErrorMode=true; ProVoice voicing is read from the
     * linear imbe4400 data at positions 48..59 in ascending group order, not
     * the reversed P25 ordering.
     */
    private static final int[] VOICE_DECISION_INDEX_PV = new int[]{92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 107};

    /**
     * Coefficient offsets for bit lengths 0 - 10:   (2 ^ (bit length -1)) - 0.5
     */
    private static final float[] COEFFICIENT_OFFSET = new float[] {0.0f, 0.5f, 1.5f, 3.5f, 7.5f, 15.5f, 31.5f, 63.5f,
        127.5f, 255.5f, 511.5f};

    private BinaryFrame mFrame;
    private IMBEFundamentalFrequency mFundamentalFrequency;
    private int[] mErrors = new int[7];
    private int mErrorCountTotal;
    private boolean mRepeatOverride;
    private boolean mMbelibErrorMode;
    private boolean[] mLinearImbe4400Data;

    /**
     * Constructs an IMBE frame from a binary message containing an 18-byte or
     * 144-bit message frame, and a previous IMBE frame.  Performs error detection
     * and correction.
     *
     * After construction, use the setPrevious() method to the previous imbe
     * frame so that model parameters can be generated.
     *
     * Use the getModelParameters() method to access the parameters for speech
     * synthesis.
     *
     * Use the .getDefault() method to generate the first (default) IMBE frame
     * to use at the start of a sequence.
     */
    IMBEFrame(byte[] data)
    {
        mFrame = BinaryFrame.fromBytes(data, ByteOrder.LITTLE_ENDIAN);
        decode();
    }

private IMBEFrame(boolean[] imbe4400Data)
    {
        if(imbe4400Data == null || imbe4400Data.length != 88)
        {
            throw new IllegalArgumentException("IMBE 4400 data must contain 88 bits");
        }

        mFrame = new BinaryFrame(144);
        loadImbe4400Data(imbe4400Data);
        mFundamentalFrequency = IMBEFundamentalFrequency.fromValue(mFrame.getInt(VECTOR_B0));
    }

    /**
     * Variant for callers that perform their own ECC (e.g. ProVoice adds the
     * 7x24 IMBE7100 grid + Golay + Hamming + demodulate + 7100->4400
     * conversion outside this class).  Pass an int[7] of per-coset error
     * counts so that downstream synthesizer repeat/mute/adaptive smoothing
     * rules fire correctly, matching the mbelib flow where
     * mbe_eccImbe7100x4400Data -> errs is propagated to
     * mbe_processImbe4400Dataf for the (bad == 1 || errs2 > 5) repeat check.
     *
     * The int[7] layout mirrors the {@code decode()} assignment order:
     * index 0 = coset 0 (Golay), 1..3 = cosets 1..3 (Golay),
     * 4..6 = cosets 4..6 (Hamming).  Index 7 (coset 7) is unused by ECC.
     */
    private IMBEFrame(boolean[] imbe4400Data, int[] errors)
    {
        if(imbe4400Data == null || imbe4400Data.length != 88)
        {
            throw new IllegalArgumentException("IMBE 4400 data must contain 88 bits");
        }

        mFrame = new BinaryFrame(144);
        loadImbe4400Data(imbe4400Data);
        mFundamentalFrequency = IMBEFundamentalFrequency.fromValue(mFrame.getInt(VECTOR_B0));

        // Plumb the caller-supplied per-coset error counts so the synthesizer's
        // repeat/mute/adaptive-smoothing rules fire for ProVoice frames too,
        // matching mbe_processImbe4400Dataf's errs2 > 5 / repeat > 3 logic.
        if(errors != null)
        {
            mMbelibErrorMode = true;
            mLinearImbe4400Data = imbe4400Data.clone();
            for(int x = 0; x < Math.min(7, errors.length); x++)
            {
                mErrors[x] = Math.max(0, errors[x]);
                mErrorCountTotal += mErrors[x];
            }

            // ProVoice/mbelib repeats when cumulative IMBE7100 ECC errors exceed
            // five, independent of the P25-specific coset0/error-rate threshold.
            mRepeatOverride = mErrorCountTotal > 5;
        }
    }

    static IMBEFrame fromImbe4400Data(boolean[] imbe4400Data)
    {
        return new IMBEFrame(imbe4400Data);
    }

    /**
     * Variant that accepts caller-supplied per-coset error counts, allowing
     * ProVoice's IMBE7100 ECC (Golay23 + 7100-Hamming15) to drive the same
     * repeat/mute/adaptive smoothing rules as the P25 path.
     *
     * @param imbe4400Data 88-bit converted parameter vector (post
     *                     imbe7100x4400 demodulate/extract/convert)
     * @param errors int[] of per-coset error counts:
     *              [0]=coset0, [1]=coset1, [2]=coset2, [3]=coset3,
     *              [4]=coset4, [5]=coset5, [6]=coset6.
     *              May be null or shorter than 7 in which case zero is used
     *              for the missing cosets (matches the pre-fix behavior).
     */
    static IMBEFrame fromImbe4400Data(boolean[] imbe4400Data, int[] errors)
    {
        return new IMBEFrame(imbe4400Data, errors);
    }

    private void loadImbe4400Data(boolean[] data)
    {
        load(data, 0, 0, 12);
        load(data, 12, 23, 12);
        load(data, 24, 46, 12);
        load(data, 36, 69, 12);
        load(data, 48, 92, 11);
        load(data, 59, 107, 11);
        load(data, 70, 122, 11);
        load(data, 81, 137, 7);
    }

    private void load(boolean[] data, int sourceOffset, int targetOffset, int length)
    {
        for(int x = 0; x < length; x++)
        {
            mFrame.set(targetOffset + x, data[sourceOffset + x]);
        }
    }

    private void decode()
    {
        IMBEInterleave.deinterleave(mFrame);

        mErrors[0] = Golay23.checkAndCorrect(mFrame, 0);
        mErrorCountTotal += mErrors[0];

        derandomize();

        mErrors[1] = Golay23.checkAndCorrect(mFrame, 23);
        mErrorCountTotal += mErrors[1];

        mErrors[2] = Golay23.checkAndCorrect(mFrame, 46);
        mErrorCountTotal += mErrors[2];
        mErrors[3] = Golay23.checkAndCorrect(mFrame, 69);
        mErrorCountTotal += mErrors[3];
        mErrors[4] = Hamming15.checkAndCorrect(mFrame, 92);
        mErrorCountTotal += mErrors[4];
        mErrors[5] = Hamming15.checkAndCorrect(mFrame, 107);
        mErrorCountTotal += mErrors[5];
        mErrors[6] = Hamming15.checkAndCorrect(mFrame, 122);
        mErrorCountTotal += mErrors[6];

        mFundamentalFrequency = IMBEFundamentalFrequency.fromValue(mFrame.getInt(VECTOR_B0));
    }

    public IMBEFundamentalFrequency getFundamentalFrequency()
    {
        return mFundamentalFrequency;
    }

    /**
     * Model parameters calculated for this frame.
     */
    public IMBEModelParameters getModelParameters(IMBEModelParameters previous)
    {
        IMBEModelParameters parameters = new IMBEModelParameters(getFundamentalFrequency());
        parameters.setAdaptiveSmoothingEnabled(!mMbelibErrorMode);
        parameters.setErrors(previous.getErrorRate(), mErrors[0], mErrors[4], mErrorCountTotal);

        /* If we have too many errors and/or the fundamental frequency is invalid
         * perform a repeat by copying the model parameters from previous frame  */
        if(parameters.repeatRequired() || mRepeatOverride)
        {
            parameters.copy(previous);
        }
        else
        {
            parameters.setVoicingDecisions(getVoicingDecisions());
            float[] log2SpectralAmplitudes = getLog2SpectralAmplitudes(previous);
            parameters.setLog2SpectralAmplitudes(log2SpectralAmplitudes);
            parameters.setSpectralAmplitudes(getSpectralAmplitudes(log2SpectralAmplitudes), previous.getLocalEnergy(),
                previous.getAmplitudeThreshold());
        }

        return parameters;
    }

    /**
     * Raw binary message source for this frame
     */
    public BinaryFrame getFrame()
    {
        return mFrame;
    }

    /**
     * Removes randomizer by generating a pseudo-random noise sequence from the first 12 bits of coset word c0 and
     * applies (xor) that sequence against message coset words c1 through c6.
     */
    private void derandomize()
    {
        /* Set the offset to the first seed bit plus 23 to point to coset c1 */
        int offset = 23;

        /* Get seed value from first 12 bits of coset c0 */
        int seed = mFrame.getInt(RANDOMIZER_SEED);

        //alg 52
        int prX = 16 * seed;

        for(int x = 0; x < 114; x++)
        {
            //Alg 53 - simplified [... - 65536 * floor((173 * pr(n-1) + 13849) / 65536)] to modulus operation
            prX = (173 * prX + 13849) % 65536;

            //Alg 54 - values 32768 and above are a 1 and below is a 0 (default)
            if(prX >= 32768)
            {
                //This is the same as xor
                mFrame.flip(x + offset);
            }
        }
    }

    /**
     * Reconstructs the spectral amplitude prediction residual set (T) for all values of L
     */
    public float[] getSpectralAmplitudePredictionResiduals()
    {
        int L = getFundamentalFrequency().getL();

        GainIndexes gainIndexes = GainIndexes.fromL(getFundamentalFrequency().getL());
        int gainIndex = mFrame.getInt(gainIndexes.getIndexes());
        Gain gain = Gain.fromValue(gainIndex);

        float[] G = new float[7];
        G[1] = gain.getGain();

        StepSizes stepSizes = StepSizes.fromL(L);
        QuantizedValueIndexes indexes = QuantizedValueIndexes.fromL(L);
        decodeGainVector(G, stepSizes, indexes);

        int[][] harmonicAllocations = HarmonicAllocation.fromL(L).getAllocations();
        //Harmonic allocation for i = 6 (index 5) will always have the largest allocation - use it to dimension C array

        float[][] C = new float[7][harmonicAllocations[5].length + 1];
        populateDctCoefficients(C, G, harmonicAllocations, stepSizes, indexes);

        return createPredictionResiduals(L, C, harmonicAllocations);
    }

    private void decodeGainVector(float[] gains, StepSizes stepSizes, QuantizedValueIndexes indexes)
    {
        //Alg 68 - Decoding gain vector G
        for(int m = 3; m <= 7; m++)
        {
            gains[m - 1] = decodeCoefficient(m, stepSizes, indexes);
        }
    }

    private void populateDctCoefficients(float[][] coefficients, float[] gains, int[][] harmonicAllocations,
        StepSizes stepSizes, QuantizedValueIndexes indexes)
    {
        //Alg 69 & 70 - Construct gain vector R as inverse DCT of G and transfer Ri to C[i][1]
        for(int i = 1; i <= 6; i++)
        {
            coefficients[i][1] = gains[1];

            for(int m = 2; m <= 6; m++)
            {
                coefficients[i][1] += (2.0f * gains[m] * (float)Math.cos((Math.PI * (m - 1) * (i - 0.5f)) / 6.0f));
            }
        }

        //Alg 71 and 72 - Decode the higher order DCT Coefficients
        for(int i = 1; i <= 6; i++)
        {
            int[] harmonics = harmonicAllocations[i - 1];

            for(int j = 2; j <= harmonics.length; j++)
            {
                coefficients[i][j] = decodeCoefficient(harmonics[j - 1], stepSizes, indexes);
            }
        }
    }

    private float[] createPredictionResiduals(int lCount, float[][] coefficients, int[][] harmonicAllocations)
    {
        //Alg 73 & 74 - inverse DCT of C to produce c and transfer results to Tl
        float[] residuals = new float[lCount + 1];
        int l = 1;

        for(int i = 1; i <= 6; i++) /* J-Block index */
        {
            int harmonicCount = harmonicAllocations[i - 1].length;

            for(int j = 1; j <= harmonicCount; j++)
            {
                residuals[l] = coefficients[i][1];

                for(int k = 2; k <= harmonicCount; k++)
                {
                    residuals[l] += 2.0f * coefficients[i][k] * (float)Math.cos((Math.PI * (k - 1) * (j - 0.5f)) /
                        harmonicCount);
                }

                l++;
            }
        }

        return residuals;
    }

    private float decodeCoefficient(int harmonic, StepSizes stepSizes, QuantizedValueIndexes indexes)
    {
        //Note: both the step sizes and quantized value indexes arrays are zero-based indexes so we have to subtract 3
        // from the harmonic value to align with the arrays
        int[] indexSet = indexes.getIndexes()[harmonic - 3];

        if(indexSet.length == 0)
        {
            return 0.0f;
        }

        int b = mFrame.getInt(indexSet);
        return stepSizes.getStepSizes()[harmonic - 3] * (b - COEFFICIENT_OFFSET[indexSet.length]);
    }

    /**
     * Supports Algorithm #75 and #77 assumptions - resize the previous frame's
     * spectral amplitudes to match the current frame's L size.
     *
     * Resizes elements array to ensure a minimum length of nextL + 1.  When
     * increasing the length, the highest index element value is copied to any
     * newly added indices.
     *
     * Index 0 element is set to 1.0.
     *
     * @param elements - current set of elements with an overall length of
     * L + 1.
     * @param nextL - requested new size of L.  returned array will be nextL + 1
     * elements in length.
     * @return properly (re)sized array
     */
    public static float[] resize(float[] elements, int nextL)
    {
        if(nextL > elements.length - 1)
        {
            float[] resized = new float[nextL + 1];

            System.arraycopy(elements, 0, resized, 0, elements.length);

            /* Copy the highest index value to the newly added indexes */
            float highest = elements[elements.length - 1];

            /* Algorithm #79 - set all new indexes to previous highest index */
            for(int x = elements.length; x < resized.length; x++)
            {
                resized[x] = highest;
            }

            return resized;
        }
        else
        {
            return elements;
        }
    }

    /**
     * Algorithms 75, 76, 77, 78, and 79 - calculate the current frame's
     * log2M spectral amplitudes using the current frame's spectral amplitude
     * residual values (T) and the previous frame's log2M spectral amplitudes
     * with appropriate scaling to account for differences in L between the
     * two frames.
     *
     * @param previousParameters - previous imbe audio frame
     */
    public float[] getLog2SpectralAmplitudes(IMBEModelParameters previousParameters)
    {
        int   currentL  = getFundamentalFrequency().getL();
        float L         = currentL;
        int   Lplus1    = currentL + 1;
        int   previousL = previousParameters.getL();

        //Get previous frame's log2M entries and resize them to 1 greater than the max of the current L, or the
        //previous L.  Set any newly expanded indexes to the value of the previously highest numbered index
        float[] previousLog2M = resize(previousParameters.getLog2SpectralAmplitudes(),
            Math.max(getFundamentalFrequency().getL(), previousL) + 1);

        //Current frame spectral amplitude prediction residuals
        float[] T = getSpectralAmplitudePredictionResiduals();

        float scale = previousL / L;

        float sum = 0.0f;

        for(int l = 1; l < Lplus1; l++)
        {
            /* Algorithm #75 and #76 - calculate kl and sl */
            float kl = l * scale;
            int klFloor = (int)Math.floor(kl);
            float sl = kl - klFloor;

            /* Algorithm #77 partial - summation */
            sum += ((1.0f - sl) * previousLog2M[klFloor]) + (sl * previousLog2M[klFloor + 1]);
        }

        /* Algorithm #77 - log2M spectral amplitudes of current frame */
        float[] log2M = new float[Lplus1];

        //Alg 55 - Prediction coefficient
        float p;

        if(L <= 15)
        {
            p = 0.4f;
        }
        else if(L <= 24)
        {
            p = 0.03f * L - 0.05f;
        }
        else
        {
            p = 0.7f;
        }

        //Represents the average log2 amplitude of the previous frame after translation to current L, scaled by the
        //prediction coefficient.
        float plSum = p / L * sum;

        //Algorithm #77
        for(int l = 1; l <= currentL; l++)
        {
            float kl = l * scale;
            int klFloor = (int)Math.floor(kl);
            float sl = kl - klFloor;

            log2M[l] = T[l]
                + (p * (1.0f - sl) * previousLog2M[klFloor])
                + (p * sl * previousLog2M[klFloor + 1])
                - plSum;
        }

        return log2M;
    }

    /**
     * Creates (M) spectral amplitudes by applying the inverse log2 (ie 2 to the power of value) to each log2M
     */
    private float[] getSpectralAmplitudes(float[] log2SpectralAmplitudes)
    {
        float[] spectralAmplitudes = new float[log2SpectralAmplitudes.length];

        for(int l = 1; l < log2SpectralAmplitudes.length; l++)
        {
            spectralAmplitudes[l] = (float)Math.pow(2.0f, log2SpectralAmplitudes[l]);
        }

        return spectralAmplitudes;
    }

    /**
     * Calculates the log base 2 of the value.
     *
     * log2(x) = log( x ) / log ( 2 );
     */
    public static float log2(float value)
    {
        return (float)Math.log(value) / LOG_2;
    }

    /**
     * Returns the voiced (true) / unvoiced (false) status for each of the
     * L harmonics.  Each of the 'l' harmonics is voiced if the K frequency
     * band to which it belongs is flagged as voiced.  The K frequency band
     * voiced/unvoiced flags are contained in each of the bit vector b1
     * bits of the imbe frame, which are variable length depending on the
     * value of K.
     *
     * @return boolean array containing L voicing decisions in array indexes
     * 1 through L, with array index 0 unused.
     */
    public boolean[] getVoicingDecisions()
    {
        int L = getFundamentalFrequency().getL();

        boolean[] decisions = new boolean[L + 1];
        int K = L < 37 ? (L + 2) / 3 : 12;

        for(int x = 1; x <= L; x++)
        {
            if(mMbelibErrorMode && mLinearImbe4400Data != null)
            {
                // ProVoice: voicing flags are in imbe4400 bits 48..59 in ascending
                // group order (group = floor((x-1)/3)), matching mbelib's linear read.
                int group = (x - 1) / 3;
                decisions[x] = mLinearImbe4400Data[48 + Math.min(group, K - 1)];
            }
            else
            {
                // P25/standard IMBE: use the per-harmonic frame bit table directly.
                decisions[x] = mFrame.get(VOICE_DECISION_INDEX[x]);
            }
        }

        return decisions;
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("VOICE FRAME FUND:");
        String fund = getFundamentalFrequency().name();

        sb.append(getFundamentalFrequency());
        if(fund.length() == 2)
        {
            sb.append("  ");
        }
        else if(fund.length() == 3)
        {
            sb.append(" ");
        }
        sb.append(" ERRORS:").append(Arrays.toString(mErrors));
        sb.append(" ").append(mFrame.toString());

        return sb.toString();
    }
}
