package forge.game;

import java.io.Serializable;

public class GameLogEntry implements Serializable {
    private static final long serialVersionUID = -5322859985172769631L;

    public final String message;
    public final GameLogEntryType type;
    public final String cardType; // e.g. "Creature", "Enchantment", "Instant" — null for non-spell entries

    GameLogEntry(final GameLogEntryType type0, final String messageIn) {
        this(type0, messageIn, null);
    }

    GameLogEntry(final GameLogEntryType type0, final String messageIn, final String cardTypeIn) {
        type = type0;
        message = messageIn;
        cardType = cardTypeIn;
    }

    @Override
    public String toString() {
        return type.getCaption() + ": " + message;
    }
}