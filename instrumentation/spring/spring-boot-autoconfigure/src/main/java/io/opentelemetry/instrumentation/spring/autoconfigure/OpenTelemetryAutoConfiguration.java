/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.instrumentation.spring.autoconfigure.exporters.otlp.OtlpLoggerExporterAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.exporters.otlp.OtlpMetricExporterAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.exporters.otlp.OtlpSpanExporterAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.internal.MapConverter;
import io.opentelemetry.instrumentation.spring.autoconfigure.internal.OpenTelemetrySupplier;
import io.opentelemetry.instrumentation.spring.autoconfigure.resources.OtelResourceAutoConfiguration;
import io.opentelemetry.instrumentation.spring.autoconfigure.resources.SpringResourceConfigProperties;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReaderBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Create {@link io.opentelemetry.api.OpenTelemetry} bean if bean is missing.
 *
 * <p>Adds span exporter beans to the active tracer provider.
 *
 * <p>Updates the sampler probability for the configured {@link TracerProvider}.
 */
@Configuration
@EnableConfigurationProperties({MetricExportProperties.class, SamplerProperties.class})
public class OpenTelemetryAutoConfiguration {

  public OpenTelemetryAutoConfiguration() {}

  @Configuration
  @ConditionalOnMissingBean(OpenTelemetry.class)
  @ConditionalOnProperty(name = "otel.sdk.disabled", havingValue = "false", matchIfMissing = true)
  public static class OpenTelemetrySdkConfig {

    @Bean
    @ConfigurationPropertiesBinding
    @ConditionalOnBean({
      OtelResourceAutoConfiguration.class,
      OtlpLoggerExporterAutoConfiguration.class,
      OtlpSpanExporterAutoConfiguration.class,
      OtlpMetricExporterAutoConfiguration.class
    })
    public MapConverter mapConverter() {
      // needed for otlp exporter headers and OtelResourceProperties
      return new MapConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SdkTracerProvider sdkTracerProvider(
        SamplerProperties samplerProperties,
        ObjectProvider<List<SpanExporter>> spanExportersProvider,
        Resource otelResource) {
      SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder();

      spanExportersProvider.getIfAvailable(Collections::emptyList).stream()
          .map(spanExporter -> BatchSpanProcessor.builder(spanExporter).build())
          .forEach(tracerProviderBuilder::addSpanProcessor);

      return tracerProviderBuilder
          .setResource(otelResource)
          .setSampler(Sampler.traceIdRatioBased(samplerProperties.getProbability()))
          .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SdkLoggerProvider sdkLoggerProvider(
        ObjectProvider<List<LogRecordExporter>> loggerExportersProvider, Resource otelResource) {

      SdkLoggerProviderBuilder loggerProviderBuilder = SdkLoggerProvider.builder();
      loggerProviderBuilder.setResource(otelResource);

      loggerExportersProvider
          .getIfAvailable(Collections::emptyList)
          .forEach(
              loggerExporter ->
                  loggerProviderBuilder.addLogRecordProcessor(
                      BatchLogRecordProcessor.builder(loggerExporter).build()));

      return loggerProviderBuilder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SdkMeterProvider sdkMeterProvider(
        MetricExportProperties properties,
        ObjectProvider<List<MetricExporter>> metricExportersProvider,
        Resource otelResource) {

      SdkMeterProviderBuilder meterProviderBuilder = SdkMeterProvider.builder();

      metricExportersProvider.getIfAvailable(Collections::emptyList).stream()
          .map(metricExporter -> createPeriodicMetricReader(properties, metricExporter))
          .forEach(meterProviderBuilder::registerMetricReader);

      return meterProviderBuilder.setResource(otelResource).build();
    }

    private static PeriodicMetricReader createPeriodicMetricReader(
        MetricExportProperties properties, MetricExporter metricExporter) {
      PeriodicMetricReaderBuilder metricReaderBuilder =
          PeriodicMetricReader.builder(metricExporter);
      if (properties.getInterval() != null) {
        metricReaderBuilder.setInterval(properties.getInterval());
      }
      return metricReaderBuilder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public Resource otelResource(
        Environment env, ObjectProvider<List<ResourceProvider>> resourceProviders) {
      ConfigProperties config = new SpringResourceConfigProperties(env, new SpelExpressionParser());
      Resource resource = Resource.getDefault();
      for (ResourceProvider resourceProvider :
          resourceProviders.getIfAvailable(Collections::emptyList)) {
        resource = resource.merge(resourceProvider.createResource(config));
      }
      return resource;
    }

    @Bean
    // If you change the bean name, also change it in the OpenTelemetryJdbcDriverAutoConfiguration
    // class
    public OpenTelemetry openTelemetry(
        ObjectProvider<ContextPropagators> propagatorsProvider,
        SdkTracerProvider tracerProvider,
        SdkMeterProvider meterProvider,
        SdkLoggerProvider loggerProvider,
        ObjectProvider<List<OpenTelemetryInjector>> openTelemetryConsumerProvider) {

      ContextPropagators propagators = propagatorsProvider.getIfAvailable(ContextPropagators::noop);

      OpenTelemetrySdk openTelemetry =
          OpenTelemetrySdk.builder()
              .setTracerProvider(tracerProvider)
              .setMeterProvider(meterProvider)
              .setLoggerProvider(loggerProvider)
              .setPropagators(propagators)
              .build();

      List<OpenTelemetryInjector> openTelemetryInjectors =
          openTelemetryConsumerProvider.getIfAvailable(() -> Collections.emptyList());
      openTelemetryInjectors.forEach(consumer -> consumer.accept(openTelemetry));

      return openTelemetry;
    }
  }

  @Configuration
  @ConditionalOnMissingBean(OpenTelemetry.class)
  @ConditionalOnProperty(name = "otel.sdk.disabled", havingValue = "true")
  public static class DisabledOpenTelemetrySdkConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
      return OpenTelemetry.noop();
    }
  }

  @Bean
  // we declared this bean as an infrastructure bean to avoid warning from BeanPostProcessorChecker
  // when it is injected into a BeanPostProcessor
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  public static OpenTelemetrySupplier openTelemetrySupplier(BeanFactory beanFactory) {
    return () -> beanFactory.getBean(OpenTelemetry.class);
  }
}
