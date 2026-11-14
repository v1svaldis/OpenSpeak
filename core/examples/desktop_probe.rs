//! Drives the core like the app does, on a desktop: connect, print events, start a TS6 stream
//! announcement (`setupstream`) and expect the server's `notifystreamstarted` back as a raw event.
//! cargo run --example desktop_probe -- 127.0.0.1:9987

use std::sync::Arc;
use std::time::{Duration, Instant};

use openspeak_core::{Cmd, ConnectParams, Core};

fn main() {
    let address = std::env::args().nth(1).unwrap_or_else(|| "127.0.0.1:9987".into());
    let start = Instant::now();
    let sink = Arc::new(move |json: String| {
        let short: String = json.chars().take(300).collect();
        println!("[{:>6} ms] {short}", start.elapsed().as_millis());
    });
    let core = Core::new(sink);
    core.send(Cmd::Connect(ConnectParams {
        address,
        nickname: "rust-core-probe".into(),
        ..Default::default()
    }));
    std::thread::sleep(Duration::from_secs(4));
    core.send(Cmd::Raw {
        name: "setupstream".into(),
        args: vec![
            ("name".into(), "core probe stream".into()),
            ("type".into(), "2".into()),
            ("accessibility".into(), "1".into()),
            ("mode".into(), "1".into()),
            ("bitrate".into(), "4000".into()),
            ("viewer_limit".into(), "0".into()),
            ("audio".into(), "0".into()),
        ],
        error_prefix: Some("setupstream".into()),
    });
    core.send(Cmd::Raw {
        name: "sendtextmessage".into(),
        args: vec![("targetmode".into(), "2".into()), ("msg".into(), "привет из Rust-ядра".into())],
        error_prefix: Some("text".into()),
    });
    // A command that must fail, to see the error path.
    core.send(Cmd::JoinChannel { id: 4, password: "wrong".into() });
    std::thread::sleep(Duration::from_secs(3));
    core.send(Cmd::Disconnect);
    std::thread::sleep(Duration::from_secs(3));
}
