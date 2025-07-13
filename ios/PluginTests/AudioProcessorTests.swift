import XCTest
@testable import Plugin

class AudioProcessorTests: XCTestCase {
    
    var audioProcessor: AudioProcessor!
    
    override func setUp() {
        super.setUp()
        audioProcessor = AudioProcessor()
    }
    
    override func tearDown() {
        audioProcessor.stopAudioProcessing()
        audioProcessor = nil
        super.tearDown()
    }
    
    func testAudioAnalysisConfigDefaults() {
        let config = AudioProcessor.AudioAnalysisConfig()
        XCTAssertEqual(config.fftSize, 1024)
        XCTAssertEqual(config.minDecibels, -90.0, accuracy: 0.01)
        XCTAssertEqual(config.maxDecibels, -10.0, accuracy: 0.01)
        XCTAssertEqual(config.smoothingTimeConstant, 0.4, accuracy: 0.01)
    }
    
    func testAudioStreamConfigDefaults() {
        let config = AudioProcessor.AudioStreamConfig()
        XCTAssertEqual(config.sampleRate, 16000)
        XCTAssertEqual(config.bufferSize, 1024)
        XCTAssertEqual(config.format, "int16")
    }
    
    func testCustomAudioAnalysisConfig() {
        let config = AudioProcessor.AudioAnalysisConfig(
            fftSize: 2048,
            minDecibels: -80.0,
            maxDecibels: -20.0,
            smoothingTimeConstant: 0.8
        )
        XCTAssertEqual(config.fftSize, 2048)
        XCTAssertEqual(config.minDecibels, -80.0, accuracy: 0.01)
        XCTAssertEqual(config.maxDecibels, -20.0, accuracy: 0.01)
        XCTAssertEqual(config.smoothingTimeConstant, 0.8, accuracy: 0.01)
    }
    
    func testCustomAudioStreamConfig() {
        let config = AudioProcessor.AudioStreamConfig(
            sampleRate: 44100,
            bufferSize: 2048,
            format: "float32"
        )
        XCTAssertEqual(config.sampleRate, 44100)
        XCTAssertEqual(config.bufferSize, 2048)
        XCTAssertEqual(config.format, "float32")
    }
    
    func testAudioProcessorInitialization() {
        XCTAssertFalse(audioProcessor.isAudioProcessing)
        XCTAssertFalse(audioProcessor.isAnalysisEnabled)
        XCTAssertFalse(audioProcessor.isStreamingEnabled)
    }
    
    func testAnalysisConfiguration() {
        let config = AudioProcessor.AudioAnalysisConfig(
            fftSize: 512,
            minDecibels: -100.0,
            maxDecibels: 0.0,
            smoothingTimeConstant: 0.2
        )
        
        audioProcessor.configureAnalysis(config)
        // Configuration should be stored (we can't easily test this without exposing internals)
        
        XCTAssertTrue(audioProcessor.startAnalysis())
        XCTAssertTrue(audioProcessor.isAnalysisEnabled)
        
        audioProcessor.stopAnalysis()
        XCTAssertFalse(audioProcessor.isAnalysisEnabled)
    }
    
    func testStreamingConfiguration() {
        let config = AudioProcessor.AudioStreamConfig(
            sampleRate: 48000,
            bufferSize: 512,
            format: "int16"
        )
        
        audioProcessor.configureStreaming(config)
        // Configuration should be stored (we can't easily test this without exposing internals)
        
        // Note: We can't easily test startStreaming without mocking AudioDataCallback
        // but we can test that the configuration is accepted
        XCTAssertFalse(audioProcessor.isStreamingEnabled) // Should be false until streaming starts
    }
    
    func testGetFrequencyDataWhenNotAnalyzing() {
        let data = audioProcessor.getFrequencyData()
        XCTAssertEqual(data.count, 0)
    }
    
    func testGetFrequencyDataWhenAnalyzing() {
        audioProcessor.startAnalysis()
        
        let data = audioProcessor.getFrequencyData()
        // Should return data of length fftSize/2 (default 1024/2 = 512)
        XCTAssertEqual(data.count, 512)
    }
    
    func testMultipleAnalysisStarts() {
        XCTAssertTrue(audioProcessor.startAnalysis())
        XCTAssertTrue(audioProcessor.isAnalysisEnabled)
        
        // Starting again should still return true
        XCTAssertTrue(audioProcessor.startAnalysis())
        XCTAssertTrue(audioProcessor.isAnalysisEnabled)
        
        audioProcessor.stopAnalysis()
        XCTAssertFalse(audioProcessor.isAnalysisEnabled)
    }
    
    func testAnalysisConfigurationValidation() {
        // Test with different FFT sizes
        let validConfig = AudioProcessor.AudioAnalysisConfig(fftSize: 256, minDecibels: -90, maxDecibels: -10, smoothingTimeConstant: 0.5)
        audioProcessor.configureAnalysis(validConfig)
        
        XCTAssertTrue(audioProcessor.startAnalysis())
        XCTAssertTrue(audioProcessor.isAnalysisEnabled)
        
        // Test frequency data size matches FFT size / 2
        let data = audioProcessor.getFrequencyData()
        XCTAssertEqual(data.count, 128) // 256 / 2
    }
    
    // Note: Audio processing tests that require actual audio hardware/permissions
    // would need to be run in a more integrated environment
    func testAudioProcessingLifecycle() {
        // Test the basic lifecycle without actual audio processing
        // (would require microphone permissions and hardware)
        XCTAssertFalse(audioProcessor.isAudioProcessing)
        
        // Stop should be safe to call even when not processing
        audioProcessor.stopAudioProcessing()
        XCTAssertFalse(audioProcessor.isAudioProcessing)
    }
}