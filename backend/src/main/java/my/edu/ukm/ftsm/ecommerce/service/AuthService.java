package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.AuthDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.model.Role;
import my.edu.ukm.ftsm.ecommerce.model.User;
import my.edu.ukm.ftsm.ecommerce.repository.UserRepository;
import my.edu.ukm.ftsm.ecommerce.security.JwtUtils;
import my.edu.ukm.ftsm.ecommerce.security.UkmEmailValidator;
import my.edu.ukm.ftsm.ecommerce.utils.OtpUtils;
import my.edu.ukm.ftsm.ecommerce.utils.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class AuthService {

    private static final Duration OTP_TTL = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final UkmEmailValidator emailValidator;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final EmailService emailService;
    private final StringRedisTemplate redis;

    public AuthService(UserRepository userRepository, UkmEmailValidator emailValidator,
                       PasswordEncoder passwordEncoder, JwtUtils jwtUtils,
                       EmailService emailService, StringRedisTemplate redis) {
        this.userRepository = userRepository;
        this.emailValidator = emailValidator;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.emailService = emailService;
        this.redis = redis;
    }

    @Transactional
    public void register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        emailValidator.assertValid(email); // throws UnauthorizedDomainException

        userRepository.findByEmail(email).ifPresent(u -> {
            if (u.isEmailVerified()) {
                throw new BusinessException("An account with this email already exists.");
            }
        });

        User user = userRepository.findByEmail(email).orElseGet(() -> User.builder()
                .email(email)
                .role(Role.STUDENT)
                .emailVerified(false)
                .build());
        user.setName(req.name());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        userRepository.save(user);

        issueOtp(email);
    }

    public void issueOtp(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        String code = OtpUtils.generateOtp();
        redis.opsForValue().set(RedisKeys.otp(normalizedEmail), code, OTP_TTL);
        emailService.sendOtp(normalizedEmail, code);
    }

    public void resendOtp(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        emailValidator.assertValid(normalizedEmail);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("No registration found for this email."));
        if (user.isEmailVerified()) {
            throw new BusinessException("This email is already verified.");
        }
        issueOtp(normalizedEmail);
    }

    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest req) {
        String email = req.email().trim().toLowerCase();
        String key = RedisKeys.otp(email);
        String expected = redis.opsForValue().get(key);
        if (expected == null) {
            throw new BusinessException("OTP expired or not found. Please request a new code.");
        }
        if (!expected.equals(req.code())) {
            throw new BusinessException("Invalid verification code.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("No registration found for this email."));
        user.setEmailVerified(true);
        userRepository.save(user);
        redis.delete(key);

        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!user.isEmailVerified()) {
            throw new BusinessException("Email not verified. Please verify the OTP sent to your email.");
        }
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtUtils.generateToken(user);
        return new AuthResponse(token, user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }
}
