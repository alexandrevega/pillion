import SwiftUI
import UIKit

/// The view SDL projects to the bike dash. SDLCarWindow renders this view controller off-screen at the
/// head unit's negotiated resolution and streams it over USB/iAP2 — so unlike NaviLite (which mirrors the
/// whole phone via ReplayKit), the SDL path renders Pillion's *own* content. v1 is a native Pillion dash;
/// richer content (HERE Maps, custom start screens) plugs in here later via the DashContentSource seam.
final class SdlDashHostingController: UIHostingController<SdlDashView> {
    init() { super.init(rootView: SdlDashView()) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
}

struct SdlDashView: View {
    @State private var now = Date()
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 16) {
                Text("Pillion")
                    .font(.system(size: 56, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
                Text(now, style: .time)
                    .font(.system(size: 40, weight: .medium, design: .monospaced))
                    .foregroundColor(.green)
                Text("Connected")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundColor(.gray)
            }
        }
        .onReceive(tick) { now = $0 }
    }
}
