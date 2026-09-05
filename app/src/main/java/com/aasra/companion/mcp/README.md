# Bounded External MCP Tools

This is an optional, manually operated caregiver feature, not an autonomous agent
integration or a general-purpose MCP SDK. Constructing `McpClient` performs no IO.
Nothing registers tools with the LLM, voice pipeline, or app navigation.

## Entry Points

- `com.aasra.companion.ui.tools.ExternalToolsScreen(onBack: () -> Unit)` uses the
  host's Material theme and localized context. English and Hindi strings are in
  `res/values*/external_tools.xml`. The host must deliberately expose this advanced
  screen; calling it does not connect to a server.
- `McpClient(endpoint)` is the public coroutine-based client for later integration.
  `discoverTools()` initializes and lists tools. `prepareCall(name, argumentsJson)`
  validates an object and creates an immutable, single-use review snapshot without
  IO. Show its **endpoint, exact tool name, and complete arguments** before calling
  `callTool(snapshot)` on an explicit approval event. Use `discardApproval` on cancel.
- The latest snapshot is bound by identity to its originating client. It cannot be
  used after cancellation, replacement, consumption, rediscovery, or client close.
  Only `callTool` sends an execution, consuming approval **before** network IO.
  This is an application consent contract, not a sandbox against malicious code
  already executing inside the app. Future callers must preserve the human gate.
- `McpToolResult.json` and `McpException.detail` are **untrusted data**. Do not
  interpret them as instructions, execute code, open URLs, fetch resources, or
  forward them to a model. The screen displays plain text, not HTML/Markdown or
  clickable links. `mcpDisplayText` escapes invisible formatting controls for review.

## Wire Behavior

The pinned protocol versions are **2025-11-25** (offered) and **2025-06-18** (accepted
if negotiated). Any other returned version fails before the initialized notification.
These are bounded subsets of the respective specifications, not a claim of complete
conformance or of compatibility with every server implementing these versions.

1. Send a UTF-8 JSON-RPC `initialize` POST with empty client capabilities and a fixed
   app component name/version. No device, contact, health, conversation, model, or
   cloud credential data is included.
2. Verify the returned version and tools capability. Capture a single valid
   `Mcp-Session-Id` only from the successful initialization response, if present.
3. Send `notifications/initialized`; require an empty HTTP 202 acknowledgement.
4. Send `tools/list`, following opaque `nextCursor` values unchanged. Reject loops,
   excessive pages/tools/metadata, duplicate names, and malformed definitions.
   Discovery failures return no partial list and close the client.
5. Send `tools/call` only after explicit approval. Return real JSON-RPC errors or
   tool results, including `isError` and structured content. There are no fabricated
   success results.

Every POST advertises `Accept: application/json, text/event-stream`. Requests after
initialization carry the negotiated `MCP-Protocol-Version` and optional session ID.
Sessions without an ID are supported. A later differing session header is rejected,
not adopted. JSON and SSE response IDs must match the outstanding request exactly.
SSE parsing supports UTF-8, an initial BOM, LF/CRLF/CR line endings, comments,
multiline data, empty primers and bounded notifications before a response. It stops
at the complete matching result, without waiting for EOF. Each HTTP exchange is
cancelled locally before closing its response to avoid draining an open stream or
waiting for a peer's TLS close handshake. This is not an MCP cancellation message.

## Security Boundaries

- Only an explicitly entered HTTPS endpoint is allowed. No cleartext, subprocesses,
  URL userinfo (including empty userinfo), query strings, or fragments. Endpoints
  are ASCII URL notation, at most 2,048 characters. Use a punycode hostname and
  percent-encoded path where needed. The canonical endpoint is shown for approval.
- The public client creates its own OkHttp configuration. It never receives the
  cloud client, BuildConfig key, app preferences, databases, or health repositories.
  Normal platform certificate/hostname verification remains enabled, with modern
  TLS only. No user-provided CA or insecure trust switch is exposed in the UI.
- Redirects, cookies, HTTP cache, HTTP/proxy authenticators, and connection retries
  are disabled. POST bodies are one-shot, also preventing status-based OkHttp
  follow-ups such as `503 Retry-After: 0`. No manual retries, reconnects, legacy
  endpoint discovery, server URL following, or retry-after scheduling occur.
- OAuth and all other authentication are **unsupported**. HTTP 401/403 produces an
  explicit authorization/access error. No metadata URL is followed, no sign-in UI
  is faked, and no token or cloud key is borrowed. This version is useful only for
  servers that accept unauthenticated requests under their own access policy.
- Endpoint, session, arguments, results, and approvals are transient. No saved-state,
  preferences, logs, analytics, or disk cache are used by this feature. Leaving the
  screen, changing the endpoint, or forgetting the connection cancels local IO and
  clears state. This does not guarantee secure erasure of JVM strings or erase any
  data already received by the remote server.
- Manual connection can reach a private-network HTTPS server with a valid trusted
  certificate. There is no IP allowlist or DNS-rebinding defense here; no server-
  supplied URLs are followed. HTTPS authenticates a host, not the safety of its tools.
- Tool annotations, titles, descriptions, schemas, initialization instructions,
  links, icons, and results never authorize an action. Initialization instructions
  and icons are ignored. Every call, even an allegedly read-only one, needs approval.

## Bounds

| Boundary | Limit |
| --- | --- |
| Connect / write / idle read / entire HTTP exchange | 10 / 10 / 15 / 30 seconds |
| Response body, including all SSE bytes and comments | 256 KiB |
| JSON nesting before recursive parsing | 32 levels |
| SSE events per response | 128 |
| Tool discovery | 8 pages, 64 tools, no partial results |
| Per-tool metadata / cursor / session ID | 16 KiB / 4,096 characters / 1,024 visible ASCII characters |
| Tool names | 1-128 ASCII letters, digits, underscore, hyphen, dot |
| Arguments | 16 KiB UTF-8, JSON object only |
| UI result / JSON-RPC error detail | 32,768 / 4,096 displayed characters |

Discovery can involve up to ten sequential HTTP exchanges (initialize, notification,
eight list pages), each with its own deadline; it does not have a separate overall
deadline. An explicitly labelled truncated display never changes the full approval
snapshot: approval arguments are not truncated. Raw results retained by the client
are bounded by the response limit. Full JSON Schema input/output validation is not
implemented; syntax, object shape, depth, names and envelope checks are not a schema
validator. Only object input schemas with explicit `type: object` are accepted.

## Deliberate Limitations

- No stdio, legacy HTTP+SSE, WebSocket, compressed HTTP bodies, or JSON-RPC batches.
  `Accept-Encoding: identity` is explicit; compressed responses fail safely.
- No background GET stream, SSE resumption, `Last-Event-ID`, polling or automatic
  list refresh. SSE IDs/retry hints are ignored because no reconnection is attempted.
  A broken/polling stream that ends before the matching response fails explicitly.
- Incoming server requests (including ping, sampling, roots and elicitation) are
  rejected; notifications are bounded and ignored. No optional client capabilities
  are advertised. Servers requiring client request handling will not interoperate.
- No task execution, resources, prompts, subscriptions, or model integration.
  Tools declaring `execution.taskSupport: required` are shown but cannot run.
- HTTP 404 for an existing session invalidates it immediately. A new session can
  only begin when the user explicitly connects again with a new client. This defers
  the specification's required new initialization to user action; the expired call
  is **never** replayed. Other errors also do not imply that remote effects were undone.
- `close()` forgets the local session and cancels IO without DELETE or
  `notifications/cancelled`. Server-side cleanup depends on the server's expiry
  policy. Local timeout/cancellation is not remote cancellation or rollback.
- An ambiguous failure always requires checking the remote outcome before a new
  approval. Single-use approval prevents accidental local reuse, not a second
  deliberately approved action or a server executing one request more than once.

## Verification

Run `./gradlew :app:testDebugUnitTest --tests 'com.aasra.companion.mcp.*'`.
Tests use existing JUnit/OkHttp/coroutines/serialization dependencies. Deterministic
interceptors cover protocol envelopes, bounds and consent behavior. A lightweight
`SSLServerSocket` HTTP server covers actual TLS, headers, SSE completion before EOF,
redirect/status replay prevention, cancellation and idle timeout. Its test-only
certificate is generated using the build JDK's `keytool` in a temporary directory
and removed afterward; production trust is never weakened. The timeout test reduces
the idle timeout through a test-only interceptor, not a production setting.

No device instrumentation, live third-party server validation, or rendered-device
UI interaction testing is included. JVM controller tests cover the interaction state
machine; resource parity tests check English/Hindi keys and format arguments.

## Official Sources

Researched directly from the official specification for this implementation:

- [2025-11-25 Streamable HTTP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports):
  POST/Accept rules, JSON/SSE, session and version headers, disconnect/resumption.
- [2025-11-25 lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle):
  initialize, version/capability negotiation, initialized notification and timeouts.
- [2025-11-25 tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools):
  list/call, input/output shapes, errors, untrusted annotations and human approval.
- [2025-11-25 pagination](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/pagination):
  opaque cursors and missing-cursor termination.
- [2025-11-25 authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization):
  optional authorization, HTTP 401/403, token audience boundaries and prohibition on
  tokens in URL queries. OAuth is documented here, not implemented by this client.
- [2025-06-18 Streamable HTTP transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports):
  the earlier negotiated version uses the same supported POST/session/header subset.
