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
public class MicrophonePlugin extends Plugin {

    // Permission alias constants
    static final String MICROPHONE = "microphone";

    private Microphone implementation;
    private AudioProcessor audioProcessor;

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
        // Not supported on Android - return null
        call.resolve(new JSObject().put("stream", null));
    }

    @PluginMethod
    public void configureAnalysis(PluginCall call) {
        // TODO: Implement audio analysis configuration
        // For now, just store the configuration
        JSObject config = new JSObject();
        config.put("fftSize", call.getInt("fftSize", 1024));
        config.put("minDecibels", call.getDouble("minDecibels", -90.0));
        config.put("maxDecibels", call.getDouble("maxDecibels", -10.0));
        config.put("smoothingTimeConstant", call.getDouble("smoothingTimeConstant", 0.4));
        
        // TODO: Apply configuration to audioProcessor when implemented
        call.resolve();
    }

    @PluginMethod
    public void startAnalysis(PluginCall call) {
        // TODO: Implement audio analysis startup
        // This will require creating AudioProcessor and starting FFT analysis
        call.reject("Audio analysis not yet implemented on Android");
    }

    @PluginMethod
    public void stopAnalysis(PluginCall call) {
        // TODO: Implement audio analysis stopping
        if (audioProcessor != null) {
            // audioProcessor.stopAnalysis();
        }
        call.resolve();
    }

    @PluginMethod
    public void getFrequencyData(PluginCall call) {
        // TODO: Implement frequency data retrieval
        // This will return FFT analysis results
        call.reject("Audio analysis not yet implemented on Android");
    }

    @PluginMethod
    public void startAudioStream(PluginCall call) {
        // TODO: Implement audio streaming
        // This will require AudioRecord for raw audio access
        JSObject config = new JSObject();
        config.put("sampleRate", call.getInt("sampleRate", 16000));
        config.put("bufferSize", call.getInt("bufferSize", 1024));
        
        call.reject("Audio streaming not yet implemented on Android");
    }

    @PluginMethod
    public void stopAudioStream(PluginCall call) {
        // TODO: Implement audio streaming stop
        if (audioProcessor != null) {
            // audioProcessor.stopStreaming();
        }
        call.resolve();
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
