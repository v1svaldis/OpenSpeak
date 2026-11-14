//! State shared between the network task and the real-time audio callbacks. Everything the audio
//! threads read is an atomic, so they never block on the network side.

use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU8, Ordering};
use std::sync::Mutex;

use tokio::sync::mpsc;
use tsclientlib::audio::AudioHandler;
use tsclientlib::ClientId;

/// An encoded voice frame from the microphone, or the end of a transmission.
pub enum OutFrame {
    Opus(Vec<u8>),
    End,
}

pub const MODE_PUSH_TO_TALK: u8 = 0;
pub const MODE_VOICE_ACTIVATION: u8 = 1;
pub const MODE_CONTINUOUS: u8 = 2;

pub struct AudioShared {
    /// Jitter buffers and decoders of everyone we hear; mixed by the playback callback.
    pub playback: Mutex<AudioHandler<ClientId>>,
    /// Client ids currently heard (written by the playback callback).
    pub talking: Mutex<Vec<u16>>,
    /// Encoded frames from the capture callback to the network task.
    pub frames: Mutex<Option<mpsc::Sender<OutFrame>>>,

    pub mode: AtomicU8,
    pub push_to_talk: AtomicBool,
    /// Voice activation threshold in dBFS × 10.
    pub vad_threshold_db10: AtomicI32,
    pub input_muted: AtomicBool,
    pub output_muted: AtomicBool,
    /// Playback gain × 100.
    pub gain_percent: AtomicU32,
    pub bitrate: AtomicU32,
    /// Whether the server lets us talk right now (connected, not muted by the server, …).
    pub can_send: AtomicBool,

    /// Microphone level in dBFS (f32 bits), for the UI meter.
    pub level_db: AtomicU32,
    pub transmitting: AtomicBool,
}

impl Default for AudioShared {
    fn default() -> Self {
        Self {
            playback: Mutex::new(AudioHandler::new()),
            talking: Mutex::new(Vec::new()),
            frames: Mutex::new(None),
            mode: AtomicU8::new(MODE_PUSH_TO_TALK),
            push_to_talk: AtomicBool::new(false),
            vad_threshold_db10: AtomicI32::new(-400),
            input_muted: AtomicBool::new(false),
            output_muted: AtomicBool::new(false),
            gain_percent: AtomicU32::new(100),
            bitrate: AtomicU32::new(30_000),
            can_send: AtomicBool::new(false),
            level_db: AtomicU32::new((-90f32).to_bits()),
            transmitting: AtomicBool::new(false),
        }
    }
}

impl AudioShared {
    pub fn level_db(&self) -> f32 { f32::from_bits(self.level_db.load(Ordering::Relaxed)) }

    pub fn set_level_db(&self, db: f32) { self.level_db.store(db.to_bits(), Ordering::Relaxed) }

    pub fn reset(&self) {
        if let Ok(mut p) = self.playback.lock() {
            p.reset();
        }
        if let Ok(mut t) = self.talking.lock() {
            t.clear();
        }
        self.transmitting.store(false, Ordering::Relaxed);
        self.can_send.store(false, Ordering::Relaxed);
        self.set_level_db(-90.0);
    }
}
