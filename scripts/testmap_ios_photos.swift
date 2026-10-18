// Simulator-only PhotoKit oracle. No product linking or UI controller.
// Launch: --request request.json --result result.json (basenames in helper Documents).
// Requests:
// {"action":"inventory"}
// {"action":"import","sourceFile":"fixture.png","runMarker":"ewm-run-123"}
// {"action":"export","assetID":"<full localIdentifier>","resourceIndex":0}
// {"action":"delete","assetIDs":["..."],"ownedIDs":["..."],"baselineIDs":["..."]}
// Delete trusts the caller's ownedIDs: the driver MUST export/revalidate resource hashes first.
// It never infers ownership from recency, filename, or an inventory difference.
// Host-only guard check: swiftc -parse-as-library this-file.swift -o /tmp/photos-check;
// then /tmp/photos-check --self-check. This path never accesses PhotoKit.

import Foundation

struct HelperFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw HelperFailure(message: message) }
}

struct Request: Decodable {
    let action: String
    var sourceFile: String?
    var runMarker: String?
    var assetID: String?
    var resourceIndex: Int?
    var assetIDs: [String]?
    var ownedIDs: [String]?
    var baselineIDs: [String]?

    func deletionIDs() throws -> [String] {
        guard let ids = assetIDs, let owned = ownedIDs, let baseline = baselineIDs else {
            throw HelperFailure(message: "delete requires assetIDs, ownedIDs, and baselineIDs")
        }
        try require(!ids.isEmpty && Set(ids).count == ids.count, "delete IDs must be nonempty and unique")
        try require((ids + owned + baseline).allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty },
                    "empty asset identifier")
        try require(Set(ids).isSubset(of: Set(owned)), "delete ID is outside ownedIDs")
        try require(Set(ids).isDisjoint(with: Set(baseline)), "refusing to delete a baseline asset")
        return ids
    }
}

func documentFile(_ name: String, in documents: URL) throws -> URL {
    try require(!name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\"),
                "expected a Documents basename")
    let url = documents.appendingPathComponent(name)
    try require(url.resolvingSymlinksInPath().deletingLastPathComponent() == documents.resolvingSymlinksInPath(),
                "file escapes helper Documents")
    return url
}

func selfCheck() throws {
    func rejected(_ operation: () throws -> Void) throws {
        do { try operation() } catch { return }
        throw HelperFailure(message: "ownership guard accepted unsafe input")
    }
    let safe = Request(action: "delete", assetIDs: ["owned"], ownedIDs: ["owned"], baselineIDs: ["baseline"])
    try require(try safe.deletionIDs() == ["owned"], "safe deletion rejected")
    var request = safe
    request.baselineIDs = ["owned"]
    try rejected { _ = try request.deletionIDs() }
    request = safe
    request.ownedIDs = []
    try rejected { _ = try request.deletionIDs() }
    request = safe
    request.baselineIDs = nil
    try rejected { _ = try request.deletionIDs() }
    request = safe
    request.assetIDs = ["owned", "owned"]
    try rejected { _ = try request.deletionIDs() }
    let documents = URL(fileURLWithPath: "/tmp/photos-helper-documents", isDirectory: true)
    try rejected { _ = try documentFile("../baseline", in: documents) }
    try require(try documentFile("request.json", in: documents).lastPathComponent == "request.json", "valid basename rejected")
}

#if targetEnvironment(simulator)
import UIKit
import Photos
import CryptoKit

func sha256(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

func resources(_ asset: PHAsset) -> [PHAssetResource] {
    PHAssetResource.assetResources(for: asset)
}

func resourceInfo(_ resource: PHAssetResource, index: Int) -> [String: Any] {
    ["index": index, "type": resource.type.rawValue, "originalFilename": resource.originalFilename,
     "contentType": resource.contentType.identifier, "width": resource.pixelWidth, "height": resource.pixelHeight]
}

func assetInfo(_ asset: PHAsset) -> [String: Any] {
    ["id": asset.localIdentifier, "width": asset.pixelWidth, "height": asset.pixelHeight,
     "mediaType": asset.mediaType.rawValue, "hidden": asset.isHidden,
     "creationDate": asset.creationDate.map { ISO8601DateFormatter().string(from: $0) } as Any? ?? NSNull(),
     "resources": resources(asset).enumerated().map { resourceInfo($0.element, index: $0.offset) }]
}

func fetch(_ ids: [String]) throws -> PHFetchResult<PHAsset> {
    let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
    var found = Set<String>()
    assets.enumerateObjects { asset, _, _ in found.insert(asset.localIdentifier) }
    try require(found == Set(ids), "one or more exact asset IDs are missing")
    return assets
}

func handleRequest(_ request: Request, documents: URL) async throws -> [String: Any] {
    let authorization = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    try require(authorization == .authorized,
                "full readWrite Photos authorization required (status=\(authorization.rawValue), bundle=\(Bundle.main.bundleIdentifier ?? "nil")); limited/notDetermined denied")
    switch request.action {
    case "inventory":
        let options = PHFetchOptions()
        options.includeHiddenAssets = true
        let fetched = PHAsset.fetchAssets(with: options)
        var assets = [[String: Any]]()
        fetched.enumerateObjects { asset, _, _ in assets.append(assetInfo(asset)) }
        return ["assets": assets.sorted { ($0["id"] as! String) < ($1["id"] as! String) }]
    case "import":
        guard let source = request.sourceFile, let marker = request.runMarker else {
            throw HelperFailure(message: "import requires sourceFile and runMarker")
        }
        try require(marker.range(of: "^[A-Za-z0-9_-]{1,80}$", options: .regularExpression) != nil,
                    "runMarker must be 1-80 ASCII letters, digits, underscores or hyphens")
        let data = try Data(contentsOf: documentFile(source, in: documents))
        try require(data.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) && UIImage(data: data) != nil,
                    "import requires a decodable PNG fixture")
        let filename = "\(marker)-\(UUID().uuidString).png"
        var createdID: String?
        try await PHPhotoLibrary.shared().performChanges {
            let creation = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = filename
            creation.addResource(with: .photo, data: data, options: options)
            createdID = creation.placeholderForCreatedAsset?.localIdentifier
        }
        guard let id = createdID else { throw HelperFailure(message: "import completed without placeholder ID") }
        // Return the mutation receipt immediately; the driver can retry inventory visibility.
        return ["id": id, "originalFilename": filename, "sha256": sha256(data), "byteCount": data.count]
    case "export":
        guard let id = request.assetID, let index = request.resourceIndex else {
            throw HelperFailure(message: "export requires assetID and resourceIndex from inventory")
        }
        let asset = try fetch([id]).object(at: 0)
        let available = resources(asset)
        try require(available.indices.contains(index), "resourceIndex is out of range")
        let resource = available[index]
        let ext = resource.contentType.preferredFilenameExtension ?? "bin"
        let output = try documentFile("resource-\(UUID().uuidString).\(ext)", in: documents)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = false
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                PHAssetResourceManager.default().writeData(for: resource, toFile: output, options: options) { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                }
            }
            let data = try Data(contentsOf: output)
            try require(!data.isEmpty, "exported resource is empty")
            return ["id": id, "resource": resourceInfo(resource, index: index), "file": output.lastPathComponent,
                    "sha256": sha256(data), "byteCount": data.count]
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
        }
    case "delete":
        let ids = try request.deletionIDs()
        let assets = try fetch(ids)
        try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }
        let remaining = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
        try require(remaining.count == 0, "delete completed but assets remain visible")
        return ["deletedIDs": ids, "scope": "active library; Recently Deleted is not purged"]
    default:
        throw HelperFailure(message: "unknown action")
    }
}

@main
final class PhotosHelperApp: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        Task { @MainActor in
            let arguments = CommandLine.arguments
            if arguments.contains("--self-check") {
                do { try selfCheck(); print("PASS ownership guards"); exit(0) }
                catch { print(error.localizedDescription); exit(1) }
            }
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            var resultURL: URL?
            var requestAction: String?
            do {
                func argument(_ flag: String) throws -> String {
                    guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else {
                        throw HelperFailure(message: "missing \(flag)")
                    }
                    return arguments[index + 1]
                }
                let result = try documentFile(argument("--result"), in: documents)
                let input = try documentFile(argument("--request"), in: documents)
                try require(input != result, "request and result must be different files")
                resultURL = result
                // Never let an earlier success survive a failed request.
                try Data("{\"success\":false,\"error\":\"request in progress\"}".utf8).write(to: result, options: .atomic)
                let request = try JSONDecoder().decode(Request.self, from: Data(contentsOf: input))
                requestAction = request.action
                var response = try await handleRequest(request, documents: documents)
                response["success"] = true
                response["action"] = request.action
                try JSONSerialization.data(withJSONObject: response, options: [.prettyPrinted, .sortedKeys]).write(to: result, options: .atomic)
                exit(0)
            } catch {
                let response: [String: Any] = ["success": false, "error": error.localizedDescription,
                                               "action": requestAction as Any? ?? NSNull()]
                if let resultURL, let data = try? JSONSerialization.data(withJSONObject: response, options: [.prettyPrinted, .sortedKeys]) {
                    try? data.write(to: resultURL, options: .atomic)
                }
                print("Photos helper failed: \(error.localizedDescription)")
                exit(1)
            }
        }
        return true
    }
}
#elseif os(macOS)
@main
enum GuardCheck {
    static func main() throws {
        try require(CommandLine.arguments.contains("--self-check"), "host supports --self-check only")
        try selfCheck()
        print("PASS ownership guards")
    }
}
#else
#error("This helper supports the iOS Simulator only")
#endif
