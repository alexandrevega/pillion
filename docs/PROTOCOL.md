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

## Native Unpack Status Codes

StreetCross exposes these status codes through `NaviLiteUnpackedMessageStatus`. The native unpacker returns them before higher-level Java dispatch touches the message.

| Status                                           | ID | Meaning                                                                                                              |
|--------------------------------------------------|---:|----------------------------------------------------------------------------------------------------------------------|
| `STATUS_OK`                                      | 0  | Header, checksum, frame/service relation, payload data type, payload length, and payload content were accepted.      |
| `STATUS_ERROR_SIZE_LESS_THAN_HEADER`             | 1  | Fewer than 12 header bytes were available.                                                                           |
| `STATUS_ERROR_MAGIC_CODE_FAIL`                   | 2  | The `nAl@` magic did not match.                                                                                      |
| `STATUS_ERROR_PAYLOAD_LEGNTH_NOT_MATCH`          | 3  | Header payload length and available bytes did not match. StreetCross keeps the misspelling in the Java constant.     |
| `STATUS_ERROR_SERVICE_TYPE_NOT_MATCH_FRAME_TYPE` | 4  | Service id is not valid for the supplied frame type.                                                                 |
| `STATUS_ERROR_SERVICE_TYPE_NOT_MATCH_PAYLOAD`    | 5  | Service id is known, but the payload data type or declared payload length does not match the native unpacker branch. |
| `STATUS_ERROR_PAYLOAD_CONTENT_ERROR`             | 6  | Payload shape passed coarse checks, but required content was missing or invalid.                                     |
| `STATUS_ERROR_CHECKSUM_FAIL`                     | 7  | CRC-32/MPEG-2 did not match the listed checksum.                                                                     |

## Receiver Classification Guidance

A receiver should distinguish four different non-success states before handing a frame to application logic:

| Classification                  | Meaning | Recommended behavior |
|---------------------------------|-----------------------| --- |
| unsupported known service       | The service id is a documented NaviLite service, but it is not an executable dashboard command in the current receiver path. Examples include `IMAGE_FRAME_UPDATE_ACK`, `AUTH_REQUEST_ACK`, or other handshake/image services handled elsewhere. | Do not treat as an unknown command. Route it to the responsible subsystem or log it as a known-but-not-actionable service.                         |
| unknown service                 | The service id is not in the documented service table.                                                                                                                                                                                           | Log the raw service id and payload metadata, then ignore safely.                                                                                         |
| malformed known service         | The service id is known, but the payload data type, length, or required content does not match the native unpacker branch.                                                                                                                       | Reject the command and log the exact reason. This maps most closely to native unpack status `5` or `6`.                                                  |

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

## Service Types

| Service                          | ID | Direction     | PayloadDataType | Payload                                                                                     | Notes                                                                                                                                            |
|----------------------------------|---:|---------------|-----------------|---------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| IMAGE_FRAME_UPDATE               | 0  | phone -> dash | pointer         | `imageType UInt8`, `sequence UInt16 LE`, JPEG bytes                                         | JPEG image frame                                                                                                                                 |
| ETA_UPDATE                       | 1  | phone -> dash | unknown         | `minutesAfterMidnight UInt32 LE`                                                            | StreetCross native method takes `hour, minute`, but `libnaviliteprotocol.so` packs one UInt32 equal to `hour * 60 + minute`.                     |
| NAVIGATION_STATUS_UPDATE         | 2  | phone -> dash | value           | status value, observed as `01 00` in setup                                                  | Used in setup burst. Exact status enum still pending, but `01 00` behaves as active.                                                             |
| CUR_ROAD_NAME_UPDATE             | 3  | phone -> dash | pointer         | raw UTF-8 road name, max 64 bytes, no length prefix                                         | StreetCross native signature is `getCurrentRoadMessage(String)`. The native packer writes the raw string only.                                   |
| NEXT_TURN_DIST_UPDATE            | 4  | phone -> dash | pointer         | `turnIcon UInt8`, `distance Float32 LE`, unit string, next road string                      | StreetCross native signature is `getNextTurnDistanceMessage(byte, float, String, String)`. Used for simpler Yamaha/Garmin dashboards it seem?    |
| TBT_LIST_UPDATE                  | 5  | phone -> dash | value           | `listSize UInt16 LE`, `hasMoreData UInt8`                                                   | StreetCross native signature is `getTurnByTurnUpdateMessage(short listSize, boolean hasMoreData)`. Related data service is `97`.                 |
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

## Vehicle Model Detection

The model is derived during authentication from the `lcSwPartNumber` / `SW_Part_Number` value received with `AUTH_REQUEST_SEC_DATA` service `83`:

| StreetCross enum | Ordinal | `SW_Part_Number` substring | Behavior |
|------------------|--------:|----------------------------|-----|
| `MODEL_IXWW22`   | 0       | `006-B3952`                | Uses the base LinkCard map layout 480px by 234px. Sends the simpler `NEXT_TURN_DIST_UPDATE` service `4`. |
| `MODEL_IMWW23`   | 1       | `006-B4160` or `006-B4920` | Uses `layout_imww23_map` 480px by 240px. Sends richer `NAVIGATION_INFO_UPDATE` service `19`, including remaining distance/time and lane bytes. |
| `MODEL_INVALID`  | 2       | any other or missing value | Falls back to base dimensions/layout and reports an invalid model to callbacks. |

After model detection, StreetCross calls `setHeadunitVehicleInfo` in Garmin OS with the enum ordinal plus height and width. Compatible implementations should treat the software part number as the proven model source and keep service `69` as diagnostic data until real hardware logs prove additional semantics. If the software part number cannot be parsed, compatibility mode should keep both service `4` and service `19` enabled; once the model is known, mirror StreetCross and send service `4` only for `MODEL_IXWW22`/`MODEL_INVALID`, or service `19` only for `MODEL_IMWW23`.

## Content Types

| Content                        | ID | Source      | Notes                                      |
|--------------------------------|---:|-------------|--------------------------------------------|
| NAVI_IMAGE                     | 1  | StreetCross | Start/stop normal navigation image stream. |
| TBT_LIST                       | 2  | StreetCross | Turn-by-turn list content.                 |
| FAVORITE_LIST                  | 3  | StreetCross | Favorites list content.                    |
| STATIONS_LIST                  | 4  | StreetCross | Gas/stations list content.                 |
| NAVI_IMAGE_FOR_THROUGHPUT_MODE | 11 | StreetCross | Throughput test/navigation image mode.     |

## Turn Arrow Icon Ordinals

StreetCross does not send turn arrow artwork to the dashboard. Garmin's route engine exposes
`GuidancePoint.getIconOrdinal()`, and StreetCross forwards that byte into
`NEXT_TURN_DIST_UPDATE`, `NAVIGATION_INFO_UPDATE`, and `TBT_LIST_DATA_UPDATE`. The Yamaha/Garmin
dashboard owns the icon artwork and renders the matching native icon.

The source enum is `NavInfo.TurnArrowIconType` in Garmin StreetCross 1.86:

| Icon                        | Ordinal | Notes                 |
|-----------------------------|--------:|-----------------------|
| `TURN_ARROW_ARRIVING`       | 0       | Destination / arrival |
| `TURN_ARROW_ARRIVING_L`     | 1       | Arrival on left       |
| `TURN_ARROW_ARRIVING_R`     | 2       | Arrival on right      |
| `TURN_ARROW_ARRIVING_VIA`   | 3       | Via point             |
| `TURN_ARROW_ARRIVING_VIA_L` | 4       | Via point on left     |
| `TURN_ARROW_ARRIVING_VIA_R` | 5       | Via point on right    |
| `TURN_ARROW_BEAR_KEEP_L`    | 6       | Keep left             |
| `TURN_ARROW_BEAR_KEEP_R`    | 7       | Keep right            |
| `TURN_ARROW_CONTINUE`       | 8       | Continue / straight   |
| `TURN_ARROW_DRIVE_TO`       | 9       | Drive to              |
| `TURN_ARROW_EXIT_L`         | 10      | Exit left             |
| `TURN_ARROW_EXIT_R`         | 11      | Exit right            |
| `TURN_ARROW_EXIT_UNSPEC`    | 12      | Exit, unknown side    |
| `TURN_ARROW_FERRY`          | 13      | Ferry                 |
| `TURN_ARROW_RNDABT_GENERIC` | 14      | Roundabout            |
| `TURN_ARROW_SHARPTURN_L`    | 32      | Sharp left            |
| `TURN_ARROW_SHARPTURN_R`    | 33      | Sharp right           |
| `TURN_ARROW_TURN_L`         | 34      | Left                  |
| `TURN_ARROW_TURN_R`         | 35      | Right                 |
| `TURN_ARROW_UTURN_L`        | 36      | U-turn left           |
| `TURN_ARROW_UTURN_R`        | 37      | U-turn right          |
| `TURN_ARROW_INV_ICON`       | 69      | Invalid / unknown     |

Roundabout spoke icons also exist at ordinals `15...22`, reverse roundabouts at `23...31`, and
heading icons at `38...68`. A compatible implementation can send the generic roundabout icon unless
the routing source gives a reliable exit angle.

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

## Dashboard Commands

| Dashboard command     | Service | PayloadDataType | Payload                                                                              | Phone action                                                                                                                                                                                                                                                                                                         | Confidence                                                                                                                                                                                                  |
|-----------------------|--------:|-----------------|--------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| start route from list | 48      | pointer         | `itemIndex UInt16 LE`, `listIndex UInt16 LE`, `routeOption UInt8`                    | Starts the selected dashboard favorite. `NEXT_STOP` inserts a coordinate-backed saved place before the next remaining stop while navigating; `LAST_STOP` appends it as the final intermediate stop. Saved routes can start as `NEW_ROUTE`; they are not inserted as stops because they are route objects, not POIs. | Confirmed by StreetCross dispatch, native `GetStartRouteRequest(short, short, byte)` byte order, and `LinkCardMapManager.v0(...)` route-option behavior.                                                    |
| stop route            | 49      | value           | empty                                                                                | Stops active route.                                                                                                                                                                                                                                                                                                  | Confirmed by StreetCross dispatch.                                                                                                                                                                          |
| skip next waypoint    | 50      | value           | empty                                                                                | Skips the next waypoint when one exists.                                                                                                                                                                                                                                                                             | Confirmed by StreetCross dispatch.                                                                                                                                                                          |
| zoom in               | 51      | value           | empty                                                                                | Increases Yamaha display map zoom multiplier.                                                                                                                                                                                                                                                                        | Confirmed service constant; live command direction still useful to verify.                                                                                                                                  |
| zoom out              | 52      | value           | empty                                                                                | Decreases Yamaha display map zoom multiplier.                                                                                                                                                                                                                                                                        | Confirmed service constant; live command direction still useful to verify.                                                                                                                                  |
| go home               | 53      | value           | `routeOption UInt8`, `00`                                                            | Starts navigation to the saved place whose metadata matches a home location.                                                                                                                                                                                                                                         | Confirmed in StreetCross: service `53` dispatches `paramInt1` to `LinkCardMapManager.L(...)`, which calls the same route-start helper as favorite routes. Mirror `NEW_ROUTE`, `NEXT_STOP`, and `LAST_STOP`. |
| go office             | 54      | value           | `routeOption UInt8`, `00`                                                            | Starts navigation to the saved place whose metadata matches a work/office location.                                                                                                                                                                                                                                  | Confirmed in StreetCross: service `54` dispatches `paramInt1` to the office route-start handler and uses the same `routeOption` behavior. Mirror `NEW_ROUTE`, `NEXT_STOP`, and `LAST_STOP`.                 |
| start content update  | 55      | value           | live payload `01 00` for navigation image; StreetCross supports `02 00` for TBT-only | Navigation image refreshes nav status, GPS status, app setting, and zoom level. TBT-only sends the current native turn-by-turn list and active index, then pauses JPEG frames.                                                                                                                                       | Navigation image confirmed on physical Yamaha dashboard; TBT-only confirmed from StreetCross decompile.                                                                                                     |
| stop content update   | 56      | value           | live payload `01 00` for navigation image; StreetCross supports `02 00` for TBT-only | Navigation image sends image-stopped update. TBT-only clears the TBT content mode. If the accessory disconnects after this, return to searching and retry soon.                                                                                                                                                      | Navigation image confirmed on physical Yamaha dashboard; TBT-only confirmed from StreetCross decompile.                                                                                                     |

## Dialog Presets

StreetCross exposes generic dialog packers plus toll-road-specific helper methods. The toll helpers do not use new service ids; they call the generic dialog packers with fixed bytes:

| Helper                                                            | Service | Payload                                                                                                |
|-------------------------------------------------------------------|--------:|--------------------------------------------------------------------------------------------------------|
| `getTollRoadConfirmationDialogMessageWithId(dialogID, messageID)` | 25      | `dialogID`, `33` (`DIALOG_YES_NO`), `messageID`, `10` seconds, `4` default response                    |
| `getTollRoadCannotAvoidDialogMessageWithId(dialogID, messageID)`  | 25      | `dialogID`, `11` (`DIALOG_OK`), `messageID`, `0` seconds, `1` default response                         |
| `getTollRoadConfirmationDialogMessage(dialogID, message)`         | 32      | `dialogID`, `33` (`DIALOG_YES_NO`), `messageLength`, `10` seconds, `4` default response, message bytes |
| `getTollRoadCannotAvoidDialogMessage(dialogID, message)`          | 32      | `dialogID`, `11` (`DIALOG_OK`), `messageLength`, `0` seconds, `1` default response, message bytes      |

StreetCross manages dialog lifecycle with a small callback registry:

- Dialog ids are allocated from `1...254`; `255` is treated as invalid/no id available.
- The sender registers a pending callback before showing a dialog.
- When service `70` arrives, `dialogID` and `buttonID` are passed to the callback and the pending entry is removed.
- If the dashboard sends service `70` for an unknown or already-cleared id, StreetCross logs the mismatch and otherwise ignores it.
- Pending dialogs should be cleared when the NaviLite connection restarts, because dashboard dialogs are session-local.

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
   3    var    jpeg        baseline JPEG, 480 × 240 or 480 × 236
```

The dash decodes the JPEG and replies **`IMAGE_ACK` (80)**. The sender waits for the ack (skipping any
other frames that arrive) before sending the next image, which naturally paces throughput.

- **Resolution:** 480 × 240 or 480 × 236.
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
  phone → IMAGE_FRAME_UPDATE(0)         # imageType + seq + JPEG(480x240 or 480×236)
  dash  → IMAGE_ACK(80)
```

Only **one** projection app can own the RFCOMM link at a time — close Garmin StreetCross before connecting.

The dashboard exposes an MFi ExternalAccessory named `Communication Control Unit` by `YAMAHA MOTOR Co.Ltd`.
The accessory advertises `com.garmin.navilite.data` and `com.yconnect.data`; We must select `com.garmin.navilite.data` for the navigation display protocol.
