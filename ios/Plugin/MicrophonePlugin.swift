import Capacitor
import AVFoundation

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(MicrophonePlugin)
public class MicrophonePlugin: CAPPlugin {
    private var implementation: Microphone? = nil
    private var audioProcessor: AudioProcessor? = nil

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
        // Not supported on iOS - return null
        call.resolve(["stream": NSNull()])
    }
    
    @objc func configureAnalysis(_ call: CAPPluginCall) {
        // TODO: Implement audio analysis configuration
        // For now, just store the configuration
        let fftSize = call.getInt("fftSize") ?? 1024
        let minDecibels = call.getFloat("minDecibels") ?? -90.0
        let maxDecibels = call.getFloat("maxDecibels") ?? -10.0
        let smoothingTimeConstant = call.getFloat("smoothingTimeConstant") ?? 0.4
        
        // TODO: Apply configuration to audioProcessor when implemented
        call.resolve()
    }
    
    @objc func startAnalysis(_ call: CAPPluginCall) {
        // TODO: Implement audio analysis startup
        // This will require creating AudioProcessor and starting vDSP FFT analysis
        call.reject("Audio analysis not yet implemented on iOS")
    }
    
    @objc func stopAnalysis(_ call: CAPPluginCall) {
        // TODO: Implement audio analysis stopping
        if let audioProcessor = audioProcessor {
            // audioProcessor.stopAnalysis()
        }
        call.resolve()
    }
    
    @objc func getFrequencyData(_ call: CAPPluginCall) {
        // TODO: Implement frequency data retrieval
        // This will return vDSP FFT analysis results
        call.reject("Audio analysis not yet implemented on iOS")
    }
    
    @objc func startAudioStream(_ call: CAPPluginCall) {
        // TODO: Implement audio streaming
        // This will require AVAudioEngine for raw audio access
        let sampleRate = call.getDouble("sampleRate") ?? 16000
        let bufferSize = call.getInt("bufferSize") ?? 1024
        
        call.reject("Audio streaming not yet implemented on iOS")
    }
    
    @objc func stopAudioStream(_ call: CAPPluginCall) {
        // TODO: Implement audio streaming stop
        if let audioProcessor = audioProcessor {
            // audioProcessor.stopStreaming()
        }
        call.resolve()
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
}
