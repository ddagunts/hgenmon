# Generator BLE Protocol Notes

Independent protocol notes for the BT module used in compatible inverter generators. The module identifies itself as `Z44A` (Americas) or `Z44J` (Japan).

## Supported generators

EU2000, EU2200, EU2200i, EU26iJ, EU3200, EU3200i, EU3200iP, EU7000, EU7000is, and other models that expose the GATT services below.

## GATT services

Four 128-bit services, each with several characteristics. Base patterns:

| Service | UUID | Purpose |
|---|---|---|
| `GENERATOR_DATA_SERVICE` | `01B60001-875A-4C56-B8BF-5103CAFAEEC7` | Engine/inverter telemetry over a CAN-bus tunnel |
| `REMOTE_CONTROL_SERVICE` | `066B0001-5D90-4939-A7BA-7B9222F53E81` | Engine start/stop, unlock |
| `BT_UNIT_CONTROL_SERVICE` | `92CD0001-4F59-4599-A73C-C92C4AC7AADE` | BT module itself — firmware version, pairing/unlock |
| `DIAGNOSTIC_CONTROL_SERVICE` | `B4EF0001-62D2-483C-8293-119E2A99A82B` | Diagnostic command/response, unit operation |

## Characteristics

### Generator Data Service (`01B6xxxx-…`)
| UUID | Name | Direction |
|---|---|---|
| `01B60002-…` | `GENERATOR_DATA_REQUEST` | write (sender → gen) |
| `01B60003-…` | `GENERATOR_DATA_RESPONSE` | notify |
| `01B60004-…` | `CAN_DATA_DRIP` | notify (periodic CAN frames) |
| `01B60005-…` | `ERROR_AND_WARNING_INFORMATION` | notify |

### Remote Control Service (`066Bxxxx-…`)
| UUID | Name |
|---|---|
| `066B0002-…` | `ENGINE_CONTROL` |
| `066B0003-…` | `ENGINE_DRIVE_STATUS` |
| `066B0004-…` | `CONTROL_SEQUENCE_CONFIGURATION` |
| `066B0005-…` | `FRAME_NUMBER` |
| `066B0006-…` | `UNLOCK_PROTECT` |
| `066B0007-…` | `CHANGE_PASSWORD` |

### BT Unit Control Service (`92CDxxxx-…`)
| UUID | Name |
|---|---|
| `92CD0002-…` | `BT_UNIT_UNLOCK_PROTECT` |
| `92CD0003-…` | `BT_UNIT_CHANGE_PASSWORD` |
| `92CD0004-…` | `BT_UNIT_UNIT_OPERATION` |
| `92CD0005-…` | `BT_FIRMWARE_VERSION` |
| `92CD0006-…` | `CAN_FIRMWARE_VERSION` |
| `92CD0007-…` | `BT_UNIT_FRAME_NUMBER` |

### Diagnostic Control Service (`B4EFxxxx-…`)
| UUID | Name |
|---|---|
| `B4EF0002-…` | `DIAGNOSTIC_COMMAND` |
| `B4EF0003-…` | `DIAGNOSTIC_RESPONSE` |
| `B4EF0004-…` | `FIRMWARE_VERSION` |
| `B4EF0005-…` | `UNIT_OPERATION` |

CCCD (notify enable) is the standard `00002902-0000-1000-8000-00805f9b34fb`.

Default unlock password is the ASCII string `"00000000"` (8 zeros). Any password matching `^[0]*$` is normalized to that value.

## Wire frame format

The on-wire protocol on the diagnostic command/response channel is an **ASCII-framed serial-style protocol**:

```
┌─────┬──────────┬───────┬──────────┬────────┬─────────┬─────┐
│ STX │ Function │ Group │  DataNo  │  Data  │ Checksum│ EOT │
│ 0x01│ 1 char   │ 1 char│  2 chars │ 2 chars│ 2 chars │ 0x04│
└─────┴──────────┴───────┴──────────┴────────┴─────────┴─────┘
 1 B    1 B       1 B     2 B        2 B      2 B       1 B    = 10 bytes total
```

- **Function**: `'B'` = READ_1 (read 1 byte), `'C'` = READ_16 (read 16 bytes), `'D'` = WRITE_1
- **Group**: a single ASCII letter naming a register page (`'A'`, `'B'`, `'C'`, `'D'`, …)
- **DataNo**: 2 hex chars — the byte address within the group (e.g. `"17"`, `"00"`)
- **Data**: 2 hex chars — 1 payload byte; `"00"` in requests, the actual value in responses
- **Checksum**: 2 hex chars representing 1 byte = XOR of the 6 preceding ASCII bytes (Function ⊕ Group ⊕ DataNo[0] ⊕ DataNo[1] ⊕ Data[0] ⊕ Data[1])

**Response** from generator: identical 10-byte frame, but the indication payload on the wire is prefixed by `0x80` (the direction byte), making the indication payload 11 bytes total.

### Unlock format (write to `UNLOCK_PROTECT` characteristic)

Two-step:
1. Write 9 zero bytes: `00 00 00 00 00 00 00 00 00` (clear/reset)
2. Write STX + password as ASCII: `01 30 30 30 30 30 30 30 30` (`STX + "00000000"` for the default password)

No EOT, no checksum — different framing from the diagnostic command channel.

### Serial read

Read `FRAME_NUMBER` characteristic → 17 bytes: `45 41 4d 54 2d 32 34 36 37 30 32 31 20 00 00 00 00` = `"EAMT-2467021 \0\0\0\0"` (ASCII, space-padded then NUL-padded).

## CAN IDs (frame selectors)

Used as the 16-bit selector in bytes `[4..5]` of the request. Internally these are CAN-bus arbitration IDs that the BT module tunnels over BLE.

| Name | dec | hex | Payload (DataItems) |
|---|---|---|---|
| `INSUFFICIENT_OIL` | 258  | 0x102 | (oil alarm) |
| `ECU_STATUS`       | 786  | 0x312 | ECO_SWITCH_STATUS, FUEL_REMAINS_LEVEL |
| `INV_STATUS`       | 802  | 0x322 | inverter status |
| `INV_INFO`         | 818  | 0x332 | OUTPUT_POWER, OUTPUT_CURRENT, POWER_VOLTAGE |
| `ECU_INFO`         | 834  | 0x342 | (ECU info) |
| `INV_INFO2`        | 850  | 0x352 | **ENGINE_HOURS** |
| `ECU_INFO_ETC`     | 866  | 0x362 | FUEL_REMAINS (remaining runtime), FUEL_LEVEL |
| `ECU_DIAG_A`       | 930  | 0x3A2 | FAULT |
| `BT_UNIT_DIAG`     | 933  | 0x3A5 | FAULT (BT module) |
| `INV_DIAG`         | 946  | 0x3B2 | FAULT (inverter) |
| `OUTPUT_SETTING`   | 1490 | 0x5D2 | OUTPUT_VOLTAGE_SETTING |
| `MAINTENANCE_INFO` | 1538 | 0x602 | (oil-change / spark plug counters) |
| `MAINTENANCE_INFO2`| 1554 | 0x612 | (additional maint counters) |
| `ECU_ERR_HISTORY`  | 1570 | 0x622 | (fault history) |
| `MACHINE_CODE`     | 1968 | 0x7B0 | model identifier |
| `FREEZE_DATA`      | 1969 | 0x7B1 | freeze-frame at fault |
| `FUNC_COMMAND_RES` | 1970 | 0x7B2 | response to a function command |
| `FUNC_COMMAND`     | 1984 | 0x7C0 | container for actionable command |

## Function commands (engine actions)

Bytes `[12..13]` when `CanId == FUNC_COMMAND` and `byte[6] == 64`:

| Name | dec | hex |
|---|---|---|
| `STOP_ENGINE` | 1026 | 0x402 |
| `START_ECO`   | 1027 | 0x403 |
| `STOP_ECO`    | 1028 | 0x404 |

Note: only **stop** engine — there is no remote start command. The hardware/regulatory model is "no unattended start."

## Periodic polling set

The diagnostic-channel poll cycle covers: output setting, insufficient oil, ECU status, inverter status, engine hours, inverter info, ECU info, ECU info-etc.

## DataItems (telemetry fields) — Z44A profile (EU2200i family)

Each DataItem reads its value byte-by-byte using READ_1 (`B`) commands at the listed group + dataNo positions; the bytes are assembled MSB-first to form a multi-byte value.

| DataItem | Group | DataNo bytes | Multi-byte? | minValue | Notes |
|---|---|---|---|---|---|
| `MACHINE_CODE`       | `A` | `00,01,02,03,04` | yes (5B identifier) | — | Static unit identifier (≠ serial number) |
| `OUTPUT_POWER`       | `B` | `17,18` | yes (16-bit) | 50.0 | **× 10 = watts** (see verification below) |
| `OUTPUT_CURRENT`     | `B` | `13,14` | yes (16-bit) | 0.6 | Amps × some scale (TBD) |
| `ECO_SWITCH_STATUS`  | `B` | `19`    | no  | — | One byte; values seen: 0x00 / 0x02 / 0x03 |
| `ENGINE_HOURS`       | `B` | `00,01` | yes (16-bit) | — | Hours of engine runtime |
| `WARNING`            | `C` | `10`    | no  | — | Warning bitfield |
| `FAULT`              | `D` | `10,11` | yes | — | Fault bitfield |

Other profiles exist (Z23W, Z37A, Z45A) with different DataItem tables; this implementation targets Z44A.

### Verified ground truth

Captured against a unit under a 1290 W load:
- `BB18` response data byte = `0x81` (= 129 decimal)
- `BB17` response data byte = `0x00`
- Combined as 16-bit MSB-first: `0x0081` = 129
- **Scaling: 129 × 10 = 1290 W ✓** — confirms OUTPUT_POWER is reported in **decawatts** (10 W units).

`minValue=50.0` appears to filter idle/noise readings below 50 W (= reported value < 5 in decawatts). The current app does not apply this filter — small values are shown verbatim.

### Confirmed engine control

`ENGINE_CONTROL` characteristic (UUID `066B0002`):
- Write 1 byte `0x00` = **stop engine**. Repeated 7× for reliability (BT module ACKs each write independently).

After write, `ENGINE_DRIVE_STATUS` indication payload first byte changes:
- `0x01` = engine running
- `0x02` = engine stopped / stopping / starting (needs more captures to disambiguate)

### Function (D = WRITE_1) commands not yet captured

Eco-on / eco-off / engine-stop equivalents via the diagnostic protocol use Function `D` (WRITE_1) — these would look like `01 44 <Group> <DataNo[2]> <Data[2]> <CS[2]> 04`. Not yet observed; needs a fresh capture during an explicit eco toggle.

## Response error flags

- `NO_ERROR` = 0
- `UART_ERROR` = 1 (BT module → ECU UART failure)
- `ECU_RESPONSE_ERROR` = 2

## Connection sequence

Observed against a Pixel 8 / Android 16:

1. BLE scan / connect to the generator's BT module (no bonding required).
2. `onServicesDiscovered` — match each UUID to its characteristic.
3. **Unlock handshake**:
   - `Write UNLOCK_PROTECT` (`066B0006-…`) — 9 zero bytes (clear/reset)
   - `Write UNLOCK_PROTECT` again — STX + 8 ASCII password chars (commits unlock)
   - `Read  FRAME_NUMBER` (`066B0005-…`) — reads the unit serial
4. `Read ENGINE_DRIVE_STATUS` (`066B0003-…`) — engine state check.
5. Subscribes (CCCD indicate) to: `DIAGNOSTIC_RESPONSE`, `ENGINE_DRIVE_STATUS`, and `ERROR_AND_WARNING_INFORMATION` if exposed.
6. **Poll loop** (every ~2 s): writes an ASCII 10-byte read frame to **`DIAGNOSTIC_COMMAND`** (`B4EF0002-…`). Responses arrive as indications on `DIAGNOSTIC_RESPONSE` (`B4EF0003-…`).

> Naming gotcha: the `GENERATOR_DATA_*` characteristics on the `01B6` service appear to be used only for the periodic CAN-frame drip setup. Day-to-day telemetry runs over the Diagnostic Control service, despite the name.

## Open questions / TODO

- Exact byte offsets + scaling for each DataItem.
- `ENGINE_DRIVE_STATUS` notify payload (engine state machine: START / DRIVING / STOPPING / STOP).
- Format of `ERROR_AND_WARNING_INFORMATION` notify payload.
- BT module advertised name format (for discovery filter).
- Whether the BT module ever requires bonding (`device.getBondState() == 12` is checked but only to extend timeouts).
- WARNING/FAULT bitfield → human-readable label mapping (currently surfaces raw hex in alarm notifications).
- BT module firmware variants — observed Z44A units have rejected reads of B00/B01 (ENGINE_HOURS) and D10/D11 (FAULT) with GATT status 3 while accepting B17/B18 (OUTPUT_POWER) and C10 (WARNING). May be firmware-version-dependent.
