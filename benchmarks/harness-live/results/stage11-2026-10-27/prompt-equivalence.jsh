import com.fasterxml.jackson.databind.*;
import com.paicli.prompt.PromptAssembler;
import java.nio.file.*;
import java.util.*;
ObjectMapper json = new ObjectMapper();
String oldBase = Files.readString(Path.of("benchmarks/harness-live/results/stage11-2026-10-27/base-prompt-before.md")).replace("\r\n", "\n").trim();
String newBase = Files.readString(Path.of("src/main/resources/prompts/base.md")).replace("\r\n", "\n").trim();
JsonNode before = json.readTree(Path.of("target/harness-stage11/live-before.json").toFile()).path("attempts").get(0);
String original = before.path("requests").get(0).path("messages").get(0).path("content").asText().replace("\r\n", "\n");
boolean found = original.contains(oldBase);
String expected = PromptAssembler.withToolAvailability(original.replace(oldBase, newBase), "read_file");
int checked = 0;
boolean matched = found;
for (String file : List.of("live-after-1", "live-after-2")) { for (JsonNode request : json.readTree(Path.of("target/harness-stage11/" + file + ".json").toFile()).path("attempts").get(0).path("requests")) { String recorded = request.path("messages").get(0).path("content").asText().replace("\r\n", "\n"); matched &= expected.equals(recorded); checked++; } }
json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/harness-stage11/prompt-equivalence.json").toFile(), Map.of("originalBaseFound", found, "recordedPostRequestsChecked", checked, "finalPromptRenderingMatchesRecordedRequests", matched));
/exit
