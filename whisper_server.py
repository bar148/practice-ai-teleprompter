"""
שרת תמלול מקומי לטלפרומפטר: מקליט מהמיקרופון, מתמלל עם Whisper על כרטיס המסך,
ושולח את הטקסט לאפליקציה דרך WebSocket (ws://127.0.0.1:5758).

הודעות מהאפליקציה:  {"type":"start","lang":"he","device":null} | {"type":"stop"} | {"type":"devices"}
הודעות לאפליקציה:   {"type":"status","state":...,"msg":...} | {"type":"text","text":...}
                    {"type":"level","rms":...} | {"type":"devices","list":[...],"default":...}
"""
import argparse
import asyncio
import glob
import json
import os
import sys
import threading
import time

import numpy as np


def add_cuda_dlls():
    # ה-DLL-ים של cuBLAS/cuDNN מגיעים כחבילות pip - צריך להוסיף אותם לנתיב החיפוש של Windows
    base = os.path.join(sys.prefix, "Lib", "site-packages", "nvidia")
    for d in glob.glob(os.path.join(base, "*", "bin")):
        os.add_dll_directory(d)
        os.environ["PATH"] = d + os.pathsep + os.environ["PATH"]


add_cuda_dlls()

import sounddevice as sd  # noqa: E402
from websockets.asyncio.server import serve  # noqa: E402
from faster_whisper import WhisperModel  # noqa: E402

SR = 16000
WINDOW_S = 5.0          # כמה שניות אחרונות מתמללים בכל סבב
STEP_S = 0.35           # כל כמה זמן מריצים תמלול
SILENCE_RMS = 0.004     # מתחת לזה - שקט, לא מתמללים (מונע "הזיות" של Whisper)

p = argparse.ArgumentParser()
p.add_argument("--port", type=int, default=5758)
p.add_argument("--model", default="ivrit-ai/whisper-large-v3-turbo-ct2")
p.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
p.add_argument("--simulate", help="לבדיקות: קובץ WAV (16kHz מונו) שמוזרם במקום המיקרופון")
args = p.parse_args()

clients = set()
loop: asyncio.AbstractEventLoop = None
state = {"model": None, "status": ("loading", "טוען מודל…"), "listening": False,
         "lang": "he", "stream": None}
buf_lock = threading.Lock()
audio = np.zeros(0, dtype=np.float32)
new_samples = 0


def broadcast(msg: dict):
    data = json.dumps(msg, ensure_ascii=False)
    for ws in list(clients):
        asyncio.run_coroutine_threadsafe(_send(ws, data), loop)


async def _send(ws, data):
    try:
        await ws.send(data)
    except Exception:
        clients.discard(ws)


def set_status(s, msg=""):
    state["status"] = (s, msg)
    broadcast({"type": "status", "state": s, "msg": msg})


def load_model():
    tries = [("cuda", "float16"), ("cpu", "int8")] if args.device == "auto" else \
            [(args.device, "float16" if args.device == "cuda" else "int8")]
    last = None
    for dev, ct in tries:
        try:
            set_status("loading", "טוען מודל Whisper…" + (" (מעבד - יהיה איטי)" if dev == "cpu" else ""))
            m = WhisperModel(args.model, device=dev, compute_type=ct)
            # חימום - התמלול הראשון תמיד איטי
            m.transcribe(np.zeros(SR, dtype=np.float32), language="he", beam_size=1)
            state["model"] = m
            if state["listening"]:
                set_status("listening", "מקשיב")
            else:
                set_status("ready", "מוכן" + (" (GPU)" if dev == "cuda" else " (מעבד)"))
            print(f"model ready on {dev}", flush=True)
            return
        except Exception as e:  # נופלים ל-CPU אם CUDA לא עובד
            last = e
            print(f"load on {dev} failed: {e}", flush=True)
    set_status("error", f"טעינת המודל נכשלה: {last}")


def on_audio(indata, frames, t, status):
    global audio, new_samples
    mono = indata[:, 0].copy()
    with buf_lock:
        audio = np.concatenate([audio, mono])[-int(SR * WINDOW_S):]
        new_samples += len(mono)


def start_listening(lang, device):
    global audio, new_samples
    stop_listening()
    with buf_lock:
        audio = np.zeros(0, dtype=np.float32)
        new_samples = 0
    state["lang"] = lang or "he"
    if args.simulate:
        threading.Thread(target=simulate_audio, daemon=True).start()
        state["listening"] = True
        set_status("listening", "מקשיב (סימולציה)")
        return
    try:
        st = sd.InputStream(samplerate=SR, channels=1, dtype="float32", blocksize=int(SR * 0.05),
                            device=device, callback=on_audio)
        st.start()
    except Exception as e:
        set_status("error", f"לא הצלחתי לפתוח מיקרופון: {e}")
        return
    state["stream"] = st
    state["listening"] = True
    set_status("listening", "מקשיב")


def simulate_audio():
    import wave
    w = wave.open(args.simulate)
    data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    step = int(SR * 0.05)
    for i in range(0, len(data), step):
        if not state["listening"]:
            return
        on_audio(data[i:i + step, None], step, None, None)
        time.sleep(0.05)


def stop_listening():
    state["listening"] = False
    st = state.pop("stream", None)
    state["stream"] = None
    if st:
        try:
            st.stop(); st.close()
        except Exception:
            pass
    if state["model"] is not None:
        set_status("ready", "מושהה")


def transcribe_loop():
    global new_samples
    last_level = 0
    while True:
        time.sleep(0.05)
        if not state["listening"]:
            continue
        with buf_lock:
            chunk = audio.copy()
            fresh = new_samples
        now = time.time()
        if len(chunk) and now - last_level > 0.12:
            last_level = now
            broadcast({"type": "level", "rms": float(np.sqrt(np.mean(chunk[-1600:] ** 2)))})
        if state["model"] is None or fresh < SR * STEP_S or len(chunk) < SR * 0.6:
            continue
        with buf_lock:
            new_samples = 0
        tail = chunk[-int(SR * 1.0):]
        if np.sqrt(np.mean(tail ** 2)) < SILENCE_RMS:
            continue
        try:
            segs, _ = state["model"].transcribe(
                chunk, language=state["lang"], beam_size=1, temperature=0.0,
                condition_on_previous_text=False, without_timestamps=True,
                no_speech_threshold=0.6, max_new_tokens=80)
            text = " ".join(s.text.strip() for s in segs if s.no_speech_prob < 0.7).strip()
        except Exception as e:
            print("transcribe error:", e, flush=True)
            continue
        if text:
            broadcast({"type": "text", "text": text})


def device_list():
    out = []
    try:
        for i, d in enumerate(sd.query_devices()):
            if d["max_input_channels"] > 0 and sd.query_hostapis(d["hostapi"])["name"] == "Windows WASAPI":
                out.append({"id": i, "name": d["name"]})
        default = sd.default.device[0]
    except Exception:
        default = None
    return {"type": "devices", "list": out, "default": default}


async def handler(ws):
    clients.add(ws)
    s, msg = state["status"]
    await ws.send(json.dumps({"type": "status", "state": s, "msg": msg}, ensure_ascii=False))
    await ws.send(json.dumps(device_list(), ensure_ascii=False))
    try:
        async for raw in ws:
            try:
                m = json.loads(raw)
            except Exception:
                continue
            t = m.get("type")
            if t == "start":
                dev = m.get("device")
                start_listening(m.get("lang", "he"), int(dev) if dev not in (None, "") else None)
            elif t == "stop":
                stop_listening()
            elif t == "devices":
                await ws.send(json.dumps(device_list(), ensure_ascii=False))
    finally:
        clients.discard(ws)


async def main():
    global loop
    loop = asyncio.get_running_loop()
    threading.Thread(target=load_model, daemon=True).start()
    threading.Thread(target=transcribe_loop, daemon=True).start()
    async with serve(handler, "127.0.0.1", args.port, max_size=2**20):
        print(f"listening on ws://127.0.0.1:{args.port}", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
