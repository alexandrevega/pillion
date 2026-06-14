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

## Frame Types

| Name            | ID | Direction     | Source      | Notes                                    |
|-----------------|---:|---------------|-------------|------------------------------------------|
| MOBILE_REQUEST  | 1  | dash -> phone | StreetCross | Dashboard requests an app action.        |
| MOBILE_RESPONSE | 2  | phone -> dash | StreetCross | Response to a mobile-side request.       |
| MOBILE_UPDATE   | 3  | phone -> dash | StreetCross | Mobile-side update.                      |
| MCU_REQUEST     | 4  | phone -> dash | StreetCross | MCU-side request from phone perspective. |
| MCU_RESPONSE    | 5  | dash -> phone | StreetCross | MCU-side response.                       |
| MCU_UPDATE      | 6  | phone -> dash | StreetCross | MCU-side update.                         |

## Payload Data Types

| Name    | ID | Source      | Notes                                             |
|---------|---:|-------------|---------------------------------------------------|
| VALUE   | 0  | StreetCross | Scalar/small payloads and empty command payloads. |
| POINTER | 1  | StreetCross | String, image, and larger payloads.               |

| Service                          | ID | Direction     | PayloadDataType | Payload                                                                                     | Notes                                                                                                                                            |
|----------------------------------|---:|---------------|-----------------|---------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| IMAGE_FRAME_UPDATE               | 0  | phone -> dash | pointer         | `imageType UInt8`, `sequence UInt16 LE`, JPEG bytes                                         | JPEG image frame                                                                                                                                 |
| ETA_UPDATE                       | 1  | phone -> dash | unknown         | `hour Int32 LE`, `minute Int32 LE`                                                          | StreetCross native signature is `getEtaMessage(int hour, int minute)`.                                                                           |
| NAVIGATION_STATUS_UPDATE         | 2  | phone -> dash | value           | status value, observed as `01 00` in setup                                                  | Used in setup burst. Exact status enum still pending, but `01 00` behaves as active.                                                             |
| CUR_ROAD_NAME_UPDATE             | 3  | phone -> dash | pointer         | `stringLength UInt8`, UTF-8 road name                                                       | StreetCross native signature is `getCurrentRoadMessage(String)`.                                                                                 |
| NEXT_TURN_DIST_UPDATE            | 4  | phone -> dash | pointer         | `turnIcon UInt8`, `distance Float32 LE`, unit string, next road string                      | StreetCross native signature is `getNextTurnDistanceMessage(byte, float, String, String)`. Used for simpler Yamaha/Garmin dashboards it seem?    |
| TBT_LIST_UPDATE                  | 5  | phone -> dash | value           | `listSize UInt16 LE`, `hasMoreData UInt8`, `00` padding                                     | StreetCross native signature is `getTurnByTurnUpdateMessage(short listSize, boolean hasMoreData)`. Related data service is `97`.                 |
| ACTIVE_TURN_LIST_UPDATE          | 6  | phone -> dash | value           | `activeIndex UInt16 LE`                                                                     | StreetCross native signature is `getActiveTurnByTurnIndexUpdateMessage(short activeIndex)`.                                                      |
| FAV_POI_LIST_UPDATE              | 7  | phone -> dash | unknown         | unknown                                                                                     | Favorite list metadata. Related data service is `98`.                                                                                            |
| GAS_POI_LIST_UPDATE              | 8  | phone -> dash | unknown         | unknown                                                                                     | Gas/stations list metadata. Related data service is `99`.                                                                                        |
| NAVI_EVENT_TEXT_UPDATE           | 9  | phone -> dash | pointer         | event text string, `eventType UInt8`, `eventSubType UInt8`, `visible UInt8`                 | StreetCross native signature is `getNaviEventTextUpdateMessage(String text, byte eventType, byte subType, boolean show)`.                        |
| HOME_SETTING_UPDATE              | 10 | phone -> dash | value           | observed `00 00` in setup                                                                   | Indicates home availability/settings.                                                                                                            |
| OFFICE_SETTING_UPDATE            | 11 | phone -> dash | value           | observed `00 00` in setup                                                                   | Indicates office availability/settings.                                                                                                          |
| APP_SETTING_UPDATE               | 12 | phone -> dash | value           | observed `00 00`, then `01 00` in setup                                                     | Exact meaning pending; StreetCross lists it as app setting update.                                                                               |
| GPS_STATUS_UPDATE                | 13 | phone -> dash | value           | observed `01 00` in setup                                                                   | Likely GPS available/active.                                                                                                                     |
| MAP_ZOOM_LEVEL_UPDATE            | 14 | phone -> dash | pointer         | observed `07 19 06 00 30 2E 32 20 6D 69`                                                    | StreetCross calls native `s(current,min,max,label,showLabel)`. Contains scale text after prefix bytes.                                           |
| ROUTE_CALC_PROGRESS_UPDATE       | 15 | phone -> dash | value           | `progress UInt8`                                                                            | StreetCross native signature is `getRouteCalcProgressUpdateMessage(byte)`.                                                                       |
| BT_THROUGHPUT_TIMEOUT_UPDATE     | 16 | phone -> dash | unknown         | unknown                                                                                     | Listed by StreetCross.                                                                                                                           |
| SPEED_LIMIT_UPDATE               | 17 | phone -> dash | pointer         | `limit Float32 LE`, speed unit string                                                       | StreetCross native signature is `getSpeedLimitMessage(float, String)` / `getNoSpeedLimitMessage(String)`. `0.0` means no known limit.            |
| VIA_POINT_COUNT_UPDATE           | 18 | phone -> dash | value           | `count UInt16 LE`                                                                           | StreetCross native signature is `getViaCountMessage(short)`.                                                                                     |
| NAVIGATION_INFO_UPDATE           | 19 | phone -> dash | pointer         | `turnIcon UInt8`                                                                            | next-turn distance+unit+road, remaining distance+unit, remaining time minutes, lane bytes                                                        |
| IMAGE_STOPPED_UPDATE             | 20 | phone -> dash | unknown         | unknown                                                                                     | Listed by StreetCross.                                                                                                                           |
| DIALOG_LC_PROMPT_UPDATE          | 25 | phone -> dash | unknown         | unknown                                                                                     | Listed by StreetCross.                                                                                                                           |
| DAY_NIGHT_MODE_UPDATE            | 31 | phone -> dash | value           | `00` day, `01` night; observed `01 00`                                                      |                                                                                                                                                  |
| DIALOG_PROMPT_UPDATE             | 32 | phone -> dash | unknown         | unknown                                                                                     | Listed by StreetCross.                                                                                                                           |
| AUTH_REQUEST                     | 33 | phone -> dash | pointer         | native signature `(short, int)`; Send `1C 07 00 01 00 00 00 00`                             | Starts authentication.                                                                                                                           |
| APP_START_ROUTE_REQUEST          | 48 | dash -> phone | inferred value  | inferred `listIndex UInt16 LE`, `itemIndex UInt16 LE`, `routeOption UInt8`                  | StreetCross dispatches `paramInt1`, `paramInt2`, `paramInt3`; native packer signature is `(short listIndex, short itemIndex, byte routeOption)`. |
| APP_STOP_ROUTE_REQUEST           | 49 | dash -> phone | inferred value  | empty                                                                                       | StreetCross calls stop navigation.                                                                                                               |
| APP_SKIP_NEXT_WAYPOINT_REQUEST   | 50 | dash -> phone | inferred value  | empty                                                                                       | StreetCross calls skip next waypoint.                                                                                                            |
| APP_MAP_ZOOM_IN_REQUEST          | 51 | dash -> phone | inferred value  | empty                                                                                       | StreetCross service constant says zoom in and dispatches to `q()`, which sends map command `274`. Respond with `MAP_ZOOM_LEVEL_UPDATE`           |
| APP_MAP_ZOOM_OUT_REQUEST         | 52 | dash -> phone | inferred value  | empty                                                                                       | StreetCross service constant says zoom out and dispatches to `p()`, which sends map command `273`. Respond with `MAP_ZOOM_LEVEL_UPDATE`          |
| APP_GO_HOME_REQUEST              | 53 | dash -> phone | inferred value  | inferred `routeOption UInt8`                                                                | StreetCross dispatches `paramInt1` to go home. Route option values are listed below.                                                             |
| APP_GO_OFFICE_REQUEST            | 54 | dash -> phone | inferred value  | inferred `routeOption UInt8`                                                                | StreetCross dispatches `paramInt1` to go office. Route option values are listed below.                                                           |
| APP_START_CONTENT_UPDATE_REQUEST | 55 | dash -> phone | inferred value  | `contentType UInt8`;                                                                        | StreetCross dispatches `paramInt1`. Native packer signature is `byte contentType`.                                                               |
| APP_STOP_CONTENT_UPDATE_REQUEST  | 56 | dash -> phone | inferred value  | `contentType UInt8`;                                                                        | StreetCross dispatches `paramInt1`. Native packer signature is `byte contentType`.                                                               |
| MCU_VEHICLE_SPEED_UPDATE         | 65 | dash -> phone | inferred value  | inferred `speedUnit UInt8`, `speedValue UInt16 LE`                                          | StreetCross dispatches two params. Native packer signature is `(byte speedUnit, short speedValue)`.                                              |
| MCU_ESN_UPDATE                   | 66 | dash -> phone | pointer         | string payload                                                                              | First dashboard frame in auth flow. StreetCross dispatches `paramString1`.                                                                       |
| MCU_SYSINFO_UPDATE               | 69 | dash -> phone | pointer         | string payload                                                                              | StreetCross dispatches `paramString1`. Useful for diagnostics.                                                                                   |
| MCU_DIALOG_USER_SELECT_UPDATE    | 70 | dash -> phone | inferred value  | inferred two small integer fields                                                           | StreetCross dispatches `paramInt1`, `paramInt2`.                                                                                                 |
| IMAGE_FRAME_UPDATE_ACK           | 80 | dash -> phone | value           | ack payload, often one byte                                                                 | Acknowledges image frame.                                                                                                                        |
| MCU_ESN_UPDATE_ACK               | 81 | phone -> dash | value           | native signature `boolean`;                                                                 | Acknowledges ESN update.                                                                                                                         |
| AUTH_REQUEST_ACK                 | 82 | dash -> phone | unknown         | unknown                                                                                     |                                                                                                                                                  |
| AUTH_REQUEST_SEC_DATA            | 83 | dash -> phone | pointer         | obfuscated security data                                                                    | Payload contains obfuscated `"SEC_DATA"` challenge. De-obfuscates with XOR `0x0A`.                                                               |
| AUTH_REQUEST_SEC_DATA_ACK        | 84 | phone -> dash | pointer         | nonce bytes                                                                                 | Echoes the de-obfuscated nonce.                                                                                                                  |
| TBT_LIST_DATA_UPDATE             | 97 | phone -> dash | pointer         | `itemIndex UInt16 LE`, `icon UInt8`, `distance Float32 LE`, unit string, instruction string | StreetCross native signature is `getTurnByTurnDataUpdateMessage(short, byte, float, String, String)`.                                            |
| FAV_POI_LIST_DATA_UPDATE         | 98 | phone -> dash | pointer         | unknown                                                                                     | Favorite POI page data.                                                                                                                          |
| GAS_POI_LIST_DATA_UPDATE         | 99 | phone -> dash | pointer         | unknown                                                                                     | Gas/stations POI page data.                                                                                                                      |

Reference: [`ServiceType.kt`](../composeApp/src/commonMain/kotlin/app/pillion/protocol/ServiceType.kt).

## Content Types

| Content                        | ID | Source      | Notes                                      |
|--------------------------------|---:|-------------|--------------------------------------------|
| NAVI_IMAGE                     | 1  | StreetCross | Start/stop normal navigation image stream. |
| TBT_LIST                       | 2  | StreetCross | Turn-by-turn list content.                 |
| FAVORITE_LIST                  | 3  | StreetCross | Favorites list content.                    |
| STATIONS_LIST                  | 4  | StreetCross | Gas/stations list content.                 |
| NAVI_IMAGE_FOR_THROUGHPUT_MODE | 11 | StreetCross | Throughput test/navigation image mode.     |

## Route Option Types

| Option    | ID | Source      | StreetCross behavior                                                                                      |
|-----------|---:|-------------|-----------------------------------------------------------------------------------------------------------|
| UNKNOWN   | 0  | StreetCross | Unknown/default.                                                                                          |
| NEW_ROUTE | 1  | StreetCross | Start a new route to the selected item. If already navigating, StreetCross stops the current route first. |
| NEXT_STOP | 2  | StreetCross | Add selected item as next stop while navigating.                                                          |
| LAST_STOP | 3  | StreetCross | Add selected item as final stop while navigating.                                                         |

## StreetCross Event Types

| Event   | ID |
|---------|---:|
| TRAFFIC | 0  |
| SPEED   | 1  |
| CAMERA  | 2  |
| BORDER  | 3  |
| SCHOOL  | 4  |
| OTHER   | 5  |

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

Only **one** projection app can own the RFCOMM link at a time — close Garmin StreetCross before connecting.

The dashboard exposes an MFi ExternalAccessory named `Communication Control Unit` by `YAMAHA MOTOR Co.Ltd`.
The accessory advertises `com.garmin.navilite.data` and `com.yconnect.data`; We must select `com.garmin.navilite.data` for the navigation display protocol.
