import Foundation
import ReplayKit
import CoreImage
import CoreMedia
import ImageIO
import ExternalAccessory
import Metal

/// Darwin notification names the app observes to reflect broadcast state (no App Group needed).
enum BroadcastSignal {
    static let started = "app.pillion.broadcast.started"
    static let stopped = "app.pillion.broadcast.stopped"
    static func post(_ name: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(name as CFString), nil, nil, true)
    }
}

/// Broadcast Upload Extension: captures the whole screen system-wide and streams it to the dash as
/// NaviLite. Because it runs as a broadcast it keeps going while the phone is in Waze/Maps. Ported
/// from rickdash-ios; picks the bike (External Accessory) when present, else the dev emulator (TCP).
class SampleHandler: RPBroadcastSampleHandler {
    private var conn: DashConn!
    // Force an explicit Metal-backed context so the downscale runs on the GPU, and encode OFF the
    // ReplayKit capture thread so a slow JPEG/readback never throttles capture (the lag source).
    private let ci: CIContext = {
        if let dev = MTLCreateSystemDefaultDevice() {
            return CIContext(mtlDevice: dev, options: [.cacheIntermediates: false])
        }
        return CIContext(options: [.cacheIntermediates: false])
    }()
    private let encodeQueue = DispatchQueue(label: "app.pillion.encode", qos: .userInteractive)
    private var encoding = false           // drop frames while busy → latest-frame, never block capture
    private let encodeLock = NSLock()
    private let lock = NSLock()
    private var latest: [UInt8]?
    private var lastEncode = Date(timeIntervalSince1970: 0)
    private var running = false
    private var seq = 1
    // Live settings, read from the App Group at broadcastStarted (default until then).
    private var sendInterval = 1.0 / Double(BroadcastConfig.maxFps)
    private var jpegQuality = 0.4

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        running = true
        // Pull the user's live Settings (fps / quality) from the App Group.
        sendInterval = 1.0 / Double(BroadcastConfig.liveMaxFps())
        jpegQuality = BroadcastConfig.liveJpegQuality()
        NSLog("PillionExt: settings — fps=%d quality=%.2f",
              BroadcastConfig.liveMaxFps(), jpegQuality)
        BroadcastSignal.post(BroadcastSignal.started)
        // Enumerate EVERY connected MFi accessory up front so a bike test is diagnosable even when the
        // protocol string doesn't match (otherwise we silently fall back to TCP and learn nothing about
        // what the CCU actually advertises). iOS only exposes MFi accessories here — if the bike isn't
        // listed at all, it isn't pairing as an External Accessory and the EA path can't work.
        let accs = EAAccessoryManager.shared().connectedAccessories
        NSLog("PillionExt: %d connected accessory(ies)", accs.count)
        for a in accs { NSLog("PillionExt:  • %@ — protocols=%@", a.name, a.protocolStrings) }
        let hasBike = accs.contains { $0.protocolStrings.contains(BroadcastConfig.dashProtocol) }
        let c: DashConn = hasBike ? EAConn()
                                  : TCPConn(host: BroadcastConfig.emulatorHost, port: BroadcastConfig.emulatorPort)
        c.logger = { s in NSLog("PillionExt: %@", s) }
        conn = c
        NSLog("PillionExt: transport = %@", hasBike ? "bike (External Accessory)" : "emulator (TCP)")
        Thread.detachNewThread { [weak self] in
            guard let self = self else { return }
            do {
                try self.conn.connect()
                try self.handshake()
                self.pushLoop()
            } catch { NSLog("PillionExt connect err: %@", (error as NSError).localizedDescription) }
        }
    }

    private func handshake() throws {
        var f = try conn.readFrame(timeout: 12); while f.svc != 66 { f = try conn.readFrame(timeout: 12) }
        conn.write(NaviLite.frame(6, 81, 0, [1, 0]))
        conn.write(NaviLite.frame(6, 33, 1, NaviLite.hexB("1c07000100000000")))
        f = try conn.readFrame(timeout: 12); while f.svc != 83 { f = try conn.readFrame(timeout: 12) }
        conn.write(NaviLite.frame(6, 84, 1, NaviLite.secDataAckPayload(f.payload)))
        let setup: [(UInt8, UInt8, [UInt8])] = [
            (2, 0, [0, 0]), (31, 0, [1, 0]), (10, 0, [0, 0]), (11, 0, [0, 0]), (13, 0, [1, 0]), (12, 0, [0, 0]),
            (14, 1, NaviLite.hexB("07190600302e32206d69")), (3, 1, []), (17, 1, NaviLite.hexB("00000000036d7068")),
            (13, 0, [1, 0]), (12, 0, [1, 0])]
        for (s, p, pl) in setup { conn.write(NaviLite.frame(6, s, p, pl)) }
        NSLog("PillionExt: auth + setup done")
    }

    /// Sender: stop-and-wait. Paces to the target fps, ships the freshest frame, then blocks on that
    /// frame's IMAGE_ACK (svc 80) before sending the next — so exactly one frame is ever on the link.
    /// The 2-in-flight pipeline buffered a frame ahead, which added a whole round-trip of latency on
    /// slower dashes; stop-and-wait trades a little peak throughput for a visibly lower-latency mirror.
    private func pushLoop() {
        var lastSend = Date(timeIntervalSince1970: 0)
        var acks = 0; var t0 = Date(); var ackTotal = 0.0
        while running {
            let wait = sendInterval - Date().timeIntervalSince(lastSend)
            if wait > 0 { usleep(UInt32(wait * 1_000_000)) }
            lock.lock(); let j = latest; lock.unlock()
            guard let jpg = j else { usleep(15000); continue }
            lastSend = Date()
            var pl: [UInt8] = [3, UInt8(seq & 0xff), UInt8((seq >> 8) & 0xff)]; pl.append(contentsOf: jpg); seq += 1
            conn.write(NaviLite.frame(6, 0, 1, pl))
            let sent = Date()
            // Wait for this frame's ACK before sending the next; on timeout, move on so a dropped ACK
            // can't wedge the stream.
            while running {
                guard let f = try? conn.readFrame(timeout: 1.0) else { break }
                if f.svc == 80 { break }
            }
            acks += 1
            ackTotal += Date().timeIntervalSince(sent)
            let dt = Date().timeIntervalSince(t0)
            if dt >= 1 {
                NSLog("PillionExt: FPS %.1f  %dKB  ack %.0fms",
                      Double(acks) / dt, jpg.count / 1024, ackTotal / Double(max(acks, 1)) * 1000)
                acks = 0; ackTotal = 0; t0 = Date()
            }
        }
    }

    override func processSampleBuffer(_ sb: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video, running else { return }
        // Encode a touch faster than we send so a fresh frame is always ready, but no faster.
        let now = Date(); if now.timeIntervalSince(lastEncode) < sendInterval * 0.8 { return }; lastEncode = now
        // Drop this frame if the previous encode is still running — latest-frame semantics, and it keeps
        // ReplayKit's capture thread from ever blocking on the GPU render + JPEG (the lag source).
        encodeLock.lock(); let busy = encoding; if !busy { encoding = true }; encodeLock.unlock()
        if busy { return }
        guard let pb = CMSampleBufferGetImageBuffer(sb) else {
            encodeLock.lock(); encoding = false; encodeLock.unlock(); return
        }
        var orient = CGImagePropertyOrientation.up
        if let n = CMGetAttachment(sb, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber,
           let o = CGImagePropertyOrientation(rawValue: n.uint32Value) { orient = o }
        let fix: CGImagePropertyOrientation
        switch orient {
        case .left: fix = .right
        case .right: fix = .left
        case .leftMirrored: fix = .rightMirrored
        case .rightMirrored: fix = .leftMirrored
        default: fix = orient   // up/down are self-inverse
        }
        // Retain the sample buffer for the async encode (keeps the pixel buffer's memory valid); only
        // one is ever held at a time thanks to the drop-if-busy guard, so the pool can't starve.
        encodeQueue.async { [weak self] in
            guard let self = self else { return }
            defer { self.encodeLock.lock(); self.encoding = false; self.encodeLock.unlock() }
            // Broadcast extensions are killed past ~50 MB; each encode runs in its own autorelease pool.
            autoreleasepool {
                let img = CIImage(cvPixelBuffer: pb).oriented(fix)
                let e = img.extent
                // Aspect-FIT (letterbox): whole screen centred on the 480×240 panel with black bars.
                let scale = min(480.0 / e.width, 240.0 / e.height)
                let s = img.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
                let se = s.extent
                let tx = (480 - se.width) / 2 - se.origin.x
                let ty = (240 - se.height) / 2 - se.origin.y
                let centered = s.transformed(by: CGAffineTransform(translationX: tx, y: ty))
                let canvas = CGRect(x: 0, y: 0, width: 480, height: 240)
                let cropped = centered.composited(over: CIImage(color: .black).cropped(to: canvas)).cropped(to: canvas)
                let opts: [CIImageRepresentationOption: Any] =
                    [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): self.jpegQuality]
                guard let data = self.ci.jpegRepresentation(of: cropped, colorSpace: CGColorSpaceCreateDeviceRGB(), options: opts) else { return }
                self.lock.lock(); self.latest = [UInt8](data); self.lock.unlock()
            }
        }
    }

    override func broadcastFinished() {
        running = false
        BroadcastSignal.post(BroadcastSignal.stopped)
        conn?.close()
    }
}
