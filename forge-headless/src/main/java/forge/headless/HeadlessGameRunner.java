package forge.headless;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.card.CardType;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CardUtil;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;

import java.io.File;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

public class HeadlessGameRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: HeadlessGameRunner <deck_a_path> <deck_b_path> [--res <res_dir>] [--games <n>]");
            System.exit(1);
        }

        String deckAPath = args[0];
        String deckBPath = args[1];
        String resDir = null;
        int numGames = 1;

        for (int i = 2; i < args.length; i++) {
            if ("--res".equals(args[i]) && i + 1 < args.length) {
                resDir = args[++i];
            } else if ("--games".equals(args[i]) && i + 1 < args.length) {
                numGames = Integer.parseInt(args[++i]);
            }
        }

        // Auto-detect res directory relative to the jar/project
        if (resDir == null) {
            resDir = findResDir();
        }

        if (resDir == null) {
            System.err.println("ERROR: Could not find Forge res/ directory. Use --res <path>");
            System.exit(1);
        }

        // Redirect stdout to stderr during initialization and gameplay
        // so Forge's internal System.out.println calls don't pollute our JSON output
        PrintStream realStdout = System.out;
        System.setOut(System.err);

        System.err.println("=== MagicForge Headless Runner ===");
        System.err.println("Res directory: " + resDir);

        try {
            // Initialize the card database
            System.err.println("Loading card database...");
            initStaticData(resDir);
            System.err.println("Card database loaded: " +
                StaticData.instance().getCommonCards().getAllCards().size() + " cards");

            // Load decks
            System.err.println("Loading decks...");
            Deck deckA = loadDeck(deckAPath);
            Deck deckB = loadDeck(deckBPath);
            System.err.println("Deck A: " + deckA.getName() + " (" + deckA.getMain().countAll() + " cards)");
            System.err.println("Deck B: " + deckB.getName() + " (" + deckB.getMain().countAll() + " cards)");

            // Run games
            List<Map<String, Object>> results = new ArrayList<>();
            int aWins = 0, bWins = 0, draws = 0;

            for (int g = 0; g < numGames; g++) {
                System.err.println("\n--- Game " + (g + 1) + " of " + numGames + " ---");
                Map<String, Object> result = runSingleGame(deckA, deckB, g + 1);
                results.add(result);

                String winner = (String) result.get("winner");
                if (winner != null && winner.equals(result.get("player_a"))) aWins++;
                else if (winner != null && winner.equals(result.get("player_b"))) bWins++;
                else draws++;

                System.err.println("Result: " + (winner != null ? winner + " wins" : "Draw")
                    + " on turn " + result.get("last_turn"));
            }

            // Build summary
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_games", numGames);
            summary.put("player_a", deckA.getName());
            summary.put("player_b", deckB.getName());
            summary.put("a_wins", aWins);
            summary.put("b_wins", bWins);
            summary.put("draws", draws);
            summary.put("games", results);

            // Restore real stdout and output JSON
            System.setOut(realStdout);
            realStdout.println(GSON.toJson(summary));

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);

            System.setOut(realStdout);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            realStdout.println(GSON.toJson(error));
            System.exit(1);
        }
    }

    private static void initStaticData(String resDir) {
        String cardDataDir = resDir + File.separator + "cardsfolder" + File.separator;
        String editionsDir = resDir + File.separator + "editions" + File.separator;
        String blockDataDir = resDir + File.separator + "blockdata" + File.separator;
        String tokenDir = resDir + File.separator + "tokenscripts" + File.separator;

        // Initialize localizer (needed by game engine)
        String langDir = resDir + File.separator + "languages" + File.separator;
        Localizer.getInstance().initialize("en-US", langDir);
        Lang.createInstance("en-US");

        // Initialize ImageKeys with empty dirs (headless mode, no images needed)
        ImageKeys.initializeDirs("", new HashMap<>(), "", "", "", "", "", "", "");

        // Load dynamic game data (card types and keywords) — CRITICAL for AI
        // Without this, the AI cannot properly identify card types and evaluate board state
        loadDynamicGamedata(resDir);

        CardStorageReader cardReader = new CardStorageReader(cardDataDir, new CardStorageReader.ProgressObserver() {
            private int last = 0;
            @Override
            public void setOperationName(String name, boolean usePercents) {
                System.err.println("  " + name);
            }
            @Override
            public void report(int current, int total) {
                int pct = (total > 0) ? (current * 100 / total) : 0;
                if (pct / 10 > last / 10) {
                    System.err.println("  Loading cards: " + pct + "%");
                    last = pct;
                }
            }
        }, false);

        CardStorageReader tokenReader;
        try {
            tokenReader = new CardStorageReader(tokenDir, null, false);
        } catch (Exception e) {
            tokenReader = null;
        }

        new StaticData(cardReader, tokenReader, null, null,
            editionsDir, editionsDir, blockDataDir, "",
            "Latest Art All Editions",
            true, false, false, false);

        // Load AI profiles (critical for proper AI decision-making)
        String aiProfileDir = resDir + File.separator + "ai" + File.separator;
        if (new File(aiProfileDir).isDirectory()) {
            AiProfileUtil.loadAllProfiles(aiProfileDir);
            System.err.println("AI profiles loaded from: " + aiProfileDir);
        } else {
            System.err.println("WARNING: AI profile directory not found: " + aiProfileDir);
        }
    }

    private static Deck loadDeck(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new RuntimeException("Deck file not found: " + path);
        }
        Deck deck = DeckSerializer.fromFile(file);
        if (deck == null) {
            throw new RuntimeException("Failed to parse deck: " + path);
        }
        return deck;
    }

    private static Map<String, Object> runSingleGame(Deck deckA, Deck deckB, int gameNumber) {
        // Create AI lobby players with proper profile
        Set<AIOption> aiOpts = EnumSet.noneOf(AIOption.class);
        LobbyPlayerAi lobbyA = new LobbyPlayerAi(deckA.getName(), aiOpts);
        LobbyPlayerAi lobbyB = new LobbyPlayerAi(deckB.getName(), aiOpts);
        lobbyA.setAiProfile("Default");
        lobbyB.setAiProfile("Default");

        // Create registered players
        RegisteredPlayer regA = new RegisteredPlayer(deckA).setPlayer(lobbyA);
        RegisteredPlayer regB = new RegisteredPlayer(deckB).setPlayer(lobbyB);
        List<RegisteredPlayer> players = Arrays.asList(regA, regB);

        // Setup game rules
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);

        // Create match and game
        Match match = new Match(rules, players, "Game " + gameNumber);
        Game game = match.createGame();

        GameLog gameLog = game.getGameLog();

        // Register custom event subscriber for draws and hand snapshots
        HeadlessEventSubscriber eventSub = new HeadlessEventSubscriber(gameLog);
        game.subscribeToEvents(eventSub);

        System.err.println("Starting game...");
        long startTime = System.currentTimeMillis();

        // Run the game
        match.startGame(game);

        long elapsed = System.currentTimeMillis() - startTime;
        System.err.println("Game completed in " + elapsed + "ms");

        // Collect results
        GameOutcome outcome = game.getOutcome();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("game_number", gameNumber);
        result.put("player_a", deckA.getName());
        result.put("player_b", deckB.getName());

        if (outcome != null) {
            result.put("last_turn", outcome.getLastTurnNumber());

            if (outcome.isDraw()) {
                result.put("winner", null);
                result.put("result", "draw");
            } else {
                String winnerName = null;
                if (outcome.getWinningLobbyPlayer() != null) {
                    winnerName = outcome.getWinningLobbyPlayer().getName();
                }
                result.put("winner", winnerName);
                result.put("result", winnerName + " wins");
            }

            // Collect per-player statistics
            List<Map<String, Object>> playerStats = new ArrayList<>();
            for (Map.Entry<RegisteredPlayer, forge.game.player.PlayerStatistics> entry : outcome) {
                Map<String, Object> ps = new LinkedHashMap<>();
                ps.put("name", entry.getKey().getPlayer().getName());
                ps.put("outcome", entry.getValue().getOutcome().toString());
                playerStats.add(ps);
            }
            result.put("player_stats", playerStats);

            // Final life totals
            Map<String, Integer> lifeTotals = new LinkedHashMap<>();
            for (forge.game.player.Player p : game.getPlayers()) {
                lifeTotals.put(p.getName(), p.getLife());
            }
            result.put("life_totals", lifeTotals);
        } else {
            result.put("last_turn", -1);
            result.put("winner", null);
            result.put("result", "no outcome");
        }

        result.put("elapsed_ms", elapsed);

        // Collect game log entries (all entries, reversed to chronological order)
        List<GameLogEntry> allEntries = gameLog.getLogEntries(null);
        Collections.reverse(allEntries);
        List<Map<String, String>> logEntries = new ArrayList<>();
        for (GameLogEntry entry : allEntries) {
            Map<String, String> le = new LinkedHashMap<>();
            le.put("type", entry.type.name());
            le.put("message", entry.message);
            logEntries.add(le);
        }
        result.put("log", logEntries);

        return result;
    }

    /**
     * Load card type definitions and non-stacking keywords.
     * Equivalent to FModel.loadDynamicGamedata() but without requiring forge-gui.
     */
    private static void loadDynamicGamedata(String resDir) {
        String listsDir = resDir + File.separator + "lists" + File.separator;

        // Load card types (creature types, land types, etc.)
        String typeListFile = listsDir + "TypeLists.txt";
        if (new File(typeListFile).exists()) {
            Map<String, List<String>> typeSections = FileSection.parseSections(FileUtil.readFile(typeListFile));
            for (Map.Entry<String, List<String>> entry : typeSections.entrySet()) {
                CardType.Helper.parseTypes(entry.getKey(), entry.getValue());
            }
            CardType.Constant.LOADED.set();
            System.err.println("Card types loaded from: " + typeListFile);
        } else {
            System.err.println("WARNING: Type list file not found: " + typeListFile);
        }

        // Load non-stacking keyword list
        String keywordListFile = listsDir + "NonStackingKWList.txt";
        if (new File(keywordListFile).exists()) {
            List<String> keywords = FileUtil.readFile(keywordListFile);
            for (String s : keywords) {
                if (s.length() > 1) {
                    CardUtil.NON_STACKING_LIST.add(s);
                }
            }
            System.err.println("Keywords loaded: " + CardUtil.NON_STACKING_LIST.size() + " non-stacking keywords");
        } else {
            System.err.println("WARNING: Keyword list file not found: " + keywordListFile);
        }
    }

    /**
     * Custom Guava EventBus subscriber that injects draw and hand-snapshot
     * entries into the game log (Forge doesn't log these by default).
     */
    static class HeadlessEventSubscriber {
        private final GameLog log;

        HeadlessEventSubscriber(GameLog log) {
            this.log = log;
        }

        @Subscribe
        public void onCardChangeZone(GameEventCardChangeZone ev) {
            // Library -> Hand = draw
            if (ev.from() != null && ev.to() != null
                    && ev.from().getZoneType() == ZoneType.Library
                    && ev.to().getZoneType() == ZoneType.Hand) {
                Card card = ev.card();
                Player owner = card.getOwner();
                String playerName = owner != null ? owner.getName() : "Unknown";
                String cardName = card.getName();
                log.add(GameLogEntryType.INFORMATION,
                    "DRAW:" + playerName + ":" + cardName);
            }
        }

        @Subscribe
        public void onTurnBegan(GameEventTurnBegan ev) {
            // Snapshot each player's state at the start of each turn
            Player turnOwner = ev.turnOwner();
            Game game = turnOwner.getGame();
            for (Player p : game.getPlayers()) {
                // Emit authoritative life total from Forge
                log.add(GameLogEntryType.INFORMATION,
                    "LIFE:" + p.getName() + ":" + p.getLife());

                List<String> cardNames = p.getCardsIn(ZoneType.Hand).stream()
                    .map(Card::getName)
                    .collect(Collectors.toList());
                String hand = String.join(" | ", cardNames);
                log.add(GameLogEntryType.INFORMATION,
                    "HAND:" + p.getName() + ":" + cardNames.size() + ":" + hand);
            }
        }
    }

    private static String findResDir() {
        // Try common locations relative to the jar/working directory
        String[] candidates = {
            "../forge-gui/res",
            "forge-gui/res",
            "../../forge-gui/res",
            "../res",
            "res",
        };
        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (dir.isDirectory() && new File(dir, "cardsfolder").isDirectory()) {
                try {
                    return dir.getCanonicalPath();
                } catch (Exception e) {
                    return dir.getAbsolutePath();
                }
            }
        }
        return null;
    }
}
