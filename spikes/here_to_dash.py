#!/usr/bin/env python3
"""
End-to-end spike: real HERE route -> NaviLite turn-by-turn frames -> the dash emulator.

Proves the whole pipeline on a desktop with no phone and no Bluetooth:
  HERE Routing v8  ->  maneuver mapping (mirrors Pillion's HereRoutingProvider + NaviLiteTbt)
                   ->  NaviLite frames over TCP  ->  navilite-receiver decodes + displays.

Reads the HERE key from ../local.properties (here.api.key) so no key is hard-coded here.
Run:  python3 spikes/here_to_dash.py        (needs network to router.hereapi.com)
Open  http://localhost:8088  to watch the maneuvers render on the emulated dash.
"""
import json, os, socket, struct, sys, threading, time, urllib.parse, urllib.request

RECEIVER_DIR = "/Users/alex/Proyectos/navilite-receiver"
sys.path.insert(0, RECEIVER_DIR)
import receiver as R  # reuse build(), recv_frame(), constants, _nav, run_dash_server, run_viewer


def load_key() -> str:
    lp = os.path.join(os.path.dirname(__file__), "..", "local.properties")
    for line in open(lp):
        if line.startswith("here.api.key="):
            return line.split("=", 1)[1].strip()
    sys.exit("set here.api.key in local.properties")


def here_route(origin: str, dest: str, key: str) -> dict:
    params = urllib.parse.urlencode({
        "transportMode": "car",
        "origin": origin,
        "destination": dest,
        "return": "polyline,summary,actions,instructions",  # omit departureTime -> live traffic
        "apiKey": key,
    })
    with urllib.request.urlopen(f"https://router.hereapi.com/v8/routes?{params}", timeout=20) as r:
        return json.load(r)


def icon_of(action: str, direction) -> int:
    """HERE action+direction -> StreetCross turn-icon ordinal (mirrors NaviLiteTbt.iconOf)."""
    d = direction or ""
    if action == "turn":
        return {"slightlyLeft": 6, "slightlyRight": 7, "sharpLeft": 32, "sharpRight": 33,
                "left": 34, "right": 35}.get(d, 8)
    if action == "keep":
        return 7 if d == "right" else 6
    if action == "uTurn":
        return 37 if d == "right" else 36
    if action in ("roundaboutEnter", "roundaboutExit", "roundaboutPass"):
        return 14
    if action == "ferry":
        return 13
    if action in ("ramp", "exit"):
        return 11 if d == "right" else (10 if d == "left" else 12)
    if action == "arrive":
        return 1 if d == "left" else (2 if d == "right" else 0)
    if action in ("depart", "continue", "merge"):
        return 8
    return 69


def road_from(instruction: str) -> str:
    for m in (" onto ", " on "):
        i = instruction.find(m)
        if i >= 0:
            return instruction[i + len(m):].split(". ")[0].strip()
    return ""


def phone_handshake(c: socket.socket):
    svc, _, _ = R.recv_frame(c)
    assert svc == R.ESN_UPDATE, f"expected ESN_UPDATE, got {svc}"
    c.sendall(R.build(R.FT_PHONE, R.ESN_ACK, 0, b"\x01\x00"))
    c.sendall(R.build(R.FT_PHONE, R.AUTH_REQUEST, 1, bytes.fromhex("1c07000100000000")))
    while True:
        svc, _, payload = R.recv_frame(c)
        if svc == R.SEC_DATA:
            break
    nonce = bytes((b ^ R.OBFUSCATION) for b in payload[-4:])
    c.sendall(R.build(R.FT_PHONE, R.SEC_DATA_ACK, 1, nonce))  # echo de-obfuscated nonce


def main():
    key = load_key()
    port, http = 37220, 8088
    threading.Thread(target=R.run_dash_server, args=(port,), daemon=True).start()
    threading.Thread(target=R.run_viewer, args=(http,), daemon=True).start()
    time.sleep(0.4)

    data = here_route("52.3463,4.8889", "52.3791,4.9003", key)  # Amsterdam: Churchilllaan -> Centraal
    sec = data["routes"][0]["sections"][0]
    actions, summ = sec["actions"], sec["summary"]
    delay = summ["duration"] - summ.get("baseDuration", summ["duration"])
    print(f"HERE: {summ['length']} m, traffic {summ['duration']}s vs base "
          f"{summ.get('baseDuration')}s (+{delay}s), {len(actions)} maneuvers\n")

    c = socket.create_connection(("127.0.0.1", port), timeout=5)
    phone_handshake(c)
    c.sendall(R.build(R.FT_PHONE, R.CONTENT_UPDATE, 0, b"\x02\x00"))  # TBT-only mode

    for a in actions:
        icon = icon_of(a["action"], a.get("direction"))
        nxt = road_from(a.get("instruction", ""))
        dist = float(a.get("length", 0))
        if nxt:
            c.sendall(R.build(R.FT_PHONE, R.CUR_ROAD, 1, nxt.encode()))
        payload = bytes([icon]) + struct.pack("<f", dist) + b"m\x00" + nxt.encode()
        c.sendall(R.build(R.FT_PHONE, R.NEXT_TURN_DIST, 1, payload))
        label = R.TURN_ICONS.get(icon, ("?", "?"))[0]
        print(f"  sent  {a['action']:<16}->  icon {icon:<2} {label:<12} {round(dist):>5}m  {nxt}")
        time.sleep(0.12)

    time.sleep(0.4)
    print("\nemulator decoded _nav:", json.dumps(R._nav))
    c.close()


if __name__ == "__main__":
    main()
