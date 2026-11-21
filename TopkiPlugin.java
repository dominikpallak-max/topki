package pl.topki;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

public class TopkiPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    enum Category {
        KILLS("Topka zabójstw", "Zabójstwa innych graczy", Material.DIAMOND_SWORD, NamedTextColor.RED),
        PLAYTIME("Topka czasu gry", "Czas spędzony na serwerze", Material.CLOCK, NamedTextColor.AQUA),
        DEATHS("Topka śmierci", "Liczba Twoich śmierci", Material.BONE, NamedTextColor.GRAY),
        KILLSTREAK("Topka killstreak", "Najdłuższa seria zabójstw bez śmierci", Material.TOTEM_OF_UNDYING, NamedTextColor.GOLD),
        STONE("Topka wykopanego kamienia", "Ilość wykopanego kamienia", Material.COBBLESTONE, NamedTextColor.WHITE),
        PEARLS("Topka rzuconych pereł", "Liczba rzuconych pereł", Material.ENDER_PEARL, NamedTextColor.DARK_AQUA);

        final String title, desc;
        final Material icon;
        final NamedTextColor color;

        Category(String title, String desc, Material icon, NamedTextColor color) {
            this.title = title;
            this.desc = desc;
            this.icon = icon;
            this.color = color;
        }
    }

    enum MenuType { MAIN, TOP }

    static class MenuHolder implements InventoryHolder {
        final MenuType type;
        final Category cat;
        final int page;
        Inventory inv;

        MenuHolder(MenuType type, Category cat, int page) {
            this.type = type;
            this.cat = cat;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class Board {
        final String id;
        final Category cat;
        final String world;
        final int x, y, z;
        final float yaw;
        UUID textId, interId;

        Board(String id, Category cat, String world, int x, int y, int z, float yaw) {
            this.id = id;
            this.cat = cat;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    static class Stats {
        private final Map<UUID, EnumMap<Category, Long>> data = new HashMap<>();
        private final Map<UUID, Integer> streak = new HashMap<>();

        long get(UUID id, Category c) {
            EnumMap<Category, Long> m = data.get(id);
            return m == null ? 0L : m.getOrDefault(c, 0L);
        }

        void add(UUID id, Category c, long n) {
            data.computeIfAbsent(id, k -> new EnumMap<>(Category.class)).merge(c, n, Long::sum);
        }

        void set(UUID id, Category c, long n) {
            data.computeIfAbsent(id, k -> new EnumMap<>(Category.class)).put(c, n);
        }

        List<UUID> ranked(Category c) {
            return data.keySet().stream()
                    .filter(id -> get(id, c) > 0)
                    .sorted(Comparator.comparingLong((UUID id) -> get(id, c)).reversed())
                    .toList();
        }

        int addStreak(UUID id) {
            return streak.merge(id, 1, Integer::sum);
        }

        void resetStreak(UUID id) {
            streak.remove(id);
        }

        void clear() {
            data.clear();
            streak.clear();
        }

        void load(File f) {
            if (!f.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection root = y.getConfigurationSection("players");
            if (root == null) return;
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                UUID id = UUID.fromString(key);
                for (Category c : Category.values()) set(id, c, s.getLong(c.name()));
            }
        }

        void save(File f) {
            YamlConfiguration y = new YamlConfiguration();
            for (UUID id : data.keySet())
                for (Category c : Category.values())
                    y.set("players." + id + "." + c.name(), get(id, c));
            try {
                y.save(f);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final Stats stats = new Stats();
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private NamespacedKey boardKey;
    private File statsFile, boardsFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boardKey = new NamespacedKey(this, "board");
        statsFile = new File(getDataFolder(), "stats.yml");
        boardsFile = new File(getDataFolder(), "boards.yml");

        stats.load(statsFile);
        loadBoards();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("topki").setExecutor(this);
        getCommand("topki").setTabCompleter(this);

        // +1 sekunda czasu gry co sekundę dla graczy online
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers())
                stats.add(p.getUniqueId(), Category.PLAYTIME, 1);
        }, 20L, 20L);

        // odświeżanie tablic co 2 s
        getServer().getScheduler().runTaskTimer(this, this::refreshBoards, 40L, 40L);

        // zapis co 5 min
        getServer().getScheduler().runTaskTimer(this, () -> stats.save(statsFile), 6000L, 6000L);

        getServer().getScheduler().runTaskLater(this, () -> boards.values().forEach(this::spawnBoard), 1L);
    }

    @Override
    public void onDisable() {
        stats.save(statsFile);
    }

    // ---------- Eventy zbierające statystyki ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        stats.add(victim.getUniqueId(), Category.DEATHS, 1);
        stats.resetStreak(victim.getUniqueId());

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            stats.add(killer.getUniqueId(), Category.KILLS, 1);
            int s = stats.addStreak(killer.getUniqueId());
            if (s > stats.get(killer.getUniqueId(), Category.KILLSTREAK))
                stats.set(killer.getUniqueId(), Category.KILLSTREAK, s);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.STONE)
            stats.add(e.getPlayer().getUniqueId(), Category.STONE, 1);
    }

    @EventHandler
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl && e.getEntity().getShooter() instanceof Player p)
            stats.add(p.getUniqueId(), Category.PEARLS, 1);
    }

    // ---------- Tablice w świecie ----------

    void loadBoards() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(boardsFile);
        ConfigurationSection s = y.getConfigurationSection("boards");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection b = s.getConfigurationSection(id);
            if (b == null) continue;
            Category c = parse(b.getString("category", ""));
            if (c == null) continue;
            boards.put(id, new Board(id, c, b.getString("world", "world"),
                    b.getInt("x"), b.getInt("y"), b.getInt("z"), (float) b.getDouble("yaw")));
        }
    }

    void saveBoards() {
        YamlConfiguration y = new YamlConfiguration();
        for (Board b : boards.values()) {
            String p = "boards." + b.id + ".";
            y.set(p + "category", b.cat.name());
            y.set(p + "world", b.world);
            y.set(p + "x", b.x);
            y.set(p + "y", b.y);
            y.set(p + "z", b.z);
            y.set(p + "yaw", b.yaw);
        }
        try {
            y.save(boardsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void removeStale(Board b) {
        World w = getServer().getWorld(b.world);
        if (w == null) return;
        Location loc = new Location(w, b.x + 0.5, b.y + 1.5, b.z + 0.5);
        for (Entity e : w.getNearbyEntities(loc, 1.5, 2, 1.5)) {
            String tag = e.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (b.id.equals(tag)) e.remove();
        }
    }

    void spawnBoard(Board b) {
        World w = getServer().getWorld(b.world);
        if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) return;

        removeStale(b);
        Location loc = new Location(w, b.x + 0.5, b.y + 1.5, b.z + 0.5, b.yaw, 0f);

        TextDisplay td = w.spawn(loc, TextDisplay.class, t -> {
            t.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, b.id);
            t.setBillboard(Display.Billboard.FIXED);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(Color.fromARGB(190, 15, 15, 15));
            t.setShadowed(false);
            t.setSeeThrough(false);
            t.setLineWidth(240);
            t.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(),
                    new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
            t.text(renderBoard(b.cat));
        });

        Interaction in = w.spawn(loc, Interaction.class, i -> {
            i.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, b.id);
            i.setInteractionWidth(2.5f);
            i.setInteractionHeight(2.0f);
        });

        b.textId = td.getUniqueId();
        b.interId = in.getUniqueId();
    }

    void refreshBoards() {
        for (Board b : boards.values()) {
            if (b.textId == null) continue;
            Entity e = getServer().getEntity(b.textId);
            if (e instanceof TextDisplay td) td.text(renderBoard(b.cat));
        }
    }

    Component renderBoard(Category c) {
        List<UUID> top = stats.ranked(c);
        Component out = Component.text(c.title.toUpperCase(), c.color, TextDecoration.BOLD)
                .append(Component.newline());
        for (int i = 0; i < 10; i++) {
            Component line;
            if (i < top.size()) {
                UUID id = top.get(i);
                line = Component.text((i + 1) + ". ", NamedTextColor.YELLOW)
                        .append(Component.text(nameOf(id), NamedTextColor.WHITE))
                        .append(Component.text("  " + fmt(c, stats.get(id, c)), NamedTextColor.GREEN));
            } else {
                line = Component.text((i + 1) + ". Pusta miejsce", NamedTextColor.DARK_GRAY);
            }
            out = out.append(line).append(Component.newline());
        }
        return out.append(Component.text("[ • KLIKNIJ, ABY ZOBACZYĆ TOP 50 ]", NamedTextColor.GRAY));
    }

    Board boardOf(Entity e) {
        for (Board b : boards.values())
            if (e.getUniqueId().equals(b.interId)) return b;
        return null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        Board b = boardOf(e.getRightClicked());
        if (b == null) return;
        e.setCancelled(true);
        openTop(e.getPlayer(), b.cat, 0);
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        Board b = boardOf(e.getEntity());
        if (b == null) return;
        e.setCancelled(true);
        openTop(p, b.cat, 0);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (Board b : boards.values()) {
            if (b.world.equals(e.getWorld().getName())
                    && (b.x >> 4) == e.getChunk().getX()
                    && (b.z >> 4) == e.getChunk().getZ()) {
                getServer().getScheduler().runTask(this, () -> spawnBoard(b));
            }
        }
    }

    // ---------- GUI ----------

    void openMain(Player p) {
        MenuHolder h = new MenuHolder(MenuType.MAIN, null, 0);
        Inventory inv = getServer().createInventory(h, 36,
                Component.text("TOPKI SERWERA", NamedTextColor.GOLD, TextDecoration.BOLD));
        h.inv = inv;
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);

        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            Category c = cats[i];
            int pos = stats.ranked(c).indexOf(p.getUniqueId());
            inv.setItem(11 + i, item(c.icon, c.title, c.color,
                    c.desc, "",
                    "Twoja pozycja: " + (pos < 0 ? "brak" : "#" + (pos + 1)),
                    "Twój wynik: " + fmt(c, stats.get(p.getUniqueId(), c)),
                    "",
                    "Kliknij, aby zobaczyć TOP 50"));
        }

        String[] own = Stream.concat(
                Stream.of("Twoje statystyki:"),
                Arrays.stream(cats).map(c -> c.title + ": " + fmt(c, stats.get(p.getUniqueId(), c)))
        ).toArray(String[]::new);
        inv.setItem(31, item(Material.NETHER_STAR, "TWOJE STATYSTYKI", NamedTextColor.LIGHT_PURPLE, own));

        p.openInventory(inv);
    }

    void openTop(Player p, Category c, int page) {
        List<UUID> ranked = stats.ranked(c);
        int pages = Math.max(1, (int) Math.ceil(ranked.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));

        MenuHolder h = new MenuHolder(MenuType.TOP, c, page);
        Inventory inv = getServer().createInventory(h, 54,
                Component.text("TOP 50: " + c.title.toUpperCase(), c.color, TextDecoration.BOLD));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int rank = page * 45 + i;
            if (rank < ranked.size()) {
                UUID id = ranked.get(rank);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                sm.setOwningPlayer(getServer().getOfflinePlayer(id));
                sm.displayName(Component.text("#" + (rank + 1) + " " + nameOf(id), NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
                sm.lore(List.of(Component.text(c.title + ": " + fmt(c, stats.get(id, c)), NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)));
                head.setItemMeta(sm);
                inv.setItem(i, head);
            } else {
                inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, "Pusta miejsce", NamedTextColor.DARK_GRAY));
            }
        }

        if (page > 0) inv.setItem(45, item(Material.ARROW, "Poprzednia strona", NamedTextColor.YELLOW));
        inv.setItem(49, item(Material.OAK_DOOR, "Wróć do menu", NamedTextColor.RED));
        if (page < pages - 1) inv.setItem(53, item(Material.ARROW, "Następna strona", NamedTextColor.YELLOW));

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;

        if (h.type == MenuType.MAIN) {
            if (slot >= 11 && slot <= 16) openTop(p, Category.values()[slot - 11], 0);
        } else {
            if (slot == 45) openTop(p, h.cat, h.page - 1);
            else if (slot == 53) openTop(p, h.cat, h.page + 1);
            else if (slot == 49) openMain(p);
        }
    }

    void fill(Inventory inv, Material m) {
        ItemStack pane = item(m, " ", NamedTextColor.GRAY);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    ItemStack item(Material m, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore)
            l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    // ---------- Komendy ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) {
                s.sendMessage("Tylko gracz może otworzyć menu.");
                return true;
            }
            openMain(p);
            return true;
        }

        if (!s.hasPermission("topki.admin")) {
            s.sendMessage("Brak uprawnień.");
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "tablica" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Tylko gracz."); return true; }
                if (a.length < 2 || parse(a[1]) == null) {
                    s.sendMessage("Użycie: /topki tablica <kategoria>");
                    s.sendMessage("Kategorie: " + String.join(", ", Arrays.stream(Category.values())
                            .map(c -> c.name().toLowerCase()).toList()));
                    return true;
                }
                Block target = p.getTargetBlockExact(6);
                if (target == null) { p.sendMessage("Spójrz na blok, na którym ma stać tablica."); return true; }

                String id = UUID.randomUUID().toString().substring(0, 6);
                Board b = new Board(id, parse(a[1]), target.getWorld().getName(),
                        target.getX(), target.getY(), target.getZ(), p.getLocation().getYaw() + 180f);
                boards.put(id, b);
                saveBoards();
                spawnBoard(b);
                p.sendMessage("Dodano tablicę " + b.cat.name() + " (id: " + id + ")");
            }
            case "usun" -> {
                Board b = a.length < 2 ? null : boards.remove(a[1]);
                if (b == null) { s.sendMessage("Nie ma takiej tablicy. Użyj /topki lista."); return true; }
                removeStale(b);
                saveBoards();
                s.sendMessage("Usunięto tablicę " + b.id);
            }
            case "lista" -> {
                if (boards.isEmpty()) s.sendMessage("Brak tablic.");
                for (Board b : boards.values())
                    s.sendMessage(b.id + " - " + b.cat.name() + " (" + b.world + " " + b.x + " " + b.y + " " + b.z + ")");
            }
            case "reset" -> {
                for (Category c : Category.values()) {
                    List<UUID> top = stats.ranked(c);
                    if (top.isEmpty()) continue;
                    String winner = nameOf(top.get(0));
                    for (String cmdLine : getConfig().getStringList("rewards." + c.name()))
                        getServer().dispatchCommand(getServer().getConsoleSender(),
                                cmdLine.replace("%player%", winner));
                }
                stats.clear();
                stats.save(statsFile);
                s.sendMessage("Topki zresetowane, nagrody rozdane.");
            }
            default -> s.sendMessage("Użycie: /topki [tablica|usun|lista|reset]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1)
            return Stream.of("tablica", "usun", "lista", "reset")
                    .filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("tablica"))
            return Arrays.stream(Category.values()).map(c -> c.name().toLowerCase()).toList();
        return List.of();
    }

    // ---------- Pomocnicze ----------

    static Category parse(String s) {
        try {
            return Category.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String fmt(Category c, long v) {
        if (c != Category.PLAYTIME) return String.valueOf(v);
        return (v / 3600) + "h " + ((v % 3600) / 60) + "m";
    }

    String nameOf(UUID id) {
        String n = getServer().getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
