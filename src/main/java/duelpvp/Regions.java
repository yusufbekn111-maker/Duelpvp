package duelpvp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Regions implements Listener, CommandExecutor {

    private static class Region {
        String name;
        String world;
        int x1, y1, z1, x2, y2, z2;
    }

    private final JavaPlugin plugin;
    private final List<Region> regions = new ArrayList<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public Regions(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        PluginCommand c = plugin.getCommand("ps");
        if (c != null) c.setExecutor(this);
        plugin.getLogger().info("Regionlar yuklandi: " + regions.size());
    }

    private void load() {
        ConfigurationSection rs = plugin.getConfig().getConfigurationSection("regions");
        if (rs == null) return;
        for (String k : rs.getKeys(false)) {
            Region r = new Region();
            r.name = k;
            r.world = rs.getString(k + ".world", "world");
            r.x1 = rs.getInt(k + ".x1");
            r.y1 = rs.getInt(k + ".y1");
            r.z1 = rs.getInt(k + ".z1");
            r.x2 = rs.getInt(k + ".x2");
            r.y2 = rs.getInt(k + ".y2");
            r.z2 = rs.getInt(k + ".z2");
            regions.add(r);
        }
    }

    private void tell(Player p, String text, NamedTextColor c) {
        p.sendMessage(Component.text(text, c));
    }

    private Region find(String name) {
        for (Region r : regions) {
            if (r.name.equalsIgnoreCase(name)) return r;
        }
        return null;
    }

    private String fmt(Location l) {
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Bu buyruq faqat o'yinda ishlaydi.");
            return true;
        }
        if (!p.hasPermission("sokinish.admin")) {
            tell(p, "Ruxsat yo'q.", NamedTextColor.RED);
            return true;
        }
        if (a.length == 0) {
            usage(p);
            return true;
        }
        UUID id = p.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "1" -> {
                Location l = p.getLocation().getBlock().getLocation();
                pos1.put(id, l);
                tell(p, "1-nuqta belgilandi: " + fmt(l), NamedTextColor.GREEN);
            }
            case "2" -> {
                Location l = p.getLocation().getBlock().getLocation();
                pos2.put(id, l);
                tell(p, "2-nuqta belgilandi: " + fmt(l), NamedTextColor.GREEN);
            }
            case "1-2" -> {
                if (a.length < 2 || !a[1].equalsIgnoreCase("start")) {
                    usage(p);
                    return true;
                }
                createRegion(p, a.length > 2 ? a[2] : null);
            }
            case "remove" -> {
                if (a.length < 2) {
                    tell(p, "Ishlatish: /ps remove <nom>", NamedTextColor.YELLOW);
                    return true;
                }
                Region r = find(a[1]);
                if (r == null) {
                    tell(p, "Bunday region yo'q.", NamedTextColor.RED);
                    return true;
                }
                regions.remove(r);
                plugin.getConfig().set("regions." + r.name, null);
                plugin.saveConfig();
                tell(p, "Region '" + r.name + "' o'chirildi.", NamedTextColor.YELLOW);
            }
            case "list" -> {
                if (regions.isEmpty()) {
                    tell(p, "Regionlar yo'q.", NamedTextColor.YELLOW);
                    return true;
                }
                for (Region r : regions) {
                    tell(p, r.name + " [" + r.world + "] " + r.x1 + " " + r.y1 + " " + r.z1
                            + " -> " + r.x2 + " " + r.y2 + " " + r.z2, NamedTextColor.YELLOW);
                }
            }
            default -> usage(p);
        }
        return true;
    }

    private void usage(Player p) {
        tell(p, "/ps 1  /ps 2  /ps 1-2 start [nom]  /ps list  /ps remove <nom>",
                NamedTextColor.YELLOW);
    }

    private void createRegion(Player p, String wanted) {
        Location l1 = pos1.get(p.getUniqueId());
        Location l2 = pos2.get(p.getUniqueId());
        if (l1 == null || l2 == null) {
            tell(p, "Avval /ps 1 va /ps 2 bilan ikkala nuqtani belgilang.", NamedTextColor.RED);
            return;
        }
        if (!l1.getWorld().equals(l2.getWorld())) {
            tell(p, "Ikkala nuqta bir dunyoda bo'lishi kerak.", NamedTextColor.RED);
            return;
        }
        String name = wanted;
        if (name == null) {
            int i = regions.size() + 1;
            while (find("region" + i) != null) i++;
            name = "region" + i;
        } else if (!name.matches("[A-Za-z0-9_-]+")) {
            tell(p, "Nom faqat harf, raqam, _ va - dan iborat bo'lsin.", NamedTextColor.RED);
            return;
        } else if (find(name) != null) {
            tell(p, "Bunday nomli region allaqachon bor.", NamedTextColor.RED);
            return;
        }
        Region r = new Region();
        r.name = name;
        r.world = l1.getWorld().getName();
        r.x1 = Math.min(l1.getBlockX(), l2.getBlockX());
        r.y1 = Math.min(l1.getBlockY(), l2.getBlockY());
        r.z1 = Math.min(l1.getBlockZ(), l2.getBlockZ());
        r.x2 = Math.max(l1.getBlockX(), l2.getBlockX());
        r.y2 = Math.max(l1.getBlockY(), l2.getBlockY());
        r.z2 = Math.max(l1.getBlockZ(), l2.getBlockZ());
        regions.add(r);
        String path = "regions." + name + ".";
        plugin.getConfig().set(path + "world", r.world);
        plugin.getConfig().set(path + "x1", r.x1);
        plugin.getConfig().set(path + "y1", r.y1);
        plugin.getConfig().set(path + "z1", r.z1);
        plugin.getConfig().set(path + "x2", r.x2);
        plugin.getConfig().set(path + "y2", r.y2);
        plugin.getConfig().set(path + "z2", r.z2);
        plugin.saveConfig();
        tell(p, "Region '" + name + "' yaratildi: " + r.x1 + " " + r.y1 + " " + r.z1 + " -> "
                + r.x2 + " " + r.y2 + " " + r.z2
                + ". Endi bu joyda blok buzib yoki qo'yib bo'lmaydi.", NamedTextColor.GREEN);
    }

    // ---------- Himoya ----------
    private boolean inRegion(Location l) {
        if (l.getWorld() == null) return false;
        String w = l.getWorld().getName();
        int x = l.getBlockX();
        int y = l.getBlockY();
        int z = l.getBlockZ();
        for (Region r : regions) {
            if (r.world.equals(w) && x >= r.x1 && x <= r.x2 && y >= r.y1 && y <= r.y2
                    && z >= r.z1 && z <= r.z2) {
                return true;
            }
        }
        return false;
    }

    private boolean denied(Player p, Location l) {
        if (!inRegion(l)) return false;
        if (p.hasPermission("sokinish.admin") && p.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        p.sendActionBar(Component.text("Bu hududda blok buzish va qo'yish mumkin emas.",
                NamedTextColor.RED));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inRegion(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> inRegion(b.getLocation()));
    }
        }
