import UIKit

/// Settings → "Share debug log": opens the share sheet with the extension's log files from the App Group,
/// or — so the tester always has something to send — a small note saying why there is none.
enum DebugLogShare {
    static func present() {
        let fm = FileManager.default
        var items = [DebugLog.current, DebugLog.previous].compactMap { $0 }.filter { fm.fileExists(atPath: $0.path) }
        if items.isEmpty {
            let why = AppGroup.id == nil
                ? "app group unavailable: tried \(AppGroup.candidates.joined(separator: ", "))"
                : "no log yet — start mirroring first"
            let note = fm.temporaryDirectory.appendingPathComponent("pillion-debug.txt")
            try? why.write(to: note, atomically: true, encoding: .utf8)
            items = [note]
        }
        guard let top = topViewController() else { return }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        // iPad presents share sheets as popovers and crashes without an anchor.
        sheet.popoverPresentationController?.sourceView = top.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        top.present(sheet, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes.first as? UIWindowScene
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController ?? scene?.windows.first?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }
}
