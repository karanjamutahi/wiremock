package functional;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.common.base.Stopwatch;
import org.apache.http.entity.StringEntity;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.wiremock.webhooks.Webhooks;
import testsupport.TestNotifier;
import testsupport.WireMockTestClient;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.http.RequestMethod.POST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.http.entity.ContentType.APPLICATION_JSON;
import static org.apache.http.entity.ContentType.TEXT_PLAIN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;
import static org.wiremock.webhooks.Webhooks.webhook;

public class WebhooksAcceptanceTest {

    @Rule
    public WireMockRule targetServer = new WireMockRule(options().dynamicPort().notifier(new ConsoleNotifier(true)));

    CountDownLatch latch;

    TestNotifier notifier = new TestNotifier();
    WireMockTestClient client;

    @Rule
    public WireMockRule rule = new WireMockRule(
        options()
            .dynamicPort()
            .notifier(notifier)
            .extensions(new Webhooks()));

    @Before
    public void init() {
        targetServer.addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().startsWith("/callback")) {
                latch.countDown();
            }
        });
        reset();
        notifier.reset();
        targetServer.stubFor(any(anyUrl())
            .willReturn(aResponse().withStatus(200)));
        latch = new CountDownLatch(1);
        client = new WireMockTestClient(rule.port());
        WireMock.configureFor(targetServer.port());

        System.out.println("Target server port: " + targetServer.port());
        System.out.println("Under test server port: " + rule.port());
    }

    @Test
    public void firesASingleWebhookWhenRequested() throws Exception {
        rule.stubFor(post(urlPathEqualTo("/something-async"))
            .willReturn(aResponse().withStatus(200))
            .withPostServeAction("webhook", webhook()
                .withMethod(POST)
                .withUrl("http://localhost:" + targetServer.port() + "/callback")
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Multi", "one", "two")
                .withBody("{ \"result\": \"SUCCESS\" }"))
        );

        verify(0, postRequestedFor(anyUrl()));

        client.post("/something-async", new StringEntity("", TEXT_PLAIN));

        waitForRequestToTargetServer();

        targetServer.verify(1, postRequestedFor(urlEqualTo("/callback"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("{ \"result\": \"SUCCESS\" }"))
        );

        List<String> multiHeaderValues = targetServer.findAll(postRequestedFor(urlEqualTo("/callback")))
                .get(0)
                .header("X-Multi").values();
        assertThat(multiHeaderValues, hasItems("one", "two"));

        assertThat(notifier.getInfoMessages(), hasItem(allOf(
            containsString("Webhook POST request to"),
            containsString("/callback returned status"),
            containsString("200")
        )));
    }

    @Test
    public void webhookCanBeConfiguredFromJson() throws Exception {
        latch = new CountDownLatch(2);

        client.postJson("/__admin/mappings", "{\n" +
                "  \"request\": {\n" +
                "    \"urlPath\": \"/hook\",\n" +
                "    \"method\": \"POST\"\n" +
                "  },\n" +
                "  \"response\": {\n" +
                "    \"status\": 204\n" +
                "  },\n" +
                "  \"postServeActions\": [\n" +
                "    {\n" +
                "      \"name\": \"webhook\",\n" +
                "      \"parameters\": {\n" +
                "        \"headers\": {\n" +
                "          \"Content-Type\": \"application/json\"\n" +
                "        },\n" +
                "        \"method\": \"POST\",\n" +
                "        \"body\": \"{ \\\"result\\\": \\\"SUCCESS\\\" }\",\n" +
                "        \"url\" : \"http://localhost:" + targetServer.port() + "/callback1\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"name\": \"webhook\",\n" +
                "      \"parameters\": {\n" +
                "        \"method\": \"POST\",\n" +
                "        \"url\" : \"http://localhost:" + targetServer.port() + "/callback2\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        verify(0, postRequestedFor(anyUrl()));

        client.post("/hook", new StringEntity("", TEXT_PLAIN));

        waitForRequestToTargetServer();

        verify(postRequestedFor(urlPathEqualTo("/callback1")));
        verify(postRequestedFor(urlPathEqualTo("/callback2")));
    }

    @Test
    public void appliesTemplatingToUrlMethodHeadersAndBodyViaDSL() throws Exception {
        rule.stubFor(post(urlPathEqualTo("/templating"))
                .willReturn(ok())
                .withPostServeAction("webhook", webhook()
                        .withMethod("{{jsonPath originalRequest.body '$.method'}}")
                        .withUrl("http://localhost:" + targetServer.port() + "{{{jsonPath originalRequest.body '$.callbackPath'}}}")
                        .withHeader("X-Single", "{{math 1 '+' 2}}")
                        .withHeader("X-Multi", "{{math 3 'x' 2}}", "{{parameters.one}}")
                        .withBody("{{jsonPath originalRequest.body '$.name'}}")
                        .withExtraParameter("one", "param-one-value"))
        );

        verify(0, postRequestedFor(anyUrl()));

        client.postJson("/templating", "{\n" +
                "  \"callbackPath\": \"/callback/123\",\n" +
                "  \"method\": \"POST\",\n" +
                "  \"name\": \"Tom\"\n" +
                "}");

        waitForRequestToTargetServer();

        LoggedRequest request = targetServer.findAll(postRequestedFor(urlEqualTo("/callback/123"))).get(0);

        assertThat(request.header("X-Single").firstValue(), is("3"));
        assertThat(request.header("X-Multi").values(), hasItems("6", "param-one-value"));
        assertThat(request.getBodyAsString(), is("Tom"));
    }

    @Test
    public void appliesTemplatingToUrlMethodHeadersAndBodyViaJSON() throws Exception {
        client.postJson("/__admin/mappings", "{\n" +
                "  \"id\" : \"8a58e190-4a83-4244-a064-265fcca46884\",\n" +
                "  \"request\" : {\n" +
                "    \"urlPath\" : \"/templating\",\n" +
                "    \"method\" : \"POST\"\n" +
                "  },\n" +
                "  \"response\" : {\n" +
                "    \"status\" : 200\n" +
                "  },\n" +
                "  \"uuid\" : \"8a58e190-4a83-4244-a064-265fcca46884\",\n" +
                "  \"postServeActions\" : [{\n" +
                "    \"name\" : \"webhook\",\n" +
                "    \"parameters\" : {\n" +
                "      \"method\" : \"{{jsonPath originalRequest.body '$.method'}}\",\n" +
                "      \"url\" : \"" + "http://localhost:" + targetServer.port() + "{{{jsonPath originalRequest.body '$.callbackPath'}}}\",\n" +
                "      \"headers\" : {\n" +
                "        \"X-Single\" : \"{{math 1 '+' 2}}\",\n" +
                "        \"X-Multi\" : [ \"{{math 3 'x' 2}}\", \"{{parameters.one}}\" ]\n" +
                "      },\n" +
                "      \"body\" : \"{{jsonPath originalRequest.body '$.name'}}\",\n" +
                "      \"one\" : \"param-one-value\"\n" +
                "    }\n" +
                "  }]\n" +
                "}\n");

        verify(0, postRequestedFor(anyUrl()));

        client.postJson("/templating", "{\n" +
                "  \"callbackPath\": \"/callback/123\",\n" +
                "  \"method\": \"POST\",\n" +
                "  \"name\": \"Tom\"\n" +
                "}");

        waitForRequestToTargetServer();

        LoggedRequest request = targetServer.findAll(postRequestedFor(urlEqualTo("/callback/123"))).get(0);

        assertThat(request.header("X-Single").firstValue(), is("3"));
        assertThat(request.header("X-Multi").values(), hasItems("6", "param-one-value"));
        assertThat(request.getBodyAsString(), is("Tom"));
    }

    @Test
    public void addsFixedDelayViaDSL() throws Exception {
        final int DELAY_MILLISECONDS = 1_000;

        rule.stubFor(post(urlPathEqualTo("/delayed"))
                .willReturn(ok())
                .withPostServeAction("webhook", webhook()
                        .withFixedDelay(DELAY_MILLISECONDS)
                        .withMethod(RequestMethod.GET)
                        .withUrl("http://localhost:" + targetServer.port() + "/callback"))
        );

        verify(0, postRequestedFor(anyUrl()));

        client.post("/delayed", new StringEntity("", TEXT_PLAIN));

        Stopwatch stopwatch = Stopwatch.createStarted();
        waitForRequestToTargetServer();
        stopwatch.stop();

        double elapsedMilliseconds = stopwatch.elapsed(MILLISECONDS);
        assertThat(elapsedMilliseconds, closeTo(DELAY_MILLISECONDS, 500.0));

        verify(1, getRequestedFor(urlEqualTo("/callback")));
    }

    @Test
    public void addsRandomDelayViaJSON() throws Exception {
        client.postJson("/__admin/mappings", "{\n" +
                "  \"request\" : {\n" +
                "    \"urlPath\" : \"/delayed\",\n" +
                "    \"method\" : \"POST\"\n" +
                "  },\n" +
                "  \"postServeActions\" : [{\n" +
                "    \"name\" : \"webhook\",\n" +
                "    \"parameters\" : {\n" +
                "      \"method\" : \"GET\",\n" +
                "      \"url\" : \"" + "http://localhost:" + targetServer.port() + "/callback\",\n" +
                "      \"delay\" : {\n" +
                "        \"type\" : \"uniform\",\n" +
                "        \"lower\": 500,\n" +
                "        \"upper\": 1000\n" +
                "      }\n" +
                "    }\n" +
                "  }]\n" +
                "}");

        verify(0, postRequestedFor(anyUrl()));

        client.post("/delayed", new StringEntity("", TEXT_PLAIN));

        Stopwatch stopwatch = Stopwatch.createStarted();
        waitForRequestToTargetServer();
        stopwatch.stop();

        long elapsedMilliseconds = stopwatch.elapsed(MILLISECONDS);
        assertThat(elapsedMilliseconds, greaterThanOrEqualTo(500L));
        assertThat(elapsedMilliseconds, lessThanOrEqualTo(1500L));

        verify(1, getRequestedFor(urlEqualTo("/callback")));
    }

    private void waitForRequestToTargetServer() throws Exception {
        assertTrue("Timed out waiting for target server to receive a request", latch.await(2, SECONDS));
    }

}
