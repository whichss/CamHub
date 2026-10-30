import CryptoKit
import Foundation
import Network
import Security

struct CamHubLocalIdentity {
    let secIdentity: sec_identity_t?
    let certificateFingerprint: String
    let pairingPIN: String
}

/// Creates and persists a self-signed identity with the same SHA256withRSA certificate shape as Android.
final class CamHubIdentityStore {
    private let keyTag = Data("com.camhub.camera.identity.rsa".utf8)
    private let certificateDefaultsKey = "camhub.server.certificate.der"
    private let pinDefaultsKey = "camhub.server.pairing.pin"

    func loadOrCreate() throws -> CamHubLocalIdentity {
        let privateKey = try loadOrCreatePrivateKey()
        let savedCertificate = UserDefaults.standard.data(forKey: certificateDefaultsKey)
        let certificateData: Data
        if let savedCertificate,
           let certificate = SecCertificateCreateWithData(nil, savedCertificate as CFData),
           certificateMatches(certificate, privateKey: privateKey) {
            certificateData = savedCertificate
        } else {
            certificateData = try makeSelfSignedCertificate(privateKey: privateKey)
            UserDefaults.standard.set(certificateData, forKey: certificateDefaultsKey)
        }
        guard let certificate = SecCertificateCreateWithData(nil, certificateData as CFData) else {
            throw IdentityError.certificateUnavailable
        }
        let addStatus = SecItemAdd([
            kSecClass: kSecClassCertificate,
            kSecValueData: certificateData
        ] as CFDictionary, nil)
        guard addStatus == errSecSuccess || addStatus == errSecDuplicateItem else {
            throw IdentityError.certificateUnavailable
        }
        var identityResult: CFTypeRef?
        let identityQuery: [CFString: Any] = [
            kSecClass: kSecClassIdentity,
            kSecMatchItemList: [certificate],
            kSecReturnRef: true
        ]
        guard SecItemCopyMatching(identityQuery as CFDictionary, &identityResult) == errSecSuccess,
              let identityResult,
              CFGetTypeID(identityResult) == SecIdentityGetTypeID() else {
            throw IdentityError.certificateUnavailable
        }
        let identity = unsafeBitCast(identityResult, to: SecIdentity.self)
        let fingerprint = SHA256.hash(data: certificateData)
            .map { String(format: "%02X", $0) }
            .joined()
        return CamHubLocalIdentity(
            secIdentity: sec_identity_create(identity),
            certificateFingerprint: fingerprint,
            pairingPIN: try loadOrCreatePIN()
        )
    }

    private func certificateMatches(_ certificate: SecCertificate, privateKey: SecKey) -> Bool {
        guard let certificateKey = SecCertificateCopyKey(certificate),
              let privateKeyPublicPart = SecKeyCopyPublicKey(privateKey),
              let certificateRepresentation = externalPublicKeyRepresentation(certificateKey),
              let privateKeyRepresentation = externalPublicKeyRepresentation(privateKeyPublicPart) else {
            return false
        }
        return certificateRepresentation == privateKeyRepresentation
    }

    private func externalPublicKeyRepresentation(_ key: SecKey) -> Data? {
        var error: Unmanaged<CFError>?
        return SecKeyCopyExternalRepresentation(key, &error) as Data?
    }

    private func loadOrCreatePrivateKey() throws -> SecKey {
        let query: [CFString: Any] = [
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: keyTag,
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecReturnRef: true
        ]
        var result: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let result,
           CFGetTypeID(result) == SecKeyGetTypeID() {
            return unsafeBitCast(result, to: SecKey.self)
        }
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits: 2_048,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: keyTag,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            ]
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw IdentityError.keyGenerationFailed
        }
        return key
    }

    private func loadOrCreatePIN() throws -> String {
        if let pin = UserDefaults.standard.string(forKey: pinDefaultsKey),
           pin.range(of: "^[0-9]{8}$", options: .regularExpression) != nil {
            return pin
        }
        var random = [UInt8](repeating: 0, count: 4)
        let randomCount = random.count
        let status = random.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, randomCount, $0.baseAddress!)
        }
        guard status == errSecSuccess else { throw IdentityError.pinGenerationFailed }
        let number = random.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 100_000_000
        let pin = String(format: "%08u", number)
        UserDefaults.standard.set(pin, forKey: pinDefaultsKey)
        return pin
    }

    private func makeSelfSignedCertificate(privateKey: SecKey) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { throw IdentityError.keyGenerationFailed }
        var keyError: Unmanaged<CFError>?
        guard let rawPublicKey = SecKeyCopyExternalRepresentation(publicKey, &keyError) as Data? else {
            if let keyError { throw keyError.takeRetainedValue() }
            throw IdentityError.keyGenerationFailed
        }
        let rsaAlgorithm = derSequence(derOID([
            0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01
        ]) + Data([0x05, 0x00]))
        let subjectPublicKeyInfo = derSequence(rsaAlgorithm + derBitString(rawPublicKey))
        var serialBytes = (0..<16).map { _ in UInt8.random(in: .min ... .max) }
        serialBytes[0] &= 0x7f
        if serialBytes.allSatisfy({ $0 == 0 }) { serialBytes[serialBytes.count - 1] = 1 }
        while serialBytes.count > 1, serialBytes.first == 0 { serialBytes.removeFirst() }
        let serial = Data(serialBytes)

        let signatureAlgorithm = derSequence(derOID([
            0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B
        ]) + Data([0x05, 0x00]))
        let name = derSequence(derSet(derSequence(
            derOID([0x55, 0x04, 0x03]) + derUTF8("CamHub iOS")
        )))
        let validity = derSequence(derUTCTime(Date().addingTimeInterval(-86_400)) +
                                   derUTCTime(Date().addingTimeInterval(365 * 86_400)))
        let tbsCertificate = derSequence(
            derTagged(0xA0, derInteger(Data([0x02]))) +
            derInteger(serial) + signatureAlgorithm + name + validity + name + subjectPublicKeyInfo
        )
        var signError: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .rsaSignatureMessagePKCS1v15SHA256,
            tbsCertificate as CFData,
            &signError
        ) as Data? else {
            if let signError { throw signError.takeRetainedValue() }
            throw IdentityError.certificateUnavailable
        }
        return derSequence(tbsCertificate + signatureAlgorithm + derBitString(signature))
    }

    private func derSequence(_ content: Data) -> Data { derTagged(0x30, content) }
    private func derSet(_ content: Data) -> Data { derTagged(0x31, content) }
    private func derInteger(_ content: Data) -> Data {
        let positive = content.first.map { $0 & 0x80 != 0 } == true ? Data([0]) + content : content
        return derTagged(0x02, positive)
    }
    private func derOID(_ content: [UInt8]) -> Data { derTagged(0x06, Data(content)) }
    private func derUTF8(_ value: String) -> Data { derTagged(0x0C, Data(value.utf8)) }
    private func derBitString(_ value: Data) -> Data { derTagged(0x03, Data([0]) + value) }
    private func derUTCTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return derTagged(0x17, Data(formatter.string(from: date).utf8))
    }
    private func derTagged(_ tag: UInt8, _ content: Data) -> Data {
        var result = Data([tag])
        result.append(derLength(content.count))
        result.append(content)
        return result
    }
    private func derLength(_ length: Int) -> Data {
        if length < 0x80 { return Data([UInt8(length)]) }
        var bytes: [UInt8] = []
        var value = length
        while value > 0 {
            bytes.insert(UInt8(value & 0xff), at: 0)
            value >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }

    private enum IdentityError: LocalizedError {
        case keyGenerationFailed
        case pinGenerationFailed
        case certificateUnavailable
        var errorDescription: String? {
            switch self {
            case .keyGenerationFailed: return "CamHub could not create its local network identity."
            case .pinGenerationFailed: return "CamHub could not create a pairing PIN."
            case .certificateUnavailable: return "CamHub could not load its local TLS certificate."
            }
        }
    }
}
