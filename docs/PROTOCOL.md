# NaviLite protocol

This is an independent description of the **NaviLite** projection protocol, observed by watching the
Bluetooth traffic between a phone and a Garmin-powered Yamaha dashboard (CCU). It documents enough to
push navigation/screen imagery to the dash for interoperability. No Garmin or Yamaha code is
reproduced here — this is an original write-up of an observed wire format.

> Everything below was derived from black-box observation on an **MT-07 (2025)** (CCU part
> `006-B4160-00`). Other Garmin-CCU Yamahas appear to use the same protocol; corrections welcome.

## Transport

- **Bluetooth Classic**, RFCOMM / Serial Port Profile (SPP).
- Service UUID: `00007220-0000-1000-8000-00805F9B34FB` (the 16-bit short form is `0x7220`).
- The phone is the RFCOMM **client**; the dash (CCU) advertises the SPP service. The phone must
  already be **bonded** with the dash. No additional pairing secret is required (see [Auth](#authentication)).
- Connect with an *insecure* RFCOMM socket to the service record above.

## Frame format

Every message is a single framed packet:

```
 offset  size  field
   0      4    magic            "nAl@"  (0x6E 0x41 0x6C 0x40)
   4      1    version          observed: 0x01
   5      1    frameType        e.g. PHONE = 6
   6      1    serviceType      see table below
   7      4    payloadSize      uint32, little-endian
  11      1    payloadDataType  VALUE = 0, POINTER = 1
  12      4    crc              uint32, little-endian (see below)
  16    var    payload          payloadSize bytes
```

The header is **12 bytes** (offsets 0–11, i.e. up to and including `payloadDataType`). The 4-byte
CRC follows the header, then the payload.

### CRC

`crc = CRC-32/MPEG-2` computed over **header (12 bytes) + payload**, then stored little-endian.

- polynomial `0x04C11DB7`
- init `0xFFFFFFFF`
- **no** input/output reflection
- xor-out `0x00000000`

Reference implementation: [`Crc32Mpeg2.kt`](../composeApp/src/commonMain/kotlin/app/pillion/protocol/Crc32Mpeg2.kt).

### Frame & payload-data types

| name   | value | meaning                                    |
|--------|------:|--------------------------------------------|
| PHONE  | 6     | frames the phone sends to the dash         |
| VALUE  | 0     | `payloadDataType`: small inline value      |
| POINTER| 1     | `payloadDataType`: variable-length payload |

## Service types

| service                  | id | direction      | notes                                  |
|--------------------------|---:|----------------|----------------------------------------|
| IMAGE_FRAME_UPDATE       | 0  | phone → dash   | JPEG image frame                       |
| IMAGE_ACK                | 80 | dash → phone   | acknowledges an image frame            |
| NAV_STATUS               | 2  | phone → dash   |                                        |
| ROAD                     | 3  | phone → dash   |                                        |
| HOME                     | 10 | phone → dash   |                                        |
| OFFICE                   | 11 | phone → dash   |                                        |
| APP_SETTING              | 12 | phone → dash   |                                        |
| GPS                      | 13 | phone → dash   |                                        |
| ZOOM                     | 14 | phone → dash   |                                        |
| SPEED_LIMIT              | 17 | phone → dash   |                                        |
| DAY_NIGHT                | 31 | phone → dash   | 0 = day, 1 = night                     |
| AUTH_REQUEST             | 33 | phone → dash   | starts authentication                  |
| ESN_UPDATE               | 66 | dash → phone   | first frame the dash sends             |
| IMAGE_ACK (alt)          | 80 | dash → phone   |                                        |
| ESN_ACK                  | 81 | phone → dash   |                                        |
| AUTH_ACK                 | 82 | dash → phone   |                                        |
| AUTH_REQUEST_SEC_DATA    | 83 | dash → phone   | carries the auth challenge ("SEC_DATA")|
| AUTH_REQUEST_SEC_DATA_ACK| 84 | phone → dash   | the auth response ("SEC_DATA_ACK")     |

Reference: [`ServiceType.kt`](../composeApp/src/commonMain/kotlin/app/pillion/protocol/ServiceType.kt).

## Authentication

There is **no per-bike key, certificate, or pairing secret to extract.** Authentication is a trivial
de-obfuscate-and-echo challenge:

1. After connect, the dash sends **`ESN_UPDATE` (66)**. The phone replies **`ESN_ACK` (81)**.
2. The phone sends **`AUTH_REQUEST` (33)**.
3. The dash sends **`AUTH_REQUEST_SEC_DATA` (83)**. Its payload is:

   ```
   payload = ( ASCII(part-number) + nonce[4] )  XOR 0x0A     (byte-wise)
   ```

   i.e. every byte is XOR-ed with `0x0A`. De-obfuscating reveals the CCU part number as an ASCII
   string (e.g. `006-B4160-00`) followed by a 4-byte nonce.
4. The phone replies **`AUTH_REQUEST_SEC_DATA_ACK` (84)** whose payload is just the **de-obfuscated
   4-byte nonce** (the last four bytes of the SEC_DATA payload, each XOR `0x0A`).

That's it — echoing back the de-obfuscated nonce authenticates the session. Reference:
[`Auth.kt`](../composeApp/src/commonMain/kotlin/app/pillion/protocol/Auth.kt).

### Setup burst

After auth, the phone sends a short burst of state frames before images are accepted (values are
the ones observed; the dash tolerates reasonable defaults): `NAV_STATUS`, `DAY_NIGHT`, `HOME`,
`OFFICE`, `GPS`, `APP_SETTING`, `ZOOM`, `ROAD`, `SPEED_LIMIT`, then `GPS`/`APP_SETTING` again.
See [`Handshake.kt`](../composeApp/src/commonMain/kotlin/app/pillion/core/Handshake.kt).

## Image channel

Send each frame as **`IMAGE_FRAME_UPDATE` (service 0, `POINTER`)**:

```
 offset  size  field
   0      1    imageType   observed 3 = expanded navigation view
   1      2    sequence    uint16, little-endian, increments per frame
   3    var    jpeg        baseline JPEG, 480 × 240
```

The dash decodes the JPEG and replies **`IMAGE_ACK` (80)**. The sender waits for the ack (skipping any
other frames that arrive) before sending the next image, which naturally paces throughput.

- **Resolution:** 480 × 240.
- **Throughput:** roughly **14–15 fps** at JPEG quality ≈ 40 on a fast phone over Bluetooth; higher
  quality → larger frames → fewer fps. The frame rate emerges from encode + Bluetooth throughput; it
  is not commanded.

## End-to-end summary

```
dash → ESN_UPDATE(66)
phone → ESN_ACK(81)
phone → AUTH_REQUEST(33)
dash → AUTH_REQUEST_SEC_DATA(83)        # ("partnumber"+nonce) XOR 0x0A
phone → AUTH_REQUEST_SEC_DATA_ACK(84)   # nonce XOR 0x0A
phone → setup burst (nav status, day/night, gps, zoom, ...)
loop:
  phone → IMAGE_FRAME_UPDATE(0)         # imageType + seq + JPEG(480x240)
  dash  → IMAGE_ACK(80)
```

Only **one** projection app can own the RFCOMM link at a time — close Garmin StreetCross / Yamaha
MyRide before connecting.
