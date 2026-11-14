//! Voice I/O through AAudio: the microphone is encoded to Opus right in the capture callback, and
//! the playback callback mixes everyone we hear from tsclientlib's jitter buffers. Both callbacks
//! run on AAudio's real-time threads, so they only touch atomics and `try_lock`.

use std::ffi::c_void;
use std::sync::atomic::Ordering;
use std::sync::Arc;

use anyhow::{Context, Result};
use audiopus::coder::{Encoder, GenericCtl};
use audiopus::{Application, Bitrate, Channels, SampleRate, Signal};
use ndk::audio::{
    AudioCallbackResult, AudioContentType, AudioDirection, AudioFormat, AudioInputPreset,
    AudioPerformanceMode, AudioSharingMode, AudioStream, AudioStreamBuilder, AudioUsage,
};
use tracing::{info, warn};

use crate::shared::{AudioShared, OutFrame, MODE_CONTINUOUS, MODE_PUSH_TO_TALK, MODE_VOICE_ACTIVATION};

const RATE: i32 = 48_000;
/// 20 ms at 48 kHz, the frame size TeamSpeak's Opus codecs use.
const FRAME: usize = 960;
/// Keep transmitting for 400 ms after the voice falls below the threshold.
const VAD_HANGOVER_FRAMES: u32 = 20;

/// Open input and output streams; dropping this stops both.
pub struct AudioIo {
    input: AudioStream,
    output: AudioStream,
}

// SAFETY: AAudio stream handles may be started, stopped and closed from any thread; the data
// callbacks run on AAudio's own threads regardless of which thread owns the handle.
unsafe impl Send for AudioIo {}

impl Drop for AudioIo {
    fn drop(&mut self) {
        let _ = self.input.request_stop();
        let _ = self.output.request_stop();
    }
}

impl AudioIo {
    pub fn start(shared: Arc<AudioShared>) -> Result<Self> {
        let output = open_output(shared.clone()).context("AAudio output")?;
        let input = open_input(shared).context("AAudio input")?;
        output.request_start().context("start output")?;
        input.request_start().context("start input")?;
        info!(
            input_rate = input.sample_rate(),
            output_rate = output.sample_rate(),
            input_burst = input.frames_per_burst(),
            "voice streams started"
        );
        Ok(Self { input, output })
    }
}

fn open_output(shared: Arc<AudioShared>) -> Result<AudioStream> {
    let mut talkers: Vec<u16> = Vec::with_capacity(8);
    let stream = AudioStreamBuilder::new()?
        .direction(AudioDirection::Output)
        .format(AudioFormat::PCM_Float)
        .channel_count(2)
        .sample_rate(RATE)
        .sharing_mode(AudioSharingMode::Shared)
        .performance_mode(AudioPerformanceMode::LowLatency)
        .usage(AudioUsage::VoiceCommunication)
        .content_type(AudioContentType::Speech)
        .data_callback(Box::new(move |_stream, data: *mut c_void, frames: i32| {
            // SAFETY: AAudio hands us `frames` interleaved stereo float frames to fill.
            let buf = unsafe { std::slice::from_raw_parts_mut(data as *mut f32, frames as usize * 2) };
            buf.fill(0.0);
            if let Ok(mut handler) = shared.playback.try_lock() {
                handler.fill_buffer(buf);
                talkers.clear();
                talkers.extend(handler.get_queues().keys().map(|id| id.0));
                if let Ok(mut t) = shared.talking.try_lock() {
                    if *t != talkers {
                        t.clone_from(&talkers);
                    }
                }
            }
            if shared.output_muted.load(Ordering::Relaxed) {
                buf.fill(0.0);
            } else {
                let gain = shared.gain_percent.load(Ordering::Relaxed) as f32 / 100.0;
                for s in buf.iter_mut() {
                    *s = (*s * gain).clamp(-1.0, 1.0);
                }
            }
            AudioCallbackResult::Continue
        }))
        .error_callback(Box::new(|_stream, error| warn!(?error, "AAudio output error")))
        .open_stream()?;
    Ok(stream)
}

fn open_input(shared: Arc<AudioShared>) -> Result<AudioStream> {
    let mut encoder = Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)?;
    encoder.set_signal(Signal::Voice)?;
    let mut bitrate = 0u32;
    let mut frame: Vec<i16> = Vec::with_capacity(FRAME);
    let mut packet = vec![0u8; 1275];
    let mut hangover = 0u32;
    let mut sending = false;

    let stream = AudioStreamBuilder::new()?
        .direction(AudioDirection::Input)
        .format(AudioFormat::PCM_I16)
        .channel_count(1)
        .sample_rate(RATE)
        .sharing_mode(AudioSharingMode::Shared)
        .performance_mode(AudioPerformanceMode::None)
        // Enables the platform's echo canceller and noise suppressor.
        .input_preset(AudioInputPreset::VoiceCommunication)
        .data_callback(Box::new(move |_stream, data: *mut c_void, frames: i32| {
            // SAFETY: AAudio hands us `frames` mono 16-bit samples.
            let samples = unsafe { std::slice::from_raw_parts(data as *const i16, frames as usize) };
            for &s in samples {
                frame.push(s);
                if frame.len() < FRAME {
                    continue;
                }

                let sum: f64 = frame.iter().map(|&v| f64::from(v) * f64::from(v)).sum();
                let rms = (sum / FRAME as f64).sqrt();
                let db = if rms < 1.0 { -90.0 } else { (20.0 * (rms / 32768.0).log10()) as f32 };
                shared.set_level_db(db);

                let want = !shared.input_muted.load(Ordering::Relaxed)
                    && shared.can_send.load(Ordering::Relaxed)
                    && match shared.mode.load(Ordering::Relaxed) {
                        MODE_PUSH_TO_TALK => shared.push_to_talk.load(Ordering::Relaxed),
                        MODE_CONTINUOUS => true,
                        MODE_VOICE_ACTIVATION => {
                            let threshold = shared.vad_threshold_db10.load(Ordering::Relaxed) as f32 / 10.0;
                            if db >= threshold {
                                hangover = VAD_HANGOVER_FRAMES;
                            } else {
                                hangover = hangover.saturating_sub(1);
                            }
                            hangover > 0
                        }
                        _ => false,
                    };

                let tx = shared.frames.try_lock().ok().and_then(|f| f.clone());
                if want {
                    let wanted_rate = shared.bitrate.load(Ordering::Relaxed);
                    if wanted_rate != bitrate {
                        bitrate = wanted_rate;
                        let _ = encoder.set_bitrate(Bitrate::BitsPerSecond(bitrate as i32));
                    }
                    if let Ok(len) = encoder.encode(&frame, &mut packet) {
                        if let Some(tx) = &tx {
                            let _ = tx.try_send(OutFrame::Opus(packet[..len].to_vec()));
                        }
                    }
                    sending = true;
                } else if sending {
                    if let Some(tx) = &tx {
                        let _ = tx.try_send(OutFrame::End);
                    }
                    let _ = encoder.reset_state();
                    sending = false;
                }
                shared.transmitting.store(sending, Ordering::Relaxed);
                frame.clear();
            }
            AudioCallbackResult::Continue
        }))
        .error_callback(Box::new(|_stream, error| warn!(?error, "AAudio input error")))
        .open_stream()?;
    Ok(stream)
}
