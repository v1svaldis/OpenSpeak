//! The connection task: drives tsclientlib, turns its state into JSON events for the UI and
//! executes the UI's commands.

use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use futures::prelude::*;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
use tracing::{info, warn};
use tsclientlib::data::Connection as Book;
use tsclientlib::{
    ClientId, Connection, DisconnectOptions, Identity, MessageHandle, OutCommandExt, StreamItem,
    Version,
};
use tsproto_packets::packets::{AudioData, CodecType, Direction, Flags, OutAudio, OutCommand, PacketType};

use crate::rawcmd::RawCommand;
use crate::shared::{AudioShared, OutFrame};

/// Where JSON events for the UI go.
pub type EventSink = Arc<dyn Fn(String) + Send + Sync>;

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ConnectParams {
    pub address: String,
    pub nickname: String,
    pub server_password: String,
    pub channel: String,
    pub channel_password: String,
    pub token: String,
    /// TeamSpeak identity in its own `<offset>V<key>` format; empty = create a new one.
    pub identity: String,
    pub hardware_id: String,
    pub input_muted: bool,
    pub output_muted: bool,
    /// Connection attempt number chosen by the UI; every event of this attempt carries it as
    /// `"seq"`, so the UI can ignore late events of an attempt it already cancelled.
    pub seq: u64,
}

pub enum Cmd {
    Connect(ConnectParams),
    Disconnect,
    /// Any client command, e.g. `sendtextmessage` or the stream commands. If `error_prefix` is
    /// set, a failure is shown to the user as "<prefix>: <server message>".
    Raw { name: String, args: Vec<(String, String)>, error_prefix: Option<String> },
    JoinChannel { id: u64, password: String },
}

/// The native core: owns the tokio runtime and the connection task.
pub struct Core {
    tx: mpsc::UnboundedSender<Cmd>,
    pub shared: Arc<AudioShared>,
    _rt: Runtime,
}

impl Core {
    pub fn new(sink: EventSink) -> Self {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("os-net")
            .enable_all()
            .build()
            .expect("tokio runtime");
        let (tx, rx) = mpsc::unbounded_channel();
        let shared = Arc::new(AudioShared::default());
        rt.spawn(run(rx, sink, shared.clone()));
        Self { tx, shared, _rt: rt }
    }

    pub fn send(&self, cmd: Cmd) { let _ = self.tx.send(cmd); }
}

fn emit(sink: &EventSink, v: Value) { sink(v.to_string()) }

/// Wraps `sink` so every event object gets `"seq": <seq>` as its first field.
fn tagged(sink: &EventSink, seq: u64) -> EventSink {
    let sink = sink.clone();
    Arc::new(move |json: String| match json.strip_prefix('{') {
        Some(rest) if rest.starts_with('}') => sink(format!("{{\"seq\":{seq}}}")),
        Some(rest) => sink(format!("{{\"seq\":{seq},{rest}")),
        None => sink(json),
    })
}

fn state(sink: &EventSink, state: &str, detail: &str) {
    emit(sink, json!({"t": "state", "state": state, "detail": detail}));
}

/// Waits for `Connect` commands and runs one connection at a time.
async fn run(mut rx: mpsc::UnboundedReceiver<Cmd>, sink: EventSink, shared: Arc<AudioShared>) {
    let mut next = None;
    loop {
        let cmd = match next.take() {
            Some(c) => c,
            None => match rx.recv().await {
                Some(c) => c,
                None => return,
            },
        };
        let Cmd::Connect(params) = cmd else { continue };
        let sink = tagged(&sink, params.seq);
        // After a crash our old session lingers on the server until it times out, and the server
        // refuses the same identity as a "clone" meanwhile. Wait that out instead of failing; not
        // faster than every 10 s, frequent reconnects trip the server's flood protection.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(120);
        let outcome = loop {
            let outcome = serve(params.clone(), &mut rx, &sink, &shared).await;
            shared.reset();
            *shared.frames.lock().unwrap() = None;
            match outcome {
                Err(Failure::Clone) if tokio::time::Instant::now() < deadline => {
                    state(&sink, "connecting", "Предыдущее подключение ещё активно на сервере, ждём его завершения…");
                    tokio::select! {
                        _ = tokio::time::sleep(Duration::from_secs(10)) => {}
                        c = rx.recv() => {
                            next = c;
                            break Ok(());
                        }
                    }
                }
                other => break other,
            }
        };
        match outcome {
            Ok(()) => state(&sink, "disconnected", ""),
            Err(Failure::Clone) => state(&sink, "failed", "Эта личность уже подключена к серверу"),
            Err(Failure::Text(reason)) => state(&sink, "failed", &reason),
        }
    }
}

enum Failure {
    /// The server still has a session with our identity.
    Clone,
    Text(String),
}

impl From<String> for Failure {
    fn from(s: String) -> Self { Failure::Text(s) }
}

impl From<&tsclientlib::Error> for Failure {
    fn from(e: &tsclientlib::Error) -> Self {
        if e.to_string().to_lowercase().contains("clone") { Failure::Clone } else { Failure::Text(describe(e)) }
    }
}

enum Wake {
    Event(Option<Result<StreamItem, tsclientlib::Error>>),
    Cmd(Option<Cmd>),
    Frame(Option<OutFrame>),
    Tick,
}

/// One connection, from connecting to disconnecting. `Err` carries the reason shown to the user.
async fn serve(
    p: ConnectParams, rx: &mut mpsc::UnboundedReceiver<Cmd>, sink: &EventSink, shared: &Arc<AudioShared>,
) -> Result<(), Failure> {
    state(sink, "connecting", "Подключение…");
    let address = p.address.trim().trim_start_matches("ts3server://").trim_end_matches('/').to_string();
    if address.is_empty() {
        return Err(Failure::Text("Не указан адрес сервера".into()));
    }
    let mut opts = Connection::build(address)
        .name(if p.nickname.trim().is_empty() { "Android".to_string() } else { p.nickname.trim().to_string() })
        // A signed official build; TeamSpeak 6 servers verify the signature.
        .version(Version::Android_3_6_0)
        .input_hardware_enabled(true)
        .output_hardware_enabled(true)
        .input_muted(p.input_muted)
        .output_muted(p.output_muted);
    if !p.hardware_id.is_empty() {
        opts = opts.hardware_id(p.hardware_id.clone());
    }
    if !p.server_password.is_empty() {
        opts = opts.password(p.server_password.clone());
    }
    if !p.channel.trim().is_empty() {
        opts = opts.channel(p.channel.trim().to_string());
        if !p.channel_password.is_empty() {
            opts = opts.channel_password(p.channel_password.clone());
        }
    }
    if !p.token.trim().is_empty() {
        opts = opts.default_token(p.token.trim().to_string());
    }
    let identity = match Identity::new_from_ts_str(p.identity.trim()) {
        Ok(id) if !p.identity.trim().is_empty() => id,
        _ => {
            state(sink, "connecting", "Создание личности (identity)…");
            let id = tokio::task::spawn_blocking(Identity::create).await.map_err(|e| e.to_string())?;
            emit_identity(sink, &id);
            id
        }
    };
    opts = opts.identity(identity);

    let mut con = opts.connect().map_err(|e| Failure::from(&e))?;
    let (frame_tx, mut frame_rx) = mpsc::channel::<OutFrame>(64);
    *shared.frames.lock().unwrap() = Some(frame_tx);

    let mut connected = false;
    let mut audio_io = None;
    let mut voice_packet_id: u16 = 0;
    let mut pending: Vec<(MessageHandle, String)> = Vec::new();
    let mut tick = tokio::time::interval(Duration::from_millis(125));
    let mut last_stats = Value::Null;

    loop {
        let wake = {
            let mut events = con.events();
            tokio::select! {
                e = events.next() => Wake::Event(e),
                c = rx.recv() => Wake::Cmd(c),
                f = frame_rx.recv() => Wake::Frame(f),
                _ = tick.tick() => Wake::Tick,
            }
        };
        match wake {
            Wake::Cmd(None) => return Ok(()),
            Wake::Cmd(Some(Cmd::Disconnect)) | Wake::Cmd(Some(Cmd::Connect(_))) => {
                if connected {
                    let _ = con.disconnect(DisconnectOptions::new().message("Leaving"));
                    // Give the server a moment to acknowledge, but never hang the UI on it.
                    let drain = con.events().for_each(|_| future::ready(()));
                    let _ = tokio::time::timeout(Duration::from_secs(2), drain).await;
                }
                return Ok(());
            }
            Wake::Cmd(Some(Cmd::Raw { name, args, error_prefix })) => {
                let mut cmd = OutCommand::new(Direction::C2S, Flags::empty(), PacketType::Command, &name);
                for (k, v) in &args {
                    cmd.write_arg(k, v);
                }
                match cmd.send_with_result(&mut con) {
                    Ok(h) => {
                        if let Some(prefix) = error_prefix {
                            pending.push((h, prefix));
                        }
                    }
                    Err(e) => warn!(%e, %name, "command not sent"),
                }
            }
            Wake::Cmd(Some(Cmd::JoinChannel { id, password })) => {
                let own = con.get_state().map(|s| s.own_client.0).unwrap_or(0);
                let mut cmd = OutCommand::new(Direction::C2S, Flags::empty(), PacketType::Command, "clientmove");
                cmd.write_arg("clid", &own);
                cmd.write_arg("cid", &id);
                if !password.is_empty() {
                    cmd.write_arg("cpw", &tsproto_types::crypto::encode_password(password.as_bytes()));
                }
                if let Ok(h) = cmd.send_with_result(&mut con) {
                    pending.push((h, "Не удалось перейти в канал".into()));
                }
            }
            Wake::Frame(Some(frame)) => {
                if !connected || !con.can_send_audio() {
                    continue;
                }
                let codec = own_codec(&con);
                let data: &[u8] = match &frame {
                    OutFrame::Opus(d) => d,
                    OutFrame::End => &[],
                };
                let packet = OutAudio::new(&AudioData::C2S { id: voice_packet_id, codec, data });
                voice_packet_id = voice_packet_id.wrapping_add(1);
                if let Err(e) = con.send_audio(packet) {
                    warn!(%e, "voice packet not sent");
                }
            }
            Wake::Frame(None) => {}
            Wake::Tick => {
                if !connected {
                    continue;
                }
                shared.can_send.store(con.can_send_audio(), Ordering::Relaxed);
                let talking: Vec<u16> = shared.talking.lock().map(|t| t.clone()).unwrap_or_default();
                let stats = json!({
                    "t": "audio",
                    "talking": talking,
                    "tx": shared.transmitting.load(Ordering::Relaxed),
                    "level": (shared.level_db() * 2.0).round() / 2.0,
                });
                if stats != last_stats {
                    emit(sink, stats.clone());
                    last_stats = stats;
                }
            }
            Wake::Event(None) => {
                return Err(Failure::Text("Соединение закрыто сервером".into()));
            }
            Wake::Event(Some(Err(e))) => {
                return Err(Failure::from(&e));
            }
            Wake::Event(Some(Ok(item))) => match item {
                StreamItem::BookEvents(_) => {
                    if let Ok(book) = con.get_state() {
                        emit(sink, model_json(book));
                        update_bitrate(shared, book);
                    }
                    if !connected {
                        connected = true;
                        state(sink, "connected", "");
                        if let Some(id) = con.get_options().get_identity() {
                            emit_identity(sink, id);
                        }
                        match start_audio(shared) {
                            Ok(io) => audio_io = Some(io),
                            Err(e) => emit(sink, json!({"t": "notice", "text": format!("Звук недоступен: {e}")})),
                        }
                    }
                }
                StreamItem::MessageEvent(msg) => handle_message(sink, msg),
                StreamItem::Audio(packet) => {
                    let from = match packet.data().data() {
                        AudioData::S2C { from, .. } | AudioData::S2CWhisper { from, .. } => *from,
                        _ => continue,
                    };
                    if let Ok(mut h) = shared.playback.lock() {
                        if let Err(e) = h.handle_packet(ClientId(from), packet) {
                            warn!(%e, "bad voice packet");
                        }
                    }
                }
                StreamItem::RawCommand(text) => {
                    if let Some(c) = RawCommand::parse(&text) {
                        for part in &c.parts {
                            emit(sink, json!({"t": "raw", "name": c.name, "args": part}));
                        }
                    }
                }
                StreamItem::MessageResult(handle, result) => {
                    if let Some(pos) = pending.iter().position(|(h, _)| *h == handle) {
                        let (_, prefix) = pending.swap_remove(pos);
                        if let Err(e) = result {
                            emit(sink, json!({"t": "notice", "text": format!("{prefix}: {e}")}));
                        }
                    }
                }
                StreamItem::IdentityLevelIncreasing(level) => {
                    state(sink, "connecting", &format!("Повышение уровня безопасности личности до {level}…"));
                }
                StreamItem::IdentityLevelIncreased => {
                    if let Some(id) = con.get_options().get_identity() {
                        emit_identity(sink, id);
                    }
                }
                StreamItem::DisconnectedTemporarily(_) => {
                    connected = false;
                    audio_io = None;
                    shared.reset();
                    state(sink, "connecting", "Связь потеряна, переподключение…");
                }
                _ => {}
            },
        }
        let _ = &audio_io;
    }
}

#[cfg(target_os = "android")]
fn start_audio(shared: &Arc<AudioShared>) -> anyhow::Result<crate::audio::AudioIo> { crate::audio::AudioIo::start(shared.clone()) }

#[cfg(not(target_os = "android"))]
fn start_audio(_shared: &Arc<AudioShared>) -> anyhow::Result<()> { Ok(()) }

fn emit_identity(sink: &EventSink, id: &Identity) {
    let value = format!("{}V{}", id.counter(), id.key().to_ts_obfuscated());
    emit(sink, json!({"t": "identity", "value": value, "level": id.level()}));
}

fn own_codec(con: &Connection) -> CodecType {
    use tsclientlib::Codec;
    let codec = con
        .get_state()
        .ok()
        .and_then(|b| b.clients.get(&b.own_client).and_then(|c| b.channels.get(&c.channel)))
        .map(|ch| ch.codec);
    match codec {
        Some(Codec::OpusMusic) => CodecType::OpusMusic,
        _ => CodecType::OpusVoice,
    }
}

/// Opus bitrate from the channel's quality setting (0–10), like the official client.
fn update_bitrate(shared: &AudioShared, book: &Book) {
    use tsclientlib::Codec;
    let Some(ch) = book.clients.get(&book.own_client).and_then(|c| book.channels.get(&c.channel)) else { return };
    let q = u32::from(ch.codec_quality.unwrap_or(6).min(10));
    let rate = if ch.codec == Codec::OpusMusic { 32_000 + q * 6_000 } else { 12_000 + q * 3_000 };
    shared.bitrate.store(rate, Ordering::Relaxed);
}

fn model_json(book: &Book) -> Value {
    use tsclientlib::{ClientType, Codec, MaxClients};
    let channels: Vec<Value> = book
        .channels
        .values()
        .map(|c| {
            json!({
                "id": c.id.0,
                "parent": c.parent.0,
                "order": c.order.0,
                "name": c.name,
                "topic": c.topic.clone().unwrap_or_default(),
                "hasPassword": c.has_password.unwrap_or(false),
                "codec": match c.codec { Codec::OpusMusic => 5, Codec::OpusVoice => 4, _ => 0 },
                "maxClients": match c.max_clients { Some(MaxClients::Limited(n)) => i64::from(n), _ => -1 },
                "isDefault": c.is_default.unwrap_or(false),
            })
        })
        .collect();
    let clients: Vec<Value> = book
        .clients
        .values()
        .map(|c| {
            json!({
                "id": c.id.0,
                "channel": c.channel.0,
                "name": c.name,
                "type": if matches!(c.client_type, ClientType::Query { .. }) { 1 } else { 0 },
                "inputMuted": c.input_muted,
                "outputMuted": c.output_muted,
                "away": c.away_message.is_some(),
                "awayMessage": c.away_message.clone().unwrap_or_default(),
                "talkPower": c.talk_power,
                "isTalker": c.talk_power_granted,
            })
        })
        .collect();
    json!({
        "t": "model",
        "server": book.server.name,
        "welcome": book.server.welcome_message,
        "own": book.own_client.0,
        "channels": channels,
        "clients": clients,
    })
}

fn handle_message(sink: &EventSink, msg: tsclientlib::messages::s2c::InMessage) {
    use tsclientlib::messages::s2c::InMessage;
    match msg {
        InMessage::TextMessage(m) => {
            for part in m.iter() {
                emit(sink, json!({
                    "t": "text",
                    "mode": part.target as u8,
                    "fromId": part.invoker_id.0,
                    "from": part.invoker_name,
                    "text": part.message,
                }));
            }
        }
        InMessage::ClientPoke(m) => {
            for part in m.iter() {
                emit(sink, json!({"t": "poke", "from": part.invoker_name, "text": part.message}));
            }
        }
        _ => {}
    }
}

/// Short Russian explanation of why a connection failed.
fn describe(e: &tsclientlib::Error) -> String {
    use tsclientlib::Error as E;
    let text = e.to_string();
    let lower = text.to_lowercase();
    match e {
        E::ResolveAddress(_) => format!("Не удалось найти сервер: {text}"),
        E::InitserverTimeout => "Сервер не отвечает (проверьте адрес и порт UDP)".into(),
        _ if lower.contains("password") => "Неверный пароль".into(),
        _ if lower.contains("banned") => format!("Вы забанены на этом сервере: {text}"),
        _ if lower.contains("maxclient") || lower.contains("max clients") => "Сервер переполнен".into(),
        _ if lower.contains("timed out") || lower.contains("timeout") => {
            "Сервер не отвечает (проверьте адрес и порт UDP)".into()
        }
        _ => {
            info!(%text, "connection failed");
            text
        }
    }
}
