package mcpthespire.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * MCP Server implementation using Streamable HTTP transport.
 * Implements JSON-RPC 2.0 protocol for MCP communication.
 */
public class MCPServer implements Runnable {

    private static final Logger logger = LogManager.getLogger(MCPServer.class.getName());
    private static final String SERVER_NAME = "MCPTheSpire";
    private static final String SERVER_VERSION = "1.0.0";

    private final String host;
    private final int port;
    private final MCPToolHandler toolHandler;
    private final Gson gson;
    private HttpServer httpServer;

    private volatile boolean running = true;
    private String sessionId = null;

    // Queue for pending tool calls that need to be executed on the game thread
    private final BlockingQueue<PendingToolCall> pendingToolCalls;

    // Serialize tool handler execution to avoid cross-thread reads during game mutation/rendering
    private final Object toolExecutionLock = new Object();

    // Pending batch execution state (for execute_actions across multiple frames)
    private PendingBatchExecution pendingBatch = null;
    private PendingMutatingToolResult pendingMutatingToolResult = null;
    private long lastBatchActionTime = 0;
    private static final long MIN_BATCH_ACTION_INTERVAL_MS = 50; // Minimum time between batch actions
    private static final long MAX_WAIT_FOR_READY_MS = 10000; // Maximum time to wait for ready_for_command

    // Waiting for ready state
    private long waitStartTime = 0;

    public MCPServer() {
        this("127.0.0.1", 8080);
    }

    public MCPServer(int port) {
        this("127.0.0.1", port);
    }

    public MCPServer(String host, int port) {
        this.host = host;
        this.port = port;
        this.toolHandler = new MCPToolHandler();
        this.gson = new Gson();
        this.pendingToolCalls = new LinkedBlockingQueue<>();
    }

    @Override
    public void run() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.setExecutor(Executors.newFixedThreadPool(4));

            // Main MCP endpoint - Streamable HTTP
            httpServer.createContext("/mcp", new MCPHandler());

            // Health check endpoint
            httpServer.createContext("/health", exchange -> {
                String response = "{\"status\":\"ok\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            });

            httpServer.start();
            logger.info("MCP Server (Streamable HTTP) started on http://" + host + ":" + port);
            logger.info("MCP endpoint: http://" + host + ":" + port + "/mcp");

            // Keep running
            while (running) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            logger.error("Error starting MCP HTTP server", e);
        }
    }

    /**
     * Streamable HTTP Handler - handles all MCP communication
     */
    private class MCPHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            // Handle CORS preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                handleCORS(exchange);
                return;
            }

            // Set CORS headers for all responses
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id");
            exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "Mcp-Session-Id");

            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                // GET for SSE stream (optional, for server-initiated notifications)
                handleGet(exchange);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                // DELETE to close session
                handleDelete(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }

        private void handleCORS(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id");
            exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "Mcp-Session-Id");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
            exchange.sendResponseHeaders(204, -1);
        }

        private void handlePost(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");

            // Read request body
            String requestBody;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                requestBody = sb.toString();
            }

            logger.info("Received MCP request: " + requestBody);

            try {
                String response = handleMessage(requestBody, exchange);

                // Add session ID header if we have one
                if (sessionId != null) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                }

                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                    os.flush();
                }
            } catch (Exception e) {
                logger.error("Error handling MCP request", e);
                String errorResponse = MCPProtocol.buildErrorResponse(null, MCPProtocol.ERROR_INTERNAL, e.getMessage());
                byte[] responseBytes = errorResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            // GET request for SSE stream - used for server-to-client notifications
            String accept = exchange.getRequestHeaders().getFirst("Accept");

            if (accept != null && accept.contains("text/event-stream")) {
                // Return SSE stream for notifications
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("Connection", "keep-alive");

                if (sessionId != null) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                }

                exchange.sendResponseHeaders(200, 0);

                try (OutputStream os = exchange.getResponseBody();
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {

                    // Send initial comment to establish connection
                    writer.print(": connected\n\n");
                    writer.flush();
                    os.flush();

                    // Keep connection alive
                    while (running) {
                        Thread.sleep(30000);
                        writer.print(": keepalive\n\n");
                        writer.flush();
                        os.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                // Regular GET - return server info
                Map<String, String> serverInfo = new HashMap<>();
                serverInfo.put("name", SERVER_NAME);
                serverInfo.put("version", SERVER_VERSION);
                serverInfo.put("transport", "streamable-http");
                String info = gson.toJson(serverInfo);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] responseBytes = info.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        }

        private void handleDelete(HttpExchange exchange) throws IOException {
            // Close session
            sessionId = null;
            exchange.sendResponseHeaders(204, -1);
            logger.info("MCP session closed");
        }
    }

    private String handleMessage(String message, HttpExchange exchange) {
        JsonObject request;
        try {
            request = MCPProtocol.parseRequest(message);
        } catch (JsonSyntaxException e) {
            return MCPProtocol.buildErrorResponse(null, MCPProtocol.ERROR_PARSE, "Parse error: " + e.getMessage());
        }

        String method = MCPProtocol.getMethod(request);
        JsonElement id = MCPProtocol.getId(request);
        JsonObject params = MCPProtocol.getParams(request);

        logger.info("MCP method: " + method + ", id: " + id);

        // Handle notifications (no id) - just return empty
        if (id == null) {
            if (MCPProtocol.METHOD_INITIALIZED.equals(method)) {
                logger.info("Client initialized notification received");
            }
            // Notifications don't require a response, but we'll send an empty success
            return "{}";
        }

        if (method == null) {
            return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INVALID_REQUEST, "Missing method");
        }

        switch (method) {
            case MCPProtocol.METHOD_INITIALIZE:
                return handleInitialize(id, params);

            case MCPProtocol.METHOD_INITIALIZED:
                logger.info("Client initialized notification received");
                return MCPProtocol.buildResponse(id, new HashMap<>());

            case MCPProtocol.METHOD_TOOLS_LIST:
                return handleToolsList(id);

            case MCPProtocol.METHOD_TOOLS_CALL:
                return handleToolsCall(id, params);

            case MCPProtocol.METHOD_PING:
                return MCPProtocol.buildResponse(id, new HashMap<>());

            default:
                return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_METHOD_NOT_FOUND, "Unknown method: " + method);
        }
    }

    private String handleInitialize(JsonElement id, JsonObject params) {
        // Generate session ID
        sessionId = UUID.randomUUID().toString();

        Map<String, Object> result = MCPProtocol.buildInitializeResult(SERVER_NAME, SERVER_VERSION);
        logger.info("MCP initialized, session: " + sessionId);
        return MCPProtocol.buildResponse(id, result);
    }

    private String handleToolsList(JsonElement id) {
        Map<String, Object> result = MCPProtocol.buildToolsListResult(toolHandler.getToolDefinitions());
        return MCPProtocol.buildResponse(id, result);
    }

    private String handleToolsCall(JsonElement id, JsonObject params) {
        if (params == null || !params.has("name")) {
            return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INVALID_PARAMS, "Missing tool name");
        }

        String toolName = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") ? params.get("arguments").getAsJsonObject() : new JsonObject();

        logger.info("Tool call: " + toolName + " with args: " + arguments);

        // Execute only tools that are explicitly safe on the HTTP thread.
        // Some read-only tools still access live game/UI state and must run on the game thread.
        if (toolHandler.isHttpThreadSafeTool(toolName)) {
            logger.info("Executing HTTP-thread-safe tool directly on HTTP thread: " + toolName);
            try {
                Map<String, Object> result;
                synchronized (toolExecutionLock) {
                    result = toolHandler.executeTool(toolName, arguments);
                }
                return MCPProtocol.buildResponse(id, result);
            } catch (Exception e) {
                logger.error("Error executing HTTP-thread-safe tool: " + toolName, e);
                return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INTERNAL, e.getMessage());
            }
        }

        // Queue the tool call for execution on the game thread
        PendingToolCall pending = new PendingToolCall(id, toolName, arguments);
        pendingToolCalls.add(pending);
        logger.info("Tool call queued for game thread, pending count: " + pendingToolCalls.size());

        // Wait for result (with timeout)
        try {
            Map<String, Object> result = pending.awaitResult(30, TimeUnit.SECONDS);
            if (result == null) {
                logger.error("Tool execution timeout - game thread did not process in 30 seconds");
                return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INTERNAL,
                    "Tool execution timeout - ensure the game is running and not paused");
            }
            logger.info("Tool call completed: " + toolName);
            return MCPProtocol.buildResponse(id, result);
        } catch (InterruptedException e) {
            return MCPProtocol.buildErrorResponse(id, MCPProtocol.ERROR_INTERNAL, "Tool execution interrupted");
        }
    }

    /**
     * Check if we should wait for ready_for_command before executing.
     * Returns true if we should wait (i.e., skip this frame), false if ready to execute.
     */
    private boolean shouldWaitForReady() {
        if (mcpthespire.GameStateListener.isWaitingForCommand()) {
            // Ready to execute, reset wait timer
            waitStartTime = 0;
            return false;
        }

        // Not ready - check if we've been waiting too long
        long now = System.currentTimeMillis();
        if (waitStartTime == 0) {
            waitStartTime = now;
            logger.info("Waiting for ready_for_command...");
        }

        if (now - waitStartTime > MAX_WAIT_FOR_READY_MS) {
            // Timeout - proceed anyway
            logger.warn("Timeout waiting for ready_for_command, proceeding anyway");
            waitStartTime = 0;
            return false;
        }

        // Still waiting
        return true;
    }

    /**
     * Process pending tool calls on the game thread.
     * This should be called from the game's update loop.
     */
    public void processPendingToolCalls() {
        // First, continue any pending batch execution
        if (pendingBatch != null) {
            if (pendingBatch.finishRequested) {
                // The last action already ran; wait until the game becomes ready
                // again so the final response reflects the settled post-action state.
                if (shouldWaitForReady()) {
                    return;
                }
                finishBatchExecution();
                return;
            }
            processBatchAction();
            return;
        }

        // Then, complete any pending mutating tool once the UI settles.
        if (pendingMutatingToolResult != null) {
            Map<String, Object> currentState = mcpthespire.GameStateConverter.getScreenOnlyState();
            pendingMutatingToolResult.observeState(currentState);

            if (shouldWaitForReady()) {
                return;
            }

            Map<String, Object> result = enrichWithState(pendingMutatingToolResult.result);
            pendingMutatingToolResult.pendingCall.complete(result);
            logger.info("Tool result added after state settled: " + pendingMutatingToolResult.toolName);
            pendingMutatingToolResult = null;
            waitStartTime = 0;
            return;
        }

        // Then, check for new tool calls
        PendingToolCall pending = pendingToolCalls.peek(); // peek first, don't remove yet
        if (pending != null) {
            // For game-modifying tools, wait for ready_for_command
            if (!toolHandler.isReadOnlyTool(pending.toolName)) {
                if (shouldWaitForReady()) {
                    return; // Not ready, try again next frame
                }
            }

            // Now remove from queue
            pendingToolCalls.poll();
            logger.info("Game thread processing tool: " + pending.toolName);

            // Special handling for execute_actions - use async batch execution
            if ("execute_actions".equals(pending.toolName)) {
                startBatchExecution(pending);
                return;
            }

            try {
                Map<String, Object> result;
                Map<String, Object> preActionState = null;
                if (!toolHandler.isReadOnlyTool(pending.toolName)) {
                    preActionState = mcpthespire.GameStateConverter.getScreenOnlyState();
                }
                synchronized (toolExecutionLock) {
                    result = toolHandler.executeTool(pending.toolName, pending.arguments);
                }
                if (!toolHandler.isReadOnlyTool(pending.toolName)) {
                    if (Boolean.TRUE.equals(result.get("isError"))) {
                        pending.complete(result);
                        logger.info("Tool result added to queue (error): " + pending.toolName);
                        return;
                    }
                    pendingMutatingToolResult = new PendingMutatingToolResult(pending, result, preActionState);
                    waitStartTime = 0;
                    return;
                }
                pending.complete(result);
                logger.info("Tool result added to queue");
            } catch (Exception e) {
                logger.error("Error executing tool: " + pending.toolName, e);
                pending.complete(MCPProtocol.buildToolCallResult("Error: " + e.getMessage(), true));
            }
        }
    }

    /**
     * Start a new batch execution.
     */
    private void startBatchExecution(PendingToolCall pendingCall) {
        JsonObject params = pendingCall.arguments;
        if (!params.has("actions") || !params.get("actions").isJsonArray()) {
            pendingCall.complete(MCPProtocol.buildToolCallResult("Error: 'actions' array is required", true));
            return;
        }

        com.google.gson.JsonArray actions = params.getAsJsonArray("actions");
        if (actions.size() == 0) {
            pendingCall.complete(MCPProtocol.buildToolCallResult("Error: 'actions' array is empty", true));
            return;
        }

        logger.info("Starting batch execution with " + actions.size() + " actions");
        pendingBatch = new PendingBatchExecution(actions, pendingCall);

        // Execute first action immediately
        processBatchAction();
    }

    /**
     * Process one action from the pending batch.
     * Called once per game frame, with rate limiting to ensure game state updates between actions.
     */
    private void processBatchAction() {
        if (pendingBatch == null) return;

        // Check if we should stop
        if (!pendingBatch.hasMoreActions()) {
            finishBatchExecution();
            return;
        }

        // Wait for ready_for_command (except for the first action which was already checked)
        if (pendingBatch.currentIndex > 0 && shouldWaitForReady()) {
            return; // Not ready, try again next frame
        }

        // Rate limit: ensure minimum time between batch actions
        // This gives the game time to process card queue, animations, etc.
        long now = System.currentTimeMillis();
        if (pendingBatch.currentIndex > 0 && (now - lastBatchActionTime) < MIN_BATCH_ACTION_INTERVAL_MS) {
            // Not enough time has passed since last action, skip this call
            return;
        }

        JsonObject action = pendingBatch.getCurrentAction();
        if (!action.has("action")) {
            pendingBatch.markError(null, "Missing 'action' field");
            finishBatchExecution();
            return;
        }

        String actionType = action.get("action").getAsString();
        logger.info("Batch executing action " + (pendingBatch.currentIndex + 1) + "/" + pendingBatch.actions.size() + ": " + actionType);

        try {
            PendingBatchExecution.ResolvedChoice resolvedChoice = null;
            if ("wait".equals(actionType)) {
                // Wait action doesn't need frame delay, just a sleep
                int waitMs = action.has("ms") ? action.get("ms").getAsInt() : 100;
                Thread.sleep(Math.min(waitMs, 500));
            } else {
                // For play_card with card_index, convert to card_uuid for stable resolution
                JsonObject processedAction = action;
                if ("play_card".equals(actionType) && action.has("card_index") &&
                    !action.get("card_index").isJsonNull() && pendingBatch.initialHandUuids != null) {
                    int originalIndex = action.get("card_index").getAsInt();
                    // Validate index against initial hand size
                    if (originalIndex < 1 || originalIndex > pendingBatch.initialHandUuids.size()) {
                        throw new mcpthespire.InvalidCommandException(
                            "card_index " + originalIndex + " out of bounds. " +
                            "Initial hand had " + pendingBatch.initialHandUuids.size() + " cards.");
                    }
                    java.util.UUID targetUuid = pendingBatch.initialHandUuids.get(originalIndex - 1);
                    // Create modified action with card_uuid instead of card_index
                    processedAction = new JsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : action.entrySet()) {
                        if (!"card_index".equals(entry.getKey())) {
                            processedAction.add(entry.getKey(), entry.getValue());
                        }
                    }
                    processedAction.addProperty("card_uuid", targetUuid.toString());
                    logger.info("Converted card_index " + originalIndex + " to card_uuid " + targetUuid);
                }
                // For choose with choice_index, resolve stable index by finding the original choice in current list
                if ("choose".equals(actionType) && processedAction.has("choice_index") &&
                    !processedAction.get("choice_index").isJsonNull() && pendingBatch.initialChoiceList != null) {
                    int originalIndex = processedAction.get("choice_index").getAsInt();
                    // Validate index against initial choice list size
                    if (originalIndex < 1 || originalIndex > pendingBatch.initialChoiceList.size()) {
                        throw new mcpthespire.InvalidCommandException(
                            "choice_index " + originalIndex + " out of bounds. " +
                            "Initial choices had " + pendingBatch.initialChoiceList.size() + " items: " +
                            pendingBatch.initialChoiceList);
                    }
                    // Find this choice in the current list to get the updated index
                    java.util.ArrayList<String> currentChoices = mcpthespire.ChoiceScreenUtils.getCurrentChoiceList();
                    resolvedChoice = pendingBatch.resolveChoice(originalIndex, currentChoices);
                    int newIndex = resolvedChoice.currentIndex;

                    if (newIndex == -1) {
                        throw new mcpthespire.InvalidCommandException(
                            "Choice no longer available: " + resolvedChoice.choiceName +
                            ". Current choices: " + currentChoices);
                    }

                    // Update choice_index to the new resolved index
                    JsonObject chooseAction = new JsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : processedAction.entrySet()) {
                        if (!"choice_index".equals(entry.getKey())) {
                            chooseAction.add(entry.getKey(), entry.getValue());
                        }
                    }
                    chooseAction.addProperty("choice_index", newIndex);
                    processedAction = chooseAction;
                    logger.info("Resolved choice_index " + originalIndex + " -> " + newIndex + " (choice: " + resolvedChoice.choiceName + ", occurrence: " + resolvedChoice.currentOccurrence + ")");
                }

                // For select_cards with indices in drop/keep, convert to UUIDs for stable resolution
                if ("select_cards".equals(actionType) && pendingBatch.initialHandUuids != null) {
                    processedAction = convertSelectCardsIndices(processedAction, pendingBatch.initialHandUuids);
                }

                // Execute the single action
                toolHandler.executeSingleBatchAction(actionType, processedAction);

                if (resolvedChoice != null) {
                    pendingBatch.recordResolvedChoice(resolvedChoice.choiceName, resolvedChoice.originalOccurrence);
                }
            }
            pendingBatch.markSuccess();
            lastBatchActionTime = System.currentTimeMillis();

            // Check if screen changed (stop execution if it did)
            pendingBatch.checkScreenChange();

            // If no more actions, request finish on NEXT frame
            // This gives the game one frame to process card queues, animations, etc.
            if (!pendingBatch.hasMoreActions()) {
                pendingBatch.finishRequested = true;
            }
            // Otherwise, next action will be processed in next frame
        } catch (Exception e) {
            pendingBatch.markError(actionType, e.getMessage());
            pendingBatch.finishRequested = true;
        }
    }

    /**
     * Finish batch execution and send result.
     */
    private void finishBatchExecution() {
        if (pendingBatch == null) return;

        int total = pendingBatch.actions.size();
        String message;

        if (pendingBatch.errorMessage == null) {
            if (pendingBatch.screenChanged && pendingBatch.successCount < total) {
                message = "OK " + pendingBatch.successCount + "/" + total + " (screen changed)";
            } else {
                message = "OK";
            }
        } else {
            message = "Error at action " + (pendingBatch.failedIndex + 1) + ": " + pendingBatch.errorMessage;
        }

        logger.info("Batch execution finished: " + pendingBatch.successCount + "/" + total);
        Map<String, Object> result = MCPProtocol.buildToolCallResult(message, pendingBatch.errorMessage != null);
        // Enrich with current game state so caller sees post-action state immediately
        result = enrichWithState(result);
        pendingBatch.pendingCall.complete(result);
        pendingBatch = null;
    }

    /**
     * Convert integer indices in select_cards drop/keep arrays to UUIDs for stable resolution.
     */
    private JsonObject convertSelectCardsIndices(JsonObject action, java.util.List<java.util.UUID> initialHandUuids)
            throws mcpthespire.InvalidCommandException {
        JsonObject result = new JsonObject();

        for (Map.Entry<String, com.google.gson.JsonElement> entry : action.entrySet()) {
            String key = entry.getKey();
            if (("drop".equals(key) || "keep".equals(key)) && entry.getValue().isJsonArray()) {
                // Convert indices to UUIDs in the array
                com.google.gson.JsonArray originalArray = entry.getValue().getAsJsonArray();
                com.google.gson.JsonArray convertedArray = new com.google.gson.JsonArray();

                for (com.google.gson.JsonElement elem : originalArray) {
                    if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                        // This is an index - convert to UUID
                        int idx = elem.getAsInt();
                        if (idx < 1 || idx > initialHandUuids.size()) {
                            throw new mcpthespire.InvalidCommandException(
                                "Card index " + idx + " out of bounds. Initial hand had " +
                                initialHandUuids.size() + " cards.");
                        }
                        // Convert to UUID string with special prefix so we can identify it later
                        convertedArray.add("uuid:" + initialHandUuids.get(idx - 1).toString());
                        logger.info("Converted card index " + idx + " to UUID: " + initialHandUuids.get(idx - 1));
                    } else {
                        // Keep strings as-is
                        convertedArray.add(elem);
                    }
                }
                result.add(key, convertedArray);
            } else {
                result.add(key, entry.getValue());
            }
        }

        return result;
    }

    /**
     * Check if there are pending tool calls or batch actions to process.
     */
    public boolean hasPendingToolCalls() {
        return !pendingToolCalls.isEmpty() || pendingBatch != null || pendingMutatingToolResult != null;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        running = false;
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /**
     * Enrich a tool result with current game state.
     * Appends state as a second text content item (JSON) so callers get
     * both the action result and the updated screen state in one response.
     */
    private Map<String, Object> enrichWithState(Map<String, Object> toolResult) {
        if (!mcpthespire.CommandExecutor.isInDungeon()) {
            return toolResult;
        }

        // Clone the result map so we don't mutate the original
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(toolResult);

        // Get the content list (clone it)
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> originalContent = (java.util.List<Map<String, Object>>) toolResult.get("content");
        if (originalContent == null) {
            originalContent = new java.util.ArrayList<>();
        }
        java.util.List<Map<String, Object>> newContent = new java.util.ArrayList<>(originalContent);

        // Add state as a second JSON text content item
        Map<String, Object> stateData = mcpthespire.GameStateConverter.getScreenOnlyState();
        Map<String, Object> stateContentItem = new HashMap<>();
        stateContentItem.put("type", "text");
        stateContentItem.put("text", gson.toJson(stateData));
        newContent.add(stateContentItem);

        enriched.put("content", newContent);
        return enriched;
    }

    /**
     * Represents a pending tool call waiting to be executed on the game thread.
     */
    private static class PendingToolCall {
        final JsonElement id;
        final String toolName;
        final JsonObject arguments;
        final BlockingQueue<Map<String, Object>> resultQueue;

        PendingToolCall(JsonElement id, String toolName, JsonObject arguments) {
            this.id = id;
            this.toolName = toolName;
            this.arguments = arguments;
            this.resultQueue = new LinkedBlockingQueue<>(1);
        }

        void complete(Map<String, Object> result) {
            resultQueue.offer(result);
        }

        Map<String, Object> awaitResult(long timeout, TimeUnit unit) throws InterruptedException {
            return resultQueue.poll(timeout, unit);
        }
    }

    /**
     * Represents a pending batch execution that spans multiple frames.
     */
    private static class PendingBatchExecution {
        final com.google.gson.JsonArray actions;
        final PendingToolCall pendingCall;
        int currentIndex;
        int successCount;
        int failedIndex;
        String failedAction;
        String errorMessage;
        boolean screenChanged;
        boolean finishRequested;
        mcpthespire.ChoiceScreenUtils.ChoiceType initialScreen;
        boolean initialInDungeon;
        // Initial hand UUIDs for stable card_index resolution
        java.util.List<java.util.UUID> initialHandUuids;
        // Initial choice list for stable choice_index resolution
        java.util.List<String> initialChoiceList;
        java.util.List<ResolvedChoiceRecord> resolvedChoices;

        PendingBatchExecution(com.google.gson.JsonArray actions, PendingToolCall pendingCall) {
            this.actions = actions;
            this.pendingCall = pendingCall;
            this.currentIndex = 0;
            this.successCount = 0;
            this.failedIndex = -1;
            this.failedAction = null;
            this.errorMessage = null;
            this.screenChanged = false;
            this.finishRequested = false;
            this.initialInDungeon = mcpthespire.CommandExecutor.isInDungeon();
            if (this.initialInDungeon) {
                this.initialScreen = mcpthespire.ChoiceScreenUtils.getCurrentChoiceType();
                // Capture initial hand state for stable index resolution
                this.initialHandUuids = new java.util.ArrayList<>();
                if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.player != null) {
                    for (com.megacrit.cardcrawl.cards.AbstractCard card :
                         com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand.group) {
                        this.initialHandUuids.add(card.uuid);
                    }
                }
                // Capture initial choice list for stable index resolution
                this.initialChoiceList = new java.util.ArrayList<>(
                    mcpthespire.ChoiceScreenUtils.getCurrentChoiceList());
            }
            this.resolvedChoices = new java.util.ArrayList<>();
        }

        boolean hasMoreActions() {
            return currentIndex < actions.size() && errorMessage == null && !screenChanged;
        }

        JsonObject getCurrentAction() {
            return actions.get(currentIndex).getAsJsonObject();
        }

        void markSuccess() {
            successCount++;
            currentIndex++;
        }

        void markError(String action, String error) {
            failedIndex = currentIndex;
            failedAction = action;
            errorMessage = error;
        }

        void checkScreenChange() {
            if (initialInDungeon && mcpthespire.CommandExecutor.isInDungeon()) {
                mcpthespire.ChoiceScreenUtils.ChoiceType currentScreen = mcpthespire.ChoiceScreenUtils.getCurrentChoiceType();
                if (initialScreen != currentScreen) {
                    screenChanged = true;
                }
            }
        }

        ResolvedChoice resolveChoice(int originalIndex, java.util.List<String> currentChoices) {
            String choiceName = initialChoiceList.get(originalIndex - 1);
            int originalOccurrence = getOccurrenceIndex(initialChoiceList, choiceName, originalIndex - 1);
            int removedEarlierMatches = 0;
            for (ResolvedChoiceRecord resolvedChoice : resolvedChoices) {
                if (resolvedChoice.choiceName.equals(choiceName)
                    && resolvedChoice.originalOccurrence < originalOccurrence) {
                    removedEarlierMatches++;
                }
            }

            int currentOccurrence = originalOccurrence - removedEarlierMatches;
            int currentIndex = findOccurrenceIndex(currentChoices, choiceName, currentOccurrence);
            return new ResolvedChoice(choiceName, originalOccurrence, currentOccurrence, currentIndex);
        }

        void recordResolvedChoice(String choiceName, int originalOccurrence) {
            resolvedChoices.add(new ResolvedChoiceRecord(choiceName, originalOccurrence));
        }

        private static int getOccurrenceIndex(java.util.List<String> choices, String choiceName, int upToIndex) {
            int occurrence = 0;
            for (int i = 0; i <= upToIndex; i++) {
                if (choiceName.equals(choices.get(i))) {
                    occurrence++;
                }
            }
            return occurrence;
        }

        private static int findOccurrenceIndex(java.util.List<String> choices, String choiceName, int targetOccurrence) {
            if (targetOccurrence <= 0) {
                return -1;
            }

            int occurrence = 0;
            for (int i = 0; i < choices.size(); i++) {
                if (choiceName.equals(choices.get(i))) {
                    occurrence++;
                    if (occurrence == targetOccurrence) {
                        return i + 1;
                    }
                }
            }
            return -1;
        }

        private static class ResolvedChoiceRecord {
            final String choiceName;
            final int originalOccurrence;

            ResolvedChoiceRecord(String choiceName, int originalOccurrence) {
                this.choiceName = choiceName;
                this.originalOccurrence = originalOccurrence;
            }
        }

        private static class ResolvedChoice {
            final String choiceName;
            final int originalOccurrence;
            final int currentOccurrence;
            final int currentIndex;

            ResolvedChoice(String choiceName, int originalOccurrence, int currentOccurrence, int currentIndex) {
                this.choiceName = choiceName;
                this.originalOccurrence = originalOccurrence;
                this.currentOccurrence = currentOccurrence;
                this.currentIndex = currentIndex;
            }
        }
    }

    /**
     * Represents a mutating tool that has executed but should not respond
     * until the game state has visibly settled.
     */
    private static class PendingMutatingToolResult {
        final String toolName;
        final PendingToolCall pendingCall;
        final Map<String, Object> result;
        final Map<String, Object> preActionState;
        Map<String, Object> lastObservedChangedState;
        int stableChangedStateFrames;

        PendingMutatingToolResult(PendingToolCall pendingCall, Map<String, Object> result, Map<String, Object> preActionState) {
            this.toolName = pendingCall.toolName;
            this.pendingCall = pendingCall;
            this.result = result;
            this.preActionState = preActionState;
            this.lastObservedChangedState = null;
            this.stableChangedStateFrames = 0;
        }

        void observeState(Map<String, Object> currentState) {
            if (preActionState.equals(currentState)) {
                return;
            }

            if (lastObservedChangedState == null || !lastObservedChangedState.equals(currentState)) {
                lastObservedChangedState = new java.util.LinkedHashMap<>(currentState);
                stableChangedStateFrames = 0;
                return;
            }

            stableChangedStateFrames++;
        }

        boolean canCompleteFromStateChange() {
            return lastObservedChangedState != null && stableChangedStateFrames >= 1;
        }
    }
}
