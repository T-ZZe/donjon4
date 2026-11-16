package fr.donjon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.CaveVines;
import org.bukkit.block.data.type.CaveVinesPlant;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Donjon : /donjon téléporte dans un monde superplat, au cœur d'une salle gothique démesurée
 * qui abrite trois portails animés : gris, rouge et doré.
 */
public final class DonjonPlugin extends JavaPlugin implements Listener, TabExecutor {
    static final int ROOM_VERSION = 2;
    static final int PORTAL_Z = -30;        // plan des membranes
    static final int PORTAL_BASE_Y = 5;     // premier bloc de la membrane (pieds sur l'estrade)
    static final int[] POINTED = {3, 3, 3, 3, 3, 3, 3, 3, 2, 1, 0};   // demi-largeur par rangée, arche gothique
    static final int[] ROUND = {3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 1};     // arche en plein cintre

    private World world;
    private boolean building;
    private RoomBuilder builder;
    private final Map<UUID, Location> returns = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final List<Portal> portals = new ArrayList<>();
    private final Map<String, Particle> pcache = new HashMap<>();
    private final Random rnd = new Random();
    private long tick;

    // ------------------------------------------------------------------ Cycle de vie

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("donjon");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        initPortals();
        ensureWorld();
        getServer().getScheduler().runTaskTimer(this, this::animate, 20L, 2L);
        getServer().getScheduler().runTaskTimer(this, this::lockSky, 100L, 200L);
        getLogger().info("Donjon activé.");
    }

    private static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s.replace('§', '&'));
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(c(getConfig().getString("prefix", "&4&l[&c&lDonjon&4&l] &7") + text));
    }

    private File stateFile() { return new File(getDataFolder(), "state.yml"); }

    private int builtVersion() {
        return stateFile().exists() ? YamlConfiguration.loadConfiguration(stateFile()).getInt("room-version", 0) : 0;
    }

    private boolean isBuilt() { return builtVersion() >= ROOM_VERSION; }

    private void saveBuilt() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("room-version", ROOM_VERSION);
        try {
            getDataFolder().mkdirs();
            y.save(stateFile());
        } catch (IOException e) {
            getLogger().warning("state.yml : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Monde

    /** Générateur superplat : bedrock puis deepslate jusqu'à y=-1 (surface à y=-1, pieds à y=0). */
    static final class FlatGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
            int min = info.getMinHeight();
            data.setRegion(0, min, 0, 16, min + 1, 16, Material.BEDROCK);
            data.setRegion(0, min + 1, 0, 16, 0, 16, Material.DEEPSLATE);
        }

        @Override
        public boolean shouldGenerateNoise() { return true; }

        @Override
        public Location getFixedSpawnLocation(World w, Random random) { return new Location(w, 0.5, 0, 14.5); }
    }

    private World ensureWorld() {
        if (world != null) return world;
        String name = getConfig().getString("world", "donjon");
        World w = Bukkit.getWorld(name);
        if (w == null) {
            WorldCreator wc = new WorldCreator(name);
            wc.generator(new FlatGenerator());
            wc.environment(World.Environment.NORMAL);
            wc.generateStructures(false);
            w = Bukkit.createWorld(wc);
        }
        if (w != null) {
            world = w;
            double size = getConfig().getDouble("border-size", 220);
            w.getWorldBorder().setCenter(0, 0);
            w.getWorldBorder().setSize(size);
            w.setSpawnLocation(0, 0, 14);
            lockSky();
        } else {
            getLogger().severe("Impossible de créer le monde du donjon.");
        }
        return world;
    }

    private void lockSky() {
        if (world == null || !getConfig().getBoolean("lock-night", true)) return;
        world.setTime(18000);
        world.setStorm(false);
        world.setThundering(false);
    }

    private Location spawn() { return new Location(world, 0.5, 0, 14.5, 180f, 0f); }

    // ------------------------------------------------------------------ Commande

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "go" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "go", "entrer" -> {
                if (!(s instanceof Player p)) {
                    msg(s, "&cCommande réservée aux joueurs.");
                    return true;
                }
                if (!p.hasPermission("donjon.use")) {
                    msg(s, "&cVous n'avez pas la permission.");
                    return true;
                }
                enter(p);
            }
            case "quitter", "leave", "sortir" -> {
                if (!(s instanceof Player p)) return true;
                leave(p);
            }
            case "rebuild" -> {
                if (!s.hasPermission("donjon.admin")) {
                    msg(s, "&cVous n'avez pas la permission.");
                    return true;
                }
                startBuild(s, true, null);
            }
            case "info" -> {
                msg(s, "&7Monde : &f" + getConfig().getString("world", "donjon") + " &7| Salle construite : "
                        + (isBuilt() ? "&aoui" : "&cnon") + (building ? " &e(construction en cours)" : ""));
                msg(s, "&7Portails : &7Château &8| &cEnfer &8| &6Cieux");
            }
            default -> msg(s, "&cUsage : /donjon [quitter|rebuild|info]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("quitter", "info", "rebuild")) {
                if (x.equals("rebuild") && !s.hasPermission("donjon.admin")) continue;
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
            }
        }
        return out;
    }

    private void enter(Player p) {
        if (ensureWorld() == null) {
            msg(p, "&cMonde du donjon indisponible (voir la console).");
            return;
        }
        if (building) {
            msg(p, "&eLe donjon est en construction, patientez quelques secondes...");
            return;
        }
        if (!isBuilt()) {
            // une ancienne version de la salle existe : on la nettoie avant de construire la nouvelle
            startBuild(p, builtVersion() > 0, () -> teleportIn(p));
            return;
        }
        teleportIn(p);
    }

    private void teleportIn(Player p) {
        if (!p.isOnline()) return;
        if (!p.getWorld().equals(world)) returns.put(p.getUniqueId(), p.getLocation());
        p.teleport(spawn());
        p.showTitle(Title.title(c("&4&l⛧ LE DONJON ⛧"), c("&7Château, Enfer ou Cieux : choisissez votre portail."),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(800))));
        Location l = p.getLocation().add(0, 1, 0);
        sound(l, "entity.wither.spawn", 0.5f, 0.6f);
        sound(l, "block.portal.trigger", 0.7f, 0.5f);
        particle(l, "soul_fire_flame", 40, 0.6, 0.9, 0.6, 0.05);
        particle(l, "reverse_portal", 60, 0.5, 0.9, 0.5, 0.3);
    }

    private void leave(Player p) {
        Location r = returns.remove(p.getUniqueId());
        if (r == null || r.getWorld() == null) r = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(r);
        msg(p, "&7Vous quittez le donjon.");
    }

    // ------------------------------------------------------------------ Construction

    private void startBuild(CommandSender who, boolean clear, Runnable done) {
        if (building) {
            msg(who, "&cUne construction est déjà en cours.");
            return;
        }
        if (ensureWorld() == null) {
            msg(who, "&cMonde du donjon indisponible.");
            return;
        }
        building = true;
        builder = new RoomBuilder(world, clear);
        msg(who, "&7Construction de la salle des portails...");
        new BukkitRunnable() {
            int lastTen = -1;

            @Override
            public void run() {
                try {
                    boolean fin = builder.run(25_000_000L);
                    int pr = builder.progress();
                    if (who instanceof Player p && pr / 10 != lastTen) {
                        lastTen = pr / 10;
                        p.sendActionBar(c("&4⛧ &cConstruction du donjon... &f" + pr + "%"));
                    }
                    if (fin) {
                        cancel();
                        building = false;
                        saveBuilt();
                        msg(who, "&aSalle construite.");
                        if (done != null) done.run();
                    }
                } catch (RuntimeException ex) {
                    cancel();
                    building = false;
                    getLogger().log(java.util.logging.Level.SEVERE, "Construction du donjon interrompue", ex);
                    msg(who, "&cErreur pendant la construction (voir la console).");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // ------------------------------------------------------------------ Effets utilitaires

    private Particle particleOf(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (pcache.containsKey(k)) return pcache.get(k);
        Particle p = null;
        try {
            p = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(k));
        } catch (Exception ignored) {
            // clé invalide
        }
        pcache.put(k, p);
        return p;
    }

    private void particle(Location l, String key, int count, double dx, double dy, double dz, double speed) {
        if (l.getWorld() == null) return;
        Particle p = particleOf(key);
        if (p == null || p.getDataType() != Void.class) return;
        l.getWorld().spawnParticle(p, l, count, dx, dy, dz, speed);
    }

    private void dust(Location l, Color color, float size, int count, double spread) {
        if (l.getWorld() == null) return;
        l.getWorld().spawnParticle(Particle.DUST, l, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    private void sound(Location l, String key, float vol, float pitch) {
        if (l.getWorld() != null) l.getWorld().playSound(l, key, vol, pitch);
    }

    private double rr(double a, double b) { return a + rnd.nextDouble() * (b - a); }

    // ------------------------------------------------------------------ Portails animés

    /** Un portail : membrane de verre teinté repeinte côté client en vagues de couleurs, particules et son. */
    static final class Portal {
        final String key;
        final int cx;
        final int[] half;
        final BlockData[] palette;
        final Color c1;
        final Color c2;
        final String p1;
        final String p2;
        final String p3;
        final String ambient;
        final List<int[]> cells = new ArrayList<>();
        final int[] last;
        final Set<UUID> viewers = new HashSet<>();
        String name = "";
        String message = "";

        Portal(String key, int cx, int[] half, Material[] pal, Color c1, Color c2, String p1, String p2, String p3, String ambient) {
            this.key = key;
            this.cx = cx;
            this.half = half;
            this.palette = new BlockData[pal.length];
            for (int i = 0; i < pal.length; i++) palette[i] = pal[i].createBlockData();
            this.c1 = c1;
            this.c2 = c2;
            this.p1 = p1;
            this.p2 = p2;
            this.p3 = p3;
            this.ambient = ambient;
            for (int r = 0; r < half.length; r++) {
                for (int dx = -half[r]; dx <= half[r]; dx++) cells.add(new int[]{cx + dx, PORTAL_BASE_Y + r});
            }
            this.last = new int[cells.size()];
            java.util.Arrays.fill(last, -1);
        }
    }

    private void initPortals() {
        portals.clear();
        Portal hell = new Portal("hell", -24, POINTED,
                new Material[]{Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.BLACK_STAINED_GLASS, Material.BROWN_STAINED_GLASS},
                Color.fromRGB(235, 25, 20), Color.fromRGB(255, 130, 20), "flame", "lava", "smoke", "block.fire.ambient");
        Portal castle = new Portal("castle", 0, POINTED,
                new Material[]{Material.YELLOW_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS, Material.WHITE_STAINED_GLASS},
                Color.fromRGB(255, 185, 45), Color.fromRGB(235, 225, 200), "end_rod", "electric_spark", "ash", "block.beacon.ambient");
        Portal heaven = new Portal("heaven", 24, ROUND,
                new Material[]{Material.WHITE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS},
                Color.fromRGB(255, 245, 200), Color.fromRGB(165, 210, 255), "totem_of_undying", "end_rod", "cloud", "block.amethyst_block.chime");
        String[] defNames = {"&c&lPortail de l'Enfer", "&7&lPortail du Château", "&6&lPortail des Cieux"};
        String[] defMsgs = {"&cLes flammes de l'Enfer rugissent derrière la membrane... le portail est encore scellé.",
                "&7Les herses du Château grincent derrière la porte dorée... elle s'ouvrira bientôt.",
                "&6La lumière des Cieux est aveuglante... le portail s'ouvrira bientôt."};
        int i = 0;
        for (Portal p : List.of(hell, castle, heaven)) {
            p.name = getConfig().getString("portals." + p.key + ".name", defNames[i]);
            p.message = getConfig().getString("portals." + p.key + ".message", defMsgs[i]);
            i++;
            portals.add(p);
        }
    }

    private void animate() {
        if (world == null || building) return;
        tick += 2;
        List<Player> ps = world.getPlayers();
        if (ps.isEmpty()) return;
        for (Portal po : portals) animatePortal(po, ps);
        if (tick % 10 == 0) ambience(ps);
        for (Player p : ps) contact(p);
    }

    private void animatePortal(Portal po, List<Player> ps) {
        final double zc = PORTAL_Z + 0.5;
        final double xc = po.cx + 0.5;
        Location mid = new Location(world, xc, 10.0, zc);
        List<Player> near = new ArrayList<>();
        for (Player p : ps) if (p.getLocation().distanceSquared(mid) <= 45 * 45) near.add(p);
        po.viewers.removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            return p == null || !near.contains(p);
        });
        if (near.isEmpty()) return;

        // 1) membrane : vagues de verre teinté (client uniquement)
        if (tick % 4 == 0) {
            int n = po.palette.length;
            double t = tick;
            boolean[] changed = new boolean[po.cells.size()];
            for (int i = 0; i < po.cells.size(); i++) {
                int[] cell = po.cells.get(i);
                double dx = cell[0] - po.cx, dy = cell[1] - 10.0;
                double v = Math.sin(Math.hypot(dx, dy * 0.7) * 1.3 - t * 0.25) + 0.6 * Math.sin(dx * 0.8 + t * 0.12)
                        + 0.4 * Math.sin(cell[1] * 0.6 - t * 0.18);
                int idx = (int) Math.floor((v + 2.0) / 4.0 * n);
                idx = Math.max(0, Math.min(n - 1, idx));
                if (idx != po.last[i]) {
                    po.last[i] = idx;
                    changed[i] = true;
                }
            }
            for (Player p : near) {
                boolean full = po.viewers.add(p.getUniqueId());
                for (int i = 0; i < po.cells.size(); i++) {
                    if (!full && !changed[i]) continue;
                    int[] cell = po.cells.get(i);
                    p.sendBlockChange(new Location(world, cell[0], cell[1], PORTAL_Z), po.palette[po.last[i]]);
                }
            }
        }

        // 2) spirale d'énergie aspirée vers le cœur du portail
        double t = tick * 0.04;
        for (int i = 0; i < 4; i++) {
            double f = (t * 0.6 + i / 4.0) % 1.0;
            double a = t * 3 + i * Math.PI / 2 + f * 5;
            double k = 1 - f;
            Location pt = new Location(world, xc + Math.cos(a) * 2.6 * k, 10.0 + Math.sin(a) * 4.7 * k, zc);
            dust(pt, i % 2 == 0 ? po.c1 : po.c2, 1.4f, 1, 0.05);
            particle(pt, po.p1, 1, 0.05, 0.05, 0.05, 0.01);
        }
        // 3) anneau pulsant autour de l'ouverture
        if (tick % 6 == 0) {
            double pulse = 0.5 + 0.5 * Math.sin(tick * 0.15);
            for (int i = 0; i < 28; i++) {
                double a = i * 2 * Math.PI / 28;
                Location pt = new Location(world, xc + Math.cos(a) * (3.2 + pulse * 0.2), 10.0 + Math.sin(a) * (5.6 + pulse * 0.2), zc);
                particle(pt, po.p2, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }
        // 4) étincelles montantes et brume au pied
        particle(new Location(world, xc + rr(-3, 3), 5.2, zc + rr(-1, 1.5)), po.p3, 2, 0.2, 0.4, 0.2, 0.02);
        if (rnd.nextInt(10) == 0) particle(new Location(world, xc + rr(-2.5, 2.5), rr(6, 14), zc), "electric_spark", 6, 0.4, 0.4, 0.1, 0.2);
        // 5) bourdonnement
        if (tick % 80 == 0) sound(mid, po.ambient, 1.2f, po.key.equals("gold") ? 1.4f : 0.6f);
    }

    /** Ambiance de la salle : cendres, âmes, cercle rituel tournant et orages lointains. */
    private void ambience(List<Player> ps) {
        for (Player p : ps) {
            Location l = p.getLocation();
            if (Math.abs(l.getX()) > 52 || l.getZ() < -50 || l.getZ() > 40) continue;
            particle(l.clone().add(rr(-10, 10), rr(4, 14), rr(-10, 10)), "ash", 4, 0.5, 0.5, 0.5, 0.01);
            particle(l.clone().add(rr(-6, 6), rr(0.2, 3), rr(-6, 6)), "soul", 1, 0.2, 0.3, 0.2, 0.01);
            if (rnd.nextInt(3) == 0) particle(l.clone().add(rr(-8, 8), rr(1, 6), rr(-8, 8)), "flame", 1, 0.1, 0.1, 0.1, 0.0);
        }
        double base = tick * 0.03;
        for (int i = 0; i < 24; i++) {
            double a = base + i * 2 * Math.PI / 24;
            Location pt = new Location(world, 0.5 + Math.cos(a) * 8.5, 0.15, 4.5 + Math.sin(a) * 8.5);
            dust(pt, i % 2 == 0 ? Color.fromRGB(255, 190, 40) : Color.fromRGB(200, 15, 15), 1.3f, 1, 0.03);
            if (i % 6 == 0) particle(pt.clone().add(0, 0.3, 0), "soul_fire_flame", 1, 0.05, 0.1, 0.05, 0.01);
        }
        Location centre = new Location(world, 0.5, 0.2, 4.5);
        particle(centre.clone().add(0, 0.2 + (tick % 40) * 0.05, 0), "enchant", 6, 0.8, 0.1, 0.8, 0.2);
        if (tick % 600 == 0) {
            Location far = new Location(world, rr(-36, 36), 30, rr(-34, 20));
            world.strikeLightningEffect(far);
            sound(far, "entity.lightning_bolt.thunder", 0.8f, 0.7f);
        }
    }

    /** Un joueur s'approche de la membrane : message, effets et recul. */
    private void contact(Player p) {
        Location l = p.getLocation();
        if (l.getZ() < PORTAL_Z + 0.9 || l.getZ() > PORTAL_Z + 2.3 || l.getY() < PORTAL_BASE_Y - 0.5 || l.getY() > PORTAL_BASE_Y + 3) return;
        for (Portal po : portals) {
            if (Math.abs(l.getX() - (po.cx + 0.5)) > 2.6) continue;
            long now = System.currentTimeMillis();
            Long last = cooldown.get(p.getUniqueId());
            if (last != null && now - last < 3000) return;
            cooldown.put(p.getUniqueId(), now);
            p.showTitle(Title.title(c(po.name), c("&8Portail scellé"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(500))));
            p.sendMessage(c(po.message));
            Location at = new Location(world, po.cx + 0.5, 10.0, PORTAL_Z + 0.5);
            particle(at, po.p1, 70, 2, 4, 0.3, 0.2);
            dust(at, po.c1, 2f, 50, 2);
            sound(at, "block.end_portal_frame.fill", 1f, 0.6f);
            sound(at, po.ambient, 1.5f, 0.5f);
            p.setVelocity(new Vector(0, 0.35, 1.1));
            return;
        }
    }

    // ------------------------------------------------------------------ Protection du monde

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (world != null && e.getBlock().getWorld().equals(world) && !e.getPlayer().hasPermission("donjon.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (world != null && e.getBlock().getWorld().equals(world) && !e.getPlayer().hasPermission("donjon.admin")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        if (world == null || !e.getLocation().getWorld().equals(world) || getConfig().getBoolean("allow-natural-mobs", false)) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DISPENSE_EGG -> { }
            default -> e.setCancelled(true);
        }
    }

    // ================================================================== Construction de la grotte

    /**
     * Construit la grotte par petites tranches (budget de temps par tick) : une immense caverne aux parois irrégulières
     * (pierre, andésite, tuf, mousse, calcite, stalactites, lianes lumineuses, cristaux bleus), un sol dallé à marqueterie dorée,
     * et trois façades monumentales sur estrades : l'Enfer (gauche), le Château (centre) et les Cieux (droite).
     */
    static final class RoomBuilder {
        static final double CX = 0, CY = 14, CZ = -4, AX = 48, AY = 34, AZ = 44;
        static final int OFFX = 56, OFFZ = 56;
        static final int ZF = -28;   // face avant des façades
        static final int ZB = -34;   // fond des façades

        interface Pat { Material at(int x, int y, int z); }

        private final World w;
        private final Deque<Runnable> steps = new ArrayDeque<>();
        private final int total;
        private final int[][] top = new int[113][113];   // y du premier bloc de plafond au-dessus de l'air (0 = inconnu)

        RoomBuilder(World w, boolean clear) {
            this.w = w;
            for (int cz = -4; cz <= 3; cz++) {
                final int c = cz;
                steps.add(() -> { for (int cx = -4; cx <= 3; cx++) w.getChunkAt(cx, c); });
            }
            if (clear) {
                for (int z = -54; z <= 48; z++) {
                    final int zz = z;
                    steps.add(() -> clearSlice(zz));
                }
            }
            for (int z = -52; z <= 46; z++) {
                final int zz = z;
                steps.add(() -> caveSlice(zz));
            }
            for (int p = 0; p < 4; p++) {
                final int pp = p;
                steps.add(() -> ceilingDecor(pp));
            }
            steps.add(this::floorDecor);
            steps.add(this::crystals);
            steps.add(() -> platform(-24,
                    (x, y, z) -> switch (hash(x, y, z) / 25) { case 0 -> Material.BLACKSTONE; case 1 -> Material.POLISHED_BLACKSTONE_BRICKS; case 2 -> Material.RED_NETHER_BRICKS; default -> Material.NETHER_BRICKS; },
                    (x, y, z) -> hash(x, y, z) < 70 ? Material.BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS, Material.RED_NETHER_BRICK_STAIRS));
            steps.add(() -> platform(0,
                    (x, y, z) -> ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : (hash(x, y, z) < 30 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS),
                    (x, y, z) -> hash(x, y, z) < 65 ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS, Material.STONE_BRICK_STAIRS));
            steps.add(() -> platform(24,
                    (x, y, z) -> ((x >> 1) + (z >> 1) & 1) == 0 ? Material.QUARTZ_BLOCK : Material.SMOOTH_QUARTZ,
                    (x, y, z) -> hash(x, y, z) < 75 ? Material.QUARTZ_BRICKS : Material.CALCITE, Material.QUARTZ_STAIRS));
            steps.add(this::hell);
            steps.add(this::castle);
            steps.add(this::heaven);
            steps.add(this::pedestals);
            this.total = steps.size();
        }

        boolean run(long budgetNanos) {
            long end = System.nanoTime() + budgetNanos;
            while (!steps.isEmpty() && System.nanoTime() < end) steps.poll().run();
            return steps.isEmpty();
        }

        int progress() { return total == 0 ? 100 : (int) (100.0 * (total - steps.size()) / total); }

        // ---------------------------------------------------------------- Outils

        private void set(int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }

        private void setData(int x, int y, int z, BlockData d) { w.getBlockAt(x, y, z).setBlockData(d, false); }

        private boolean air(int x, int y, int z) { return w.getBlockAt(x, y, z).getType().isAir(); }

        static int hash(int x, int y, int z) { return Math.floorMod(x * 73856093 ^ y * 19349663 ^ z * 83492791, 100); }

        static double vnoise(int x, int y, int z, int s) {
            return hash(Math.floorDiv(x, s), Math.floorDiv(y, s), Math.floorDiv(z, s)) / 100.0;
        }

        /** Champ scalaire de la caverne : < 1 = intérieur (ellipsoïde déformé par un bruit en blocs). */
        static double field(int x, int y, int z) {
            double dx = (x - CX) / AX, dy = (y - CY) / AY, dz = (z - CZ) / AZ;
            double s = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return s + (vnoise(x, y, z, 6) - 0.5) * 0.14 + (vnoise(x + 500, y, z, 3) - 0.5) * 0.07 + (vnoise(x, y + 77, z + 31, 2) - 0.5) * 0.03;
        }

        private void clearSlice(int z) {
            for (int x = -54; x <= 54; x++) {
                for (int y = 0; y <= 56; y++) {
                    if (!air(x, y, z)) set(x, y, z, Material.AIR);
                }
            }
        }

        // ---------------------------------------------------------------- Caverne

        private Material rock(int x, int y, int z, double depth) {
            int h = hash(x, y, z);
            int h2 = hash(x + 11, y + 7, z + 3);
            boolean surf = depth < 0.35;
            if (surf && h2 == 0) return Material.SHROOMLIGHT;
            if (surf && h2 == 1) return Material.AMETHYST_BLOCK;
            if (surf && h2 == 2) return Material.RAW_GOLD_BLOCK;
            if (surf && y >= 26 && h < 18) return Material.DRIPSTONE_BLOCK;
            if (h < 30) return Material.STONE;
            if (h < 46) return Material.ANDESITE;
            if (h < 58) return Material.TUFF;
            if (h < 68) return Material.COBBLESTONE;
            if (h < 76) return surf ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE;
            if (h < 84) return (surf && y < 8) ? Material.MOSS_BLOCK : Material.DEEPSLATE;
            if (h < 90) return Material.CALCITE;
            if (h < 95) return Material.COBBLED_DEEPSLATE;
            return Material.STONE;
        }

        /** Sol dallé irrégulier avec marqueterie dorée (losanges concentriques et allée axiale vers le château). */
        private Material floor(int x, int z, double s0) {
            int d = Math.abs(x) + Math.abs(z - 4);
            if (d <= 1 || d == 4 || d == 8 || (d == 12 && ((x + z) & 1) == 0)) return Material.GOLD_BLOCK;
            if (d < 4) return Material.POLISHED_BLACKSTONE_BRICKS;
            if (d > 4 && d < 8) return ((x + z) & 3) == 0 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE;
            if (x == 0 && z < -4 && z > -19) return (z & 1) == 0 ? Material.GOLD_BLOCK : Material.POLISHED_BLACKSTONE;
            if (Math.abs(x) == 1 && z < -4 && z > -19) return Material.POLISHED_BLACKSTONE_BRICKS;
            if (s0 > 0.84 && hash(x, 5, z) < 40) return Material.MOSS_BLOCK;
            int h = hash(x, -1, z);
            if (h < 28) return Material.POLISHED_BLACKSTONE_BRICKS;
            if (h < 48) return Material.DEEPSLATE_TILES;
            if (h < 66) return Material.STONE_BRICKS;
            if (h < 76) return Material.CRACKED_STONE_BRICKS;
            if (h < 85) return Material.MOSSY_STONE_BRICKS;
            if (h < 93) return Material.POLISHED_ANDESITE;
            if (h < 97) return Material.COBBLESTONE;
            return Material.MOSS_BLOCK;
        }

        private void caveSlice(int z) {
            for (int x = -52; x <= 52; x++) {
                boolean seen = false;
                for (int y = -1; y <= 52; y++) {
                    if (y == -1) {
                        double s0 = field(x, 0, z);
                        if (s0 < 1) set(x, -1, z, floor(x, z, s0));
                        continue;
                    }
                    double s = field(x, y, z);
                    if (s < 1) {
                        seen = true;
                        continue;
                    }
                    double ds = (4 + 3 * vnoise(x, y, z, 5)) / 42.0;
                    if (s < 1 + ds) {
                        set(x, y, z, rock(x, y, z, (s - 1) / ds));
                        if (seen && top[x + OFFX][z + OFFZ] == 0) top[x + OFFX][z + OFFZ] = y;
                    } else if (seen) {
                        break;
                    }
                }
            }
        }

        private void stalactite(int x, int y, int z, int len) {
            for (int i = 0; i < len; i++) {
                if (!air(x, y - i, z)) break;
                PointedDripstone d = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
                d.setVerticalDirection(BlockFace.DOWN);
                PointedDripstone.Thickness t;
                if (i == len - 1) t = PointedDripstone.Thickness.TIP;
                else if (i == len - 2) t = PointedDripstone.Thickness.FRUSTUM;
                else if (i == 0) t = PointedDripstone.Thickness.BASE;
                else t = PointedDripstone.Thickness.MIDDLE;
                d.setThickness(t);
                setData(x, y - i, z, d);
            }
        }

        private void caveVines(int x, int y, int z, int len) {
            for (int i = 0; i < len; i++) {
                if (!air(x, y - i, z)) break;
                boolean berries = hash(x, y - i, z) < 45;
                if (i == len - 1) {
                    CaveVines head = (CaveVines) Material.CAVE_VINES.createBlockData();
                    head.setBerries(berries);
                    setData(x, y - i, z, head);
                } else {
                    CaveVinesPlant plant = (CaveVinesPlant) Material.CAVE_VINES_PLANT.createBlockData();
                    plant.setBerries(berries);
                    setData(x, y - i, z, plant);
                }
            }
        }

        /** Plafond vivant : stalactites, racines pendantes, fleurs-spores, lianes à baies lumineuses, lanternes suspendues. */
        private void ceilingDecor(int part) {
            Lantern hanging = (Lantern) Material.LANTERN.createBlockData();
            hanging.setHanging(true);
            for (int x = -52 + 26 * part; x < -52 + 26 * (part + 1); x++) {
                for (int z = -52; z <= 46; z++) {
                    int ty = top[x + OFFX][z + OFFZ];
                    if (ty == 0) continue;
                    int yb = ty - 1;
                    if (!air(x, yb, z)) continue;
                    int h = hash(x * 5, ty, z * 3);
                    if (h < 7) stalactite(x, yb, z, 2 + hash(x, ty + 3, z) % 8);
                    else if (h < 10) set(x, yb, z, Material.HANGING_ROOTS);
                    else if (h < 12) set(x, yb, z, Material.SPORE_BLOSSOM);
                    else if (h < 16) caveVines(x, yb, z, 2 + hash(x, ty, z + 9) % 5);
                    else if (h == 16 && hash(x, ty, z + 21) < 35) setData(x, yb, z, hanging);
                }
            }
        }

        private void boulder(int x, int z) {
            Material[] mats = {Material.STONE, Material.ANDESITE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.TUFF};
            int[][] offs = {{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 1}};
            int n = 3 + hash(x, 5, z) % 4;
            for (int i = 0; i < n; i++) {
                int[] o = offs[i];
                if (field(x + o[0], 0, z + o[2]) >= 1) continue;
                set(x + o[0], o[1], z + o[2], mats[hash(x + o[0], o[1] + 2, z + o[2]) % mats.length]);
            }
        }

        /** Rochers, tapis de mousse et grappes d'améthyste au sol. */
        private void floorDecor() {
            for (int x = -50; x <= 50; x++) {
                for (int z = -50; z <= 44; z++) {
                    double s0 = field(x, 0, z);
                    if (s0 >= 1) continue;
                    if (z <= -18 && Math.abs(x) <= 37) continue;
                    if (Math.abs(x) + Math.abs(z - 4) <= 14) continue;
                    int h = hash(x * 7, 91, z * 11);
                    boolean edge = s0 > 0.78;
                    if (h == 0 || (edge && h < 3)) boulder(x, z);
                    else if (edge && h < 8) set(x, 0, z, Material.MOSS_CARPET);
                    else if (edge && h < 11) set(x, 0, z, Material.AMETHYST_CLUSTER);
                }
            }
        }

        /** Cristaux bleus géants (verre teinté et lanternes marines) le long des parois. */
        private void crystals() {
            spire(-40, -10, 30, 3.4);
            spire(40, -8, 28, 3.2);
            spire(-34, 24, 15, 2.2);
            spire(35, 22, 13, 2.2);
        }

        private void spire(int cx, int cz, int h, double baseR) {
            for (int y = 0; y <= h; y++) {
                double r = Math.max(0.8, baseR * (1 - (double) y / h));
                int shift = (int) Math.round(Math.sin(y * 0.25) * 1.5);
                int ri = (int) Math.ceil(r);
                for (int dx = -ri; dx <= ri; dx++) {
                    for (int dz = -ri; dz <= ri; dz++) {
                        double d = Math.hypot(dx, dz);
                        if (d > r) continue;
                        Material m;
                        if (d <= r * 0.4) m = (y % 3 == 0) ? Material.SEA_LANTERN : Material.BLUE_STAINED_GLASS;
                        else if (d <= r * 0.75) m = Material.LIGHT_BLUE_STAINED_GLASS;
                        else m = Material.BLUE_STAINED_GLASS;
                        set(cx + dx + shift, y, cz + dz, m);
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Estrades et escaliers

        /** Estrade de 5 hauteurs (sommet y=4) sur x∈[cx-11,cx+11], z∈[-34,-22], avec grand escalier de 9 de large au sud. */
        private void platform(int cx, Pat topPat, Pat sidePat, Material stairMat) {
            for (int x = cx - 11; x <= cx + 11; x++) {
                for (int z = -34; z <= -22; z++) {
                    for (int y = 0; y <= 4; y++) set(x, y, z, y == 4 ? topPat.at(x, y, z) : sidePat.at(x, y, z));
                }
            }
            Stairs st = (Stairs) Bukkit.createBlockData(stairMat);
            st.setFacing(BlockFace.NORTH);
            for (int k = 1; k <= 4; k++) {
                int z = -22 + k, ty = 4 - k;
                for (int x = cx - 4; x <= cx + 4; x++) {
                    for (int y = 0; y < ty; y++) set(x, y, z, sidePat.at(x, y, z));
                    setData(x, ty, z, st);
                }
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int y = 0; y <= ty + 1; y++) set(cx + sx * 5, y, z, sidePat.at(cx + sx * 5, y, z));
                }
            }
        }

        // ---------------------------------------------------------------- Outils de façade

        private void body(int cx, int y0, int[] hs, Pat wall) {
            for (int dx = -10; dx <= 10; dx++) {
                int h = hs[Math.abs(dx)];
                for (int z = ZB; z <= ZF; z++) {
                    for (int y = y0; y <= y0 + h; y++) set(cx + dx, y, z, wall.at(cx + dx, y, z));
                }
            }
        }

        private void pilasters(int cx, int y0, int[] hs, int[] at, Material shaft, Material chisel, Material cap) {
            for (int dx : at) {
                int h = hs[Math.abs(dx)];
                for (int y = y0; y <= y0 + h - 2; y++) set(cx + dx, y, ZF + 1, ((y - y0) % 4 == 3) ? chisel : shaft);
                set(cx + dx, y0 + h - 1, ZF + 1, cap);
            }
        }

        private void courses(int cx, int y0, int[] hs, int[] offs, Material chisel, Material accent) {
            for (int off : offs) {
                for (int dx = -10; dx <= 10; dx++) {
                    if (off < hs[Math.abs(dx)]) set(cx + dx, y0 + off, ZF + 1, (dx % 5 == 0) ? accent : chisel);
                }
            }
        }

        private void carveOpening(int cx, int[] half, int y0) {
            for (int r = 0; r < half.length; r++) {
                for (int dx = -half[r]; dx <= half[r]; dx++) {
                    for (int z = ZB; z <= ZF + 1; z++) set(cx + dx, y0 + r, z, Material.AIR);
                }
            }
        }

        /** Trois anneaux d'encadrement autour de l'ouverture (relief vers l'avant). */
        private void rings(int cx, int[] half, int y0, Material r1, Material r2, Material r3) {
            final int W = 10, R = 20;
            boolean[][] open = new boolean[2 * W + 1][R + 1];
            for (int r = 0; r < half.length; r++) {
                for (int dx = -half[r]; dx <= half[r]; dx++) open[dx + W][r + 3] = true;
            }
            for (int gx = 0; gx <= 2 * W; gx++) {
                for (int gr = 0; gr <= R; gr++) {
                    if (open[gx][gr]) continue;
                    int best = 99;
                    for (int ox = Math.max(0, gx - 3); ox <= Math.min(2 * W, gx + 3); ox++) {
                        for (int oy = Math.max(0, gr - 3); oy <= Math.min(R, gr + 3); oy++) {
                            if (open[ox][oy]) best = Math.min(best, Math.abs(gx - ox) + Math.abs(gr - oy));
                        }
                    }
                    int r = gr - 3;
                    if (best > 3 || r < 0) continue;
                    int x = cx + gx - W, y = y0 + r;
                    Material m = best == 1 ? r1 : best == 2 ? r2 : r3;
                    set(x, y, ZF, m);
                    if (best <= 2) set(x, y, ZF + 1, m);
                    if (best == 1) set(x, y, ZF - 1, m);
                }
            }
        }

        private void membrane(int cx, int[] half, int y0, Material glass) {
            for (int r = 0; r < half.length; r++) {
                for (int dx = -half[r]; dx <= half[r]; dx++) set(cx + dx, y0 + r, DonjonPlugin.PORTAL_Z, glass);
            }
            for (int r : new int[]{2, 6, 9}) {
                for (int dx : new int[]{-2, 0, 2}) {
                    if (Math.abs(dx) <= half[r]) set(cx + dx, y0 + r, DonjonPlugin.PORTAL_Z - 2, Material.LIGHT);
                }
            }
        }

        private void rose(int cx, int cy, int z, int radius, Material ring, Material spoke, Material fill, Material core) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    double r = Math.hypot(dx, dy);
                    if (r > radius + 0.5) continue;
                    Material m;
                    if (r >= radius - 1.0) m = ring;
                    else if (r <= 0.9) m = core;
                    else if (dx == 0 || dy == 0 || Math.abs(dx) == Math.abs(dy)) m = spoke;
                    else m = fill;
                    set(cx + dx, cy + dy, z, m);
                    set(cx + dx, cy + dy, z - 1, m);
                }
            }
        }

        /** Pyramide étagée ; retourne le y du dessus. */
        private int pyramid(int cx, int y, int cz, int hx, int hz, Material a, Material b) {
            int i = 0;
            while (hx - i >= 0 && hz - i >= 0) {
                for (int dx = -(hx - i); dx <= hx - i; dx++) {
                    for (int dz = -(hz - i); dz <= hz - i; dz++) set(cx + dx, y + i, cz + dz, (i % 2 == 0) ? a : b);
                }
                i++;
            }
            return y + i;
        }

        private void spire(int x, int y, int z, int len, Material a, Material b, Material tip) {
            for (int i = 0; i < len; i++) set(x, y + i, z, i % 2 == 0 ? a : b);
            set(x, y + len, z, tip);
        }

        private void banner(int x, int topY, int z, int wd, int ht, Material cloth, Material emblem, Material trim, Material fringe) {
            for (int dx = 0; dx < wd; dx++) {
                set(x + dx, topY + 1, z, trim);
                for (int dy = 0; dy < ht; dy++) set(x + dx, topY - dy, z, cloth);
                set(x + dx, topY - ht, z, fringe);
            }
            int ex = x + (wd - 1) / 2, ey = topY - ht / 2;
            set(ex, ey, z, emblem);
            set(ex, ey + 1, z, emblem);
            set(ex, ey - 1, z, emblem);
            if (wd > 1) {
                set(ex + 1, ey, z, emblem);
                set(ex - 1, ey, z, emblem);
            }
        }

        private void windowSlit(int x, int y, int z, int ht, Material glass, Material frame) {
            for (int i = 0; i < ht; i++) {
                set(x, y + i, z, glass);
                set(x - 1, y + i, z, frame);
                set(x + 1, y + i, z, frame);
            }
            set(x, y - 1, z, frame);
            set(x, y + ht, z, frame);
        }

        private void hangLantern(int x, int y, int z, Material lantern) {
            Lantern d = (Lantern) lantern.createBlockData();
            d.setHanging(true);
            setData(x, y, z, d);
        }

        /** Lianes accrochées sous les corniches (collées au mur nord de la façade). */
        private void ivy(int cx, int y0, int[] hs, int[] courseOffs) {
            MultipleFacing vine = (MultipleFacing) Material.VINE.createBlockData();
            vine.setFace(BlockFace.NORTH, true);
            for (int off : courseOffs) {
                for (int dx = -10; dx <= 10; dx++) {
                    if (off >= hs[Math.abs(dx)] || hash(cx + dx, off, 7) >= 32) continue;
                    int len = 2 + hash(cx + dx, off, 3) % 5;
                    for (int i = 1; i <= len; i++) {
                        int y = y0 + off - i;
                        if (!air(cx + dx, y, ZF + 1) || air(cx + dx, y, ZF)) break;
                        setData(cx + dx, y, ZF + 1, vine);
                    }
                }
            }
        }

        private void spikesTop(int cx, int y0, int[] hs, int density) {
            for (int dx = -10; dx <= 10; dx++) {
                int y = y0 + hs[Math.abs(dx)] + 1;
                for (int z = ZB + 1; z <= ZF - 1; z += 2) {
                    if (hash(cx + dx, y, z) < density) set(cx + dx, y, z, Material.POINTED_DRIPSTONE);
                }
            }
        }

        // ---------------------------------------------------------------- Façade du Château (centre)

        private void castle() {
            final int cx = 0, y0 = 5, zc = (ZB + ZF) / 2;
            final int[] hs = {28, 28, 28, 28, 28, 22, 22, 17, 17, 25, 25};
            Pat wall = (x, y, z) -> {
                int h = hash(x, y, z);
                if (h < 50) return Material.STONE_BRICKS;
                if (h < 66) return Material.CRACKED_STONE_BRICKS;
                if (h < 80) return Material.MOSSY_STONE_BRICKS;
                if (h < 90) return Material.POLISHED_ANDESITE;
                if (h < 96) return Material.ANDESITE;
                return Material.DEEPSLATE_BRICKS;
            };
            body(cx, y0, hs, wall);
            pilasters(cx, y0, hs, new int[]{-10, -7, -4, 4, 7, 10}, Material.POLISHED_ANDESITE, Material.CHISELED_STONE_BRICKS, Material.GILDED_BLACKSTONE);
            courses(cx, y0, hs, new int[]{6, 12, 18}, Material.CHISELED_STONE_BRICKS, Material.GILDED_BLACKSTONE);
            carveOpening(cx, DonjonPlugin.POINTED, y0);
            rings(cx, DonjonPlugin.POINTED, y0, Material.GOLD_BLOCK, Material.CHISELED_STONE_BRICKS, Material.DEEPSLATE_BRICKS);
            membrane(cx, DonjonPlugin.POINTED, y0, Material.YELLOW_STAINED_GLASS);
            // rosace dorée
            rose(cx, y0 + 21, ZF, 5, Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.BLACK_STAINED_GLASS, Material.SEA_LANTERN);
            // flèche de la tour centrale, tours latérales, pinacles
            int ty = pyramid(cx, y0 + 29, zc, 4, 3, Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS);
            spire(cx, ty, zc, 5, Material.GILDED_BLACKSTONE, Material.CHISELED_STONE_BRICKS, Material.LIGHTNING_ROD);
            for (int sx = -1; sx <= 1; sx += 2) {
                int t1 = pyramid(cx + sx * 9, y0 + 26, zc, 2, 3, Material.DEEPSLATE_BRICKS, Material.CHISELED_STONE_BRICKS);
                spire(cx + sx * 9, t1, zc, 3, Material.GILDED_BLACKSTONE, Material.CHISELED_STONE_BRICKS, Material.LIGHTNING_ROD);
                int t2 = pyramid(cx + sx * 6, y0 + 23, zc, 1, 2, Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS);
                spire(cx + sx * 6, t2, zc, 2, Material.GILDED_BLACKSTONE, Material.CHISELED_STONE_BRICKS, Material.LIGHTNING_ROD);
                int t3 = pyramid(cx + sx * 8, y0 + 18, zc, 1, 2, Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS);
                spire(cx + sx * 8, t3, zc, 2, Material.GILDED_BLACKSTONE, Material.CHISELED_STONE_BRICKS, Material.LIGHTNING_ROD);
                // bannières noires à emblème doré, fenêtres, lanternes
                banner(sx > 0 ? cx + 5 : cx - 6, y0 + 14, ZF + 1, 2, 6, Material.BLACK_WOOL, Material.GOLD_BLOCK, Material.GILDED_BLACKSTONE, Material.YELLOW_CARPET);
                windowSlit(cx + sx * 8, y0 + 8, ZF + 1, 3, Material.LIGHT_BLUE_STAINED_GLASS, Material.GILDED_BLACKSTONE);
                windowSlit(cx + sx * 9, y0 + 14, ZF + 1, 4, Material.LIGHT_BLUE_STAINED_GLASS, Material.GILDED_BLACKSTONE);
                hangLantern(cx + sx * 8, y0 + 11, ZF + 1, Material.LANTERN);
                hangLantern(cx + sx * 8, y0 + 5, ZF + 1, Material.LANTERN);
                hangLantern(cx + sx * 5, y0 + 5, ZF + 1, Material.LANTERN);
            }
            ivy(cx, y0, hs, new int[]{6, 12, 18});
        }

        // ---------------------------------------------------------------- Façade de l'Enfer (gauche)

        private void hell() {
            final int cx = -24, y0 = 5, zc = (ZB + ZF) / 2;
            final int[] hs = {30, 30, 30, 20, 20, 27, 17, 24, 14, 20, 12};
            Pat wall = (x, y, z) -> {
                int h = hash(x, y, z);
                if (h < 30) return Material.NETHER_BRICKS;
                if (h < 55) return Material.RED_NETHER_BRICKS;
                if (h < 67) return Material.BLACKSTONE;
                if (h < 81) return Material.POLISHED_BLACKSTONE_BRICKS;
                if (h < 87) return Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
                if (h < 90) return Material.CHISELED_NETHER_BRICKS;
                if (h < 94) return Material.MAGMA_BLOCK;
                if (h < 97) return Material.NETHERRACK;
                return Material.OBSIDIAN;
            };
            body(cx, y0, hs, wall);
            pilasters(cx, y0, hs, new int[]{-9, -6, -3, 3, 6, 9}, Material.BLACKSTONE, Material.CHISELED_POLISHED_BLACKSTONE, Material.CRYING_OBSIDIAN);
            courses(cx, y0, hs, new int[]{7, 14, 21}, Material.CHISELED_NETHER_BRICKS, Material.MAGMA_BLOCK);
            carveOpening(cx, DonjonPlugin.POINTED, y0);
            rings(cx, DonjonPlugin.POINTED, y0, Material.MAGMA_BLOCK, Material.OBSIDIAN, Material.NETHER_BRICKS);
            membrane(cx, DonjonPlugin.POINTED, y0, Material.RED_STAINED_GLASS);
            // visage démoniaque : arcades sourcilières, yeux de braise, nez d'obsidienne, crocs
            for (int dx = -6; dx <= 6; dx++) {
                set(cx + dx, y0 + 19 - (Math.abs(dx) > 3 ? 1 : 0), ZF + 1, Material.POLISHED_BLACKSTONE_BRICKS);
                set(cx + dx, y0 + 18 - (Math.abs(dx) > 3 ? 1 : 0), ZF + 1, Material.BLACKSTONE);
            }
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int dx = 2; dx <= 4; dx++) {
                    set(cx + sx * dx, y0 + (dx == 4 ? 17 : 16), ZF, Material.SHROOMLIGHT);
                    set(cx + sx * dx, y0 + (dx == 4 ? 17 : 16), ZF + 1, Material.SHROOMLIGHT);
                }
                set(cx + sx * 3, y0 + 16, ZF + 1, Material.OBSIDIAN);
                // cornes crénelées
                for (int i = 0; i < 8; i++) {
                    int x = cx + sx * (6 + i), y = y0 + 20 + i;
                    for (int z = ZB + 2; z <= ZF - 2; z++) {
                        set(x, y, z, (i % 2 == 0) ? Material.NETHER_BRICKS : Material.RED_NETHER_BRICKS);
                        set(x, y - 1, z, Material.BLACKSTONE);
                    }
                    if (i == 7) set(x, y + 1, zc, Material.POINTED_DRIPSTONE);
                }
                // fenêtres de braise
                windowSlit(cx + sx * 8, y0 + 7, ZF + 1, 3, Material.ORANGE_STAINED_GLASS, Material.CRYING_OBSIDIAN);
            }
            for (int i = 0; i < 3; i++) set(cx, y0 + 13 + i, ZF + 1, Material.OBSIDIAN);
            int t = pyramid(cx, y0 + 31, zc, 3, 3, Material.BLACKSTONE, Material.NETHER_BRICKS);
            spire(cx, t, zc, 5, Material.CRYING_OBSIDIAN, Material.BLACKSTONE, Material.POINTED_DRIPSTONE);
            spikesTop(cx, y0, hs, 55);
            // crânes sur les corniches
            for (int off : new int[]{7, 14, 21}) {
                for (int dx = -9; dx <= 9; dx += 3) {
                    if (off < hs[Math.abs(dx)]) set(cx + dx, y0 + off + 1, ZF + 1, Material.WITHER_SKELETON_SKULL);
                }
            }
            // crevasses de braise devant l'estrade
            int x = cx, z = -17;
            for (int i = 0; i < 40; i++) {
                if (field(x, 0, z) < 1 && z > -19) set(x, -1, z, Material.SHROOMLIGHT);
                int hh = hash(x, i, z);
                if (hh < 40) x += 1; else if (hh < 80) x -= 1;
                z += (hh % 3 == 0) ? 0 : 1;
                if (z > 6) break;
            }
        }

        // ---------------------------------------------------------------- Façade des Cieux (droite)

        private void heaven() {
            final int cx = 24, y0 = 5, zc = (ZB + ZF) / 2;
            final int[] hs = {26, 26, 26, 26, 22, 22, 22, 18, 18, 24, 24};
            Pat wall = (x, y, z) -> {
                int h = hash(x, y, z);
                if (h < 38) return Material.QUARTZ_BRICKS;
                if (h < 56) return Material.SMOOTH_QUARTZ;
                if (h < 70) return Material.QUARTZ_BLOCK;
                if (h < 82) return Material.CALCITE;
                if (h < 90) return Material.POLISHED_DIORITE;
                if (h < 94) return Material.CHISELED_QUARTZ_BLOCK;
                if (h < 97) return Material.GOLD_BLOCK;
                return Material.SEA_LANTERN;
            };
            body(cx, y0, hs, wall);
            pilasters(cx, y0, hs, new int[]{-10, -7, -4, 4, 7, 10}, Material.QUARTZ_PILLAR, Material.CHISELED_QUARTZ_BLOCK, Material.GOLD_BLOCK);
            courses(cx, y0, hs, new int[]{6, 12, 18}, Material.CHISELED_QUARTZ_BLOCK, Material.GOLD_BLOCK);
            carveOpening(cx, DonjonPlugin.ROUND, y0);
            rings(cx, DonjonPlugin.ROUND, y0, Material.GOLD_BLOCK, Material.CHISELED_QUARTZ_BLOCK, Material.SMOOTH_QUARTZ);
            membrane(cx, DonjonPlugin.ROUND, y0, Material.WHITE_STAINED_GLASS);
            // halo solaire doré au-dessus du portail
            rose(cx, y0 + 17, ZF, 5, Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.LIGHT_BLUE_STAINED_GLASS, Material.GLOWSTONE);
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4;
                set(cx + (int) Math.round(Math.cos(ang) * 6), y0 + 17 + (int) Math.round(Math.sin(ang) * 6), ZF, Material.SEA_LANTERN);
            }
            // pinacles blancs à pointe dorée et bâtons d'extrémité lumineux
            int[] px = {-10, -7, -4, 4, 7, 10, -1, 1};
            for (int dx : px) {
                int base = y0 + hs[Math.abs(dx)] + 1;
                int t1 = pyramid(cx + dx, base, zc, 1, 2, Material.QUARTZ_BRICKS, Material.CHISELED_QUARTZ_BLOCK);
                spire(cx + dx, t1, zc, 2, Material.GOLD_BLOCK, Material.QUARTZ_PILLAR, Material.END_ROD);
            }
            for (int sx = -1; sx <= 1; sx += 2) {
                banner(sx > 0 ? cx + 5 : cx - 6, y0 + 14, ZF + 1, 2, 6, Material.BLUE_WOOL, Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.LIGHT_BLUE_WOOL);
                windowSlit(cx + sx * 8, y0 + 8, ZF + 1, 3, Material.LIGHT_BLUE_STAINED_GLASS, Material.GOLD_BLOCK);
                windowSlit(cx + sx * 9, y0 + 14, ZF + 1, 4, Material.LIGHT_BLUE_STAINED_GLASS, Material.GOLD_BLOCK);
                hangLantern(cx + sx * 8, y0 + 11, ZF + 1, Material.LANTERN);
                hangLantern(cx + sx * 8, y0 + 5, ZF + 1, Material.LANTERN);
                for (int off : new int[]{6, 12, 18}) {
                    set(cx + sx * 3, y0 + off, ZF + 1, Material.SEA_LANTERN);
                    set(cx + sx * 9, y0 + off, ZF + 1, Material.SEA_LANTERN);
                }
            }
        }

        // ---------------------------------------------------------------- Piédestaux à lanternes

        /** Piédestaux de pierre à lanternes (devant chaque estrade et au premier plan, comme dans la salle de référence). */
        private void pedestals() {
            int[][] spots = {{-12, -8}, {12, -8}, {-36, -10}, {36, -10}, {-22, 8}, {22, 8}, {-8, 22}, {8, 22},
                    {-35, -24}, {35, -24}, {-13, -24}, {13, -24}, {-7, -24}, {7, -24}};
            for (int[] s : spots) {
                int px = s[0], pz = s[1];
                boolean onPlatform = pz <= -22 && pz >= -34;
                int base = onPlatform ? 5 : 0;
                if (!onPlatform && field(px, 0, pz) >= 1) continue;
                Material a = (px < -18 && onPlatform) ? Material.BLACKSTONE : (px > 18 && onPlatform ? Material.QUARTZ_BRICKS : Material.STONE_BRICKS);
                Material b = (px < -18 && onPlatform) ? Material.POLISHED_BLACKSTONE_BRICKS : (px > 18 && onPlatform ? Material.CHISELED_QUARTZ_BLOCK : Material.ANDESITE);
                int r = onPlatform ? 0 : 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        set(px + dx, base, pz + dz, a);
                        set(px + dx, base + 1, pz + dz, b);
                    }
                }
                set(px, base + 2, pz, a);
                set(px, base + 3, pz, (px < -18 && onPlatform) ? Material.CAMPFIRE : Material.LANTERN);
            }
        }
    }
}
