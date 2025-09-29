package org.birroph.continuousrec;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioConverter {
    static void convert(File pcmFile, File wavFile, int sampleRate, int channels, int bitsPerSample) throws IOException {
        // Existing PCM->WAV conversion code unchanged
        long totalAudioLen = pcmFile.length();
        long totalDataLen = totalAudioLen + 36;
        long byteRate = sampleRate * channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        long longSampleRate = sampleRate;
        long channelsL = channels;
        long bitsPerSampleL = bitsPerSample;

        // RIFF header
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeInt(header, 4, (int) totalDataLen);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeInt(header, 16, 16);
        writeShort(header, 20, (short) 1); // PCM format
        writeShort(header, 22, (short) channelsL);
        writeInt(header, 24, (int) longSampleRate);
        writeInt(header, 28, (int) byteRate);
        writeShort(header, 32, (short) (channelsL * bitsPerSampleL / 8));
        writeShort(header, 34, (short) bitsPerSampleL);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeInt(header, 40, (int) totalAudioLen);

        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(wavFile));
        out.write(header, 0, 44);

        FileInputStream fi = new FileInputStream(pcmFile);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = fi.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        fi.close();
        out.flush();
        out.close();
    }

    static void convertToM4a(File pcmFile, File m4aFile, int sampleRate, int channels, int bitsPerSample, Context context) throws IOException {
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileInputStream fis = null;

        try {
            // Configure MediaFormat for AAC encoding
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000); // 128 kbps, adjust if needed
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            // Create encoder and configure
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // Create muxer for output file, output format set to MUXER_OUTPUT_MPEG_4 (.m4a)
            muxer = new MediaMuxer(m4aFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            fis = new FileInputStream(pcmFile);
            byte[] inputBuffer = new byte[2048 * 2]; // since 16-bit PCM, 2 bytes per sample
            boolean inputDone = false;
            boolean encoderDone = false;

            int trackIndex = -1;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (!encoderDone) {
                // Feed encoder input if not done
                if (!inputDone) {
                    int inputBufferIndex = encoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer codecInputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        codecInputBuffer.clear();

                        int bytesRead = fis.read(inputBuffer);
                        if (bytesRead == -1) {
                            // End of stream
                            encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codecInputBuffer.put(inputBuffer, 0, bytesRead);
                            long presentationTimeUs = System.nanoTime() / 1000;
                            encoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0);
                        }
                    }
                }

                // Get encoder output
                int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Add track for muxer
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(newFormat);
                    muxer.start();

                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (bufferInfo.size != 0 && trackIndex != -1) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                }
            }

        } catch (IOException e) {
            Log.e("PcmToAac", "Encoding failed", e);
            throw e;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xff);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xff);
        buffer[offset + 2] = (byte) ((value >> 16) & 0xff);
        buffer[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void writeShort(byte[] buffer, int offset, short value) {
        buffer[offset] = (byte) (value & 0xff);
        buffer[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}

