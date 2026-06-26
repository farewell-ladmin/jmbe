package jmbe.codec.imbe;

import org.junit.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone JMBE ProVoice WAV generator. Reads an EDACS-PROVOICE-IMBE7100 .mbe
 * JSON file (sdrtrunk export), decodes every 21-byte packed grid sequentially
 * through {@link ProVoiceIMBEAudioCodec}, and writes the cumulative 8 kHz mono
 * 16-bit PCM audio to a WAV file so it can be listened to independently of
 * sdrtrunk's audio plumbing (segments, squelch, gain, routing).
 *
 * <p>Run with the .mbe path via system property {@code jmbe.mbe.file}.</p>
 */
public class ProVoiceStandaloneWavTest
{
    @Test
    public void generateWavFromMbeFile() throws Exception
    {
        String path = System.getProperty("jmbe.mbe.file");
        System.out.println("jmbe.mbe.file=" + path);
        if(path == null)
        {
            System.out.println("Skipped (set -Djmbe.mbe.file=... to enable)");
            return;
        }

        boolean useProVoice = true;  // default: ProVoice codec
        List<String> hexes = new ArrayList<>();
        if(path.endsWith(".imb"))
        {
            // DSD-FME .imb format: 16-byte header followed by N * 21-byte packed 7x24 IMBE7100 grids
            byte[] raw = Files.readAllBytes(Paths.get(path));
            int headerSize = 16;
            int frameSize = 21;
            int frameCount = (raw.length - headerSize) / frameSize;
            System.out.println("IMB file: " + raw.length + " bytes, header=" + headerSize + ", frames=" + frameCount);
            for(int i = 0; i < frameCount; i++)
            {
                int off = headerSize + i * frameSize;
                StringBuilder sb = new StringBuilder(42);
                for(int b = 0; b < frameSize; b++)
                {
                    sb.append(String.format("%02X", raw[off + b] & 0xFF));
                }
                hexes.add(sb.toString());
            }
        }
        else
        {
            byte[] raw = Files.readAllBytes(Paths.get(path));
            String json = new String(raw, StandardCharsets.UTF_8);
            // If this is a P25 .mbe, route to JMBE's IMBEAudioCodec (P25 decoder) instead of ProVoice
            if(json.contains("\"APCO25-PHASE1\""))
            {
                useProVoice = false;
                System.out.println("P25 .mbe detected — routing to IMBEAudioCodec (P25 path)");
            }
            Pattern hexRe = Pattern.compile("\"hex\"\\s*:\\s*\"([0-9A-Fa-f]+)\"");
            Matcher m = hexRe.matcher(json);
            while(m.find()) hexes.add(m.group(1));
        }

        if(hexes.isEmpty())
        {
            throw new IllegalStateException("No frame hex entries found in " + path);
        }

        ProVoiceIMBEAudioCodec provoiceCodec = useProVoice ? new ProVoiceIMBEAudioCodec() : null;
        IMBEAudioCodec p25Codec = useProVoice ? null : new IMBEAudioCodec();
        List<Byte> pcm = new ArrayList<>(hexes.size() * 4 * 160 * 2);
        int silentFrames = 0;
        int nonzeroFrames = 0;
        float globalMaxAbs = 0f;
        int expectedFrameSize = useProVoice ? 21 : 18;
        for(int i = 0; i < hexes.size(); i++)
        {
            String hx = hexes.get(i);
            byte[] frameBytes = hex(hx);
            if(frameBytes.length != expectedFrameSize)
            {
                throw new IllegalStateException("Frame " + i + " length " + frameBytes.length + " != " + expectedFrameSize);
            }
            float[] audio;
            if(useProVoice)
            {
                // Break out the codec stages to log b0/L/w0 for pitch diagnostics
                boolean[][] grid = provoiceCodec.unpackGrid(frameBytes);
                provoiceCodec.correctC0(grid);
                provoiceCodec.demodulate(grid);
                boolean[] imbe7100 = provoiceCodec.extractData(grid);
                boolean[] imbe4400 = provoiceCodec.convert7100To4400(imbe7100);
                IMBEFrame frame = IMBEFrame.fromImbe4400Data(imbe4400);
                int b0 = frame.getFrame().getInt(new int[]{0,1,2,3,4,5,141,142});
                int L = frame.getFundamentalFrequency().getL();
                float w0 = frame.getFundamentalFrequency().getFrequency();
                float w0Hz = w0 * 8000f / (float)(2*Math.PI);
                audio = provoiceCodec.getAudio(frameBytes);
                if(i < 10 || (i % 200) == 0)
                {
                    System.out.println("frame#" + i + " b0=" + b0 + " L=" + L + " w0=" + w0 + " (" + w0Hz + "Hz) maxAbs=...");
                }
            }
            else
            {
                audio = p25Codec.getAudio(frameBytes);
            }
            float maxAbs = 0f;
            for(float s : audio)
            {
                if(Math.abs(s) > maxAbs) maxAbs = Math.abs(s);
            }
            if(maxAbs > globalMaxAbs) globalMaxAbs = maxAbs;
            if(maxAbs < 1e-6f) silentFrames++; else nonzeroFrames++;
            if(!useProVoice && (i < 5 || (i % 100) == 0))
            {
                System.out.println("frame#" + i + " maxAbs=" + maxAbs + " audioLen=" + audio.length);
            }
            if(useProVoice && (i < 10 || (i % 200) == 0))
            {
                System.out.println("  maxAbs=" + maxAbs + " audioLen=" + audio.length);
            }
            for(float s : audio)
            {
                // JMBE outputs floats roughly in [-1,1]; scale to signed 16-bit
                int v = Math.round(s * 32767f);
                if(v > 32767) v = 32767;
                if(v < -32768) v = -32768;
                pcm.add((byte)(v & 0xFF));
                pcm.add((byte)((v >> 8) & 0xFF));
            }
        }

        byte[] buf = new byte[pcm.size()];
        for(int i = 0; i < buf.length; i++) buf[i] = pcm.get(i);

        AudioFormat fmt = new AudioFormat(8000f, 16, 1, true, false);
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);
        AudioInputStream ais = new AudioInputStream(bais, fmt, buf.length / 2);
        String outPath = System.getProperty("jmbe.mbe.out",
                new File(path).getAbsolutePath().replace(".mbe", ".jmbe.wav").replace(".mbe", ".wav"));
        File out = new File(outPath);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
        long samples = buf.length / 2;
        System.out.println("==== ProVoice JMBE WAV ====");
        System.out.println("input: " + path);
        System.out.println("output: " + outPath);
        System.out.println("frames: " + hexes.size());
        System.out.println("silentFrames: " + silentFrames);
        System.out.println("nonzeroFrames: " + nonzeroFrames);
        System.out.println("globalMaxAbs: " + globalMaxAbs);
        System.out.println("samples: " + samples + "  durationMs: " + (samples * 1000L / 8000L));
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