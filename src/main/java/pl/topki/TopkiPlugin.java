package pl.topki;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class TopkiPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ---------- Kolory ----------
    static final TextColor GOLD = TextColor.fromHexString("#FFD54F");
    static final TextColor SILVER = TextColor.fromHexString("#E0E6ED");
    static final TextColor BRONZE = TextColor.fromHexString("#E0A46B");
    static final TextColor WHITE = TextColor.fromHexString("#FFFFFF");
    static final TextColor LIGHT = TextColor.fromHexString("#D6E4F0");
    static final TextColor DIM = TextColor.fromHexString("#8A9BB0");
    static final TextColor DIM2 = TextColor.fromHexString("#5F6F85");
    static final TextColor GREEN = TextColor.fromHexString("#7CFF8A");
    static final TextColor RED = TextColor.fromHexString("#FF6B6B");
    static final TextColor AQUA = TextColor.fromHexString("#5CE1E6");
    static final TextColor PINK = TextColor.fromHexString("#FF7EB6");
    static final TextColor GRAY = TextColor.fromHexString("#A0AEC0");
    static final Component PREFIX = grad("[Topki] ", PINK, AQUA);

    enum Category {
        KILLS("Topka zabójstw", "Zabójstwa innych graczy", Material.DIAMOND_SWORD, "#FF4D6D", "#FFB347"),
        PLAYTIME("Topka czasu gry", "Czas spędzony na serwerze", Material.CLOCK, "#00E5FF", "#3D5AFE"),
        DEATHS("Topka śmierci", "Liczba Twoich śmierci", Material.SKELETON_SKULL, "#CFD8DC", "#546E7A"),
        KILLSTREAK("Topka killstreak", "Najdłuższa seria zabójstw bez śmierci", Material.TOTEM_OF_UNDYING, "#FFE066", "#FF8F00"),
        STONE("Topka wykopanego kamienia", "Ilość wykopanego kamienia", Material.COBBLESTONE, "#B2EBF2", "#78909C"),
        PEARLS("Topka rzuconych pereł", "Liczba rzuconych pereł", Material.ENDER_PEARL, "#64FFDA", "#00897B");

        final String title, desc;
        final Material icon;
        final TextColor from, to;

        Category(String title, String desc, Material icon, String from, String to) {
            this.title = title;
            this.desc = desc;
            this.icon = icon;
            this.from = TextColor.fromHexString(from);
            this.to = TextColor.fromHexString(to);
        }
    }

    enum MenuType { MAIN, TOP, LIST, ADD }

    static class Menu implements InventoryHolder {
        final MenuType type;
        final Category cat;
        final int page;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;

        Menu(MenuType type, Category cat, int page) {
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

    static class ListEntry {
        final String id;
        final ItemStack icon;
        final String command;
        final String holo;
        final String world;
        final int x, y, z;
        UUID holoId;

        ListEntry(String id, ItemStack icon, String command, String holo, String world, int x, int y, int z) {
            this.id = id;
            this.icon = icon;
            this.command = command;
            this.holo = holo;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static class AddSession {
        final ItemStack icon;
        int stage = 1; // 1 = czeka na komendę, 2 = czeka na hologram
        String command = "";

        AddSession(ItemStack icon) {
            this.icon = icon;
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
    private final Map<String, ListEntry> entries = new LinkedHashMap<>();
    private final Map<UUID, AddSession> sessions = new ConcurrentHashMap<>();
    private NamespacedKey boardKey, holoKey;
    private File statsFile, boardsFile, listsFile;

    // ---------- Start / stop ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boardKey = new NamespacedKey(this, "board");
        holoKey = new NamespacedKey(this, "holo");
        statsFile = new File(getDataFolder(), "stats.yml");
        boardsFile = new File(getDataFolder(), "boards.yml");
        listsFile = new File(getDataFolder(), "lists.yml");

        stats.load(statsFile);
        loadBoards();
        loadEntries();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("topki").setExecutor(this);
        getCommand("topki").setTabCompleter(this);
        getCommand("lista").setExecutor(this);
        getCommand("lista").setTabCompleter(this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers())
                stats.add(p.getUniqueId(), Category.PLAYTIME, 1);
        }, 20L, 20L);

        getServer().getScheduler().runTaskTimer(this, this::refreshBoards, 40L, 40L);
        getServer().getScheduler().runTaskTimer(this, () -> stats.save(statsFile), 6000L, 6000L);

        getServer().getScheduler().runTaskLater(this, () -> {
            boards.values().forEach(this::spawnBoard);
            entries.values().forEach(this::spawnHolo);
        }, 1L);
    }

    @Override
    public void onDisable() {
        stats.save(statsFile);
    }

    // ---------- Statystyki ----------

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

    // ---------- Tablice (TOP 10) ----------

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

    void spawnBoard(Board b) {
        World w = getServer().getWorld(b.world);
        if (w == null || !w.isChunkLoaded(b.x >> 4, b.z >> 4)) return;

        purge(boardKey, b.id, b.world, b.x, b.y, b.z);
        float s = (float) scale();
        Location loc = new Location(w, b.x + 0.5, b.y + 1.5, b.z + 0.5, b.yaw, 0f);

        TextDisplay td = w.spawn(loc, TextDisplay.class, t -> {
            t.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, b.id);
            t.setBillboard(Display.Billboard.FIXED);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(Color.fromARGB(210, 12, 14, 24));
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setLineWidth(260);
            t.setTransformation(scaleT(0.6f * s));
            t.text(renderBoard(b.cat));
        });

        Interaction in = w.spawn(loc, Interaction.class, i -> {
            i.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, b.id);
            i.setInteractionWidth(2.5f * s);
            i.setInteractionHeight(2.2f * s);
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
        Component out = grad(c.title.toUpperCase(), c.from, c.to).append(Component.newline());
        out = out.append(t("--------------------------------", DIM2)).append(Component.newline());

        for (int i = 0; i < 10; i++) {
            TextColor rankColor = rankColor(i);
            Component line = Component.text((i + 1) + ". ", rankColor).decoration(TextDecoration.ITALIC, false);
            if (i < top.size()) {
                UUID id = top.get(i);
                line = line.append(Component.text(nameOf(id), i < 3 ? WHITE : LIGHT)
                                .decoration(TextDecoration.BOLD, i < 3))
                        .append(Component.text("  " + fmt(c, stats.get(id, c)), c.to)
                                .decoration(TextDecoration.ITALIC, false));
            } else {
                line = line.append(Component.text("Pusta miejsce", DIM2).decoration(TextDecoration.ITALIC, true));
            }
            out = out.append(line).append(Component.newline());
        }
        out = out.append(t("--------------------------------", DIM2)).append(Component.newline());
        return out.append(t("» Kliknij, aby zobaczyć TOP 50 «", c.from));
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

    // ---------- Lista trybów (wpisy z itemem, komendą i hologramem) ----------

    void loadEntries() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(listsFile);
        ConfigurationSection s = y.getConfigurationSection("entries");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            String p = "entries." + id + ".";
            Object o = y.get(p + "icon");
            if (!(o instanceof ItemStack icon)) continue;
            entries.put(id, new ListEntry(id, icon,
                    y.getString(p + "command", ""),
                    y.getString(p + "holo"),
                    y.getString(p + "world"),
                    y.getInt(p + "x"), y.getInt(p + "y"), y.getInt(p + "z")));
        }
    }

    void saveEntries() {
        YamlConfiguration y = new YamlConfiguration();
        for (ListEntry e : entries.values()) {
            String p = "entries." + e.id + ".";
            y.set(p + "icon", e.icon);
            y.set(p + "command", e.command);
            y.set(p + "holo", e.holo);
            y.set(p + "world", e.world);
            y.set(p + "x", e.x);
            y.set(p + "y", e.y);
            y.set(p + "z", e.z);
        }
        try {
            y.save(listsFile);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    void spawnHolo(ListEntry e) {
        if (e.holo == null || e.world == null) return;
        World w = getServer().getWorld(e.world);
        if (w == null || !w.isChunkLoaded(e.x >> 4, e.z >> 4)) return;

        purge(holoKey, e.id, e.world, e.x, e.y, e.z);
        float s = (float) scale();
        Location loc = new Location(w, e.x + 0.5, e.y + 1.5, e.z + 0.5);
        Component text = e.holo.contains("&")
                ? LegacyComponentSerializer.legacyAmpersand().deserialize(e.holo)
                : grad(e.holo, PINK, AQUA);

        TextDisplay td = w.spawn(loc, TextDisplay.class, t -> {
            t.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, e.id);
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setLineWidth(300);
            t.setTransformation(scaleT(0.8f * s));
            t.text(text);
        });
        e.holoId = td.getUniqueId();
    }

    void openList(Player p) {
        Menu m = new Menu(MenuType.LIST, null, 0);
        Inventory inv = getServer().createInventory(m, 54, grad("LISTA TRYBÓW", GOLD, PINK));
        m.inv = inv;
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);

        boolean admin = p.hasPermission("topki.admin");
        int slot = 0;
        for (ListEntry en : entries.values()) {
            if (slot >= 45) break;
            ItemStack it = en.icon.clone();
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(t("» Lewy klik: uruchom", AQUA));
            if (!en.command.isEmpty()) lore.add(t("Komenda: /" + en.command, GRAY));
            if (admin) lore.add(t("ID: " + en.id, DIM2));
            meta.lore(lore);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
            m.slots.put(slot, en.id);
            slot++;
        }
        if (entries.isEmpty())
            inv.setItem(22, item(Material.BARRIER, t("Brak wpisów", RED), t("Admin dodaje: /lista dodaj", GRAY)));

        p.openInventory(inv);
    }

    void openAdd(Player p) {
        Menu m = new Menu(MenuType.ADD, null, 0);
        Inventory inv = getServer().createInventory(m, 27, grad("DODAJ WPIS DO LISTY", PINK, AQUA));
        m.inv = inv;
        fill(inv, Material.PURPLE_STAINED_GLASS_PANE);
        inv.setItem(13, null);
        inv.setItem(21, item(Material.LIME_WOOL, t("ZATWIERDŹ", GREEN),
                t("Włóż item w środkowy slot", GRAY),
                t("i kliknij tutaj, aby przejść dalej", GRAY)));
        inv.setItem(23, item(Material.RED_WOOL, t("ANULUJ", RED)));
        p.openInventory(inv);
    }

    void confirmAdd(Player p, Inventory inv) {
        ItemStack icon = inv.getItem(13);
        if (icon == null || icon.getType().isAir()) {
            send(p, "Najpierw włóż item do środkowego slotu.", RED);
            return;
        }
        inv.setItem(13, null);
        sessions.put(p.getUniqueId(), new AddSession(icon.clone()));
        p.closeInventory();
        send(p, "Wpisz w czacie komendę bez / (np. spawn). Wpisz 'nie', jeśli wpis ma nic nie robić.", AQUA);
        send(p, "Wpisz 'anuluj' w każdej chwili, aby przerwać.", GRAY);
    }

    void handleChat(Player p, AddSession s, String msg) {
        if (msg.equalsIgnoreCase("anuluj")) {
            sessions.remove(p.getUniqueId());
            giveBack(p, s.icon);
            send(p, "Anulowano dodawanie wpisu.", RED);
            return;
        }
        if (s.stage == 1) {
            s.command = msg.equalsIgnoreCase("nie") ? "" : msg.startsWith("/") ? msg.substring(1) : msg;
            s.stage = 2;
            send(p, "Wpisz tekst hologramu nad wpisem (kolory: &c, &l itd.) albo 'nie'.", AQUA);
            send(p, "Przy hologramie spójrz na blok, na którym ma stać, zanim wyślesz tekst.", GRAY);
            return;
        }
        if (msg.equalsIgnoreCase("nie")) {
            finishAdd(p, s, null, null);
            return;
        }
        Block target = p.getTargetBlockExact(6);
        if (target == null) {
            send(p, "Spójrz na blok, na którym ma stać hologram, i wyślij tekst ponownie.", RED);
            return;
        }
        finishAdd(p, s, msg, target);
    }

    void finishAdd(Player p, AddSession s, String holo, Block target) {
        ListEntry en = target == null
                ? new ListEntry(randomId(), s.icon, s.command, null, null, 0, 0, 0)
                : new ListEntry(randomId(), s.icon, s.command, holo,
                target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        entries.put(en.id, en);
        sessions.remove(p.getUniqueId());
        saveEntries();
        spawnHolo(en);
        send(p, "Dodano wpis " + en.id + " do Listy Trybów.", GREEN);
    }

    void runEntry(Player p, String id) {
        ListEntry en = entries.get(id);
        if (en == null) return;
        p.closeInventory();
        if (en.command.isEmpty()) {
            send(p, "Ten wpis nie ma przypisanej komendy.", GRAY);
            return;
        }
        p.performCommand(en.command);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        AddSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        getServer().getScheduler().runTask(this, () -> handleChat(p, s, msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        AddSession s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(), s.icon);
    }

    // ---------- Chunki ----------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        String world = e.getWorld().getName();
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        for (Board b : boards.values()) {
            if (b.world.equals(world) && (b.x >> 4) == cx && (b.z >> 4) == cz)
                getServer().getScheduler().runTask(this, () -> spawnBoard(b));
        }
        for (ListEntry en : entries.values()) {
            if (en.holo != null && en.world != null && en.world.equals(world)
                    && (en.x >> 4) == cx && (en.z >> 4) == cz)
                getServer().getScheduler().runTask(this, () -> spawnHolo(en));
        }
    }

    // ---------- GUI: kliknięcia ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        if (!(e.getWhoClicked() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        int slot = e.getRawSlot();
        boolean topSide = slot >= 0 && slot < e.getInventory().getSize();

        if (m.type == MenuType.ADD) {
            if (!topSide) return;
            if (slot == 13) return;
            e.setCancelled(true);
            if (slot == 21) confirmAdd(p, e.getInventory());
            else if (slot == 23) p.closeInventory();
            return;
        }

        e.setCancelled(true);
        if (!topSide) return;

        if (m.type == MenuType.MAIN) {
            if (slot >= 11 && slot <= 16) openTop(p, Category.values()[slot - 11], 0);
        } else if (m.type == MenuType.TOP) {
            if (slot == 45) openTop(p, m.cat, m.page - 1);
            else if (slot == 53) openTop(p, m.cat, m.page + 1);
            else if (slot == 49) openMain(p);
        } else if (m.type == MenuType.LIST) {
            String id = m.slots.get(slot);
            if (id != null && e.isLeftClick()) runEntry(p, id);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        if (m.type == MenuType.ADD && e.getRawSlots().stream()
                .allMatch(s -> s == 13 || s >= e.getInventory().getSize())) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || m.type != MenuType.ADD) return;
        ItemStack left = e.getInventory().getItem(13);
        if (left != null && !left.getType().isAir() && e.getPlayer() instanceof Player p) {
            e.getInventory().setItem(13, null);
            giveBack(p, left);
        }
    }

    void giveBack(Player p, ItemStack it) {
        p.getInventory().addItem(it).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // ---------- GUI: menu główne i TOP 50 ----------

    void openMain(Player p) {
        Menu m = new Menu(MenuType.MAIN, null, 0);
        Inventory inv = getServer().createInventory(m, 36, grad("TOPKI SERWERA", GOLD, PINK));
        m.inv = inv;
        fill(inv, Material.PURPLE_STAINED_GLASS_PANE);

        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            Category c = cats[i];
            int pos = stats.ranked(c).indexOf(p.getUniqueId());
            inv.setItem(11 + i, item(c.icon, t(c.title, c.from),
                    t(c.desc, GRAY),
                    Component.empty(),
                    t("Twoja pozycja: " + (pos < 0 ? "brak" : "#" + (pos + 1)), LIGHT),
                    t("Twój wynik: " + fmt(c, stats.get(p.getUniqueId(), c)), c.to),
                    Component.empty(),
                    t("» Kliknij, aby zobaczyć TOP 50", AQUA)));
        }

        Component[] own = Arrays.stream(cats)
                .map(c -> t(c.title.replace("Topka ", "") + ": " + fmt(c, stats.get(p.getUniqueId(), c)), c.to))
                .toArray(Component[]::new);
        inv.setItem(31, item(Material.NETHER_STAR, grad("TWOJE STATYSTYKI", PINK, AQUA), own));

        p.openInventory(inv);
    }

    void openTop(Player p, Category c, int page) {
        List<UUID> ranked = stats.ranked(c);
        int pages = Math.max(1, (int) Math.ceil(ranked.size() / 45.0));
        int pg = Math.max(0, Math.min(page, pages - 1));

        Menu m = new Menu(MenuType.TOP, c, pg);
        Inventory inv = getServer().createInventory(m, 54, grad("TOP 50 | " + c.title.toUpperCase(), c.from, c.to));
        m.inv = inv;

        for (int i = 0; i < 45; i++) {
            int rank = pg * 45 + i;
            if (rank < ranked.size()) {
                UUID id = ranked.get(rank);
                long value = stats.get(id, c);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                sm.setOwningPlayer(getServer().getOfflinePlayer(id));
                sm.displayName(t("#" + (rank + 1) + " " + nameOf(id), rankColor(rank)));
                sm.lore(List.of(
                        t("Wynik: " + fmt(c, value), c.to),
                        t("Miejsce: #" + (rank + 1), GRAY)));
                head.setItemMeta(sm);
                inv.setItem(i, head);
            } else {
                inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, t("Pusta miejsce", DIM2)));
            }
        }

        if (pg > 0) inv.setItem(45, item(Material.ARROW, t("« Poprzednia strona", AQUA)));
        inv.setItem(49, item(Material.OAK_DOOR, t("Wróć do menu", RED)));
        if (pg < pages - 1) inv.setItem(53, item(Material.ARROW, t("Następna strona »", AQUA)));

        p.openInventory(inv);
    }

    // ---------- Komendy ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("lista")) return handleLista(s, a);
        return handleTopki(s, a);
    }

    boolean handleTopki(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) {
                send(s, "Tylko gracz może otworzyć menu.", RED);
                return true;
            }
            openMain(p);
            return true;
        }

        if (!s.hasPermission("topki.admin")) {
            send(s, "Brak uprawnień.", RED);
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "tablica" -> {
                if (!(s instanceof Player p)) { send(s, "Tylko gracz.", RED); return true; }
                Category c = a.length < 2 ? null : parse(a[1]);
                if (c == null) {
                    send(s, "Użycie: /topki tablica <kategoria>", GRAY);
                    send(s, "Kategorie: " + String.join(", ", Arrays.stream(Category.values())
                            .map(x -> x.name().toLowerCase()).toList()), GRAY);
                    return true;
                }
                Block target = p.getTargetBlockExact(6);
                if (target == null) { send(p, "Spójrz na blok, na którym ma stać tablica.", RED); return true; }
                Board b = new Board(randomId(), c, target.getWorld().getName(),
                        target.getX(), target.getY(), target.getZ(), p.getLocation().getYaw() + 180f);
                boards.put(b.id, b);
                saveBoards();
                spawnBoard(b);
                send(p, "Dodano tablicę " + c.name() + " (id: " + b.id + ")", GREEN);
            }
            case "usun" -> {
                Board b = a.length < 2 ? null : boards.remove(a[1]);
                if (b == null) { send(s, "Nie ma takiej tablicy. Użyj /topki lista.", RED); return true; }
                purge(boardKey, b.id, b.world, b.x, b.y, b.z);
                saveBoards();
                send(s, "Usunięto tablicę " + b.id, GREEN);
            }
            case "lista" -> {
                if (boards.isEmpty()) send(s, "Brak tablic.", GRAY);
                for (Board b : boards.values())
                    send(s, b.id + " - " + b.cat.name() + " (" + b.world + " " + b.x + " " + b.y + " " + b.z + ")", LIGHT);
            }
            case "skala" -> {
                if (a.length < 2) { send(s, "Użycie: /topki skala <0.3 - 5.0>", GRAY); return true; }
                double v;
                try {
                    v = Double.parseDouble(a[1].replace(',', '.'));
                } catch (NumberFormatException ex) {
                    send(s, "To nie jest liczba.", RED);
                    return true;
                }
                v = Math.max(0.3, Math.min(5.0, v));
                getConfig().set("scale", v);
                saveConfig();
                boards.values().forEach(this::spawnBoard);
                entries.values().forEach(this::spawnHolo);
                send(s, "Skala ustawiona na " + v + ". Tablice i hologramy odświeżone.", GREEN);
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
                send(s, "Topki zresetowane, nagrody rozdane.", GREEN);
            }
            default -> send(s, "Użycie: /topki [tablica|usun|lista|skala|reset]", GRAY);
        }
        return true;
    }

    boolean handleLista(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player p)) { send(s, "Tylko gracz.", RED); return true; }
            openList(p);
            return true;
        }

        if (!s.hasPermission("topki.admin")) {
            send(s, "Brak uprawnień.", RED);
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "dodaj" -> {
                if (!(s instanceof Player p)) { send(s, "Tylko gracz.", RED); return true; }
                if (sessions.containsKey(p.getUniqueId())) {
                    send(p, "Masz już otwarte dodawanie. Wpisz 'anuluj' w czacie, aby przerwać.", RED);
                    return true;
                }
                openAdd(p);
            }
            case "usun" -> {
                ListEntry en = a.length < 2 ? null : entries.remove(a[1]);
                if (en == null) { send(s, "Nie ma takiego wpisu. ID zobaczysz w /lista jako admin.", RED); return true; }
                purge(holoKey, en.id, en.world, en.x, en.y, en.z);
                saveEntries();
                send(s, "Usunięto wpis " + en.id, GREEN);
            }
            default -> send(s, "Użycie: /lista [dodaj|usun <id>]", GRAY);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("lista")) {
            if (a.length == 1 && s.hasPermission("topki.admin"))
                return Stream.of("dodaj", "usun").filter(x -> x.startsWith(a[0].toLowerCase())).toList();
            return List.of();
        }
        if (a.length == 1)
            return Stream.of("tablica", "usun", "lista", "skala", "reset")
                    .filter(x -> x.startsWith(a[0].toLowerCase())).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("tablica"))
            return Arrays.stream(Category.values()).map(c -> c.name().toLowerCase()).toList();
        if (a.length == 2 && a[0].equalsIgnoreCase("skala"))
            return List.of("0.5", "1.0", "1.5", "2.0");
        return List.of();
    }

    // ---------- Pomocnicze ----------

    double scale() {
        return getConfig().getDouble("scale", 1.0);
    }

    static Transformation scaleT(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(s, s, s), new Quaternionf());
    }

    void purge(NamespacedKey key, String id, String worldName, int x, int y, int z) {
        if (worldName == null) return;
        World w = getServer().getWorld(worldName);
        if (w == null) return;
        Location loc = new Location(w, x + 0.5, y + 1.5, z + 0.5);
        for (Entity e : w.getNearbyEntities(loc, 2, 3, 2)) {
            if (id.equals(e.getPersistentDataContainer().get(key, PersistentDataType.STRING))) e.remove();
        }
    }

    static ItemStack item(Material m, Component name, Component... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) meta.lore(new ArrayList<>(Arrays.asList(lore)));
        it.setItemMeta(meta);
        return it;
    }

    void fill(Inventory inv, Material m) {
        ItemStack pane = item(m, Component.text(" "));
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    static Component t(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    static Component grad(String s, TextColor a, TextColor b) {
        Component out = Component.empty();
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            double k = (double) i / n;
            TextColor c = TextColor.color(
                    (int) Math.round(a.red() + (b.red() - a.red()) * k),
                    (int) Math.round(a.green() + (b.green() - a.green()) * k),
                    (int) Math.round(a.blue() + (b.blue() - a.blue()) * k));
            out = out.append(Component.text(String.valueOf(s.charAt(i)), c)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    static TextColor rankColor(int rank) {
        return switch (rank) {
            case 0 -> GOLD;
            case 1 -> SILVER;
            case 2 -> BRONZE;
            default -> DIM;
        };
    }

    void send(CommandSender s, String text, TextColor c) {
        s.sendMessage(PREFIX.append(t(text, c)));
    }

    static String randomId() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

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
