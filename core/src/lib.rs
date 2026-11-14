//! Open Speak native core: TeamSpeak protocol (tsclientlib), voice (libopus + AAudio) and the
//! TeamSpeak 6 stream signaling relay. The Android UI talks to it through [`jni_api`]:
//! commands go in as JSON strings, state changes come back as JSON events.

pub mod rawcmd;
pub mod session;
pub mod shared;

#[cfg(target_os = "android")]
mod audio;
#[cfg(target_os = "android")]
mod jni_api;

pub use session::{Cmd, ConnectParams, Core};
