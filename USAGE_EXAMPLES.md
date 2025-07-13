# Usage Examples for Audio Streaming & Analysis

This document provides practical examples of how to use the new audio streaming and analysis features in the Capacitor Microphone plugin.

## Basic Audio Analysis (Web)

```typescript
import { Microphone } from '@mozartec/capacitor-microphone';

// Configure analysis parameters
await Microphone.configureAnalysis({
  fftSize: 1024,
  minDecibels: -90,
  maxDecibels: -10,
  smoothingTimeConstant: 0.4
});

// Start recording first
await Microphone.startRecording();

// Start analysis
await Microphone.startAnalysis();

// Get frequency data for visualization
const getFrequencyData = async () => {
  const frequencyData = await Microphone.getFrequencyData();
  // frequencyData is a Uint8Array with frequency magnitudes (0-255)
  console.log('Frequency data:', frequencyData);
};

// Call periodically for real-time visualization
const interval = setInterval(getFrequencyData, 100);

// Stop analysis
await Microphone.stopAnalysis();
await Microphone.stopRecording();
clearInterval(interval);
```

## Audio Streaming for Transcription

```typescript
import { Microphone } from '@mozartec/capacitor-microphone';

// Start recording
await Microphone.startRecording();

// Configure streaming for AssemblyAI (16kHz)
const streamConfig = {
  sampleRate: 16000,
  bufferSize: 1024,
  format: 'int16' as const
};

// Start streaming with callback
await Microphone.startAudioStream(streamConfig, (audioData: Int16Array) => {
  // audioData contains raw audio samples
  console.log(`Received ${audioData.length} samples`);
  
  // Send to transcription service
  sendToTranscriptionService(audioData);
});

// Stop streaming
await Microphone.stopAudioStream();
await Microphone.stopRecording();
```

## Live Audio Visualization

```typescript
import { Microphone } from '@mozartec/capacitor-microphone';

class AudioVisualizer {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private animationId: number | null = null;

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d')!;
  }

  async startVisualization() {
    // Configure for visualization
    await Microphone.configureAnalysis({
      fftSize: 2048,
      minDecibels: -90,
      maxDecibels: -10,
      smoothingTimeConstant: 0.8
    });

    // Start recording and analysis
    await Microphone.startRecording();
    await Microphone.startAnalysis();

    // Start visualization loop
    this.visualize();
  }

  private async visualize() {
    const frequencyData = await Microphone.getFrequencyData();
    
    // Clear canvas
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    
    // Draw frequency bars
    const barWidth = this.canvas.width / frequencyData.length;
    let x = 0;
    
    for (let i = 0; i < frequencyData.length; i++) {
      const barHeight = (frequencyData[i] / 255) * this.canvas.height;
      
      // Color based on frequency intensity
      const red = Math.floor(frequencyData[i] * 2);
      const green = Math.floor(frequencyData[i] * 0.5);
      const blue = Math.floor(frequencyData[i] * 0.2);
      
      this.ctx.fillStyle = `rgb(${red}, ${green}, ${blue})`;
      this.ctx.fillRect(x, this.canvas.height - barHeight, barWidth, barHeight);
      
      x += barWidth;
    }
    
    // Continue animation
    this.animationId = requestAnimationFrame(() => this.visualize());
  }

  async stopVisualization() {
    if (this.animationId) {
      cancelAnimationFrame(this.animationId);
      this.animationId = null;
    }
    
    await Microphone.stopAnalysis();
    await Microphone.stopRecording();
  }
}

// Usage
const canvas = document.getElementById('visualizer') as HTMLCanvasElement;
const visualizer = new AudioVisualizer(canvas);

// Start visualization
await visualizer.startVisualization();

// Stop after 10 seconds
setTimeout(() => visualizer.stopVisualization(), 10000);
```

## Simultaneous Analysis and Streaming

```typescript
import { Microphone } from '@mozartec/capacitor-microphone';

class AudioProcessor {
  private isProcessing = false;
  private visualizationInterval: NodeJS.Timeout | null = null;

  async startProcessing() {
    if (this.isProcessing) return;
    
    // Start recording
    await Microphone.startRecording();
    
    // Configure analysis for visualization
    await Microphone.configureAnalysis({
      fftSize: 1024,
      minDecibels: -90,
      maxDecibels: -10,
      smoothingTimeConstant: 0.4
    });
    
    // Start analysis
    await Microphone.startAnalysis();
    
    // Start streaming for transcription
    await Microphone.startAudioStream({
      sampleRate: 16000,
      bufferSize: 1024,
      format: 'int16'
    }, this.handleAudioData.bind(this));
    
    // Start visualization loop
    this.visualizationInterval = setInterval(async () => {
      const frequencyData = await Microphone.getFrequencyData();
      this.updateVisualization(frequencyData);
    }, 100);
    
    this.isProcessing = true;
  }

  private handleAudioData(audioData: Int16Array) {
    // Process audio data for transcription
    console.log(`Processing ${audioData.length} samples`);
    
    // Send to transcription service
    this.sendToTranscriptionService(audioData);
  }

  private updateVisualization(frequencyData: Uint8Array) {
    // Update your visualization here
    console.log('Updating visualization with', frequencyData.length, 'frequency bins');
  }

  private sendToTranscriptionService(audioData: Int16Array) {
    // Implementation for sending to transcription service
    // This would typically involve buffering and sending to AssemblyAI, etc.
  }

  async stopProcessing() {
    if (!this.isProcessing) return;
    
    // Stop visualization
    if (this.visualizationInterval) {
      clearInterval(this.visualizationInterval);
      this.visualizationInterval = null;
    }
    
    // Stop streaming and analysis
    await Microphone.stopAudioStream();
    await Microphone.stopAnalysis();
    await Microphone.stopRecording();
    
    this.isProcessing = false;
  }
}

// Usage
const processor = new AudioProcessor();

// Start processing
await processor.startProcessing();

// Stop after 30 seconds
setTimeout(() => processor.stopProcessing(), 30000);
```

## Error Handling Best Practices

```typescript
import { Microphone } from '@mozartec/capacitor-microphone';

class SafeAudioProcessor {
  async startSafeProcessing() {
    try {
      // Check permissions first
      const permissions = await Microphone.checkPermissions();
      if (permissions.microphone !== 'granted') {
        const result = await Microphone.requestPermissions();
        if (result.microphone !== 'granted') {
          throw new Error('Microphone permission denied');
        }
      }

      // Start recording
      await Microphone.startRecording();

      // Configure analysis with error handling
      try {
        await Microphone.configureAnalysis({
          fftSize: 1024,
          minDecibels: -90,
          maxDecibels: -10,
          smoothingTimeConstant: 0.4
        });
        
        await Microphone.startAnalysis();
      } catch (error) {
        console.warn('Analysis not supported:', error);
        // Continue without analysis
      }

      // Start streaming with error handling
      try {
        await Microphone.startAudioStream({
          sampleRate: 16000,
          bufferSize: 1024,
          format: 'int16'
        }, this.handleAudioData.bind(this));
      } catch (error) {
        console.warn('Audio streaming not supported:', error);
        // Continue without streaming
      }

    } catch (error) {
      console.error('Failed to start audio processing:', error);
      // Clean up on error
      await this.cleanup();
    }
  }

  private handleAudioData(audioData: Int16Array) {
    try {
      // Process audio data safely
      if (audioData && audioData.length > 0) {
        this.processAudioData(audioData);
      }
    } catch (error) {
      console.error('Error processing audio data:', error);
    }
  }

  private processAudioData(audioData: Int16Array) {
    // Your audio processing logic here
    console.log(`Processing ${audioData.length} samples`);
  }

  async cleanup() {
    try {
      await Microphone.stopAudioStream();
    } catch (error) {
      console.warn('Error stopping audio stream:', error);
    }

    try {
      await Microphone.stopAnalysis();
    } catch (error) {
      console.warn('Error stopping analysis:', error);
    }

    try {
      await Microphone.stopRecording();
    } catch (error) {
      console.warn('Error stopping recording:', error);
    }
  }
}
```

## Platform-Specific Considerations

### Web Platform
- All features are fully supported
- Uses Web Audio API for efficient processing
- AudioWorklet provides low-latency streaming
- Requires HTTPS for microphone access

### Android Platform (Coming Soon)
- Uses AudioRecord for raw audio access
- KissFFT library for efficient FFT processing
- Requires RECORD_AUDIO permission
- Optimized for ARM NEON where available

### iOS Platform (Coming Soon)
- Uses AVAudioEngine for real-time processing
- vDSP framework for optimized FFT
- Requires microphone usage description in Info.plist
- Optimized for Apple Silicon and ARM processors

## Performance Tips

1. **Choose appropriate FFT size**: Larger sizes give better frequency resolution but higher latency
2. **Use reasonable update intervals**: 100ms is often sufficient for visualization
3. **Configure sample rates wisely**: 16kHz is good for speech, 44.1kHz for music
4. **Clean up resources**: Always stop analysis and streaming when done
5. **Handle errors gracefully**: Not all platforms support all features

## Next Steps

Once Android and iOS implementations are complete, all examples will work across all platforms with the same API. The Web implementation serves as a reference for the expected behavior on all platforms.