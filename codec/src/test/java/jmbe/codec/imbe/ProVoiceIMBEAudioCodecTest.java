package jmbe.codec.imbe;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic stage tests for the EDACS ProVoice IMBE7100x4400 path.
 */
public class ProVoiceIMBEAudioCodecTest
{
    @Test
    public void testUnpackGridIsRowMajorMsbFirst()
    {
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();

        for(int bit = 0; bit < 168; bit++)
        {
            byte[] frame = new byte[21];
            frame[bit / 8] = (byte)(0x80 >> (bit % 8));
            boolean[][] grid = codec.unpackGrid(frame);

            for(int row = 0; row < 7; row++)
            {
                for(int column = 0; column < 24; column++)
                {
                    assertEquals("bit " + bit + " row " + row + " column " + column,
                            bit == (row * 24) + column, grid[row][column]);
                }
            }
        }
    }

    @Test
    public void testDemodulateMatchesMbelibTraversal()
    {
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        boolean[][] grid = patternedGrid();
        boolean[][] expected = copy(grid);

        referenceDemodulate(expected);
        codec.demodulate(grid);

        assertGridEquals(expected, grid);
    }

    @Test
    public void testExtractDataCopiesC0AndC6InMbelibOrder()
    {
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        boolean[][] grid = new boolean[7][24];

        for(int column = 12; column <= 18; column++)
        {
            grid[0][column] = column % 2 == 0;
        }

        for(int column = 0; column <= 22; column++)
        {
            grid[6][column] = column % 3 == 0;
        }

        boolean[] data = codec.extractData(grid);

        int offset = 0;
        for(int column = 18; column > 11; column--)
        {
            assertEquals(grid[0][column], data[offset++]);
        }

        offset = 65;
        for(int column = 22; column >= 0; column--)
        {
            assertEquals(grid[6][column], data[offset++]);
        }
    }

    @Test
    public void testConvert7100To4400MatchesMbelibIndexing()
    {
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();

        for(int seed = 0; seed < 256; seed++)
        {
            boolean[] imbe7100 = new boolean[88];

            for(int bit = 0; bit < imbe7100.length; bit++)
            {
                imbe7100[bit] = ((seed * 37 + bit * 11) & 0x40) != 0;
            }

            setB0(imbe7100, seed);
            assertArrayEquals(referenceConvert7100To4400(imbe7100), codec.convert7100To4400(imbe7100));
        }
    }

    @Test
    public void testImbe4400FrameUsesMbelibB0Indexes()
    {
        for(int b0 = 0; b0 <= 207; b0++)
        {
            boolean[] imbe4400 = new boolean[88];

            for(int index = 0; index < 6; index++)
            {
                imbe4400[index] = (b0 & (1 << (7 - index))) != 0;
            }

            imbe4400[85] = (b0 & 0x02) != 0;
            imbe4400[86] = (b0 & 0x01) != 0;
            imbe4400[84] = !imbe4400[85];
            imbe4400[87] = !imbe4400[86];

            IMBEFrame frame = IMBEFrame.fromImbe4400Data(imbe4400);
            assertEquals(referenceL(b0), frame.getFundamentalFrequency().getL());
        }
    }

    private static boolean[][] patternedGrid()
    {
        boolean[][] grid = new boolean[7][24];

        for(int row = 0; row < grid.length; row++)
        {
            for(int column = 0; column < grid[row].length; column++)
            {
                grid[row][column] = ((row * 31 + column * 17) & 0x08) != 0;
            }
        }

        setSeed(grid, 0x55);
        return grid;
    }

    private static void setSeed(boolean[][] grid, int seed)
    {
        for(int column = 18; column > 11; column--)
        {
            grid[0][column] = (seed & (1 << (column - 12))) != 0;
        }
    }

    private static void referenceDemodulate(boolean[][] grid)
    {
        int seed = 0;

        for(int column = 18; column > 11; column--)
        {
            seed = (seed << 1) | (grid[0][column] ? 1 : 0);
        }

        int[] pr = new int[101];
        pr[0] = 16 * seed;

        for(int index = 1; index < 101; index++)
        {
            pr[index] = (173 * pr[index - 1] + 13849) % 65536;
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

    private static boolean[] referenceConvert7100To4400(boolean[] imbe7100Data)
    {
        boolean[] imbe4400Data = new boolean[88];
        int b0 = 0;

        for(int index = 1; index <= 6; index++)
        {
            b0 = (b0 << 1) | (imbe7100Data[index] ? 1 : 0);
        }

        b0 = (b0 << 1) | (imbe7100Data[86] ? 1 : 0);
        b0 = (b0 << 1) | (imbe7100Data[87] ? 1 : 0);

        float w0 = (float)((4 * Math.PI) / ((float)b0 + 39.5f));
        int l = (int)(0.9254f * (int)((Math.PI / w0) + 0.25f));
        int k = l < 37 ? (int)((float)(l + 2) / 3.0f) : 12;

        imbe4400Data[87] = imbe7100Data[0];
        imbe4400Data[48 + k] = imbe7100Data[42];
        imbe4400Data[49 + k] = imbe7100Data[43];

        int source = 44;
        int destination = 48;
        for(int index = 0; index < k; index++)
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

    private static int referenceL(int b0)
    {
        float w0 = (float)((4 * Math.PI) / ((float)b0 + 39.5f));
        return (int)(0.9254f * (int)((Math.PI / w0) + 0.25f));
    }

    private static void setB0(boolean[] data, int b0)
    {
        for(int index = 6; index >= 1; index--)
        {
            data[index] = (b0 & (1 << (8 - index))) != 0;
        }

        data[86] = (b0 & 0x02) != 0;
        data[87] = (b0 & 0x01) != 0;
    }

    private static boolean[][] copy(boolean[][] grid)
    {
        boolean[][] copy = new boolean[grid.length][];

        for(int row = 0; row < grid.length; row++)
        {
            copy[row] = grid[row].clone();
        }

        return copy;
    }

    private static void assertGridEquals(boolean[][] expected, boolean[][] actual)
    {
        assertEquals(expected.length, actual.length);

        for(int row = 0; row < expected.length; row++)
        {
            assertArrayEquals("row " + row, expected[row], actual[row]);
        }
    }
}
