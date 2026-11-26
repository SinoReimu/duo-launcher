// Presses a toolbar button of Xcode's Device Hub by its accessibility description, without
// touching the mouse: "Closed", "Partially Open", "Open", "Rotate Right", "Home", ...
// usage: dhbutton <description> | dhbutton --list
import AppKit
import ApplicationServices

let want = CommandLine.arguments.dropFirst().first ?? "--list"
guard let app = NSWorkspace.shared.runningApplications.first(where: { $0.localizedName == "Device Hub" || $0.bundleURL?.lastPathComponent == "DeviceHub.app" }) else {
    print("Device Hub is not running"); exit(2)
}
let root = AXUIElementCreateApplication(app.processIdentifier)

func str(_ e: AXUIElement, _ a: String) -> String {
    var v: CFTypeRef?
    AXUIElementCopyAttributeValue(e, a as CFString, &v)
    return v as? String ?? ""
}
func children(_ e: AXUIElement) -> [AXUIElement] {
    var v: CFTypeRef?
    AXUIElementCopyAttributeValue(e, kAXChildrenAttribute as CFString, &v)
    return v as? [AXUIElement] ?? []
}
var found = false
func walk(_ e: AXUIElement, _ depth: Int) {
    if found || depth > 40 { return }
    let role = str(e, kAXRoleAttribute)
    if role == "AXMenuBar" { return }
    if role == "AXButton" || role == "AXCheckBox" || role == "AXRadioButton" {
        let d = str(e, kAXDescriptionAttribute)
        if want == "--list" { if !d.isEmpty { print(d, "|", str(e, kAXValueAttribute)) } }
        else if d == want {
            found = true
            let r = AXUIElementPerformAction(e, kAXPressAction as CFString)
            print(r == .success ? "pressed \(d)" : "press failed \(r.rawValue)")
            return
        }
    }
    for c in children(e) { walk(c, depth + 1) }
}
if want == "--select" {
    // dhbutton --select <device name>: select a device in the sidebar.
    let name = CommandLine.arguments.dropFirst(2).first ?? ""
    func selectRow(_ e: AXUIElement, _ depth: Int) -> Bool {
        if depth > 40 { return false }
        if str(e, kAXRoleAttribute) == "AXRow" {
            func hasText(_ x: AXUIElement, _ d: Int) -> Bool {
                if d > 6 { return false }
                if str(x, kAXRoleAttribute) == "AXStaticText" && (str(x, kAXValueAttribute) == name || str(x, kAXTitleAttribute) == name || str(x, kAXDescriptionAttribute) == name) { return true }
                return children(x).contains { hasText($0, d + 1) }
            }
            if hasText(e, 0) {
                let r = AXUIElementSetAttributeValue(e, kAXSelectedAttribute as CFString, kCFBooleanTrue)
                print(r == .success ? "selected \(name)" : "select failed \(r.rawValue)")
                return true
            }
            return false
        }
        return children(e).contains { selectRow($0, depth + 1) }
    }
    if !selectRow(root, 0) { print("no row '\(name)'"); exit(1) }
    exit(0)
}
walk(root, 0)
if want != "--list" && !found { print("no button '\(want)'"); exit(1) }
