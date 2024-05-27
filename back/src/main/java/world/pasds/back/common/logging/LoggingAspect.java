package world.pasds.back.common.logging;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
@RequiredArgsConstructor
public class LoggingAspect {

    private static final Logger logger = LoggerFactory.getLogger(LoggingAspect.class);
    private final ObjectMapper objectMapper;

    @Pointcut("within(*..*Controller)")
    public void controller() {}

    @Around("controller()")
    public Object requestLogging(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;

        Map<String, String> headers = request != null ? getHeadersInfo(request) : new HashMap<>();
        LogInfo logInfo = LogInfo.builder()
                .url(request != null ? request.getRequestURL().toString() : "N/A")
                .name(joinPoint.getSignature().getName())
                .className(joinPoint.getSignature().getDeclaringTypeName())
                .method(request != null ? request.getMethod() : "N/A")
                .headers(headers)
                .build();

        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long endTime = System.currentTimeMillis();
            logInfo.setResponseTime(endTime - startTime);
            String logMessage = objectMapper.writeValueAsString(Map.of("logInfo", logInfo));
            logger.info(logMessage);
            return result;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logInfo.setException(sw.toString());
            String logMessage = objectMapper.writeValueAsString(Map.of("logInfo", logInfo));
            logger.error(logMessage);
            throw e;
        }
    }

    private Map<String, String> getHeadersInfo(HttpServletRequest request) {
        Map<String, String> map = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String key = headerNames.nextElement();
            String value = request.getHeader(key);
            map.put(key, value);
        }
        return map;
    }
}
