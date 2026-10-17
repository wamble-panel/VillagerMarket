package net.bestemor.villagermarket.economy;

import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The money system shops use: NetherBank (netherite) when it's installed, otherwise Vault.
 *
 * <p>Every call reports whether it worked, and a failed call changes nothing. Shops always take
 * the money first and only hand out items once the payment succeeded.</p>
 */
public interface ShopEconomy {

    enum Result {
        SUCCESS,
        NOT_ENOUGH_MONEY,
        /** A receiver's bank is full. */
        RECEIVER_FULL,
        /** The payer's bank is too full to make change. */
        NO_ROOM_FOR_CHANGE,
        /** Something else went wrong (database, economy plugin). Nothing was changed. */
        FAILED;

        public boolean isSuccess() {
            return this == SUCCESS;
        }
    }

    String getName();

    /**
     * Whether shop income may be held in the shop until it's collected (require_collect). With
     * NetherBank it's paid straight to the owner instead: holding it would take scrap from the
     * customer and later pay the owner in ingots, turning scrap into ingots for free.
     */
    default boolean canHoldIncome() {
        return true;
    }

    /** Smallest amount this economy can move (0.25 for NetherBank, 0.01 for Vault). */
    BigDecimal step();

    /** Rounds a price to the nearest step. A price above zero never becomes free. */
    default BigDecimal price(BigDecimal raw) {
        if (raw == null || raw.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rounded = raw.divide(step(), 0, java.math.RoundingMode.HALF_UP).multiply(step());
        return rounded.signum() == 0 ? step() : rounded.stripTrailingZeros();
    }

    default BigDecimal floor(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(step(), 0, java.math.RoundingMode.FLOOR).multiply(step()).stripTrailingZeros();
    }

    default BigDecimal ceil(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(step(), 0, java.math.RoundingMode.CEILING).multiply(step()).stripTrailingZeros();
    }

    BigDecimal getBalance(OfflinePlayer player);

    boolean has(OfflinePlayer player, BigDecimal amount);

    /** Takes money out of the economy (buying from the server, rent). */
    Result withdraw(OfflinePlayer player, BigDecimal amount, String reason);

    /** Puts new money into the economy (selling to the server, refunds, collected income). */
    Result deposit(OfflinePlayer player, BigDecimal amount, String reason);

    /**
     * Pays several players from one player in one step; {@code burned} (tax) leaves the economy.
     * Everything happens or nothing does.
     */
    Result pay(OfflinePlayer from, Map<UUID, BigDecimal> to, BigDecimal burned, String reason);

    /**
     * Splits income between the owner and other players: everyone else gets an equal share rounded
     * down to the step, and the owner gets the rest, so the parts always add up exactly.
     */
    default Map<UUID, BigDecimal> split(BigDecimal amount, UUID owner, List<UUID> others) {
        Map<UUID, BigDecimal> parts = new LinkedHashMap<>();
        int people = others.size() + 1;
        BigDecimal share = floor(amount.divide(BigDecimal.valueOf(people), 10, java.math.RoundingMode.FLOOR));
        BigDecimal rest = amount;
        for (UUID other : others) {
            if (!other.equals(owner)) {
                parts.merge(other, share, BigDecimal::add);
                rest = rest.subtract(share);
            }
        }
        parts.merge(owner, rest, BigDecimal::add);
        return parts;
    }
}
