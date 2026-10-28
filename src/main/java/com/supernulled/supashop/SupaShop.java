package com.supernulled.supashop;

import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public final class SupaShop extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private File balancesFile;
    private YamlConfiguration balances;
    private YamlConfiguration prices;
    private YamlConfiguration guiConfig;
    private NamespacedKey actionKey;
    private NamespacedKey idKey;
    private final Map<UUID, Purchase> pending = new HashMap<>();
    private final MiniMessage mini = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private record Purchase(String kind, String id, int price, Material material) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("gui.yml", false);
        saveResource("prices.yml", false);
        loadGuiConfig();
        loadPrices();
        actionKey = new NamespacedKey(this, "action");
        idKey = new NamespacedKey(this, "item_id");
        loadBalances();
        setupEconomy();
        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("shardshop")).setExecutor(this);
        Objects.requireNonNull(getCommand("sell")).setExecutor(this);
        Objects.requireNonNull(getCommand("worth")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SupaShop enabled. Vault economy: " + (economy != null));
    }


    private void loadPrices() {
        File file = new File(getDataFolder(), "prices.yml");
        prices = YamlConfiguration.loadConfiguration(file);
    }

    private void openSellGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, color(guiConfig.getString("sell.title", "ѕᴇʟʟ ɪᴛᴇᴍѕ")));
        p.openInventory(inv);
    }

    private void showWorth(Player p) {
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            String msg = getConfig().getString("sell.messages.not-holding-item", "<red>You're not holding an item.");
            p.sendMessage(color(msg)); p.sendActionBar(color(msg)); return;
        }
        double value = itemWorth(item, true);
        if (value <= 0) {
            String msg = getConfig().getString("sell.messages.not-sellable", "<red>This item is not sellable.");
            p.sendMessage(color(msg)); p.sendActionBar(color(msg)); return;
        }
        String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? plain(item.getItemMeta().displayName()) : human(item.getType().name());
        String msg = getConfig().getString("sell.messages.worth", "<#01a5fc>%amount% %itemName% is worth <#01fd00>$%formattedWorth%")
                .replace("%amount%", String.valueOf(item.getAmount())).replace("%itemName%", itemName)
                .replace("%formattedWorth%", formatCompact(value));
        p.sendMessage(color(msg)); p.sendActionBar(color(msg));
    }

    private void onSellClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (!plain(event.getView().title()).equals(plain(color(guiConfig.getString("sell.title", "ѕᴇʟʟ ɪᴛᴇᴍѕ"))))) return;
        Inventory inv = event.getInventory();
        double total = 0;
        List<ItemStack> unsellable = new ArrayList<>();
        for (ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            double worth = itemWorth(stack, true);
            if (worth > 0) total += worth;
            else unsellable.add(stack.clone());
        }
        inv.clear();
        if (total > 0 && economy != null) {
            economy.depositPlayer(p, total);
            String message = getConfig().getString("sell.messages.sold", "<#7afc00>+$%formatted%").replace("%formatted%", formatCompact(total));
            p.sendMessage(color(message)); p.sendActionBar(color(message)); play(p, "sounds.sell-success");
        } else if (total > 0) {
            p.sendMessage(color("<red>Sell economy is unavailable. Items were returned."));
            for (ItemStack stack : inv.getContents()) if (stack != null && !stack.getType().isAir()) unsellable.add(stack.clone());
        }
        for (ItemStack item : unsellable) {
            Map<Integer, ItemStack> leftovers = p.getInventory().addItem(item);
            leftovers.values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        if (!unsellable.isEmpty()) {
            String message = getConfig().getString("sell.messages.not-sellable", "<red>This item is not sellable.");
            p.sendMessage(color(message)); p.sendActionBar(color(message));
        }
    }

    private double itemWorth(ItemStack item, boolean includeShulkerContents) {
        if (item == null || item.getType().isAir()) return 0;
        if (item.getType() == Material.ENCHANTED_BOOK && item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            return enchantmentWorth(meta.getStoredEnchants()) * item.getAmount();
        }
        double base = prices.getDouble("prices." + humanKey(item.getType().name()), -1);
        if (base < 0) return 0;
        double enchants = item.getEnchantments().entrySet().stream().mapToDouble(e -> enchantmentPrice(e.getKey(), e.getValue())).sum();
        double contents = 0;
        if (includeShulkerContents && item.getItemMeta() instanceof BlockStateMeta meta
                && meta.getBlockState() instanceof ShulkerBox shulker) {
            for (ItemStack inside : shulker.getInventory().getContents()) {
                if (inside == null || inside.getType().isAir()) continue;
                double insideValue = itemWorth(inside, false);
                if (insideValue > 0) contents += insideValue;
            }
        }
        return (base + enchants + contents) * item.getAmount();
    }

    private double enchantmentWorth(Map<Enchantment, Integer> enchantments) {
        double total = 0;
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) total += enchantmentPrice(entry.getKey(), entry.getValue());
        return total;
    }

    private double enchantmentPrice(Enchantment enchantment, int level) {
        return prices.getDouble("enchants." + enchantment.getKey().getKey().replace('_', ' ') + " " + level, 0);
    }

    private String humanKey(String materialName) { return materialName.toLowerCase(Locale.ROOT).replace('_', ' '); }
    private String formatCompact(double value) {
        String[] suffix = {"", "K", "M", "B", "T", "Q"};
        int index = 0;
        while (Math.abs(value) >= 1000 && index < suffix.length - 1) { value /= 1000; index++; }
        java.text.DecimalFormat df = new java.text.DecimalFormat(value >= 100 ? "#,##0" : value >= 10 ? "#,##0.0" : "#,##0.##");
        return df.format(value) + suffix[index];
    }

    private void setupEconomy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();
        if (economy == null) getLogger().warning("Vault economy not found. Money-shop purchases will be unavailable.");
    }

    private void loadBalances() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        balancesFile = new File(getDataFolder(), "shards.yml");
        balances = YamlConfiguration.loadConfiguration(balancesFile);
    }
    private void saveBalances() {
        try { balances.save(balancesFile); } catch (IOException e) { getLogger().severe("Could not save shards.yml: " + e.getMessage()); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("supashop.admin")) { sender.sendMessage(color("<red>You don't have permission.")); return true; }
            reloadConfig(); loadGuiConfig(); loadPrices(); setupEconomy(); sender.sendMessage(color("<green>SupaShop configuration reloaded.")); return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used by a player."); return true; }
        if (!player.hasPermission("supashop.use")) { player.sendMessage(color("<red>You don't have permission.")); return true; }
        if (command.getName().equalsIgnoreCase("sell")) { openSellGui(player); return true; }
        if (command.getName().equalsIgnoreCase("worth")) { showWorth(player); return true; }
        if (command.getName().equalsIgnoreCase("shardshop")) openShardShop(player); else openMain(player);
        return true;
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(null, guiConfig.getInt("layouts.main.size", 27), title("titles.main"));
        set(inv, 11, Material.END_STONE, "<#00F986>ᴇɴᴅ", "end");
        set(inv, 12, Material.NETHERRACK, "<#00F986>ɴᴇᴛʜᴇʀ", "nether");
        set(inv, 13, Material.TOTEM_OF_UNDYING, "<#00F986>ɢᴇᴀʀ", "gear");
        set(inv, 14, Material.COOKED_BEEF, "<#00F986>ꜰᴏᴏᴅ", "food");
        set(inv, 15, Material.AMETHYST_SHARD, "<#A303F9>ꜱʜᴀʀᴅ ꜱʜᴏᴘ", "shard");
        p.openInventory(inv);
    }

    private void openCategory(Player p, String category) {
        String path = "shop." + category;
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null) return;
        Inventory inv = Bukkit.createInventory(null, guiConfig.getInt("layouts.category.size", 27), title("titles." + category));
        back(inv);
        int slot = guiConfig.getInt("layouts.category.first-item-slot", 9);
        int lastSlot = guiConfig.getInt("layouts.category.last-item-slot", 17);
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || slot > lastSlot) continue;
            int price = Math.max(0, section.getInt(key));
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.lore(List.of(color("<white>Buy Price: <#4FF750>$" + format(price))));
            item.setItemMeta(meta);
            item = tagged(item, "money:" + category + ":" + key);
            inv.setItem(slot++, item);
        }
        p.openInventory(inv);
    }

    private void openShardShop(Player p) {
        Inventory inv = Bukkit.createInventory(null, guiConfig.getInt("layouts.shard.size", 27), title("titles.shard"));
        ConfigurationSection keys = getConfig().getConfigurationSection("shard-shop.keys");
        int slot = guiConfig.getInt("layouts.shard.first-key-slot", 2);
        int lastKeySlot = guiConfig.getInt("layouts.shard.last-key-slot", 6);
        if (keys != null) for (String id : keys.getKeys(false)) {
            if (slot > lastKeySlot) break;
            String display = keys.getString(id + ".display", id + " Key");
            ItemStack item = named(Material.TRIPWIRE_HOOK, display, "<white>Buy Price: <#A303F9>" + keys.getInt(id + ".price") + "x Shards");
            inv.setItem(slot, tagged(item, "key:" + id)); slot++;
        }
        ConfigurationSection spawners = getConfig().getConfigurationSection("shard-shop.spawners");
        slot = guiConfig.getInt("layouts.shard.first-spawner-slot", 9);
        int lastSpawnerSlot = guiConfig.getInt("layouts.shard.last-spawner-slot", 17);
        if (spawners != null) for (String id : spawners.getKeys(false)) {
            if (slot > lastSpawnerSlot) break;
            String display = spawners.getString(id + ".display", "<light_purple>Spawner");
            ItemStack item = named(Material.SPAWNER, display, "<yellow>" + id.replace('_', ' ') + "</yellow>", "<white>Buy Price: <#A303F9>" + spawners.getInt(id + ".price") + "x Shards");
            inv.setItem(slot, tagged(item, "spawner:" + id)); slot++;
        }
        back(inv); p.openInventory(inv);
    }

    private void openConfirm(Player p, String kind, String id) {
        String path = "shard-shop." + (kind.equals("key") ? "keys." : "spawners.") + id;
        int price = getConfig().getInt(path + ".price", -1);
        if (price < 0) return;
        Purchase purchase = new Purchase(kind, id, price, kind.equals("spawner") ? Material.SPAWNER : Material.TRIPWIRE_HOOK);
        pending.put(p.getUniqueId(), purchase);
        Inventory inv = Bukkit.createInventory(null, guiConfig.getInt("layouts.confirm.size", 27), title("titles.confirm"));
        inv.setItem(guiConfig.getInt("layouts.confirm.preview-slot", 13), tagged(named(purchase.material(), getConfig().getString(path + ".display", id), "<white>Price: <#A303F9>" + price + " Shards"), "preview"));
        inv.setItem(guiConfig.getInt("layouts.confirm.cancel.slot", 11), tagged(named(Material.matchMaterial(guiConfig.getString("layouts.confirm.cancel.material", "RED_STAINED_GLASS_PANE")), guiConfig.getString("layouts.confirm.cancel.display", "<#fd0100>ᴄᴀɴᴄᴇʟ"), guiConfig.getString("layouts.confirm.cancel.lore", "<white>Click to cancel")), "cancel"));
        inv.setItem(guiConfig.getInt("layouts.confirm.confirm-button.slot", 15), tagged(named(Material.matchMaterial(guiConfig.getString("layouts.confirm.confirm-button.material", "LIME_STAINED_GLASS_PANE")), guiConfig.getString("layouts.confirm.confirm-button.display", "<#01fc01>ᴄᴏɴꜰɪʀᴍ"), guiConfig.getString("layouts.confirm.confirm-button.lore", "<white>Click to buy")), "confirm"));
        p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String view = plain(event.getView().title());
        if (view.equals(plain(color(guiConfig.getString("sell.title", "ѕᴇʟʟ ɪᴛᴇᴍѕ"))))) return;
        if (!isOurTitle(view)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        play(p, "sounds.click");
        if (view.equals(plain(title("titles.main")))) {
            if (action.equals("shard")) openShardShop(p);
            else if (List.of("end", "nether", "gear", "food").contains(action)) openCategory(p, action);
            return;
        }
        if (action.equals("back")) { openMain(p); return; }
        if (view.equals(plain(title("titles.shard")))) {
            if (action.startsWith("key:")) openConfirm(p, "key", action.substring(4));
            else if (action.startsWith("spawner:")) openConfirm(p, "spawner", action.substring(8));
            return;
        }
        if (view.equals(plain(title("titles.confirm")))) {
            if (action.equals("cancel")) { pending.remove(p.getUniqueId()); openShardShop(p); return; }
            if (action.equals("confirm")) { finishShardPurchase(p); return; }
        }
        if (view.startsWith("ѕʜᴏᴘ")) {
            if (action.startsWith("money:")) {
                String[] parts = action.split(":", 3);
                if (parts.length == 3) buyMoneyItem(p, parts[1], parts[2]);
            }
        }
    }

    @EventHandler public void onSellCloseEvent(org.bukkit.event.inventory.InventoryCloseEvent event) { onSellClose(event); }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (isOurTitle(plain(event.getView().title()))) event.setCancelled(true);
    }

    private void buyMoneyItem(Player p, String category, String materialName) {
        if (economy == null) { p.sendMessage(message("messages.prefix").append(color("<red>Shop economy is not available. Install Vault and an economy provider."))); return; }
        Material material = Material.matchMaterial(materialName);
        if (material == null) return;
        int price = getConfig().getInt("shop." + category + "." + materialName, -1);
        if (price < 0) return;
        ItemStack item = new ItemStack(material);
        if (p.getInventory().firstEmpty() == -1) { p.sendMessage(message("messages.inventory-full")); play(p, "sounds.failure"); return; }
        if (economy.getBalance(p) < price) { p.sendMessage(message("messages.not-enough-money")); play(p, "sounds.failure"); return; }
        if (!economy.withdrawPlayer(p, price).transactionSuccess()) { p.sendMessage(message("messages.not-enough-money")); return; }
        p.getInventory().addItem(item);
        p.sendMessage(replaceMessage("messages.purchase-success", Map.of("%amount%", "1", "%item%", human(materialName), "%price%", format(price))));
        play(p, "sounds.success");
    }

    private void finishShardPurchase(Player p) {
        Purchase purchase = pending.remove(p.getUniqueId());
        if (purchase == null) { openShardShop(p); return; }
        String balancePath = p.getUniqueId() + ".balance";
        int balance = balances.getInt(balancePath, getConfig().getInt("currency.shards.starting-balance", 0));
        if (balance < purchase.price()) {
            p.sendMessage(message("messages.not-enough-shards")); play(p, "sounds.failure"); openShardShop(p); return;
        }
        if (purchase.kind().equals("spawner")) {
            String command = resolveSpawnerCommand(p, purchase.id());
            if (command == null || command.isBlank()) {
                p.sendMessage(message("messages.provider-missing")); play(p, "sounds.failure"); openShardShop(p); return;
            }
            // Do not charge the player unless a configured provider command is available.
            balances.set(balancePath, balance - purchase.price()); saveBalances();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } else {
            String command = getConfig().getString("shard-shop.keys." + purchase.id() + ".command", "");
            if (command.isBlank()) { p.sendMessage(color("<red>No command configured for this key.")); openShardShop(p); return; }
            balances.set(balancePath, balance - purchase.price()); saveBalances();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", p.getName()));
        }
        p.sendMessage(replaceMessage("messages.shard-purchase-success", Map.of("%item%", human(purchase.id()), "%price%", format(purchase.price()))));
        play(p, "sounds.success"); openShardShop(p);
    }

    private String resolveSpawnerCommand(Player p, String id) {
        String base = "shard-shop.spawners." + id;
        String entity = getConfig().getString(base + ".entity", id.toLowerCase(Locale.ROOT));
        String override = getConfig().getString("spawner-provider.command-overrides." + id, "");
        String template = override;
        if (template.isBlank() && getConfig().getBoolean("spawner-provider.auto-detect", true)) {
            for (Map<?, ?> provider : getConfig().getMapList("spawner-provider.providers")) {
                Object pluginObj = provider.get("plugin");
                if (pluginObj == null || !Bukkit.getPluginManager().isPluginEnabled(String.valueOf(pluginObj))) continue;
                Object commandsObj = provider.get("commands");
                if (commandsObj instanceof Map<?, ?> commands) {
                    Object command = commands.get(id);
                    if (command == null) command = commands.get("default");
                    if (command != null) { template = String.valueOf(command); break; }
                }
            }
        }
        if (template.isBlank()) template = getConfig().getString("spawner-provider.fallback-command", "");
        if (template.isBlank()) return null;
        return template.replace("%player%", p.getName()).replace("%entity%", entity).replace("%amount%", "1");
    }

    private void set(Inventory inv, int slot, Material material, String display, String action) {
        inv.setItem(slot, tagged(named(material, display, "<white>Click to view"), action));
    }
    private void back(Inventory inv) {
        String path = plain(inv.getViewers().isEmpty() ? Component.empty() : inv.getViewers().get(0).getOpenInventory().title()).equals(plain(title("titles.shard"))) ? "layouts.shard.back" : "layouts.category.back";
        Material material = Material.matchMaterial(guiConfig.getString(path + ".material", "RED_STAINED_GLASS_PANE"));
        inv.setItem(guiConfig.getInt(path + ".slot", 18), tagged(named(material == null ? Material.RED_STAINED_GLASS_PANE : material, guiConfig.getString(path + ".display", "<#fd0100>ʙᴀᴄᴋ"), guiConfig.getString(path + ".lore", "<white>Click to return")), "back"));
    }
    private ItemStack named(Material material, String display, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(display));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(this::color).toList());
        item.setItemMeta(meta); return item;
    }
    private ItemStack tagged(ItemStack item, String action) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta); return item;
    }
    private void loadGuiConfig() { guiConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "gui.yml")); }
    private Component title(String path) { return color(guiConfig.getString(path, getConfig().getString(path, "Shop"))); }
    private Component color(String text) {
        if (text == null) return Component.empty();
        Component component;
        if (text.contains("<#") || text.contains("<red>") || text.contains("<white>") || text.contains("<green>") || text.contains("<yellow>") || text.contains("<dark_gray>") || text.contains("<light_purple>")) component = mini.deserialize(text);
        else component = LEGACY.deserialize(text);
        return component.decoration(TextDecoration.ITALIC, false);
    }
    private String plain(Component component) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component); }
    private boolean isOurTitle(String title) {
        for (String path : List.of("titles.main", "titles.end", "titles.nether", "titles.gear", "titles.food", "titles.shard", "titles.confirm")) {
            if (plain(title(path)).equals(title)) return true;
        }
        return title.startsWith("ѕʜᴏᴘ") || title.startsWith("ᴄᴏɴꜰɪʀᴍ");
    }
    private Component message(String path) { return color(getConfig().getString(path, "")); }
    private Component replaceMessage(String path, Map<String,String> replacements) {
        String text = getConfig().getString(path, "");
        for (Map.Entry<String,String> entry : replacements.entrySet()) text = text.replace(entry.getKey(), entry.getValue());
        return color(text);
    }
    private void play(Player p, String path) {
        try { p.playSound(p.getLocation(), Sound.valueOf(getConfig().getString(path, "UI_BUTTON_CLICK")), (float)getConfig().getDouble("sounds.volume", 0.5), 1f); }
        catch (IllegalArgumentException ignored) { }
    }
    private String format(int value) { return new DecimalFormat("#,##0").format(value); }
    private String human(String value) { String[] words = value.toLowerCase(Locale.ROOT).replace('_', ' ').split(" "); StringBuilder result = new StringBuilder(); for (String word : words) { if (word.isEmpty()) continue; if (!result.isEmpty()) result.append(' '); result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); } return result.toString(); }
}
