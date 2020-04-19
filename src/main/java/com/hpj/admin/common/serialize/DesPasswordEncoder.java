package com.hpj.admin.common.serialize;

import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.util.DigestUtils;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * @author huangpeijun
 * @date 2020/3/11
 */
public class DesPasswordEncoder {

    private static final String KEY = "xUHdKxzVCbsgVIwTnc1jtpWn";

    public String encode(CharSequence rawPassword) {
        return DESede(rawPassword.toString());
    }

    public boolean match(CharSequence rawPassword, String encodedPassword) {
        return encodedPassword.equals(DESede(rawPassword.toString()));
    }

    private static byte[] toHex(String key) {
        String digest = DigestUtils.md5DigestAsHex(key.getBytes());
        byte[] bytes = new byte[24];
        System.arraycopy(digest.getBytes(), 0, bytes, 0, 24);
        return bytes;
    }

    public static String DESede(String rawPassword) {
        byte[] hex = toHex(KEY);
        try {
            Cipher cipher = Cipher.getInstance("DESede");
            SecretKeySpec deSede = new SecretKeySpec(hex, "DESede");
            cipher.init(Cipher.ENCRYPT_MODE, deSede);
            return Base64.encodeBase64String(cipher.doFinal(rawPassword.getBytes()));
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException(e);
        }
    }
    public String decode(String rawPassword) {
        Base64 base64 = new Base64();
        byte[] hex = toHex(KEY);
        try {
            Cipher cipher = Cipher.getInstance("DESede");
            SecretKey deSede = new SecretKeySpec(hex, "DESede");
            cipher.init(Cipher.DECRYPT_MODE, deSede);
            return new String(cipher.doFinal(base64.decode(rawPassword)));
        } catch (NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException | BadPaddingException | IllegalBlockSizeException e) {
            throw new RuntimeException(e);
        }
    }
}
