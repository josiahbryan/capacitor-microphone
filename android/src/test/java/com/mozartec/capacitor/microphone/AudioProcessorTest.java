package com.mozartec.capacitor.microphone;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioProcessorTest {
    
    @Test
    public void testAudioAnalysisConfig() {
        AudioProcessor.AudioAnalysisConfig config = new AudioProcessor.AudioAnalysisConfig();
        assertEquals(1024, config.fftSize);
        assertEquals(-90.0f, config.minDecibels, 0.01f);
        assertEquals(-10.0f, config.maxDecibels, 0.01f);
        assertEquals(0.4f, config.smoothingTimeConstant, 0.01f);
    }
    
    @Test
    public void testAudioStreamConfig() {
        AudioProcessor.AudioStreamConfig config = new AudioProcessor.AudioStreamConfig();
        assertEquals(16000, config.sampleRate);
        assertEquals(1024, config.bufferSize);
        assertEquals("int16", config.format);
    }
    
    @Test
    public void testCustomAudioAnalysisConfig() {
        AudioProcessor.AudioAnalysisConfig config = new AudioProcessor.AudioAnalysisConfig(
            2048, -80.0f, -20.0f, 0.8f
        );
        assertEquals(2048, config.fftSize);
        assertEquals(-80.0f, config.minDecibels, 0.01f);
        assertEquals(-20.0f, config.maxDecibels, 0.01f);
        assertEquals(0.8f, config.smoothingTimeConstant, 0.01f);
    }
    
    @Test
    public void testCustomAudioStreamConfig() {
        AudioProcessor.AudioStreamConfig config = new AudioProcessor.AudioStreamConfig(
            44100, 2048, "float32"
        );
        assertEquals(44100, config.sampleRate);
        assertEquals(2048, config.bufferSize);
        assertEquals("float32", config.format);
    }
    
    @Test
    public void testAudioProcessorInitialization() {
        AudioProcessor processor = new AudioProcessor();
        assertFalse(processor.isProcessing());
        assertFalse(processor.isAnalysisEnabled());
        assertFalse(processor.isStreamingEnabled());
    }
    
    @Test
    public void testAnalysisConfiguration() {
        AudioProcessor processor = new AudioProcessor();
        AudioProcessor.AudioAnalysisConfig config = new AudioProcessor.AudioAnalysisConfig(
            512, -100.0f, 0.0f, 0.2f
        );
        
        processor.configureAnalysis(config);
        // The configuration should be stored (we can't easily test this without exposing internals)
        
        assertTrue(processor.startAnalysis());
        assertTrue(processor.isAnalysisEnabled());
        
        processor.stopAnalysis();
        assertFalse(processor.isAnalysisEnabled());
    }
    
    @Test
    public void testStreamingConfiguration() {
        AudioProcessor processor = new AudioProcessor();
        AudioProcessor.AudioStreamConfig config = new AudioProcessor.AudioStreamConfig(
            48000, 512, "int16"
        );
        
        processor.configureStreaming(config);
        // The configuration should be stored (we can't easily test this without exposing internals)
        
        // Note: We can't easily test startStreaming without mocking PluginCall
        // but we can test that the configuration is accepted
        assertFalse(processor.isStreamingEnabled()); // Should be false until streaming starts
    }
    
    @Test
    public void testGetFrequencyDataWhenNotAnalyzing() {
        AudioProcessor processor = new AudioProcessor();
        byte[] data = processor.getFrequencyData();
        assertEquals(0, data.length);
    }
    
    @Test
    public void testGetFrequencyDataWhenAnalyzing() {
        AudioProcessor processor = new AudioProcessor();
        processor.startAnalysis();
        
        byte[] data = processor.getFrequencyData();
        // Should return data of length fftSize/2 (default 1024/2 = 512)
        assertEquals(512, data.length);
    }
}