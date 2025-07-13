/* eslint-disable no-restricted-syntax */
/* eslint-disable no-unused-vars */
import { useMicVAD } from '@ricky0123/vad-react';
import { RealtimeTranscriber } from 'assemblyai';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useConditionallyHandleErrors } from 'shared/hooks/useConditionallyHandleErrors';
import { AiRequests } from 'shared/requests/AiRequests';
import { useAuthorization } from 'shared/services/AuthService';
import { jsonSafeStringify } from 'shared/utils/jsonSafeStringify';
import useSWR from 'swr';

let isRecording = null;
let rt;
let microphone;

let mark = Date.now();
const timeSinceLastMark = () => {
	const diff = Date.now() - mark;
	mark = Date.now();
	return `< [${diff}ms diff] ${new Date().toISOString()} >`;
};

function mergeBuffers(lhs, rhs) {
	const mergedBuffer = new Int16Array(lhs.length + rhs.length);
	mergedBuffer.set(lhs, 0);
	mergedBuffer.set(rhs, lhs.length);
	return mergedBuffer;
}

// Storing as a string to read into a blob and load module below
const audioProcessorJs = /* js */ `
/* global registerProcessor, AudioWorkletProcessor */
const MAX_16BIT_INT = 32767;

class AudioProcessor extends AudioWorkletProcessor {
	process(inputs) {
		try {
			const input = inputs[0];
			if (!input) throw new Error('No input');

			const channelData = input[0];
			if (!channelData) throw new Error('No channelData');

			const float32Array = Float32Array.from(channelData);
			const int16Array = Int16Array.from(
				float32Array.map((n) => n * MAX_16BIT_INT),
			);
			const { buffer } = int16Array;
			this.port.postMessage({ audio_data: buffer });

			return true;
		} catch (error) {
			// eslint-disable-next-line no-console
			console.error(error);
			return false;
		}
	}
}

registerProcessor('audio-processor', AudioProcessor);

`;

function createMicrophone() {
	let stream;
	let audioContext;
	let audioWorkletNode;
	let source;
	let audioBufferQueue = new Int16Array(0);
	return {
		async requestPermission() {
			stream = await navigator.mediaDevices.getUserMedia({
				audio: true,
			});
		},
		async startRecording(onAudioCallback) {
			if (!stream)
				stream = await navigator.mediaDevices.getUserMedia({
					audio: true,
				});
			audioContext = new (window.AudioContext ||
				window.webkitAudioContext)({
				sampleRate: 16_000,
				latencyHint: 'balanced',
			});
			source = audioContext.createMediaStreamSource(stream);

			// Load a blob with our audio processor code instead of storing in a separate file.
			// From: https://stackoverflow.com/a/72180421
			const blob = new Blob([audioProcessorJs], {
				type: 'application/javascript',
			});

			const reader = new FileReader();
			reader.readAsDataURL(blob);
			const dataURI = await new Promise((res) => {
				reader.onloadend = () => {
					res(reader.result);
				};
			});

			await audioContext.audioWorklet.addModule(
				dataURI,
				// 'audio-processor.js',
			);
			audioWorkletNode = new window.AudioWorkletNode(
				audioContext,
				'audio-processor',
			);

			source.connect(audioWorkletNode);
			audioWorkletNode.connect(audioContext.destination);
			audioWorkletNode.port.onmessage = (event) => {
				const currentBuffer = new Int16Array(
					event.data.audio_data,
				);
				audioBufferQueue = mergeBuffers(
					audioBufferQueue,
					currentBuffer,
				);

				const bufferDuration =
					(audioBufferQueue.length / audioContext.sampleRate) *
					1000;

				// wait until we have 100ms of audio data
				if (bufferDuration >= 100) {
					const totalSamples = Math.floor(
						audioContext.sampleRate * 0.1,
					);

					const finalBuffer = new Uint8Array(
						audioBufferQueue.subarray(0, totalSamples).buffer,
					);

					audioBufferQueue =
						audioBufferQueue.subarray(totalSamples);
					if (onAudioCallback) onAudioCallback(finalBuffer);
				}
			};
		},
		stopRecording() {
			stream?.getTracks().forEach((track) => track.stop());
			audioContext?.close();
			audioBufferQueue = new Int16Array(0);
		},
	};
}

const stopTranscriptionService = async () => {
	if (microphone) {
		microphone.stopRecording();
		microphone = null;
	} else {
		// eslint-disable-next-line no-console
		console.warn('No microphone to stop');
	}

	if (rt) {
		await rt.close(false);
		rt = null;
	}

	if (isRecording) {
		isRecording = null;
	}
};

// runs real-time transcription and handles global variables
const startTranscriptionService = async ({
	token,
	onTranscript,
	pauseTransmitRef,
	// Default is 700ms per https://www.assemblyai.com/docs/speech-to-text/streaming#authenticate-with-a-temporary-token
	endUtteranceSilenceThreshold = 700,
	onSocketOpened,
	onSocketClosed,
	onSocketError,
	wordBoost,
}) => {
	const opts = {
		token,
		endUtteranceSilenceThreshold,
		...(wordBoost?.length > 0 ? { wordBoost } : {}),
	};

	if (jsonSafeStringify(opts) === jsonSafeStringify(isRecording)) {
		return;
	}

	if (isRecording) {
		// eslint-disable-next-line no-console
		console.log(
			`[startTranscriptionService] Stopping previous transcription because options changed:`,
			{
				newOptions: opts,
				previousOptions: isRecording,
			},
		);
	} else {
		// eslint-disable-next-line no-console
		console.log(
			`[startTranscriptionService] Starting new transcription with options:`,
			opts,
		);
	}

	if (isRecording) {
		await stopTranscriptionService();
	}

	isRecording = opts;

	rt = new RealtimeTranscriber(opts);

	let socketOpen = false;
	rt.on('open', ({ sessionId, expiresAt }) => {
		socketOpen = true;
		onSocketOpened?.({ sessionId, expiresAt });

		// eslint-disable-next-line no-console
		console.warn(
			'AssemblyAI Session ID:',
			sessionId,
			'Expires at:',
			expiresAt,
		);
	});
	rt.on('close', (code, reason) => {
		socketOpen = false;
		// eslint-disable-next-line no-console
		console.log('AssemblyAI Socket Closed', code, reason);
		onSocketClosed?.({ code, reason });
	});
	rt.on('error', (error) => {
		socketOpen = false;
		// eslint-disable-next-line no-console
		console.error('AssemblyAI Transcription Error', error);
		onSocketError?.(error);
	});

	// handle incoming messages to display transcription to the DOM
	let texts = {};
	rt.on('transcript.partial', (message) => {
		let msg = '';
		texts[message.audio_start] = message.text;
		const keys = Object.keys(texts);
		keys.sort((a, b) => a - b);
		for (const key of keys) {
			if (texts[key]) {
				msg += ` ${texts[key]}`;
			}
		}
		msg = msg.trim();
		onTranscript({ text: msg, isPartial: true });
	});

	rt.on('transcript.final', (message) => {
		onTranscript({ text: message?.text, isFinal: true });
		texts = {};
	});

	rt.on('error', async (error) => {
		// eslint-disable-next-line no-console
		console.error(`Error transcribing:`, error);
		await rt.close();
	});

	rt.on('close', (event) => {
		rt = null;
	});

	await rt.connect().catch((ex) => {
		// eslint-disable-next-line no-console
		console.error(`Error connecting to real-time transcription:`, ex);
	});

	// eslint-disable-next-line no-console
	console.log('Running Audio Worklet to record audio');
	microphone = createMicrophone();
	await microphone.requestPermission();
	await microphone.startRecording((audioData) => {
		if (!rt || pauseTransmitRef.current) {
			return;
		}
		if (!socketOpen) {
			// eslint-disable-next-line no-console
			console.warn('Socket not open, skipping audio send');
			return;
		}
		rt.sendAudio(audioData);
	});
};

/**
 * A custom React hook that provides speech-to-text transcription functionality using the browser's built-in SpeechRecognition API.
 *
 * @param {Object} options - An object containing the following options:
 * @param {Function} options.onNewResult - A callback function that will be called with the latest transcription result whenever a new result is available.
 * @param {Object} [options.recognitionRef] - Optional, a ref object that can be used to access the SpeechRecognition instance.
 * @param {Object} [options.speakingRef] - Optional, a ref object that can be used to check if the browser is currently speaking.
 * @param {number} [options.endUtteranceSilenceThreshold] From AssemblyAI: The threshold for how long to wait before ending an utterance.
 * @param {string[]} [options.wordBoost] - Optional, an array of words to boost in transcription.
 * @param {function} [options.onSocketOpened] Called when socket is opened from AssemblyAI
 * @returns {Object} An object containing the following properties:
 * - `started`: A boolean indicating whether speech recognition is currently active.
 * - `setStarted`: A function that can be used to start or stop speech recognition.
 * - `intermResults`: A string containing the current interim transcription results.
 * - `recognitionRef`: A ref object that can be used to access the SpeechRecognition instance.
 * - `speakingRef`: A ref object that can be used to check if the browser is currently speaking.
 */
export function useCloudSpeechTranscription({
	onNewResult,
	onIntermResult,
	recognitionRef: recognitionRefInput,
	speakingRef: speakingRefInput,
	onTranscript,
	endUtteranceSilenceThreshold = 700, // default
	wordBoost,
	onSocketOpened,
	onSocketClosed,
	onSocketError,
}) {
	const recognitionRefLocal = useRef();
	const speakingRefLocal = useRef();

	const handleErrors = useConditionallyHandleErrors();

	const speakingRef = useMemo(
		() => speakingRefInput || speakingRefLocal,
		[speakingRefInput],
	);

	const pauseTransmitRef = useRef(false);

	const recognitionRef = useMemo(
		() => recognitionRefInput || recognitionRefLocal,
		[recognitionRefInput],
	);

	// const [intermResults, setIntermResults] = useState('');
	const intermResultsRef = useRef('');
	const { user } = useAuthorization();

	const [token, setToken] = useState();
	const hasTokenRef = useRef();
	useEffect(() => {
		if (!user?.id) {
			// eslint-disable-next-line no-console
			console.log(`No user to get token`);
			return;
		}

		if (hasTokenRef.current) {
			return;
		}

		handleErrors(AiRequests.AssemblyAiClientToken()).then((data) => {
			hasTokenRef.current = true;
			setToken(data?.token);
			// eslint-disable-next-line no-console
			// console.log(`Received token:`, data?.token);
		});
	}, [handleErrors, token, user?.id]);

	const [started, setStartedState] = useState(false);

	const startedRef = useRef(false);
	const hasPartialRef = useRef(false);
	const startTranscription = useCallback(
		async ({ onStarted } = {}) => {
			let externalToken = token;
			if (!externalToken) {
				// // eslint-disable-next-line no-console
				// console.warn('No token available for transcription');
				// return;

				const tokenData = await handleErrors(
					AiRequests.AssemblyAiClientToken(),
				);
				hasTokenRef.current = true;
				setToken(tokenData?.token);
				externalToken = tokenData?.token;
			}

			if (!externalToken) {
				// eslint-disable-next-line no-console
				console.warn('No token available for transcription');
				return;
			}

			if (startedRef.current) {
				// eslint-disable-next-line no-console
				console.warn(
					'Transcription already started, call stopTranscription first',
				);
				return;
			}

			startedRef.current = true;

			startTranscriptionService({
				token: externalToken,
				pauseTransmitRef,
				endUtteranceSilenceThreshold,
				wordBoost,
				onSocketOpened: (data) => {
					setStartedState(true);
					// Only indicate started when ACTUALLY started
					onSocketOpened?.(data);
					// Local passed-in
					onStarted?.(data);
				},
				onSocketClosed: (data) => {
					setStartedState(false);
					onSocketClosed?.(data);
				},
				onSocketError: (error) => {
					setStartedState(false);
					onSocketError?.(error);
				},
				onTranscript: ({ text, isFinal, isPartial, ...rest }) => {
					if (isFinal) {
						hasPartialRef.current = false;
						onNewResult(text);
						intermResultsRef.current = ''; // Clear interm results
					}
					if (isPartial) {
						hasPartialRef.current = true;
						intermResultsRef.current = text;
						if (onIntermResult) {
							onIntermResult(text);
						}
					}
					if (onTranscript) {
						onTranscript({
							...rest,
							text,
							isFinal,
							isPartial,
						});
					}
				},
			});
		},
		[
			token,
			endUtteranceSilenceThreshold,
			wordBoost,
			handleErrors,
			onSocketOpened,
			onSocketClosed,
			onSocketError,
			onTranscript,
			onNewResult,
			onIntermResult,
		],
	);

	const stopTranscription = useCallback(() => {
		stopTranscriptionService();
		if (hasPartialRef.current) {
			// Stopped with partial result but no final? Send it now as final
			onNewResult(intermResultsRef.current);

			if (onTranscript) {
				onTranscript({
					text: intermResultsRef.current,
					isFinal: true,
					isPartial: false,
				});
			}
		}
	}, [onNewResult, onTranscript]);

	const setStarted = useCallback(
		(flag) => {
			if (flag) {
				startTranscription();
			} else {
				stopTranscription();
			}
		},
		[startTranscription, stopTranscription],
	);

	// console.log(timeSinceLastMark(), `Transcription hook ready`);

	return useMemo(
		() => ({
			started,
			setStarted,
			startTranscription,
			stopTranscription,
			intermResultsRef,
			recognitionRef,
			speakingRef,
			pauseTransmitRef,
		}),
		[
			intermResultsRef,
			recognitionRef,
			setStarted,
			speakingRef,
			startTranscription,
			started,
			stopTranscription,
		],
	);
}
