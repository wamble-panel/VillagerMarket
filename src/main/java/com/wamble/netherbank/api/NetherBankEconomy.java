package com.wamble.netherbank.api;

import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/**
 * NetherBank's economy for other plugins (shops, markets ...). The money is the real netherite in
 * players' banks, so every call can fail and every result must be checked.
 *
 * <p>Get it with {@code Bukkit.getServicesManager().load(NetherBankEconomy.class)}.</p>
 *
 * <ul>
 *   <li>Call everything on the main server thread.</li>
 *   <li>Amounts are in netherite (1 = one ingot) and must be multiples of {@link #step()} (0.25 by
 *       default). Use {@link #roundUp}, {@link #roundDown} or {@link #roundNearest} first.</li>
 *   <li>Offline players work too; their bank is loaded on the spot.</li>
 *   <li>A failed call changes nothing.</li>
 * </ul>
 *
 * <p>This file is also copied (unchanged) into plugins that use the API, only for compiling. Bump
 * {@link #API_VERSION} when changing it.</p>
 */
public interface NetherBankEconomy {

    int API_VERSION = 1;

    enum Result {
        SUCCESS,
        /** The payer doesn't have enough. */
        INSUFFICIENT_FUNDS,
        /** The payer's bank is completely full, so there's no room for change. */
        NO_ROOM_FOR_CHANGE,
        /** A receiver's bank is completely full. */
        RECEIVER_FULL,
        /** A bank is locked because the database is having problems. */
        VAULT_LOCKED,
        /** The amount is negative or not a multiple of {@link #step()}. */
        INVALID_AMOUNT,
        /** A bank couldn't be loaded from the database. */
        STORAGE_ERROR,
        /** NetherBank's economy is turned off. */
        DISABLED;

        public boolean isSuccess() {
            return this == SUCCESS;
        }
    }

    boolean isEnabled();

    /** The smallest amount (0.25 when scrap is worth a quarter). */
    double step();

    double roundDown(double amount);

    double roundUp(double amount);

    double roundNearest(double amount);

    /** "1,206.25" */
    String format(double amount);

    /** Spendable balance. 0 if the bank can't be loaded. */
    double getBalance(OfflinePlayer player);

    /** Whether the player can pay {@code amount} (rounded up to the step). */
    boolean has(OfflinePlayer player, double amount);

    /** Whether {@link #deposit} would work right now, without changing anything. */
    Result canDeposit(OfflinePlayer player, double amount);

    /** Takes money out of a bank (it leaves the economy, e.g. buying from the server). */
    Result withdraw(OfflinePlayer player, double amount, String reason);

    /** Puts new money into a bank (e.g. selling to the server). */
    Result deposit(OfflinePlayer player, double amount, String reason);

    /**
     * Pays several players from one bank in one step: {@code to} gets their parts and {@code burned}
     * leaves the economy (e.g. a tax). Either everything happens or nothing does. If the payer is
     * also in {@code to}, their own part simply stays with them.
     */
    Result pay(OfflinePlayer from, Map<UUID, Double> to, double burned, String reason);
}
