import Foundation
import Darwin

#if DEBUG

/// The dev transport: streams NaviLite to the emulator (`receiver.py` TCP dash) over a plain socket.
/// Used by the broadcast extension when no bike accessory is connected — same role as the app's
/// Kotlin `NetworkByteChannel`, so the whole stack can be tested without the bike.
///
/// Single-threaded by design: the extension drives connect/handshake/pushLoop on one detached thread,
/// and `processSampleBuffer` never touches the socket (only the JPEG buffer), so no locking is needed.
final class TCPConn: DashConn {
    private let host: String
    private let port: UInt16
    private var fd: Int32 = -1
    private var inBuf = [UInt8]()
    var logger: ((String) -> Void)?
    var isClosed: Bool { fd < 0 }

    init(host: String, port: UInt16) { self.host = host; self.port = port }

    func connect() throws {
        let s = socket(AF_INET, SOCK_STREAM, 0)
        guard s >= 0 else { throw err("socket() failed") }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { Darwin.close(s); throw err("bad host '\(host)'") }
        let r = withUnsafePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard r == 0 else { Darwin.close(s); throw err("connect(\(host):\(port)) failed — emulator running in TCP mode?") }
        // TCP_NODELAY: with stop-and-wait, Nagle holds each frame's trailing partial segment until
        // the receiver's delayed ACK (~100-200ms/frame). Android sets tcpNoDelay=true; match it.
        var one: Int32 = 1
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        fd = s
        logger?("TCP connected to \(host):\(port)")
    }

    func write(_ bytes: [UInt8]) {
        guard fd >= 0, !bytes.isEmpty else { return }
        bytes.withUnsafeBytes { raw in
            let base = raw.bindMemory(to: UInt8.self).baseAddress!
            var off = 0
            while off < bytes.count {
                let n = Darwin.send(fd, base + off, bytes.count - off, 0)
                if n <= 0 { break }
                off += n
            }
        }
    }

    func readFrame(timeout: TimeInterval) throws -> NaviFrame {
        while true {
            try fill(16, timeout: timeout)
            if inBuf[0] == 0x6e && inBuf[1] == 0x41 && inBuf[2] == 0x6c && inBuf[3] == 0x40 { break }
            inBuf.removeFirst()
        }
        let size = Int(inBuf[7]) | (Int(inBuf[8]) << 8) | (Int(inBuf[9]) << 16) | (Int(inBuf[10]) << 24)
        try fill(16 + size, timeout: timeout)
        let payload = size > 0 ? Array(inBuf[16..<16 + size]) : []
        let svc = Int(inBuf[6])
        inBuf.removeFirst(16 + size)
        return NaviFrame(svc: svc, payload: payload)
    }

    func close() { if fd >= 0 { Darwin.close(fd); fd = -1 } }

    private func fill(_ n: Int, timeout: TimeInterval) throws {
        var tv = timeval(tv_sec: Int(timeout), tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var tmp = [UInt8](repeating: 0, count: 8192)
        while inBuf.count < n {
            let r = tmp.withUnsafeMutableBytes { Darwin.recv(fd, $0.baseAddress, $0.count, 0) }
            if r > 0 { inBuf.append(contentsOf: tmp[0..<r]) } else { throw err("read timeout / closed") }
        }
    }

    private func err(_ s: String) -> NSError { NSError(domain: "TCPConn", code: 1, userInfo: [NSLocalizedDescriptionKey: s]) }
}
#endif
