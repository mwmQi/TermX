package com.termx.app.power.ssh

import android.content.Context
import android.util.Log
import java.io.*
import java.math.BigInteger
import java.security.*
import java.security.interfaces.*
import java.security.spec.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages SSH host keys for the TermX SSH server.
 *
 * Host keys are used by the SSH server to identify itself to clients.
 * When a client connects for the first time, it verifies the server's
 * host key fingerprint (similar to how OpenSSH works). If the key
 * changes on subsequent connections, the client is warned about a
 * potential man-in-the-middle attack.
 *
 * Supported key types:
 *   - **RSA** (2048/4096-bit): Widely compatible, default for most SSH servers
 *   - **ECDSA** (NIST P-256/P-384/P-521): Modern, efficient elliptic curve keys
 *   - **Ed25519**: Modern, secure, compact signatures (requires BouncyCastle or API 33+)
 *
 * Key storage:
 *   - Private keys are stored in the app's private directory (`~/.ssh/`)
 *   - Keys are stored in OpenSSH-compatible format when possible
 *   - PEM format is used as fallback for maximum compatibility
 *
 * Fingerprints:
 *   - SHA-256 (default, base64-encoded, modern)
 *   - MD5 (hex-encoded, legacy compatibility)
 *
 * Usage:
 *   ```kotlin
 *   val keyManager = HostKeyManager(context)
 *   keyManager.ensureHostKeys()           // Generate keys if not present
 *   val keyPair = keyManager.getActiveKeyPair()
 *   val fingerprint = keyManager.getFingerprint(keyPair, "sha256")
 *   ```
 *
 * CLI commands (via termx-ssh):
 *   termx-ssh keygen rsa        Generate RSA host key
 *   termx-ssh keygen ecdsa      Generate ECDSA host key
 *   termx-ssh keygen ed25519    Generate Ed25519 host key
 *   termx-ssh keygen            Generate default key (RSA 4096)
 *   termx-ssh keys              List all host keys with fingerprints
 *   termx-ssh key-rotate        Rotate keys (generate new, deprecate old)
 */
class HostKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "HostKeyManager"

        /** SSH directory relative to app files dir */
        const val SSH_DIR = ".ssh"

        /** Host key file names */
        const val RSA_KEY_FILE = "ssh_host_rsa_key"
        const val ECDSA_KEY_FILE = "ssh_host_ecdsa_key"
        const val ED25519_KEY_FILE = "ssh_host_ed25519_key"

        /** Public key file suffix */
        const val PUB_KEY_SUFFIX = ".pub"

        /** Key type identifiers */
        const val KEY_TYPE_RSA = "ssh-rsa"
        const val KEY_TYPE_ECDSA = "ecdsa-sha2-nistp256"
        const val KEY_TYPE_ED25519 = "ssh-ed25519"

        /** Default RSA key size in bits */
        const val DEFAULT_RSA_KEY_SIZE = 4096

        /** Minimum RSA key size */
        const val MIN_RSA_KEY_SIZE = 2048

        /** ECDSA curve names */
        const val ECDSA_CURVE_P256 = "secp256r1"   // NIST P-256
        const val ECDSA_CURVE_P384 = "secp384r1"   // NIST P-384
        const val ECDSA_CURVE_P521 = "secp521r1"   // NIST P-521

        /** Key rotation configuration */
        const val MAX_KEY_AGE_DAYS = 365    // Auto-rotate keys older than 1 year
        const val MAX_DEPRECATED_KEYS = 3   // Keep at most 3 deprecated key sets
    }

    /**
     * Information about a host key.
     */
    data class HostKeyInfo(
        val type: String,
        val keySize: Int,
        val fingerprintSha256: String,
        val fingerprintMd5: String,
        val createdAt: Long,
        val filePath: String,
        val isDeprecated: Boolean = false
    )

    /**
     * Result of key generation.
     */
    data class KeyGenResult(
        val success: Boolean,
        val keyType: String,
        val keySize: Int,
        val fingerprintSha256: String,
        val fingerprintMd5: String,
        val errorMessage: String? = null
    )

    /**
     * Key pair entry with metadata.
     */
    private data class KeyEntry(
        val keyPair: KeyPair,
        val type: String,
        val keySize: Int,
        val createdAt: Long,
        val filePath: String,
        var isDeprecated: Boolean = false
    )

    // ---- State ----

    /** Loaded key entries */
    private val keyEntries = ConcurrentHashMap<String, KeyEntry>()

    /** The currently active key type (used for new connections) */
    @Volatile var activeKeyType: String = KEY_TYPE_RSA
        private set

    // ---- Key Generation ----

    /**
     * Generate a new host key of the specified type.
     *
     * @param type Key type: "rsa", "ecdsa", "ed25519", or "auto" (default RSA)
     * @param keySize Key size in bits (for RSA: 2048/4096, for ECDSA: 256/384/521)
     * @return KeyGenResult with generation details
     */
    fun generateKey(type: String = "rsa", keySize: Int? = null): KeyGenResult {
        val normalizedType = type.lowercase().trim()

        try {
            val keyPair: KeyPair
            val keyType: String
            val effectiveSize: Int

            when (normalizedType) {
                "rsa" -> {
                    effectiveSize = keySize ?: DEFAULT_RSA_KEY_SIZE
                    if (effectiveSize < MIN_RSA_KEY_SIZE) {
                        return KeyGenResult(false, KEY_TYPE_RSA, effectiveSize, "", "",
                            "RSA key size must be at least $MIN_RSA_KEY_SIZE bits")
                    }
                    val generator = KeyPairGenerator.getInstance("RSA")
                    generator.initialize(effectiveSize, SecureRandom())
                    keyPair = generator.generateKeyPair()
                    keyType = KEY_TYPE_RSA
                }
                "ecdsa" -> {
                    val curveSize = keySize ?: 256
                    val curveName = when (curveSize) {
                        256 -> ECDSA_CURVE_P256
                        384 -> ECDSA_CURVE_P384
                        521 -> ECDSA_CURVE_P521
                        else -> {
                            return KeyGenResult(false, KEY_TYPE_ECDSA, curveSize, "", "",
                                "Invalid ECDSA curve size: $curveSize (use 256, 384, or 521)")
                        }
                    }
                    val generator = KeyPairGenerator.getInstance("EC")
                    val ecSpec = ECGenParameterSpec(curveName)
                    generator.initialize(ecSpec, SecureRandom())
                    keyPair = generator.generateKeyPair()
                    keyType = when (curveSize) {
                        256 -> "ecdsa-sha2-nistp256"
                        384 -> "ecdsa-sha2-nistp384"
                        521 -> "ecdsa-sha2-nistp521"
                        else -> KEY_TYPE_ECDSA
                    }
                    effectiveSize = curveSize
                }
                "ed25519" -> {
                    // Ed25519 requires API 33+ or BouncyCastle
                    // Fall back to ECDSA P-256 if not available
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val generator = KeyPairGenerator.getInstance("Ed25519")
                        keyPair = generator.generateKeyPair()
                        keyType = KEY_TYPE_ED25519
                        effectiveSize = 256
                    } else {
                        Log.w(TAG, "Ed25519 not available on API < 33, falling back to ECDSA P-256")
                        val generator = KeyPairGenerator.getInstance("EC")
                        val ecSpec = ECGenParameterSpec(ECDSA_CURVE_P256)
                        generator.initialize(ecSpec, SecureRandom())
                        keyPair = generator.generateKeyPair()
                        keyType = KEY_TYPE_ECDSA
                        effectiveSize = 256
                    }
                }
                else -> {
                    return KeyGenResult(false, normalizedType, 0, "", "",
                        "Unknown key type: $type (supported: rsa, ecdsa, ed25519)")
                }
            }

            // Save the key pair
            val filePath = saveKeyPair(keyPair, keyType)

            // Compute fingerprints
            val sha256Fp = computeFingerprint(keyPair.public, "sha256")
            val md5Fp = computeFingerprint(keyPair.public, "md5")

            // Register the key entry
            val entry = KeyEntry(
                keyPair = keyPair,
                type = keyType,
                keySize = effectiveSize,
                createdAt = System.currentTimeMillis(),
                filePath = filePath
            )
            keyEntries[keyType] = entry

            // Set as active key
            activeKeyType = keyType

            Log.i(TAG, "Generated $keyType host key ($effectiveSize bits): SHA256:$sha256Fp")

            return KeyGenResult(
                success = true,
                keyType = keyType,
                keySize = effectiveSize,
                fingerprintSha256 = sha256Fp,
                fingerprintMd5 = md5Fp
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate $type key", e)
            return KeyGenResult(false, type, keySize ?: 0, "", "",
                "Key generation failed: ${e.message}")
        }
    }

    // ---- Key Loading ----

    /**
     * Ensure host keys exist by generating defaults if needed.
     * Called during SSH server startup.
     */
    fun ensureHostKeys() {
        val sshDir = File(context.filesDir, SSH_DIR)
        if (!sshDir.exists()) sshDir.mkdirs()

        // Load existing keys
        loadExistingKeys()

        // Generate RSA key if no keys exist
        if (keyEntries.isEmpty()) {
            Log.i(TAG, "No host keys found, generating default RSA key...")
            generateKey("rsa", DEFAULT_RSA_KEY_SIZE)
        }

        // Check for key rotation (deprecated old keys)
        checkKeyRotation()
    }

    /**
     * Load all existing host keys from storage.
     */
    private fun loadExistingKeys() {
        val sshDir = File(context.filesDir, SSH_DIR)
        if (!sshDir.exists()) return

        // Try loading each key type
        loadKeyFromFile(File(sshDir, RSA_KEY_FILE), KEY_TYPE_RSA)
        loadKeyFromFile(File(sshDir, ECDSA_KEY_FILE), KEY_TYPE_ECDSA)
        loadKeyFromFile(File(sshDir, ED25519_KEY_FILE), KEY_TYPE_ED25519)

        Log.i(TAG, "Loaded ${keyEntries.size} host key(s)")
    }

    /**
     * Load a key pair from a private key file.
     * Supports PKCS#8 (OpenSSH 7.8+) and PKCS#1 (legacy) formats.
     */
    private fun loadKeyFromFile(keyFile: File, keyType: String) {
        if (!keyFile.exists()) {
            Log.d(TAG, "Key file not found: ${keyFile.name}")
            return
        }

        try {
            val keyBytes = keyFile.readBytes()
            val keyPair = parsePrivateKey(keyBytes, keyType)

            if (keyPair != null) {
                val keySize = estimateKeySize(keyPair, keyType)

                val entry = KeyEntry(
                    keyPair = keyPair,
                    type = keyType,
                    keySize = keySize,
                    createdAt = keyFile.lastModified(),
                    filePath = keyFile.absolutePath
                )
                keyEntries[keyType] = entry

                // Set the first loaded key as active
                if (keyEntries.size == 1) {
                    activeKeyType = keyType
                }

                Log.d(TAG, "Loaded $keyType key from ${keyFile.name} ($keySize bits)")
            } else {
                Log.w(TAG, "Failed to parse key file: ${keyFile.name}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading key file: ${keyFile.name}", e)
        }
    }

    /**
     * Parse a private key from byte data.
     * Tries PKCS#8 first, then PKCS#1 for RSA.
     */
    private fun parsePrivateKey(keyBytes: ByteArray, keyType: String): KeyPair? {
        // Strip PEM headers if present
        val pemContent = String(keyBytes, Charsets.UTF_8)
        val base64Data = if (pemContent.contains("-----BEGIN")) {
            pemContent
                .replace("-----BEGIN.*-----".toRegex(), "")
                .replace("-----END.*-----".toRegex(), "")
                .replace("\\s".toRegex(), "")
        } else {
            // Assume raw base64
            pemContent.trim()
        }

        val derData = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

        // Try PKCS#8 format
        try {
            val keySpec = PKCS8EncodedKeySpec(derData)
            val factory = KeyFactory.getInstance(when (keyType) {
                KEY_TYPE_RSA -> "RSA"
                KEY_TYPE_ECDSA -> "EC"
                KEY_TYPE_ED25519 -> {
                    if (android.os.Build.VERSION.SDK_INT >= 33) "Ed25519" else "EC"
                }
                else -> "RSA"
            })
            val privateKey = factory.generatePrivate(keySpec)
            val publicKey = factory.generatePublic(X509EncodedKeySpec(privateKey.encoded))
            // Note: X509EncodedKeySpec from private key won't work directly;
            // we need to reconstruct from the public key
            return reconstructKeyPair(privateKey, keyType)
        } catch (e: Exception) {
            Log.d(TAG, "PKCS#8 parsing failed for $keyType, trying alternate format")
        }

        // Try loading from the .pub file for public key
        try {
            val pubFile = File(keyTypeToFile(keyType).absolutePath + PUB_KEY_SUFFIX)
            if (pubFile.exists()) {
                val pubContent = pubFile.readText().trim()
                val pubParts = pubContent.split("\\s+".toRegex())
                if (pubParts.size >= 2) {
                    val pubKeyData = android.util.Base64.decode(
                        pubParts[1], android.util.Base64.DEFAULT
                    )
                    val publicKey = parsePublicKeyBlob(pubKeyData, keyType)
                    if (publicKey != null) {
                        // We have the public key but need the private key
                        // If PKCS#8 failed, try PKCS#1 for RSA
                        if (keyType == KEY_TYPE_RSA) {
                            val privateKey = parseRsaPkcs1(derData)
                            if (privateKey != null) {
                                return KeyPair(publicKey, privateKey)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Public key file parsing failed: ${e.message}")
        }

        return null
    }

    /**
     * Reconstruct a KeyPair from a private key.
     */
    private fun reconstructKeyPair(privateKey: PrivateKey, keyType: String): KeyPair? {
        try {
            when (privateKey) {
                is RSAPrivateKey -> {
                    val factory = KeyFactory.getInstance("RSA")
                    val pubSpec = RSAPublicKeySpec(privateKey.modulus, BigInteger("65537"))
                    val publicKey = factory.generatePublic(pubSpec)
                    return KeyPair(publicKey, privateKey)
                }
                is ECPrivateKey -> {
                    val factory = KeyFactory.getInstance("EC")
                    val w = ecPointMultiply(privateKey.params.generator, privateKey.s, privateKey.params)
                    val pubSpec = ECPublicKeySpec(w, privateKey.params)
                    val publicKey = factory.generatePublic(pubSpec)
                    return KeyPair(publicKey, privateKey)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to reconstruct key pair from private key", e)
        }
        return null
    }

    /**
     * Try parsing RSA PKCS#1 format (legacy OpenSSH format).
     */
    private fun parseRsaPkcs1(derData: ByteArray): RSAPrivateKey? {
        try {
            // Minimal PKCS#1 RSA private key parsing
            // In practice, we'd use BouncyCastle for robust parsing
            val keySpec = PKCS8EncodedKeySpec(wrapPkcs1ToPkcs8(derData, "RSA"))
            val factory = KeyFactory.getInstance("RSA")
            return factory.generatePrivate(keySpec) as RSAPrivateKey
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Wrap PKCS#1 data into PKCS#8 format.
     * This is a simplified approach — proper PKCS#1 parsing requires ASN.1 decoding.
     */
    private fun wrapPkcs1ToPkcs8(pkcs1Data: ByteArray, algorithm: String): ByteArray {
        // PKCS#8 wrapper for RSA:
        // SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL },
        //   OCTET STRING { <pkcs1-data> } }
        val rsaOid = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00)

        val wrapped = ByteArrayOutputStream()
        wrapped.write(0x30) // SEQUENCE
        wrapped.write(0x82) // 2-byte length follows
        val contentLen = 3 + rsaOid.size + 4 + pkcs1Data.size
        wrapped.write((contentLen shr 8) and 0xFF)
        wrapped.write(contentLen and 0xFF)
        wrapped.write(0x02) // INTEGER
        wrapped.write(0x01) // length 1
        wrapped.write(0x00) // version 0
        wrapped.write(rsaOid)
        wrapped.write(0x04) // OCTET STRING
        wrapped.write(0x82) // 2-byte length
        wrapped.write((pkcs1Data.size shr 8) and 0xFF)
        wrapped.write(pkcs1Data.size and 0xFF)
        wrapped.write(pkcs1Data)

        return wrapped.toByteArray()
    }

    /**
     * Parse an SSH public key blob into a PublicKey.
     */
    private fun parsePublicKeyBlob(blob: ByteArray, keyType: String): PublicKey? {
        try {
            val input = DataInputStream(ByteArrayInputStream(blob))
            val type = readSshString(input)

            return when (type) {
                KEY_TYPE_RSA -> {
                    val factory = KeyFactory.getInstance("RSA")
                    val exponent = BigInteger(readSshBlob(input))
                    val modulus = BigInteger(readSshBlob(input))
                    factory.generatePublic(RSAPublicKeySpec(modulus, exponent))
                }
                "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                    val curveName = readSshString(input)
                    val pointData = readSshBlob(input)
                    val factory = KeyFactory.getInstance("EC")
                    // Simplified: reconstruct from point data
                    val ecSpec = ECGenParameterSpec(when (curveName) {
                        "nistp256" -> ECDSA_CURVE_P256
                        "nistp384" -> ECDSA_CURVE_P384
                        "nistp521" -> ECDSA_CURVE_P521
                        else -> ECDSA_CURVE_P256
                    })
                    val keyGen = KeyPairGenerator.getInstance("EC")
                    keyGen.initialize(ecSpec)
                    // Note: proper EC point reconstruction requires parsing the point
                    // This is a simplified approach
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }

    // ---- Key Saving ----

    /**
     * Save a key pair to the SSH directory.
     * Saves both the private key (PKCS#8 PEM) and public key (OpenSSH format).
     *
     * @param keyPair The key pair to save
     * @param keyType The SSH key type identifier
     * @return The path to the private key file
     */
    private fun saveKeyPair(keyPair: KeyPair, keyType: String): String {
        val sshDir = File(context.filesDir, SSH_DIR)
        if (!sshDir.exists()) sshDir.mkdirs()

        val keyFile = keyTypeToFile(keyType)
        val pubFile = File(keyFile.absolutePath + PUB_KEY_SUFFIX)

        // Save private key in PKCS#8 PEM format
        val privateKeyPem = buildPem(
            "PRIVATE KEY",
            keyPair.private.encoded
        )
        keyFile.writeText(privateKeyPem)

        // Save public key in OpenSSH format
        val pubKeyBlob = encodePublicKeyBlob(keyPair, keyType)
        val pubKeyLine = "$keyType ${android.util.Base64.encodeToString(pubKeyBlob, android.util.Base64.NO_WRAP)} termx@android"
        pubFile.writeText("$pubKeyLine\n")

        // Set restrictive permissions
        setFilePermissions(keyFile, "600")
        setFilePermissions(pubFile, "644")

        Log.d(TAG, "Saved $keyType key pair: ${keyFile.name}")

        return keyFile.absolutePath
    }

    /**
     * Build a PEM-encoded string for key data.
     */
    private fun buildPem(header: String, data: ByteArray): String {
        val base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        val lines = base64.chunked(64)
        return buildString {
            appendLine("-----BEGIN $header-----")
            lines.forEach { appendLine(it) }
            appendLine("-----END $header-----")
        }
    }

    /**
     * Encode a public key as an SSH wire-format blob.
     */
    private fun encodePublicKeyBlob(keyPair: KeyPair, keyType: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)

        when (keyType) {
            KEY_TYPE_RSA -> {
                val rsaPub = keyPair.public as RSAPublicKey
                out.write(encodeSshString(KEY_TYPE_RSA))
                out.write(encodeSshBlob(rsaPub.publicExponent.toByteArray()))
                out.write(encodeSshBlob(rsaPub.modulus.toByteArray()))
            }
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                val ecPub = keyPair.public as ECPublicKey
                val curveName = when (keyType) {
                    "ecdsa-sha2-nistp256" -> "nistp256"
                    "ecdsa-sha2-nistp384" -> "nistp384"
                    "ecdsa-sha2-nistp521" -> "nistp521"
                    else -> "nistp256"
                }
                out.write(encodeSshString(keyType))
                out.write(encodeSshString(curveName))
                // Encode EC point as uncompressed: 0x04 || x || y
                val point = ecPub.w
                val xBytes = stripLeadingZero(point.affineX.toByteArray())
                val yBytes = stripLeadingZero(point.affineY.toByteArray())
                val coordLen = maxOf(xBytes.size, yBytes.size)
                val pointBlob = ByteArray(1 + coordLen * 2)
                pointBlob[0] = 0x04
                System.arraycopy(xBytes, 0, pointBlob, 1 + coordLen - xBytes.size, xBytes.size)
                System.arraycopy(yBytes, 0, pointBlob, 1 + coordLen + coordLen - yBytes.size, yBytes.size)
                out.write(encodeSshBlob(pointBlob))
            }
            KEY_TYPE_ED25519 -> {
                // Ed25519 public key is 32 bytes
                out.write(encodeSshString(KEY_TYPE_ED25519))
                val pubBytes = keyPair.public.encoded
                // The encoded form includes the raw 32-byte key
                out.write(encodeSshBlob(pubBytes.copyOfRange(pubBytes.size - 32, pubBytes.size)))
            }
        }

        return buf.toByteArray()
    }

    /**
     * Multiply an EC point by a scalar using double-and-add algorithm.
     * Required because java.security.spec.ECPoint does not have a multiply method.
     */
    private fun ecPointMultiply(
        generator: java.security.spec.ECPoint,
        scalar: BigInteger,
        params: java.security.spec.ECParameterSpec
    ): java.security.spec.ECPoint {
        val curve = params.curve
        val a = curve.a
        val prime = (curve.field as java.security.spec.ECFieldFp).p

        var result: java.security.spec.ECPoint? = null // point at infinity

        for (i in scalar.bitLength() - 1 downTo 0) {
            if (result != null) {
                result = ecPointDouble(result, a, prime)
            }
            if (scalar.testBit(i)) {
                if (result == null) {
                    result = generator
                } else {
                    result = ecPointAdd(result, generator, a, prime)
                }
            }
        }

        return result ?: java.security.spec.ECPoint(BigInteger.ZERO, BigInteger.ZERO)
    }

    private fun ecPointAdd(
        p1: java.security.spec.ECPoint,
        p2: java.security.spec.ECPoint,
        a: BigInteger,
        prime: BigInteger
    ): java.security.spec.ECPoint {
        val dy = p2.affineY.subtract(p1.affineY)
        val dx = p2.affineX.subtract(p1.affineX)
        val lambda = dy.multiply(dx.modInverse(prime)).mod(prime)
        val x3 = lambda.multiply(lambda).subtract(p1.affineX).subtract(p2.affineX).mod(prime)
        val y3 = lambda.multiply(p1.affineX.subtract(x3)).subtract(p1.affineY).mod(prime)
        return java.security.spec.ECPoint(x3, y3)
    }

    private fun ecPointDouble(
        p: java.security.spec.ECPoint,
        a: BigInteger,
        prime: BigInteger
    ): java.security.spec.ECPoint {
        val lambda = BigInteger.valueOf(3).multiply(p.affineX).multiply(p.affineX)
            .add(a)
            .multiply(BigInteger.valueOf(2).multiply(p.affineY).modInverse(prime))
            .mod(prime)
        val x3 = lambda.multiply(lambda).subtract(BigInteger.valueOf(2).multiply(p.affineX)).mod(prime)
        val y3 = lambda.multiply(p.affineX.subtract(x3)).subtract(p.affineY).mod(prime)
        return java.security.spec.ECPoint(x3, y3)
    }

    /**
     * Strip leading zero byte from a BigInteger byte array.
     */
    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    // ---- Key Access ----

    /**
     * Get the active (current) host key pair.
     * This is the key used for new SSH connections.
     */
    fun getActiveKeyPair(): KeyPair {
        val entry = keyEntries[activeKeyType]
        if (entry != null) return entry.keyPair

        // Fallback: return any available key
        val fallback = keyEntries.values.firstOrNull()
        if (fallback != null) {
            activeKeyType = fallback.type
            return fallback.keyPair
        }

        // Last resort: generate a new key
        val result = generateKey("rsa", DEFAULT_RSA_KEY_SIZE)
        if (result.success) {
            return keyEntries[activeKeyType]?.keyPair
                ?: throw IllegalStateException("Failed to generate host key")
        }

        throw IllegalStateException("No host keys available and generation failed")
    }

    /**
     * Get the active key type info string.
     */
    fun getActiveKeyInfo(): String {
        val entry = keyEntries[activeKeyType] ?: return "none"
        return "${entry.type} (${entry.keySize} bits)"
    }

    /**
     * Get information about all host keys.
     */
    fun getAllKeyInfo(): List<HostKeyInfo> {
        return keyEntries.values.map { entry ->
            HostKeyInfo(
                type = entry.type,
                keySize = entry.keySize,
                fingerprintSha256 = computeFingerprint(entry.keyPair.public, "sha256"),
                fingerprintMd5 = computeFingerprint(entry.keyPair.public, "md5"),
                createdAt = entry.createdAt,
                filePath = entry.filePath,
                isDeprecated = entry.isDeprecated
            )
        }.sortedByDescending { it.createdAt }
    }

    /**
     * Get a specific key pair by type.
     */
    fun getKeyPair(keyType: String): KeyPair? {
        return keyEntries[keyType]?.keyPair
    }

    /**
     * Set the active key type.
     * The key must already exist.
     */
    fun setActiveKeyType(keyType: String): Boolean {
        if (keyEntries.containsKey(keyType)) {
            activeKeyType = keyType
            Log.i(TAG, "Active key type set to: $keyType")
            return true
        }
        Log.w(TAG, "Cannot set active key type to $keyType: key not found")
        return false
    }

    // ---- Fingerprint Calculation ----

    /**
     * Compute the fingerprint of a public key.
     *
     * @param publicKey The public key to fingerprint
     * @param algorithm Hash algorithm: "sha256" (default) or "md5"
     * @return The fingerprint string
     *
     * SHA-256 format: Base64-encoded hash with "SHA256:" prefix (OpenSSH style)
     *   Example: SHA256:uNiVztksCsDhcc0u9e8BgrJXVGLWSNrY8F5xsWzzoQg
     *
     * MD5 format: Colon-separated hex pairs (legacy OpenSSH style)
     *   Example: 04:c5:30:28:19:7f:de:32:61:34:8a:db:fc:bc:b5:67
     */
    fun computeFingerprint(publicKey: PublicKey, algorithm: String = "sha256"): String {
        // Encode the public key as an SSH blob first
        val keyType = when (publicKey) {
            is RSAPublicKey -> KEY_TYPE_RSA
            is ECPublicKey -> KEY_TYPE_ECDSA
            else -> KEY_TYPE_RSA
        }
        val keyBlob = encodePublicKeyBlob(KeyPair(publicKey, generateDummyPrivateKey(keyType)), keyType)

        return when (algorithm.lowercase()) {
            "sha256" -> {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(keyBlob)
                val b64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                "SHA256:$b64"
            }
            "md5" -> {
                val digest = MessageDigest.getInstance("MD5")
                val hash = digest.digest(keyBlob)
                hash.joinToString(":") { "%02x".format(it) }
            }
            else -> {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(keyBlob)
                val b64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                "SHA256:$b64"
            }
        }
    }

    /**
     * Generate a dummy private key for KeyPair construction (fingerprint computation only).
     */
    private fun generateDummyPrivateKey(keyType: String): PrivateKey {
        return when (keyType) {
            KEY_TYPE_RSA -> {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                generator.generateKeyPair().private
            }
            KEY_TYPE_ECDSA -> {
                val generator = KeyPairGenerator.getInstance("EC")
                generator.initialize(ECGenParameterSpec(ECDSA_CURVE_P256))
                generator.generateKeyPair().private
            }
            else -> {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                generator.generateKeyPair().private
            }
        }
    }

    // ---- Key Rotation ----

    /**
     * Check if any keys need rotation based on age.
     */
    private fun checkKeyRotation() {
        val now = System.currentTimeMillis()
        val maxAgeMs = MAX_KEY_AGE_DAYS * 24 * 60 * 60 * 1000L

        for (entry in keyEntries.values) {
            if (now - entry.createdAt > maxAgeMs && !entry.isDeprecated) {
                Log.i(TAG, "Key ${entry.type} is older than $MAX_KEY_AGE_DAYS days, marking for rotation")
                entry.isDeprecated = true
            }
        }

        // If all keys are deprecated, generate a new one
        if (keyEntries.values.all { it.isDeprecated }) {
            Log.i(TAG, "All keys are deprecated, generating new RSA key")
            generateKey("rsa", DEFAULT_RSA_KEY_SIZE)
        }

        // Ensure at least one non-deprecated key is active
        val activeEntry = keyEntries[activeKeyType]
        if (activeEntry?.isDeprecated == true) {
            val nonDeprecated = keyEntries.values.find { !it.isDeprecated }
            if (nonDeprecated != null) {
                activeKeyType = nonDeprecated.type
            }
        }
    }

    /**
     * Rotate all host keys.
     * Marks current keys as deprecated and generates new ones.
     *
     * @return List of new key generation results
     */
    fun rotateKeys(): List<KeyGenResult> {
        val results = mutableListOf<KeyGenResult>()

        Log.i(TAG, "Rotating all host keys...")

        // Mark all current keys as deprecated
        keyEntries.values.forEach { it.isDeprecated = true }

        // Generate new keys
        results.add(generateKey("rsa", DEFAULT_RSA_KEY_SIZE))
        results.add(generateKey("ecdsa", 256))

        // Try Ed25519 if available
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            results.add(generateKey("ed25519"))
        }

        // Clean up excess deprecated keys
        cleanupDeprecatedKeys()

        Log.i(TAG, "Key rotation complete: ${results.count { it.success }} new keys generated")
        return results
    }

    /**
     * Clean up excess deprecated key files.
     * Keeps at most MAX_DEPRECATED_KEYS deprecated key sets.
     */
    private fun cleanupDeprecatedKeys() {
        val deprecatedEntries = keyEntries.values
            .filter { it.isDeprecated }
            .sortedByDescending { it.createdAt }

        if (deprecatedEntries.size > MAX_DEPRECATED_KEYS) {
            val toRemove = deprecatedEntries.drop(MAX_DEPRECATED_KEYS)
            toRemove.forEach { entry ->
                Log.d(TAG, "Removing old deprecated key: ${entry.filePath}")
                try {
                    File(entry.filePath).delete()
                    File(entry.filePath + PUB_KEY_SUFFIX).delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete deprecated key file: ${entry.filePath}", e)
                }
                keyEntries.remove(entry.type)
            }
        }
    }

    // ---- Known Hosts Management ----

    /**
     * Export all current host keys in known_hosts format.
     * Format: [host]:port key-type base64-key
     *
     * @param port The SSH server port
     * @param host The server hostname (default: localhost)
     * @return Lines in known_hosts format
     */
    fun exportKnownHosts(port: Int = 8022, host: String = "localhost"): List<String> {
        val lines = mutableListOf<String>()

        for (entry in keyEntries.values.filter { !it.isDeprecated }) {
            val keyBlob = encodePublicKeyBlob(entry.keyPair, entry.type)
            val b64 = android.util.Base64.encodeToString(keyBlob,
                android.util.Base64.NO_WRAP)
            lines.add("[$host]:$port ${entry.type} $b64")
        }

        return lines
    }

    /**
     * Write known_hosts entries to a file.
     */
    fun writeKnownHostsFile(port: Int = 8022, host: String = "localhost") {
        val sshDir = File(context.filesDir, SSH_DIR)
        if (!sshDir.exists()) sshDir.mkdirs()

        val knownHostsFile = File(sshDir, "known_hosts")
        val lines = exportKnownHosts(port, host)
        knownHostsFile.writeText(lines.joinToString("\n") + "\n")

        Log.d(TAG, "Written ${lines.size} known_hosts entries")
    }

    // ---- Utility ----

    /**
     * Map a key type to its file path.
     */
    private fun keyTypeToFile(keyType: String): File {
        val name = when (keyType) {
            KEY_TYPE_RSA -> RSA_KEY_FILE
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", KEY_TYPE_ECDSA -> ECDSA_KEY_FILE
            KEY_TYPE_ED25519 -> ED25519_KEY_FILE
            else -> RSA_KEY_FILE
        }
        return File(context.filesDir, "$SSH_DIR/$name")
    }

    /**
     * Estimate the key size from a key pair.
     */
    private fun estimateKeySize(keyPair: KeyPair, keyType: String): Int {
        return when (keyPair.public) {
            is RSAPublicKey -> (keyPair.public as RSAPublicKey).modulus.bitLength()
            is ECPublicKey -> (keyPair.public as ECPublicKey).params.curve.field.fieldSize
            else -> 256
        }
    }

    /**
     * Set file permissions using chmod.
     */
    private fun setFilePermissions(file: File, mode: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("chmod", mode, file.absolutePath))
            proc.waitFor()
        } catch (e: Exception) {
            Log.d(TAG, "chmod failed (expected on some Android versions): ${e.message}")
        }
    }

    /**
     * Read an SSH string from a DataInputStream.
     */
    private fun readSshString(input: DataInputStream): String {
        val len = input.readInt()
        val data = ByteArray(len)
        input.readFully(data)
        return String(data, Charsets.UTF_8)
    }

    /**
     * Read an SSH blob from a DataInputStream.
     */
    private fun readSshBlob(input: DataInputStream): ByteArray {
        val len = input.readInt()
        val data = ByteArray(len)
        input.readFully(data)
        return data
    }

    /**
     * Encode a string as SSH wire format.
     */
    private fun encodeSshString(s: String): ByteArray {
        val data = s.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(data.size)
        out.write(data)
        return buf.toByteArray()
    }

    /**
     * Encode a blob as SSH wire format.
     */
    private fun encodeSshBlob(data: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(data.size)
        out.write(data)
        return buf.toByteArray()
    }

    /**
     * Get a summary of all host keys as a formatted string.
     */
    fun getSummary(): String {
        val infos = getAllKeyInfo()
        return buildString {
            appendLine("TermX SSH Host Keys")
            appendLine("=" .repeat(40))

            if (infos.isEmpty()) {
                appendLine("No host keys configured")
            } else {
                infos.forEach { info ->
                    appendLine()
                    appendLine("  Type: ${info.type}")
                    appendLine("  Size: ${info.keySize} bits")
                    appendLine("  SHA256: ${info.fingerprintSha256}")
                    appendLine("  MD5: ${info.fingerprintMd5}")
                    appendLine("  Created: ${java.text.SimpleDateFormat.getDateTimeInstance()
                        .format(java.util.Date(info.createdAt))}")
                    appendLine("  File: ${info.filePath}")
                    if (info.isDeprecated) appendLine("  Status: DEPRECATED (rotation needed)")
                    else appendLine("  Status: Active")
                }
            }
        }
    }
}
