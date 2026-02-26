package forge.headless;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.combat.CombatUtil;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.io.PrintStream;
import java.util.*;

/**
 * Player controller that delegates strategic decisions to an external LLM
 * via stdin/stdout JSON protocol. Mechanical decisions (mana payment, damage
 * assignment, etc.) are inherited from PlayerControllerAi.
 *
 * Protocol:
 *   Java -> Python:  DECISION_REQUEST\n{json}\nDECISION_END\n
 *   Python -> Java:  {"choice": N}\n
 */
public class PlayerControllerLLM extends PlayerControllerAi {

    private final PrintStream out;  // realStdout for protocol messages
    private final Scanner in;       // System.in for reading responses
    private static final Gson GSON = new Gson();

    public PlayerControllerLLM(Game game, Player player, LobbyPlayer lp, PrintStream realStdout) {
        super(game, player, lp);
        this.out = realStdout;
        this.in = new Scanner(System.in);
    }

    // ---- Protocol I/O ----

    /**
     * Send a decision request to the Python bridge and read the response.
     * Returns the chosen index, or -1 on failure.
     */
    private int requestDecision(String decisionType, List<Map<String, Object>> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }

        String json = GameStateSerializer.serialize(decisionType, getPlayer(), getGame(), options);

        synchronized (out) {
            out.println("DECISION_REQUEST");
            out.println(json);
            out.println("DECISION_END");
            out.flush();
        }

        try {
            String line = in.nextLine().trim();
            JsonObject response = JsonParser.parseString(line).getAsJsonObject();
            JsonElement choiceElem = response.get("choice");
            if (choiceElem != null) {
                int choice = choiceElem.getAsInt();
                if (choice >= 0 && choice < options.size()) {
                    return choice;
                }
            }
        } catch (Exception e) {
            System.err.println("[LLM] Failed to parse response, falling back to AI: " + e.getMessage());
        }

        return -1; // signal fallback
    }

    // ---- Strategic method overrides ----

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> aiChoices = super.chooseSpellAbilityToPlay();
        if (aiChoices == null || aiChoices.isEmpty()) {
            return aiChoices;
        }

        // Build options from all playable spell abilities
        List<Map<String, Object>> options = GameStateSerializer.buildSpellOptions(aiChoices);

        int choice = requestDecision("choose_spell", options);
        if (choice < 0) {
            return aiChoices; // fallback to AI
        }

        // Last option = "pass"
        if (choice >= aiChoices.size()) {
            return null; // pass priority
        }

        return Collections.singletonList(aiChoices.get(choice));
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        CardCollection creatures = attacker.getCreaturesInPlay();
        CardCollection canAttack = new CardCollection();
        for (Card c : creatures) {
            if (!c.isTapped() && !c.isSick() && CombatUtil.canAttack(c)) {
                canAttack.add(c);
            }
        }

        if (canAttack.isEmpty()) {
            return; // nothing can attack
        }

        // Build multi-select options: each creature is an option
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < canAttack.size(); i++) {
            Card c = canAttack.get(i);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", i);
            opt.put("description", c.getName() + " (" + c.getNetPower() + "/" + c.getNetToughness() + ")");
            options.add(opt);
        }
        // Add "attack with none" option
        Map<String, Object> noneOpt = new LinkedHashMap<>();
        noneOpt.put("id", canAttack.size());
        noneOpt.put("description", "Don't attack (pass combat)");
        options.add(noneOpt);

        // For multi-creature attack, ask LLM once (simplified: ask per-creature)
        // We send the full list and expect a comma-separated list or single choice
        String json = GameStateSerializer.serialize("declare_attackers", getPlayer(), getGame(), options);

        synchronized (out) {
            out.println("DECISION_REQUEST");
            out.println(json);
            out.println("DECISION_END");
            out.flush();
        }

        try {
            String line = in.nextLine().trim();
            JsonObject response = JsonParser.parseString(line).getAsJsonObject();

            // Support both {"choice": N} for single and {"choices": [0,1,2]} for multi
            if (response.has("choices")) {
                for (JsonElement e : response.getAsJsonArray("choices")) {
                    int idx = e.getAsInt();
                    if (idx >= 0 && idx < canAttack.size()) {
                        GameEntity defender = combat.getDefenders().iterator().next();
                        combat.addAttacker(canAttack.get(idx), defender);
                    }
                }
            } else if (response.has("choice")) {
                int choice = response.get("choice").getAsInt();
                if (choice >= 0 && choice < canAttack.size()) {
                    // Single attacker
                    GameEntity defender = combat.getDefenders().iterator().next();
                    combat.addAttacker(canAttack.get(choice), defender);
                }
                // choice == canAttack.size() means "don't attack"
            }
        } catch (Exception e) {
            System.err.println("[LLM] Failed to parse attacker response, falling back to AI: " + e.getMessage());
            super.declareAttackers(attacker, combat);
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // Blocking is complex — fall back to AI for now
        // A future version could present blocking assignments to the LLM
        super.declareBlockers(defender, combat);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max) {
        if (p != getPlayer()) {
            return (CardCollection) super.chooseCardsToDiscardFrom(p, sa, validCards, min, max);
        }

        List<Map<String, Object>> options = GameStateSerializer.buildCardOptions(validCards);
        CardCollection chosen = new CardCollection();

        for (int i = 0; i < min; i++) {
            int choice = requestDecision("choose_discard", options);
            if (choice < 0 || choice >= validCards.size()) {
                return (CardCollection) super.chooseCardsToDiscardFrom(p, sa, validCards, min, max);
            }
            chosen.add(validCards.get(choice));
        }

        return chosen;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        List<Map<String, Object>> options = GameStateSerializer.buildCardOptions(validTargets);
        CardCollection chosen = new CardCollection();

        for (int i = 0; i < min; i++) {
            int choice = requestDecision("choose_sacrifice", options);
            if (choice < 0 || choice >= validTargets.size()) {
                return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
            }
            chosen.add(validTargets.get(choice));
        }

        return chosen;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> stringOptions, Card cardToShow, Map<String, Object> params) {
        String desc = message != null ? message : "Confirm action";
        if (sa != null && sa.getHostCard() != null) {
            desc = sa.getHostCard().getName() + ": " + desc;
        }
        List<Map<String, Object>> options = GameStateSerializer.buildBinaryOptions("Yes - " + desc, "No");

        int choice = requestDecision("confirm_action", options);
        if (choice < 0) {
            return super.confirmAction(sa, mode, message, stringOptions, cardToShow, params);
        }
        return choice == 0; // 0 = yes, 1 = no
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        if (wrapper.isMandatory()) {
            return true;
        }

        SpellAbility sa = wrapper.getWrappedAbility();
        String desc = sa.getHostCard().getName();
        if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
            desc += " - " + sa.getDescription();
        }
        List<Map<String, Object>> options = GameStateSerializer.buildBinaryOptions(
            "Yes - trigger: " + desc, "No - decline trigger");

        int choice = requestDecision("confirm_trigger", options);
        if (choice < 0) {
            return super.confirmTrigger(wrapper);
        }
        return choice == 0;
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        List<Map<String, Object>> options = GameStateSerializer.buildBinaryOptions(
            "Keep hand", "Mulligan (draw " + (getPlayer().getCardsIn(ZoneType.Hand).size() - 1) + " cards)");

        int choice = requestDecision("mulligan", options);
        if (choice < 0) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        return choice == 0; // 0 = keep, 1 = mulligan
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (possible == null || possible.isEmpty()) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }

        List<Map<String, Object>> options = GameStateSerializer.buildModeOptions(possible);

        List<AbilitySub> chosen = new ArrayList<>();
        for (int i = 0; i < num && i < possible.size(); i++) {
            int choice = requestDecision("choose_mode", options);
            if (choice < 0 || choice >= possible.size()) {
                return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
            }
            chosen.add(possible.get(choice));
            if (!allowRepeat) {
                // Remove chosen from options for next pick
                options.remove(choice);
                possible = new ArrayList<>(possible);
                possible.remove(choice);
            }
        }

        return chosen;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }

        if (optionList == null || optionList.isEmpty()) {
            return null;
        }

        List<T> entityList = new ArrayList<>();
        for (T e : optionList) {
            entityList.add(e);
        }

        List<Map<String, Object>> options = GameStateSerializer.buildEntityOptions(entityList);
        if (isOptional) {
            Map<String, Object> noneOpt = new LinkedHashMap<>();
            noneOpt.put("id", entityList.size());
            noneOpt.put("description", "None (skip)");
            options.add(noneOpt);
        }

        int choice = requestDecision("choose_target", options);
        if (choice < 0) {
            return super.chooseSingleEntityForEffect(optionList, null, sa, title, isOptional, targetedPlayer, params);
        }

        if (isOptional && choice >= entityList.size()) {
            return null;
        }

        if (choice >= 0 && choice < entityList.size()) {
            return entityList.get(choice);
        }

        return super.chooseSingleEntityForEffect(optionList, null, sa, title, isOptional, targetedPlayer, params);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (sourceList == null || sourceList.isEmpty()) {
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }

        List<Map<String, Object>> options = GameStateSerializer.buildCardOptions(sourceList);
        CardCollection chosen = new CardCollection();

        int toChoose = Math.min(min, sourceList.size());
        for (int i = 0; i < toChoose; i++) {
            int choice = requestDecision("choose_cards", options);
            if (choice < 0 || choice >= sourceList.size()) {
                return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
            }
            chosen.add(sourceList.get(choice));
        }

        return chosen;
    }
}
