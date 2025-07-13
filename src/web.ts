import { WebPlugin } from '@capacitor/core';

import type { MicrophonePlugin, PermissionStatus, AudioRecording, AudioAnalysisConfig, AudioStreamConfig } from './definitions';
import { StatusMessageTypes } from './status-message-types';

export class MicrophoneWeb extends WebPlugin implements MicrophonePlugin {
  private mediaRecorder: MediaRecorder | null = null;
  private audioChunks: Blob[] = [];
  
  // Audio analysis properties
  private audioContext: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private analysisConfig: AudioAnalysisConfig = {
    fftSize: 1024,
    minDecibels: -90,
    maxDecibels: -10,
    smoothingTimeConstant: 0.4
  };
  
  // Audio streaming properties
  private audioWorkletNode: AudioWorkletNode | null = null;
  private streamCallback: ((audioData: Int16Array) => void) | null = null;

  async checkPermissions(): Promise<PermissionStatus> {
    const permissionStatus = await navigator.permissions.query({ name: 'microphone' as PermissionName });
    return { microphone: permissionStatus.state as 'granted' | 'denied' | 'prompt' };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    try {
      await navigator.mediaDevices.getUserMedia({ audio: true });
      return { microphone: 'granted' };
    } catch {
      return { microphone: 'denied' };
    }
  }

  async startRecording(): Promise<{ status: string }> {
    // Check permission first
    const permissionStatus = await this.checkPermissions();
    if (permissionStatus.microphone !== 'granted') {
      throw StatusMessageTypes.MicrophonePermissionNotGranted;
    }

    // Check if there's already a recording in progress
    if (this.mediaRecorder !== null) {
      throw StatusMessageTypes.RecordingInProgress;
    }

    try {
      const stream = await navigator?.mediaDevices?.getUserMedia({ audio: true });

      // Find a supported MIME type for audio recording
      const getSupportedMimeType = () => {
        // Try these MIME types in order of preference
        const types = ['audio/webm', 'audio/mp4', 'audio/ogg', 'audio/wav'];
        for (const type of types) {
          if (MediaRecorder.isTypeSupported(type)) {
            return type;
          }
        }
        return ''; // Let browser decide default
      };

      const mimeType = getSupportedMimeType();
      this.mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      this.audioChunks = [];

      this.mediaRecorder.ondataavailable = (event: any) => {
        if (event.data.size > 0) {
          this.audioChunks.push(event.data);
        }
      };

      this.mediaRecorder.start();
      return {
        status: StatusMessageTypes.RecordingStared,
      };
    } catch (error) {
      throw StatusMessageTypes.RecordingFailed;
    }
  }

  async stopRecording(): Promise<AudioRecording> {
    return new Promise((resolve, reject) => {
      if (!this.mediaRecorder) {
        reject(StatusMessageTypes.NoRecordingInProgress);
        return;
      }

      this.mediaRecorder.onstop = () => {
        try {
          // Use the actual MIME type that was used for recording
          const mimeType = this.mediaRecorder?.mimeType || 'audio/webm';
          const audioBlob = new Blob(this.audioChunks, { type: mimeType });
          const audioUrl = URL.createObjectURL(audioBlob);
          this.mediaRecorder?.stream?.getTracks().forEach((track) => track.stop());

          // Get duration if possible, or use a more accurate calculation
          let duration = 0;
          try {
            // Better duration estimation - based on audio data size and bit rate
            // This is still an approximation, but better than using sampleRate
            duration = this.audioChunks.length > 0 ? this.audioChunks.reduce((acc, chunk) => acc + chunk.size, 0) : 0;
          } catch (e) {
            console.error('Could not determine audio duration', e);
          }

          const reader = new FileReader();
          reader.onerror = () => {
            this.mediaRecorder = null;
            reject(StatusMessageTypes.FailedToFetchRecording);
          };

          reader.onloadend = () => {
            const base64String = reader.result?.toString().split(',')[1];

            if (!base64String || duration < 0) {
              this.mediaRecorder = null;
              reject(StatusMessageTypes.FailedToFetchRecording);
              return;
            }

            // Determine file extension based on MIME type
            const format = mimeType.includes('webm')
              ? '.webm'
              : mimeType.includes('mp4')
                ? '.mp4'
                : mimeType.includes('ogg')
                  ? '.ogg'
                  : mimeType.includes('wav')
                    ? '.wav'
                    : '.webm';

            const recording: AudioRecording = {
              base64String,
              dataUrl: `data:${mimeType};base64,${base64String}`,
              webPath: audioUrl,
              duration,
              format,
              mimeType,
            };

            this.mediaRecorder = null;
            resolve(recording);
          };

          reader.readAsDataURL(audioBlob);
        } catch (error) {
          this.mediaRecorder = null;
          reject(StatusMessageTypes.FailedToFetchRecording);
        }
      };

      try {
        this.mediaRecorder.stop();
      } catch (error) {
        this.mediaRecorder = null;
        reject(StatusMessageTypes.FailedToFetchRecording);
      }
    });
  }

  async getLiveStream(): Promise<MediaStream | null> {
    return this.mediaRecorder?.stream || null;
  }

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
    
    // Load AudioWorklet processor inline
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
}
