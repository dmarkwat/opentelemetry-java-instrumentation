/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.lettuce.v5_1;

import io.opentelemetry.instrumentation.api.semconv.network.NetworkAttributesGetter;
import io.opentelemetry.instrumentation.lettuce.v5_1.OpenTelemetryTracing.OpenTelemetryEndpoint;
import java.net.InetSocketAddress;
import javax.annotation.Nullable;

final class LettuceNetworkAttributesGetter
    implements NetworkAttributesGetter<OpenTelemetryEndpoint, Void> {

  @Nullable
  @Override
  public InetSocketAddress getNetworkPeerInetSocketAddress(
      OpenTelemetryEndpoint openTelemetryEndpoint, @Nullable Void unused) {
    return openTelemetryEndpoint.address;
  }
}
