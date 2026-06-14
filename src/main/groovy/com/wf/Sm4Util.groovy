package com.wf

import org.bouncycastle.jce.provider.BouncyCastleProvider

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.Security
import java.security.SecureRandom

class Sm4Util {

    static final String MODE_HEX = 'hex'
    static final String MODE_BASE64 = 'base64'

    private static final int BLOCK_SIZE = 16
    private static final String ECB_ALGORITHM = 'SM4/ECB/PKCS7Padding'
    private static final String CBC_ALGORITHM = 'SM4/CBC/PKCS7Padding'

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider())
        }
    }

    // Hex 模式密钥：32 位 hex（SM4Utils / ByteUtils.fromHexString）
    static byte[] parseHexKey(String keyText) {
        String trimmed = keyText?.trim()
        if (!trimmed || !(trimmed ==~ /(?i)[0-9a-f]{32}/)) {
            throw new IllegalArgumentException('Hex 模式 Key 必须是 32 位十六进制字符串')
        }
        return hexToBytes(trimmed)
    }

    // Base64 模式密钥：UTF-8 字节（Hutool SmUtil.sm4(key.getBytes())）
    static byte[] parseUtf8Key(String keyText) {
        String trimmed = keyText?.trim()
        if (!trimmed) {
            throw new IllegalArgumentException('SM4 key 不能为空')
        }
        byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8)
        if (bytes.length != 16) {
            throw new IllegalArgumentException('Base64 模式 Key 必须是 16 字节 UTF-8 字符串')
        }
        return bytes
    }

    // SM4Utils：CBC 加密，随机 IV 前置，返回 Hex
    static String encryptHexEcb(String plainText, String hexKey) {
        byte[] iv = new byte[BLOCK_SIZE]
        new SecureRandom().nextBytes(iv)
        byte[] cipher = cbcCrypt(plainText.getBytes(StandardCharsets.UTF_8), parseHexKey(hexKey), Cipher.ENCRYPT_MODE, iv)
        byte[] output = new byte[iv.length + cipher.length]
        System.arraycopy(iv, 0, output, 0, iv.length)
        System.arraycopy(cipher, 0, output, iv.length, cipher.length)
        return bytesToHex(output)
    }

    // SM4Utils：读取前置 IV 并 CBC 解密 Hex 密文
    static String decryptHexEcb(String cipherHex, String hexKey) {
        byte[] cipherWithIv = hexToBytes(cipherHex.replaceAll(/\s+/, ''))
        if (cipherWithIv.length <= BLOCK_SIZE || cipherWithIv.length % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException('Hex 密文长度无效')
        }
        byte[] iv = Arrays.copyOfRange(cipherWithIv, 0, BLOCK_SIZE)
        byte[] cipher = Arrays.copyOfRange(cipherWithIv, BLOCK_SIZE, cipherWithIv.length)
        byte[] plain = cbcCrypt(cipher, parseHexKey(hexKey), Cipher.DECRYPT_MODE, iv)
        return new String(plain, StandardCharsets.UTF_8)
    }

    // Hutool：ECB 加密，返回 Base64
    static String encryptBase64Ecb(String plainText, String utf8Key) {
        byte[] encrypted = ecbCrypt(plainText.getBytes(StandardCharsets.UTF_8), parseUtf8Key(utf8Key), Cipher.ENCRYPT_MODE)
        return Base64.encoder.encodeToString(encrypted)
    }

    // Hutool：ECB 解密 Base64 密文
    static String decryptBase64Ecb(String cipherBase64, String utf8Key) {
        byte[] cipherBytes = Base64.decoder.decode(cipherBase64.replaceAll(/\s+/, ''))
        byte[] plain = ecbCrypt(cipherBytes, parseUtf8Key(utf8Key), Cipher.DECRYPT_MODE)
        return new String(plain, StandardCharsets.UTF_8)
    }

    private static byte[] ecbCrypt(byte[] input, byte[] key, int mode) {
        if (key.length != 16) {
            throw new IllegalArgumentException('SM4 密钥长度必须是 16 字节')
        }
        Cipher cipher = Cipher.getInstance(ECB_ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, new SecretKeySpec(key, 'SM4'))
        return cipher.doFinal(input)
    }

    private static byte[] cbcCrypt(byte[] input, byte[] key, int mode, byte[] iv) {
        if (key.length != BLOCK_SIZE) {
            throw new IllegalArgumentException('SM4 密钥长度必须是 16 字节')
        }
        if (iv.length != BLOCK_SIZE) {
            throw new IllegalArgumentException('SM4 IV 长度必须是 16 字节')
        }
        Cipher cipher = Cipher.getInstance(CBC_ALGORITHM, BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, new SecretKeySpec(key, 'SM4'), new IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException('十六进制长度无效')
        }
        byte[] bytes = new byte[hex.length().intdiv(2)]
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16)
        }
        return bytes
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2)
        bytes.each { byte b ->
            sb.append(String.format('%02x', b & 0xff))
        }
        return sb.toString()
    }
}
