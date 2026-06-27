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

/**
 * Base Multi-Band Excitation (MBE) voice frame model parameters required to synthesize an audio frame.
 */
public abstract class MBEModelParameters
{
    private static final float PI_96 = 0.96f * (float)Math.PI;
    private float mLocalEnergy = 75000.0f;
    private int mAmplitudeThreshold = 20480;
    private boolean[] mVoicingDecisions;
    private float[] mLog2SpectralAmplitudes;
    protected float[] mSpectralAmplitudes;
    protected float[] mEnhancedSpectralAmplitudes;
    private float mErrorRate;
    private int mErrorCount;
    private int mErrorCount4;
    private int mRepeatCount = 0;
    private boolean mAdaptiveSmoothingEnabled = true;

    private IFundamentalFrequency mMBEFundamentalFrequency;
    private FrameType mFrameType;
    private float mFundamentalFrequency;
    private int mL;

    /**
     * Constructs an instance
     *
     * @param mbeFundamentalFrequency for the frame
     */
    protected MBEModelParameters(IFundamentalFrequency mbeFundamentalFrequency)
    {
        setMBEFundamentalFrequency(mbeFundamentalFrequency);
    }

    /**
     * Sets or changes the fundamental frequency
     */
    protected void setMBEFundamentalFrequency(IFundamentalFrequency mbeFundamentalFrequency)
    {
        mMBEFundamentalFrequency = mbeFundamentalFrequency;
        mFrameType = mbeFundamentalFrequency.getFrameType();
        mFundamentalFrequency = mbeFundamentalFrequency.getFrequency();
        mL = mbeFundamentalFrequency.getL();
    }

    /**
     * MBE fundamental frequency
     *
     * @return fundamental frequency entry
     */
    public IFundamentalFrequency getMBEFundamentalFrequency()
    {
        return mMBEFundamentalFrequency;
    }

    /**
     * Fundamental frequency used to synthesize voice in each of the L frequency bands
     *
     * @return frequency (0.0 <> 0.5)</>
     */
    public float getFundamentalFrequency()
    {
        return mFundamentalFrequency;
    }

    public void setFundamentalFrequency(float frequency)
    {
        mFundamentalFrequency = frequency;
    }

    /**
     * Number of frequency bands to synthesize
     */
    public int getL()
    {
        return mL;
    }

    public void setL(int L)
    {
        mL = L;
    }

    /**
     * Frame Type
     *
     * @return type of frame
     */
    public FrameType getFrameType()
    {
        return mFrameType;
    }

    public void setFrameType(FrameType frameType)
    {
        mFrameType = frameType;
    }

    /**
     * Voicing decisions array for each of the L frequency bands.
     *
     * @return array indicating which bands are voiced (ie true).
     */
    public boolean[] getVoicingDecisions()
    {
        return mVoicingDecisions;
    }

    public void setVoicingDecisions(boolean[] voicingDecisions)
    {
        mVoicingDecisions = voicingDecisions;
    }

    /**
     * Log2 spectral amplitudes
     */
    public float[] getLog2SpectralAmplitudes()
    {
        return mLog2SpectralAmplitudes;
    }

    public void setLog2SpectralAmplitudes(float[] log2SpectralAmplitudes)
    {
        mLog2SpectralAmplitudes = log2SpectralAmplitudes;
    }

    /**
     * (Unenhanced) Spectral amplitudes
     */
    public float[] getSpectralAmplitudes()
    {
        return mSpectralAmplitudes;
    }

    public void setSpectralAmplitudes(float[] spectralAmplitudes, float previousLocalEnergy, int previousAmplitudeThreshold)
    {
        mSpectralAmplitudes = spectralAmplitudes;
        enhanceSpectralAmplitudes(previousLocalEnergy, previousAmplitudeThreshold);
    }

    /**
     * Enhances Spectral amplitudes
     */
    public float[] getEnhancedSpectralAmplitudes()
    {
        return mEnhancedSpectralAmplitudes;
    }

    protected void setEnhancedSpectralAmplitudes(float[] enhancedSpectralAmplitudes)
    {
        mEnhancedSpectralAmplitudes = enhancedSpectralAmplitudes;
    }

    /**
     * Local energy
     */
    public float getLocalEnergy()
    {
        return mLocalEnergy;
    }

    protected void setLocalEnergy(float localEnergy)
    {
        mLocalEnergy = localEnergy;
    }

    /**
     * Error rate
     */
    public float getErrorRate()
    {
        return mErrorRate;
    }

    public void setErrorRate(float errorRate)
    {
        mErrorRate = errorRate;
    }

    /**
     * Bit error count - total number of bit errors detected/corrected for the audio frame
     */
    public int getErrorCountTotal()
    {
        return mErrorCount;
    }

    public void setErrorCountTotal(int errorCount)
    {
        mErrorCount = errorCount;
    }

    /**
     * Bit error count for coset 4
     */
    public int getErrorCount4()
    {
        return mErrorCount4;
    }

    public void setErrorCount4(int errorCount4)
    {
        mErrorCount4 = errorCount4;
    }

    /**
     * Number of times this frame has been repeated
     */
    public int getRepeatCount()
    {
        return mRepeatCount;
    }

    public void setRepeatCount(int repeatCount)
    {
        mRepeatCount = repeatCount;
    }

    /**
     * Indicates if this frame is a repeat from the previous
     */
    public boolean isRepeatFrame()
    {
        return mRepeatCount > 0;
    }

    /**
     * Indicates if this frame's repeat count has exceeded the max frame repeat threshold, indicating that audio
     * muting should occur.
     */
    public boolean isMaxFrameRepeat()
    {
        return getRepeatCount() >= 4;
    }

    /**
     * Amplitude threshold
     */
    public int getAmplitudeThreshold()
    {
        return mAmplitudeThreshold;
    }

    public void setAmplitudeThreshold(int threshold)
    {
        mAmplitudeThreshold = threshold;
    }

    /**
     * Indicates if any of the L frequency band harmonics are voiced
     */
    public boolean hasVoicedBands()
    {
        boolean[] voicingDecisions = getVoicingDecisions();

        for(int l = 1; l <= getL(); l++)
        {
            if(voicingDecisions[l])
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Count of the unvoiced bands in this frame
     */
    public int getUnvoicedBandCount()
    {
        int unvoiced = 0;
        boolean[] voicingDecisions = getVoicingDecisions();

        for(int l = 1; l <= getL(); l++)
        {
            if(!voicingDecisions[l])
            {
                unvoiced++;
            }
        }

        return unvoiced;
    }

    /**
     * Indicates if adaptive smoothing is required when the error rate threshold is exceeded
     */
    public boolean requiresAdaptiveSmoothing()
    {
        return mAdaptiveSmoothingEnabled && (getErrorRate() > 0.0125f || getErrorCountTotal() > 4);
    }

    public void setAdaptiveSmoothingEnabled(boolean adaptiveSmoothingEnabled)
    {
        mAdaptiveSmoothingEnabled = adaptiveSmoothingEnabled;
    }

    /**
     * Generates enhanced spectral amplitudes from decoded spectral amplitudes as described in Chapter 8.
     *
     * @param previousLocalEnergy from previous frame's voice parameters
     * @param previousAmplitudeThreshold from previous frame's voice parameters
     */
    private void enhanceSpectralAmplitudes(float previousLocalEnergy, int previousAmplitudeThreshold)
    {
        /* Algorithm #105 and #106 - calculate RM0 and RM1 from amplitudes */
        float[] spectralAmplitudes = getSpectralAmplitudes();

        int L = getL();
        float rm0 = 0.0f;
        float rm1 = 0.0f;

        for(int l = 1; l <= L; l++)
        {
            float amplitudesSquared = spectralAmplitudes[l] * spectralAmplitudes[l];
            rm0 += amplitudesSquared;
            rm1 = (float)(rm1 + (amplitudesSquared * Math.cos(getFundamentalFrequency() * l)));
        }

        float rm0squared = rm0 * rm0;
        float rm1squared = rm1 * rm1;

        // mbelib stores model parameters in fixed-size arrays and only mutates
        // active harmonics 1..L. Preserve any stale tail entries because later
        // frames can read them during log2M prediction when intkl + 1 lands
        // just beyond the previous frame's L.
        float[] enhancedSpectralAmplitudes = spectralAmplitudes.clone();

        if(rm0 == 0.0f)
        {
            setEnhancedSpectralAmplitudes(enhancedSpectralAmplitudes);
            return;
        }

        /* Algorithm #107 and #108 - calculate and apply enhancement weights to produce enhanced amplitudes */
        for(int l = 1; l <= L; l++)
        {
            if((8 * l) <= L)
            {
                enhancedSpectralAmplitudes[l] = spectralAmplitudes[l];
            }
            else
            {
                float temp = (PI_96 * (rm0squared + rm1squared -
                    (2.0f * rm0 * rm1 * (float)Math.cos(getFundamentalFrequency() * l)))) /
                    (getFundamentalFrequency() * rm0 * (rm0squared - rm1squared));
                float weight = (float)(Math.sqrt(spectralAmplitudes[l]) * Math.pow(temp, 0.25));

                if(weight > 1.2f)
                {
                    enhancedSpectralAmplitudes[l] = spectralAmplitudes[l] * 1.2f;
                }
                else if(weight < 0.5f)
                {
                    enhancedSpectralAmplitudes[l] = spectralAmplitudes[l] * 0.5f;
                }
                else
                {
                    enhancedSpectralAmplitudes[l] = spectralAmplitudes[l] * weight;
                }
            }
        }

        /* Algorithm #109 - remove energy differential of enhanced amplitudes */
        float denominator = 0.0f;

        for(int l = 1; l <= L; l++)
        {
            denominator += (enhancedSpectralAmplitudes[l] * enhancedSpectralAmplitudes[l]);
        }

        float y = 0.0f;

        if(denominator > 0.0f)
        {
            y = (float)Math.sqrt(rm0 / denominator);
        }

        /* Algorithm #110 - scale enhanced amplitudes to remove energy differential */
        for(int l = 1; l <= L; l++)
        {
            enhancedSpectralAmplitudes[l] *= y;
        }

        /* Algorithm #111 - calculate local energy */
        mLocalEnergy = (0.95f * previousLocalEnergy) + (0.05f * rm0);

        if(mLocalEnergy < 10000.0f)
        {
            mLocalEnergy = 10000.0f;
        }

        setEnhancedSpectralAmplitudes(enhancedSpectralAmplitudes);

        applyAdaptiveSmoothing(previousAmplitudeThreshold);
    }

    /**
     * Performs adaptive smoothing on enhanced spectral amplitudes and the voice/no-voice decisions when error rate
     * is above a certain threshold that could cause audio distortions or discontinuities between successive frames
     */
    private void applyAdaptiveSmoothing(int previousAmplitudeThresholdTM)
    {
        if(!mAdaptiveSmoothingEnabled)
        {
            return;
        }

        float[] enhancedSpectralAmplitudes = getEnhancedSpectralAmplitudes();

        /* Algorithm #112 - calculate adaptive threshold */
        if(getErrorRate() > 0.005 || getErrorCountTotal() > 4)
        {
            float VM = getAdaptiveThreshold();

            //Voicing decisions only have to be smoothed in the presence of errors
            boolean[] voicingDecisions = getVoicingDecisions();

            for(int l = 1; l <= getL(); l++)
            {
                float amplitude = enhancedSpectralAmplitudes[l];

                /* Algorithm #113 - apply adaptive threshold to voice/no voice decisions */
                voicingDecisions[l] = amplitude > VM || voicingDecisions[l];
            }

            setVoicingDecisions(voicingDecisions);
        }

        float Am = 0.0f;

        for(int l = 1; l <= getL(); l++)
        {
            /* Algorithm #114 - calculate amplitude measure */
            Am += enhancedSpectralAmplitudes[l];
        }

        int Tm = 0;

        /* Algorithm #115 - calculate amplitude threshold */
        if(getErrorRate() <= 0.005 && getErrorCountTotal() <= 6)
        {
            Tm = 20480;
        }
        else
        {
            Tm = (6000 - (300 * getErrorCountTotal()) + previousAmplitudeThresholdTM);
        }

        setAmplitudeThreshold(Tm);

        //Algorithm #116 - scale enhanced spectral amplitudes if amplitude measure is greater than amplitude threshold
        if(Am > 0.0f && Am > Tm)
        {
            float scale = Tm / Am;

            for(int l = 1; l < getL() + 1; l++)
            {
                enhancedSpectralAmplitudes[l] *= scale;
            }
        }

        setEnhancedSpectralAmplitudes(enhancedSpectralAmplitudes);
    }

    private float getAdaptiveThreshold()
    {
        float energy = (float)Math.pow(getLocalEnergy(), 0.375f);

        if(getErrorRate() <= 0.0125f && getErrorCount4() == 0)
        {
            return (45.255f * energy) / (float)Math.exp(277.26f * getErrorRate());
        }

        return 1.414f * energy;
    }
}
