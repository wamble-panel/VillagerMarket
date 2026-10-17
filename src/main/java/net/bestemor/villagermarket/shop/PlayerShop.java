package net.bestemor.villagermarket.shop;

import net.bestemor.core.config.ConfigManager;
import net.bestemor.core.config.CurrencyBuilder;
import net.bestemor.villagermarket.VMPlugin;
import net.bestemor.villagermarket.event.AbandonShopEvent;
import net.bestemor.villagermarket.event.interact.BuyShopItemsEvent;
import net.bestemor.villagermarket.event.interact.SellShopItemsEvent;
import net.bestemor.villagermarket.event.interact.TradeShopItemsEvent;
import net.bestemor.villagermarket.economy.ShopEconomy;
import net.bestemor.villagermarket.menu.BuyShopMenu;
import net.bestemor.villagermarket.menu.StorageHolder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlayerShop extends VillagerShop {

    private final StorageHolder storageHolder;
    private final List<String> trustedPlayers;

    protected UUID ownerUUID;
    protected String ownerName;
    private boolean disableNotifications;

    public PlayerShop(VMPlugin plugin, File file) {
        super(plugin, file);
        super.collectedMoney = BigDecimal.valueOf(config.getDouble("collected_money"));

        String uuidString = config.getString("ownerUUID");
        String nameString = config.getString("ownerName");
        this.ownerUUID = uuidString == null || uuidString.equals("admin_shop") || uuidString.equals("null") ? null : UUID.fromString(uuidString);
        this.ownerName = nameString == null || nameString.equals("admin_shop") || nameString.equals("null") ? null : nameString;
        this.disableNotifications = config.getBoolean("disable_notifications");

        if (ownerUUID != null) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
            this.ownerName = owner.getName();
        }

        final List<ItemStack> items = new ArrayList<>();
        List<?> storage = getConfig().getList("storage");
        if (storage != null) {
            items.addAll((List<ItemStack>) storage);
        }
        this.storageHolder = new StorageHolder(plugin, this);
        this.storageHolder.loadItems(items);

        this.menus.put(ShopMenu.BUY_SHOP, new BuyShopMenu(plugin, this));

        this.trustedPlayers = config.getStringList("trusted");
        shopfrontHolder.load();
    }

    public void openStorage(Player player) {
        storageHolder.open(player);
    }

    public StorageHolder getStorageHolder() {
        return storageHolder;
    }

    @Override
    public void buyItem(ShopItem item, int amount, Player player) {
        BigDecimal price = item.getSellPrice(amount, true);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
        if (!item.verifyPurchase(player, ItemMode.SELL, amount, owner, storageHolder)) {
            return;
        }

        if (item.isItemTrade()) {
            TradeShopItemsEvent tradeShopItemsEvent = new TradeShopItemsEvent(player, this, item);
            Bukkit.getPluginManager().callEvent(tradeShopItemsEvent);
            if (tradeShopItemsEvent.isCancelled()) {
                return;
            }
            removeItems(player.getInventory(), item.getItemTrade(), item.getItemTradeAmount());
            keepOverflow(item.getItemTrade(), storageHolder.addItem(item.getItemTrade(), item.getItemTradeAmount()));

            if (owner.isOnline() && owner.getPlayer() != null && !disableNotifications) {
                Player ownerOnline = owner.getPlayer();
                ownerOnline.sendMessage(ConfigManager.getCurrencyBuilder("messages.sold_item_as_owner")
                        .replace("%price%", item.getItemTradeAmount() + "x " + " " + item.getItemTradeName())
                        .replace("%player%", player.getName())
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%item%", item.getItemName()).addPrefix().build());
            }
        } else {
            BuyShopItemsEvent buyShopItemsEvent = new BuyShopItemsEvent(player, this, item, amount);
            Bukkit.getPluginManager().callEvent(buyShopItemsEvent);
            if (buyShopItemsEvent.isCancelled()) {
                return;
            }
            if (noEconomy(player)) {
                return;
            }
            ShopEconomy economy = VMPlugin.getShopEconomy();
            BigDecimal taxAmount = taxOf(price);
            BigDecimal ownerShare = price.subtract(taxAmount);
            String reason = "Bought " + amount + "x " + plain(item.getItemName()) + " at " + plain(getShopName());

            // Take the money first. Nothing else happens unless the payment worked.
            ShopEconomy.Result result;
            if (ConfigManager.getBoolean("require_collect") && economy.canHoldIncome()) {
                result = economy.withdraw(player, price, reason);
                if (result.isSuccess()) {
                    super.collectedMoney = collectedMoney.add(ownerShare);
                    super.updateMenu(ShopMenu.EDIT_SHOP);
                }
            } else {
                result = economy.pay(player, incomeParts(ownerShare), taxAmount, reason);
            }
            if (!paymentOk(player, result, true)) {
                return;
            }
            shopStats.addEarned(price.doubleValue());


            if (owner.isOnline() && owner.getPlayer() != null) {
                Player ownerOnline = owner.getPlayer();
                CurrencyBuilder builder = ConfigManager.getCurrencyBuilder("messages.sold_item_as_owner")
                        .replace("%player%", player.getName())
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%item%", item.getItemName()).addPrefix();

                if (price.equals(BigDecimal.ZERO)) {
                    builder.replace("%price%", ConfigManager.getString("quantity.free"));
                } else {
                    builder.replaceCurrency("%price%", price);
                }
                ownerOnline.sendMessage(builder.build());

                if (taxAmount.doubleValue() > 0) {
                    BigDecimal t = new BigDecimal(String.valueOf(taxAmount));
                    ownerOnline.sendMessage(ConfigManager.getCurrencyBuilder("messages.tax").replaceCurrency("%tax%", t).addPrefix().build());
                }
            }

            BigDecimal left = economy.getBalance(player);
            player.sendMessage(ConfigManager.getCurrencyBuilder("messages.money_left").replaceCurrency("%amount%", left).addPrefix().build());
        }

        shopStats.addSold(amount);

        giveShopItem(player, item, amount);
        storageHolder.removeItem(item.getRawItem(), amount);

        CurrencyBuilder message = ConfigManager.getCurrencyBuilder("messages.bought_item_as_customer")
                .replace("%amount%", String.valueOf(amount))
                .replace("%item%", item.getItemName())
                .replace("%shop%", getShopName())
                .addPrefix();

        if (item.isItemTrade()) {
            message.replace("%price%", item.getItemTradeAmount() + "x " + item.getItemTradeName());
        } else {
            message.replaceCurrency("%price%", price);
        }
        player.sendMessage(message.build());

        player.playSound(player.getLocation(), ConfigManager.getSound("sounds.buy_item"), 1, 1);
        VMPlugin.log.add(new Date() + ": " + player.getName() + " bought " + amount + "x " + item.getType() + " from " + ownerName + " (" + price + ")");
        saveAfterTrade(player);
    }

    @Override
    public void sellItem(ShopItem item, int amount, Player player) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);

        BigDecimal price = item.getBuyPrice(amount, true);
        BigDecimal taxAmount = taxOf(price);

        if (!item.verifyPurchase(player, ItemMode.BUY, amount, Bukkit.getOfflinePlayer(ownerUUID), storageHolder)) {
            return;
        }
        SellShopItemsEvent sellShopItemsEvent = new SellShopItemsEvent(player, this, item, amount);
        Bukkit.getPluginManager().callEvent(sellShopItemsEvent);
        if (sellShopItemsEvent.isCancelled()) {
            return;
        }
        if (noEconomy(player)) {
            return;
        }
        ShopEconomy economy = VMPlugin.getShopEconomy();
        // The owner pays first (the customer gets the price minus tax). Items only move if that worked.
        String reason = "Sold " + amount + "x " + plain(item.getItemName()) + " to " + plain(getShopName());
        Map<UUID, BigDecimal> payout = new LinkedHashMap<>();
        payout.put(player.getUniqueId(), price.subtract(taxAmount));
        if (!paymentOk(player, economy.pay(owner, payout, taxAmount, reason), false)) {
            return;
        }
        removeItems(player.getInventory(), item.getRawItem(), amount);

        BigDecimal moneyLeft = economy.getBalance(player);
        keepOverflow(item.getRawItem(), storageHolder.addItem(item.getRawItem(), amount));
        shopStats.addBought(amount);
        shopStats.addSpent(price.doubleValue());


        player.playSound(player.getLocation(), ConfigManager.getSound("sounds.sell_item"), 0.5f, 1);
        player.sendMessage(ConfigManager.getCurrencyBuilder("messages.money_currently").replaceCurrency("%amount%", moneyLeft).build());

        if (taxAmount.doubleValue() > 0) {
            player.sendMessage(ConfigManager.getCurrencyBuilder("messages.tax").replaceCurrency("%tax%", taxAmount).addPrefix().build());
        }

        if (owner.isOnline() && owner.getPlayer() != null && !disableNotifications) {
            Player ownerOnline = owner.getPlayer();
            CurrencyBuilder builder = ConfigManager.getCurrencyBuilder("messages.bought_item_as_owner")
                    .replace("%player%", player.getName())
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%item%", item.getType().name().replaceAll("_", " ").toLowerCase()).addPrefix();

            if (price.equals(BigDecimal.ZERO)) {
                builder.replace("%price%", ConfigManager.getString("quantity.free"));
            } else {
                builder.replaceCurrency("%price%", price);
            }
            ownerOnline.sendMessage(builder.build());
        }

        player.sendMessage(ConfigManager.getCurrencyBuilder("messages.sold_item_as_customer")
                .replace("%amount%", String.valueOf(amount))
                .replaceCurrency("%price%", price)
                .replace("%item%", item.getItemName())
                .replace("%shop%", getShopName()).build());

        VMPlugin.log.add(new Date() + ": " + player.getName() + " sold " + amount + "x " + item.getType() + " to " + ownerName + " (" + price + ")");
        saveAfterTrade(player);
    }

    @Override
    public String getModeCycle(String mode, boolean isItemTrade) {
        return ConfigManager.getString("menus.edit_item.mode_cycle.player_shop." + (!isItemTrade ? mode : "item_trade"));
    }

    /**
     * Collects money
     */
    public void collectMoney(Player player) {
        if (noEconomy(player)) {
            return;
        }
        ShopEconomy economy = VMPlugin.getShopEconomy();
        BigDecimal amount = economy.floor(collectedMoney);
        if (amount.signum() > 0) {
            // Only take it out of the shop once it's safely in the player's bank.
            if (!paymentOk(player, economy.deposit(player, amount, "Collected income from " + plain(getShopName())), false)) {
                return;
            }
            super.collectedMoney = collectedMoney.subtract(amount);
            saveAfterTrade(player);
        }
        player.sendMessage(ConfigManager.getCurrencyBuilder("messages.collected_money").replaceCurrency("%amount%", amount).addPrefix().build());
        player.playSound(player.getLocation(), ConfigManager.getSound("sounds.collect_money"), 1, 1);
        super.updateMenu(ShopMenu.EDIT_SHOP);
    }

    /** Who gets the shop's income: the owner, or the owner and trusted players (distribute_income). */
    private Map<UUID, BigDecimal> incomeParts(BigDecimal amount) {
        ShopEconomy economy = VMPlugin.getShopEconomy();
        if (ConfigManager.getBoolean("distribute_income")) {
            List<UUID> trusted = trustedPlayers.stream().map(UUID::fromString).collect(Collectors.toList());
            return economy.split(amount, ownerUUID, trusted);
        }
        Map<UUID, BigDecimal> parts = new LinkedHashMap<>();
        parts.put(ownerUUID, amount);
        return parts;
    }

    /**
     * Saves the shop file and the player's data right after money moved, so a crash can't bring
     * back items or income that were already paid for (NetherBank saves the money immediately).
     */
    private void saveAfterTrade(Player player) {
        save();
        player.saveData();
    }

    /** Items that didn't fit in storage go to the owner's expired storage (/vm expired) instead of vanishing. */
    private void keepOverflow(ItemStack item, int amount) {
        if (amount <= 0 || ownerUUID == null) {
            return;
        }
        List<ItemStack> kept = new ArrayList<>();
        int left = amount;
        while (left > 0) {
            ItemStack stack = item.clone();
            int size = Math.min(left, stack.getMaxStackSize());
            stack.setAmount(size);
            kept.add(stack);
            left -= size;
        }
        plugin.getShopManager().getExpiredStorages().computeIfAbsent(ownerUUID, uuid -> new ArrayList<>()).addAll(kept);
        plugin.getLogger().warning(amount + "x " + item.getType() + " did not fit in the storage of " + plain(getShopName())
                + " and were moved to the owner's expired storage.");
    }

    private static String plain(String text) {
        return text == null ? "" : org.bukkit.ChatColor.stripColor(text);
    }

    /**
     * Deposits money to the Shop/Owner
     */
    public void depositOwner(BigDecimal amount) {
        // Kept for plugins using the old API: adds new money for the owner (and trusted players).
        if (ConfigManager.getBoolean("require_collect")) {
            super.collectedMoney = collectedMoney.add(amount);
            super.updateMenu(ShopMenu.EDIT_SHOP);
            return;
        }
        if (VMPlugin.getShopEconomy() == null) {
            return;
        }
        incomeParts(VMPlugin.getShopEconomy().floor(amount)).forEach((uuid, part) ->
                plugin.getPendingPayouts().payOrKeep(Bukkit.getOfflinePlayer(uuid), part, "Shop income"));
    }

    @Override
    public int getAvailable(ShopItem shopItem) {
        ShopEconomy economy = VMPlugin.getShopEconomy();
        OfflinePlayer owner = ownerUUID == null ? null : Bukkit.getOfflinePlayer(ownerUUID);

        ItemStack i = shopItem.isItemTrade() ? shopItem.getItemTrade() : shopItem.getRawItem();
        int available = storageHolder.getAvailableSpace(i);

        if (shopItem.getLimit() != 0) {
            int inStorage = storageHolder.getAmount(i);
            available = Math.min(shopItem.getLimit() - inStorage, available);
        }

        BigDecimal unitPrice = shopItem.getBuyPrice();
        // Menus refresh in the background; the bank can only be asked on the main thread. The real
        // check happens again on the main thread right before every trade (ShopItem#verifyPurchase).
        if (owner != null && !shopItem.isItemTrade() && unitPrice.signum() > 0 && Bukkit.isPrimaryThread()) {
            BigDecimal balance = economy == null ? BigDecimal.ZERO : economy.getBalance(owner);
            int affordable = balance.divide(unitPrice, 0, RoundingMode.FLOOR).min(BigDecimal.valueOf(Integer.MAX_VALUE / Math.max(1, shopItem.getAmount()))).intValue();
            available = Math.max(0, Math.min(available, affordable * shopItem.getAmount()));
        }

        if (shopItem.isItemTrade()) {
            int inStorage = storageHolder.getAmount(shopItem.getRawItem());
            available = available < shopItem.getItemTradeAmount() ? 0 : available;
            available = Math.min(inStorage, available);
        } else {
            available = available < shopItem.getAmount() ? 0 : available;
        }
        return available;
    }

    public void abandon() {
        Bukkit.getPluginManager().callEvent(new AbandonShopEvent(this));

        closeAllMenus();

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownerUUID);

        // Refunds go to the owner's bank, or wait in pending_payouts.yml until there's room. Never lost.
        if (cost != -1) {
            BigDecimal refund = BigDecimal.valueOf(getCost())
                    .multiply(BigDecimal.valueOf(ConfigManager.getDouble("refund_percent")))
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(timesRented));
            plugin.getPendingPayouts().payOrKeep(offlinePlayer, refund, "Refund for " + plain(getShopName()));
        }
        plugin.getPendingPayouts().payOrKeep(offlinePlayer, collectedMoney, "Collected income from " + plain(getShopName()));

        List<ItemStack> storage = storageHolder.getItems();
        if (!storage.isEmpty()) {
            if (plugin.getShopManager().getExpiredStorages().containsKey(ownerUUID)) {
                plugin.getShopManager().getExpiredStorages().get(ownerUUID).addAll(storage);
            } else {
                plugin.getShopManager().getExpiredStorages().put(ownerUUID, storage);
            }
        }

        plugin.getShopManager().resetShopEntity(entityUUID);

        if (offlinePlayer.isOnline()) {
            Player player = (Player) offlinePlayer;
            player.playSound(player.getLocation(), ConfigManager.getSound("sounds.expired"), 1, 1);
            player.sendMessage(ConfigManager.getMessage("messages.expired"));
        }

        VMPlugin.log.add(new Date() + ": " + offlinePlayer.getName() + " abandoned shop: " + entityUUID.toString());

        storageHolder.clear();
        trustedPlayers.clear();
        shopfrontHolder.closeAll();
        shopfrontHolder.clear();
        super.collectedMoney = BigDecimal.ZERO;
        this.ownerUUID = null;
        this.ownerName = null;
        super.shopStats = new ShopStats(config);
        super.timesRented = 0;
        super.updateMenu(ShopMenu.EDIT_SHOP);
    }

    /**
     * Increase rent time
     */
    public void increaseTime() {
        super.expireDate = expireDate.plusSeconds(seconds);
        this.timesRented++;
    }

    public boolean hasOwner() {
        return ownerUUID != null;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    /**
     * Returns true if rent has expired, false if not
     */
    public boolean hasExpired() {
        return seconds != 0 && expireDate != null && expireDate.isBefore(Instant.now());
    }

    @Override
    public void closeAllMenus() {
        storageHolder.closeAll();
        super.closeAllMenus();
    }

    @Override
    public void save() {
        config.set("storage", storageHolder.getItems());
        config.set("trusted", trustedPlayers);
        config.set("disable_notifications", disableNotifications);

        config.set("ownerUUID", ownerUUID == null ? null : ownerUUID.toString());
        config.set("ownerName", ownerName);

        super.save();
    }

    /**
     * Sets owner and changes name
     */
    public void setOwner(Player player) {
        this.expireDate = (seconds == 0 ? null : Instant.now().plusSeconds(seconds));

        this.ownerName = player.getName();
        setShopName(ConfigManager.getString("villager.name_taken").replace("%player%", player.getName()));
        this.ownerUUID = player.getUniqueId();

        super.timesRented = 1;
    }

    /**
     * Adds trusted to the trusted list
     */
    public void addTrusted(Player player) {
        trustedPlayers.add(player.getUniqueId().toString());
    }

    /**
     * Removes trusted from the trusted list
     */
    public void removeTrusted(Player player) {
        trustedPlayers.remove(player.getUniqueId().toString());
    }

    /**
     * Returns true if player is trusted, false if not
     */
    public boolean isTrusted(Player player) {
        return trustedPlayers.contains(player.getUniqueId().toString());
    }

    public boolean isDisableNotifications() {
        return disableNotifications;
    }

    public void setDisableNotifications(boolean disableNotifications) {
        this.disableNotifications = disableNotifications;
    }
}
