# Callback Mock Rule Engine Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Upgrade CallbackTestPanel from one global response to a persistent, ordered rule engine that returns different responses for method, path, query, header, form, and JSON request differences, including request-value templates.

**Architecture:** Keep matching and rendering in Java-8-compatible, Swing-free domain classes. A callback application service owns an immutable MockRuleSet snapshot and a loopback HttpServer; the HTTP handler parses HttpExchange into a domain request, resolves one response, and publishes a request record back to Swing on the EDT. The UI edits rules in a separate dialog and persists versioned JSON through ApplicationPaths and AtomicFiles before publishing a new snapshot.

**Tech Stack:** Java 8 source/target, JUnit 5, Jackson 2.15.2 already present in pom.xml, JDK com.sun.net.httpserver.HttpServer, Swing/FlatLaf UI kit, existing ApplicationPaths and AtomicFiles.

---

## File map

Create the focused classes below and keep CallbackTestPanel as the composition root.

- Domain: src/main/java/com/aqishi/toolbox/feature/network/domain/callbackmock/
  - MatchSource.java, MatchOperator.java, PathMatchMode.java
  - MockResponse.java, MockCondition.java, MockRule.java, MockRuleSet.java
  - MockRequest.java, MockResolution.java
  - MockRuleResolver.java, MockTemplateRenderer.java, MockRuleValidator.java
- Application: src/main/java/com/aqishi/toolbox/feature/network/application/CallbackMockService.java and MockRequestRecord.java.
- Infrastructure: src/main/java/com/aqishi/toolbox/feature/network/infra/
  - CallbackMockRuleRepository.java, CallbackMockHttpHandler.java
  - MockHttpRequestParser.java, FormBodyParser.java, MultipartFormDataParser.java
- UI: replace src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackTestPanel.java; create CallbackMockRuleDialog.java and CallbackMockRuleTableModel.java in the same package.
- Configuration: modify src/main/java/com/aqishi/toolbox/vault/ApplicationPaths.java.
- Resources: modify messages.properties, messages_zh_CN.properties, and messages_en_US.properties.
- Tests: add matching domain, infra, application, and UI tests; extend ApplicationPathsTest.java and I18nResourceTest.java.

Each task below is independently testable and ends with a small local commit.

### Task 1: Add immutable callback-mock domain types and the resolver

Files:
- Create the twelve domain files listed in src/main/java/com/aqishi/toolbox/feature/network/domain/callbackmock/.
- Test: src/test/java/com/aqishi/toolbox/feature/network/domain/callbackmock/MockRuleResolverTest.java.

- [ ] Step 1: Write the first failing resolver test using real objects:

~~~java
@Test
void resolvesFirstEnabledRuleWhenMethodPathAndAllConditionsMatch() throws Exception {
    MockResponse paid = new MockResponse(201, "application/json", "{\"result\":\"paid\"}");
    MockRule rule = MockRule.builder("paid-order")
            .method("POST")
            .path(PathMatchMode.PREFIX, "/orders")
            .condition(new MockCondition(MatchSource.JSON, "status",
                    MatchOperator.EQUALS, "paid"))
            .condition(new MockCondition(MatchSource.HEADER, "X-Channel",
                    MatchOperator.EXISTS, null))
            .response(paid)
            .build();
    MockRequest request = MockRequest.builder()
            .method("post")
            .path("/orders/42")
            .header("X-Channel", "mobile")
            .json(new ObjectMapper().readTree("{\"status\":\"paid\"}"))
            .build();

    MockResolution resolution = new MockRuleResolver().resolve(
            MockRuleSet.of(Collections.singletonList(rule),
                    new MockResponse(200, "application/json", "fallback")),
            request);

    assertEquals("paid-order", resolution.getRuleName());
    assertFalse(resolution.isFallback());
    assertEquals(paid, resolution.getResponse());
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=MockRuleResolverTest test. Expected RED: the callback-mock domain types do not exist.

- [ ] Step 3: Implement final Java 8 value classes with defensive unmodifiable copies. Use enum values MatchSource.QUERY/HEADER/FORM/JSON, MatchOperator.EQUALS/EXISTS/CONTAINS/REGEX, and PathMatchMode.EXACT/PREFIX/REGEX. Expose these exact construction methods:

~~~java
public static MockRule.Builder builder(String name);
public Builder method(String method);
public Builder path(PathMatchMode mode, String path);
public Builder condition(MockCondition condition);
public Builder response(MockResponse response);
public Builder enabled(boolean enabled);
public Builder id(String id);
public MockRule build();

public static MockRequest.Builder builder();
public Builder method(String method);
public Builder path(String path);
public Builder query(String key, String value);
public Builder header(String key, String value);
public Builder form(String key, String value);
public Builder json(JsonNode root);
public Builder body(String rawBody, boolean truncated);
public MockRequest build();

public static MockRuleSet of(List<MockRule> rules, MockResponse fallbackResponse);
public List<MockRule> getRules();
public MockResponse getFallbackResponse();
~~~

MockRequest stores Map<String,List<String>> values for query/header/form, lowercases only header lookup keys, and stores a Jackson JSON root plus raw-body/truncated metadata. MockResolution contains selected response, rule ID/name, and a fallback flag.

- [ ] Step 4: Implement MockRuleResolver.resolve(MockRuleSet, MockRequest). Iterate in list order, skip disabled rules, compare methods case-insensitively, use path equality/startsWith/Pattern.matcher(path).matches(), and require every condition to match. For repeated values, any value may satisfy EQUALS, CONTAINS, or REGEX; EXISTS only requires one value. Return fallback when no rule matches.

- [ ] Step 5: Run mvn -q -Dtest=MockRuleResolverTest test and then mvn -q test. Expected: focused and existing tests pass.

- [ ] Step 6: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/feature/network/domain/callbackmock src/test/java/com/aqishi/toolbox/feature/network/domain/callbackmock/MockRuleResolverTest.java
git commit -m "feat: add callback mock rule domain"
~~~

### Task 2: Complete resolver edge cases, template rendering, and validation

Files:
- Modify MockRuleResolver.java.
- Create MockTemplateRenderer.java and MockRuleValidator.java.
- Test: MockRuleResolverTest.java, MockTemplateRendererTest.java, MockRuleValidatorTest.java in the matching test package.

- [ ] Step 1: Add failing tests for disabled rules, ANY, exact/prefix/regex paths, four parameter sources, duplicate values, AND composition, missing values, malformed JSON, all four operators, template variables, nested JSON, object/array replacement, missing-variable preservation, and one-pass rendering. Use this concrete template test:

~~~java
@Test
void rendersNestedJsonHeaderQueryAndFormValuesOnce() throws Exception {
    MockRequest request = MockRequest.builder()
            .query("orderId", "Q-7")
            .header("X-Request-Id", "trace-9")
            .form("status", "paid")
            .json(new ObjectMapper().readTree("{\"order\":{\"id\":42}}"))
            .build();

    String rendered = new MockTemplateRenderer().render(
            "${query.orderId}|${header.X-Request-Id}|${form.status}|${json.order.id}",
            request);

    assertEquals("Q-7|trace-9|paid|42", rendered);
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=MockRuleResolverTest,MockTemplateRendererTest,MockRuleValidatorTest test. Expected RED failures must identify missing behavior or missing types, not a Maven setup error.

- [ ] Step 3: Implement JSON lookup for dotted object segments and [index] array segments. MockTemplateRenderer scans the response body once with the pattern $\\{([a-zA-Z]+)\\.([^}]+)}; replace known scalar values, compact JSON for objects/arrays, leave unknown variables unchanged, and do not recursively render replacement text.

- [ ] Step 4: Implement MockRuleValidator returning immutable List<String> field messages. Validate rule name, status code 100–599, nonblank Content-Type, exact/prefix paths beginning with /, regex compilation, condition source/field, EXISTS without expected text, valid JSON path segments, and balanced ${namespace.path} syntax. Validate the fallback response too.

- [ ] Step 5: Run the focused tests to GREEN, then mvn -q test. Refactor only after the tests pass.

- [ ] Step 6: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/feature/network/domain/callbackmock src/test/java/com/aqishi/toolbox/feature/network/domain/callbackmock
git commit -m "feat: match callback requests and render templates"
~~~

### Task 3: Add persistent rule-file location and JSON repository

Files:
- Modify src/main/java/com/aqishi/toolbox/vault/ApplicationPaths.java.
- Modify src/test/java/com/aqishi/toolbox/vault/ApplicationPathsTest.java.
- Create src/main/java/com/aqishi/toolbox/feature/network/infra/CallbackMockRuleRepository.java.
- Test: src/test/java/com/aqishi/toolbox/feature/network/infra/CallbackMockRuleRepositoryTest.java.

- [ ] Step 1: Add a failing path assertion and repository tests for missing-file defaults, round-trip order and long body, malformed JSON preserving the source, unknown version fallback, and injected AtomicFiles failure:

~~~java
@Test
void callbackRulesFileLivesBesideOtherApplicationConfiguration() {
    ApplicationPaths paths = ApplicationPaths.resolve(
            "Windows 11", "C:\\Users\\dev",
            Collections.singletonMap("APPDATA",
                    "C:\\Users\\dev\\AppData\\Roaming"),
            Paths.get("D:\\portable"));

    assertEquals("C:/Users/dev/AppData/Roaming/JavaToolbox/callback-mock-rules.json",
            paths.getCallbackMockRulesFile().toString().replace('\\\\', '/'));
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=ApplicationPathsTest,CallbackMockRuleRepositoryTest test. Expected RED because the accessor and repository do not exist.

- [ ] Step 3: Add this accessor without changing existing paths:

~~~java
private static final String CALLBACK_MOCK_RULES_FILE_NAME =
        "callback-mock-rules.json";

public Path getCallbackMockRulesFile() {
    return configDirectory.resolve(CALLBACK_MOCK_RULES_FILE_NAME);
}
~~~

- [ ] Step 4: Implement CallbackMockRuleRepository(Path, AtomicFiles, ObjectMapper). Its public API is:

~~~java
public CallbackMockRuleRepository(Path file, AtomicFiles atomicFiles,
                                  ObjectMapper mapper);
public LoadResult load();
public void save(MockRuleSet ruleSet) throws IOException;
~~~

LoadResult exposes getRuleSet() and getWarning(). Use explicit Jackson DTOs or tree conversion so private domain fields are not serialized accidentally. load returns built-in defaults for absent, malformed, and unsupported-version files but never deletes/replaces the source. save emits version 1 with rules in list order and calls the instance method atomicFiles.write(file, bytes). Saving errors propagate to the UI and never publish a new service snapshot.

- [ ] Step 5: Run mvn -q -Dtest=ApplicationPathsTest,CallbackMockRuleRepositoryTest test and then mvn -q test. Expected: all pass, including injected write failure.

- [ ] Step 6: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/vault/ApplicationPaths.java src/test/java/com/aqishi/toolbox/vault/ApplicationPathsTest.java src/main/java/com/aqishi/toolbox/feature/network/infra/CallbackMockRuleRepository.java src/test/java/com/aqishi/toolbox/feature/network/infra/CallbackMockRuleRepositoryTest.java
git commit -m "feat: persist callback mock rules"
~~~

### Task 4: Parse HttpExchange requests without Swing dependencies

Files:
- Create MockHttpRequestParser.java, FormBodyParser.java, MultipartFormDataParser.java in src/main/java/com/aqishi/toolbox/feature/network/infra/.
- Test: src/test/java/com/aqishi/toolbox/feature/network/infra/MockHttpRequestParserTest.java.

- [ ] Step 1: Write parser tests for decoded query values and duplicates, case-insensitive headers, URL-encoded forms, multipart text fields, JSON objects/arrays, unsupported content types, and the 1 MiB body cap. Use HttpURLConnection against a local server and assert MockRequest values. Include:

~~~java
@Test
void parsesUrlEncodedFormFields() throws Exception {
    MockRequest request = parse("POST", "/callback",
            "application/x-www-form-urlencoded",
            "status=paid&tag=one&tag=two");

    assertEquals(Arrays.asList("one", "two"),
            request.values(MatchSource.FORM, "tag"));
    assertEquals(Collections.singletonList("paid"),
            request.values(MatchSource.FORM, "status"));
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=MockHttpRequestParserTest test. Expected RED because parser classes are missing.

- [ ] Step 3: Implement MockHttpRequestParser.parse(HttpExchange) with MAX_BODY_BYTES = 1024 * 1024, always closing the request body, UTF-8 decoding, method/path/raw query/client address/headers, and a truncated flag. Bind header lookups case-insensitively.

- [ ] Step 4: Implement application/x-www-form-urlencoded parsing with & pairs and + decoding. Implement multipart parsing using the Content-Type boundary parameter, retaining text fields with a name and ignoring file parts. Parse application/json and *+json with ObjectMapper; invalid/truncated JSON leaves the JSON root absent without throwing out of the handler.

- [ ] Step 5: Run mvn -q -Dtest=MockHttpRequestParserTest test, then mvn -q test. Expected: parser tests and all existing tests pass.

- [ ] Step 6: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/feature/network/infra src/test/java/com/aqishi/toolbox/feature/network/infra/MockHttpRequestParserTest.java
git commit -m "feat: parse callback mock request inputs"
~~~

### Task 5: Add snapshot-based callback service and HTTP handler

Files:
- Create src/main/java/com/aqishi/toolbox/feature/network/application/CallbackMockService.java and MockRequestRecord.java.
- Create src/main/java/com/aqishi/toolbox/feature/network/infra/CallbackMockHttpHandler.java.
- Test: src/test/java/com/aqishi/toolbox/feature/network/application/CallbackMockServiceTest.java.

- [ ] Step 1: Write the failing local HTTP integration test:

~~~java
@Test
void returnsDifferentResponsesForDifferentJsonValuesAndPublishesHit() throws Exception {
    CallbackMockService service = new CallbackMockService(
            new MockRuleResolver(), new MockHttpRequestParser());
    AtomicReference<MockRequestRecord> record = new AtomicReference<MockRequestRecord>();
    service.setRequestListener(record::set);
    service.replaceRuleSet(ruleSetFor("paid", 201, "paid-body"));
    service.start(0);

    HttpResult result = postJson(service.getPort(), "/orders",
            "{\"status\":\"paid\"}");

    assertEquals(201, result.status);
    assertEquals("paid-body", result.body);
    assertEquals("paid", record.get().getRuleName());
    service.closeResources();
    service.closeResources();
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=CallbackMockServiceTest test. Expected RED because the service/handler and test helpers do not exist.

- [ ] Step 3: Implement MockRequestRecord as an immutable application event containing receive time, method, path, query, headers, body, client address, selected rule name, fallback flag, response status, and response body. Implement CallbackMockService as ManagedResourceOwner with AtomicReference<MockRuleSet>, synchronized HttpServer lifecycle, and this API:

~~~java
public CallbackMockService(MockRuleResolver resolver,
                           MockHttpRequestParser parser);
public synchronized void start(int port) throws IOException;
public synchronized void stop();
public synchronized boolean isRunning();
public synchronized int getPort();
public MockRuleSet getRuleSet();
public void replaceRuleSet(MockRuleSet ruleSet);
public void setRequestListener(Consumer<MockRequestRecord> listener);
@Override public void closeResources();
~~~

Bind only to 127.0.0.1. Pass port 0 through for tests and expose the actual bound port. replaceRuleSet publishes the whole immutable object in one AtomicReference.set.

- [ ] Step 4: Implement CallbackMockHttpHandler.handle(HttpExchange). Parse once, read one snapshot, resolve and render one response, construct a MockRequestRecord with request summary and selected rule name/fallback flag, invoke the listener, write UTF-8 response headers/body, and close the exchange in finally. Malformed input sources must produce normal no-match/fallback rather than a 500.

- [ ] Step 5: Run mvn -q -Dtest=CallbackMockServiceTest test and mvn -q test. Expected: all integration tests pass and no server remains bound after the test.

- [ ] Step 6: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/feature/network/application/CallbackMockService.java src/main/java/com/aqishi/toolbox/feature/network/application/MockRequestRecord.java src/main/java/com/aqishi/toolbox/feature/network/infra/CallbackMockHttpHandler.java src/test/java/com/aqishi/toolbox/feature/network/application/CallbackMockServiceTest.java
git commit -m "feat: serve callback mock rule snapshots"
~~~

### Task 6: Add the independent rule editor dialog and table model

Files:
- Create src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackMockRuleTableModel.java.
- Create src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackMockRuleDialog.java.
- Test src/test/java/com/aqishi/toolbox/feature/network/ui/CallbackMockRuleDialogTest.java.

- [ ] Step 1: Write failing EDT tests for table summaries, EXISTS disabling expected-value input, invalid-field errors, and cancel preserving the original rule. Keep test access package-private:

~~~java
@Test
void existsConditionDisablesExpectedValueAndCancelKeepsOriginalRule()
        throws Exception {
    AtomicReference<CallbackMockRuleDialog> ref =
            new AtomicReference<CallbackMockRuleDialog>();
    SwingUtilities.invokeAndWait(() -> ref.set(
            new CallbackMockRuleDialog(null, originalRule(), null)));

    CallbackMockRuleDialog dialog = ref.get();
    assertFalse(dialog.getExpectedValueFieldForTest().isEnabled());
    assertEquals("original", dialog.getInitialRuleForTest().getName());
    dialog.dispose();
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=CallbackMockRuleDialogTest test. Expected RED because the editor classes do not exist.

- [ ] Step 3: Implement CallbackMockRuleTableModel by extending AbstractTableModel. Keep a copied list, expose columns enabled/name/request summary/response status, make only enabled editable, and provide setRules, getRuleAt, and conditionSummary.

- [ ] Step 4: Implement CallbackMockRuleDialog with constructor CallbackMockRuleDialog(Window owner, MockRule initialRule, Consumer<MockRule> onSaved) as a resizable JDialog using Card, FormGrid, Fields, Buttons, and Layouts. Include rule name, enabled, method including ANY, path mode, path, condition rows with source/operator/field/expected, status code, Content-Type, response body, variable help, cancel, and save. Save validates with MockRuleValidator and invokes onSaved only when validation succeeds; invalid fields show local messages.

- [ ] Step 5: Run mvn -q -Dtest=CallbackMockRuleDialogTest test, verify nonzero preferred size and focused accessibility labels, then mvn -q test.

- [ ] Step 6: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackMockRuleTableModel.java src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackMockRuleDialog.java src/test/java/com/aqishi/toolbox/feature/network/ui/CallbackMockRuleDialogTest.java
git commit -m "feat: add callback mock rule editor"
~~~

### Task 7: Replace CallbackTestPanel with the rule-list composition root

Files:
- Modify src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackTestPanel.java.
- Create src/test/java/com/aqishi/toolbox/feature/network/ui/CallbackTestPanelTest.java.
- Do not change callback.mock registration in ToolRegistry.java or ToolCatalog.java.

- [ ] Step 1: Write failing EDT panel tests for stable ID, rule table/service controls, add/edit/reorder/delete, fallback protection, load warning, and idempotent closeResources. Inject a temporary repository/service using ConfigManagerTestSupport.install so tests never touch user configuration.

- [ ] Step 2: Run mvn -q -Dtest=CallbackTestPanelTest test. Expected RED because the current panel only has global response fields and no ManagedResourceOwner lifecycle.

- [ ] Step 3: Replace global response fields with CallbackMockRuleRepository.LoadResult, CallbackMockService, CallbackMockRuleTableModel, JTable, rule actions, and a default-response action. The panel loads on construction, retains a nonfatal warning banner, and does not make the HTTP handler read Swing fields.

- [ ] Step 4: Implement add/edit/delete/move-up/move-down by copying the current rule list, validating and saving through the repository, and only then replacing the service snapshot. Disable selection-dependent actions and protect the fallback from deletion/reordering. Append selected rule name or fallback to request summary and list labels on the EDT.

- [ ] Step 5: Keep the top server card, left rule/history split, and right request tabs. Use Card.titled/flush, Fields.scroll/scrollVertical, Layouts.splitHorizontal/splitVertical, and no nested fixed-width controls that clip at the 820×520 minimum. Implement ManagedResourceOwner.closeResources() as null-safe and idempotent.

- [ ] Step 6: Run:

~~~bash
mvn -q -Dtest=CallbackTestPanelTest test
mvn -q test
mvn -q package
~~~

Expected: focused tests, all tests, and the Java 8-targeted shaded jar succeed.

- [ ] Step 7: Commit:

~~~bash
git add src/main/java/com/aqishi/toolbox/feature/network/ui/CallbackTestPanel.java src/test/java/com/aqishi/toolbox/feature/network/ui/CallbackTestPanelTest.java
git commit -m "feat: integrate callback mock rules into panel"
~~~

### Task 8: Localize controls and verify end-to-end behavior

Files:
- Modify messages.properties, messages_zh_CN.properties, messages_en_US.properties.
- Modify src/test/java/com/aqishi/toolbox/util/I18nResourceTest.java.
- Modify callback-mock UI only when a verified failure requires it.

- [ ] Step 1: Add a failing resource assertion:

~~~java
@Test
void definesCallbackMockRuleLabelsInEverySupportedResource()
        throws Exception {
    assertProperty("messages.properties", "callback.mock.rules", "响应规则");
    assertProperty("messages_zh_CN.properties",
            "callback.mock.operator.contains", "包含");
    assertProperty("messages_en_US.properties",
            "callback.mock.operator.contains", "Contains");
}
~~~

- [ ] Step 2: Run mvn -q -Dtest=I18nResourceTest test. Expected RED because the callback rule keys are absent.

- [ ] Step 3: Add the same complete key set to all resources: callback.mock.rules, add, edit, delete, moveUp, moveDown, fallback, method, path, pathMode, condition, source.query/header/form/json, operator.equals/exists/contains/regex, pathMode.exact/prefix/regex, save, cancel, loadWarning, and saveFailed. Replace new labels with I18n.get calls.

- [ ] Step 4: Run the focused resource/UI tests, then:

~~~bash
mvn -q -Dtest=I18nResourceTest,CallbackMockRuleDialogTest,CallbackTestPanelTest test
mvn -q test
mvn -q package
~~~

- [ ] Step 5: Exercise the built local HTTP flow with an isolated config root. Send different JSON, form, query, and fallback requests and assert differing status, Content-Type, body, and template values. Stop the app and verify its loopback port is released. Do not connect to external services.

- [ ] Step 6: Inspect final changes:

~~~bash
git diff --check
git status --short
git diff --stat HEAD~7..HEAD
~~~

Expected: no whitespace errors and no visual-companion sessions, screenshots, credentials, target profiles, or generated jars are staged.

- [ ] Step 7: Commit localization and verified hardening separately:

~~~bash
git add src/main/resources/com/aqishi/toolbox/util src/test/java/com/aqishi/toolbox/util/I18nResourceTest.java src/main/java/com/aqishi/toolbox/feature/network/ui src/test/java/com/aqishi/toolbox/feature/network/ui
git commit -m "test: verify callback mock rule engine"
~~~

## Plan self-review

- Spec coverage: domain matching, four request sources, four operators, three path modes, templates, fallback, persistence, migration/error fallback, snapshot concurrency, lifecycle, UI dialog/table, localization, and local HTTP tests each have an explicit task.
- Placeholder scan: no unfinished placeholder markers or unspecified error-handling steps are present. Every task names files, commands, expected outcomes, and concrete APIs or test content.
- Type consistency: MockRuleSet, MockRule, MockCondition, MockResponse, MockRequest, MockResolution, MockRuleResolver, MockTemplateRenderer, MockRuleValidator, CallbackMockRuleRepository, MockHttpRequestParser, and CallbackMockService are named consistently across tasks.
- Scope check: this is one feature with tightly connected domain, infrastructure, application, and UI units; no unrelated tool refactor is included.
- Java compatibility: all planned APIs use Java 8 language/library APIs; HttpURLConnection is used for tests instead of java.net.http.
