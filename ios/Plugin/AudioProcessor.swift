import Foundation
import AVFoundation
import Accelerate

/**
 * AudioProcessor handles real-time audio processing including FFT analysis and streaming
 * for the Capacitor Microphone plugin on iOS. It uses AVAudioEngine for raw audio access
 * and vDSP for efficient FFT processing.
 */
@objc public class AudioProcessor: NSObject {
    
    // MARK: - Callback Protocol
    @objc public protocol AudioDataCallback: AnyObject {
        func onAudioData(_ audioData: Data, sampleRate: Int, length: Int)
    }
    
    // MARK: - Configuration Classes
    @objc public class AudioAnalysisConfig: NSObject {
        @objc public var fftSize: Int = 1024
        @objc public var minDecibels: Float = -90.0
        @objc public var maxDecibels: Float = -10.0
        @objc public var smoothingTimeConstant: Float = 0.4
        
        @objc public override init() {
            super.init()
        }
        
        @objc public init(fftSize: Int, minDecibels: Float, maxDecibels: Float, smoothingTimeConstant: Float) {
            self.fftSize = fftSize
            self.minDecibels = minDecibels
            self.maxDecibels = maxDecibels
            self.smoothingTimeConstant = smoothingTimeConstant
            super.init()
        }
    }
    
    @objc public class AudioStreamConfig: NSObject {
        @objc public var sampleRate: Int = 16000  // Default for AssemblyAI
        @objc public var bufferSize: Int = 1024
        @objc public var format: String = "int16"
        
        @objc public override init() {
            super.init()
        }
        
        @objc public init(sampleRate: Int, bufferSize: Int, format: String) {
            self.sampleRate = sampleRate
            self.bufferSize = bufferSize
            self.format = format
            super.init()
        }
    }
    
    // MARK: - Private Properties
    private let audioEngine = AVAudioEngine()
    private var inputNode: AVAudioInputNode?
    private var audioFormat: AVAudioFormat?
    
    // Analysis configuration
    private var analysisConfig: AudioAnalysisConfig?
    private var analysisEnabled = false
    
    // Streaming configuration
    private var streamConfig: AudioStreamConfig?
    private var streamingEnabled = false
    private weak var audioDataCallback: AudioDataCallback?
    
    // Audio processing state
    private var isProcessing = false
    
    // FFT properties
    private var fftSetup: vDSP_DFT_Setup?
    private var fftInput: [Float] = []
    private var fftOutput: [Float] = []
    private var frequencyData: [UInt8] = []
    
    // Audio streaming buffers
    private var streamingBuffer: [Int16] = []
    private let streamingBufferDurationMs: Int = 100  // 100ms chunks like reference code
    private let audioDataQueue = DispatchQueue(label: "com.mozartec.microphone.audiodata", qos: .userInitiated)
    
    // Constants
    private let defaultFFTSize = 1024
    private let defaultMinDecibels: Float = -90.0
    private let defaultMaxDecibels: Float = -10.0
    
    // MARK: - Public Methods
    
    /**
     * Configure audio analysis parameters
     */
    @objc public func configureAnalysis(_ config: AudioAnalysisConfig) {
        self.analysisConfig = config
        
        // Setup FFT if needed
        if fftSetup != nil {
            vDSP_DFT_DestroySetup(fftSetup)
        }
        
        let log2n = vDSP_Length(log2(Double(config.fftSize)))
        fftSetup = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(config.fftSize), vDSP_DFT_FORWARD)
        
        // Initialize FFT buffers
        fftInput = Array(repeating: 0.0, count: config.fftSize * 2)  // Complex data (real + imaginary)
        fftOutput = Array(repeating: 0.0, count: config.fftSize * 2)
        frequencyData = Array(repeating: 0, count: config.fftSize / 2)
        
        print("AudioProcessor: Analysis configured with FFT size: \(config.fftSize)")
    }
    
    /**
     * Configure audio streaming parameters
     */
    @objc public func configureStreaming(_ config: AudioStreamConfig) {
        self.streamConfig = config
        print("AudioProcessor: Streaming configured - sampleRate: \(config.sampleRate), bufferSize: \(config.bufferSize)")
    }
    
    /**
     * Start audio analysis
     */
    @objc public func startAnalysis() -> Bool {
        if analysisConfig == nil {
            analysisConfig = AudioAnalysisConfig()
            configureAnalysis(analysisConfig!)
        }
        
        analysisEnabled = true
        print("AudioProcessor: Audio analysis started")
        return true
    }
    
    /**
     * Stop audio analysis
     */
    @objc public func stopAnalysis() {
        analysisEnabled = false
        print("AudioProcessor: Audio analysis stopped")
    }
    
    /**
     * Get current frequency data for visualization
     */
    @objc public func getFrequencyData() -> Data {
        guard analysisEnabled, !frequencyData.isEmpty else {
            return Data()
        }
        
        // Return a copy to avoid concurrent modification
        return Data(frequencyData)
    }
    
    /**
     * Start audio streaming with callback
     */
    @objc public func startStreaming(_ config: AudioStreamConfig, callback: AudioDataCallback) -> Bool {
        configureStreaming(config)
        self.audioDataCallback = callback
        self.streamingEnabled = true
        
        print("AudioProcessor: Audio streaming started with callback")
        return true
    }
    
    /**
     * Stop audio streaming
     */
    @objc public func stopStreaming() {
        streamingEnabled = false
        audioDataCallback = nil
        streamingBuffer.removeAll()
        print("AudioProcessor: Audio streaming stopped")
    }
    
    /**
     * Start audio processing with AVAudioEngine
     */
    @objc public func startAudioProcessing() -> Bool {
        guard !isProcessing else {
            print("AudioProcessor: Audio processing already running")
            return false
        }
        
        do {
            // Use default config if not set
            if streamConfig == nil {
                streamConfig = AudioStreamConfig()
            }
            
            // Configure audio session
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.record, mode: .measurement, options: [])
            try audioSession.setActive(true)
            
            // Setup audio format
            audioFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Double(streamConfig!.sampleRate),
                channels: 1,
                interleaved: true
            )
            
            guard let format = audioFormat else {
                print("AudioProcessor: Failed to create audio format")
                return false
            }
            
            // Get input node
            inputNode = audioEngine.inputNode
            
            // Install tap on input node
            inputNode?.installTap(onBus: 0, bufferSize: AVAudioFrameCount(streamConfig!.bufferSize), format: format) { [weak self] buffer, time in
                self?.processAudioBuffer(buffer)
            }
            
            // Start audio engine
            audioEngine.prepare()
            try audioEngine.start()
            
            isProcessing = true
            print("AudioProcessor: Audio processing started successfully")
            return true
            
        } catch {
            print("AudioProcessor: Failed to start audio processing: \(error)")
            return false
        }
    }
    
    /**
     * Stop audio processing
     */
    @objc public func stopAudioProcessing() {
        guard isProcessing else { return }
        
        audioEngine.stop()
        inputNode?.removeTap(onBus: 0)
        
        // Deactivate audio session
        do {
            try AVAudioSession.sharedInstance().setActive(false)
        } catch {
            print("AudioProcessor: Error deactivating audio session: \(error)")
        }
        
        isProcessing = false
        analysisEnabled = false
        streamingEnabled = false
        
        print("AudioProcessor: Audio processing stopped")
    }
    
    /**
     * Check if audio processing is currently running
     */
    @objc public var isAudioProcessing: Bool {
        return isProcessing
    }
    
    /**
     * Check if analysis is enabled
     */
    @objc public var isAnalysisEnabled: Bool {
        return analysisEnabled
    }
    
    /**
     * Check if streaming is enabled
     */
    @objc public var isStreamingEnabled: Bool {
        return streamingEnabled
    }
    
    // MARK: - Private Methods
    
    /**
     * Process audio buffer from AVAudioEngine
     */
    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.int16ChannelData else { return }
        
        let frameLength = Int(buffer.frameLength)
        let audioData = Array(UnsafeBufferPointer(start: channelData[0], count: frameLength))
        
        // Process for analysis if enabled
        if analysisEnabled {
            processForAnalysis(audioData)
        }
        
        // Process for streaming if enabled
        if streamingEnabled {
            processForStreaming(audioData)
        }
    }
    
    /**
     * Process audio data for analysis (FFT)
     */
    private func processForAnalysis(_ buffer: [Int16]) {
        guard let config = analysisConfig,
              let setup = fftSetup,
              !fftInput.isEmpty else { return }
        
        let processLength = min(buffer.count, config.fftSize)
        
        // Clear FFT input buffer
        for i in 0..<fftInput.count {
            fftInput[i] = 0.0
        }
        
        // Convert Int16 to Float and fill real part of complex array
        for i in 0..<processLength {
            fftInput[i * 2] = Float(buffer[i]) / 32768.0  // Real part
            fftInput[i * 2 + 1] = 0.0  // Imaginary part
        }
        
        // Perform FFT using vDSP
        performFFT(setup: setup, input: fftInput, output: &fftOutput)
        
        // Convert FFT output to frequency magnitude data
        convertToFrequencyData(fftOutput: fftOutput, freqData: &frequencyData)
    }
    
    /**
     * Process audio data for streaming
     */
    private func processForStreaming(_ buffer: [Int16]) {
        guard streamingEnabled,
              let config = streamConfig,
              audioDataCallback != nil else { return }
        
        audioDataQueue.async { [weak self] in
            guard let self = self else { return }
            
            // Accumulate audio data in streaming buffer
            self.streamingBuffer.append(contentsOf: buffer)
            
            // Calculate how much data we need for the desired duration
            let samplesNeeded = (config.sampleRate * self.streamingBufferDurationMs) / 1000
            
            // If we have enough data, send it
            if self.streamingBuffer.count >= samplesNeeded {
                // Extract the chunk to send
                let chunkToSend = Array(self.streamingBuffer.prefix(samplesNeeded))
                self.streamingBuffer.removeFirst(samplesNeeded)
                
                // Convert to byte array (similar to Android implementation)
                let audioBytes = self.convertToByteArray(chunkToSend)
                
                // Deliver via callback on main queue
                DispatchQueue.main.async {
                    self.audioDataCallback?.onAudioData(audioBytes, sampleRate: config.sampleRate, length: audioBytes.count)
                }
            }
        }
    }
    
    /**
     * Perform FFT using vDSP
     */
    private func performFFT(setup: vDSP_DFT_Setup, input: [Float], output: inout [Float]) {
        // Prepare complex data for vDSP
        let realPart = stride(from: 0, to: input.count, by: 2).map { input[$0] }
        let imagPart = stride(from: 1, to: input.count, by: 2).map { input[$0] }
        
        var realOutput = [Float](repeating: 0.0, count: realPart.count)
        var imagOutput = [Float](repeating: 0.0, count: imagPart.count)
        
        // Perform DFT
        vDSP_DFT_Execute(setup, realPart, imagPart, &realOutput, &imagOutput)
        
        // Calculate magnitudes
        for i in 0..<realOutput.count {
            let magnitude = sqrt(realOutput[i] * realOutput[i] + imagOutput[i] * imagOutput[i])
            if i * 2 < output.count {
                output[i] = magnitude
            }
        }
    }
    
    /**
     * Convert FFT output to frequency data suitable for visualization
     */
    private func convertToFrequencyData(fftOutput: [Float], freqData: inout [UInt8]) {
        guard let config = analysisConfig else { return }
        
        let dataLength = min(fftOutput.count / 4, freqData.count)
        
        for i in 0..<dataLength {
            let magnitude = fftOutput[i]
            
            // Convert to decibels
            let db = magnitude > 0 ? 20 * log10(magnitude + 1e-10) : config.minDecibels
            
            // Clamp to decibel range
            let clampedDb = max(config.minDecibels, min(config.maxDecibels, db))
            
            // Normalize to 0-255 range
            let normalized = (clampedDb - config.minDecibels) / (config.maxDecibels - config.minDecibels)
            
            freqData[i] = UInt8(normalized * 255)
        }
    }
    
    /**
     * Convert Int16 array to byte array (similar to Android implementation)
     */
    private func convertToByteArray(_ shortArray: [Int16]) -> Data {
        var byteArray = Data()
        byteArray.reserveCapacity(shortArray.count * 2)
        
        for short in shortArray {
            byteArray.append(UInt8(short & 0xFF))
            byteArray.append(UInt8((short >> 8) & 0xFF))
        }
        
        return byteArray
    }
    
    // MARK: - Deinitialization
    deinit {
        if let setup = fftSetup {
            vDSP_DFT_DestroySetup(setup)
        }
        stopAudioProcessing()
    }
}