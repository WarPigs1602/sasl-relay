package net.midiandmore.newserv.sasl.relay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class Scram {
    private Scram() {
    }

    static byte[] saltedPassword(String password, byte[] salt, int iterations, String hash)
            throws GeneralSecurityException {
        String algorithm = switch (hash) {
            case "SHA-256" -> "PBKDF2WithHmacSHA256";
            case "SHA-512" -> "PBKDF2WithHmacSHA512";
            default -> "PBKDF2WithHmacSHA1";
        };
        int keyLength = switch (hash) {
            case "SHA-256" -> 256;
            case "SHA-512" -> 512;
            default -> 160;
        };
        return SecretKeyFactory.getInstance(algorithm)
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, keyLength))
                .getEncoded();
    }

    static byte[] hmac(String hash, byte[] key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("Hmac" + hash.replace("-", ""));
        mac.init(new SecretKeySpec(key, mac.getAlgorithm()));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] xor(byte[] left, byte[] right) {
        byte[] result = left.clone();
        for (int index = 0; index < result.length; index++) {
            result[index] ^= right[index];
        }
        return result;
    }

    static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    static boolean equals(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }

    static String unescape(String value) {
        return value.replace("=2C", ",").replace("=3D", "=");
    }
}
