package com.mozartec.capacitor.microphone;

import android.Manifest;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.FileUtils;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@CapacitorPlugin(
        name = "Microphone",
        permissions = {
                @Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = MicrophonePlugin.MICROPHONE),
        }
)
public class MicrophonePlugin extends Plugin implements AudioProcessor.AudioDataCallback {

    // Permission alias constants
    static final String MICROPHONE = "microphone";

    private Microphone implementation;
    private AudioProcessor audioProcessor;
    
    // Current audio processing state
    private boolean isAudioProcessingActive = false;
    
    @Override
    public void load() {
        super.load();
        implementation = new Microphone();
        audioProcessor = new AudioProcessor();
    }
    
    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        
        // Clean up audio processing
        if (audioProcessor != null) {
            audioProcessor.stopAudioProcessing();
            audioProcessor = null;
        }
        
        // Clean up Microphone implementation
        if (implementation != null) {
            implementation = null;
        }
        
        isAudioProcessingActive = false;
    }
    
    /**
     * AudioDataCallback implementation - receives audio data from AudioProcessor
     */
    @Override
    public void onAudioData(byte[] audioData, int sampleRate, int length) {
        try {
            // Convert byte array to int array for JavaScript compatibility
            int[] intAudioData = new int[audioData.length];
            for (int i = 0; i < audioData.length; i++) {
                intAudioData[i] = audioData[i] & 0xFF; // Convert to unsigned int
            }
            
            // Create event data
            JSObject eventData = new JSObject();
            eventData.put("audioData", intAudioData);
            eventData.put("sampleRate", sampleRate);
            eventData.put("length", length);
            eventData.put("timestamp", System.currentTimeMillis());
            
            // Send event to JavaScript
            notifyListeners("audioData", eventData);
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending audio data event", e);
        }
    }

    // Looks like checkPermissions is available out of the box

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        // Save the call to be able to access it in microphonePermissionsCallback
        bridge.saveCall(call);
        // If the microphone permission is defined in the manifest, then we have to prompt the user
        // or else we will get a security exception when trying to present the microphone. If, however,
        // it is not defined in the manifest then we don't need to prompt and it will just work.
        if (isPermissionDeclared(MICROPHONE)) {
            // just request normally
            super.requestPermissions(call);
        } else {
            // the manifest does not define microphone permissions, so we need to decide what to do
            // first, extract the permissions being requested
            // TODO: (CHECK) We are not even sending permission list (Do we need it ?)
            JSArray providedPerms = call.getArray("permissions");
            List<String> permsList = null;
            try {
                permsList = providedPerms.toList();
            } catch (JSONException e) {
            }

            // TODO: (CHECK) This may not even be needed as till now we only need mic permission
            if (permsList != null && permsList.size() == 1 && permsList.contains(MICROPHONE)) {
                // the only thing being asked for was the microphone so we can just return the current state
                checkPermissions(call);
            } else {
                // we need to ask about microphone so request storage permissions
                // This will break complaining about permission missing in manifest
                requestPermissionForAlias(MICROPHONE, call, "checkPermissions");
            }
        }
    }

    @PermissionCallback
    private void microphonePermissionsCallback(PluginCall call) {
        checkPermissions(call);
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (!isAudioRecordingPermissionGranted()) {
            call.reject(StatusMessageTypes.MicrophonePermissionNotGranted.getValue());
            return;
        }

        if (implementation != null) {
            call.reject(StatusMessageTypes.RecordingInProgress.getValue());
            return;
        }

        try {
            implementation = new Microphone(getContext());
            implementation.startRecording();
            JSObject success = new JSObject();
            success.put("status", StatusMessageTypes.RecordingStared.getValue());
            call.resolve(success);
        } catch (Exception exp) {
            call.reject(StatusMessageTypes.CannotRecordOnThisPhone.getValue());
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        if (implementation == null) {
            call.reject(StatusMessageTypes.NoRecordingInProgress.getValue());
            return;
        }

        try {
            implementation.stopRecording();
            File audioFileUrl = implementation.getOutputFile();
            Uri newUri = Uri.fromFile(audioFileUrl);
            String webURL = FileUtils.getPortablePath(getContext(), bridge.getLocalUrl(), newUri);
            Log.e("webURL", webURL);
            String base64String = readFileAsBase64(audioFileUrl);
            int duration = getAudioFileDuration(audioFileUrl.getAbsolutePath());
            Log.e("duration", duration + "");
            Log.e("newUri", newUri.toString());
            Recording recording = new Recording(
                    base64String,
                    "data:audio/aac;base64," + base64String,
                    newUri.toString(),
                    webURL,
                    duration,
                    ".m4a",
                    "audio/aac"
            );
            if (base64String == null || duration < 0)
                call.reject(StatusMessageTypes.FailedToFetchRecording.getValue());
            else
                call.resolve(recording.toJSObject());
        } catch (Exception exp) {
            call.reject(StatusMessageTypes.FailedToFetchRecording.getValue());
        } finally {
            implementation = null;
        }
    }

    @PluginMethod
    public void getLiveStream(PluginCall call) {
        // Android doesn't support MediaStream like web
        // Instead, provide information about the event-based streaming system
        JSObject result = new JSObject();
        result.put("stream", null);
        result.put("platform", "android");
        result.put("alternativeApproach", "event-based");
        result.put("eventName", "audioData");
        result.put("instructions", "Use startAudioStream() and listen for 'audioData' events for real-time audio data");
        
        call.resolve(result);
    }

    @PluginMethod
    public void configureAnalysis(PluginCall call) {
        try {
            // Extract configuration parameters
            int fftSize = call.getInt("fftSize", 1024);
            float minDecibels = (float) call.getDouble("minDecibels", -90.0);
            float maxDecibels = (float) call.getDouble("maxDecibels", -10.0);
            float smoothingTimeConstant = (float) call.getDouble("smoothingTimeConstant", 0.4);
            
            // Validate FFT size (must be power of 2)
            if ((fftSize & (fftSize - 1)) != 0 || fftSize < 32 || fftSize > 32768) {
                call.reject("Invalid fftSize. Must be a power of 2 between 32 and 32768");
                return;
            }
            
            // Create and configure analysis
            AudioProcessor.AudioAnalysisConfig config = new AudioProcessor.AudioAnalysisConfig(
                fftSize, minDecibels, maxDecibels, smoothingTimeConstant
            );
            
            audioProcessor.configureAnalysis(config);
            
            JSObject result = new JSObject();
            result.put("status", "Audio analysis configured successfully");
            result.put("fftSize", fftSize);
            result.put("minDecibels", minDecibels);
            result.put("maxDecibels", maxDecibels);
            result.put("smoothingTimeConstant", smoothingTimeConstant);
            
            call.resolve(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Error configuring audio analysis", e);
            call.reject("Failed to configure audio analysis: " + e.getMessage());
        }
    }

    @PluginMethod
    public void startAnalysis(PluginCall call) {
        try {
            // Check permissions
            if (!isAudioRecordingPermissionGranted()) {
                call.reject("Audio recording permission not granted");
                return;
            }
            
            // Start audio processing if not already active
            if (!isAudioProcessingActive) {
                if (!audioProcessor.startAudioProcessing()) {
                    call.reject("Failed to start audio processing");
                    return;
                }
                isAudioProcessingActive = true;
            }
            
            // Start analysis
            if (audioProcessor.startAnalysis()) {
                JSObject result = new JSObject();
                result.put("status", "Audio analysis started successfully");
                call.resolve(result);
            } else {
                call.reject("Failed to start audio analysis");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio analysis", e);
            call.reject("Failed to start audio analysis: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopAnalysis(PluginCall call) {
        try {
            if (audioProcessor != null) {
                audioProcessor.stopAnalysis();
                
                // Stop audio processing if neither analysis nor streaming is active
                if (!audioProcessor.isAnalysisEnabled() && !audioProcessor.isStreamingEnabled()) {
                    audioProcessor.stopAudioProcessing();
                    isAudioProcessingActive = false;
                }
                
                JSObject result = new JSObject();
                result.put("status", "Audio analysis stopped successfully");
                call.resolve(result);
            } else {
                call.resolve();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio analysis", e);
            call.reject("Failed to stop audio analysis: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getFrequencyData(PluginCall call) {
        try {
            if (audioProcessor != null && audioProcessor.isAnalysisEnabled()) {
                byte[] frequencyData = audioProcessor.getFrequencyData();
                
                // Convert byte array to int array for JavaScript compatibility
                int[] intFrequencyData = new int[frequencyData.length];
                for (int i = 0; i < frequencyData.length; i++) {
                    intFrequencyData[i] = frequencyData[i] & 0xFF; // Convert to unsigned int
                }
                
                JSObject result = new JSObject();
                result.put("frequencyData", intFrequencyData);
                result.put("length", intFrequencyData.length);
                
                call.resolve(result);
            } else {
                call.reject("Audio analysis not started");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting frequency data", e);
            call.reject("Failed to get frequency data: " + e.getMessage());
        }
    }

    @PluginMethod
    public void startAudioStream(PluginCall call) {
        try {
            // Check permissions
            if (!isAudioRecordingPermissionGranted()) {
                call.reject("Audio recording permission not granted");
                return;
            }
            
            // Extract configuration
            int sampleRate = call.getInt("sampleRate", 16000);
            int bufferSize = call.getInt("bufferSize", 1024);
            String format = call.getString("format", "int16");
            
            // Create streaming config
            AudioProcessor.AudioStreamConfig config = new AudioProcessor.AudioStreamConfig(
                sampleRate, bufferSize, format
            );
            
            // Start audio processing if not already active
            if (!isAudioProcessingActive) {
                audioProcessor.configureStreaming(config);
                if (!audioProcessor.startAudioProcessing()) {
                    call.reject("Failed to start audio processing");
                    return;
                }
                isAudioProcessingActive = true;
            }
            
            // Start streaming with callback
            if (audioProcessor.startStreaming(config, this)) {
                JSObject result = new JSObject();
                result.put("status", "Audio streaming started successfully");
                result.put("sampleRate", sampleRate);
                result.put("bufferSize", bufferSize);
                result.put("format", format);
                result.put("eventName", "audioData");
                call.resolve(result);
            } else {
                call.reject("Failed to start audio streaming");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio stream", e);
            call.reject("Failed to start audio stream: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopAudioStream(PluginCall call) {
        try {
            if (audioProcessor != null) {
                audioProcessor.stopStreaming();
                
                // Stop audio processing if neither analysis nor streaming is active
                if (!audioProcessor.isAnalysisEnabled() && !audioProcessor.isStreamingEnabled()) {
                    audioProcessor.stopAudioProcessing();
                    isAudioProcessingActive = false;
                }
                
                JSObject result = new JSObject();
                result.put("status", "Audio streaming stopped successfully");
                call.resolve(result);
            } else {
                call.resolve();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio stream", e);
            call.reject("Failed to stop audio stream: " + e.getMessage());
        }
    }

    private boolean isAudioRecordingPermissionGranted() {
        return getPermissionState(MICROPHONE) == PermissionState.GRANTED;
    }

    private String readFileAsBase64(File file) {
        BufferedInputStream bns;
        byte[] bArray = new byte[(int) file.length()];
        try {
            bns = new BufferedInputStream(new FileInputStream(file));
            bns.read(bArray);
            bns.close();
        } catch (IOException exp) {
            return null;
        }
        return Base64.encodeToString(bArray, Base64.DEFAULT);
    }

    private int getAudioFileDuration(String filePath) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(filePath);
            mp.prepare();
            return mp.getDuration();
        } catch (Exception ignore) {
            return -1;
        }
    }
}
