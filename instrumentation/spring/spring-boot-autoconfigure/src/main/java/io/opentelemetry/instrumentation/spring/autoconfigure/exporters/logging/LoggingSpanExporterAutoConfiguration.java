/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.autoconfigure.exporters.logging;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.exporters.internal.ExporterConfigEvaluator;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Configures {@link LoggingSpanExporter} bean for tracing. */
@Configuration
@EnableConfigurationProperties(LoggingExporterProperties.class)
@AutoConfigureBefore(OpenTelemetryAutoConfiguration.class)
@Conditional(LoggingSpanExporterAutoConfiguration.CustomCondition.class)
@ConditionalOnClass(LoggingSpanExporter.class)
public class LoggingSpanExporterAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public LoggingSpanExporter otelLoggingSpanExporter() {
    return LoggingSpanExporter.create();
  }

  static final class CustomCondition implements Condition {
    @Override
    public boolean matches(
        org.springframework.context.annotation.ConditionContext context,
        org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      return ExporterConfigEvaluator.isExporterEnabled(
          context.getEnvironment(),
          "otel.exporter.logging.enabled",
          "otel.exporter.logging.traces.enabled",
          "otel.traces.exporter",
          "logging",
          false);
    }
  }
}
