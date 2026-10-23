import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Produces the crypto test vectors with the same JCA calls the Android app uses in Sync.kt, so the
 * TypeScript implementation is checked against them. Run: java GenVectors.java > vectors.json
 */
public class GenVectors {
    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    public static void main(String[] a) throws Exception {
        String[] passwords = { "hunter2", "كلمة سر 123", "pässwörd✓" };
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) salt[i] = (byte) (i * 7 + 3);
        StringBuilder sb = new StringBuilder("{\n  \"salt\": \"" + b64(salt) + "\",\n  \"cases\": [\n");
        for (int p = 0; p < passwords.length; p++) {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(passwords[p].toCharArray(), salt, 120_000, 256)).getEncoded();
            byte[] iv = new byte[12];
            for (int i = 0; i < 12; i++) iv[i] = (byte) (p * 31 + i);
            String plain = "{\"id\":\"n" + p + "\",\"title\":\"عنوان\",\"body\":\"line one\\nسطر ٢ ✓\"}";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] sealed = new byte[12 + ct.length];                         // iv + ciphertext + tag, as Sync.seal does
            System.arraycopy(iv, 0, sealed, 0, 12);
            System.arraycopy(ct, 0, sealed, 12, ct.length);
            byte[] live = MessageDigest.getInstance("SHA-256").digest(concat("scratchy-live-v1".getBytes("UTF-8"), key));
            StringBuilder hex = new StringBuilder();
            for (byte x : live) hex.append(String.format("%02x", x));
            sb.append("    { \"password\": ").append(json(passwords[p]))
              .append(", \"key\": \"").append(b64(key))
              .append("\", \"plain\": ").append(json(plain))
              .append(", \"sealed\": \"").append(b64(sealed))
              .append("\", \"liveId\": \"").append(hex).append("\" }")
              .append(p < passwords.length - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        System.out.print(sb);
    }

    static byte[] concat(byte[] x, byte[] y) { byte[] r = new byte[x.length + y.length]; System.arraycopy(x, 0, r, 0, x.length); System.arraycopy(y, 0, r, x.length, y.length); return r; }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20 || ch > 0x7e) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
