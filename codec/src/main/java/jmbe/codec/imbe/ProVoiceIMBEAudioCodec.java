package jmbe.codec.imbe;

import jmbe.audio.AudioWithMetadata;
import jmbe.binary.BinaryFrame;
import jmbe.edac.Golay23;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioWithMetadata;

/**
 * EDACS ProVoice IMBE7100x4400 decoder.
 *
 * <p>Input is one 7x24 IMBE7100 grid packed into 21 bytes, row-major, MSB first.
 * The conversion sequence follows mbelib's IMBE7100x4400 path: correct C0,
 * demodulate, extract/correct the 88-bit parameter vector, convert it to the
 * generic IMBE4400 ordering, and synthesize with the existing IMBE synthesizer.</p>
 *
 * <p>Reference: mbelib imbe7100x4400.c.</p>
 */
public class ProVoiceIMBEAudioCodec implements IAudioCodec
{
    public static final String CODEC_NAME = "PROVOICE";
    private static final int GRID_ROWS = 7;
    private static final int GRID_COLUMNS = 24;
    private static final int GRID_BYTES = 21;
    private static final int[] IMBE7100_HAMMING_GENERATOR = { 0x7AC8, 0x3D64, 0x1EB2, 0x7591 };
    private static final int[] HAMMING_MATRIX = {
            0x0, 0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x40,
            0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000
    };

    private final IMBESynthesizer mSynthesizer = new IMBESynthesizer();

    @Override
    public String getCodecName()
    {
        return CODEC_NAME;
    }

    @Override
    public float[] getAudio(byte[] frameData)
    {
        boolean[][] grid = unpackGrid(frameData);
        correctC0(grid);
        demodulate(grid);
        boolean[] imbe7100Data = extractData(grid);
        boolean[] imbe4400Data = convert7100To4400(imbe7100Data);
        return mSynthesizer.getAudio(IMBEFrame.fromImbe4400Data(imbe4400Data));
    }

    @Override
    public IAudioWithMetadata getAudioWithMetadata(byte[] frameData)
    {
        return AudioWithMetadata.create(getAudio(frameData));
    }

    @Override
    public void reset()
    {
        mSynthesizer.reset();
    }

    private boolean[][] unpackGrid(byte[] frameData)
    {
        if(frameData == null || frameData.length != GRID_BYTES)
        {
            throw new IllegalArgumentException("ProVoice IMBE7100 frame must contain 21 bytes");
        }

        boolean[][] grid = new boolean[GRID_ROWS][GRID_COLUMNS];

        for(int bit = 0; bit < GRID_ROWS * GRID_COLUMNS; bit++)
        {
            grid[bit / GRID_COLUMNS][bit % GRID_COLUMNS] = (frameData[bit / 8] & (0x80 >> (bit % 8))) != 0;
        }

        return grid;
    }

    private void correctC0(boolean[][] grid)
    {
        BinaryFrame frame = new BinaryFrame(23);

        for(int original = 1; original <= 18; original++)
        {
            frame.set(23 - original, grid[0][original]);
        }

        Golay23.checkAndCorrect(frame, 0);

        for(int original = 1; original <= 18; original++)
        {
            grid[0][original] = frame.get(23 - original);
        }
    }

    private void demodulate(boolean[][] grid)
    {
        int seed = 0;
        for(int column = 18; column > 11; column--)
        {
            seed = (seed << 1) | (grid[0][column] ? 1 : 0);
        }

        int[] pr = new int[101];
        pr[0] = 16 * seed;
        for(int x = 1; x < pr.length; x++)
        {
            pr[x] = (173 * pr[x - 1] + 13849) % 65536;
        }

        int k = 1;
        for(int column = 23; column >= 0; column--)
        {
            grid[1][column] ^= pr[k++] >= 32768;
        }

        for(int row = 2; row < 4; row++)
        {
            for(int column = 22; column >= 0; column--)
            {
                grid[row][column] ^= pr[k++] >= 32768;
            }
        }

        for(int row = 4; row < 6; row++)
        {
            for(int column = 14; column >= 0; column--)
            {
                grid[row][column] ^= pr[k++] >= 32768;
            }
        }
    }

    private boolean[] extractData(boolean[][] grid)
    {
        boolean[] data = new boolean[88];
        int offset = 0;

        for(int column = 18; column > 11; column--)
        {
            data[offset++] = grid[0][column];
        }

        offset = extractGolayReversed(grid[1], 1, 23, data, offset);
        offset = extractGolayReversed(grid[2], 0, 22, data, offset);
        offset = extractGolayReversed(grid[3], 0, 22, data, offset);
        offset = extractHammingReversed(grid[4], data, offset);
        offset = extractHammingReversed(grid[5], data, offset);

        for(int column = 22; column >= 0; column--)
        {
            data[offset++] = grid[6][column];
        }

        return data;
    }

    private int extractGolayReversed(boolean[] row, int firstColumn, int lastColumn, boolean[] data, int offset)
    {
        BinaryFrame frame = new BinaryFrame(23);
        for(int column = lastColumn; column >= firstColumn; column--)
        {
            frame.add(row[column]);
        }

        Golay23.checkAndCorrect(frame, 0);

        for(int x = 0; x < 12; x++)
        {
            data[offset++] = frame.get(x);
        }

        return offset;
    }

    private int extractHammingReversed(boolean[] row, boolean[] data, int offset)
    {
        int block = 0;
        for(int column = 14; column >= 0; column--)
        {
            block = (block << 1) | (row[column] ? 1 : 0);
        }

        int syndrome = 0;
        for(int generator : IMBE7100_HAMMING_GENERATOR)
        {
            syndrome = (syndrome << 1) | (Integer.bitCount(block & generator) & 1);
        }

        if(syndrome > 0)
        {
            block ^= HAMMING_MATRIX[syndrome];
        }

        for(int x = 14; x >= 4; x--)
        {
            data[offset++] = (block & (1 << x)) != 0;
        }

        return offset;
    }

    private boolean[] convert7100To4400(boolean[] imbe7100Data)
    {
        boolean[] imbe4400Data = new boolean[88];
        int b0 = 0;

        for(int x = 1; x <= 6; x++)
        {
            b0 = (b0 << 1) | (imbe7100Data[x] ? 1 : 0);
        }

        b0 = (b0 << 1) | (imbe7100Data[86] ? 1 : 0);
        b0 = (b0 << 1) | (imbe7100Data[87] ? 1 : 0);

        double w0 = (4.0 * Math.PI) / (b0 + 39.5);
        int l = (int)(0.9254 * (int)((Math.PI / w0) + 0.25));
        int k = l < 37 ? (l + 2) / 3 : 12;

        imbe4400Data[87] = imbe7100Data[0];
        imbe4400Data[48 + k] = imbe7100Data[42];
        imbe4400Data[49 + k] = imbe7100Data[43];

        int source = 44;
        int destination = 48;
        for(int x = 0; x < k; x++)
        {
            imbe4400Data[destination++] = imbe7100Data[source++];
        }

        destination = 0;
        source = 1;
        while(destination < 87)
        {
            imbe4400Data[destination] = imbe7100Data[source];
            if(++destination == 48)
            {
                destination += k + 2;
            }
            if(++source == 42)
            {
                source += k + 2;
            }
        }

        return imbe4400Data;
    }
}
