import XCTest

/// Command server for measuring the iPhone Duo Home Screen. It polls `<dir>/cmd.json`, runs the
/// command against SpringBoard and writes `<dir>/out.json`. See ../duoref.py for the client.
final class Driver: XCTestCase {
    private let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    private var dir = "/tmp/duoref"

    @MainActor
    func testServe() throws {
        if let d = ProcessInfo.processInfo.environment["DUOREF_DIR"], !d.isEmpty { dir = d }
        let fm = FileManager.default
        try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let cmdPath = dir + "/cmd.json"
        let outPath = dir + "/out.json"
        try? fm.removeItem(atPath: cmdPath)
        write(outPath, ["ready": true])
        let idleLimit = 3600.0
        var lastCmd = Date()
        while Date().timeIntervalSince(lastCmd) < idleLimit {
            guard let data = fm.contents(atPath: cmdPath),
                  let cmd = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                usleep(20_000)
                continue
            }
            try? fm.removeItem(atPath: cmdPath)
            lastCmd = Date()
            let op = cmd["op"] as? String ?? ""
            if op == "quit" { write(outPath, ["ok": true, "id": cmd["id"] ?? 0]); return }
            var res: [String: Any] = ["id": cmd["id"] ?? 0]
            let t0 = Date()
            do {
                res["result"] = try run(op, cmd)
                res["ok"] = true
            } catch {
                res["ok"] = false
                res["error"] = "\(error)"
            }
            res["elapsed"] = Date().timeIntervalSince(t0)
            write(outPath, res)
        }
    }

    private func write(_ path: String, _ obj: [String: Any]) {
        let data = (try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])) ?? Data()
        let tmp = path + ".tmp"
        FileManager.default.createFile(atPath: tmp, contents: data)
        _ = try? FileManager.default.replaceItemAt(URL(fileURLWithPath: path), withItemAt: URL(fileURLWithPath: tmp))
    }

    private func num(_ c: [String: Any], _ k: String, _ d: Double = 0) -> Double {
        (c[k] as? NSNumber)?.doubleValue ?? d
    }

    private func pt(_ x: Double, _ y: Double) -> XCUICoordinate {
        sb.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y))
    }

    struct Fail: Error, CustomStringConvertible { let description: String }

    @MainActor
    private func run(_ op: String, _ c: [String: Any]) throws -> Any {
        switch op {
        case "ping":
            return "pong"
        case "methods":
            // Runtime introspection of an (often private) class: used to find new XCTest entry points.
            guard let cls: AnyClass = NSClassFromString(c["class"] as? String ?? "") else { throw Fail(description: "no class") }
            var out: [String] = []
            for (prefix, k) in [("-", cls as AnyClass?), ("+", object_getClass(cls))] {
                var n: UInt32 = 0
                if let k, let list = class_copyMethodList(k, &n) {
                    for i in 0..<Int(n) { out.append(prefix + NSStringFromSelector(method_getName(list[i]))) }
                    free(list)
                }
            }
            return out.sorted().joined(separator: "\n")
        case "screens":
            return XCUIScreen.screens.map { "\($0)" }.joined(separator: "\n")
        case "dump":
            return sb.debugDescription
        case "frame":
            let f = sb.frame
            return ["x": f.minX, "y": f.minY, "w": f.width, "h": f.height,
                    "orientation": XCUIDevice.shared.orientation.rawValue]
        case "tap":
            pt(num(c, "x"), num(c, "y")).tap()
            return true
        case "press":
            pt(num(c, "x"), num(c, "y")).press(forDuration: num(c, "duration", 1.0))
            return true
        case "drag":
            // Public API: press, drag at a velocity (pt/s), hold, release.
            pt(num(c, "x"), num(c, "y")).press(
                forDuration: num(c, "press", 0.05),
                thenDragTo: pt(num(c, "x2"), num(c, "y2")),
                withVelocity: XCUIGestureVelocity(num(c, "velocity", 800)),
                thenHoldForDuration: num(c, "hold", 0))
            return true
        case "path":
            // Exact touch path: points = [[t, x, y], ...]; finger lifts at the last point's time.
            guard let pts = c["points"] as? [[NSNumber]], pts.count >= 2 else { throw Fail(description: "points") }
            // display: 1 = cover, 3 = inner (XCUIScreen.displayID). orient: UIInterfaceOrientation raw value.
            return try synth(pts.map { ($0[0].doubleValue, CGPoint(x: $0[1].doubleValue, y: $0[2].doubleValue)) },
                             display: Int32(num(c, "display", 1)), orient: Int(num(c, "orient", 1)))
        case "rotate":
            let map: [String: UIDeviceOrientation] = [
                "portrait": .portrait, "left": .landscapeLeft, "right": .landscapeRight, "down": .portraitUpsideDown,
            ]
            guard let o = map[c["to"] as? String ?? ""] else { throw Fail(description: "orientation") }
            XCUIDevice.shared.orientation = o
            return XCUIDevice.shared.orientation.rawValue
        case "home":
            XCUIDevice.shared.press(.home)
            return true
        case "launch":
            let app = XCUIApplication(bundleIdentifier: c["bundle"] as? String ?? "")
            app.activate()
            return true
        default:
            throw Fail(description: "unknown op \(op)")
        }
    }

    @MainActor
    private func synth(_ pts: [(Double, CGPoint)], display: Int32, orient: Int) throws -> Any {
        guard let first = pts.first, let last = pts.last else { throw Fail(description: "empty") }
        guard let path = XCPointerEventPath(forTouchAt: first.1, offset: first.0) else { throw Fail(description: "path") }
        for p in pts.dropFirst() { path.move(to: p.1, atOffset: p.0) }
        path.liftUp(atOffset: last.0)
        guard let rec = XCSynthesizedEventRecord(name: "duoref", displayID: display, interfaceOrientation: Int64(orient)) else { throw Fail(description: "record") }
        rec.add(path)
        let dev = XCUIDevice.shared
        if dev.responds(to: Selector(("eventSynthesizer"))) {
            let sem = expectation(description: "synth")
            var err: Error?
            _ = dev.eventSynthesizer().synthesizeEvent(rec) { _, e in err = e; sem.fulfill() }
            wait(for: [sem], timeout: last.0 + 10)
            if let err { throw err }
            return "synthesizer"
        }
        try rec.synthesize()
        return "record"
    }
}
