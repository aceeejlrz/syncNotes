package com.jezrel.syncnotes.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden vectors: every merge input and expected output in merge-vectors.json was produced by the
 * executable model (docs/sync_protocol_model.py, contract rules). The Java engine must agree on
 * every one, which keeps the two legs of the contract in sync (rule R3).
 *
 * Regenerate after a rule change with:
 *   python docs/sync_protocol_model.py --export-vectors server/src/test/resources/merge-vectors.json
 */
class MergeEngineVectorTest {

    private static JsonNode root;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = MergeEngineVectorTest.class.getResourceAsStream("/merge-vectors.json")) {
            assertThat(in).as("merge-vectors.json on the test classpath").isNotNull();
            root = JsonMapper.builder().build().readTree(in);
        }
    }

    static Stream<Arguments> vectors() throws IOException {
        if (root == null) {
            load();
        }
        List<Arguments> args = new ArrayList<>();
        for (JsonNode v : root.get("vectors")) {
            args.add(Arguments.of(v.get("name").asString(), v));
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void engineMatchesModel(String name, JsonNode v) {
        ServerNote current = v.get("current").isNull() ? null : serverNote(v.get("current"));
        IncomingChange change = change(v.get("change"));

        MergeResult actual = MergeEngine.merge(current, change, device(v.get("device").asString()),
                v.get("deviceName").asString());

        JsonNode e = v.get("expected");
        assertThat(outcome(actual)).isEqualTo(new Outcome(
                PushStatus.valueOf(e.get("status").asString()),
                values(e.get("after")),
                fields(e.get("changed")),
                e.get("copy").isNull() ? null
                        : new ConflictCopy(e.get("copy").get("title").asString(), e.get("copy").get("body").asString()),
                rules(e.get("rules"))));
    }

    @Test
    void vectorsCoverEveryRuleAndAreNotTruncated() {
        Set<MergeRule> covered = EnumSet.noneOf(MergeRule.class);
        for (JsonNode v : root.get("vectors")) {
            covered.addAll(rules(v.get("expected").get("rules")));
        }
        assertThat(covered).containsExactlyInAnyOrder(MergeRule.values());
        assertThat(root.get("vectors").size()).isGreaterThanOrEqualTo(1000);
    }

    // ---- mapping from the model's JSON ------------------------------------------------------

    record Outcome(PushStatus status, NoteValues after, Set<NoteField> changed, ConflictCopy copy, Set<MergeRule> rules) {
    }

    private static Outcome outcome(MergeResult r) {
        return new Outcome(r.status(), r.after(), r.changedFields(), r.conflictCopy(), r.rules());
    }

    /** Model devices D1..D3 map to fixed UUIDs. */
    static UUID device(String id) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + id.substring(1));
    }

    private static ServerNote serverNote(JsonNode n) {
        JsonNode fv = n.get("fieldVersions");
        JsonNode fd = n.get("fieldDevices");
        return new ServerNote(values(n),
                new PerField<>(fv.get("title").asLong(), fv.get("body").asLong(), fv.get("pinned").asLong(), fv.get("trashed").asLong()),
                new PerField<>(device(fd.get("title").asString()), device(fd.get("body").asString()),
                        device(fd.get("pinned").asString()), device(fd.get("trashed").asString())),
                n.get("version").asLong());
    }

    private static IncomingChange change(JsonNode c) {
        JsonNode s = c.get("sent");
        JsonNode b = c.get("baseVersions");
        return new IncomingChange(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                new PerField<>(b.get("title").asLong(), b.get("body").asLong(), b.get("pinned").asLong(), b.get("trashed").asLong()),
                s.has("title") ? s.get("title").asString() : null,
                s.has("body") ? s.get("body").asString() : null,
                s.has("pinned") ? s.get("pinned").asBoolean() : null,
                s.has("trashed") ? s.get("trashed").asBoolean() : null);
    }

    private static NoteValues values(JsonNode n) {
        return new NoteValues(n.get("title").asString(), n.get("body").asString(),
                n.get("pinned").asBoolean(), n.get("trashed").asBoolean());
    }

    private static Set<NoteField> fields(JsonNode arr) {
        Set<NoteField> out = EnumSet.noneOf(NoteField.class);
        for (JsonNode f : arr) {
            out.add(NoteField.valueOf(f.asString().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static Set<MergeRule> rules(JsonNode arr) {
        Set<MergeRule> out = EnumSet.noneOf(MergeRule.class);
        for (JsonNode r : arr) {
            out.add(MergeRule.valueOf(r.asString()));
        }
        return out;
    }
}
