import Foundation
import ExternalAccessory

/// The bike transport: streams NaviLite to the real dash as an MFi External Accessory.
/// Ported from the rickdash-ios proof of concept. Runs inside the broadcast extension so it keeps
/// streaming while the phone is in Waze/Maps.
final class EAConn: NSObject, StreamDelegate, DashConn {
    private var session: EASession?
    private var input: InputStream?
    private var output: OutputStream?
    private weak var streamThread: Thread?
    private let ioDone = DispatchSemaphore(value: 0)   // signalled when the ea-io thread has torn its streams down
    private let cond = NSCondition()
    private var closed = false                          // guarded by `cond`; set on close() or a stream end/error
    private var tornDown = false                        // guarded by `cond`; close() body runs once
    private var inBuf = Data()
    private let outLock = NSLock()
    private var outQueue = Data()
    var logger: ((String) -> Void)?

    func connect() throws {
        let mgr = EAAccessoryManager.shared()
        let accs = mgr.connectedAccessories
        guard let acc = accs.first(where: { $0.protocolStrings.contains(BroadcastConfig.dashProtocol) }) else {
            throw err("CCU not found (no accessory advertising \(BroadcastConfig.dashProtocol)). Pair the bike + select NAV mode.")
        }
        guard let s = EASession(accessory: acc, forProtocol: BroadcastConfig.dashProtocol) else {
            throw err("could not open the bike session — another app (e.g. StreetCross) may be holding it; force-quit it")
        }
        session = s; input = s.inputStream; output = s.outputStream
        let t = Thread { [weak self] in
            guard let self = self, let inp = self.input, let outp = self.output else { return }
            inp.delegate = self; outp.delegate = self
            inp.schedule(in: .current, forMode: .default)
            outp.schedule(in: .current, forMode: .default)
            inp.open(); outp.open()
            // Short slices instead of run(): close() just sets `closed` and this thread winds itself down.
            while !self.isClosed { RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.25)) }
            inp.remove(from: .current, forMode: .default)
            outp.remove(from: .current, forMode: .default)
            inp.delegate = nil; outp.delegate = nil
            inp.close(); outp.close()
            self.ioDone.signal()
        }
        t.name = "ea-io"; t.start(); streamThread = t
        logger?("EASession opened for \(BroadcastConfig.dashProtocol)")
    }

    func stream(_ s: Stream, handle e: Stream.Event) {
        switch e {
        case .hasBytesAvailable:
            if let inp = input {
                var tmp = [UInt8](repeating: 0, count: 8192)
                let n = inp.read(&tmp, maxLength: tmp.count)
                if n > 0 { cond.lock(); inBuf.append(contentsOf: tmp[0..<n]); cond.signal(); cond.unlock() }
            }
        case .hasSpaceAvailable:
            flush()
        case .errorOccurred:
            logger?("stream error: \(s.streamError?.localizedDescription ?? "?")")
            markClosed()
        case .endEncountered:
            logger?("stream end")
            markClosed()
        default: break
        }
    }

    @objc private func flush() {
        guard let out = output else { return }
        outLock.lock()
        while !outQueue.isEmpty && out.hasSpaceAvailable {
            let n = outQueue.withUnsafeBytes { (p: UnsafeRawBufferPointer) -> Int in
                out.write(p.bindMemory(to: UInt8.self).baseAddress!, maxLength: outQueue.count)
            }
            if n > 0 { outQueue.removeFirst(n) } else { break }
        }
        outLock.unlock()
    }

    var isClosed: Bool { cond.lock(); defer { cond.unlock() }; return closed }

    /// The link is dead: wake any reader so it throws instead of waiting out its timeout.
    private func markClosed() { cond.lock(); closed = true; cond.broadcast(); cond.unlock() }

    func write(_ bytes: [UInt8]) {
        outLock.lock(); outQueue.append(contentsOf: bytes); outLock.unlock()
        if let t = streamThread { perform(#selector(flush), on: t, with: nil, waitUntilDone: false) }
    }

    /// Fully tears down: stops the ea-io thread (which closes + unschedules the streams) and drops the session.
    /// Safe to call more than once, and on a connection whose `connect()` failed.
    func close() {
        cond.lock(); let first = !tornDown; tornDown = true; cond.unlock()
        guard first else { return }   // loop thread and broadcastFinished may both close
        markClosed()
        if streamThread != nil { _ = ioDone.wait(timeout: .now() + 2) }
        streamThread = nil; input = nil; output = nil; session = nil
    }

    private func readBytes(_ n: Int, timeout: TimeInterval) throws -> [UInt8] {
        cond.lock(); defer { cond.unlock() }
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if closed { throw err("link closed") }
            if inBuf.count >= n { break }
            if !cond.wait(until: deadline) { throw err("read timeout") }
        }
        let out = Array(inBuf.prefix(n)); inBuf.removeFirst(n); return out
    }

    func readFrame(timeout: TimeInterval) throws -> NaviFrame {
        var h = try readBytes(16, timeout: timeout)
        while !(h[0] == 0x6e && h[1] == 0x41 && h[2] == 0x6c && h[3] == 0x40) {
            let b = try readBytes(1, timeout: timeout); h.removeFirst(); h.append(b[0])
        }
        let size = Int(h[7]) | (Int(h[8]) << 8) | (Int(h[9]) << 16) | (Int(h[10]) << 24)
        let payload = size > 0 ? try readBytes(size, timeout: timeout) : []
        return NaviFrame(svc: Int(h[6]), payload: payload)
    }

    private func err(_ s: String) -> NSError { NSError(domain: "EAConn", code: 1, userInfo: [NSLocalizedDescriptionKey: s]) }
}
