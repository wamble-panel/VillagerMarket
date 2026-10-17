package net.bestemor.villagermarket.economy;

import net.bestemor.core.config.ConfigManager;
import net.bestemor.villagermarket.VMPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Money a shop owes a player that couldn't be paid right away (their bank was full when their shop
 * expired or was sold). It's kept here, saved to pending_payouts.yml, and paid out as soon as it
 * fits: when they join, and every few minutes while they're online. Nothing is ever dropped.
 */
public final class PendingPayouts {

    private final VMPlugin plugin;
    private final File file;
    private final Map<UUID, BigDecimal> owed = new HashMap<>();

    public PendingPayouts(VMPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending_payouts.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                owed.put(UUID.fromString(key), new BigDecimal(yaml.getString(key, "0")));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid entry in pending_payouts.yml: " + key);
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tryPay(player);
            }
        }, 20L * 60 * 5, 20L * 60 * 5);
    }

    /** Pays the player now if possible, otherwise remembers it. */
    public void payOrKeep(OfflinePlayer player, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        ShopEconomy economy = VMPlugin.getShopEconomy();
        BigDecimal payable = economy == null ? BigDecimal.ZERO : economy.floor(amount);
        if (payable.signum() > 0 && economy.deposit(player, payable, reason).isSuccess()) {
            amount = amount.subtract(payable);
            if (amount.signum() <= 0) {
                return;
            }
        } else {
            plugin.getLogger().info("Could not pay " + amount + " to " + player.getName() + " (" + reason
                    + "), it will be paid when it fits.");
        }
        // Whatever couldn't be paid (including amounts smaller than the smallest coin) is kept.
        owed.merge(player.getUniqueId(), amount, BigDecimal::add);
        save();
    }

    /** Tries to pay out what the player is owed. */
    public void tryPay(Player player) {
        BigDecimal amount = owed.get(player.getUniqueId());
        ShopEconomy economy = VMPlugin.getShopEconomy();
        if (amount == null || economy == null) {
            return;
        }
        // Pay what the economy can move; a remainder smaller than the smallest coin stays owed.
        BigDecimal payable = economy.floor(amount);
        if (payable.signum() <= 0) {
            return;
        }
        BigDecimal rest = amount.subtract(payable);
        // Write the new owed amount before paying, so a failed save can never lead to paying twice.
        if (rest.signum() > 0) {
            owed.put(player.getUniqueId(), rest);
        } else {
            owed.remove(player.getUniqueId());
        }
        if (!save()) {
            owed.put(player.getUniqueId(), amount);
            return;
        }
        if (economy.deposit(player, payable, "Money owed by a shop").isSuccess()) {
            player.sendMessage(ConfigManager.getCurrencyBuilder("messages.pending_money_paid")
                    .replaceCurrency("%amount%", payable).addPrefix().build());
        } else {
            owed.put(player.getUniqueId(), amount);
            save();
        }
    }

    private boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        owed.forEach((uuid, amount) -> yaml.set(uuid.toString(), amount.toPlainString()));
        try {
            yaml.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save pending_payouts.yml", e);
            return false;
        }
    }
}
