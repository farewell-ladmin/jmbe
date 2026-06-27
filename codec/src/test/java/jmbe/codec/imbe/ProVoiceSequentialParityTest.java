package jmbe.codec.imbe;

import org.junit.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Sequential ProVoice mbelib/JMBE parity harness for real .mbe frame streams.
 */
public class ProVoiceSequentialParityTest
{
    @Test
    public void dumpSequentialJmbeFrames() throws Exception
    {
        String hexPath = property("jmbe.provoice.hex", "JMBE_PROVOICE_HEX");
        String outPath = property("jmbe.provoice.dump", "JMBE_PROVOICE_DUMP");

        if(hexPath == null || outPath == null)
        {
            System.out.println("Skipped (set -Djmbe.provoice.hex=... and -Djmbe.provoice.dump=...)");
            return;
        }

        List<String> hexFrames = readHexFrames(hexPath);
        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        IMBESynthesizer synthesizer = new IMBESynthesizer();

        try(BufferedWriter writer = Files.newBufferedWriter(Paths.get(outPath), StandardCharsets.US_ASCII))
        {
            for(int index = 0; index < hexFrames.size(); index++)
            {
                DumpFrame dump = decode(codec, synthesizer, hexFrames.get(index));
                writeFrame(writer, index + 1, dump);
            }
        }

        System.out.println("Wrote JMBE sequential ProVoice dump: " + outPath + " frames=" + hexFrames.size());
    }

    @Test
    public void compareSequentialMbelibDump() throws Exception
    {
        String hexPath = property("jmbe.provoice.hex", "JMBE_PROVOICE_HEX");
        String mbelibPath = property("jmbe.mbelib.dump", "JMBE_MBELIB_DUMP");

        if(hexPath == null || mbelibPath == null)
        {
            System.out.println("Skipped (set -Djmbe.provoice.hex=... and -Djmbe.mbelib.dump=...)");
            return;
        }

        List<String> hexFrames = readHexFrames(hexPath);
        List<ReferenceFrame> referenceFrames = readReferenceFrames(mbelibPath);
        assertEquals("frame count", hexFrames.size(), referenceFrames.size());

        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        IMBESynthesizer synthesizer = new IMBESynthesizer();

        for(int index = 0; index < hexFrames.size(); index++)
        {
            DumpFrame actual = decode(codec, synthesizer, hexFrames.get(index));
            ReferenceFrame expected = referenceFrames.get(index);
            String divergence = firstDivergence(index + 1, expected, actual);

            if(divergence != null)
            {
                throw new AssertionError(divergence);
            }
        }
    }

    private static DumpFrame decode(ProVoiceIMBEAudioCodec codec, IMBESynthesizer synthesizer, String hex)
    {
        byte[] frameBytes = hex(hex);
        boolean[][] grid = codec.unpackGrid(frameBytes);
        int c0Errors = codec.correctC0(grid);
        codec.demodulate(grid);
        int[] errors = new int[7];
        boolean[] imbe7100 = codec.extractData(grid, errors);
        errors[0] = c0Errors;
        boolean[] imbe4400 = codec.convert7100To4400(imbe7100);
        IMBEFrame frame = IMBEFrame.fromImbe4400Data(imbe4400, errors);
        float[] audio = synthesizer.getAudio(frame);

        DumpFrame dump = new DumpFrame();
        dump.c0Errors = c0Errors;
        for(int index = 1; index < errors.length; index++)
        {
            dump.dataErrors += errors[index];
        }
        dump.errs2 = c0Errors + dump.dataErrors;
        dump.b0_7100 = bitsToInt(imbe7100, new int[] {1, 2, 3, 4, 5, 6, 86, 87});
        dump.b0_4400 = bitsToInt(imbe4400, new int[] {0, 1, 2, 3, 4, 5, 85, 86});
        dump.b1Bits = bits(imbe4400, 48, 12);
        dump.cur = ParamFrame.from(synthesizer.getPreviousSynthesisParameters(), true);
        dump.prev = ParamFrame.from(synthesizer.getPreviousDecodeParameters(), false);
        dump.prevEnhanced = ParamFrame.from(synthesizer.getPreviousSynthesisParameters(), true);
        dump.audio = audio;
        return dump;
    }

    private static String firstDivergence(int frame, ReferenceFrame expected, DumpFrame actual)
    {
        String prefix = "FRAME " + frame + " ";

        if(expected.b0_4400 != actual.b0_4400)
        {
            return prefix + "b0_4400 differs mbelib=" + expected.b0_4400 + " jmbe=" + actual.b0_4400;
        }

        String paramDiff = firstParamDivergence(prefix + "params.cur ", expected.cur, actual.cur);
        if(paramDiff != null)
        {
            return paramDiff;
        }

        return null;
    }

    private static String firstParamDivergence(String prefix, ParamFrame expected, ParamFrame actual)
    {
        if(expected == null)
        {
            return prefix + "missing mbelib params";
        }
        if(Math.abs(expected.w0 - actual.w0) > 0.000001f)
        {
            return prefix + "w0 differs mbelib=" + expected.w0 + " jmbe=" + actual.w0;
        }
        if(expected.L != actual.L)
        {
            return prefix + "L differs mbelib=" + expected.L + " jmbe=" + actual.L;
        }
        if(expected.K != actual.K)
        {
            return prefix + "K differs mbelib=" + expected.K + " jmbe=" + actual.K;
        }
        if(expected.repeat != actual.repeat)
        {
            return prefix + "repeat differs mbelib=" + expected.repeat + " jmbe=" + actual.repeat;
        }

        for(int index = 1; index <= expected.L; index++)
        {
            if(expected.vl[index] != actual.vl[index])
            {
                return prefix + "Vl[" + index + "] differs mbelib=" + expected.vl[index] + " jmbe=" + actual.vl[index]
                        + " mbelibVl=" + join(expected.vl) + " jmbeVl=" + join(actual.vl);
            }
        }

        for(int index = 1; index <= expected.L; index++)
        {
            if(!matches(expected.log2Ml[index], actual.log2Ml[index]))
            {
                return prefix + "log2Ml[" + index + "] differs mbelib=" + expected.log2Ml[index] + " jmbe=" + actual.log2Ml[index];
            }
        }

        for(int index = 1; index <= expected.L; index++)
        {
            if(!matches(expected.ml[index], actual.ml[index]))
            {
                return prefix + "Ml[" + index + "] differs mbelib=" + expected.ml[index] + " jmbe=" + actual.ml[index];
            }
        }

        return null;
    }

    private static void writeFrame(BufferedWriter writer, int frame, DumpFrame dump) throws Exception
    {
        writer.write("FRAME " + frame + "\n");
        writer.write("c0_errors=" + dump.c0Errors + "\n");
        writer.write("data_errors=" + dump.dataErrors + "\n");
        writer.write("errs2=" + dump.errs2 + "\n");
        writer.write("b0_7100=" + dump.b0_7100 + "\n");
        writer.write("b0_4400=" + dump.b0_4400 + "\n");
        writer.write("imbe4400_b1=" + dump.b1Bits + "\n");
        writeParams(writer, "params.cur", dump.cur);
        writeParams(writer, "params.prev", dump.prev);
        writeParams(writer, "params.prev_enhanced", dump.prevEnhanced);
        writer.write("audio=");
        for(int index = 0; index < dump.audio.length; index++)
        {
            writer.write(String.format(java.util.Locale.US, "%.9f", dump.audio[index] * 32767.0f));
            if(index + 1 < dump.audio.length)
            {
                writer.write(' ');
            }
        }
        writer.write("\n\n");
    }

    private static void writeParams(BufferedWriter writer, String prefix, ParamFrame params) throws Exception
    {
        writer.write(prefix + ".w0=" + String.format(java.util.Locale.US, "%.9f", params.w0) + "\n");
        writer.write(prefix + ".L=" + params.L + "\n");
        writer.write(prefix + ".K=" + params.K + "\n");
        writer.write(prefix + ".repeat=" + params.repeat + "\n");
        writer.write(prefix + ".Vl=" + join(params.vl) + "\n");
        writer.write(prefix + ".log2Ml=" + join(params.log2Ml) + "\n");
        writer.write(prefix + ".Ml=" + join(params.ml) + "\n");
    }

    private static List<ReferenceFrame> readReferenceFrames(String path) throws Exception
    {
        List<ReferenceFrame> frames = new ArrayList<>();
        ReferenceFrame current = null;

        for(String line : Files.readAllLines(Paths.get(path), StandardCharsets.ISO_8859_1))
        {
            line = line.trim();
            if(line.startsWith("FRAME "))
            {
                current = new ReferenceFrame();
                frames.add(current);
            }
            else if(current != null && !line.isEmpty())
            {
                parseReferenceLine(current, line);
            }
        }

        return frames;
    }

    private static void parseReferenceLine(ReferenceFrame frame, String line)
    {
        int equals = line.indexOf('=');
        if(equals < 0)
        {
            return;
        }

        String key = line.substring(0, equals);
        String value = line.substring(equals + 1).trim();
        frame.values.put(key, value);

        if("b0_4400".equals(key))
        {
            frame.b0_4400 = Integer.parseInt(value);
        }
        else if(key.startsWith("params.cur."))
        {
            if(frame.cur == null)
            {
                frame.cur = new ParamFrame();
            }
            parseParamLine(frame.cur, key.substring("params.cur.".length()), value);
        }
    }

    private static void parseParamLine(ParamFrame params, String key, String value)
    {
        switch(key)
        {
            case "w0":
                params.w0 = Float.parseFloat(value);
                break;
            case "L":
                params.L = Integer.parseInt(value);
                break;
            case "K":
                params.K = Integer.parseInt(value);
                break;
            case "repeat":
                params.repeat = Integer.parseInt(value);
                break;
            case "Vl":
                String[] vl = value.split(" ");
                for(int index = 0; index < vl.length && index < params.vl.length; index++)
                {
                    params.vl[index] = Integer.parseInt(vl[index]);
                }
                break;
            case "log2Ml":
                params.log2Ml = parseFloats(value);
                break;
            case "Ml":
                params.ml = parseFloats(value);
                break;
            default:
                break;
        }
    }

    private static List<String> readHexFrames(String path) throws Exception
    {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.US_ASCII);
        String compact = text.replaceAll("[^0-9A-Fa-f]", "");
        List<String> frames = new ArrayList<>();

        for(int offset = 0; offset + 42 <= compact.length(); offset += 42)
        {
            frames.add(compact.substring(offset, offset + 42));
        }

        return frames;
    }

    private static String property(String property, String environment)
    {
        String value = System.getProperty(property);
        return value != null ? value : System.getenv(environment);
    }

    private static float[] parseFloats(String value)
    {
        String[] parts = value.split(" ");
        float[] floats = new float[57];
        for(int index = 0; index < parts.length && index < floats.length; index++)
        {
            floats[index] = Float.parseFloat(parts[index]);
        }
        return floats;
    }

    private static int bitsToInt(boolean[] data, int[] indexes)
    {
        int value = 0;
        for(int index : indexes)
        {
            value = (value << 1) | (data[index] ? 1 : 0);
        }
        return value;
    }

    private static String bits(boolean[] data, int offset, int length)
    {
        StringBuilder sb = new StringBuilder(length);
        for(int index = 0; index < length; index++)
        {
            sb.append(data[offset + index] ? '1' : '0');
        }
        return sb.toString();
    }

    private static boolean matches(float expected, float actual)
    {
        float delta = Math.abs(expected - actual);
        return delta <= Math.max(0.01f, Math.abs(expected) * 0.00001f);
    }

    private static String join(int[] values)
    {
        StringBuilder sb = new StringBuilder();
        for(int index = 0; index < values.length; index++)
        {
            if(index > 0)
            {
                sb.append(' ');
            }
            sb.append(values[index]);
        }
        return sb.toString();
    }

    private static String join(float[] values)
    {
        StringBuilder sb = new StringBuilder();
        for(int index = 0; index < values.length; index++)
        {
            if(index > 0)
            {
                sb.append(' ');
            }
            sb.append(String.format(java.util.Locale.US, "%.9f", values[index]));
        }
        return sb.toString();
    }

    private static byte[] hex(String value)
    {
        byte[] bytes = new byte[value.length() / 2];
        for(int index = 0; index < bytes.length; index++)
        {
            bytes[index] = (byte)Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    private static class DumpFrame
    {
        int c0Errors;
        int dataErrors;
        int errs2;
        int b0_7100;
        int b0_4400;
        String b1Bits;
        ParamFrame cur;
        ParamFrame prev;
        ParamFrame prevEnhanced;
        float[] audio;
    }

    private static class ReferenceFrame
    {
        int b0_4400;
        ParamFrame cur;
        Map<String, String> values = new HashMap<>();
    }

    private static class ParamFrame
    {
        float w0;
        int L;
        int K;
        int repeat;
        int[] vl = new int[57];
        float[] log2Ml = new float[57];
        float[] ml = new float[57];

        static ParamFrame from(IMBEModelParameters parameters, boolean enhanced)
        {
            ParamFrame frame = new ParamFrame();
            frame.w0 = parameters.getFundamentalFrequency();
            frame.L = parameters.getL();
            frame.K = frame.L < 37 ? (frame.L + 2) / 3 : 12;
            frame.repeat = parameters.getRepeatCount();

            boolean[] voicing = parameters.getVoicingDecisions();
            for(int index = 0; index < frame.vl.length && index < voicing.length; index++)
            {
                frame.vl[index] = voicing[index] ? 1 : 0;
            }

            copy(parameters.getLog2SpectralAmplitudes(), frame.log2Ml);
            copy(enhanced ? parameters.getEnhancedSpectralAmplitudes() : parameters.getSpectralAmplitudes(), frame.ml);
            return frame;
        }

        private static void copy(float[] source, float[] destination)
        {
            for(int index = 0; index < destination.length && index < source.length; index++)
            {
                destination[index] = source[index];
            }
        }
    }
}
