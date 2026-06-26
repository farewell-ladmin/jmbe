package jmbe.codec.imbe;

import jmbe.binary.BinaryFrame;
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
    private static final byte[] CAPTURED_GRID_1 = hex("164720DB86E29BEC5C81DBA080FA00F5A200620018");
    private static final String[] MBELIB_GRID_1_POST_C0 = {
            "000101100100011100100000",
            "110110111000011011100010",
            "100110111110110001011100",
            "100000011101101110100000",
            "100000001111101000000000",
            "111101011010001000000000",
            "011000100000000000011000"
    };
    private static final String[] MBELIB_GRID_1_POST_DEMOD = {
            "000101100100011100100000",
            "011110011111011001011001",
            "011001011001010011100110",
            "100111001110101111101010",
            "001111001101000000000000",
            "111001001110000000000000",
            "011000100000000000011000"
    };
    private static final String MBELIB_GRID_1_IMBE7100 =
            "1001110101111100110111010100101101011111111000101110110000110001000110000000000001000110";
    private static final String MBELIB_GRID_1_IMBE4400 =
            "0011101011111001101110101001011010111111111000010010111010100010001100000000000010001101";

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

    /**
     * Diagnostic: mbelib decodes ProVoice/P25 b0 from imbe_d indices
     * {0,1,2,3,4,5,85,86}. After loadImbe4400Data maps the 88-bit vector into
     * the 144-bit frame, verify which frame index pair actually holds the b0
     * low bits so we can confirm the VECTOR_B0 / load mapping for ProVoice.
     */
    @Test
    public void testProVoiceB0MappingMatchesMbelib()
    {
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        boolean[][] grid = codec.unpackGrid(CAPTURED_GRID_1);
        codec.correctC0(grid);
        codec.demodulate(grid);
        boolean[] imbe = codec.extractData(grid);
        boolean[] imbe4400 = codec.convert7100To4400(imbe);

        int b0Mbelib = bitsToInt(imbe4400, new int[] {0, 1, 2, 3, 4, 5, 85, 86});
        IMBEFrame frame = IMBEFrame.fromImbe4400Data(imbe4400);
        int b0Frame142143 = frame.getFrame().getInt(new int[] {0, 1, 2, 3, 4, 5, 142, 143});
        int b0Frame141142 = frame.getFrame().getInt(new int[] {0, 1, 2, 3, 4, 5, 141, 142});
        int b0Frame142143FromData = bitsToInt(imbe4400, new int[] {0, 1, 2, 3, 4, 5, 86, 87});

        // mbelib reference for this captured frame is b0=58, L=22.
        assertEquals(58, b0Mbelib);
        System.out.println("b0Mbelib=" + b0Mbelib + " b0Frame142143=" + b0Frame142143
                + " b0Frame141142=" + b0Frame141142 + " b0Frame142143FromData=" + b0Frame142143FromData);
    }

    private static int bitsToInt(boolean[] data, int[] indexes)
    {
        int value = 0;
        for(int index : indexes)
        {
            value = Integer.rotateLeft(value, 1);
            if(data[index])
            {
                value++;
            }
        }
        return value;
    }

    /**
     * Live frame mbelib-vs-JMBE stage comparison. The packed grid
     * 17FE20DB8CEAD8DC5E012F8470BA0074B8004A30F8 is the first frame of
     * C:\Users\ethan\SDRTrunk\recordings\20260626_170239_851987500_1_PROVOICE_535_102.mbe
     * and was dumped via the standalone mbelib provoice_stage_dump tool:
     *   b0_7100=22 b0_4400=22 L=13 K=5 decode_status=0 c0_errors=1 data_errors=7
     * Enable DUMP_STAGE_TO_STDERR to print JMBE's intermediate stages for diffing.
     */
    @Test
    public void testLiveFrameMbelibComparison()
    {
        byte[] live = hex("17FE20DB8CEAD8DC5E012F8470BA0074B8004A30F8");
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        boolean[][] grid = codec.unpackGrid(live);
        codec.correctC0(grid);
        codec.demodulate(grid);
        boolean[] imbe7100 = codec.extractData(grid);
        boolean[] imbe4400 = codec.convert7100To4400(imbe7100);

        // mbelib ground truth from provoice_stage_dump on the same packed grid:
        // imbe7100=1000101001111001101001111101111011001111100000100110001110001001000111110000110001010010
        // imbe4400=0001010011110011010011111011110110011111011000110010000100010010001111100001100010100101
        // b0_7100=22 b0_4400=22 L=13 K=5
        assertEquals("1000101001111001101001111101111011001111100000100110001110001001000111110000110001010010",
                bits(imbe7100));
        assertEquals("0001010011110011010011111011110110011111011000110010000100010010001111100001100010100101",
                bits(imbe4400));

        int b0_7100 = bitsToInt(imbe7100, new int[] {1, 2, 3, 4, 5, 6, 86, 87});
        int b0_4400 = bitsToInt(imbe4400, new int[] {0, 1, 2, 3, 4, 5, 85, 86});
        assertEquals(22, b0_7100);
        assertEquals(22, b0_4400);

        IMBEFrame frame = IMBEFrame.fromImbe4400Data(imbe4400);
        assertEquals(13, frame.getFundamentalFrequency().getL());

        float[] audio = codec.getAudio(live);
        assertEquals(160, audio.length);
    }

    /**
     * Dump JMBE's intermediate stages for one VALID frame from DSD-FME .imb
     * dump: CA1A28803F1A082146CD7CE1B725BC8913220B40BD (mbelib ground truth:
     * b0=20, L=13, K=5, decode_status=0).
     */
    @Test
    public void testJmbeVsMbelibPerParameterValidFrame()
    {
        byte[] frame = hex("CA1A28803F1A082146CD7CE1B725BC8913220B40BD");
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        boolean[][] grid = codec.unpackGrid(frame);
        codec.correctC0(grid);
        codec.demodulate(grid);
        boolean[] imbe7100 = codec.extractData(grid);
        boolean[] imbe4400 = codec.convert7100To4400(imbe7100);
        IMBEFrame jmbeframe = IMBEFrame.fromImbe4400Data(imbe4400);

        int b0_jmbe = jmbeframe.getFrame().getInt(new int[] {0,1,2,3,4,5,141,142});
        System.out.println("JMBE b0=" + b0_jmbe + " (expected 20)");

        System.out.println("imbe7100=" + bits(imbe7100));
        System.out.println("imbe4400=" + bits(imbe4400));

        // Dump JMBE frame bits at QuantizedValueIndexes[L=13] positions, mapped back to imbe_d indices.
        int L = jmbeframe.getFundamentalFrequency().getL();
        System.out.println("L=" + L + " K=" + (L < 37 ? (L+2)/3 : 12));
        int[][] qi = jmbe.codec.imbe.QuantizedValueIndexes.fromL(L).getIndexes();
        for(int p = 0; p < qi.length; p++)
        {
            int harmonic = p + 3;
            int[] positions = qi[p];
            StringBuilder sb = new StringBuilder();
            sb.append("b").append(harmonic).append(" framePositions=");
            for(int x = 0; x < positions.length; x++)
            {
                int fpos = positions[x];
                int imbeIndex = jmbeFramePosToImbeDIndex(fpos);
                sb.append(fpos).append("(d").append(imbeIndex).append("=");
                sb.append(imbe4400[imbeIndex] ? "1" : "0").append(")");
                if(x < positions.length - 1) sb.append(",");
            }
            System.out.println(sb.toString());
        }
    }

    /**
     * Map JMBE frame position back to imbe_d index using loadImbe4400Data chunking:
     *  coset 0: frame[0..11]   <- imbe_d[0..11]
     *  coset 1: frame[23..34]  <- imbe_d[12..23]
     *  coset 2: frame[46..57]  <- imbe_d[24..35]
     *  coset 3: frame[69..80]  <- imbe_d[36..47]
     *  coset 4: frame[92..102] <- imbe_d[48..58]
     *  coset 5: frame[107..117]<- imbe_d[59..69]
     *  coset 6: frame[122..132]<- imbe_d[70..80]
     *  coset 7: frame[137..143]<- imbe_d[81..87]
     */
    private static int jmbeFramePosToImbeDIndex(int framePos)
    {
        int[][] cosets = {{0, 0, 12}, {23, 12, 12}, {46, 24, 12}, {69, 36, 12},
                          {92, 48, 11}, {107, 59, 11}, {122, 70, 11}, {137, 81, 7}};
        for(int[] c : cosets)
        {
            if(framePos >= c[0] && framePos < c[0] + c[2])
            {
                return c[1] + (framePos - c[0]);
            }
        }
        return -1;
    }

    /**
     * Validate that {@code IMBEFrame.fromImbe4400Data(boolean[])} correctly
     * loads P25-extracted coset data so that synthesis produces the same audio
     * as the direct {@code IMBEFrame(byte[])} path.
     *
     * Take a real P25 raw 18-byte frame, decode it normally to build path1;
     * extract the 88-bit coset-data from path1's final frame state, feed via
     * fromImbe4400Data to build path2; synthesize both via fresh IMBESynthesizer
     * instances fed the same initial previous frame; compare every PCM sample.
     *
     * Hex captured from C:\Users\ethan\SDRTrunk\recordings\20260613_233848_773281250_1_P2087_44947.mbe.
     */
    @Test
    public void testFromImbe4400DataMatchesDecodePathForP25()
    {
        byte[] p25Frame = hex("8A9A20B183FFBFC5C89024735948A08810A8");
        IMBEFrame frame1 = new IMBEFrame(p25Frame);
        BinaryFrame bf1 = frame1.getFrame();
        System.out.println("P25 path: b0=" + frame1.getFundamentalFrequency().name() +
                " L=" + frame1.getFundamentalFrequency().getL());

        // Extract the 88-bit coset data from the post-decode frame using the
        // same chunk layout JMBE loadImbe4400Data expects to be fed.
        boolean[] imbe888 = new boolean[88];
        int[][] cosets = {{0, 0, 12}, {23, 12, 12}, {46, 24, 12}, {69, 36, 12},
                           {92, 48, 11}, {107, 59, 11}, {122, 70, 11}, {137, 81, 7}};
        for(int[] c : cosets)
        {
            for(int k = 0; k < c[2]; k++)
            {
                imbe888[c[1] + k] = bf1.get(c[0] + k);
            }
        }

        IMBEFrame frame2 = IMBEFrame.fromImbe4400Data(imbe888);
        System.out.println("fromImbe4400Data path: b0=" + frame2.getFundamentalFrequency().name() +
                " L=" + frame2.getFundamentalFrequency().getL());

        // Both must agree on fundamental frequency (validates VECTOR_B0 placement incl. bits 141,142).
        assertEquals("b0 must match across paths",
            frame1.getFundamentalFrequency(), frame2.getFundamentalFrequency());

        // Synthesize audio for both. Both synthesizers start from fresh default previous params.
        IMBESynthesizer synth1 = new IMBESynthesizer();
        IMBESynthesizer synth2 = new IMBESynthesizer();
        float[] audio1 = synth1.getAudio(frame1);
        float[] audio2 = synth2.getAudio(frame2);

        assertEquals(audio1.length, audio2.length);
        int differences = 0;
        float maxDelta = 0f;
        for(int n = 0; n < audio1.length; n++)
        {
            float d = Math.abs(audio1[n] - audio2[n]);
            if(d > maxDelta) maxDelta = d;
            if(d > 0.0001f)
            {
                if(differences < 10) System.out.println("audio[" + n + "]: " + audio1[n] + " vs " + audio2[n] + "  delta=" + d);
                differences++;
            }
        }
        System.out.println("Synthesizer sample differences: " + differences + " / " + audio1.length + " maxDelta=" + maxDelta);
        assertEquals("loadImbe4400Data must produce identical samples as direct decode() for P25-extracted bits",
            0, differences);
    }

    @Test
    public void testCapturedGridMatchesMbelibStages()
    {
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        boolean[][] grid = codec.unpackGrid(CAPTURED_GRID_1);

        codec.correctC0(grid);
        assertGridEquals(MBELIB_GRID_1_POST_C0, grid);

        codec.demodulate(grid);
        assertGridEquals(MBELIB_GRID_1_POST_DEMOD, grid);

        boolean[] imbe7100 = codec.extractData(grid);
        assertEquals(MBELIB_GRID_1_IMBE7100, bits(imbe7100));

        boolean[] imbe4400 = codec.convert7100To4400(imbe7100);
        assertEquals(MBELIB_GRID_1_IMBE4400, bits(imbe4400));

        IMBEFrame frame = IMBEFrame.fromImbe4400Data(imbe4400);
        assertEquals(22, frame.getFundamentalFrequency().getL());
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

    private static void assertGridEquals(String[] expected, boolean[][] actual)
    {
        assertEquals(expected.length, actual.length);

        for(int row = 0; row < expected.length; row++)
        {
            assertEquals("row " + row, expected[row], bits(actual[row]));
        }
    }

    private static String bits(boolean[] bits)
    {
        StringBuilder sb = new StringBuilder(bits.length);

        for(boolean bit : bits)
        {
            sb.append(bit ? '1' : '0');
        }

        return sb.toString();
    }

    private static byte[] hex(String value)
    {
        byte[] bytes = new byte[value.length() / 2];

        for(int x = 0; x < bytes.length; x++)
        {
            bytes[x] = (byte)Integer.parseInt(value.substring(x * 2, x * 2 + 2), 16);
        }

        return bytes;
    }
}
