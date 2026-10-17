package net.bestemor.villagermarket;

import net.bestemor.core.CorePlugin;
import net.bestemor.core.command.CommandModule;
import net.bestemor.core.config.ConfigManager;
import net.bestemor.villagermarket.command.ShopCommand;
import net.bestemor.villagermarket.command.subcommand.*;
import net.bestemor.villagermarket.command.subcommand.TeleportCommand;
import net.bestemor.villagermarket.command.subcommand.ListCommand;
import net.bestemor.villagermarket.command.subcommand.SpawnCommand;
import net.bestemor.villagermarket.economy.NetherBankShopEconomy;
import net.bestemor.villagermarket.economy.PendingPayouts;
import net.bestemor.villagermarket.economy.ShopEconomy;
import net.bestemor.villagermarket.economy.VaultShopEconomy;
import net.bestemor.villagermarket.listener.EntityListener;
import net.bestemor.villagermarket.listener.PlayerListener;
import net.bestemor.villagermarket.shop.ShopManager;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class VMPlugin extends CorePlugin {

    private static Economy economy = null;
    private static ShopEconomy shopEconomy = null;
    private PendingPayouts pendingPayouts;

    public static final List<String> log = new ArrayList<>();

    private ShopManager shopManager;
    private PlayerListener playerListener;

    private final Map<String, String> localizedMaterials = new HashMap<>();

    @Override
    protected void onPluginEnable() {
        setupEconomy();
        this.pendingPayouts = new PendingPayouts(this);

        Metrics metrics = new Metrics(this, 8922);

        ConfigManager.setPrefixPath("plugin_prefix");

        setupCommands();

        this.shopManager = new ShopManager(this);
        Bukkit.getScheduler().runTaskAsynchronously(this, shopManager::load);

        this.playerListener = new PlayerListener(this);
        registerEvents();

        boolean enableUpdate = !getConfig().contains("auto_update") || getConfig().getBoolean("auto_update");
        Bukkit.getLogger().info("[VillagerMarket] Auto config update is " + (enableUpdate ? "enabled" : "disabled"));

        //Bukkit.getLogger().warning("[VillagerMarket] §cYou are running a §aBETA 1.13.0-#3 of VillagerMarket! Please expect and report all bugs in my discord server");

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getPluginManager().getPlugin("VillagerBank") != null) {
                Bukkit.getLogger().info("[VillagerMarket] Nice to see you Villager Bank!");
                Bukkit.getLogger().info("[VillagerBank] You too Villager Market!");
            }
        }, 31);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderManager(this).register();
        }
        loadMappings();

        VillagerMarketAPI.init(this);
    }

    @Override
    protected String[] getLanguages() {
        return new String[]{"en_US", "de_DE", "es_ES", "pt_BR", "zh_CN", "fr_FR"};
    }

    @Override
    protected String getLanguageFolder() {
        return "language";
    }

    @Override
    protected void onPluginDisable() {
        shopManager.closeAllShopfronts();
        shopManager.saveAll();
        if (getConfig().getBoolean("auto_log")) saveLog();
    }

    private void loadMappings() {
        localizedMaterials.clear();
        File materials = new File(getDataFolder(), "materials.yml");
        if (materials.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(materials);
            ConfigurationSection section = config.getConfigurationSection("materials");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    localizedMaterials.put(key, section.getString(key));
                }
            }
        }
    }

    private void setupCommands() {
        CommandModule module = new CommandModule.Builder(this)
                .addSubCommand("create", new CreateCommand(this))
                .addSubCommand("item", new ItemCommand(this))
                .addSubCommand("move", new MoveCommand(this))
                .addSubCommand("teleport", new TeleportCommand(this))
                .addSubCommand("spawn", new SpawnCommand(this))
                .addSubCommand("list", new ListCommand(this))
                .addSubCommand("reload", new ReloadCommand(this))
                .addSubCommand("remove", new RemoveCommand(this))
                .addSubCommand("search", new SearchCommand(this))
                .addSubCommand("stats", new StatsCommand(this))
                .addSubCommand("trusted", new TrustedCommand(this))
                .addSubCommand("getid", new GetIDCommand(this))
                .addSubCommand("expiredstorage", new ExpiredStorageCommand(this))
                .addSubCommand("clone", new CloneCommand(this))
                .addSubCommand("setsize", new SetSizeCommand(this))
                .addSubCommand("toggleperm", new ToggleRequirePermissionCommand(this))
                .addSubCommand("open", new OpenCommand(this))
                .permissionPrefix("villagermarket.command")
                .build();

        if (!ConfigManager.getString("default_admin_shop").isEmpty()) {
            getCommand("shop").setExecutor(new ShopCommand(this));
        }
        module.register("vm");
    }

    public void reloadConfiguration() {
        reloadConfig();
        loadMappings();
    }

    /**
     * Picks the money system: NetherBank (netherite) when installed, otherwise Vault.
     * config.yml "economy" can force one: auto, netherbank or vault.
     */
    private void setupEconomy() {
        String mode = getConfig().getString("economy", "auto").toLowerCase(Locale.ROOT);
        if (!mode.equals("vault") && Bukkit.getPluginManager().isPluginEnabled("NetherBank")) {
            shopEconomy = NetherBankShopEconomy.create();
        }
        if (shopEconomy == null && !mode.equals("netherbank") && Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(Economy.class);
            if (economyProvider != null) {
                economy = economyProvider.getProvider();
                shopEconomy = new VaultShopEconomy(economy);
            }
        }
        if (shopEconomy == null) {
            Bukkit.getLogger().severe("[VillagerMarket] No economy found! Install NetherBank, or Vault with an economy plugin.");
        } else {
            Bukkit.getLogger().info("[VillagerMarket] Using " + shopEconomy.getName() + " for shop payments.");
        }
    }

    /**
     * Registers event listeners
     */
    private void registerEvents() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        pluginManager.registerEvents(new EntityListener(this), this);
        pluginManager.registerEvents(playerListener, this);
    }

    @Override
    protected int getSpigotResourceID() {
        return 82965;
    }

    /**
     * Saves log to /log/ folder and clears log
     */
    public void saveLog() {
        String fileName = new Date().toString().replace(":", "-");
        File file = new File(getDataFolder() + "/logs/" + fileName + ".yml");
        FileConfiguration logConfig = YamlConfiguration.loadConfiguration(file);
        logConfig.set("log", log);

        try {
            logConfig.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        log.clear();
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public PlayerListener getPlayerListener() {
        return playerListener;
    }

    public String getLocalizedMaterial(String material) {
        return localizedMaterials.get(material);
    }

    /** The raw Vault economy, or null when NetherBank is used. Prefer {@link #getShopEconomy()}. */
    public static Economy getEconomy() {
        return economy;
    }

    /** The money system shops use (NetherBank or Vault), or null if there is none. */
    public static ShopEconomy getShopEconomy() {
        return shopEconomy;
    }

    public PendingPayouts getPendingPayouts() {
        return pendingPayouts;
    }

    public boolean isCitizensEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }
}
