package com.payrail.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured JSON Logback layout for production log aggregation (ELK, Datadog, CloudWatch).
 * Output contains ISO-8601 timestamps, log level, thread, logger name, MDC correlation ID,
 * and safe exception information.
 */
public class JsonLoggingLayout extends LayoutBase<ILoggingEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        logEntry.put("level", event.getLevel().toString());
        logEntry.put("thread", event.getThreadName());
        logEntry.put("logger", event.getLoggerName());
        logEntry.put("message", event.getFormattedMessage());

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && mdc.containsKey("correlationId")) {
            logEntry.put("correlationId", mdc.get("correlationId"));
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            logEntry.put("exceptionClass", throwable.getClassName());
            logEntry.put("exceptionMessage", throwable.getMessage());
        }

        try {
            return objectMapper.writeValueAsString(logEntry) + "\n";
        } catch (Exception e) {
            return "{\"level\":\"" + event.getLevel() + "\",\"message\":\"" + event.getFormattedMessage() + "\"}\n";
        }
    }
}
