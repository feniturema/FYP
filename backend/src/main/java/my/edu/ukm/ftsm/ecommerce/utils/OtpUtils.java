package my.edu.ukm.ftsm.ecommerce.utils;

import java.security.SecureRandom;

public final class OtpUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OtpUtils() {}

    /** Generates a 6-digit numeric OTP, zero-padded. */
    public static String generateOtp() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
