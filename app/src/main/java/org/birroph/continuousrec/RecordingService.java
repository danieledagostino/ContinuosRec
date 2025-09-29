package org.birroph.continuousrec;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingService extends Service {
    private final IBinder binder = new LocalBinder();
    private static final String TAG = "RecordingService";
    private boolean running = false;

    private SharedPreferences prefs;

    // audio config
    private int sampleRate = 44100;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

    private Thread recordingThread;
    private long recordingStartTime = 0;
    private int savedCount = 0;

    private volatile boolean currentlyRecordingToFile = false;
    private File currentTempFile;
    private BufferedOutputStream currentOut;
    private long currentRecordedFrames = 0;
    private long silenceCounterMs = 0;
    private boolean hadAboveThreshold = false;

    private volatile double normalizedLevelForWave = 0;

    // ⭐️ Callback per aggiornare l’UI
    public interface LevelCallback {
        void onLevel(float level);
    }

    private LevelCallback levelCallback;

    public void setLevelCallback(LevelCallback cb) {
        this.levelCallback = cb;
    }

    public class LocalBinder extends Binder {
        RecordingService getService() {
            return RecordingService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        stopRecordingLoop();
        super.onDestroy();
    }

    private void startRecordingLoop() {
        recordingThread = new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, Math.max(minBuf, sampleRate * 2));
            short[] buffer = new short[2048];
            recorder.startRecording();

            recordingStartTime = System.currentTimeMillis();

            int frameSec = prefs.getInt("frame_sec", 30);
            int silenceCut = prefs.getInt("silence_cut", 20);
            int thresholdPercent = prefs.getInt("threshold_db", 50);

            long frameMs = frameSec * 1000L;
            long silenceCutMs = silenceCut * 1000L;

            long fileStartMs = 0;
            long lastAboveTs = System.currentTimeMillis();

            try {
                // ⭐️ Apriamo subito un nuovo file
                startNewTempFile();
                fileStartMs = System.currentTimeMillis();
                currentlyRecordingToFile = true;

                while (running) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read <= 0) continue;

                    double rms = 0;
                    for (int i = 0; i < read; i++) {
                        rms += buffer[i] * buffer[i];
                    }
                    rms = Math.sqrt(rms / read);

                    float normalizedLevel = (float) Math.min(1.0, rms / 32768.0);
                    normalizedLevelForWave = normalizedLevel;

                    long now = System.currentTimeMillis();

                    if (levelCallback != null) {
                        levelCallback.onLevel(normalizedLevel);
                    }

                    float thresholdNormalized = thresholdPercent / 100f;
                    boolean above = normalizedLevel >= thresholdNormalized;

                    if (above) {
                        lastAboveTs = now;
                        hadAboveThreshold = true;
                    }

                    // Scriviamo sempre
                    if (currentlyRecordingToFile) {
                        try {
                            byte[] bytes = shortToLittleEndianBytes(buffer, read);
                            currentOut.write(bytes);
                            currentRecordedFrames += read;
                        } catch (IOException e) {
                            Log.e(TAG, "Write error", e);
                        }
                    }

                    // ⭐️ Se file ha raggiunto frameSec -> chiudi e apri subito un nuovo file
                    if (now - fileStartMs >= frameMs) {
                        finalizeCurrentFile(hadAboveThreshold);
                        startNewTempFile();
                        fileStartMs = now;
                        currentlyRecordingToFile = true;
                        hadAboveThreshold = false;
                        lastAboveTs = now;
                    }

                    // ⭐️ Se silenzio prolungato -> chiudi file e apri subito un nuovo file
                    if (now - lastAboveTs >= silenceCutMs) {
                        finalizeCurrentFile(hadAboveThreshold);
                        startNewTempFile();
                        fileStartMs = now;
                        currentlyRecordingToFile = true;
                        hadAboveThreshold = false;
                        lastAboveTs = now;
                    }

                    Thread.sleep(20);
                }
            } catch (Exception e) {
                Log.e(TAG, "Recording loop error", e);
            } finally {
                recorder.stop();
                recorder.release();
            }
        }, "RecordingThread");
        recordingThread.start();
    }


    private void stopRecordingLoop() {
        running = false;
        try {
            if (recordingThread != null) recordingThread.join(500);
        } catch (InterruptedException ignored) {}
    }

    private void startNewTempFile() throws IOException {
        // quando apri il file temporaneo (PCM)
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "continuousrec");
        if (!dir.exists()) dir.mkdirs();
        currentTempFile = File.createTempFile("cr_tmp_", ".pcm", dir);
        currentOut = new BufferedOutputStream(new FileOutputStream(currentTempFile));
        currentRecordedFrames = 0;
        silenceCounterMs = 0;
        hadAboveThreshold = false;
    }

    private void finalizeCurrentFile(boolean hadAudioAboveThreshold) {
        try {
            if (currentOut != null) {
                currentOut.flush();
                currentOut.close();
            }
        } catch (IOException e) { Log.e(TAG, "close", e); }

        if (!hadAudioAboveThreshold) {
            if (currentTempFile != null && currentTempFile.exists()) currentTempFile.delete();
            currentTempFile = null;
            return;
        }

        String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date());
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "continuousrec");
        if (!dir.exists()) dir.mkdirs();
        File wav = new File(dir, name + ".wav");
        try {
            PcmToWav.convert(currentTempFile, wav, sampleRate, 1, 16);
            savedCount++;
        } catch (IOException e) {
            Log.e(TAG, "convert", e);
        }
        if (currentTempFile != null) currentTempFile.delete();
        currentTempFile = null;
    }

    public long getRecordingSeconds() {
        if (!running) return 0;
        return (System.currentTimeMillis() - recordingStartTime) / 1000;
    }

    public int getSavedCount() {
        return savedCount;
    }

    private static byte[] shortToLittleEndianBytes(short[] samples, int length) {
        byte[] out = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            short s = samples[i];
            out[i * 2] = (byte) (s & 0xff);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundServiceWithNotification();

        if (!running) {
            running = true;
            startRecordingLoop();
        }

        return START_STICKY;
    }

    private void startForegroundServiceWithNotification() {
        String channelId = "continuousrec_channel";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "ContinuousRec",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification n = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("ContinuousRec")
                .setContentText("Registrazione in corso...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    public double getNormalizedLevel() {
        return normalizedLevelForWave;
    }

    // Simple PCM->WAV converter helper class (static)
    static class PcmToWav {
        static void convert(File pcmFile, File wavFile, int sampleRate, int channels, int bitsPerSample) throws IOException {
            long totalAudioLen = pcmFile.length();
            long totalDataLen = totalAudioLen + 36;
            long byteRate = sampleRate * channels * bitsPerSample / 8;

            byte[] header = new byte[44];

            long longSampleRate = sampleRate;
            long channelsL = channels;
            long bitsPerSampleL = bitsPerSample;

            // RIFF header
            header[0] = 'R';
            header[1] = 'I';
            header[2] = 'F';
            header[3] = 'F';
            writeInt(header, 4, (int) totalDataLen);
            header[8] = 'W';
            header[9] = 'A';
            header[10] = 'V';
            header[11] = 'E';
            header[12] = 'f';
            header[13] = 'm';
            header[14] = 't';
            header[15] = ' ';
            writeInt(header, 16, 16);
            writeShort(header, 20, (short) 1); // PCM
            writeShort(header, 22, (short) channelsL);
            writeInt(header, 24, (int) longSampleRate);
            writeInt(header, 28, (int) byteRate);
            writeShort(header, 32, (short) (channelsL * bitsPerSampleL / 8));
            writeShort(header, 34, (short) bitsPerSampleL);
            header[36] = 'd';
            header[37] = 'a';
            header[38] = 't';
            header[39] = 'a';
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
}
