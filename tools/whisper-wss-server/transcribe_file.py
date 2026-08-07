"""Send an existing audio file through the local WSS endpoint."""

import argparse
import asyncio
import json
import ssl

from faster_whisper.audio import decode_audio
import numpy as np
from websockets.asyncio.client import connect


def arguments():
    parser = argparse.ArgumentParser(description="Test the local Whisper WSS service.")
    parser.add_argument("audio_file")
    parser.add_argument("--url", default="wss://127.0.0.1:7896/ws/transcribe")
    parser.add_argument("--ca-file", required=True)
    parser.add_argument("--language", default="zh")
    parser.add_argument("--chunk-ms", type=int, default=200)
    parser.add_argument("--realtime", action="store_true")
    return parser.parse_args()


async def receive_until_final(websocket):
    while True:
        event = json.loads(await websocket.recv())
        print(json.dumps(event, ensure_ascii=False), flush=True)
        if event.get("type") == "transcript.final":
            return event
        if event.get("type") == "error":
            raise RuntimeError(event.get("message", "WSS transcription failed."))


async def transcribe(options):
    audio = decode_audio(options.audio_file, sampling_rate=16000)
    pcm = np.clip(audio * 32768.0, -32768, 32767).astype("<i2").tobytes()
    chunk_bytes = options.chunk_ms * 32
    context = ssl.create_default_context(cafile=options.ca_file)

    async with connect(options.url, ssl=context, compression=None) as websocket:
        await websocket.send(json.dumps({
            "type": "session.start",
            "language": options.language,
            "format": "pcm_s16le",
            "sampleRate": 16000,
            "channels": 1,
        }))
        ready = json.loads(await websocket.recv())
        print(json.dumps(ready, ensure_ascii=False), flush=True)

        receiver = asyncio.create_task(receive_until_final(websocket))
        for offset in range(0, len(pcm), chunk_bytes):
            await websocket.send(pcm[offset:offset + chunk_bytes])
            if options.realtime:
                await asyncio.sleep(options.chunk_ms / 1000)
        await websocket.send(json.dumps({"type": "input.commit"}))
        return await receiver


def main():
    asyncio.run(transcribe(arguments()))


if __name__ == "__main__":
    main()
