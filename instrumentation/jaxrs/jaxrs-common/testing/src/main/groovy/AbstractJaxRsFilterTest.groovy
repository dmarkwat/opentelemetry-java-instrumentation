/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import io.opentelemetry.instrumentation.api.semconv.http.internal.HttpAttributes
import io.opentelemetry.instrumentation.test.AgentInstrumentationSpecification
import io.opentelemetry.semconv.SemanticAttributes
import org.junit.jupiter.api.Assumptions
import spock.lang.Unroll

import static io.opentelemetry.api.trace.SpanKind.INTERNAL
import static io.opentelemetry.api.trace.SpanKind.SERVER
import static io.opentelemetry.api.trace.StatusCode.UNSET

@Unroll
abstract class AbstractJaxRsFilterTest extends AgentInstrumentationSpecification {

  abstract makeRequest(String url)

  Tuple2<String, String> runRequest(String resource) {
    if (runsOnServer()) {
      return makeRequest(resource)
    }
    // start a trace because the test doesn't go through any servlet or other instrumentation.
    return runWithHttpServerSpan {
      makeRequest(resource)
    }
  }

  boolean testAbortPrematch() {
    true
  }

  boolean runsOnServer() {
    false
  }

  String defaultServerRoute() {
    null
  }

  abstract void setAbortStatus(boolean abortNormal, boolean abortPrematch)

  def "test #resource, #abortNormal, #abortPrematch"() {
    Assumptions.assumeTrue(!abortPrematch || testAbortPrematch())

    given:
    setAbortStatus(abortNormal, abortPrematch)
    def abort = abortNormal || abortPrematch

    when:

    def (responseText, responseStatus) = runRequest(resource)

    then:
    responseText == expectedResponse

    if (abort) {
      responseStatus == 401 // Response.Status.UNAUTHORIZED.statusCode
    } else {
      responseStatus == 200 // Response.Status.OK.statusCode
    }

    def serverRoute = route ?: defaultServerRoute()
    def method = runsOnServer() ? "POST" : "GET"
    def expectedServerSpanName = serverRoute == null ? method : method + " " + serverRoute

    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name expectedServerSpanName
          kind SERVER
          if (runsOnServer() && abortNormal) {
            status UNSET
          }
        }
        span(1) {
          childOf span(0)
          name controllerName
          if (abortPrematch) {
            attributes {
              "$SemanticAttributes.CODE_NAMESPACE" "JaxRsFilterTest\$PrematchRequestFilter"
              "$SemanticAttributes.CODE_FUNCTION" "filter"
            }
          } else {
            attributes {
              "$SemanticAttributes.CODE_NAMESPACE" ~/Resource[$]Test*/
              "$SemanticAttributes.CODE_FUNCTION" "hello"
            }
          }
        }
      }
    }

    where:
    resource           | abortNormal | abortPrematch | route                 | controllerName                 | expectedResponse
    "/test/hello/bob"  | false       | false         | "/test/hello/{name}"  | "Test1.hello"                  | "Test1 bob!"
    "/test2/hello/bob" | false       | false         | "/test2/hello/{name}" | "Test2.hello"                  | "Test2 bob!"
    "/test3/hi/bob"    | false       | false         | "/test3/hi/{name}"    | "Test3.hello"                  | "Test3 bob!"

    // Resteasy and Jersey give different resource class names for just the below case
    // Resteasy returns "SubResource.class"
    // Jersey returns "Test1.class
    // "/test/hello/bob"  | true        | false         | "/test/hello/{name}"  | "Test1.hello"                  | "Aborted"

    "/test2/hello/bob" | true        | false         | "/test2/hello/{name}" | "Test2.hello"                  | "Aborted"
    "/test3/hi/bob"    | true        | false         | "/test3/hi/{name}"    | "Test3.hello"                  | "Aborted"
    "/test/hello/bob"  | false       | true          | null                  | "PrematchRequestFilter.filter" | "Aborted Prematch"
    "/test2/hello/bob" | false       | true          | null                  | "PrematchRequestFilter.filter" | "Aborted Prematch"
    "/test3/hi/bob"    | false       | true          | null                  | "PrematchRequestFilter.filter" | "Aborted Prematch"
  }

  def "test nested call"() {
    given:
    setAbortStatus(false, false)

    when:
    def (responseText, responseStatus) = runRequest(resource)

    then:
    responseStatus == 200 // Response.Status.OK.statusCode
    responseText == expectedResponse

    def method = runsOnServer() ? "POST" : "GET"

    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name method + " " + route
          kind SERVER
          if (!runsOnServer()) {
            attributes {
              "$SemanticAttributes.HTTP_REQUEST_METHOD" method
              "$SemanticAttributes.HTTP_ROUTE" route
              "$HttpAttributes.ERROR_TYPE" "_OTHER"
            }
          }
        }
        span(1) {
          childOf span(0)
          name controller1Name
          kind INTERNAL
          attributes {
            "$SemanticAttributes.CODE_NAMESPACE" ~/Resource[$]Test*/
            "$SemanticAttributes.CODE_FUNCTION" "nested"
          }
        }
      }
    }

    where:
    resource        | route           | controller1Name | expectedResponse
    "/test3/nested" | "/test3/nested" | "Test3.nested"  | "Test3 nested!"
  }
}
