package org.birroph.continuousrec;

import android.Manifest;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String PREFS = "continuousrec_prefs";
    private SeekBar sbThreshold;
    private TextView tvThresholdValue;
    private AudioLevelMeter meterPreview;
    private SeekBar sbFrame;
    private TextView tvFrameValue;

    private SeekBar sbSilenceCut;
    private TextView tvSilenceValue;

    private AudioRecord previewRecorder;
    private Thread previewThread;
    private boolean previewRunning = false;


    private SharedPreferences prefs;

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        sbThreshold = findViewById(R.id.sbThreshold);
        tvThresholdValue = findViewById(R.id.tvThresholdValue);
        meterPreview = findViewById(R.id.meterPreview);

        sbFrame = findViewById(R.id.sbFrame);
        tvFrameValue = findViewById(R.id.tvFrameValue);

        sbSilenceCut = findViewById(R.id.sbSilenceCut);
        tvSilenceValue = findViewById(R.id.tvSilenceValue);

        int threshold = prefs.getInt("threshold_db", 50);
        sbThreshold.setProgress(threshold);
        tvThresholdValue.setText(threshold + " dB");
        meterPreview.setThresholdDb(threshold);

        int frame = prefs.getInt("frame_sec", 30);
        sbFrame.setProgress(frame);
        tvFrameValue.setText(frame + " s");

        int silence = prefs.getInt("silence_cut", 20);
        sbSilenceCut.setProgress(silence);
        tvSilenceValue.setText(silence + " s");

        sbThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvThresholdValue.setText(progress + " dB");
                meterPreview.setThresholdDb(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbFrame.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Snap to allowed values: 10,30,60
                int val = progress;
                if (val < 20) val = 10;
                else if (val < 45) val = 30;
                else val = 60;
                sbFrame.setProgress(val);
                tvFrameValue.setText(val + " s");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbSilenceCut.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Snap to allowed values (10..60 step 5)
                int val = Math.max(10, Math.min(60, (progress / 5) * 5));
                sbSilenceCut.setProgress(val);
                tvSilenceValue.setText(val + " s");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbSilenceCut.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int frame = sbFrame.getProgress();
                int val = Math.min(progress, frame); // forza il limite
                sbSilenceCut.setProgress(val);
                tvSilenceValue.setText(val + " s");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });


        startPreviewMic();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            saveAndExit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveAndExit() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt("threshold_db", sbThreshold.getProgress());
        e.putInt("frame_sec", sbFrame.getProgress());
        e.putInt("silence_cut", sbSilenceCut.getProgress());
        e.apply();
        finish();
    }

    @Override
    public void onBackPressed() {
        saveAndExit();
        super.onBackPressed();
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void startPreviewMic() {
        int sampleRate = 44100;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int bufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

        previewRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufSize);

        previewRunning = true;
        previewRecorder.startRecording();

        previewThread = new Thread(() -> {
            short[] buffer = new short[1024];
            while (previewRunning) {
                int read = previewRecorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    double rms = 0;
                    for (int i = 0; i < read; i++) {
                        rms += buffer[i] * buffer[i];
                    }
                    rms = Math.sqrt(rms / read);

                    // normalizza su 0..1 rispetto al massimo possibile (32768)
                                        float level = (float) (rms / 32768.0);

                    // opzionale: calcolo in dB solo per testo/debug
                    double db = 20 * Math.log10(rms / 32768.0 + 1e-6);

                    runOnUiThread(() -> {
                        meterPreview.setLevel(level);
                        tvThresholdValue.setText(String.format("%.1f dB", db)); // debug
                    });

                }
            }
        });
        previewThread.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreviewMic();
    }

    private void stopPreviewMic() {
        previewRunning = false;
        if (previewThread != null) {
            try { previewThread.join(300); } catch (InterruptedException ignored) {}
            previewThread = null;
        }
        if (previewRecorder != null) {
            previewRecorder.stop();
            previewRecorder.release();
            previewRecorder = null;
        }
    }

}