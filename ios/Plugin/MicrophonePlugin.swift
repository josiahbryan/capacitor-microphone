import Capacitor
import AVFoundation

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(MicrophonePlugin)
public class MicrophonePlugin: CAPPlugin, AudioProcessor.AudioDataCallback {
    private var implementation: Microphone? = nil
    private var audioProcessor: AudioProcessor? = nil
    
    // Current audio processing state
    private var isAudioProcessingActive = false
    
    public override func load() {
        super.load()
        audioProcessor = AudioProcessor()
    }
    
    deinit {
        audioProcessor?.stopAudioProcessing()
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        var result: [String: Any] = [:]
        for permission in MicrophonePermissionType.allCases {
            let state: String
            switch permission {
            case .microphone:
                state = AVCaptureDevice.authorizationStatus(for: .audio).authorizationState
            }
            result[permission.rawValue] = state
        }
        call.resolve(result)
    }
    
    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        // TODO: (CHECK) We are not even sending permission list (Do we need it ?)
        // get the list of desired types, if passed
        let typeList = call.getArray("permissions", String.self)?.compactMap({ (type) -> MicrophonePermissionType? in
            return MicrophonePermissionType(rawValue: type)
        }) ?? []
        // otherwise check everything
        let permissions: [MicrophonePermissionType] = (typeList.count > 0) ? typeList : MicrophonePermissionType.allCases
        // request the permissions
        let group = DispatchGroup()
        for permission in permissions {
            switch permission {
            case .microphone:
                group.enter()
                AVCaptureDevice.requestAccess(for: .audio) { _ in
                    group.leave()
                }
            }
        }
        group.notify(queue: DispatchQueue.main) { [weak self] in
            self?.checkPermissions(call)
        }
    }
    
    @objc func startRecording(_ call: CAPPluginCall) {
        if(!isAudioRecordingPermissionGranted()) {
            call.reject(StatusMessageTypes.microphonePermissionNotGranted.rawValue)
            return
        }
        
        if(implementation != nil) {
            call.reject(StatusMessageTypes.recordingInProgress.rawValue)
            return
        }
        
        implementation = Microphone()
        if(implementation == nil) {
            call.reject(StatusMessageTypes.cannotRecordOnThisPhone.rawValue)
            return
        }
        
        let successfullyStartedRecording = implementation!.startRecording()
        if successfullyStartedRecording == false {
            call.reject(StatusMessageTypes.cannotRecordOnThisPhone.rawValue)
        } else {
            call.resolve(["status": StatusMessageTypes.recordingStared.rawValue])
        }
    }
    
    @objc func stopRecording(_ call: CAPPluginCall) {
        if(implementation == nil) {
            call.reject(StatusMessageTypes.noRecordingInProgress.rawValue)
            return
        }
        
        implementation?.stopRecording()
        
        let audioFileUrl = implementation?.getOutputFile()
        if(audioFileUrl == nil) {
            implementation = nil
            call.reject(StatusMessageTypes.failedToFetchRecording.rawValue)
            return
        }
        
        let webURL = bridge?.portablePath(fromLocalURL: audioFileUrl)
        let base64String = readFileAsBase64(audioFileUrl)
        
        let audioRecording = AudioRecording(
            base64String: base64String,
            dataUrl: (base64String != nil) ? ("data:audio/aac;base64," + base64String!) : nil,
            path: audioFileUrl?.absoluteString,
            webPath: webURL?.path,
            duration: getAudioFileDuration(audioFileUrl),
            format: ".m4a",
            mimeType: "audio/aac"
        )
        implementation = nil
        if audioRecording.base64String == nil || audioRecording.duration < 0 {
            call.reject(StatusMessageTypes.failedToFetchRecording.rawValue)
        } else {
            call.resolve(audioRecording.toDictionary())
        }
    }
    
    @objc func getLiveStream(_ call: CAPPluginCall) {
        // iOS doesn't support MediaStream like web
        // Instead, provide information about the event-based streaming system
        let result: [String: Any] = [
            "stream": NSNull(),
            "platform": "ios",
            "alternativeApproach": "event-based",
            "eventName": "audioData",
            "instructions": "Use startAudioStream() and listen for 'audioData' events for real-time audio data"
        ]
        
        call.resolve(result)
    }
    
    @objc func configureAnalysis(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.reject("AudioProcessor not initialized")
            return
        }
        
        // Extract configuration parameters
        let fftSize = call.getInt("fftSize") ?? 1024
        let minDecibels = call.getFloat("minDecibels") ?? -90.0
        let maxDecibels = call.getFloat("maxDecibels") ?? -10.0
        let smoothingTimeConstant = call.getFloat("smoothingTimeConstant") ?? 0.4
        
        // Validate FFT size (must be power of 2)
        if (fftSize & (fftSize - 1)) != 0 || fftSize < 32 || fftSize > 32768 {
            call.reject("Invalid fftSize. Must be a power of 2 between 32 and 32768")
            return
        }
        
        // Create and configure analysis
        let config = AudioProcessor.AudioAnalysisConfig(
            fftSize: fftSize,
            minDecibels: minDecibels,
            maxDecibels: maxDecibels,
            smoothingTimeConstant: smoothingTimeConstant
        )
        
        audioProcessor.configureAnalysis(config)
        
        let result: [String: Any] = [
            "status": "Audio analysis configured successfully",
            "fftSize": fftSize,
            "minDecibels": minDecibels,
            "maxDecibels": maxDecibels,
            "smoothingTimeConstant": smoothingTimeConstant
        ]
        
        call.resolve(result)
    }
    
    @objc func startAnalysis(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.reject("AudioProcessor not initialized")
            return
        }
        
        // Check permissions
        if !isAudioRecordingPermissionGranted() {
            call.reject("Audio recording permission not granted")
            return
        }
        
        // Start audio processing if not already active
        if !isAudioProcessingActive {
            if !audioProcessor.startAudioProcessing() {
                call.reject("Failed to start audio processing")
                return
            }
            isAudioProcessingActive = true
        }
        
        // Start analysis
        if audioProcessor.startAnalysis() {
            let result: [String: Any] = [
                "status": "Audio analysis started successfully"
            ]
            call.resolve(result)
        } else {
            call.reject("Failed to start audio analysis")
        }
    }
    
    @objc func stopAnalysis(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.resolve()
            return
        }
        
        audioProcessor.stopAnalysis()
        
        // Stop audio processing if neither analysis nor streaming is active
        if !audioProcessor.isAnalysisEnabled && !audioProcessor.isStreamingEnabled {
            audioProcessor.stopAudioProcessing()
            isAudioProcessingActive = false
        }
        
        let result: [String: Any] = [
            "status": "Audio analysis stopped successfully"
        ]
        
        call.resolve(result)
    }
    
    @objc func getFrequencyData(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.reject("AudioProcessor not initialized")
            return
        }
        
        if audioProcessor.isAnalysisEnabled {
            let frequencyData = audioProcessor.getFrequencyData()
            
            // Convert Data to NSArray for JavaScript compatibility
            let intFrequencyData = frequencyData.map { Int($0) }
            
            let result: [String: Any] = [
                "frequencyData": intFrequencyData,
                "length": intFrequencyData.count
            ]
            
            call.resolve(result)
        } else {
            call.reject("Audio analysis not started")
        }
    }
    
    @objc func startAudioStream(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.reject("AudioProcessor not initialized")
            return
        }
        
        // Check permissions
        if !isAudioRecordingPermissionGranted() {
            call.reject("Audio recording permission not granted")
            return
        }
        
        // Extract configuration
        let sampleRate = call.getInt("sampleRate") ?? 16000
        let bufferSize = call.getInt("bufferSize") ?? 1024
        let format = call.getString("format") ?? "int16"
        
        // Create streaming config
        let config = AudioProcessor.AudioStreamConfig(
            sampleRate: sampleRate,
            bufferSize: bufferSize,
            format: format
        )
        
        // Start audio processing if not already active
        if !isAudioProcessingActive {
            audioProcessor.configureStreaming(config)
            if !audioProcessor.startAudioProcessing() {
                call.reject("Failed to start audio processing")
                return
            }
            isAudioProcessingActive = true
        }
        
        // Start streaming with callback
        if audioProcessor.startStreaming(config, callback: self) {
            let result: [String: Any] = [
                "status": "Audio streaming started successfully",
                "sampleRate": sampleRate,
                "bufferSize": bufferSize,
                "format": format,
                "eventName": "audioData"
            ]
            call.resolve(result)
        } else {
            call.reject("Failed to start audio streaming")
        }
    }
    
    @objc func stopAudioStream(_ call: CAPPluginCall) {
        guard let audioProcessor = audioProcessor else {
            call.resolve()
            return
        }
        
        audioProcessor.stopStreaming()
        
        // Stop audio processing if neither analysis nor streaming is active
        if !audioProcessor.isAnalysisEnabled && !audioProcessor.isStreamingEnabled {
            audioProcessor.stopAudioProcessing()
            isAudioProcessingActive = false
        }
        
        let result: [String: Any] = [
            "status": "Audio streaming stopped successfully"
        ]
        
        call.resolve(result)
    }
    
    private func isAudioRecordingPermissionGranted() -> Bool {
        return AVAudioSession.sharedInstance().recordPermission == AVAudioSession.RecordPermission.granted
    }
    
    private func readFileAsBase64(_ filePath: URL?) -> String? {
        if(filePath == nil) {
            return nil
        }
        
        do {
            let fileData = try Data.init(contentsOf: filePath!)
            let fileStream = fileData.base64EncodedString()
            return fileStream
        } catch {}
        
        return nil
    }
    
    private func getAudioFileDuration(_ filePath: URL?) -> Int {
        if filePath == nil {
            return -1
        }
        return Int(CMTimeGetSeconds(AVURLAsset(url: filePath!).duration) * 1000)
    }
    
    // MARK: - AudioDataCallback Implementation
    
    /**
     * AudioDataCallback implementation - receives audio data from AudioProcessor
     */
    public func onAudioData(_ audioData: Data, sampleRate: Int, length: Int) {
        // Convert Data to NSArray for JavaScript compatibility
        let audioArray = audioData.map { Int($0) }
        
        // Create event data
        let eventData: [String: Any] = [
            "audioData": audioArray,
            "sampleRate": sampleRate,
            "length": length,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ]
        
        // Send event to JavaScript
        notifyListeners("audioData", data: eventData)
    }
}
