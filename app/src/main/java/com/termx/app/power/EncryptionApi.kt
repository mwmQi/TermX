package com.termx.app.power

import android.content.Context
import android.util.Log
import java.io.*
import java.security.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*
import java.util.Base64

/**
 * Encryption API for TermX — file and text encryption from terminal.
 *
 * Provides comprehensive cryptographic operations including:
 * - AES-256-CBC encryption/decryption with PBKDF2 key derivation
 * - RSA key generation, encryption, decryption, signing, verification
 * - SHA-256, SHA-512, MD5, SHA-1 hashing and HMAC
 * - Base64 encode/decode
 * - File and text operations
 * - Key storage in app private directory
 *
 * Shell usage:
 *   termx-crypt encrypt <input> <output> [password]  Encrypt file (AES-256)
 *   termx-crypt decrypt <input> <output> <password>  Decrypt file
 *   termx-crypt hash <file> [algo]                    Hash file (SHA-256 default)
 *   termx-crypt hash-text <text> [algo]               Hash text
 *   termx-crypt gen-key [algo] [bits]                 Generate key pair
 *   termx-crypt sign <file> <key>                     Sign file
 *   termx-crypt verify <file> <sig> <key>             Verify signature
 *   termx-crypt base64-encode <input>                 Base64 encode
 *   termx-crypt base64-decode <input>                 Base64 decode
 *   termx-crypt aes-enc <text> <password>             AES encrypt text
 *   termx-crypt aes-dec <ciphertext> <password>       AES decrypt text
 *   termx-crypt rsa-enc <text> <pubkey>               RSA encrypt
 *   termx-crypt rsa-dec <ciphertext> <privkey>        RSA decrypt
 *   termx-crypt list-algos                            List available algorithms
 */
object EncryptionApi {

    private const val TAG = "EncryptionApi"
    private const val KEY_DIR = "keys"
    private const val AES_ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val RSA_ALGORITHM = "RSA/ECB/PKCS1Padding"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 16
    private const val ITERATIONS = 10000
    private const val AES_KEY_SIZE = 256

    // Supported hash algorithms
    private val HASH_ALGORITHMS = listOf("SHA-256", "SHA-512", "SHA-1", "MD5")
    private val KEY_ALGORITHMS = listOf("RSA", "DSA", "EC")

    // =========================================================================
    // Main command dispatcher — called from shell scripts / TermXApiReceiver
    // =========================================================================

    /**
     * Execute a termx-crypt command.
     *
     * @param context Android context
     * @param args    Command arguments (first element is subcommand)
     * @return Result string to be printed to terminal
     */
    fun execute(context: Context, args: List<String>): String {
        if (args.isEmpty()) return getHelpText()

        return try {
            when (args[0]) {
                "encrypt" -> encryptFile(context, args)
                "decrypt" -> decryptFile(context, args)
                "hash" -> hashFile(args)
                "hash-text" -> hashText(args)
                "gen-key" -> generateKeyPair(context, args)
                "sign" -> signFile(context, args)
                "verify" -> verifyFile(context, args)
                "base64-encode" -> base64Encode(args)
                "base64-decode" -> base64Decode(args)
                "aes-enc" -> aesEncryptText(args)
                "aes-dec" -> aesDecryptText(args)
                "rsa-enc" -> rsaEncryptText(context, args)
                "rsa-dec" -> rsaDecryptText(context, args)
                "hmac" -> hmacText(args)
                "list-algos" -> listAlgorithms()
                "help" -> getHelpText()
                else -> "Unknown command: ${args[0]}\n${getHelpText()}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${args[0]}", e)
            "Error: ${e.message}"
        }
    }

    // =========================================================================
    // AES-256 File Encryption / Decryption
    // =========================================================================

    /**
     * Encrypt a file using AES-256-CBC with PBKDF2 key derivation.
     * Output format: [SALT(16)] [IV(16)] [ENCRYPTED_DATA]
     *
     * Usage: termx-crypt encrypt <input> <output> [password]
     */
    private fun encryptFile(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt encrypt <input> <output> [password]"

        val inputPath = args[1]
        val outputPath = args[2]
        val password = if (args.size >= 4) args[3] else readPasswordFromStdin()
            ?: return "Error: Password required (provide as argument or via stdin)"

        val inputFile = File(inputPath)
        if (!inputFile.exists()) return "Error: Input file not found: $inputPath"

        try {
            // Generate random salt and IV
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

            // Derive key from password using PBKDF2
            val secretKey = deriveKey(password, salt)

            // Encrypt
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

            val inputBytes = inputFile.readBytes()
            val encryptedBytes = cipher.doFinal(inputBytes)

            // Write: salt + iv + ciphertext
            val outputStream = File(outputPath)
            outputStream.parentFile?.mkdirs()
            outputStream.outputStream().use { out ->
                out.write(salt)
                out.write(iv)
                out.write(encryptedBytes)
            }

            // Securely wipe plaintext from memory
            inputBytes.fill(0)

            val sizeKb = encryptedBytes.size / 1024
            return "Encrypted: $inputPath -> $outputPath (${sizeKb}KB)"
        } catch (e: Exception) {
            return "Encryption failed: ${e.message}"
        }
    }

    /**
     * Decrypt a file encrypted with AES-256-CBC.
     * Reads format: [SALT(16)] [IV(16)] [ENCRYPTED_DATA]
     *
     * Usage: termx-crypt decrypt <input> <output> <password>
     */
    private fun decryptFile(context: Context, args: List<String>): String {
        if (args.size < 4) return "Usage: termx-crypt decrypt <input> <output> <password>"

        val inputPath = args[1]
        val outputPath = args[2]
        val password = args[3]

        val inputFile = File(inputPath)
        if (!inputFile.exists()) return "Error: Input file not found: $inputPath"

        try {
            val allBytes = inputFile.readBytes()

            // Extract salt, IV, and ciphertext
            if (allBytes.size < SALT_SIZE + IV_SIZE) {
                return "Error: Invalid encrypted file format (too short)"
            }

            val salt = allBytes.copyOfRange(0, SALT_SIZE)
            val iv = allBytes.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
            val encryptedBytes = allBytes.copyOfRange(SALT_SIZE + IV_SIZE, allBytes.size)

            // Derive key from password
            val secretKey = deriveKey(password, salt)

            // Decrypt
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(encryptedBytes)

            // Write output
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(decryptedBytes)

            // Securely wipe
            decryptedBytes.fill(0)

            val sizeKb = inputFile.length() / 1024
            return "Decrypted: $inputPath -> $outputPath (${sizeKb}KB)"
        } catch (e: BadPaddingException) {
            return "Decryption failed: Wrong password or corrupted file"
        } catch (e: Exception) {
            return "Decryption failed: ${e.message}"
        }
    }

    // =========================================================================
    // AES-256 Text Encryption / Decryption
    // =========================================================================

    /**
     * AES-256-CBC encrypt a text string.
     * Returns Base64-encoded string: Base64(SALT + IV + CIPHERTEXT)
     *
     * Usage: termx-crypt aes-enc <text> <password>
     */
    private fun aesEncryptText(args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt aes-enc <text> <password>"

        val plaintext = args[1]
        val password = args[2]

        try {
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            val secretKey = deriveKey(password, salt)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Combine salt + iv + ciphertext and Base64 encode
            val combined = ByteArray(SALT_SIZE + IV_SIZE + encryptedBytes.size)
            System.arraycopy(salt, 0, combined, 0, SALT_SIZE)
            System.arraycopy(iv, 0, combined, SALT_SIZE, IV_SIZE)
            System.arraycopy(encryptedBytes, 0, combined, SALT_SIZE + IV_SIZE, encryptedBytes.size)

            return Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            return "AES encryption failed: ${e.message}"
        }
    }

    /**
     * AES-256-CBC decrypt a Base64-encoded ciphertext string.
     *
     * Usage: termx-crypt aes-dec <ciphertext> <password>
     */
    private fun aesDecryptText(args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt aes-dec <ciphertext> <password>"

        val ciphertextB64 = args[1]
        val password = args[2]

        try {
            val combined = Base64.getDecoder().decode(ciphertextB64)

            if (combined.size < SALT_SIZE + IV_SIZE) {
                return "Error: Invalid ciphertext format"
            }

            val salt = combined.copyOfRange(0, SALT_SIZE)
            val iv = combined.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
            val encryptedBytes = combined.copyOfRange(SALT_SIZE + IV_SIZE, combined.size)

            val secretKey = deriveKey(password, salt)
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: BadPaddingException) {
            return "Decryption failed: Wrong password"
        } catch (e: Exception) {
            return "AES decryption failed: ${e.message}"
        }
    }

    // =========================================================================
    // Hashing
    // =========================================================================

    /**
     * Hash a file.
     * Usage: termx-crypt hash <file> [algo]
     */
    private fun hashFile(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-crypt hash <file> [algo]"

        val filePath = args[1]
        val algorithm = if (args.size >= 3) args[2].uppercase() else "SHA-256"

        if (!HASH_ALGORITHMS.contains(algorithm)) {
            return "Unsupported algorithm: $algorithm\nSupported: ${HASH_ALGORITHMS.joinToString(", ")}"
        }

        val file = File(filePath)
        if (!file.exists()) return "Error: File not found: $filePath"

        try {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = digest.digest()
            val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

            return "$algorithm ($filePath) = $hashHex"
        } catch (e: Exception) {
            return "Hash failed: ${e.message}"
        }
    }

    /**
     * Hash a text string.
     * Usage: termx-crypt hash-text <text> [algo]
     */
    private fun hashText(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-crypt hash-text <text> [algo]"

        val text = args[1]
        val algorithm = if (args.size >= 3) args[2].uppercase() else "SHA-256"

        if (!HASH_ALGORITHMS.contains(algorithm)) {
            return "Unsupported algorithm: $algorithm\nSupported: ${HASH_ALGORITHMS.joinToString(", ")}"
        }

        try {
            val digest = MessageDigest.getInstance(algorithm)
            val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
            val hashHex = hashBytes.joinToString("") { "%02x".format(it) }

            return "$algorithm = $hashHex"
        } catch (e: Exception) {
            return "Hash failed: ${e.message}"
        }
    }

    /**
     * Generate HMAC for a text string.
     * Usage: termx-crypt hmac <text> <key> [algo]
     */
    private fun hmacText(args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt hmac <text> <key> [algo]"

        val text = args[1]
        val key = args[2]
        val algorithm = if (args.size >= 4) args[3].uppercase() else "HmacSHA256"

        try {
            val mac = Mac.getInstance(algorithm)
            val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm)
            mac.init(secretKeySpec)
            val hmacBytes = mac.doFinal(text.toByteArray(Charsets.UTF_8))
            val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }

            return "$algorithm = $hmacHex"
        } catch (e: Exception) {
            return "HMAC failed: ${e.message}"
        }
    }

    // =========================================================================
    // RSA Key Generation, Encryption, Decryption, Signing, Verification
    // =========================================================================

    /**
     * Generate an RSA/DSA/EC key pair and store in app private directory.
     * Usage: termx-crypt gen-key [algo] [bits]
     */
    private fun generateKeyPair(context: Context, args: List<String>): String {
        val algorithm = if (args.size >= 2) args[1].uppercase() else "RSA"
        val keySize = if (args.size >= 3) args[2].toIntOrNull() ?: 2048 else 2048

        if (!KEY_ALGORITHMS.contains(algorithm)) {
            return "Unsupported algorithm: $algorithm\nSupported: ${KEY_ALGORITHMS.joinToString(", ")}"
        }

        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(algorithm)
            keyPairGenerator.initialize(keySize, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()

            // Save keys to app private directory
            val keyDir = File(context.filesDir, KEY_DIR)
            keyDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val privKeyFile = File(keyDir, "${algorithm}_${timestamp}_private.key")
            val pubKeyFile = File(keyDir, "${algorithm}_${timestamp}_public.key")

            // Store keys as Base64-encoded PKCS8/X509
            val privateKeyEncoded = Base64.getEncoder().encodeToString(keyPair.private.encoded)
            val publicKeyEncoded = Base64.getEncoder().encodeToString(keyPair.public.encoded)

            privKeyFile.writeText(privateKeyEncoded)
            pubKeyFile.writeText(publicKeyEncoded)

            // Also save human-readable key info
            val fingerprint = getMessageDigest(keyPair.public.encoded)

            return buildString {
                appendLine("Key pair generated successfully")
                appendLine("Algorithm: $algorithm | Key size: $keySize bits")
                appendLine("Private key: ${privKeyFile.absolutePath}")
                appendLine("Public key:  ${pubKeyFile.absolutePath}")
                appendLine("Fingerprint: $fingerprint")
            }
        } catch (e: Exception) {
            return "Key generation failed: ${e.message}"
        }
    }

    /**
     * Sign a file with a private key.
     * Usage: termx-crypt sign <file> <private-key-path>
     */
    private fun signFile(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt sign <file> <private-key-path>"

        val filePath = args[1]
        val keyPath = args[2]

        val dataFile = File(filePath)
        if (!dataFile.exists()) return "Error: File not found: $filePath"
        val keyFile = File(keyPath)
        if (!keyFile.exists()) return "Error: Key file not found: $keyPath"

        try {
            // Load private key
            val privateKey = loadPrivateKey(keyFile.readText())

            // Sign the file
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(privateKey, SecureRandom())

            dataFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    signature.update(buffer, 0, bytesRead)
                }
            }

            val signatureBytes = signature.sign()
            val signatureB64 = Base64.getEncoder().encodeToString(signatureBytes)

            // Save signature to file
            val sigFile = File("$filePath.sig")
            sigFile.writeText(signatureB64)

            return buildString {
                appendLine("File signed successfully")
                appendLine("Signature file: ${sigFile.absolutePath}")
                appendLine("Signature (Base64): $signatureB64")
            }
        } catch (e: Exception) {
            return "Signing failed: ${e.message}"
        }
    }

    /**
     * Verify a file signature with a public key.
     * Usage: termx-crypt verify <file> <signature-path> <public-key-path>
     */
    private fun verifyFile(context: Context, args: List<String>): String {
        if (args.size < 4) return "Usage: termx-crypt verify <file> <signature-path> <public-key-path>"

        val filePath = args[1]
        val sigPath = args[2]
        val keyPath = args[3]

        val dataFile = File(filePath)
        if (!dataFile.exists()) return "Error: File not found: $filePath"
        val sigFile = File(sigPath)
        if (!sigFile.exists()) return "Error: Signature file not found: $sigPath"
        val keyFile = File(keyPath)
        if (!keyFile.exists()) return "Error: Key file not found: $keyPath"

        try {
            // Load public key
            val publicKey = loadPublicKey(keyFile.readText())

            // Load signature
            val signatureBytes = Base64.getDecoder().decode(sigFile.readText().trim())

            // Verify
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)

            dataFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    signature.update(buffer, 0, bytesRead)
                }
            }

            val verified = signature.verify(signatureBytes)

            return if (verified) {
                "SIGNATURE VERIFIED: $filePath is authentic"
            } else {
                "SIGNATURE INVALID: $filePath has been tampered with or signed with a different key"
            }
        } catch (e: Exception) {
            return "Verification failed: ${e.message}"
        }
    }

    /**
     * RSA encrypt text with a public key.
     * Usage: termx-crypt rsa-enc <text> <public-key-path>
     */
    private fun rsaEncryptText(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt rsa-enc <text> <public-key-path>"

        val plaintext = args[1]
        val keyPath = args[2]
        val keyFile = File(keyPath)
        if (!keyFile.exists()) return "Error: Key file not found: $keyPath"

        try {
            val publicKey = loadPublicKey(keyFile.readText())

            val cipher = Cipher.getInstance(RSA_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)

            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (e: Exception) {
            return "RSA encryption failed: ${e.message}"
        }
    }

    /**
     * RSA decrypt text with a private key.
     * Usage: termx-crypt rsa-dec <ciphertext> <private-key-path>
     */
    private fun rsaDecryptText(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-crypt rsa-dec <ciphertext> <private-key-path>"

        val ciphertextB64 = args[1]
        val keyPath = args[2]
        val keyFile = File(keyPath)
        if (!keyFile.exists()) return "Error: Key file not found: $keyPath"

        try {
            val privateKey = loadPrivateKey(keyFile.readText())

            val cipher = Cipher.getInstance(RSA_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)

            val encryptedBytes = Base64.getDecoder().decode(ciphertextB64)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return "RSA decryption failed: ${e.message}"
        }
    }

    // =========================================================================
    // Base64 Encode / Decode
    // =========================================================================

    /**
     * Base64 encode a file or text.
     * Usage: termx-crypt base64-encode <input-path-or-text>
     */
    private fun base64Encode(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-crypt base64-encode <input>"

        val input = args[1]
        val file = File(input)

        return if (file.exists() && file.isFile) {
            // Encode file
            try {
                val bytes = file.readBytes()
                val encoded = Base64.getEncoder().encodeToString(bytes)
                // Write to output file if specified
                if (args.size >= 3) {
                    File(args[2]).writeText(encoded)
                    "Base64 encoded: $input -> ${args[2]}"
                } else {
                    encoded
                }
            } catch (e: Exception) {
                "Base64 encode failed: ${e.message}"
            }
        } else {
            // Encode text string
            Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Base64 decode a string or file.
     * Usage: termx-crypt base64-decode <input>
     */
    private fun base64Decode(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-crypt base64-decode <input>"

        val input = args[1]
        val file = File(input)

        return if (file.exists() && file.isFile) {
            // Decode file
            try {
                val encoded = file.readText().trim()
                val decoded = Base64.getDecoder().decode(encoded)
                // Write to output file if specified
                if (args.size >= 3) {
                    File(args[2]).writeBytes(decoded)
                    "Base64 decoded: $input -> ${args[2]}"
                } else {
                    String(decoded, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                "Base64 decode failed: ${e.message}"
            }
        } else {
            // Decode text string
            try {
                val decoded = Base64.getDecoder().decode(input)
                String(decoded, Charsets.UTF_8)
            } catch (e: Exception) {
                "Base64 decode failed: ${e.message}"
            }
        }
    }

    // =========================================================================
    // List Algorithms
    // =========================================================================

    /**
     * List all available cryptographic algorithms and providers.
     */
    private fun listAlgorithms(): String {
        val sb = StringBuilder()
        sb.appendLine("TermX Cryptographic Algorithms")
        sb.appendLine("================================")
        sb.appendLine()

        // Hash algorithms
        sb.appendLine("Hash Algorithms:")
        HASH_ALGORITHMS.forEach { sb.appendLine("  - $it") }
        sb.appendLine()

        // Key algorithms
        sb.appendLine("Key Generation Algorithms:")
        KEY_ALGORITHMS.forEach { sb.appendLine("  - $it") }
        sb.appendLine()

        // List security providers
        sb.appendLine("Security Providers:")
        Security.getProviders().forEachIndexed { index, provider ->
            sb.appendLine("  ${index + 1}. ${provider.name} v${provider.version} — ${provider.info}")
        }
        sb.appendLine()

        // List available Cipher algorithms
        sb.appendLine("Available Cipher Services:")
        Security.getAlgorithms("Cipher").sorted().forEach { algo ->
            sb.appendLine("  - $algo")
        }
        sb.appendLine()

        // List available MessageDigest algorithms
        sb.appendLine("Available MessageDigest Services:")
        Security.getAlgorithms("MessageDigest").sorted().forEach { algo ->
            sb.appendLine("  - $algo")
        }
        sb.appendLine()

        // List available Mac algorithms
        sb.appendLine("Available Mac Services:")
        Security.getAlgorithms("Mac").sorted().forEach { algo ->
            sb.appendLine("  - $algo")
        }
        sb.appendLine()

        // List available Signature algorithms
        sb.appendLine("Available Signature Services:")
        Security.getAlgorithms("Signature").sorted().forEach { algo ->
            sb.appendLine("  - $algo")
        }

        return sb.toString()
    }

    // =========================================================================
    // Key Management Helpers
    // =========================================================================

    /**
     * List all stored key pairs.
     */
    fun listKeys(context: Context): String {
        val keyDir = File(context.filesDir, KEY_DIR)
        if (!keyDir.exists() || keyDir.listFiles().isNullOrEmpty()) {
            return "No keys found. Use 'termx-crypt gen-key' to generate a key pair."
        }

        val sb = StringBuilder()
        sb.appendLine("Stored Key Pairs:")
        sb.appendLine("─".repeat(60))

        keyDir.listFiles()?.filter { it.name.endsWith("_public.key") }?.sortedBy { it.name }
            ?.forEach { pubKeyFile ->
                val baseName = pubKeyFile.name.removeSuffix("_public.key")
                val privKeyFile = File(keyDir, "${baseName}_private.key")
                val hasPrivate = privKeyFile.exists()

                sb.appendLine("  $baseName")
                sb.appendLine("    Public key:  ${pubKeyFile.absolutePath}")
                sb.appendLine("    Private key: ${if (hasPrivate) privKeyFile.absolutePath else "MISSING"}")
                sb.appendLine("    Size:        ${pubKeyFile.length()} bytes")
            }

        return sb.toString()
    }

    /**
     * Delete a stored key pair by base name.
     */
    fun deleteKey(context: Context, keyName: String): String {
        val keyDir = File(context.filesDir, KEY_DIR)
        val pubKeyFile = File(keyDir, "${keyName}_public.key")
        val privKeyFile = File(keyDir, "${keyName}_private.key")

        var deleted = false
        if (pubKeyFile.exists()) { pubKeyFile.delete(); deleted = true }
        if (privKeyFile.exists()) { privKeyFile.delete(); deleted = true }

        return if (deleted) "Key pair deleted: $keyName" else "Key not found: $keyName"
    }

    // =========================================================================
    // Internal Utilities
    // =========================================================================

    /**
     * Derive an AES-256 key from a password using PBKDF2 with HMAC-SHA256.
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, AES_KEY_SIZE)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Load an RSA private key from Base64-encoded PKCS8 format.
     */
    private fun loadPrivateKey(keyB64: String): PrivateKey {
        val keyBytes = Base64.getDecoder().decode(keyB64.trim())
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    /**
     * Load an RSA public key from Base64-encoded X509 format.
     */
    private fun loadPublicKey(keyB64: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(keyB64.trim())
        val keySpec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec)
    }

    /**
     * Get SHA-256 hex digest of a byte array (for fingerprinting).
     */
    private fun getMessageDigest(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString(":") { "%02x".format(it) }
    }

    /**
     * Attempt to read a password from stdin (non-echo).
     * In a terminal environment, this reads from System.in.
     */
    private fun readPasswordFromStdin(): String? {
        return try {
            System.console()?.readPassword()?.let { String(it) }
        } catch (e: Exception) {
            null
        }
    }

    // =========================================================================
    // Help Text
    // =========================================================================

    /**
     * Get help text for termx-crypt commands.
     */
    fun getHelpText(): String {
        return """
TermX Cryptographic API — termx-crypt
======================================

File Encryption:
  encrypt <input> <output> [password]   Encrypt file (AES-256-CBC)
  decrypt <input> <output> <password>   Decrypt file

Text Encryption:
  aes-enc <text> <password>             AES-256 encrypt text (Base64 output)
  aes-dec <ciphertext> <password>       AES-256 decrypt text
  rsa-enc <text> <pubkey>               RSA encrypt text
  rsa-dec <ciphertext> <privkey>        RSA decrypt text

Hashing:
  hash <file> [algo]                    Hash file (SHA-256|SHA-512|SHA-1|MD5)
  hash-text <text> [algo]               Hash text string
  hmac <text> <key> [algo]              Generate HMAC

Key Management:
  gen-key [algo] [bits]                 Generate key pair (RSA|DSA|EC)
  sign <file> <privkey>                 Sign file with private key
  verify <file> <sig> <pubkey>          Verify file signature
  list-keys                             List stored keys
  delete-key <name>                     Delete a key pair

Encoding:
  base64-encode <input> [output]        Base64 encode file or text
  base64-decode <input> [output]        Base64 decode file or text

Information:
  list-algos                            List available crypto algorithms
  help                                  Show this help text

Examples:
  termx-crypt encrypt secrets.txt secrets.enc mypassword
  termx-crypt decrypt secrets.enc secrets.txt mypassword
  termx-crypt hash /path/to/file SHA-512
  termx-crypt gen-key RSA 4096
  termx-crypt sign document.txt private.key
  termx-crypt aes-enc "Hello World" mypass
""".trimIndent()
    }
}
