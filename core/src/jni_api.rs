//! JNI entry points for `com.v1svaldis.openspeak.core.NativeCore` (Kotlin). Commands come in as plain
//! strings / JSON; events go back through `NativeCore.Listener.onEvent(String)`.

use std::sync::atomic::Ordering;
use std::sync::{Arc, OnceLock};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde::Deserialize;

use crate::session::{Cmd, ConnectParams, Core};
use crate::shared::{MODE_CONTINUOUS, MODE_PUSH_TO_TALK, MODE_VOICE_ACTIVATION};

static CORE: OnceLock<Core> = OnceLock::new();

fn core() -> Option<&'static Core> { CORE.get() }

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

/// Initialises logging and the core once; `listener` receives every event as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeInit(
    env: JNIEnv, _class: JClass, listener: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            // tsclientlib logs every parsed message at info; keep only its warnings.
            .with_filter(android_logger::FilterBuilder::new().parse("warn,openspeak_core=info").build())
            .with_tag("OpenSpeakCore"),
    );
    if CORE.get().is_some() {
        return;
    }
    let vm: JavaVM = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return,
    };
    let listener: GlobalRef = match env.new_global_ref(listener) {
        Ok(l) => l,
        Err(_) => return,
    };
    let vm = Arc::new(vm);
    let sink = Arc::new(move |json: String| {
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return };
        if let Ok(s) = env.new_string(json) {
            let _ = env.call_method(listener.as_obj(), "onEvent", "(Ljava/lang/String;)V", &[JValue::Object(&s)]);
            let _ = env.delete_local_ref(s);
        }
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
    });
    let _ = CORE.set(Core::new(sink));
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeConnect(mut env: JNIEnv, _class: JClass, params: JString) {
    let json = string(&mut env, &params);
    let params: ConnectParams = serde_json::from_str(&json).unwrap_or_default();
    if let Some(c) = core() {
        c.shared.input_muted.store(params.input_muted, Ordering::Relaxed);
        c.shared.output_muted.store(params.output_muted, Ordering::Relaxed);
        c.send(Cmd::Connect(params));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeDisconnect(_env: JNIEnv, _class: JClass) {
    if let Some(c) = core() {
        c.send(Cmd::Disconnect);
    }
}

/// `args` is a JSON array of `[key, value]` pairs (order matters to some commands).
#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeCommand(
    mut env: JNIEnv, _class: JClass, name: JString, args: JString, error_prefix: JString,
) {
    let name = string(&mut env, &name);
    let args: Vec<(String, String)> = serde_json::from_str(&string(&mut env, &args)).unwrap_or_default();
    let prefix = if error_prefix.is_null() { String::new() } else { string(&mut env, &error_prefix) };
    if let Some(c) = core() {
        c.send(Cmd::Raw { name, args, error_prefix: if prefix.is_empty() { None } else { Some(prefix) } });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeJoinChannel(
    mut env: JNIEnv, _class: JClass, id: jlong, password: JString,
) {
    let password = if password.is_null() { String::new() } else { string(&mut env, &password) };
    if let Some(c) = core() {
        c.send(Cmd::JoinChannel { id: id as u64, password });
    }
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct AudioSettings {
    mode: String,
    vad_threshold_db: f32,
    gain: f32,
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeAudioSettings(
    mut env: JNIEnv, _class: JClass, json: JString,
) {
    let s: AudioSettings = serde_json::from_str(&string(&mut env, &json)).unwrap_or_default();
    let Some(c) = core() else { return };
    let mode = match s.mode.as_str() {
        "vad" => MODE_VOICE_ACTIVATION,
        "on" => MODE_CONTINUOUS,
        _ => MODE_PUSH_TO_TALK,
    };
    c.shared.mode.store(mode, Ordering::Relaxed);
    c.shared.vad_threshold_db10.store((s.vad_threshold_db * 10.0) as i32, Ordering::Relaxed);
    c.shared.gain_percent.store((s.gain.clamp(0.0, 8.0) * 100.0) as u32, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativePushToTalk(_env: JNIEnv, _class: JClass, pressed: jboolean) {
    if let Some(c) = core() {
        c.shared.push_to_talk.store(pressed != 0, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeSetMuted(
    _env: JNIEnv, _class: JClass, input: jboolean, output: jboolean,
) {
    let Some(c) = core() else { return };
    c.shared.input_muted.store(input != 0, Ordering::Relaxed);
    c.shared.output_muted.store(output != 0, Ordering::Relaxed);
    let flag = |b: jboolean| if b != 0 { "1".to_string() } else { "0".to_string() };
    c.send(Cmd::Raw {
        name: "clientupdate".into(),
        args: vec![("client_input_muted".into(), flag(input)), ("client_output_muted".into(), flag(output))],
        error_prefix: None,
    });
}

#[no_mangle]
pub extern "system" fn Java_com_v1svaldis_openspeak_core_NativeCore_nativeVersion(env: JNIEnv, _class: JClass) -> jstring {
    env.new_string(env!("CARGO_PKG_VERSION")).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// Unused parameter types kept explicit for the JNI signatures above.
#[allow(dead_code)]
fn _types(_: jint) {}
