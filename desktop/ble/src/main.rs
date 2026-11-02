//! Hamstr's Bluetooth LE helper for the desktop app. The JVM has no Bluetooth, so the app runs
//! this next to it and speaks JSON lines with it: commands on stdin, events on stdout.
//!
//! It only moves bytes. It advertises Hamstr's service and runs its GATT server, whose reads
//! and writes it hands to the app to answer (`read`/`write` events, answered by `respond`), and
//! it scans for other Hamstrs (`seen`) and connects, reads and writes theirs (`connect`, `read`,
//! `write`, `disconnect`, each answered by a `done` with the same `req`). What the bytes mean,
//! signed hellos, sealed links and files, stays in the app (shared/…/nearby/Ble.kt).

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use ble_peripheral_rust::gatt::characteristic::Characteristic as LocalCharacteristic;
use ble_peripheral_rust::gatt::peripheral_event::{PeripheralEvent, ReadRequestResponse, RequestResponse, WriteRequestResponse};
use ble_peripheral_rust::gatt::properties::{AttributePermission, CharacteristicProperty};
use ble_peripheral_rust::gatt::service::Service;
use ble_peripheral_rust::{Peripheral as Server, PeripheralImpl};
use btleplug::api::{Central, CentralEvent, Manager as _, Peripheral as _, ScanFilter, WriteType};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures::StreamExt;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::{mpsc, oneshot, Mutex};
use uuid::Uuid;

const SERVICE: Uuid = Uuid::from_u128(0x6d3a0001_5a1e_4c7b_9f3e_48616d737472);
const INFO: Uuid = Uuid::from_u128(0x6d3a0002_5a1e_4c7b_9f3e_48616d737472);
const INBOX: Uuid = Uuid::from_u128(0x6d3a0003_5a1e_4c7b_9f3e_48616d737472);
const RECEIPT: Uuid = Uuid::from_u128(0x6d3a0004_5a1e_4c7b_9f3e_48616d737472);
const STREAM: Uuid = Uuid::from_u128(0x6d3a0005_5a1e_4c7b_9f3e_48616d737472);
/// The Bluetooth SIG's id for tests and unregistered makers; phones put their 8-byte id there.
const MAKER: u16 = 0xFFFF;
/// How long a read or write from another device waits for the app's answer.
const ANSWER_WITHIN: Duration = Duration::from_secs(10);

enum Answer {
    Read(oneshot::Sender<ReadRequestResponse>),
    Write(oneshot::Sender<WriteRequestResponse>),
}

#[derive(Clone)]
struct Out(mpsc::UnboundedSender<Value>);

impl Out {
    fn send(&self, v: Value) {
        let _ = self.0.send(v);
    }
}

struct State {
    out: Out,
    adapter: Option<Adapter>,
    /// Devices heard while scanning, by the id the app knows them by.
    devices: Mutex<HashMap<String, Peripheral>>,
    /// Reads and writes from other devices that wait for the app, by request number.
    answers: Mutex<HashMap<u64, Answer>>,
    server: Mutex<Option<Server>>,
    next: AtomicU64,
}

#[tokio::main]
async fn main() {
    let (tx, mut rx) = mpsc::unbounded_channel::<Value>();
    let out = Out(tx);
    // One writer, so events never interleave.
    let writer = tokio::spawn(async move {
        let mut stdout = tokio::io::stdout();
        while let Some(v) = rx.recv().await {
            let mut line = v.to_string();
            line.push('\n');
            if stdout.write_all(line.as_bytes()).await.is_err() || stdout.flush().await.is_err() {
                break;
            }
        }
    });

    let adapter = match Manager::new().await {
        Ok(m) => m.adapters().await.ok().and_then(|a| a.into_iter().next()),
        Err(_) => None,
    };
    out.send(json!({ "ev": "ready", "central": adapter.is_some(), "version": env!("CARGO_PKG_VERSION") }));
    let state = Arc::new(State {
        out: out.clone(),
        adapter,
        devices: Mutex::new(HashMap::new()),
        answers: Mutex::new(HashMap::new()),
        server: Mutex::new(None),
        next: AtomicU64::new(1),
    });
    if let Some(adapter) = state.adapter.clone() {
        tokio::spawn(listen(state.clone(), adapter));
    }

    // The app closing stdin ends the helper, so it never outlives Hamstr.
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let Ok(cmd) = serde_json::from_str::<Value>(&line) else { continue };
        let state = state.clone();
        tokio::spawn(async move {
            let req = cmd.get("req").cloned().unwrap_or(Value::Null);
            match handle(&state, &cmd).await {
                Ok(Some(mut done)) => {
                    done["ev"] = json!("done");
                    done["req"] = req;
                    done["ok"] = json!(true);
                    state.out.send(done);
                }
                Ok(None) => {}
                Err(e) => state.out.send(json!({ "ev": "done", "req": req, "ok": false, "error": e })),
            }
        });
    }
    if let Some(mut server) = state.server.lock().await.take() {
        let _ = server.stop_advertising().await;
    }
    drop(state);
    drop(out);
    let _ = tokio::time::timeout(Duration::from_secs(1), writer).await;
}

fn text(cmd: &Value, key: &str) -> Result<String, String> {
    cmd.get(key)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| format!("no {key}"))
}

fn characteristic(cmd: &Value) -> Result<Uuid, String> {
    Uuid::parse_str(&text(cmd, "char")?).map_err(|e| e.to_string())
}

/// Runs one command; `Some` is the reply for commands with a `req`.
async fn handle(state: &Arc<State>, cmd: &Value) -> Result<Option<Value>, String> {
    match cmd.get("op").and_then(Value::as_str).unwrap_or("") {
        "serve" => serve(state).await.map(|_| Some(json!({}))),
        "unserve" => {
            if let Some(server) = state.server.lock().await.as_mut() {
                server.stop_advertising().await.map_err(|e| e.to_string())?;
            }
            Ok(Some(json!({})))
        }
        "respond" => {
            let id = cmd.get("sreq").and_then(Value::as_u64).ok_or("no sreq")?;
            let ok = cmd.get("ok").and_then(Value::as_bool).unwrap_or(false);
            let data = cmd
                .get("data")
                .and_then(Value::as_str)
                .and_then(|s| B64.decode(s).ok())
                .unwrap_or_default();
            let response = if ok {
                RequestResponse::Success
            } else {
                RequestResponse::UnlikelyError
            };
            match state.answers.lock().await.remove(&id) {
                Some(Answer::Read(tx)) => {
                    let _ = tx.send(ReadRequestResponse { value: data, response });
                }
                Some(Answer::Write(tx)) => {
                    let _ = tx.send(WriteRequestResponse { response });
                }
                None => {}
            }
            Ok(None)
        }
        "scan" => {
            let adapter = state.adapter.as_ref().ok_or("no Bluetooth adapter")?;
            if cmd.get("on").and_then(Value::as_bool).unwrap_or(true) {
                adapter
                    .start_scan(ScanFilter { services: vec![SERVICE] })
                    .await
                    .map_err(|e| e.to_string())?;
            } else {
                adapter.stop_scan().await.map_err(|e| e.to_string())?;
            }
            Ok(Some(json!({})))
        }
        "connect" => {
            let p = device(state, cmd).await?;
            if !p.is_connected().await.unwrap_or(false) {
                p.connect_with_timeout(Duration::from_secs(15)).await.map_err(|e| e.to_string())?;
            }
            p.discover_services_with_timeout(Duration::from_secs(15))
                .await
                .map_err(|e| e.to_string())?;
            let ours = p.characteristics();
            if !ours.iter().any(|c| c.service_uuid == SERVICE && c.uuid == INFO) {
                let _ = p.disconnect().await;
                return Err("not a Hamstr".into());
            }
            let chars: Vec<Value> = ours
                .iter()
                .filter(|c| c.service_uuid == SERVICE)
                .map(|c| json!({ "uuid": c.uuid.to_string(), "fast": c.properties.contains(btleplug::api::CharPropFlags::WRITE_WITHOUT_RESPONSE) }))
                .collect();
            Ok(Some(json!({ "mtu": p.mtu(), "chars": chars })))
        }
        "read" => {
            let p = device(state, cmd).await?;
            let uuid = characteristic(cmd)?;
            let c = p
                .characteristics()
                .into_iter()
                .find(|c| c.service_uuid == SERVICE && c.uuid == uuid)
                .ok_or("no such characteristic")?;
            let value = p.read(&c).await.map_err(|e| e.to_string())?;
            Ok(Some(json!({ "data": B64.encode(value) })))
        }
        "write" => {
            let p = device(state, cmd).await?;
            let uuid = characteristic(cmd)?;
            let data = B64.decode(text(cmd, "data")?).map_err(|e| e.to_string())?;
            let c = p
                .characteristics()
                .into_iter()
                .find(|c| c.service_uuid == SERVICE && c.uuid == uuid)
                .ok_or("no such characteristic")?;
            let fast = cmd.get("fast").and_then(Value::as_bool).unwrap_or(false);
            let kind = if fast {
                WriteType::WithoutResponse
            } else {
                WriteType::WithResponse
            };
            p.write(&c, &data, kind).await.map_err(|e| e.to_string())?;
            Ok(Some(json!({})))
        }
        "disconnect" => {
            if let Ok(p) = device(state, cmd).await {
                let _ = p.disconnect().await;
            }
            Ok(Some(json!({})))
        }
        other => Err(format!("unknown op {other}")),
    }
}

async fn device(state: &State, cmd: &Value) -> Result<Peripheral, String> {
    let id = text(cmd, "dev")?;
    state.devices.lock().await.get(&id).cloned().ok_or_else(|| "not seen".to_string())
}

/// Advertises the service and serves its characteristics; their reads and writes go to the app.
async fn serve(state: &Arc<State>) -> Result<(), String> {
    let mut slot = state.server.lock().await;
    if let Some(server) = slot.as_mut() {
        return server.start_advertising("Hamstr", &[SERVICE]).await.map_err(|e| e.to_string());
    }
    let (tx, mut rx) = mpsc::channel::<PeripheralEvent>(256);
    let mut server = Server::new(tx).await.map_err(|e| e.to_string())?;
    for _ in 0..50 {
        if server.is_powered().await.unwrap_or(false) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    let readable = |uuid| LocalCharacteristic {
        uuid,
        properties: vec![CharacteristicProperty::Read],
        permissions: vec![AttributePermission::Readable],
        value: None,
        descriptors: Vec::new(),
    };
    let writable = |uuid| LocalCharacteristic {
        uuid,
        properties: vec![CharacteristicProperty::Write],
        permissions: vec![AttributePermission::Writeable],
        value: None,
        descriptors: Vec::new(),
    };
    let stream = LocalCharacteristic {
        uuid: STREAM,
        properties: vec![CharacteristicProperty::Read, CharacteristicProperty::Write],
        permissions: vec![AttributePermission::Readable, AttributePermission::Writeable],
        value: None,
        descriptors: Vec::new(),
    };
    server
        .add_service(&Service {
            uuid: SERVICE,
            primary: true,
            characteristics: vec![readable(INFO), writable(INBOX), readable(RECEIPT), stream],
        })
        .await
        .map_err(|e| e.to_string())?;
    server.start_advertising("Hamstr", &[SERVICE]).await.map_err(|e| e.to_string())?;
    *slot = Some(server);
    drop(slot);

    let state = state.clone();
    tokio::spawn(async move {
        while let Some(event) = rx.recv().await {
            let id = state.next.fetch_add(1, Ordering::Relaxed);
            match event {
                PeripheralEvent::ReadRequest {
                    request,
                    offset,
                    responder,
                } => {
                    state.answers.lock().await.insert(id, Answer::Read(responder));
                    state.out.send(json!({ "ev": "read", "sreq": id, "client": request.client, "char": request.characteristic.to_string(), "offset": offset }));
                }
                PeripheralEvent::WriteRequest {
                    request,
                    value,
                    offset,
                    responder,
                } => {
                    state.answers.lock().await.insert(id, Answer::Write(responder));
                    state.out.send(json!({ "ev": "write", "sreq": id, "client": request.client, "char": request.characteristic.to_string(), "offset": offset, "data": B64.encode(value) }));
                }
                PeripheralEvent::StateUpdate { is_powered } => {
                    state.out.send(json!({ "ev": "power", "on": is_powered }));
                    continue;
                }
                PeripheralEvent::CharacteristicSubscriptionUpdate { .. } => continue,
            }
            // An app that never answers mustn't hold the other device: it gets an error instead.
            let state = state.clone();
            tokio::spawn(async move {
                tokio::time::sleep(ANSWER_WITHIN).await;
                match state.answers.lock().await.remove(&id) {
                    Some(Answer::Read(tx)) => {
                        let _ = tx.send(ReadRequestResponse {
                            value: Vec::new(),
                            response: RequestResponse::UnlikelyError,
                        });
                    }
                    Some(Answer::Write(tx)) => {
                        let _ = tx.send(WriteRequestResponse {
                            response: RequestResponse::UnlikelyError,
                        });
                    }
                    None => {}
                }
            });
        }
    });
    Ok(())
}

/// Reports every device that advertises Hamstr's service, with the id a phone advertises.
async fn listen(state: Arc<State>, adapter: Adapter) {
    let Ok(mut events) = adapter.events().await else { return };
    while let Some(event) = events.next().await {
        let id = match event {
            CentralEvent::DeviceDiscovered(id) | CentralEvent::DeviceUpdated(id) => id,
            CentralEvent::ManufacturerDataAdvertisement { id, .. } => id,
            CentralEvent::ServicesAdvertisement { id, .. } => id,
            CentralEvent::DeviceDisconnected(id) => {
                state.out.send(json!({ "ev": "disconnected", "dev": format!("{id:?}") }));
                continue;
            }
            _ => continue,
        };
        let Ok(p) = adapter.peripheral(&id).await else { continue };
        let Ok(Some(props)) = p.properties().await else { continue };
        let hamstr = props
            .manufacturer_data
            .get(&MAKER)
            .filter(|d| d.len() == 8)
            .map(|d| d.iter().map(|b| format!("{b:02x}")).collect::<String>());
        if !props.services.contains(&SERVICE) && hamstr.is_none() {
            continue;
        }
        let key = format!("{id:?}");
        state.devices.lock().await.insert(key.clone(), p);
        state
            .out
            .send(json!({ "ev": "seen", "dev": key, "hamstr": hamstr, "rssi": props.rssi }));
    }
}
