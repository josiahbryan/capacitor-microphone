# Audio Streaming & Analysis Implementation Plan

## Overview

This document outlines the implementation plan for adding live audio analysis and streaming capabilities to the `@mozartec/capacitor-microphone` plugin. The goal is to enable real-time audio visualization and transcription while maintaining the existing recording functionality.

## Goals

1. **Live Audio Visualization**: Enable real-time frequency analysis for audio visualization components
2. **Live Audio Streaming**: Provide raw audio data stream for transcription services (AssemblyAI)
3. **Cross-Platform Parity**: Ensure consistent behavior across Web, iOS, and Android
4. **Backward Compatibility**: Maintain existing recording API without breaking changes

## Technical Requirements

### Audio Analysis (for Visualization)
- FFT analysis with configurable parameters
- Real-time frequency data output (`Uint8Array`)
- Configurable analysis parameters (fftSize, decibel ranges, smoothing)
- Compatible with existing `LiveAudioVisualizer` component

### Audio Streaming (for Transcription)
- Raw audio data stream (`Int16Array`)
- Configurable sample rate (default: 16kHz for AssemblyAI)
- Real-time audio data callbacks
- Buffering and format conversion

### Platform Requirements
- **Web**: Leverage existing `MediaRecorder` stream with `AnalyserNode`
- **iOS**: Use `AVAudioEngine` with audio taps
- **Android**: Use `AudioRecord` for raw audio access

## API Changes

### New TypeScript Interfaces

```typescript
// Add to definitions.ts

export interface AudioAnalysisConfig {
  fftSize?: 32 | 64 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 8192 | 16384 | 32768;
  minDecibels?: number;
  maxDecibels?: number;
  smoothingTimeConstant?: number;
}

export interface AudioStreamConfig {
  sampleRate?: number; // Default: 16000
  bufferSize?: number; // Default: 1024
  format?: 'int16' | 'float32'; // Default: 'int16'
}

export interface MicrophonePlugin {
  // ... existing methods

  /**
   * Get live MediaStream for web-based visualization
   * @returns MediaStream | null
   * @since 0.1.0
   */
  getLiveStream(): Promise<MediaStream | null>;

  /**
   * Configure audio analysis parameters
   * @param config Analysis configuration
   * @since 0.1.0
   */
  configureAnalysis(config: AudioAnalysisConfig): Promise<void>;

  /**
   * Start audio analysis for visualization
   * @since 0.1.0
   */
  startAnalysis(): Promise<void>;

  /**
   * Stop audio analysis
   * @since 0.1.0
   */
  stopAnalysis(): Promise<void>;

  /**
   * Get real-time frequency data for visualization
   * @returns Uint8Array frequency data
   * @since 0.1.0
   */
  getFrequencyData(): Promise<Uint8Array>;

  /**
   * Start streaming raw audio data
   * @param config Stream configuration
   * @param callback Audio data callback
   * @since 0.1.0
   */
  startAudioStream(
    config: AudioStreamConfig,
    callback: (audioData: Int16Array) => void
  ): Promise<void>;

  /**
   * Stop audio streaming
   * @since 0.1.0
   */
  stopAudioStream(): Promise<void>;
}
```

## Implementation Details

### Web Implementation (`src/web.ts`)

#### 1. getLiveStream()
```typescript
async getLiveStream(): Promise<MediaStream | null> {
  return this.mediaRecorder?.stream || null;
}
```

#### 2. Audio Analysis
```typescript
private audioContext: AudioContext | null = null;
private analyser: AnalyserNode | null = null;
private analysisConfig: AudioAnalysisConfig = {
  fftSize: 1024,
  minDecibels: -90,
  maxDecibels: -10,
  smoothingTimeConstant: 0.4
};

async configureAnalysis(config: AudioAnalysisConfig): Promise<void> {
  this.analysisConfig = { ...this.analysisConfig, ...config };
  if (this.analyser) {
    this.analyser.fftSize = this.analysisConfig.fftSize!;
    this.analyser.minDecibels = this.analysisConfig.minDecibels!;
    this.analyser.maxDecibels = this.analysisConfig.maxDecibels!;
    this.analyser.smoothingTimeConstant = this.analysisConfig.smoothingTimeConstant!;
  }
}

async startAnalysis(): Promise<void> {
  if (!this.mediaRecorder?.stream) {
    throw new Error('No active recording stream');
  }

  this.audioContext = new AudioContext();
  this.analyser = this.audioContext.createAnalyser();
  
  // Apply configuration
  await this.configureAnalysis(this.analysisConfig);
  
  const source = this.audioContext.createMediaStreamSource(this.mediaRecorder.stream);
  source.connect(this.analyser);
}

async stopAnalysis(): Promise<void> {
  if (this.audioContext) {
    await this.audioContext.close();
    this.audioContext = null;
  }
  this.analyser = null;
}

async getFrequencyData(): Promise<Uint8Array> {
  if (!this.analyser) {
    throw new Error('Analysis not started');
  }
  
  const data = new Uint8Array(this.analyser.frequencyBinCount);
  this.analyser.getByteFrequencyData(data);
  return data;
}
```

#### 3. Audio Streaming
```typescript
private audioWorkletNode: AudioWorkletNode | null = null;
private streamCallback: ((audioData: Int16Array) => void) | null = null;

async startAudioStream(
  config: AudioStreamConfig,
  callback: (audioData: Int16Array) => void
): Promise<void> {
  if (!this.mediaRecorder?.stream) {
    throw new Error('No active recording stream');
  }

  this.streamCallback = callback;
  
  // Create AudioContext with specified sample rate
  const audioContext = new AudioContext({ sampleRate: config.sampleRate || 16000 });
  
  // Load AudioWorklet processor
  const audioProcessorCode = `
    const MAX_16BIT_INT = 32767;
    
    class AudioProcessor extends AudioWorkletProcessor {
      process(inputs) {
        const input = inputs[0];
        if (!input || !input[0]) return true;
        
        const channelData = input[0];
        const int16Array = new Int16Array(channelData.length);
        
        for (let i = 0; i < channelData.length; i++) {
          int16Array[i] = Math.max(-32767, Math.min(32767, channelData[i] * MAX_16BIT_INT));
        }
        
        this.port.postMessage({ audioData: int16Array });
        return true;
      }
    }
    
    registerProcessor('audio-processor', AudioProcessor);
  `;
  
  const blob = new Blob([audioProcessorCode], { type: 'application/javascript' });
  const audioWorkletUrl = URL.createObjectURL(blob);
  
  await audioContext.audioWorklet.addModule(audioWorkletUrl);
  
  this.audioWorkletNode = new AudioWorkletNode(audioContext, 'audio-processor');
  const source = audioContext.createMediaStreamSource(this.mediaRecorder.stream);
  
  source.connect(this.audioWorkletNode);
  this.audioWorkletNode.connect(audioContext.destination);
  
  this.audioWorkletNode.port.onmessage = (event) => {
    if (this.streamCallback) {
      this.streamCallback(event.data.audioData);
    }
  };
}

async stopAudioStream(): Promise<void> {
  if (this.audioWorkletNode) {
    this.audioWorkletNode.disconnect();
    this.audioWorkletNode = null;
  }
  this.streamCallback = null;
}
```

### Android Implementation

#### 1. Dependencies
Add to `android/build.gradle`:
```gradle
dependencies {
    implementation 'be.tarsos.dsp:core:2.4'  // For FFT analysis
    implementation 'be.tarsos.dsp:jvm:2.4'
}
```

#### 2. Core Audio Processing (`android/src/main/java/com/mozartec/capacitor/microphone/AudioProcessor.java`)
```java
import be.tarsos.dsp.util.fft.FFT;

public class AudioProcessor {
    private AudioRecord audioRecord;
    private Thread audioThread;
    private FFT fft;
    private AudioAnalysisConfig analysisConfig;
    private AudioStreamConfig streamConfig;
    private boolean isProcessing = false;
    
    // Audio analysis variables
    private float[] fftBuffer;
    private float[] magnitudes;
    private byte[] frequencyData;
    
    // Audio streaming variables
    private short[] audioBuffer;
    private PluginCall streamCallback;
    
    public void startAudioProcessing(AudioAnalysisConfig analysisConfig, AudioStreamConfig streamConfig) {
        this.analysisConfig = analysisConfig;
        this.streamConfig = streamConfig;
        
        int sampleRate = streamConfig.sampleRate != null ? streamConfig.sampleRate : 16000;
        int bufferSize = streamConfig.bufferSize != null ? streamConfig.bufferSize : 1024;
        
        // Initialize FFT for analysis
        fft = new FFT(analysisConfig.fftSize);
        fftBuffer = new float[analysisConfig.fftSize * 2];
        magnitudes = new float[analysisConfig.fftSize / 2];
        frequencyData = new byte[analysisConfig.fftSize / 2];
        
        // Initialize audio buffer for streaming
        audioBuffer = new short[bufferSize];
        
        // Setup AudioRecord
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        );
        
        isProcessing = true;
        audioThread = new Thread(this::processAudio);
        audioThread.start();
    }
    
    private void processAudio() {
        audioRecord.startRecording();
        
        while (isProcessing) {
            int samplesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
            
            if (samplesRead > 0) {
                // Process for analysis
                if (analysisConfig != null) {
                    processForAnalysis(audioBuffer, samplesRead);
                }
                
                // Process for streaming
                if (streamCallback != null) {
                    processForStreaming(audioBuffer, samplesRead);
                }
            }
        }
        
        audioRecord.stop();
        audioRecord.release();
    }
    
    private void processForAnalysis(short[] buffer, int length) {
        // Convert to float and apply windowing
        for (int i = 0; i < Math.min(length, analysisConfig.fftSize); i++) {
            fftBuffer[i * 2] = buffer[i] / 32768.0f; // Real part
            fftBuffer[i * 2 + 1] = 0; // Imaginary part
        }
        
        // Perform FFT
        fft.complexForward(fftBuffer);
        
        // Calculate magnitudes
        for (int i = 0; i < magnitudes.length; i++) {
            float real = fftBuffer[i * 2];
            float imag = fftBuffer[i * 2 + 1];
            magnitudes[i] = (float) Math.sqrt(real * real + imag * imag);
        }
        
        // Convert to decibels and scale to byte array
        for (int i = 0; i < frequencyData.length; i++) {
            float db = 20 * (float) Math.log10(magnitudes[i] + 1e-10);
            db = Math.max(analysisConfig.minDecibels, Math.min(analysisConfig.maxDecibels, db));
            frequencyData[i] = (byte) (((db - analysisConfig.minDecibels) / 
                (analysisConfig.maxDecibels - analysisConfig.minDecibels)) * 255);
        }
    }
    
    private void processForStreaming(short[] buffer, int length) {
        // Create array for callback
        short[] audioData = new short[length];
        System.arraycopy(buffer, 0, audioData, 0, length);
        
        // Send to JavaScript via bridge
        JSObject result = new JSObject();
        result.put("audioData", audioData);
        streamCallback.resolve(result);
    }
    
    public byte[] getFrequencyData() {
        return frequencyData != null ? frequencyData.clone() : new byte[0];
    }
    
    public void setStreamCallback(PluginCall callback) {
        this.streamCallback = callback;
    }
    
    public void stopProcessing() {
        isProcessing = false;
        if (audioThread != null) {
            try {
                audioThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

#### 3. Plugin Integration (`android/src/main/java/com/mozartec/capacitor/microphone/MicrophonePlugin.java`)
```java
public class MicrophonePlugin extends Plugin {
    private AudioProcessor audioProcessor;
    
    @PluginMethod
    public void getLiveStream(PluginCall call) {
        // Not supported on Android
        call.resolve(new JSObject().put("stream", null));
    }
    
    @PluginMethod
    public void configureAnalysis(PluginCall call) {
        AudioAnalysisConfig config = new AudioAnalysisConfig();
        config.fftSize = call.getInt("fftSize", 1024);
        config.minDecibels = call.getFloat("minDecibels", -90.0f);
        config.maxDecibels = call.getFloat("maxDecibels", -10.0f);
        config.smoothingTimeConstant = call.getFloat("smoothingTimeConstant", 0.4f);
        
        if (audioProcessor != null) {
            audioProcessor.setAnalysisConfig(config);
        }
        
        call.resolve();
    }
    
    @PluginMethod
    public void startAnalysis(PluginCall call) {
        if (audioProcessor == null) {
            call.reject("No audio processor available");
            return;
        }
        
        audioProcessor.startAnalysis();
        call.resolve();
    }
    
    @PluginMethod
    public void stopAnalysis(PluginCall call) {
        if (audioProcessor != null) {
            audioProcessor.stopAnalysis();
        }
        call.resolve();
    }
    
    @PluginMethod
    public void getFrequencyData(PluginCall call) {
        if (audioProcessor == null) {
            call.reject("No audio processor available");
            return;
        }
        
        byte[] data = audioProcessor.getFrequencyData();
        JSObject result = new JSObject();
        result.put("frequencyData", data);
        call.resolve(result);
    }
    
    @PluginMethod
    public void startAudioStream(PluginCall call) {
        AudioStreamConfig config = new AudioStreamConfig();
        config.sampleRate = call.getInt("sampleRate", 16000);
        config.bufferSize = call.getInt("bufferSize", 1024);
        
        if (audioProcessor == null) {
            audioProcessor = new AudioProcessor();
        }
        
        audioProcessor.setStreamCallback(call);
        audioProcessor.startAudioProcessing(null, config);
        
        JSObject result = new JSObject();
        result.put("status", "started");
        call.resolve(result);
    }
    
    @PluginMethod
    public void stopAudioStream(PluginCall call) {
        if (audioProcessor != null) {
            audioProcessor.stopProcessing();
        }
        call.resolve();
    }
}
```

### iOS Implementation

#### 1. Core Audio Processing (`ios/Plugin/AudioProcessor.swift`)
```swift
import AVFoundation
import Accelerate

class AudioProcessor: NSObject {
    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var analysisConfig: AudioAnalysisConfig
    private var streamConfig: AudioStreamConfig
    
    // Analysis variables
    private var fftSetup: vDSP_DFT_Setup?
    private var fftBuffer: [Float] = []
    private var magnitudes: [Float] = []
    private var frequencyData: [UInt8] = []
    
    // Streaming variables
    private var streamCallback: ((Data) -> Void)?
    
    struct AudioAnalysisConfig {
        var fftSize: Int = 1024
        var minDecibels: Float = -90.0
        var maxDecibels: Float = -10.0
        var smoothingTimeConstant: Float = 0.4
    }
    
    struct AudioStreamConfig {
        var sampleRate: Double = 16000
        var bufferSize: Int = 1024
    }
    
    override init() {
        self.analysisConfig = AudioAnalysisConfig()
        self.streamConfig = AudioStreamConfig()
        super.init()
    }
    
    func configureAnalysis(_ config: AudioAnalysisConfig) {
        self.analysisConfig = config
        setupFFT()
    }
    
    func configureStreaming(_ config: AudioStreamConfig) {
        self.streamConfig = config
    }
    
    private func setupFFT() {
        fftSetup = vDSP_DFT_zrop_CreateSetup(nil, vDSP_Length(analysisConfig.fftSize), .FORWARD)
        fftBuffer = Array(repeating: 0.0, count: analysisConfig.fftSize * 2)
        magnitudes = Array(repeating: 0.0, count: analysisConfig.fftSize / 2)
        frequencyData = Array(repeating: 0, count: analysisConfig.fftSize / 2)
    }
    
    func startAudioProcessing(streamCallback: @escaping (Data) -> Void) {
        self.streamCallback = streamCallback
        
        audioEngine = AVAudioEngine()
        inputNode = audioEngine!.inputNode
        
        let format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: streamConfig.sampleRate,
            channels: 1,
            interleaved: false
        )!
        
        inputNode!.installTap(onBus: 0, bufferSize: AVAudioFrameCount(streamConfig.bufferSize), format: format) { [weak self] buffer, time in
            self?.processAudioBuffer(buffer)
        }
        
        do {
            try audioEngine!.start()
        } catch {
            print("Error starting audio engine: \(error)")
        }
    }
    
    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.int16ChannelData?[0] else { return }
        
        let frameCount = Int(buffer.frameLength)
        let audioData = Data(bytes: channelData, count: frameCount * MemoryLayout<Int16>.size)
        
        // Process for analysis
        processForAnalysis(channelData, frameCount: frameCount)
        
        // Process for streaming
        streamCallback?(audioData)
    }
    
    private func processForAnalysis(_ audioData: UnsafeMutablePointer<Int16>, frameCount: Int) {
        guard let fftSetup = fftSetup else { return }
        
        // Convert to float and prepare for FFT
        let processCount = min(frameCount, analysisConfig.fftSize)
        for i in 0..<processCount {
            fftBuffer[i * 2] = Float(audioData[i]) / 32768.0 // Real part
            fftBuffer[i * 2 + 1] = 0 // Imaginary part
        }
        
        // Perform FFT
        fftBuffer.withUnsafeMutableBytes { bufferPointer in
            let complexBuffer = bufferPointer.bindMemory(to: DSPComplex.self)
            vDSP_DFT_Execute(fftSetup, complexBuffer.baseAddress!, complexBuffer.baseAddress!)
        }
        
        // Calculate magnitudes
        for i in 0..<magnitudes.count {
            let real = fftBuffer[i * 2]
            let imag = fftBuffer[i * 2 + 1]
            magnitudes[i] = sqrt(real * real + imag * imag)
        }
        
        // Convert to decibels and scale to byte array
        for i in 0..<frequencyData.count {
            let db = 20 * log10(magnitudes[i] + 1e-10)
            let clampedDb = max(analysisConfig.minDecibels, min(analysisConfig.maxDecibels, db))
            let normalized = (clampedDb - analysisConfig.minDecibels) / (analysisConfig.maxDecibels - analysisConfig.minDecibels)
            frequencyData[i] = UInt8(normalized * 255)
        }
    }
    
    func getFrequencyData() -> [UInt8] {
        return frequencyData
    }
    
    func stopProcessing() {
        audioEngine?.stop()
        inputNode?.removeTap(onBus: 0)
        audioEngine = nil
        inputNode = nil
        streamCallback = nil
    }
    
    deinit {
        if let fftSetup = fftSetup {
            vDSP_DFT_DestroySetup(fftSetup)
        }
    }
}
```

#### 2. Plugin Integration (`ios/Plugin/MicrophonePlugin.swift`)
```swift
@objc(MicrophonePlugin)
public class MicrophonePlugin: CAPPlugin {
    private var audioProcessor: AudioProcessor?
    
    @objc func getLiveStream(_ call: CAPPluginCall) {
        // Not supported on iOS
        call.resolve(["stream": NSNull()])
    }
    
    @objc func configureAnalysis(_ call: CAPPluginCall) {
        let config = AudioProcessor.AudioAnalysisConfig(
            fftSize: call.getInt("fftSize") ?? 1024,
            minDecibels: call.getFloat("minDecibels") ?? -90.0,
            maxDecibels: call.getFloat("maxDecibels") ?? -10.0,
            smoothingTimeConstant: call.getFloat("smoothingTimeConstant") ?? 0.4
        )
        
        if audioProcessor == nil {
            audioProcessor = AudioProcessor()
        }
        
        audioProcessor?.configureAnalysis(config)
        call.resolve()
    }
    
    @objc func startAnalysis(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.reject("No audio processor available")
            return
        }
        
        call.resolve()
    }
    
    @objc func stopAnalysis(_ call: CAPPluginCall) {
        audioProcessor?.stopProcessing()
        call.resolve()
    }
    
    @objc func getFrequencyData(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.reject("No audio processor available")
            return
        }
        
        let data = audioProcessor.getFrequencyData()
        call.resolve(["frequencyData": data])
    }
    
    @objc func startAudioStream(_ call: CAPPluginCall) {
        let streamConfig = AudioProcessor.AudioStreamConfig(
            sampleRate: call.getDouble("sampleRate") ?? 16000,
            bufferSize: call.getInt("bufferSize") ?? 1024
        )
        
        if audioProcessor == nil {
            audioProcessor = AudioProcessor()
        }
        
        audioProcessor?.configureStreaming(streamConfig)
        audioProcessor?.startAudioProcessing { [weak self] audioData in
            // Convert to Int16Array for JavaScript
            let int16Array = audioData.withUnsafeBytes { bytes in
                Array(bytes.bindMemory(to: Int16.self))
            }
            
            // Send to JavaScript via bridge
            self?.notifyListeners("audioData", data: ["audioData": int16Array])
        }
        
        call.resolve(["status": "started"])
    }
    
    @objc func stopAudioStream(_ call: CAPPluginCall) {
        audioProcessor?.stopProcessing()
        call.resolve()
    }
}
```

## Integration with Existing Recording

### Modified Recording Classes

Both `Microphone.java` and `Microphone.swift` need to be updated to work alongside the new `AudioProcessor` classes:

#### Android Integration
- Modify `Microphone.java` to optionally use `AudioProcessor` instead of `MediaRecorder`
- Add flag to enable/disable live processing during recording
- Ensure both recording and live processing can work simultaneously

#### iOS Integration
- Modify `Microphone.swift` to use `AVAudioEngine` instead of `AVAudioRecorder`
- Use `AVAudioMixerNode` to split audio stream for both recording and live processing
- Maintain compatibility with existing recording format (M4A/AAC)

## Testing Strategy

### Unit Tests
- [ ] Web: MediaRecorder stream access
- [ ] Web: AudioWorklet processing accuracy
- [ ] Android: AudioRecord initialization and data flow
- [ ] iOS: AVAudioEngine setup and processing
- [ ] Cross-platform: API consistency

### Integration Tests
- [ ] Live visualization with real audio input
- [ ] Transcription accuracy with streamed audio
- [ ] Simultaneous recording and streaming
- [ ] Memory usage under continuous processing
- [ ] Audio quality during live processing

### Performance Tests
- [ ] CPU usage during live processing
- [ ] Memory leaks during extended use
- [ ] Audio latency measurements
- [ ] Battery usage impact
- [ ] Device heating under load

## Documentation Updates

### API Documentation
- [ ] Update `README.md` with new methods
- [ ] Add code examples for visualization
- [ ] Add code examples for transcription
- [ ] Document platform-specific limitations

### Migration Guide
- [ ] Backward compatibility notes
- [ ] Upgrade instructions
- [ ] Breaking changes (if any)

## TODO List

### Phase 1: Core Implementation (Week 1-2)
- [ ] **Web Implementation**
  - [ ] Add `getLiveStream()` method
  - [ ] Implement audio analysis methods
  - [ ] Add AudioWorklet-based streaming
  - [ ] Test with existing `LiveAudioVisualizer`

- [ ] **Android Implementation**
  - [ ] Add TarsosDSP dependency
  - [ ] Create `AudioProcessor` class
  - [ ] Implement FFT analysis
  - [ ] Add raw audio streaming
  - [ ] Integrate with existing plugin

- [ ] **iOS Implementation**
  - [ ] Create `AudioProcessor` class
  - [ ] Implement vDSP-based FFT
  - [ ] Add AVAudioEngine integration
  - [ ] Add raw audio streaming
  - [ ] Integrate with existing plugin

### Phase 2: Integration & Testing (Week 3)
- [ ] **Cross-Platform Testing**
  - [ ] Test API consistency across platforms
  - [ ] Verify audio format compatibility
  - [ ] Test simultaneous recording + streaming
  - [ ] Performance benchmarking

- [ ] **Demo Integration**
  - [ ] Update Angular demo with visualization
  - [ ] Add transcription example
  - [ ] Test on physical devices

### Phase 3: Documentation & Release (Week 4)
- [ ] **Documentation**
  - [ ] Update TypeScript definitions
  - [ ] Update README with new APIs
  - [ ] Create migration guide
  - [ ] Add code examples

- [ ] **Release Preparation**
  - [ ] Version bump to 0.1.0
  - [ ] Update changelog
  - [ ] Test npm package distribution
  - [ ] Update CocoaPods spec

### Phase 4: Performance Optimization (Week 5)
- [ ] **Performance Improvements**
  - [ ] Optimize FFT calculations
  - [ ] Reduce memory allocations
  - [ ] Implement audio buffer pooling
  - [ ] Add configurable processing intervals

- [ ] **Advanced Features**
  - [ ] Add audio effects (optional)
  - [ ] Implement noise reduction
  - [ ] Add automatic gain control
  - [ ] Support multiple audio formats

## Timeline Estimate

- **Week 1**: Web implementation + Android AudioProcessor
- **Week 2**: iOS implementation + basic integration
- **Week 3**: Cross-platform testing + bug fixes
- **Week 4**: Documentation + release preparation
- **Week 5**: Performance optimization + advanced features

## Success Criteria

1. **Functional Requirements**
   - [ ] Live audio visualization works on all platforms
   - [ ] Real-time transcription with AssemblyAI
   - [ ] Simultaneous recording and streaming
   - [ ] No breaking changes to existing API

2. **Performance Requirements**
   - [ ] < 100ms audio latency
   - [ ] < 20% CPU usage during processing
   - [ ] No memory leaks during extended use
   - [ ] Stable performance across device types

3. **Quality Requirements**
   - [ ] Audio quality maintains 16kHz sample rate
   - [ ] FFT analysis accuracy matches web standards
   - [ ] Consistent behavior across platforms
   - [ ] Robust error handling and recovery

## Risk Mitigation

### Technical Risks
- **FFT Implementation Complexity**: Use proven libraries (TarsosDSP, vDSP)
- **Performance Issues**: Implement progressive optimization
- **Platform Inconsistencies**: Extensive cross-platform testing

### Project Risks
- **Scope Creep**: Stick to core requirements, defer advanced features
- **Integration Complexity**: Maintain existing API stability
- **Testing Challenges**: Use automated testing where possible

## Dependencies

### External Libraries
- **Android**: TarsosDSP for FFT processing
- **iOS**: vDSP (built-in) for FFT processing
- **Web**: Native Web Audio API

### Development Tools
- **Android**: Android Studio, Gradle
- **iOS**: Xcode, CocoaPods
- **Web**: Node.js, TypeScript, Rollup

### Testing Requirements
- Physical devices for each platform
- Audio input capabilities for testing
- Performance monitoring tools 