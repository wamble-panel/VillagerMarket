package net.bestemor.villagermarket.economy;

import com.wamble.netherbank.api.NetherBankEconomy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shops paid in netherite through NetherBank's API (all-or-nothing payments). */
public final class NetherBankShopEconomy implements ShopEconomy {

    private final NetherBankEconomy bank;

    private NetherBankShopEconomy(NetherBankEconomy bank) {
        this.bank = bank;
    }

    /** Only call when the NetherBank plugin is enabled (its classes don't exist otherwise). */
    public static ShopEconomy create() {
        NetherBankEconomy bank = Bukkit.getServicesManager().load(NetherBankEconomy.class);
        return bank == null ? null : new NetherBankShopEconomy(bank);
    }

    @Override
    public String getName() {
        return "NetherBank";
    }

    @Override
    public boolean canHoldIncome() {
        return false;
    }

    @Override
    public BigDecimal step() {
        return BigDecimal.valueOf(bank.step());
    }

    @Override
    public BigDecimal getBalance(OfflinePlayer player) {
        return BigDecimal.valueOf(bank.getBalance(player));
    }

    @Override
    public boolean has(OfflinePlayer player, BigDecimal amount) {
        return bank.has(player, amount.doubleValue());
    }

    @Override
    public Result withdraw(OfflinePlayer player, BigDecimal amount, String reason) {
        if (amount.signum() == 0) {
            return Result.SUCCESS;
        }
        return map(bank.withdraw(player, amount.doubleValue(), reason));
    }

    @Override
    public Result deposit(OfflinePlayer player, BigDecimal amount, String reason) {
        if (amount.signum() == 0) {
            return Result.SUCCESS;
        }
        return map(bank.deposit(player, amount.doubleValue(), reason));
    }

    @Override
    public Result pay(OfflinePlayer from, Map<UUID, BigDecimal> to, BigDecimal burned, String reason) {
        Map<UUID, Double> parts = new LinkedHashMap<>();
        to.forEach((uuid, amount) -> parts.merge(uuid, amount.doubleValue(), Double::sum));
        return map(bank.pay(from, parts, burned.doubleValue(), reason));
    }

    private static Result map(NetherBankEconomy.Result result) {
        return switch (result) {
            case SUCCESS -> Result.SUCCESS;
            case INSUFFICIENT_FUNDS -> Result.NOT_ENOUGH_MONEY;
            case RECEIVER_FULL -> Result.RECEIVER_FULL;
            case NO_ROOM_FOR_CHANGE -> Result.NO_ROOM_FOR_CHANGE;
            default -> Result.FAILED;
        };
    }
}
