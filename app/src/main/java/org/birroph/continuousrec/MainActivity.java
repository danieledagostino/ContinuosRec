package org.birroph.continuousrec;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private ImageButton btnToggle;
    private TextView tvTimer;
    private TextView tvHeaderCount;
    private AudioWaveformView waveformView;

    private boolean isRecording = false;
    private RecordingService recordingService;
    private boolean bound = false;
    private Handler handler = new Handler();
    private Runnable timerRunnable;

    private SharedPreferences prefs;

    private ActivityResultLauncher<String[]> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggle = findViewById(R.id.btnToggle);
        tvTimer = findViewById(R.id.tvTimer);
        tvHeaderCount = findViewById(R.id.tvHeaderCount);
        waveformView = findViewById(R.id.waveformView); // ⚡ aggiunto

        prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        float savedThreshold = prefs.getInt("threshold_db", 50); // default 50 dB
        waveformView.setThresholdDb(savedThreshold);

        btnToggle.setOnClickListener(v -> toggleRecording());

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    Boolean audio = result.getOrDefault(Manifest.permission.RECORD_AUDIO, false);
                    if (!audio) {
                        Toast.makeText(this, "Permesso microfono necessario", Toast.LENGTH_LONG).show();
                    }
                }
        );

        ensurePermissions();

        // Timer + aggiornamento onda
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording && bound && recordingService != null) {
                    long seconds = recordingService.getRecordingSeconds();
                    tvTimer.setText(formatSeconds(seconds));
                    tvHeaderCount.setText("Registrazioni: " + recordingService.getSavedCount());

                    // 🔥 aggiorna waveform con livello audio
                    double db = recordingService.getLastDbLevel();
                    waveformView.addLevel((float) db);
                }
                handler.postDelayed(this, 200);
            }
        };
        handler.post(timerRunnable);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, RecordingService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
            recordingService = binder.getService();
            bound = true;
            tvHeaderCount.setText("Registrazioni: " + recordingService.getSavedCount());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    private void toggleRecording() {
        if (!isRecording) {
            if (!hasRequiredPermissions()) {
                ensurePermissions();
                return;
            }
            Intent intent = new Intent(this, RecordingService.class);
            ContextCompat.startForegroundService(this, intent);
            isRecording = true;
            btnToggle.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            Intent intent = new Intent(this, RecordingService.class);
            stopService(intent);
            isRecording = false;
            btnToggle.setImageResource(android.R.drawable.ic_media_play);
        }

        waveformView.invalidate();
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions() {
        if (!hasRequiredPermissions()) {
            requestPermissionLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
        }
    }

    private String formatSeconds(long seconds) {
        long s = seconds % 60;
        long m = (seconds / 60) % 60;
        long h = seconds / 3600;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent i = new Intent(this, SettingsActivity.class);
            startActivity(i);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
