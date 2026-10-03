import Foundation

/// File log in the App Group container so testers can send it from Settings → "Share debug log".
/// The extension writes (`append`), the app only reads the two file URLs. All writes go through one
/// serial queue and swallow errors: logging must never block the sender or crash the extension.
enum DebugLog {
    static var current: URL? { AppGroup.container?.appendingPathComponent("pillion-debug.log") }
    static var previous: URL? { AppGroup.container?.appendingPathComponent("pillion-debug.prev.log") }

    private static let cap = 512 * 1024
    private static let queue = DispatchQueue(label: "app.pillion.debuglog")
    private static var size = 0
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    /// New broadcast: current → prev (replacing the old prev), then a fresh file starting with `header`.
    static func rotate(header: String) {
        queue.sync {
            guard let cur = current, let prev = previous else { return }
            try? FileManager.default.removeItem(at: prev)
            try? FileManager.default.moveItem(at: cur, to: prev)
            FileManager.default.createFile(atPath: cur.path, contents: nil)
            size = 0
        }
        append(header)
    }

    static func append(_ line: String) {
        queue.async {
            guard let cur = current else { return }
            let data = Data("\(stamp.string(from: Date())) \(line)\n".utf8)
            if size + data.count > cap {
                // ponytail: keep the newest half; a rewrite every ~256 KB of logging is cheap enough.
                if let old = try? Data(contentsOf: cur) {
                    try? old.suffix(cap / 2).write(to: cur, options: .atomic)
                    size = cap / 2
                }
            }
            guard let fh = try? FileHandle(forWritingTo: cur) else {
                FileManager.default.createFile(atPath: cur.path, contents: data)
                size = data.count
                return
            }
            defer { try? fh.close() }
            do { try fh.seekToEnd(); try fh.write(contentsOf: data); size += data.count } catch {}
        }
    }
}
