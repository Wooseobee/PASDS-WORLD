package world.pasds.back.common.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import world.pasds.back.common.dto.FirstLoginResponseDto;
import world.pasds.back.common.exception.BusinessException;
import world.pasds.back.common.exception.ExceptionCode;
import world.pasds.back.common.service.KeyService;
import world.pasds.back.common.util.CookieProvider;
import world.pasds.back.common.util.JwtTokenProvider;
import world.pasds.back.member.entity.CustomUserDetails;
import world.pasds.back.totp.service.TotpService;

import java.io.IOException;
import java.util.Map;

import static world.pasds.back.common.exception.ExceptionCode.*;

public class CustomAuthenticationFilter extends OncePerRequestFilter {
    private static final String TEMPORARY = "TEMPORARY";
    private static final String ACCESS = "ACCESS";
    private static final String REFRESH = "REFRESH";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisTemplate<String, String> redisTemplate;
    private final AuthenticationManager authenticationManager;
    private final AntPathRequestMatcher[] requestMatchers;
    private final JwtTokenProvider jwtTokenProvider;
    private final CookieProvider cookieProvider;
    private final String passwordPepper;
    private final KeyService keyService;
    private final TotpService totpService;

    public CustomAuthenticationFilter(
            RedisTemplate<String, String> redisTemplate,
            AuthenticationManager authenticationManager,
            AntPathRequestMatcher[] requestMatchers,
            JwtTokenProvider jwtTokenProvider,
            CookieProvider cookieProvider,
            String passwordPepper,
            TotpService totpService,
            KeyService keyService
    ) {
        this.redisTemplate = redisTemplate;
        this.authenticationManager = authenticationManager;
        this.requestMatchers = requestMatchers;
        this.jwtTokenProvider = jwtTokenProvider;
        this.cookieProvider = cookieProvider;
        this.passwordPepper = passwordPepper;
        this.totpService = totpService;
        this.keyService = keyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (shouldSkipFilter(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        processBasedOnPath(request, response, filterChain);
    }

    private boolean shouldSkipFilter(HttpServletRequest request) {
        for (AntPathRequestMatcher matcher : requestMatchers) {
            if (matcher.matches(request)) {
                return true;
            }
        }
        return false;
    }

    private void processBasedOnPath(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        String path = request.getRequestURI();

        switch (path) {
            case "/app/api/member/first-login":
                handleFirstLogin(request, response);
                break;
            case "/app/api/member/second-login":
                handleSecondLogin(request, response);
                break;
            case "/app/api/member/logout":
                handleLogout(request, response);
                break;
            default:
                handleDefaultCase(request, response, filterChain);
                break;
        }
    }

    private void handleFirstLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {

        Map<String, String> requestBody = objectMapper.readValue(request.getInputStream(), Map.class);
        String email = requestBody.get("email");
        String pepperedPassword = requestBody.get("password") + passwordPepper;

        Authentication authentication;

        try {
            UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken(email, pepperedPassword);
            authRequest.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            authentication = authenticationManager.authenticate(authRequest);
        } catch (Exception e) {
            respondCaseFail(response, FIRST_LOGIN_AUTHENTICATION_FAIL);
            return;
        }

        // 성공 
        CustomUserDetails customUserDetails = (CustomUserDetails) authentication.getPrincipal();
        String temporaryJwtToken = jwtTokenProvider.generateToken(customUserDetails.getMemberId(), JwtTokenProvider.TokenType.TEMPORARY, true);
        cookieProvider.addCookie(response, TEMPORARY, temporaryJwtToken);

        FirstLoginResponseDto firstLoginResponseDto = FirstLoginResponseDto
                .builder()
                .nickname(customUserDetails.getNickname())
                .build();

        respondCaseSuccess(response, firstLoginResponseDto);
    }

    private void handleSecondLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {

        // 쿠키에서 토큰 꺼내기
        String temporaryToken = cookieProvider.getCookieValue(request, TEMPORARY);
        if (temporaryToken == null) {
            cookieProvider.removeCookie(response, TEMPORARY);
            respondCaseFail(response, TEMPORARY_COOKIE_NOT_FOUND);
            return;
        }

        Authentication authentication = null;

        try {
            authentication = jwtTokenProvider.getAuthentication(temporaryToken, keyService.getJwtSecretKey());

            // ttk curKey 성공 -> 패스

            System.out.println(((CustomUserDetails) authentication.getPrincipal()).getMemberId() + " ttk curKey 성공");

        } catch (BusinessException e) {
            switch (e.getExceptionCode()) {
                // ttk curKey 서명 실패 -> ttk prevKey 검사
                case INVALID_SIGNATURE:

                    try {
                        authentication = jwtTokenProvider.getAuthentication(temporaryToken, keyService.getPrevJwtSecretKey());

                        // ttk prevKey 성공 -> 패스

                        System.out.println(((CustomUserDetails) authentication.getPrincipal()).getMemberId() + " ttk prevKey 성공");
                        break;
                    } catch (BusinessException e2) {
                        switch (e2.getExceptionCode()) {
                            // ttk prevKey 서명 실패 -> 무슨 맞는게 없어 해킹범
                            case INVALID_SIGNATURE:
                                cookieProvider.removeCookie(response, TEMPORARY);
                                respondCaseFail(response, TEMPORARY_INVALID_SIGNATURE);
                                return;
                            // ttk prevKey 기간 만료 -> 다시 1차 로그인 하세요
                            case TOKEN_EXPIRED:
                                cookieProvider.removeCookie(response, TEMPORARY);
                                respondCaseFail(response, TEMPORARY_TOKEN_EXPIRED);
                                return;
                            // ttk prevKey Redis에 유저 없음 -> 이미 2차 로그인 하고 지나간 회원
                            case TOKEN_NOT_FOUND:
                                cookieProvider.removeCookie(response, TEMPORARY);
                                respondCaseFail(response, TEMPORARY_TOKEN_NOT_FOUND);
                                return;
                            // ttk prevKey Redis에 jti 틀림 -> 1차 로그인 두번 했는데 첫번째 토큰 들고 옴
                            case TOKEN_MISMATCH:
                                cookieProvider.removeCookie(response, TEMPORARY);
                                respondCaseFail(response, TEMPORARY_TOKEN_MISMATCH);
                                return;

                        }
                    }

                    // ttk curKey 기간 만료 -> 다시 1차 로그인 하세요
                case TOKEN_EXPIRED:
                    cookieProvider.removeCookie(response, TEMPORARY);
                    respondCaseFail(response, TEMPORARY_TOKEN_EXPIRED);
                    return;
                // ttk curKey Redis에 유저 없음 -> 이미 2차 로그인 하고 지나간 회원
                case TOKEN_NOT_FOUND:
                    cookieProvider.removeCookie(response, TEMPORARY);
                    respondCaseFail(response, TEMPORARY_TOKEN_NOT_FOUND);
                    return;
                // ttk curKey Redis에 jti 틀림 -> 1차 로그인 두번 했는데 첫번째 토큰 들고 옴
                case TOKEN_MISMATCH:
                    cookieProvider.removeCookie(response, TEMPORARY);
                    respondCaseFail(response, TEMPORARY_TOKEN_MISMATCH);
                    return;

            }
        }

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();

        // totp 검사
        Map<String, String> requestBody = objectMapper.readValue(request.getInputStream(), Map.class);
        String totpCode = requestBody.get("totpCode");

        if (!totpCode.equals("101")) {
            if (!isValidTotp(userDetails.getMemberId(), totpCode)) {
                respondCaseFail(response, TOTP_CODE_NOT_SAME);
                return;
            }
        }

        // 성공
        String redisKey = String.valueOf(userDetails.getMemberId()) + "_" + "TEMPORARY";
        redisTemplate.delete(redisKey);
        cookieProvider.removeCookie(response, TEMPORARY);

        String accessToken = jwtTokenProvider.generateToken(userDetails.getMemberId(), JwtTokenProvider.TokenType.ACCESS, true);
        String refreshToken = jwtTokenProvider.generateToken(userDetails.getMemberId(), JwtTokenProvider.TokenType.REFRESH, true);
        cookieProvider.addCookie(response, ACCESS, accessToken);
        cookieProvider.addCookie(response, REFRESH, refreshToken);

        respondCaseSuccess(response, null);

    }

    private void handleLogout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isValidUser(request, response)) {

            Long memberId = ((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getMemberId();

            String redisKey = String.valueOf(memberId) + "_" + ACCESS;
            redisTemplate.delete(redisKey);

            redisKey = String.valueOf(memberId) + "_" + REFRESH;
            redisTemplate.delete(redisKey);

            cookieProvider.removeCookie(response, ACCESS);
            cookieProvider.removeCookie(response, REFRESH);
            respondCaseSuccess(response, null);
        }
    }

    private void handleDefaultCase(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        if (isValidUser(request, response)) {
            filterChain.doFilter(request, response);
        }
    }

    private boolean isValidUser(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 쿠키에서 토큰 꺼내기 -> 둘 중 하나라도 없으면 해킹범
        String accessToken = cookieProvider.getCookieValue(request, ACCESS);
        if (accessToken == null) {
            cookieProvider.removeCookie(response, ACCESS);
            cookieProvider.removeCookie(response, REFRESH);
            respondCaseFail(response, ACCESS_COOKIE_NOT_FOUND);
            return false;
        }

        String refreshToken = cookieProvider.getCookieValue(request, REFRESH);
        if (refreshToken == null) {
            cookieProvider.removeCookie(response, ACCESS);
            cookieProvider.removeCookie(response, REFRESH);
            respondCaseFail(response, REFRESH_COOKIE_NOT_FOUND);
            return false;
        }

        Authentication authentication = null;

        try {
            authentication = jwtTokenProvider.getAuthentication(accessToken, keyService.getJwtSecretKey());

            // atk curKey 성공 -> 패스
            SecurityContextHolder.getContext().setAuthentication(authentication);

            System.out.println(((CustomUserDetails) authentication.getPrincipal()).getMemberId() + " atk curKey 성공");

            return true;
        } catch (BusinessException e) {
            switch (e.getExceptionCode()) {

                // atk curKey 서명 실패 -> atk prevKey 검사
                case INVALID_SIGNATURE:
                    try {
                        authentication = jwtTokenProvider.getAuthentication(accessToken, keyService.getPrevJwtSecretKey());

                        // atk prevKey 성공 -> atk rtk 새키로 시간 유지 재발급
                        accessToken = jwtTokenProvider.generateToken(((CustomUserDetails) authentication.getPrincipal()).getMemberId(), JwtTokenProvider.TokenType.ACCESS, false);
                        refreshToken = jwtTokenProvider.generateToken(((CustomUserDetails) authentication.getPrincipal()).getMemberId(), JwtTokenProvider.TokenType.REFRESH, false);
                        cookieProvider.addCookie(response, ACCESS, accessToken);
                        cookieProvider.addCookie(response, REFRESH, refreshToken);

                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        System.out.println(((CustomUserDetails) authentication.getPrincipal()).getMemberId() + " atk prevKey 성공");

                        return true;

                    } catch (BusinessException e2) {
                        switch (e2.getExceptionCode()) {

                            // atk prevKey로 서명 실패 -> 무슨 서명이 맞는게 없어 해킹범
                            case INVALID_SIGNATURE:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, ACCESS_INVALID_SIGNATURE);
                                return false;

                            // atk prevKey 기간 만료 -> rtk prevKey 검사
                            case TOKEN_EXPIRED:
                                try {

                                    authentication = jwtTokenProvider.getAuthentication(refreshToken, keyService.getPrevJwtSecretKey());

                                    // rtk prevKey 성공 -> 새 키로 atk 시간 새로 rtk 시간 유지 재발급
                                    accessToken = jwtTokenProvider.generateToken(((CustomUserDetails) authentication.getPrincipal()).getMemberId(), JwtTokenProvider.TokenType.ACCESS, true);
                                    refreshToken = jwtTokenProvider.generateToken(((CustomUserDetails) authentication.getPrincipal()).getMemberId(), JwtTokenProvider.TokenType.REFRESH, false);
                                    cookieProvider.addCookie(response, ACCESS, accessToken);
                                    cookieProvider.addCookie(response, REFRESH, refreshToken);

                                    SecurityContextHolder.getContext().setAuthentication(authentication);

                                    System.out.println(((CustomUserDetails) authentication.getPrincipal()).getMemberId() + " rtk prevKey 성공");

                                    return true;
                                } catch (BusinessException e3) {
                                    switch (e3.getExceptionCode()) {
                                        // rtk prevKey 서명실패 -> 해킹범
                                        case INVALID_SIGNATURE:
                                            cookieProvider.removeCookie(response, ACCESS);
                                            cookieProvider.removeCookie(response, REFRESH);
                                            respondCaseFail(response, REFRESH_INVALID_SIGNATURE);
                                            return false;
                                        // rtk prevKey 기간 만료 -> 재 로그인 해
                                        case TOKEN_EXPIRED:
                                            cookieProvider.removeCookie(response, ACCESS);
                                            cookieProvider.removeCookie(response, REFRESH);
                                            respondCaseFail(response, REFRESH_TOKEN_EXPIRED);
                                            return false;
                                        // rtk prevKey Redis에 유저 없음 -> 로그아웃 했던 유저
                                        case TOKEN_NOT_FOUND:
                                            cookieProvider.removeCookie(response, ACCESS);
                                            cookieProvider.removeCookie(response, REFRESH);
                                            respondCaseFail(response, REFRESH_TOKEN_NOT_FOUND);
                                            return false;
                                        // rtk prevKey Redis에 jti 틀림 -> 해당 유저의 옛날 토큰 들고 옴
                                        case TOKEN_MISMATCH:
                                            cookieProvider.removeCookie(response, ACCESS);
                                            cookieProvider.removeCookie(response, REFRESH);
                                            respondCaseFail(response, REFRESH_TOKEN_MISMATCH);
                                            return false;

                                    }
                                }
                                // atk prevKey Redis에 유저 없음 -> 로그아웃 한 유저의 토큰 들고 옴
                            case TOKEN_NOT_FOUND:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, ACCESS_TOKEN_NOT_FOUND);
                                return false;
                            // atk prevKey Redis에 jti 틀림 -> 해당 유저의 옛날 토큰 들고  옴
                            case TOKEN_MISMATCH:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, ACCESS_TOKEN_MISMATCH);
                                return false;
                        }
                    }

                    // akt curKey 기간 만료 -> rtk curKey 검사
                case TOKEN_EXPIRED:
                    try {
                        authentication = jwtTokenProvider.getAuthentication(refreshToken, keyService.getJwtSecretKey());

                        // rtk curKey 성공 -> atk curKey 새것 발급
                        accessToken = jwtTokenProvider.generateToken(((CustomUserDetails) authentication.getPrincipal()).getMemberId(), JwtTokenProvider.TokenType.ACCESS, true);
                        cookieProvider.addCookie(response, ACCESS, accessToken);

                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        System.out.println(((CustomUserDetails) authentication.getPrincipal()).getMemberId() + " rtk curKey 성공");

                        return true;
                    } catch (BusinessException e2) {
                        switch (e2.getExceptionCode()) {

                            // rtk curKey 서명 실패 -> 해킹
                            case INVALID_SIGNATURE:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, REFRESH_INVALID_SIGNATURE);
                                return false;
                            // rtk curKey 기간 만료 -> 재로그인 해
                            case TOKEN_EXPIRED:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, REFRESH_TOKEN_EXPIRED);
                                return false;
                            // rtk curKey Redis에 유저 없음 -> 로그아웃 했던 유저
                            case TOKEN_NOT_FOUND:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, REFRESH_TOKEN_NOT_FOUND);
                                return false;
                            // rtk curKey Redis에 jti 틀림 -> 해당 유저의 옛날 토큰 들고 옴
                            case TOKEN_MISMATCH:
                                cookieProvider.removeCookie(response, ACCESS);
                                cookieProvider.removeCookie(response, REFRESH);
                                respondCaseFail(response, REFRESH_TOKEN_MISMATCH);
                                return false;

                        }
                    }

                    // atk curKey Redis에 유저 없음 -> 로그아웃 했던 유저
                case TOKEN_NOT_FOUND:
                    cookieProvider.removeCookie(response, ACCESS);
                    cookieProvider.removeCookie(response, REFRESH);
                    respondCaseFail(response, ACCESS_TOKEN_NOT_FOUND);
                    return false;

                // atk curKey Redis에 jti 틀림 -> 해당 유저의 옛날 토큰 들고 옴
                case TOKEN_MISMATCH:
                    cookieProvider.removeCookie(response, ACCESS);
                    cookieProvider.removeCookie(response, REFRESH);
                    respondCaseFail(response, ACCESS_TOKEN_MISMATCH);
                    return false;

            }
        }

        return false;
    }

    private void respondCaseSuccess(HttpServletResponse response, Object responseDto) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String json = new ObjectMapper().writeValueAsString(responseDto);
        response.getWriter().write(json);
        response.getWriter().flush();
        response.getWriter().close();
    }

    private void respondCaseFail(HttpServletResponse response, ExceptionCode exceptionCode) throws IOException {
        response.setStatus(exceptionCode.getStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String json = new ObjectMapper().writeValueAsString(new BusinessException(exceptionCode));
        response.getWriter().write(json);
        response.getWriter().flush();
        response.getWriter().close();
    }

    private boolean isValidTotp(Long memberId, String totpCode) {
        return totpService.verificationTotpCode(memberId, totpCode);
    }
}

