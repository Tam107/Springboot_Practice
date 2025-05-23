package com.devteria.identityservice.service;

import com.devteria.identityservice.dto.request.AuthenticationRequest;
import com.devteria.identityservice.dto.request.IntrospectRequest;
import com.devteria.identityservice.dto.request.LogoutRequest;
import com.devteria.identityservice.dto.response.AuthenticationResponse;
import com.devteria.identityservice.dto.response.IntrospectResponse;
import com.devteria.identityservice.entity.InvalidatedToken;
import com.devteria.identityservice.entity.User;
import com.devteria.identityservice.exception.AppException;
import com.devteria.identityservice.exception.ErrorCode;
import com.devteria.identityservice.repository.InvalidatedTokenRepository;
import com.devteria.identityservice.repository.UserRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.StringJoiner;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTHENTICATION-SERVICE")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticationService {
    UserRepository userRepository;
    private final InvalidatedTokenRepository invalidatedTokenRepository;

    @NonFinal
    @Value("${jwt.signerKey}")
    protected String SIGNER_KEY;



    /**
     * Validates and introspects a given JWT token.
     * @param request The introspection request containing the token.
     * @return IntrospectResponse indicating whether the token is valid and not expired.
     * @throws JOSEException If there is an issue with token verification.
     * @throws ParseException If the token cannot be parsed.
     */
    public IntrospectResponse introspect(IntrospectRequest request)
            throws JOSEException, ParseException {
        var token = request.getToken();

//        JWSVerifier verifier = new MACVerifier(SIGNER_KEY.getBytes());
//
//        SignedJWT signedJWT = SignedJWT.parse(token);
//
//        Date expiryTime = signedJWT.getJWTClaimsSet().getExpirationTime();
//
//        var verified = signedJWT.verify(verifier);

        boolean isValid = true;
        try{
            verifyToken(token);
        }catch (AppException e){
            isValid = false;
        }

        return IntrospectResponse.builder()
                .valid(isValid)
                .build();
    }



    public AuthenticationResponse authenticate(AuthenticationRequest request){
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
        var user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        boolean authenticated = passwordEncoder.matches(request.getPassword(),
                user.getPassword());

        if (!authenticated)
            throw new AppException(ErrorCode.UNAUTHENTICATED);

        var token = generateToken(user);

        return AuthenticationResponse.builder()
                .token(token)
                .authenticated(true)
                .build();
    }


    public void logout(LogoutRequest request) throws ParseException, JOSEException {
        var signedToken = verifyToken(request.getToken());

        String jwtId = signedToken.getJWTClaimsSet().getJWTID();

        Date expiryTime = signedToken.getJWTClaimsSet().getExpirationTime();

        InvalidatedToken invalidatedToken = InvalidatedToken.builder()
                .id(jwtId)
                .expiryTime(expiryTime)
                .build();

        invalidatedTokenRepository.save(invalidatedToken);

    }

    private SignedJWT verifyToken(String token) throws JOSEException, ParseException{
        JWSVerifier verifier = new MACVerifier(SIGNER_KEY.getBytes());

        SignedJWT signedJWT = SignedJWT.parse(token);

        Date expiryTime = signedJWT.getJWTClaimsSet().getExpirationTime();

        var verified = signedJWT.verify(verifier);

        if (!(verified && expiryTime.after(new Date()))){
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        //find the existing token id in the repository
        if (invalidatedTokenRepository.existsById(signedJWT.getJWTClaimsSet().getJWTID())){
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        return signedJWT;
    }


    /**
     * Generates a JWT token for a given user.
     * @param user The user for whom the token is generated.
     * @return A signed JWT token as a String.
     */
    private String generateToken(User user) {
        JWSHeader header = new JWSHeader(JWSAlgorithm.HS512);

        JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder()
                .subject(user.getUsername())
                .issuer("devteria.com")
                .issueTime(new Date())
                .expirationTime(new Date(
                        Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli()
                ))

                // get token id for logout then create entity to keep token logout
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", buildScope(user))
                .build();

        Payload payload = new Payload(jwtClaimsSet.toJSONObject());
        JWSObject jwsObject = new JWSObject(header, payload);
        try {
            jwsObject.sign(new MACSigner(SIGNER_KEY.getBytes()));
            return jwsObject.serialize();
        } catch (JOSEException e) {
            log.error("Cannot create token", e);
            throw new RuntimeException(e);
        }
    }




    /**
     * Builds the scope string for a user based on their roles and permissions.
     * @param user The user whose scope is being built.
     * @return A space-separated string of roles and permissions.
     */
    private String buildScope(User user){
        StringJoiner stringJoiner = new StringJoiner(" ");

         if (!CollectionUtils.isEmpty(user.getRoles()))
            user.getRoles().forEach(role -> {
                stringJoiner.add("ROLE_" + role.getName());
                if (!CollectionUtils.isEmpty(role.getPermissions()))
                    role.getPermissions()
                            .forEach(permission -> stringJoiner.add(permission.getName()));
            });
        return stringJoiner.toString();
    }

}
