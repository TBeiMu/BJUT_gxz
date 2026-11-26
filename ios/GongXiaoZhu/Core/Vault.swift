import Foundation
import CryptoKit
import Security
import Darwin

/// Only normalized model bytes reach this store. Keys never leave the device Keychain.
struct VaultFile: Sendable {
    let name: String
    let keyName: String
    let limit: Int

    static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Private", isDirectory: true)
    }
    var url: URL { Self.directory.appendingPathComponent(name) }
    private var service: String { (Bundle.main.bundleIdentifier ?? "io.github.tbeimu.gongxiaozhu.ios") + "." + keyName }
    private func key(create: Bool) throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: "aes-gcm", kSecAttrSynchronizable as String: false]
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecSuccess, let bytes = result as? Data, bytes.count == 32 { return SymmetricKey(data: bytes) }
        guard status == errSecItemNotFound, create else { throw AppError.storage }
        var bytes = Data(count: 32)
        let random = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard random == errSecSuccess else { throw AppError.storage }
        var addition = query
        addition[kSecValueData as String] = bytes
        addition[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(addition as CFDictionary, nil)
        if added == errSecDuplicateItem { return try key(create: false) }
        guard added == errSecSuccess else { throw AppError.storage }
        return SymmetricKey(data: bytes)
    }
    func read() throws -> Data? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            guard let size = attributes[.size] as? NSNumber, size.intValue <= limit, size.intValue >= 32 else { throw AppError.storage }
            let bytes = try Data(contentsOf: url)
            guard bytes.count <= limit, bytes.prefix(4) == Data("GXZI".utf8) else { throw AppError.storage }
            let box = try AES.GCM.SealedBox(combined: bytes.dropFirst(4))
            let plaintext = try AES.GCM.open(box, using: key(create: false))
            guard plaintext.count <= limit - 32 else { throw AppError.storage }
            return plaintext
        } catch { throw AppError.storage }
    }
    func save(_ plaintext: Data) throws {
        guard plaintext.count <= limit - 32 else { throw AppError.storage }
        do {
            let exists = FileManager.default.fileExists(atPath: url.path)
            let sealed = try AES.GCM.seal(plaintext, using: key(create: !exists))
            guard let combined = sealed.combined else { throw AppError.storage }
            let bytes = Data("GXZI".utf8) + combined
            guard bytes.count <= limit else { throw AppError.storage }
            try Self.atomicWrite(bytes, to: url)
        } catch { throw AppError.storage }
    }
    func remove() throws {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do { try FileManager.default.removeItem(at: url) } catch { throw AppError.storage }
    }
    /// An explicit reset removes just this module, never a different account or schedule key.
    func reset() throws {
        try remove()
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: "aes-gcm", kSecAttrSynchronizable as String: false]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw AppError.storage }
    }
    static func atomicWrite(_ data: Data, to url: URL) throws {
        let manager = FileManager.default
        try manager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var directory = url.deletingLastPathComponent()
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        let temporary = url.deletingLastPathComponent().appendingPathComponent(".write-" + UUID().uuidString)
        defer { try? manager.removeItem(at: temporary) }
        guard manager.createFile(atPath: temporary.path, contents: data,
                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw AppError.storage }
        let handle = try FileHandle(forWritingTo: temporary)
        try handle.synchronize()
        try handle.close()
        var staged = temporary
        try staged.setResourceValues(values)
        guard Darwin.rename(temporary.path, url.path) == 0 else { throw AppError.storage }
    }
}

enum ModelCoding {
    static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(value)
    }
    static func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try JSONDecoder().decode(type, from: data) } catch { throw AppError.storage }
    }
}

/// The flag can be invalidated synchronously before actor writes or transport callbacks.
final class OperationLease: @unchecked Sendable {
    let id = UUID().uuidString
    private let lock = NSLock()
    private var valid = true
    func invalidate() { lock.withLock { valid = false } }
    var isValid: Bool { lock.withLock { valid } }
    func requireValid() throws { guard isValid else { throw AppError.cancelled } }
}
