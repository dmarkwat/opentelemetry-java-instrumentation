/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.rabbitmq;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.api.semconv.network.internal.NetworkAttributes;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.semconv.SemanticAttributes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

class ReactorRabbitMqTest extends AbstractRabbitMqTest {
  @RegisterExtension
  private static final InstrumentationExtension testing = AgentInstrumentationExtension.create();

  @Test
  void testShouldNotFailDeclaringExchange() {
    Sender sender =
        RabbitFlux.createSender(new SenderOptions().connectionFactory(connectionFactory));

    try {
      sender.declareExchange(ExchangeSpecification.exchange("testExchange")).block();
    } catch (RuntimeException e) {
      Assertions.fail("Should not fail declaring exchange", e);
    }

    testing.waitAndAssertTraces(
        trace ->
            trace
                .hasSize(1)
                .hasSpansSatisfyingExactly(
                    span -> {
                      span.hasName("exchange.declare")
                          .hasKind(SpanKind.CLIENT)
                          .hasAttribute(SemanticAttributes.MESSAGING_SYSTEM, "rabbitmq")
                          .hasAttribute(
                              AttributeKey.stringKey("rabbitmq.command"), "exchange.declare")
                          .hasAttributesSatisfying(
                              attributes ->
                                  assertThat(attributes)
                                      .satisfies(
                                          attrs -> {
                                            String peerAddr =
                                                attrs.get(NetworkAttributes.NETWORK_PEER_ADDRESS);
                                            assertThat(peerAddr)
                                                .isIn("127.0.0.1", "0:0:0:0:0:0:0:1", null);

                                            String networkType =
                                                attrs.get(SemanticAttributes.NETWORK_TYPE);
                                            assertThat(networkType).isIn("ipv4", "ipv6", null);

                                            assertNotNull(
                                                attrs.get(NetworkAttributes.NETWORK_PEER_PORT));
                                          }));
                    }));
  }
}
