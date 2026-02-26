package forge.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.mana.ManaPool;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;

import java.util.*;

/**
 * Serializes Forge game state to JSON for the LLM bridge protocol.
 *
 * Output schema matches what Python's serializer.py expects:
 * {
 *   "decision_type": "choose_spell",
 *   "game_state": { turn, phase, life_totals, hand, battlefield, ... },
 *   "options": [ { "id": 0, "description": "..." }, ... ]
 * }
 */
public class GameStateSerializer {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Build a full decision request JSON string.
     */
    public static String serialize(String decisionType, Player player, Game game, List<Map<String, Object>> options) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("decision_type", decisionType);
        root.put("player_name", player.getName());
        root.put("game_state", buildGameState(player, game));
        root.put("options", options);
        return GSON.toJson(root);
    }

    private static Map<String, Object> buildGameState(Player player, Game game) {
        Map<String, Object> state = new LinkedHashMap<>();

        PhaseHandler ph = game.getPhaseHandler();
        state.put("turn", ph.getTurn());
        state.put("phase", ph.getPhase() != null ? ph.getPhase().name() : "PREGAME");
        state.put("active_player", ph.getPlayerTurn() != null ? ph.getPlayerTurn().getName() : "unknown");

        // Life totals
        Map<String, Integer> lifeTotals = new LinkedHashMap<>();
        for (Player p : game.getPlayers()) {
            lifeTotals.put(p.getName(), p.getLife());
        }
        state.put("life_totals", lifeTotals);

        // Find opponent
        Player opponent = null;
        for (Player p : game.getPlayers()) {
            if (p != player) {
                opponent = p;
                break;
            }
        }

        // Your hand
        state.put("your_hand", serializeCards(player.getCardsIn(ZoneType.Hand)));

        // Your battlefield
        state.put("your_battlefield", serializePermanents(player.getCardsIn(ZoneType.Battlefield)));

        // Your graveyard
        state.put("your_graveyard", serializeCardsSimple(player.getCardsIn(ZoneType.Graveyard)));

        // Your mana pool
        state.put("your_mana_pool", serializeManaPool(player));

        // Opponent info
        if (opponent != null) {
            state.put("opponent_battlefield", serializePermanents(opponent.getCardsIn(ZoneType.Battlefield)));
            state.put("opponent_graveyard", serializeCardsSimple(opponent.getCardsIn(ZoneType.Graveyard)));
            state.put("opponent_hand_size", opponent.getCardsIn(ZoneType.Hand).size());
        }

        // Stack
        state.put("stack", serializeStack(game.getStack()));

        return state;
    }

    private static List<Map<String, Object>> serializeCards(CardCollectionView cards) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Card c : cards) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", c.getName());
            card.put("mana_cost", c.getManaCost().toString());
            card.put("types", getTypeList(c));
            card.put("oracle_text", c.getOracleText());
            if (c.isCreature()) {
                card.put("power", c.getNetPower());
                card.put("toughness", c.getNetToughness());
            }
            result.add(card);
        }
        return result;
    }

    private static List<Map<String, Object>> serializePermanents(CardCollectionView cards) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Card c : cards) {
            Map<String, Object> perm = new LinkedHashMap<>();
            perm.put("name", c.getName());
            perm.put("tapped", c.isTapped());
            perm.put("types", getTypeList(c));
            if (c.isCreature()) {
                perm.put("power", c.getNetPower());
                perm.put("toughness", c.getNetToughness());
                perm.put("summoning_sick", c.isSick());
            }
            result.add(perm);
        }
        return result;
    }

    private static List<Map<String, Object>> serializeCardsSimple(CardCollectionView cards) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Card c : cards) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", c.getName());
            result.add(card);
        }
        return result;
    }

    private static Map<String, Integer> serializeManaPool(Player player) {
        Map<String, Integer> mana = new LinkedHashMap<>();
        ManaPool pool = player.getManaPool();
        if (pool == null) {
            mana.put("W", 0); mana.put("U", 0); mana.put("B", 0);
            mana.put("R", 0); mana.put("G", 0); mana.put("C", 0);
            return mana;
        }
        mana.put("W", pool.getAmountOfColor(forge.card.MagicColor.WHITE));
        mana.put("U", pool.getAmountOfColor(forge.card.MagicColor.BLUE));
        mana.put("B", pool.getAmountOfColor(forge.card.MagicColor.BLACK));
        mana.put("R", pool.getAmountOfColor(forge.card.MagicColor.RED));
        mana.put("G", pool.getAmountOfColor(forge.card.MagicColor.GREEN));
        mana.put("C", pool.getAmountOfColor((byte) 0));
        return mana;
    }

    private static List<String> serializeStack(MagicStack stack) {
        List<String> result = new ArrayList<>();
        for (SpellAbilityStackInstance si : stack) {
            SpellAbility sa = si.getSpellAbility();
            String desc = sa.getHostCard().getName();
            if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
                desc += " - " + sa.getDescription();
            }
            result.add(desc);
        }
        return result;
    }

    private static List<String> getTypeList(Card c) {
        List<String> types = new ArrayList<>();
        if (c.isLand()) types.add("Land");
        if (c.isCreature()) types.add("Creature");
        if (c.isArtifact()) types.add("Artifact");
        if (c.isEnchantment()) types.add("Enchantment");
        if (c.isInstant()) types.add("Instant");
        if (c.isSorcery()) types.add("Sorcery");
        if (c.isPlaneswalker()) types.add("Planeswalker");
        return types;
    }

    // --- Option builders for common decision types ---

    public static List<Map<String, Object>> buildSpellOptions(List<SpellAbility> spells) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < spells.size(); i++) {
            SpellAbility sa = spells.get(i);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", i);
            opt.put("description", describeSpellAbility(sa));
            options.add(opt);
        }
        // Always add "Pass" as the last option
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("id", spells.size());
        pass.put("description", "Pass priority (do nothing)");
        options.add(pass);
        return options;
    }

    public static List<Map<String, Object>> buildCardOptions(CardCollectionView cards) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", i);
            opt.put("description", describeCard(c));
            options.add(opt);
        }
        return options;
    }

    public static <T> List<Map<String, Object>> buildEntityOptions(List<T> entities) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", i);
            opt.put("description", entities.get(i).toString());
            options.add(opt);
        }
        return options;
    }

    public static List<Map<String, Object>> buildBinaryOptions(String yesLabel, String noLabel) {
        List<Map<String, Object>> options = new ArrayList<>();
        Map<String, Object> yes = new LinkedHashMap<>();
        yes.put("id", 0);
        yes.put("description", yesLabel);
        options.add(yes);
        Map<String, Object> no = new LinkedHashMap<>();
        no.put("id", 1);
        no.put("description", noLabel);
        options.add(no);
        return options;
    }

    public static List<Map<String, Object>> buildModeOptions(List<?> modes) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < modes.size(); i++) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", i);
            Object mode = modes.get(i);
            if (mode instanceof SpellAbility) {
                opt.put("description", describeSpellAbility((SpellAbility) mode));
            } else {
                opt.put("description", mode.toString());
            }
            options.add(opt);
        }
        return options;
    }

    private static String describeSpellAbility(SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        Card host = sa.getHostCard();
        if (sa.isSpell()) {
            sb.append("Cast ").append(host.getName());
            if (host.getManaCost() != null) {
                sb.append(" (").append(host.getManaCost()).append(")");
            }
        } else if (sa.isLandAbility()) {
            sb.append("Play land: ").append(host.getName());
        } else {
            sb.append("Activate: ").append(host.getName());
            String desc = sa.getDescription();
            if (desc != null && !desc.isEmpty()) {
                sb.append(" - ").append(desc);
            }
        }
        return sb.toString();
    }

    private static String describeCard(Card c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getName());
        if (c.isCreature()) {
            sb.append(" (").append(c.getNetPower()).append("/").append(c.getNetToughness()).append(")");
        }
        if (c.isTapped()) {
            sb.append(" [tapped]");
        }
        return sb.toString();
    }
}
