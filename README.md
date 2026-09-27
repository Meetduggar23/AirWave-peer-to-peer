# BLE Chat — offline phone-to-phone messaging over Bluetooth Low Energy

No internet, no pairing, no accounts. Two Android phones running this app discover
each other over BLE and exchange text messages directly.

## How it works

Each phone plays **both** BLE roles at the same time:

- **Peripheral role** — advertises a chat service UUID and runs a GATT server with
  one characteristic (`WRITE` + `NOTIFY`).
- **Central role** — scans for the chat service UUID, and when you tap a peer it
  connects as a GATT client.

On the single link that forms, the **central sends via characteristic write** and
the **peripheral replies via notification** on the same characteristic. Messages are
framed as `senderName|text` in UTF-8. The app requests the max MTU (517) so
messages up to ~500 bytes go through in one shot.

Relevant Android BLE concepts used: `BluetoothLeAdvertiser`, `BluetoothLeScanner`,
`BluetoothGattServer` / `BluetoothGattCallback`, services, characteristics, and the
CCCD descriptor (`0x2902`) for notifications.

## Requirements

- 2 physical Android phones (BLE advertising doesn't work on most emulators)
- Android 8.0 (API 26)+, Bluetooth turned on, phones within a few meters
- Grant the Bluetooth permissions the app asks for at runtime

## Build & install

1. Open this `ble-chat` folder in **Android Studio** (it will sync Gradle automatically).
2. Connect phone #1 via USB, enable USB debugging, then **Run** the app.
3. Repeat for phone #2 (or use *Build > Build APK* and sideload the APK).

## Test: send a message

1. On **both** phones, enter a display name and tap **Start**.
   - Each phone starts advertising *and* scanning simultaneously.
2. Within a few seconds each phone lists the other under "Nearby devices".
3. On **one** phone only, tap the peer to connect. (One link is enough —
   connecting from both sides creates a redundant second link.)
4. Wait for the status to show **"Ready to chat"**.
5. Type a message and tap **Send** on either phone — it appears on the other.

## Troubleshooting

- **Peer not appearing**: make sure both tapped Start, Bluetooth is on, and the
  phones are close together. Some vendors throttle BLE scans when the screen is off.
- **"Not connected yet" when sending**: the central side needs MTU negotiation to
  finish ("Ready to chat"); the peripheral side needs an incoming connection first.
- **Long messages cut off**: MTU negotiation can fail on some devices; keep
  messages short or retry.

## Security note

Per the Android docs: data exchanged over an unencrypted BLE link is visible to
**all apps on the user's device** (and sniffable over the air). This demo sends
plain text. If you chat about anything sensitive, add app-layer encryption
(e.g. a pre-shared key + AES-GCM) before shipping anything real.

## Project layout

```
ble-chat/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml            # BLE permissions
        ├── java/com/example/blechat/
        │   ├── BleChatManager.kt          # all BLE logic (both roles)
        │   └── MainActivity.kt            # UI + runtime permissions
        └── res/layout/activity_main.xml   # single-screen chat UI
```
