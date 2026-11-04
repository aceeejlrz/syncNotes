package com.jezrel.syncnotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs docs/api.http from top to bottom against the real server and checks each request's
 * "# Expect:" line, so the file can't drift from the API. It reads the file's REST Client syntax
 * itself: this proves the requests and expectations, not the VS Code extension.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApiHttpFileTest {

    static final Path FILE = Path.of("../docs/api.http");
    static final Pattern VARIABLE = Pattern.compile("^@(\\w+)\\s*=\\s*(.*)$", Pattern.MULTILINE);
    static final Pattern EXPECT = Pattern.compile("^# Expect: (\\d{3})(?: (.+))?$", Pattern.MULTILINE);
    static final Pattern NAME = Pattern.compile("^# @name (\\w+)$", Pattern.MULTILINE);
    static final Pattern REFERENCE = Pattern.compile("\\{\\{([^}]+)}}");
    static final Pattern RESPONSE_FIELD = Pattern.compile("(\\w+)\\.response\\.body\\.\\$\\.(\\w+)");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** Section 8.2. */
    static final Set<String> ENDPOINTS = Set.of(
            "POST /api/v1/auth/register", "POST /api/v1/auth/login", "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout", "GET /api/v1/me", "POST /api/v1/sync/push", "GET /api/v1/sync/pull",
            "GET /actuator/health");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    final Map<String, String> variables = new HashMap<>();
    final Map<String, JsonNode> responses = new HashMap<>();

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
    }

    String resolve(String text) {
        Matcher m = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(valueOf(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    String valueOf(String reference) {
        Matcher field = RESPONSE_FIELD.matcher(reference);
        if (field.matches()) {
            assertThat(responses).as("%s is used before that request was sent", reference).containsKey(field.group(1));
            return responses.get(field.group(1)).get(field.group(2)).asString();
        }
        assertThat(variables).as("unknown variable %s", reference).containsKey(reference);
        return variables.get(reference);
    }

    @Test
    void everyRequestInTheFile_getsWhatItSaysItWill() throws Exception {
        String file = Files.readString(FILE).replace("\r\n", "\n");
        String[] blocks = file.split("(?m)^###.*$");

        Matcher vars = VARIABLE.matcher(blocks[0]);
        while (vars.find()) {
            variables.put(vars.group(1), vars.group(2).strip());
        }
        assertThat(variables).containsKey("baseUrl");
        variables.put("baseUrl", "http://localhost:" + port);          // the file's default is for people

        Set<String> covered = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i].strip();
            Matcher expect = EXPECT.matcher(block);
            assertThat(expect.find()).as("request %d has no Expect line:%n%s", i, block).isTrue();
            String[] parts = block.split("\n\n", 2);
            List<String> lines = parts[0].lines().filter(l -> !l.startsWith("#")).toList();
            String[] requestLine = lines.getFirst().split(" ", 2);

            String url = resolve(requestLine[1]);
            URI uri = URI.create(url);
            covered.add(requestLine[0] + " " + uri.getPath());

            String body = parts.length > 1 ? resolve(parts[1]) : "";
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).method(requestLine[0],
                    body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            for (String header : lines.subList(1, lines.size())) {
                String[] nameValue = header.split(":", 2);
                request.header(nameValue[0].strip(), resolve(nameValue[1].strip()));
            }
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());

            String wantedText = expect.group(2);
            if (response.statusCode() != Integer.parseInt(expect.group(1))
                    || (wantedText != null && !response.body().contains(wantedText))) {
                problems.add("request %d: %s %s expected %s %s but got %d %s".formatted(i, requestLine[0], uri.getPath(),
                        expect.group(1), wantedText == null ? "" : wantedText, response.statusCode(), response.body()));
            }
            Matcher name = NAME.matcher(block);
            if (name.find()) {
                responses.put(name.group(1), JSON.readTree(response.body()));
            }
        }

        assertThat(problems).isEmpty();
        assertThat(covered).as("section 8.2's endpoints").containsAll(ENDPOINTS);
    }
}
