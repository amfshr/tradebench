package dev.amfshr.tradebench.ig.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.amfshr.tradebench.ig.IgCredentials;
import dev.amfshr.tradebench.ig.IgEnvironment;
import dev.amfshr.tradebench.ig.http.HttpCall;
import dev.amfshr.tradebench.ig.http.HttpResult;
import dev.amfshr.tradebench.ig.http.JdkHttpTransport;

/**
 * E1-T17 (B28): capture the real wire for the two responses the fixtures only knew from the docs — the login (its
 * {@code timezoneOffset}) and {@code GET /operations/application} (the pacer's discovery). One login, one read. The
 * raw bodies land in build/wire-capture/ (gitignored, never committed): they hold the account's ids and the API key,
 * so this prints only status codes, and the committed fixtures are made synthetic from them. Gated like the smoke:
 * IG_SMOKE=1 and IG_DEMO_* set.
 */
@Tag("ig-demo")
class LoginAndApplicationProbe {

    @Test
    @EnabledIfEnvironmentVariable(named = "IG_SMOKE", matches = "1")
    void capturesTheLoginAndTheApplicationAllowance() throws Exception {
        IgCredentials credentials = IgCredentials.fromEnv(System.getenv(), IgEnvironment.DEMO);
        JdkHttpTransport http = new JdkHttpTransport();
        Path out = Path.of("build/wire-capture");
        Files.createDirectories(out);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-IG-API-KEY", credentials.apiKey());
        headers.put("VERSION", "2");
        headers.put("Accept", "application/json; charset=UTF-8");
        headers.put("Content-Type", "application/json; charset=UTF-8");
        String body = new ObjectMapper().createObjectNode().put("identifier", credentials.identifier())
                .put("password", credentials.password()).toString();
        HttpResult login = http.exchange(new HttpCall("POST", URI.create(IgEnvironment.DEMO.baseUrl() + "/session"),
                headers, body));
        Files.writeString(out.resolve("login-response-v2.json"), login.body());
        System.out.println("[probe] POST /session v2: " + login.status());
        assertEquals(200, login.status(), "POST /session");

        String cst = login.firstHeader("CST").orElseThrow(() -> new AssertionError("the login carries no CST"));
        String xst = login.firstHeader("X-SECURITY-TOKEN").orElseThrow(() -> new AssertionError("no X-SECURITY-TOKEN"));
        headers.remove("Content-Type");
        headers.put("VERSION", "1");
        headers.put("CST", cst);
        headers.put("X-SECURITY-TOKEN", xst);
        Thread.sleep(6_500); // the demo's 10 a minute: never two calls inside one pacer slot
        HttpResult application = http.exchange(new HttpCall("GET",
                URI.create(IgEnvironment.DEMO.baseUrl() + "/operations/application"), headers, null));
        Files.writeString(out.resolve("application.json"), application.body());
        System.out.println("[probe] GET /operations/application: " + application.status());
        assertEquals(200, application.status(), "GET /operations/application");
    }
}
