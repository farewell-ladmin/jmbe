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

    /**
     * Compares the sequential synthesized audio (the {@code audio=} line) between mbelib and JMBE.
     *
     * <p>The existing {@link #compareSequentialMbelibDump()} only validates model parameters
     * (params.cur) and silently ignores the synthesized waveform, giving false confidence: bit-exact
     * parameters can still produce garbled audio if synthesis phase/algorithm diverge. This test
     * surfaces that divergence using per-frame zero-lag Pearson correlation.</p>
     *
     * <p>Correlation, not per-sample diff, is the right metric because mbelib's unvoiced/high-harmonic
     * phases come from the C-library {@code rand()} sequence which JMBE cannot bit-reproduce. Unvoiced
     * bands therefore cannot correlate by design (random noise sounds the same regardless of phase),
     * so the report buckets correlation by the frame's voiced fraction. Voiced content carries the
     * intelligibility; that is where high correlation must hold.</p>
     *
     * <p>This is a diagnostic report. It only hard-fails if the highly-voiced bucket regresses below a
     * loose floor so a clear voiced-phase break cannot slip through unnoticed.</p>
     */
    @Test
    public void compareSequentialAudio() throws Exception
    {
        String hexPath = property("jmbe.provoice.hex", "JMBE_PROVOICE_HEX");
        String mbelibPath = property("jmbe.mbelib.dump", "JMBE_MBELIB_DUMP");

        if(hexPath == null || mbelibPath == null)
        {
            System.out.println("Skipped (set -Djmbe.provoice.hex=... and -Djmbe.mbelib.dump=...)");
            return;
        }

        List<String> hexFrames = readHexFrames(hexPath);
        List<ReferenceFrame> referenceFrames = readReferenceAudioFrames(mbelibPath);
        assertEquals("frame count", hexFrames.size(), referenceFrames.size());

        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        IMBESynthesizer synthesizer = new IMBESynthesizer();

        double[] bucketCorrSum = new double[5];
        int[] bucketCount = new int[5];
        int audibleFrames = 0;
        int wellCorrelated = 0;
        String worstVoicedFrame = null;
        double worstVoicedCorr = 2.0;

        for(int index = 0; index < hexFrames.size(); index++)
        {
            DumpFrame actual = decode(codec, synthesizer, hexFrames.get(index));
            ReferenceFrame expected = referenceFrames.get(index);

            if(expected.audio == null || actual.audio == null)
            {
                continue;
            }

            double referenceRms = rms(expected.audio);
            if(referenceRms < 1.0e-6)
            {
                continue; //mbelib synthesized silence; nothing to correlate
            }

            audibleFrames++;
            double correlation = pearson(expected.audio, actual.audio);
            double voicedFraction = actual.cur.L > 0 ? (double)voicedCount(actual.cur) / actual.cur.L : 0.0;
            int bucket = Math.min(4, (int)(voicedFraction * 5.0));
            bucketCorrSum[bucket] += correlation;
            bucketCount[bucket]++;

            if(correlation > 0.7)
            {
                wellCorrelated++;
            }

            if(voicedFraction >= 0.6 && correlation < worstVoicedCorr)
            {
                worstVoicedCorr = correlation;
                worstVoicedFrame = "frame " + (index + 1) + " L=" + actual.cur.L +
                        " vf=" + String.format(java.util.Locale.US, "%.2f", voicedFraction) +
                        " corr=" + String.format(java.util.Locale.US, "%.3f", correlation);
            }
        }

        System.out.println("=== ProVoice sequential AUDIO correlation (mbelib vs JMBE) ===");
        System.out.println("audible frames=" + audibleFrames + " wellCorrelated(>0.7)=" + wellCorrelated +
                " (" + String.format(java.util.Locale.US, "%.1f", 100.0 * wellCorrelated / Math.max(1, audibleFrames)) + "%)");
        String[] labels = {"0.0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1.0"};
        for(int b = 0; b < 5; b++)
        {
            System.out.println("  voicedFraction " + labels[b] + ": n=" + bucketCount[b] +
                    " meanCorr=" + String.format(java.util.Locale.US, "%.3f", bucketCount[b] > 0 ? bucketCorrSum[b] / bucketCount[b] : 0.0));
        }
        if(worstVoicedFrame != null)
        {
            System.out.println("  worst mostly-voiced frame: " + worstVoicedFrame);
        }

        //Guard against a gross voiced-phase regression. The highly-voiced bucket should retain
        //meaningful positive correlation; a value near zero or negative means voiced phase broke.
        double highlyVoicedMean = bucketCount[4] > 0 ? bucketCorrSum[4] / bucketCount[4] : Double.NaN;
        if(!Double.isNaN(highlyVoicedMean))
        {
            org.junit.Assert.assertTrue("highly-voiced (0.8-1.0) mean correlation regressed below 0.3: " +
                    highlyVoicedMean, highlyVoicedMean >= 0.3);
        }
    }

    /**
     * Compares the low-harmonic synthesis phase (PSIl, and PHIl where deterministic) between mbelib
     * and JMBE. Reads the mbelib phase dump produced by {@code provoice_stage_dump -a} (which now emits
     * {@code cur.PSIl=} / {@code cur.PHIl=} lines).
     *
     * <p>Only harmonics {@code l <= L/4} are checked for PHIl because above that band mbelib derives
     * PHIl from the C {@code rand()} sequence, which JMBE cannot reproduce. PSIl is fully deterministic
     * for all l, so it is checked across all active harmonics. The first divergent low-harmonic phase
     * frame is reported - that is where any voiced-phase bug first appears.</p>
     */
    @Test
    public void compareSequentialPhase() throws Exception
    {
        String hexPath = property("jmbe.provoice.hex", "JMBE_PROVOICE_HEX");
        String phasePath = property("jmbe.mbelib.phase", "JMBE_MBELIB_PHASE");

        if(hexPath == null || phasePath == null)
        {
            System.out.println("Skipped (set -Djmbe.provoice.hex=... and -Djmbe.mbelib.phase=...)");
            return;
        }

        List<String> hexFrames = readHexFrames(hexPath);
        List<PhaseFrame> reference = readReferencePhaseFrames(phasePath);
        assertEquals("frame count", hexFrames.size(), reference.size());

        ProVoiceIMBEAudioCodec codec = new ProVoiceIMBEAudioCodec();
        IMBESynthesizer synthesizer = new IMBESynthesizer();

        int comparedFrames = 0;
        int psilMismatchFrames = 0;
        int philMismatchFrames = 0;
        double maxPsilDelta = 0.0;
        double maxPhilDelta = 0.0;
        String firstPsilDivergence = null;
        String firstPhilDivergence = null;
        int reported = 0;

        for(int index = 0; index < hexFrames.size(); index++)
        {
            DumpFrame actual = decode(codec, synthesizer, hexFrames.get(index));
            PhaseFrame expected = reference.get(index);

            float[] jmbePsil = synthesizer.getCurrentPsilArray();
            float[] jmbePhil = synthesizer.getCurrentPhilArray();

            if(expected.psil == null || actual.cur.L <= 0)
            {
                continue;
            }

            //Skip frames JMBE muted/repeated (audio all-zero) - phase isn't computed there.
            boolean muted = actual.audio != null && rms(actual.audio) < 1.0e-9;
            if(muted)
            {
                continue;
            }

            comparedFrames++;
            int L = actual.cur.L;
            int philBand = L / 4;
            boolean psilBad = false;
            boolean philBad = false;

            for(int l = 1; l <= L && l <= 56; l++)
            {
                double dPsil = phaseDelta(expected.psil[l], jmbePsil[l]);
                if(dPsil > maxPsilDelta)
                {
                    maxPsilDelta = dPsil;
                }
                if(dPsil > 0.01 && !psilBad)
                {
                    psilBad = true;
                    if(firstPsilDivergence == null)
                    {
                        firstPsilDivergence = "frame " + (index + 1) + " l=" + l + " L=" + L +
                                " mbelibPSIl=" + expected.psil[l] + " jmbePSIl=" + jmbePsil[l] +
                                " delta=" + String.format(java.util.Locale.US, "%.5f", dPsil);
                    }
                }

                //PHIl only matters where the harmonic is voiced in the current frame; unvoiced
                //harmonics ignore PHIl entirely during synthesis. Restrict to voiced low harmonics.
                boolean voicedHere = l < actual.cur.vl.length && actual.cur.vl[l] == 1;
                if(l <= philBand && voicedHere)
                {
                    double dPhil = phaseDelta(expected.phil[l], jmbePhil[l]);
                    if(dPhil > maxPhilDelta)
                    {
                        maxPhilDelta = dPhil;
                    }
                    if(dPhil > 0.01 && !philBad)
                    {
                        philBad = true;
                        if(firstPhilDivergence == null)
                        {
                            firstPhilDivergence = "frame " + (index + 1) + " l=" + l + " L=" + L +
                                    " (philBand<=" + philBand + ") mbelibPHIl=" + expected.phil[l] +
                                    " jmbePHIl=" + jmbePhil[l] +
                                    " delta=" + String.format(java.util.Locale.US, "%.5f", dPhil);
                        }
                    }
                }
            }

            if(psilBad)
            {
                psilMismatchFrames++;
            }
            if(philBad)
            {
                philMismatchFrames++;
                if(reported < 8)
                {
                    reported++;
                    System.out.println("  PHIl mismatch frame " + (index + 1) + " L=" + L +
                            " philBand<=" + philBand + " l1: mbelibPHIl=" + expected.phil[1] +
                            " jmbePHIl=" + jmbePhil[1] + " mbelibPSIl=" + expected.psil[1] +
                            " jmbePSIl=" + jmbePsil[1]);
                }
            }
        }

        System.out.println("=== ProVoice sequential PHASE comparison (mbelib vs JMBE) ===");
        System.out.println("compared frames=" + comparedFrames);
        System.out.println("PSIl: mismatch frames=" + psilMismatchFrames + " maxDelta(rad)=" +
                String.format(java.util.Locale.US, "%.6f", maxPsilDelta));
        System.out.println("  first PSIl divergence: " + (firstPsilDivergence == null ? "none" : firstPsilDivergence));
        System.out.println("PHIl (l<=L/4 only): mismatch frames=" + philMismatchFrames + " maxDelta(rad)=" +
                String.format(java.util.Locale.US, "%.6f", maxPhilDelta));
        System.out.println("  first PHIl divergence: " + (firstPhilDivergence == null ? "none" : firstPhilDivergence));
    }

    private static double phaseDelta(float a, float b)
    {
        double d = a - b;
        d = d % (2.0 * Math.PI);
        if(d > Math.PI)
        {
            d -= 2.0 * Math.PI;
        }
        else if(d < -Math.PI)
        {
            d += 2.0 * Math.PI;
        }
        return Math.abs(d);
    }

    private static List<PhaseFrame> readReferencePhaseFrames(String path) throws Exception
    {
        List<PhaseFrame> frames = new ArrayList<>();
        PhaseFrame current = null;

        for(String line : Files.readAllLines(Paths.get(path), StandardCharsets.ISO_8859_1))
        {
            line = line.trim();
            if(line.startsWith("FRAME "))
            {
                current = new PhaseFrame();
                frames.add(current);
            }
            else if(current != null && line.startsWith("cur.PSIl="))
            {
                current.psil = parsePhaseArray(line.substring("cur.PSIl=".length()).trim());
            }
            else if(current != null && line.startsWith("cur.PHIl="))
            {
                current.phil = parsePhaseArray(line.substring("cur.PHIl=".length()).trim());
            }
        }

        return frames;
    }

    private static float[] parsePhaseArray(String value)
    {
        String[] parts = value.split("\\s+");
        //mbelib emits l=1..56 -> store 1-indexed (slot 0 unused) for direct l lookup
        float[] array = new float[57];
        for(int index = 0; index < parts.length && (index + 1) < array.length; index++)
        {
            array[index + 1] = Float.parseFloat(parts[index]);
        }
        return array;
    }

    private static class PhaseFrame
    {
        float[] psil;
        float[] phil;
    }

    private static int voicedCount(ParamFrame frame)
    {
        int count = 0;
        for(int index = 1; index <= frame.L && index < frame.vl.length; index++)
        {
            if(frame.vl[index] == 1)
            {
                count++;
            }
        }
        return count;
    }

    private static double rms(float[] samples)
    {
        double sum = 0.0;
        for(float sample : samples)
        {
            sum += (double)sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    private static double pearson(float[] a, float[] b)
    {
        int n = Math.min(a.length, b.length);
        double meanA = 0.0;
        double meanB = 0.0;
        for(int i = 0; i < n; i++)
        {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= n;
        meanB /= n;

        double num = 0.0;
        double denomA = 0.0;
        double denomB = 0.0;
        for(int i = 0; i < n; i++)
        {
            double x = a[i] - meanA;
            double y = b[i] - meanB;
            num += x * y;
            denomA += x * x;
            denomB += y * y;
        }

        if(denomA <= 0.0 || denomB <= 0.0)
        {
            return 0.0;
        }
        return num / Math.sqrt(denomA * denomB);
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
        dump.imbe4400Bits = bits(imbe4400, 0, imbe4400.length);
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
        writer.write("imbe4400=" + dump.imbe4400Bits + "\n");
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

    private static List<ReferenceFrame> readReferenceAudioFrames(String path) throws Exception
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
            else if(current != null && line.startsWith("audio="))
            {
                String[] parts = line.substring("audio=".length()).trim().split("\\s+");
                float[] audio = new float[parts.length];
                for(int index = 0; index < parts.length; index++)
                {
                    audio[index] = Float.parseFloat(parts[index]);
                }
                current.audio = audio;
            }
        }

        return frames;
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
        String imbe4400Bits;
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
        float[] audio;
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
