package com.mozartec.capacitor.microphone;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

/**
 * AudioProcessor handles real-time audio processing including FFT analysis and streaming
 * for the Capacitor Microphone plugin. It uses AudioRecord for raw audio access and
 * provides configurable audio analysis and streaming capabilities.
 */
public class AudioProcessor {
    
    /**
     * Callback interface for delivering audio data to the plugin
     */
    public interface AudioDataCallback {
        void onAudioData(byte[] audioData, int sampleRate, int length);
    }
    private static final String TAG = "AudioProcessor";
    
    // Audio configuration
    private AudioRecord audioRecord;
    private Thread audioThread;
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // Analysis configuration
    private AudioAnalysisConfig analysisConfig;
    private boolean analysisEnabled = false;
    
    // Streaming configuration
    private AudioStreamConfig streamConfig;
    private boolean streamingEnabled = false;
    private PluginCall streamCallback;
    private AudioDataCallback audioDataCallback;
    
    // Audio data queue for real-time delivery
    private Queue<byte[]> audioDataQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean deliverAudioData = false;
    
    // Audio data buffers
    private short[] audioBuffer;
    private float[] fftInput;
    private float[] fftOutput;
    private byte[] frequencyData;
    
    // Audio streaming buffers
    private short[] streamingBuffer = new short[0];
    private static final int STREAMING_BUFFER_DURATION_MS = 100; // 100ms chunks like reference code
    
    // FFT processing (placeholder for now - will integrate KissFFT)
    private static final int DEFAULT_FFT_SIZE = 1024;
    private static final float DEFAULT_MIN_DECIBELS = -90.0f;
    private static final float DEFAULT_MAX_DECIBELS = -10.0f;
    
    /**
     * Configuration class for audio analysis parameters
     */
    public static class AudioAnalysisConfig {
        public int fftSize = DEFAULT_FFT_SIZE;
        public float minDecibels = DEFAULT_MIN_DECIBELS;
        public float maxDecibels = DEFAULT_MAX_DECIBELS;
        public float smoothingTimeConstant = 0.4f;
        
        public AudioAnalysisConfig() {}
        
        public AudioAnalysisConfig(int fftSize, float minDecibels, float maxDecibels, float smoothingTimeConstant) {
            this.fftSize = fftSize;
            this.minDecibels = minDecibels;
            this.maxDecibels = maxDecibels;
            this.smoothingTimeConstant = smoothingTimeConstant;
        }
    }
    
    /**
     * Configuration class for audio streaming parameters
     */
    public static class AudioStreamConfig {
        public int sampleRate = 16000; // Default for AssemblyAI
        public int bufferSize = 1024;
        public String format = "int16";
        
        public AudioStreamConfig() {}
        
        public AudioStreamConfig(int sampleRate, int bufferSize, String format) {
            this.sampleRate = sampleRate;
            this.bufferSize = bufferSize;
            this.format = format;
        }
    }
    
    /**
     * Configure audio analysis parameters
     */
    public void configureAnalysis(AudioAnalysisConfig config) {
        this.analysisConfig = config;
        Log.d(TAG, "Analysis configured: FFT size=" + config.fftSize + 
              ", minDecibels=" + config.minDecibels + 
              ", maxDecibels=" + config.maxDecibels);
    }
    
    /**
     * Configure audio streaming parameters
     */
    public void configureStreaming(AudioStreamConfig config) {
        this.streamConfig = config;
        Log.d(TAG, "Streaming configured: sampleRate=" + config.sampleRate + 
              ", bufferSize=" + config.bufferSize + 
              ", format=" + config.format);
    }
    
    /**
     * Start audio analysis
     */
    public boolean startAnalysis() {
        if (analysisConfig == null) {
            analysisConfig = new AudioAnalysisConfig();
        }
        
        analysisEnabled = true;
        
        // Initialize FFT buffers
        fftInput = new float[analysisConfig.fftSize * 2]; // Complex FFT needs real + imaginary
        fftOutput = new float[analysisConfig.fftSize * 2];
        frequencyData = new byte[analysisConfig.fftSize / 2]; // Frequency bins
        
        Log.d(TAG, "Audio analysis started with FFT size: " + analysisConfig.fftSize);
        return true;
    }
    
    /**
     * Stop audio analysis
     */
    public void stopAnalysis() {
        analysisEnabled = false;
        Log.d(TAG, "Audio analysis stopped");
    }
    
    /**
     * Get current frequency data for visualization
     */
    public byte[] getFrequencyData() {
        if (!analysisEnabled || frequencyData == null) {
            return new byte[0];
        }
        
        // Return a copy to avoid concurrent modification
        byte[] result = new byte[frequencyData.length];
        System.arraycopy(frequencyData, 0, result, 0, frequencyData.length);
        return result;
    }
    
    /**
     * Start audio streaming with callback
     */
    public boolean startStreaming(AudioStreamConfig config, PluginCall callback) {
        configureStreaming(config);
        this.streamCallback = callback;
        this.streamingEnabled = true;
        this.deliverAudioData = true;
        
        Log.d(TAG, "Audio streaming started");
        return true;
    }
    
    /**
     * Start audio streaming with data callback
     */
    public boolean startStreaming(AudioStreamConfig config, AudioDataCallback callback) {
        configureStreaming(config);
        this.audioDataCallback = callback;
        this.streamingEnabled = true;
        this.deliverAudioData = true;
        
        Log.d(TAG, "Audio streaming started with data callback");
        return true;
    }
    
    /**
     * Stop audio streaming
     */
    public void stopStreaming() {
        streamingEnabled = false;
        streamCallback = null;
        audioDataCallback = null;
        deliverAudioData = false;
        audioDataQueue.clear();
        Log.d(TAG, "Audio streaming stopped");
    }
    
    /**
     * Start audio processing with AudioRecord
     */
    public boolean startAudioProcessing() {
        if (isProcessing.get()) {
            Log.w(TAG, "Audio processing already running");
            return false;
        }
        
        try {
            // Use default config if not set
            if (streamConfig == null) {
                streamConfig = new AudioStreamConfig();
            }
            
            // Calculate buffer size
            int minBufferSize = AudioRecord.getMinBufferSize(
                streamConfig.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            
            int bufferSize = Math.max(minBufferSize, streamConfig.bufferSize * 2);
            
            // Initialize AudioRecord
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                streamConfig.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                return false;
            }
            
            // Initialize audio buffer
            audioBuffer = new short[streamConfig.bufferSize];
            
            // Start processing thread
            isProcessing.set(true);
            audioThread = new Thread(this::processAudioLoop);
            audioThread.start();
            
            // Start recording
            audioRecord.startRecording();
            
            Log.d(TAG, "Audio processing started successfully");
            return true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for audio recording", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio processing", e);
            return false;
        }
    }
    
    /**
     * Stop audio processing
     */
    public void stopAudioProcessing() {
        isProcessing.set(false);
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }
        
        if (audioThread != null) {
            try {
                audioThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }
        
        // Clean up
        analysisEnabled = false;
        streamingEnabled = false;
        
        Log.d(TAG, "Audio processing stopped");
    }
    
    /**
     * Main audio processing loop
     */
    private void processAudioLoop() {
        Log.d(TAG, "Audio processing loop started");
        
        while (isProcessing.get() && audioRecord != null) {
            try {
                // Read audio data
                int samplesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                
                if (samplesRead > 0) {
                    // Process for analysis if enabled
                    if (analysisEnabled) {
                        processForAnalysis(audioBuffer, samplesRead);
                    }
                    
                    // Process for streaming if enabled
                    if (streamingEnabled && streamCallback != null) {
                        processForStreaming(audioBuffer, samplesRead);
                    }
                } else if (samplesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: " + samplesRead);
                    break;
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error in audio processing loop", e);
                break;
            }
        }
        
        Log.d(TAG, "Audio processing loop ended");
    }
    
    /**
     * Process audio data for analysis (FFT)
     */
    private void processForAnalysis(short[] buffer, int length) {
        if (analysisConfig == null || fftInput == null || fftOutput == null) {
            return;
        }
        
        try {
            // Convert to float and prepare for FFT
            int processLength = Math.min(length, analysisConfig.fftSize);
            
            // Clear the FFT input buffer
            for (int i = 0; i < fftInput.length; i++) {
                fftInput[i] = 0;
            }
            
            // Copy audio data to FFT input (real part only)
            for (int i = 0; i < processLength; i++) {
                fftInput[i * 2] = buffer[i] / 32768.0f; // Real part
                fftInput[i * 2 + 1] = 0; // Imaginary part
            }
            
            // TODO: Integrate KissFFT here
            // For now, simulate FFT with placeholder processing
            performFFTPlaceholder(fftInput, fftOutput);
            
            // Convert FFT output to frequency magnitude data
            convertToFrequencyData(fftOutput, frequencyData);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing audio for analysis", e);
        }
    }
    
    /**
     * Process audio data for streaming
     */
    private void processForStreaming(short[] buffer, int length) {
        if (!deliverAudioData || (streamCallback == null && audioDataCallback == null)) {
            return;
        }
        
        try {
            // Accumulate audio data in streaming buffer
            streamingBuffer = mergeBuffers(streamingBuffer, buffer, length);
            
            // Calculate how much data we need for the desired duration
            int samplesNeeded = (streamConfig.sampleRate * STREAMING_BUFFER_DURATION_MS) / 1000;
            
            // If we have enough data, send it
            if (streamingBuffer.length >= samplesNeeded) {
                // Extract the chunk to send
                short[] chunkToSend = new short[samplesNeeded];
                System.arraycopy(streamingBuffer, 0, chunkToSend, 0, samplesNeeded);
                
                // Remove sent data from buffer
                short[] remainingBuffer = new short[streamingBuffer.length - samplesNeeded];
                System.arraycopy(streamingBuffer, samplesNeeded, remainingBuffer, 0, remainingBuffer.length);
                streamingBuffer = remainingBuffer;
                
                // Convert to byte array (similar to reference code conversion)
                byte[] audioBytes = convertToByteArray(chunkToSend);
                
                // Deliver via callback
                if (audioDataCallback != null) {
                    audioDataCallback.onAudioData(audioBytes, streamConfig.sampleRate, audioBytes.length);
                }
                
                // Also queue for potential retrieval
                audioDataQueue.offer(audioBytes);
                
                // Keep queue size manageable
                while (audioDataQueue.size() > 10) {
                    audioDataQueue.poll();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing audio for streaming", e);
        }
    }
    
    /**
     * Merge two short arrays (similar to mergeBuffers in reference code)
     */
    private short[] mergeBuffers(short[] lhs, short[] rhs, int rhsLength) {
        short[] merged = new short[lhs.length + rhsLength];
        System.arraycopy(lhs, 0, merged, 0, lhs.length);
        System.arraycopy(rhs, 0, merged, lhs.length, rhsLength);
        return merged;
    }
    
    /**
     * Convert short array to byte array (similar to reference code)
     * This mimics the Int16Array to Uint8Array conversion in the reference code
     */
    private byte[] convertToByteArray(short[] shortArray) {
        byte[] byteArray = new byte[shortArray.length * 2];
        for (int i = 0; i < shortArray.length; i++) {
            byteArray[i * 2] = (byte) (shortArray[i] & 0xFF);
            byteArray[i * 2 + 1] = (byte) ((shortArray[i] >> 8) & 0xFF);
        }
        return byteArray;
    }
    
    /**
     * Placeholder FFT implementation until KissFFT integration
     */
    private void performFFTPlaceholder(float[] input, float[] output) {
        // Simple placeholder that simulates FFT magnitude calculation
        // This will be replaced with actual KissFFT integration
        
        int halfSize = input.length / 2;
        for (int i = 0; i < halfSize; i += 2) {
            float real = input[i];
            float imag = input[i + 1];
            float magnitude = (float) Math.sqrt(real * real + imag * imag);
            output[i / 2] = magnitude;
        }
    }
    
    /**
     * Convert FFT output to frequency data suitable for visualization
     */
    private void convertToFrequencyData(float[] fftOutput, byte[] freqData) {
        if (analysisConfig == null || freqData == null) {
            return;
        }
        
        int dataLength = Math.min(fftOutput.length / 4, freqData.length);
        
        for (int i = 0; i < dataLength; i++) {
            float magnitude = fftOutput[i];
            
            // Convert to decibels
            float db = magnitude > 0 ? 20 * (float) Math.log10(magnitude + 1e-10) : analysisConfig.minDecibels;
            
            // Clamp to decibel range
            db = Math.max(analysisConfig.minDecibels, Math.min(analysisConfig.maxDecibels, db));
            
            // Normalize to 0-255 range
            float normalized = (db - analysisConfig.minDecibels) / 
                              (analysisConfig.maxDecibels - analysisConfig.minDecibels);
            
            freqData[i] = (byte) (normalized * 255);
        }
    }
    
    /**
     * Check if audio processing is currently running
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    /**
     * Check if analysis is enabled
     */
    public boolean isAnalysisEnabled() {
        return analysisEnabled;
    }
    
    /**
     * Check if streaming is enabled
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }
    
    /**
     * Get the next audio data from the queue (non-blocking)
     */
    public byte[] getNextAudioData() {
        return audioDataQueue.poll();
    }
    
    /**
     * Check if audio data is available in the queue
     */
    public boolean hasAudioData() {
        return !audioDataQueue.isEmpty();
    }
}