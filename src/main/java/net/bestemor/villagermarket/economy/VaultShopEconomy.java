package net.bestemor.villagermarket.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Shops paid through Vault (any economy plugin). Unlike the original code, every response is
 * checked, and a payment that fails halfway is rolled back.
 */
public final class VaultShopEconomy implements ShopEconomy {

    private final Economy economy;

    public VaultShopEconomy(Economy economy) {
        this.economy = economy;
    }

    public Economy getVault() {
        return economy;
    }

    @Override
    public String getName() {
        return "Vault (" + economy.getName() + ")";
    }

    @Override
    public BigDecimal step() {
        return new BigDecimal("0.01");
    }

    @Override
    public BigDecimal getBalance(OfflinePlayer player) {
        return BigDecimal.valueOf(economy.getBalance(player));
    }

    @Override
    public boolean has(OfflinePlayer player, BigDecimal amount) {
        return economy.has(player, amount.doubleValue());
    }

    @Override
    public Result withdraw(OfflinePlayer player, BigDecimal amount, String reason) {
        if (amount.signum() == 0) {
            return Result.SUCCESS;
        }
        if (!economy.has(player, amount.doubleValue())) {
            return Result.NOT_ENOUGH_MONEY;
        }
        return ok(economy.withdrawPlayer(player, amount.doubleValue())) ? Result.SUCCESS : Result.FAILED;
    }

    @Override
    public Result deposit(OfflinePlayer player, BigDecimal amount, String reason) {
        if (amount.signum() == 0) {
            return Result.SUCCESS;
        }
        return ok(economy.depositPlayer(player, amount.doubleValue())) ? Result.SUCCESS : Result.FAILED;
    }

    @Override
    public Result pay(OfflinePlayer from, Map<UUID, BigDecimal> to, BigDecimal burned, String reason) {
        BigDecimal total = burned;
        for (Map.Entry<UUID, BigDecimal> entry : to.entrySet()) {
            if (!entry.getKey().equals(from.getUniqueId())) {
                total = total.add(entry.getValue());
            }
        }
        Result taken = withdraw(from, total, reason);
        if (!taken.isSuccess()) {
            return taken;
        }
        List<Map.Entry<OfflinePlayer, BigDecimal>> paid = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : to.entrySet()) {
            if (entry.getKey().equals(from.getUniqueId()) || entry.getValue().signum() == 0) {
                continue;
            }
            OfflinePlayer receiver = Bukkit.getOfflinePlayer(entry.getKey());
            if (deposit(receiver, entry.getValue(), reason).isSuccess()) {
                paid.add(Map.entry(receiver, entry.getValue()));
                continue;
            }
            // Roll back everything done so far.
            for (Map.Entry<OfflinePlayer, BigDecimal> done : paid) {
                if (!ok(economy.withdrawPlayer(done.getKey(), done.getValue().doubleValue()))) {
                    Bukkit.getLogger().log(Level.SEVERE, "[VillagerMarket] Could not take back " + done.getValue()
                            + " from " + done.getKey().getName() + " after a failed payment (" + reason + ")");
                }
            }
            if (!ok(economy.depositPlayer(from, total.doubleValue()))) {
                Bukkit.getLogger().log(Level.SEVERE, "[VillagerMarket] Could not refund " + total + " to "
                        + from.getName() + " after a failed payment (" + reason + ")");
            }
            return Result.RECEIVER_FULL;
        }
        return Result.SUCCESS;
    }

    private static boolean ok(EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }
}
