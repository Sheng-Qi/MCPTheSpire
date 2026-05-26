package mcpthespire.mcp;

import basemod.ReflectionHacks;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import mcpthespire.ChoiceScreenUtils;
import mcpthespire.CommandExecutor;
import mcpthespire.GameStateConverter;
import mcpthespire.InvalidCommandException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class AgentProtocolService {

    private static final Logger logger = LogManager.getLogger(AgentProtocolService.class.getName());
    private static final Gson gson = new Gson();

    private final MCPToolHandler toolHandler;

    private String currentRunId;
    private String currentRunFingerprint;
    private int runCounter;
    private int decisionCounter;
    private int sharedContextVersion;
    private String lastSharedContextHash;
    private Map<String, Object> lastTransitionDiff;
    private DecisionFrame lastDecisionFrame;

    AgentProtocolService(MCPToolHandler toolHandler) {
        this.toolHandler = toolHandler;
        this.currentRunId = null;
        this.currentRunFingerprint = null;
        this.runCounter = 0;
        this.decisionCounter = 0;
        this.sharedContextVersion = 0;
        this.lastSharedContextHash = null;
        this.lastTransitionDiff = null;
        this.lastDecisionFrame = null;
    }

    synchronized Map<String, Object> getDecisionBundle(JsonObject args) {
        ensureRunState();

        String detailLevel = getStringArg(args, "detail_level", "full");
        String contextMode = getStringArg(args, "context_mode", "full_inline");
        String actionView = getStringArg(args, "action_view", "factorized");
        boolean includeDiff = getBooleanArg(args, "include_diff", true);
        boolean includeNextMapGraph = getBooleanArg(args, "include_next_map_graph", false);

        DecisionFrame frame = buildDecisionFrame(detailLevel, contextMode, actionView, includeDiff, includeNextMapGraph);
        lastDecisionFrame = frame;
        logRecord("decision_bundle", frame.bundle);
        return frame.bundle;
    }

    synchronized PreparedAgentAction prepareAndExecuteAgentAction(JsonObject args) throws InvalidCommandException {
        ensureRunState();

        if (lastDecisionFrame == null) {
            throw new InvalidCommandException("execute_agent_action requires a prior get_decision_bundle call");
        }

        String decisionId = requireStringArg(args, "decision_id");
        if (!lastDecisionFrame.decisionId.equals(decisionId)) {
            throw new InvalidCommandException(
                "decision_id mismatch. Expected " + lastDecisionFrame.decisionId + ", got " + decisionId);
        }

        String actionGroupId = requireStringArg(args, "action_group_id");
        ActionGroupRuntime runtime = lastDecisionFrame.actionsById.get(actionGroupId);
        if (runtime == null) {
            throw new InvalidCommandException("Unknown action_group_id: " + actionGroupId);
        }

        JsonObject bindingsObject = new JsonObject();
        if (args.has("bindings") && args.get("bindings").isJsonObject()) {
            bindingsObject = args.getAsJsonObject("bindings");
        }
        boolean returnNextBundle = getBooleanArg(args, "return_next_bundle", true);

        JsonObject toolArguments = runtime.buildToolArguments(bindingsObject);
        Map<String, Object> toolResult = toolHandler.executeTool(runtime.toolName, toolArguments);

        PreparedAgentAction prepared = new PreparedAgentAction();
        prepared.sourceFrame = lastDecisionFrame;
        prepared.runtime = runtime;
        prepared.returnNextBundle = returnNextBundle;
        prepared.toolArguments = toolArguments;
        prepared.toolResult = toolResult;
        prepared.bindings = jsonObjectToMap(bindingsObject);
        prepared.appliedAction = runtime.toAppliedAction(prepared.bindings);
        return prepared;
    }

    synchronized Map<String, Object> finalizeAgentAction(PreparedAgentAction preparedAction) {
        ensureRunState();

        DecisionFrame nextFrame = buildDecisionFrame(
            preparedAction.sourceFrame.detailLevel,
            preparedAction.sourceFrame.contextMode,
            preparedAction.sourceFrame.actionView,
            false,
            preparedAction.sourceFrame.includeNextMapGraph);

        Map<String, Object> transitionDiff = buildTransitionDiff(preparedAction.sourceFrame, nextFrame, preparedAction);
        lastTransitionDiff = transitionDiff;

        if (preparedAction.returnNextBundle) {
            nextFrame.bundle.put("transition_diff", transitionDiff);
            lastDecisionFrame = nextFrame;
        } else {
            lastDecisionFrame = null;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", Boolean.TRUE.equals(preparedAction.toolResult.get("isError")) ? "error" : "ok");
        response.put("run_id", preparedAction.returnNextBundle ? nextFrame.runId : preparedAction.sourceFrame.runId);
        response.put("applied_action", preparedAction.appliedAction);
        response.put("transition_diff", transitionDiff);
        response.put("tool_result", extractPrimaryText(preparedAction.toolResult));
        if (preparedAction.returnNextBundle) {
            response.put("next_decision_bundle", nextFrame.bundle);
        }

        logRecord("action_result", response);
        return response;
    }

    synchronized Map<String, Object> getStaticReference(JsonObject args) throws InvalidCommandException {
        String referenceType = requireStringArg(args, "reference_type").toLowerCase(Locale.ROOT);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reference_type", referenceType);

        List<String> ids = new ArrayList<String>();
        if (args.has("ids") && args.get("ids").isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray("ids")) {
                ids.add(element.getAsString());
            }
        }

        if ("cards".equals(referenceType)) {
            List<Object> cards = new ArrayList<Object>();
            for (String id : ids) {
                cards.add(GameStateConverter.getCardInfo(id));
            }
            response.put("cards", cards);
            return response;
        }

        if ("relics".equals(referenceType)) {
            List<Object> relics = new ArrayList<Object>();
            for (String id : ids) {
                relics.add(GameStateConverter.getRelicInfo(id));
            }
            response.put("relics", relics);
            return response;
        }

        throw new InvalidCommandException("Unsupported reference_type: " + referenceType);
    }

    private DecisionFrame buildDecisionFrame(String detailLevel,
                                             String contextMode,
                                             String actionView,
                                             boolean includeDiff,
                                             boolean includeNextMapGraph) {
        Map<String, Object> sharedContext = GameStateConverter.getSharedContext(includeNextMapGraph);
        Map<String, Object> decisionContext = GameStateConverter.getDecisionContext();
        Map<String, Object> derivedContext = GameStateConverter.getDerivedContext();
        String normalizedScreenType = getNormalizedScreenType();
        String decisionType = GameStateConverter.getDecisionType();

        applyDetailLevel(detailLevel, sharedContext, decisionContext);

        String sharedHash = hashForMap(sharedContext);
        if (!sharedHash.equals(lastSharedContextHash)) {
            sharedContextVersion += 1;
            lastSharedContextHash = sharedHash;
        }
        String decisionHash = hashForMap(decisionContext);
        String decisionId = String.format(Locale.ROOT, "dec_%06d", ++decisionCounter);

        LegalActionBuild legalActions = buildLegalActions(actionView, normalizedScreenType, decisionType, decisionContext);

        Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        bundle.put("run_id", currentRunId);
        bundle.put("decision_id", decisionId);
        bundle.put("screen_type", normalizedScreenType);
        bundle.put("decision_type", decisionType);
        bundle.put("shared_context_version", sharedContextVersion);
        bundle.put("decision_context_version", decisionCounter);

        if ("session_ref_plus_delta".equals(contextMode)
            && lastDecisionFrame != null
            && sharedHash.equals(lastDecisionFrame.sharedContextHash)) {
            bundle.put("context_mode", "session_ref_plus_delta");
            Map<String, Object> sharedContextRef = new LinkedHashMap<String, Object>();
            sharedContextRef.put("version", sharedContextVersion);
            sharedContextRef.put("hash", sharedHash);
            bundle.put("shared_context_ref", sharedContextRef);
            bundle.put("shared_context_delta", new LinkedHashMap<String, Object>());
        } else {
            bundle.put("context_mode", "full_inline");
            bundle.put("shared_context", sharedContext);
        }

        bundle.put("decision_context", decisionContext);
        bundle.put("derived_context", derivedContext);
        bundle.put("legal_actions", legalActions.serialized);

        if (includeDiff && lastTransitionDiff != null) {
            bundle.put("transition_diff", lastTransitionDiff);
        }

        Map<String, Object> hashes = new LinkedHashMap<String, Object>();
        hashes.put("shared_context_hash", sharedHash);
        hashes.put("decision_context_hash", decisionHash);
        bundle.put("hashes", hashes);

        DecisionFrame frame = new DecisionFrame();
        frame.runId = currentRunId;
        frame.decisionId = decisionId;
        frame.screenType = normalizedScreenType;
        frame.decisionType = decisionType;
        frame.detailLevel = detailLevel;
        frame.contextMode = contextMode;
        frame.actionView = actionView;
        frame.includeNextMapGraph = includeNextMapGraph;
        frame.sharedContextHash = sharedHash;
        frame.decisionContextHash = decisionHash;
        frame.sharedContext = sharedContext;
        frame.decisionContext = decisionContext;
        frame.bundle = bundle;
        frame.actionsById = legalActions.actionsById;
        return frame;
    }

    private LegalActionBuild buildLegalActions(String actionView,
                                               String normalizedScreenType,
                                               String decisionType,
                                               Map<String, Object> decisionContext) {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Map<String, ActionGroupRuntime> runtimes = new LinkedHashMap<String, ActionGroupRuntime>();

        if (!CommandExecutor.isInDungeon()) {
            buildMenuActions(groups, runtimes);
        } else if (AbstractDungeon.getCurrRoom().phase.equals(com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT)
                   && !AbstractDungeon.isScreenUp) {
            buildCombatActions(groups, runtimes);
        } else {
            buildChoiceScreenActions(groups, runtimes, normalizedScreenType, decisionType, decisionContext);
        }

        Map<String, Object> legalActions = new LinkedHashMap<String, Object>();
        legalActions.put("factorized_groups_available", true);
        legalActions.put("atomic_view_available", true);

        if ("atomic".equals(actionView)) {
            legalActions.put("view", "atomic");
            legalActions.put("actions", expandAtomicActions(groups));
        } else {
            legalActions.put("view", "factorized");
            legalActions.put("groups", groups);
        }

        LegalActionBuild build = new LegalActionBuild();
        build.serialized = legalActions;
        build.actionsById = runtimes;
        return build;
    }

    private void buildMenuActions(List<Map<String, Object>> groups, Map<String, ActionGroupRuntime> runtimes) {
        ActionGroupRuntime startRuntime = new ActionGroupRuntime();
        startRuntime.actionGroupId = "g_start_game";
        startRuntime.kind = "start_game";
        startRuntime.toolName = "start_game";
        startRuntime.label = "Start Game";
        startRuntime.parameterName = "character";
        startRuntime.optionalBindingNames.add("ascension");
        startRuntime.optionalBindingNames.add("seed");
        startRuntime.choiceEntityToValue.put("character:IRONCLAD", "IRONCLAD");
        startRuntime.choiceEntityToValue.put("character:SILENT", "SILENT");
        startRuntime.choiceEntityToValue.put("character:DEFECT", "DEFECT");
        startRuntime.choiceEntityToValue.put("character:WATCHER", "WATCHER");

        Map<String, Object> startGroup = new LinkedHashMap<String, Object>();
        startGroup.put("action_group_id", startRuntime.actionGroupId);
        startGroup.put("kind", startRuntime.kind);
        startGroup.put("label", startRuntime.label);
        List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
        Map<String, Object> characterParameter = new LinkedHashMap<String, Object>();
        characterParameter.put("name", "character");
        characterParameter.put("required", true);
        characterParameter.put("selection_type", "character");
        List<Map<String, Object>> characterChoices = new ArrayList<Map<String, Object>>();
        characterChoices.add(buildChoiceOption("character:IRONCLAD", "IRONCLAD"));
        characterChoices.add(buildChoiceOption("character:SILENT", "SILENT"));
        characterChoices.add(buildChoiceOption("character:DEFECT", "DEFECT"));
        characterChoices.add(buildChoiceOption("character:WATCHER", "WATCHER"));
        characterParameter.put("choices", characterChoices);
        parameters.add(characterParameter);
        startGroup.put("parameters", parameters);
        groups.add(startGroup);
        runtimes.put(startRuntime.actionGroupId, startRuntime);

        if (CardCrawlGame.characterManager.anySaveFileExists()) {
            addSimpleAction(groups, runtimes, "g_continue_game", "continue_game", "continue_game", "Continue Game");
            addSimpleAction(groups, runtimes, "g_abandon_run", "abandon_run", "abandon_run", "Abandon Run");
        }
    }

    private void buildCombatActions(List<Map<String, Object>> groups, Map<String, ActionGroupRuntime> runtimes) {
        List<Map<String, Object>> targetChoices = buildEnemyTargetChoices();

        for (int i = 0; i < AbstractDungeon.player.hand.group.size(); i++) {
            AbstractCard card = AbstractDungeon.player.hand.group.get(i);
            AbstractMonster defaultTarget = getDefaultTarget(card);
            boolean canUse = card.canUse(AbstractDungeon.player, defaultTarget);
            if (!canUse) {
                continue;
            }

            ActionGroupRuntime runtime = new ActionGroupRuntime();
            runtime.actionGroupId = "g_play_card:" + card.uuid.toString();
            runtime.kind = "play_card";
            runtime.toolName = "play_card";
            runtime.label = "Play " + card.name;
            runtime.cardUuid = card.uuid.toString();
            runtime.requiresTarget = requiresEnemyTarget(card);

            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("action_group_id", runtime.actionGroupId);
            group.put("kind", runtime.kind);
            group.put("label", runtime.label);

            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("zone", "hand");
            source.put("hand_index", i + 1);
            source.put("card_entity_id", GameStateConverter.getCardEntityId(card));
            source.put("card_name", card.name);
            source.put("current_cost", card.costForTurn);
            source.put("base_cost", card.cost);
            group.put("source", source);

            List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
            if (runtime.requiresTarget) {
                Map<String, Object> parameter = new LinkedHashMap<String, Object>();
                parameter.put("name", "target");
                parameter.put("required", true);
                parameter.put("selection_type", "enemy");
                parameter.put("choices", targetChoices);
                parameters.add(parameter);
            }
            group.put("parameters", parameters);

            groups.add(group);
            runtimes.put(runtime.actionGroupId, runtime);
        }

        for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
            AbstractPotion potion = AbstractDungeon.player.potions.get(i);
            if (potion.ID.equals("Potion Slot") || !potion.canUse()) {
                continue;
            }

            ActionGroupRuntime runtime = new ActionGroupRuntime();
            runtime.actionGroupId = "g_use_potion:" + (i + 1);
            runtime.kind = "use_potion";
            runtime.toolName = "use_potion";
            runtime.label = "Use " + potion.name;
            runtime.potionSlot = i + 1;
            runtime.requiresTarget = potion.isThrown;

            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("action_group_id", runtime.actionGroupId);
            group.put("kind", runtime.kind);
            group.put("label", runtime.label);

            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("zone", "potions");
            source.put("slot", i + 1);
            source.put("potion_entity_id", GameStateConverter.getPotionSlotEntityId(potion, i + 1));
            source.put("potion_name", potion.name);
            group.put("source", source);

            List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
            if (runtime.requiresTarget) {
                Map<String, Object> parameter = new LinkedHashMap<String, Object>();
                parameter.put("name", "target");
                parameter.put("required", true);
                parameter.put("selection_type", "enemy");
                parameter.put("choices", targetChoices);
                parameters.add(parameter);
            }
            group.put("parameters", parameters);

            groups.add(group);
            runtimes.put(runtime.actionGroupId, runtime);
        }

        if (CommandExecutor.isEndCommandAvailable()) {
            addSimpleAction(groups, runtimes, "g_end_turn", "end_turn", "end_turn", "End Turn");
        }
    }

    private void buildChoiceScreenActions(List<Map<String, Object>> groups,
                                          Map<String, ActionGroupRuntime> runtimes,
                                          String normalizedScreenType,
                                          String decisionType,
                                          Map<String, Object> decisionContext) {
        if ("SHOP_SCREEN".equals(normalizedScreenType)) {
            buildShopScreenActions(groups, runtimes, decisionContext);
        }

        if (CommandExecutor.isChooseCommandAvailable()) {
            if ("SHOP_SCREEN".equals(normalizedScreenType) && !groups.isEmpty()) {
                // Shop actions are clearer as one action per purchase/purge option.
            } else {
            ActionGroupRuntime runtime = new ActionGroupRuntime();
            runtime.actionGroupId = "g_choose:" + normalizedScreenType.toLowerCase(Locale.ROOT);
            runtime.kind = buildChoiceKind(normalizedScreenType);
            runtime.toolName = "choose";
            runtime.label = buildChoiceLabel(decisionType);
            runtime.parameterName = "choice";

            List<Map<String, Object>> choiceOptions = new ArrayList<Map<String, Object>>();
            ArrayList<String> choiceList = ChoiceScreenUtils.getCurrentChoiceList();
            for (int i = 0; i < choiceList.size(); i++) {
                String entityId = "choice:" + normalizedScreenType.toLowerCase(Locale.ROOT) + ":" + (i + 1);
                runtime.choiceEntityToIndex.put(entityId, i + 1);
                choiceOptions.add(buildChoiceOption(entityId, choiceList.get(i)));
            }

            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("action_group_id", runtime.actionGroupId);
            group.put("kind", runtime.kind);
            group.put("label", runtime.label);

            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("screen_type", normalizedScreenType);
            source.put("decision_type", decisionType);
            group.put("source", source);

            List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
            Map<String, Object> parameter = new LinkedHashMap<String, Object>();
            parameter.put("name", "choice");
            parameter.put("required", true);
            parameter.put("selection_type", normalizedScreenType.toLowerCase(Locale.ROOT));
            parameter.put("choices", choiceOptions);
            parameters.add(parameter);
            group.put("parameters", parameters);

            groups.add(group);
            runtimes.put(runtime.actionGroupId, runtime);
            }
        }

        if (ChoiceScreenUtils.isConfirmButtonAvailable()) {
            String toolName = ChoiceScreenUtils.getConfirmButtonText().equals("confirm") ? "confirm" : "proceed";
            String label = toolName.equals("confirm") ? "Confirm" : "Proceed";
            addSimpleAction(groups, runtimes, "g_" + toolName, toolName, toolName, label);
        }

        if (ChoiceScreenUtils.isCancelButtonAvailable()) {
            String buttonText = ChoiceScreenUtils.getCancelButtonText();
            String toolName = "skip".equals(buttonText) ? "skip" : "cancel";
            String label = "skip".equals(buttonText) ? "Skip" : "Cancel";
            addSimpleAction(groups, runtimes, "g_" + toolName, toolName, toolName, label);
        }
    }

    private void buildShopScreenActions(List<Map<String, Object>> groups,
                                        Map<String, ActionGroupRuntime> runtimes,
                                        Map<String, Object> decisionContext) {
        Object screenStateObject = decisionContext.get("screen_state");
        if (!(screenStateObject instanceof Map)) {
            return;
        }

        Map<String, Object> screenState = castMap(screenStateObject);
        Object availableActionsObject = screenState.get("available_shop_actions");
        if (!(availableActionsObject instanceof List)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> availableActions = (List<Object>) availableActionsObject;
        for (Object actionObject : availableActions) {
            if (!(actionObject instanceof Map)) {
                continue;
            }

            Map<String, Object> action = castMap(actionObject);
            Integer choiceIndex = asInteger(action.get("choice_index"));
            String entityId = String.valueOf(action.get("entity_id"));
            String kind = String.valueOf(action.get("kind"));
            String label = String.valueOf(action.get("label"));
            if (choiceIndex == null || entityId == null || kind == null || label == null) {
                continue;
            }

            ActionGroupRuntime runtime = new ActionGroupRuntime();
            runtime.actionGroupId = "g_" + entityId;
            runtime.kind = kind;
            runtime.toolName = "choose";
            runtime.label = label;
            runtime.fixedChoiceIndex = choiceIndex;

            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("action_group_id", runtime.actionGroupId);
            group.put("kind", runtime.kind);
            group.put("label", runtime.label);

            Map<String, Object> source = new LinkedHashMap<String, Object>(action);
            source.remove("label");
            source.remove("choice_index");
            group.put("source", source);
            group.put("parameters", new ArrayList<Object>());

            groups.add(group);
            runtimes.put(runtime.actionGroupId, runtime);
        }
    }

    private void addSimpleAction(List<Map<String, Object>> groups,
                                 Map<String, ActionGroupRuntime> runtimes,
                                 String actionGroupId,
                                 String kind,
                                 String toolName,
                                 String label) {
        ActionGroupRuntime runtime = new ActionGroupRuntime();
        runtime.actionGroupId = actionGroupId;
        runtime.kind = kind;
        runtime.toolName = toolName;
        runtime.label = label;

        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("action_group_id", actionGroupId);
        group.put("kind", kind);
        group.put("label", label);
        group.put("parameters", new ArrayList<Object>());

        groups.add(group);
        runtimes.put(actionGroupId, runtime);
    }

    private List<Map<String, Object>> buildEnemyTargetChoices() {
        List<Map<String, Object>> choices = new ArrayList<Map<String, Object>>();
        for (AbstractMonster monster : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (!monster.isDeadOrEscaped()) {
                choices.add(buildChoiceOption(GameStateConverter.getMonsterEntityId(monster), monster.name));
            }
        }
        return choices;
    }

    private AbstractMonster getDefaultTarget(AbstractCard card) {
        if (!requiresEnemyTarget(card)) {
            return null;
        }

        for (AbstractMonster monster : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (!monster.isDeadOrEscaped()) {
                return monster;
            }
        }
        return null;
    }

    private boolean requiresEnemyTarget(AbstractCard card) {
        return card.target == AbstractCard.CardTarget.ENEMY || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
    }

    private Map<String, Object> buildChoiceOption(String entityId, String label) {
        Map<String, Object> option = new LinkedHashMap<String, Object>();
        option.put("entity_id", entityId);
        option.put("label", label);
        return option;
    }

    private List<Map<String, Object>> expandAtomicActions(List<Map<String, Object>> factorizedGroups) {
        List<Map<String, Object>> atomicActions = new ArrayList<Map<String, Object>>();

        for (Map<String, Object> group : factorizedGroups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parameters = (List<Map<String, Object>>) group.get("parameters");
            if (parameters == null || parameters.isEmpty()) {
                Map<String, Object> atomic = new LinkedHashMap<String, Object>();
                atomic.put("action_id", group.get("action_group_id"));
                atomic.put("action_group_id", group.get("action_group_id"));
                atomic.put("kind", group.get("kind"));
                atomic.put("label", group.get("label"));
                atomic.put("bindings", new LinkedHashMap<String, Object>());
                atomicActions.add(atomic);
                continue;
            }

            if (parameters.size() == 1) {
                Map<String, Object> parameter = parameters.get(0);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) parameter.get("choices");
                if (choices != null) {
                    for (Map<String, Object> choice : choices) {
                        Map<String, Object> atomic = new LinkedHashMap<String, Object>();
                        String entityId = (String) choice.get("entity_id");
                        atomic.put("action_id", group.get("action_group_id") + ":" + entityId);
                        atomic.put("action_group_id", group.get("action_group_id"));
                        atomic.put("kind", group.get("kind"));
                        atomic.put("label", group.get("label") + " -> " + choice.get("label"));
                        Map<String, Object> bindings = new LinkedHashMap<String, Object>();
                        bindings.put((String) parameter.get("name"), entityId);
                        atomic.put("bindings", bindings);
                        atomicActions.add(atomic);
                    }
                }
            }
        }

        return atomicActions;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> buildTransitionDiff(DecisionFrame before,
                                                    DecisionFrame after,
                                                    PreparedAgentAction preparedAction) {
        Map<String, Map<String, Object>> beforeEntities = collectEntityStates(before);
        Map<String, Map<String, Object>> afterEntities = collectEntityStates(after);

        List<Map<String, Object>> changedEntities = new ArrayList<Map<String, Object>>();
        List<String> entityIds = new ArrayList<String>();
        entityIds.addAll(beforeEntities.keySet());
        for (String entityId : afterEntities.keySet()) {
            if (!entityIds.contains(entityId)) {
                entityIds.add(entityId);
            }
        }

        for (String entityId : entityIds) {
            Map<String, Object> beforeState = beforeEntities.get(entityId);
            Map<String, Object> afterState = afterEntities.get(entityId);
            if (statesEqual(beforeState, afterState)) {
                continue;
            }

            Map<String, Object> diffEntry = new LinkedHashMap<String, Object>();
            diffEntry.put("entity_id", entityId);
            diffEntry.put("fields", buildFieldDiff(beforeState, afterState));
            changedEntities.add(diffEntry);
        }

        List<Map<String, Object>> zoneMoves = buildZoneMoves(before, after);

        List<String> events = new ArrayList<String>();
        events.add(preparedAction.runtime.label);
        for (Map<String, Object> entityDiff : changedEntities) {
            Map<String, Object> fields = castMap(entityDiff.get("fields"));
            if (fields.containsKey("current_hp")) {
                Map<String, Object> hpDiff = castMap(fields.get("current_hp"));
                events.add(entityDiff.get("entity_id") + " current_hp: " + hpDiff.get("from") + " -> " + hpDiff.get("to"));
            }
            if (fields.containsKey("block")) {
                Map<String, Object> blockDiff = castMap(fields.get("block"));
                events.add(entityDiff.get("entity_id") + " block: " + blockDiff.get("from") + " -> " + blockDiff.get("to"));
            }
            if (fields.containsKey("powers")) {
                events.add(entityDiff.get("entity_id") + " powers changed");
            }
        }

        Map<String, Object> diff = new LinkedHashMap<String, Object>();
        diff.put("changed_entities", changedEntities);
        diff.put("zone_moves", zoneMoves);
        diff.put("events", events);
        return diff;
    }

    private Map<String, Map<String, Object>> collectEntityStates(DecisionFrame frame) {
        Map<String, Map<String, Object>> entities = new LinkedHashMap<String, Map<String, Object>>();

        Object playerObject = frame.decisionContext.get("player");
        if (playerObject instanceof Map) {
            Map<String, Object> player = castMap(playerObject);
            entities.put("player", new LinkedHashMap<String, Object>(player));
        }

        addEntitiesFromList(entities, frame.decisionContext.get("monsters"));
        addEntitiesFromList(entities, frame.sharedContext.get("deck"));
        addEntitiesFromList(entities, frame.sharedContext.get("relics"));
        addEntitiesFromList(entities, frame.sharedContext.get("potions"));
        addEntitiesFromList(entities, frame.decisionContext.get("hand"));
        addEntitiesFromList(entities, frame.decisionContext.get("draw_pile"));
        addEntitiesFromList(entities, frame.decisionContext.get("discard_pile"));
        addEntitiesFromList(entities, frame.decisionContext.get("exhaust_pile"));
        addEntitiesFromList(entities, frame.decisionContext.get("limbo"));

        return entities;
    }

    private void addEntitiesFromList(Map<String, Map<String, Object>> entities, Object listObject) {
        if (!(listObject instanceof List)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) listObject;
        for (Object item : items) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> entity = castMap(item);
            Object entityId = entity.get("entity_id");
            if (entityId instanceof String) {
                entities.put((String) entityId, new LinkedHashMap<String, Object>(entity));
            }
        }
    }

    private List<Map<String, Object>> buildZoneMoves(DecisionFrame before, DecisionFrame after) {
        Map<String, String> beforeZones = collectCardZones(before);
        Map<String, String> afterZones = collectCardZones(after);
        List<Map<String, Object>> zoneMoves = new ArrayList<Map<String, Object>>();

        for (Map.Entry<String, String> entry : beforeZones.entrySet()) {
            String cardId = entry.getKey();
            String beforeZone = entry.getValue();
            String afterZone = afterZones.get(cardId);
            if (afterZone != null && !beforeZone.equals(afterZone)) {
                Map<String, Object> move = new LinkedHashMap<String, Object>();
                move.put("card_entity_id", cardId);
                move.put("from", beforeZone);
                move.put("to", afterZone);
                zoneMoves.add(move);
            }
        }

        return zoneMoves;
    }

    private Map<String, String> collectCardZones(DecisionFrame frame) {
        Map<String, String> zones = new LinkedHashMap<String, String>();
        addZoneEntries(zones, frame.decisionContext.get("hand"), "hand");
        addZoneEntries(zones, frame.decisionContext.get("draw_pile"), "draw_pile");
        addZoneEntries(zones, frame.decisionContext.get("discard_pile"), "discard_pile");
        addZoneEntries(zones, frame.decisionContext.get("exhaust_pile"), "exhaust_pile");
        addZoneEntries(zones, frame.decisionContext.get("limbo"), "limbo");
        return zones;
    }

    private void addZoneEntries(Map<String, String> zones, Object zoneObject, String zoneName) {
        if (!(zoneObject instanceof List)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> cards = (List<Object>) zoneObject;
        for (Object cardObject : cards) {
            if (!(cardObject instanceof Map)) {
                continue;
            }
            Map<String, Object> card = castMap(cardObject);
            Object entityId = card.get("entity_id");
            if (entityId instanceof String) {
                zones.put((String) entityId, zoneName);
            }
        }
    }

    private Map<String, Object> buildFieldDiff(Map<String, Object> beforeState, Map<String, Object> afterState) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        List<String> keys = new ArrayList<String>();
        if (beforeState != null) {
            keys.addAll(beforeState.keySet());
        }
        if (afterState != null) {
            for (String key : afterState.keySet()) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }

        for (String key : keys) {
            Object beforeValue = beforeState == null ? null : beforeState.get(key);
            Object afterValue = afterState == null ? null : afterState.get(key);
            if (statesEqual(beforeValue, afterValue)) {
                continue;
            }
            Map<String, Object> diff = new LinkedHashMap<String, Object>();
            diff.put("from", beforeValue);
            diff.put("to", afterValue);
            fields.put(key, diff);
        }

        return fields;
    }

    private void applyDetailLevel(String detailLevel,
                                  Map<String, Object> sharedContext,
                                  Map<String, Object> decisionContext) {
        if (!"compact".equals(detailLevel)) {
            return;
        }

        decisionContext.remove("draw_pile");
        decisionContext.remove("discard_pile");
        decisionContext.remove("exhaust_pile");
        decisionContext.remove("limbo");

        Object mapObject = sharedContext.get("map");
        if (mapObject instanceof Map) {
            castMap(mapObject).remove("graph");
        }
    }

    private String getNormalizedScreenType() {
        if (!CommandExecutor.isInDungeon()) {
            return "MAIN_MENU";
        }

        ChoiceScreenUtils.ChoiceType choiceType = ChoiceScreenUtils.getCurrentChoiceType();
        if (AbstractDungeon.getCurrRoom().phase.equals(com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT)
            && choiceType == ChoiceScreenUtils.ChoiceType.NONE) {
            return "COMBAT";
        }
        return choiceType.name();
    }

    private String buildChoiceKind(String normalizedScreenType) {
        if ("MAP".equals(normalizedScreenType)) {
            return "choose_map_node";
        }
        if ("CARD_REWARD".equals(normalizedScreenType)) {
            return "pick_reward_card";
        }
        if ("COMBAT_REWARD".equals(normalizedScreenType)) {
            return "collect_reward";
        }
        if ("BOSS_REWARD".equals(normalizedScreenType)) {
            return "pick_boss_relic";
        }
        return "choose_option";
    }

    private String buildChoiceLabel(String decisionType) {
        if ("map_path_choice".equals(decisionType)) {
            return "Choose Map Node";
        }
        if ("card_reward_choice".equals(decisionType)) {
            return "Pick Card Reward";
        }
        if ("combat_reward_choice".equals(decisionType)) {
            return "Collect Reward";
        }
        if ("boss_reward_choice".equals(decisionType)) {
            return "Pick Boss Relic";
        }
        return "Choose Option";
    }

    private void ensureRunState() {
        if (!CommandExecutor.isInDungeon()) {
            String fingerprint = "menu|" + CardCrawlGame.characterManager.anySaveFileExists();
            if (currentRunFingerprint == null || !currentRunFingerprint.equals(fingerprint)) {
                currentRunFingerprint = fingerprint;
                currentRunId = buildRunId("menu");
                decisionCounter = 0;
                sharedContextVersion = 0;
                lastSharedContextHash = null;
                lastDecisionFrame = null;
                lastTransitionDiff = null;
            }
            return;
        }

        String fingerprint = AbstractDungeon.player.chosenClass.name()
            + "|" + AbstractDungeon.ascensionLevel
            + "|" + AbstractDungeon.floorNum
            + "|" + String.valueOf(com.megacrit.cardcrawl.core.Settings.seed);

        if (currentRunFingerprint == null) {
            currentRunFingerprint = fingerprint;
            currentRunId = buildRunId("run");
            decisionCounter = 0;
            sharedContextVersion = 0;
            lastSharedContextHash = null;
            lastDecisionFrame = null;
            lastTransitionDiff = null;
            return;
        }

        if (!AbstractDungeon.player.chosenClass.name().equals(extractFingerprintPart(currentRunFingerprint, 0))
            || AbstractDungeon.ascensionLevel != Integer.parseInt(extractFingerprintPart(currentRunFingerprint, 1))
            || com.megacrit.cardcrawl.core.Settings.seed != Long.parseLong(extractFingerprintPart(currentRunFingerprint, 3))) {
            currentRunFingerprint = fingerprint;
            currentRunId = buildRunId("run");
            decisionCounter = 0;
            sharedContextVersion = 0;
            lastSharedContextHash = null;
            lastDecisionFrame = null;
            lastTransitionDiff = null;
        } else {
            currentRunFingerprint = fingerprint;
        }
    }

    private String extractFingerprintPart(String fingerprint, int index) {
        String[] parts = fingerprint.split("\\|");
        return index < parts.length ? parts[index] : "";
    }

    private String buildRunId(String prefix) {
        runCounter += 1;
        return prefix + "_" + System.currentTimeMillis() + "_" + runCounter;
    }

    private String hashForMap(Map<String, Object> value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(gson.toJson(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 support", e);
        }
    }

    private boolean statesEqual(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return gson.toJson(left).equals(gson.toJson(right));
    }

    private String requireStringArg(JsonObject args, String name) throws InvalidCommandException {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            throw new InvalidCommandException(name + " is required");
        }
        return args.get(name).getAsString();
    }

    private String getStringArg(JsonObject args, String name, String defaultValue) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            return defaultValue;
        }
        return args.get(name).getAsString();
    }

    private boolean getBooleanArg(JsonObject args, String name, boolean defaultValue) {
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            return defaultValue;
        }
        return args.get(name).getAsBoolean();
    }

    private Map<String, Object> jsonObjectToMap(JsonObject object) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonNull()) {
                result.put(entry.getKey(), null);
            } else if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isBoolean()) {
                    result.put(entry.getKey(), value.getAsBoolean());
                } else if (value.getAsJsonPrimitive().isNumber()) {
                    result.put(entry.getKey(), value.getAsNumber());
                } else {
                    result.put(entry.getKey(), value.getAsString());
                }
            } else {
                result.put(entry.getKey(), gson.fromJson(value, Object.class));
            }
        }
        return result;
    }

    private String extractPrimaryText(Map<String, Object> toolResult) {
        Object contentObject = toolResult.get("content");
        if (!(contentObject instanceof List)) {
            return "";
        }

        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) contentObject;
        if (content.isEmpty() || !(content.get(0) instanceof Map)) {
            return "";
        }

        Map<String, Object> first = castMap(content.get(0));
        Object text = first.get("text");
        return text == null ? "" : text.toString();
    }

    private void logRecord(String type, Map<String, Object> payload) {
        if (currentRunId == null) {
            return;
        }

        Path directory = Paths.get(System.getProperty("user.home"), ".mcpthespire", "experience");
        Path file = directory.resolve(currentRunId + ".jsonl");
        try {
            Files.createDirectories(directory);
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("type", type);
            record.put("timestamp_ms", System.currentTimeMillis());
            record.put("payload", payload);
            BufferedWriter writer = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            try {
                writer.write(gson.toJson(record));
                writer.newLine();
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            logger.warn("Failed to write agent protocol log", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    static class PreparedAgentAction {
        DecisionFrame sourceFrame;
        ActionGroupRuntime runtime;
        boolean returnNextBundle;
        JsonObject toolArguments;
        Map<String, Object> toolResult;
        Map<String, Object> bindings;
        Map<String, Object> appliedAction;
    }

    private static class DecisionFrame {
        String runId;
        String decisionId;
        String screenType;
        String decisionType;
        String detailLevel;
        String contextMode;
        String actionView;
        boolean includeNextMapGraph;
        String sharedContextHash;
        String decisionContextHash;
        Map<String, Object> sharedContext;
        Map<String, Object> decisionContext;
        Map<String, Object> bundle;
        Map<String, ActionGroupRuntime> actionsById;
    }

    private static class LegalActionBuild {
        Map<String, Object> serialized;
        Map<String, ActionGroupRuntime> actionsById;
    }

    private class ActionGroupRuntime {
        String actionGroupId;
        String kind;
        String toolName;
        String label;
        String parameterName;
        String cardUuid;
        Integer potionSlot;
        Integer fixedChoiceIndex;
        boolean requiresTarget;
        Map<String, Integer> choiceEntityToIndex = new LinkedHashMap<String, Integer>();
        Map<String, String> choiceEntityToValue = new LinkedHashMap<String, String>();
        List<String> optionalBindingNames = new ArrayList<String>();

        JsonObject buildToolArguments(JsonObject bindingsObject) throws InvalidCommandException {
            JsonObject toolArgs = new JsonObject();

            if ("play_card".equals(toolName)) {
                toolArgs.addProperty("card_uuid", cardUuid);
                if (requiresTarget) {
                    String targetEntityId = getBoundString(bindingsObject, "target");
                    toolArgs.addProperty("target_index", resolveMonsterTargetIndex(targetEntityId));
                }
                return toolArgs;
            }

            if ("use_potion".equals(toolName)) {
                toolArgs.addProperty("potion_slot", potionSlot.intValue());
                if (requiresTarget) {
                    String targetEntityId = getBoundString(bindingsObject, "target");
                    toolArgs.addProperty("target_index", resolveMonsterTargetIndex(targetEntityId));
                }
                return toolArgs;
            }

            if ("choose".equals(toolName)) {
                if (fixedChoiceIndex != null) {
                    toolArgs.addProperty("choice_index", fixedChoiceIndex.intValue());
                    return toolArgs;
                }

                String choiceEntityId = getBoundString(bindingsObject, parameterName == null ? "choice" : parameterName);
                Integer choiceIndex = choiceEntityToIndex.get(choiceEntityId);
                if (choiceIndex == null) {
                    throw new InvalidCommandException("Unknown choice entity_id: " + choiceEntityId);
                }
                toolArgs.addProperty("choice_index", choiceIndex.intValue());
                return toolArgs;
            }

            if ("start_game".equals(toolName)) {
                String characterEntityId = getBoundString(bindingsObject, parameterName == null ? "character" : parameterName);
                String character = choiceEntityToValue.get(characterEntityId);
                if (character == null) {
                    throw new InvalidCommandException("Unknown character binding: " + characterEntityId);
                }
                toolArgs.addProperty("character", character);

                if (bindingsObject.has("ascension") && !bindingsObject.get("ascension").isJsonNull()) {
                    toolArgs.addProperty("ascension", bindingsObject.get("ascension").getAsInt());
                }
                if (bindingsObject.has("seed") && !bindingsObject.get("seed").isJsonNull()) {
                    toolArgs.addProperty("seed", bindingsObject.get("seed").getAsString());
                }
                return toolArgs;
            }

            return toolArgs;
        }

        Map<String, Object> toAppliedAction(Map<String, Object> bindings) {
            Map<String, Object> applied = new LinkedHashMap<String, Object>();
            applied.put("decision_id", lastDecisionFrame == null ? null : lastDecisionFrame.decisionId);
            applied.put("action_group_id", actionGroupId);
            applied.put("kind", kind);
            applied.put("tool", toolName);
            applied.put("bindings", bindings);
            return applied;
        }

        private String getBoundString(JsonObject bindingsObject, String name) throws InvalidCommandException {
            if (!bindingsObject.has(name) || bindingsObject.get(name).isJsonNull()) {
                throw new InvalidCommandException("Missing binding: " + name);
            }
            return bindingsObject.get(name).getAsString();
        }

        private int resolveMonsterTargetIndex(String targetEntityId) throws InvalidCommandException {
            int aliveIndex = 0;
            for (AbstractMonster monster : AbstractDungeon.getCurrRoom().monsters.monsters) {
                if (monster.isDeadOrEscaped()) {
                    continue;
                }
                aliveIndex += 1;
                if (GameStateConverter.getMonsterEntityId(monster).equals(targetEntityId)) {
                    return aliveIndex;
                }
            }
            throw new InvalidCommandException("Unknown target entity_id: " + targetEntityId);
        }
    }
}