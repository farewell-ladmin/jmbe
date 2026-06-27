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

import jmbe.codec.MBEModelParameters;
import jmbe.codec.MBESynthesizer;

/**
 * IMBE synthesizer for IMBE audio frames
 */
class IMBESynthesizer extends MBESynthesizer
{
    private IMBEModelParameters mPreviousParameters = new IMBEModelParameters();

    @Override
    protected MBEModelParameters getPreviousFrame()
    {
        return mPreviousParameters;
    }

    void reset()
    {
        mPreviousParameters = new IMBEModelParameters();
        // Match mbelib mbe_initMbeParms: PSIl[l]=pi/2, PHIl[l]=0, mPreviousUw=0.
        // Without this, cold-start segments inherit stale phase rolls from the
        // previous segment and the first voiced frame's phase diverges from
        // mbelib's reference synthesis path. Compounds badly for short EDACS
        // ProVoice calls where squelch flaps then reset() between segments.
        resetPhaseState();
    }

    /**
     * Synthesizes 20 milliseconds of audio from the imbe frame parameters in
     * the following format:
     *
     * Sample Rate: 8 kHz
     * Sample Size: 16-bits
     * Frame Size: 160 samples
     * Bit Format: Little Endian
     *
     * @return ByteBuffer containing the audio sample bytes
     */
    float[] getAudio(IMBEFrame frame)
    {
        IMBEModelParameters parameters = frame.getModelParameters(mPreviousParameters);

        float[] audio;

        // mbelib output on cur_mp->repeat > 3 is mbe_synthesizeSilencef
        // (zero samples, no random fill). The previous JMBE behavior of
        // returning getWhiteNoise(160, 0.003) here produced low-amplitude
        // random samples that the mPreviousUw WOLA accumulator would carry
        // forward into subsequent frames, perturbing the unvoiced synthesis.
        if(parameters.isMaxFrameRepeat() || parameters.requiresMuting())
        {
            audio = new float[SAMPLES_PER_FRAME];
        }
        else
        {
            audio = getVoice(parameters);
        }

        mPreviousParameters = parameters;

        return audio;
    }

}
