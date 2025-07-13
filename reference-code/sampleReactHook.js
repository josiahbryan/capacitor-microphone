import { useState, useCallback, useRef } from 'react';

/**
 * @typedef {Object} RecorderControls
 * @property {() => void} startRecording - Starts the recording
 * @property {() => void} stopRecording - Stops the recording
 * @property {() => void} togglePauseResume - Toggles pause/resume state
 * @property {Blob} [recordingBlob] - The recorded audio blob
 * @property {boolean} isRecording - Whether recording is in progress
 * @property {boolean} isPaused - Whether recording is paused
 * @property {number} recordingTime - Recording duration in seconds
 * @property {MediaRecorder} [mediaRecorder] - The current MediaRecorder instance
 */

/**
 * @typedef {Object} MediaAudioTrackConstraints
 * @property {string} [deviceId]
 * @property {string} [groupId]
 * @property {boolean} [autoGainControl]
 * @property {number} [channelCount]
 * @property {boolean} [echoCancellation]
 * @property {boolean} [noiseSuppression]
 * @property {number} [sampleRate]
 * @property {number} [sampleSize]
 * @property {function} [onBlobReady] Non-standard callback that is called when blob is ready
 */

/**
 * Custom hook for audio recording functionality
 * @param {MediaAudioTrackConstraints} [audioTrackConstraints] - Takes a {@link https://developer.mozilla.org/en-US/docs/Web/API/MediaTrackSettings#instance_properties_of_audio_tracks subset} of `MediaTrackConstraints` that apply to the audio track
 * @param {(exception: DOMException) => any} [onNotAllowedOrFound] - A method that gets called when the getUserMedia promise is rejected. It receives the DOMException as its input.
 * @param {MediaRecorderOptions} [mediaRecorderOptions] - Options for MediaRecorder
 * @returns {RecorderControls} Controls for the recording
 *
 * @details `startRecording`: Calling this method would result in the recording to start. Sets `isRecording` to true
 * @details `stopRecording`: This results in a recording in progress being stopped and the resulting audio being present in `recordingBlob`. Sets `isRecording` to false
 * @details `togglePauseResume`: Calling this method would pause the recording if it is currently running or resume if it is paused. Toggles the value `isPaused`
 * @details `recordingBlob`: This is the recording blob that is created after `stopRecording` has been called
 * @details `isRecording`: A boolean value that represents whether a recording is currently in progress
 * @details `isPaused`: A boolean value that represents whether a recording in progress is paused
 * @details `recordingTime`: Number of seconds that the recording has gone on. This is updated every second
 * @details `mediaRecorder`: The current mediaRecorder in use
 */
export const useAudioRecorder = ({
	audioTrackConstraints,
	onNotAllowedOrFound,
	mediaRecorderOptions,
	onBlobReady,
	onDataAvailable,
} = {}) => {
	const [isRecording, setIsRecording] = useState(false);
	const [isPaused, setIsPaused] = useState(false);
	const [recordingTime, setRecordingTime] = useState(0);
	const [mediaRecorder, setMediaRecorder] = useState();
	const [timerInterval, setTimerInterval] = useState();
	const [recordingBlob, setRecordingBlob] = useState();
	const chunksRef = useRef([]);
	const cancelPending = useRef(false);

	const _startTimer = useCallback(() => {
		const interval = setInterval(() => {
			setRecordingTime((time) => time + 1);
		}, 1000);
		setTimerInterval(interval);
	}, [setRecordingTime, setTimerInterval]);

	const _stopTimer = useCallback(() => {
		if (timerInterval) {
			clearInterval(timerInterval);
		}
		setTimerInterval(undefined);
	}, [timerInterval, setTimerInterval]);

	/**
	 * Calling this method would result in the recording to start. Sets `isRecording` to true
	 */
	const startRecording = useCallback(() => {
		if (timerInterval) return;

		navigator.mediaDevices
			.getUserMedia({ audio: audioTrackConstraints ?? true })
			.then((stream) => {
				setIsRecording(true);

				const {
					timeSlice, // spell correctors like this one...
					timeslice = timeSlice,
					...optionsRest
				} = mediaRecorderOptions ?? {};

				// onBlobReady is non-standard and used below,
				// not passed to MediaRecorder
				const recorder = new MediaRecorder(stream, optionsRest);
				setMediaRecorder(recorder);
				// eslint-disable-next-line no-console
				console.log('Starting recorder with timeslice', timeslice);
				recorder.start(timeslice);
				_startTimer();

				recorder.addEventListener('dataavailable', (event) => {
					chunksRef.current.push(event.data);
					onDataAvailable?.(event.data);
				});

				recorder.addEventListener('stop', () => {
					// setRecordingBlob(event.data);
					// recorder.stream.getTracks().forEach((t) => t.stop());
					// setMediaRecorder(undefined);

					if (cancelPending.current) {
						cancelPending.current = false;

						chunksRef.current = [];
						recorder.stream.getTracks().forEach((t) => t.stop());
						setMediaRecorder(undefined);
						return;
					}

					const blob = new Blob(chunksRef.current, {
						type: mediaRecorderOptions?.mimeType ?? 'audio/wav',
					});
					setRecordingBlob(blob);
					chunksRef.current = [];
					recorder.stream.getTracks().forEach((t) => t.stop());
					setMediaRecorder(undefined);
					onBlobReady?.(blob);
					onDataAvailable?.(blob);
				});
			})
			.catch((err) => {
				// eslint-disable-next-line no-console
				console.log(err.name, err.message, err.cause);
				onNotAllowedOrFound?.(err);
			});
	}, [
		timerInterval,
		setIsRecording,
		setMediaRecorder,
		_startTimer,
		setRecordingBlob,
		onNotAllowedOrFound,
		mediaRecorderOptions,
		audioTrackConstraints,
		onBlobReady,
		onDataAvailable,
	]);

	/**
	 * Calling this method results in a recording in progress being stopped and the resulting audio being present in `recordingBlob`. Sets `isRecording` to false
	 */
	const stopRecording = useCallback(() => {
		mediaRecorder?.stop();
		_stopTimer();
		setRecordingTime(0);
		setIsRecording(false);
		setIsPaused(false);
	}, [
		mediaRecorder,
		setRecordingTime,
		setIsRecording,
		setIsPaused,
		_stopTimer,
	]);

	const cancelRecording = useCallback(() => {
		cancelPending.current = true;
		stopRecording();
	}, [stopRecording]);

	/**
	 * Calling this method would pause the recording if it is currently running or resume if it is paused. Toggles the value `isPaused`
	 */
	const togglePauseResume = useCallback(() => {
		if (isPaused) {
			setIsPaused(false);
			mediaRecorder?.resume();
			_startTimer();
		} else {
			setIsPaused(true);
			_stopTimer();
			mediaRecorder?.pause();
		}
	}, [mediaRecorder, setIsPaused, _startTimer, _stopTimer, isPaused]);

	return {
		startRecording,
		stopRecording,
		cancelRecording,
		togglePauseResume,
		recordingBlob,
		isRecording,
		isPaused,
		recordingTime,
		mediaRecorder,
	};
};
