package duelpvp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class DuelPvP extends JavaPlugin implements Listener {

    private static class Invite {
        UUID from;
        String fromName;
        String kit;
        int rounds;
        long time;
    }

    private static class Saved {
        ItemStack[] inv;
        ItemStack[] armor;
        Location loc;
        GameMode mode;
        double health;
        int food;
    }

    private static class Match {
        UUID a, b;
        String nameA, nameB, kit;
        int rounds, need, winsA, winsB, round;
        boolean active;
        Map<UUID, Saved> saved = new HashMap<>();
    }

    private static class Edit {
        String name;
        Saved saved;
    }

    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, Edit> editing = new HashMap<>();
    private Match current;

    @Override
    public void onEnable() {
        getConfig().addDefault("duel.kits.klassik", List.of(
                "give {player} diamond_sword",
                "give {player} golden_apple 3",
                "item replace entity {player} armor.head with diamond_helmet",
                "item replace entity {player} armor.chest with diamond_chestplate",
                "item replace entity {player} armor.legs with diamond_leggings",
                "item replace entity {player} armor.feet with diamond_boots"));
        getConfig().addDefault("duel.kits.temir", List.of(
                "give {player} iron_sword",
                "give {player} golden_apple 2",
                "item replace entity {player} armor.head with iron_helmet",
                "item replace entity {player} armor.chest with iron_chestplate",
                "item replace entity {player} armor.legs with iron_leggings",
                "item replace entity {player} armor.feet with iron_boots"));
        getConfig().options().copyDefaults(true);
        saveConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DuelPvP yoqildi. Kitlar: " + kitNames());
    }

    @Override
    public void onDisable() {
        if (current != null) finish(current, null);
        for (Map.Entry<UUID, Edit> en : editing.entrySet()) {
            Player pl = Bukkit.getPlayer(en.getKey());
            if (pl != null) restore(pl, en.getValue().saved);
        }
        editing.clear();
    }

    // ---------- Buyruqlar ----------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Bu buyruq faqat o'yinda ishlaydi.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("duelset")) {
            if (!p.hasPermission("sokinish.admin")) {
                tell(p, "Ruxsat yo'q.", NamedTextColor.RED);
                return true;
            }
            if (a.length != 1 || !(a[0].equals("1") || a[0].equals("2"))) {
                tell(p, "Ishlatish: /duelset 1 yoki /duelset 2", NamedTextColor.RED);
                return true;
            }
            getConfig().set("duel.spawn" + a[0], p.getLocation());
            saveConfig();
            tell(p, a[0] + "-nuqta saqlandi.", NamedTextColor.GREEN);
            return true;
        }
        if (a.length == 0) {
            tell(p, "Ishlatish: /duel <nick> <kit> <raund>  (Kitlar: " + kitNames() + ")",
                    NamedTextColor.YELLOW);
            if (p.hasPermission("sokinish.admin")) {
                tell(p, "Admin: /duel kit edit <nom> | save | cancel | delete <nom> | list",
                        NamedTextColor.GRAY);
            }
            return true;
        }
        if (a[0].equalsIgnoreCase("kit")) {
            kitCmd(p, a);
        } else if (a[0].equalsIgnoreCase("accept")) {
            accept(p);
        } else if (a[0].equalsIgnoreCase("deny")) {
            deny(p);
        } else {
            send(p, a);
        }
        return true;
    }

    private Set<String> allKits() {
        Set<String> set = new LinkedHashSet<>();
        ConfigurationSection a = getConfig().getConfigurationSection("duel.itemkits");
        if (a != null) set.addAll(a.getKeys(false));
        ConfigurationSection b = getConfig().getConfigurationSection("duel.kits");
        if (b != null) set.addAll(b.getKeys(false));
        return set;
    }

    private String kitNames() {
        Set<String> set = allKits();
        return set.isEmpty() ? "-" : String.join(", ", set);
    }

    private String findKit(String name) {
        for (String k : allKits()) {
            if (k.equalsIgnoreCase(name)) return k;
        }
        return null;
    }

    private boolean giveItemKit(Player p, String key) {
        List<?> items = getConfig().getList("duel.itemkits." + key + ".items");
        if (items == null) return false;
        ItemStack[] arr = new ItemStack[p.getInventory().getSize()];
        for (int i = 0; i < items.size() && i < arr.length; i++) {
            if (items.get(i) instanceof ItemStack is) arr[i] = is;
        }
        p.getInventory().setContents(arr);
        return true;
    }

    // ---------- Kit tahrirlash (admin) ----------
    private void kitCmd(Player p, String[] a) {
        if (!p.hasPermission("sokinish.admin")) {
            tell(p, "Ruxsat yo'q.", NamedTextColor.RED);
            return;
        }
        if (a.length < 2) {
            tell(p, "Ishlatish: /duel kit edit <nom> | save | cancel | delete <nom> | list",
                    NamedTextColor.YELLOW);
            return;
        }
        String sub = a[1].toLowerCase();
        UUID id = p.getUniqueId();
        switch (sub) {
            case "list" -> tell(p, "Kitlar: " + kitNames(), NamedTextColor.YELLOW);
            case "edit" -> {
                if (a.length < 3) {
                    tell(p, "Ishlatish: /duel kit edit <nom>", NamedTextColor.YELLOW);
                    return;
                }
                if (editing.containsKey(id)) {
                    tell(p, "Avval /duel kit save yoki /duel kit cancel yozing.",
                            NamedTextColor.RED);
                    return;
                }
                if (inMatch(p)) {
                    tell(p, "Duel paytida kit tahrirlab bo'lmaydi.", NamedTextColor.RED);
                    return;
                }
                if (!a[2].matches("[A-Za-z0-9_-]+")) {
                    tell(p, "Nom faqat harf, raqam, _ va - dan iborat bo'lsin.",
                            NamedTextColor.RED);
                    return;
                }
                String existing = findKit(a[2]);
                Edit ed = new Edit();
                ed.name = existing != null ? existing : a[2].toLowerCase();
                ed.saved = save(p);
                p.getInventory().clear();
                giveItemKit(p, ed.name);
                p.setGameMode(GameMode.CREATIVE);
                editing.put(id, ed);
                tell(p, "Tahrirlash rejimi: '" + ed.name + "'. Narsalarni joylab /duel kit save "
                        + "yozing. Bekor qilish: /duel kit cancel", NamedTextColor.GREEN);
            }
            case "save" -> {
                Edit ed = editing.remove(id);
                if (ed == null) {
                    tell(p, "Siz tahrirlash rejimida emassiz.", NamedTextColor.RED);
                    return;
                }
                List<ItemStack> list = Arrays.asList(p.getInventory().getContents());
                getConfig().set("duel.itemkits." + ed.name + ".items", list);
                saveConfig();
                restore(p, ed.saved);
                tell(p, "Kit '" + ed.name + "' saqlandi.", NamedTextColor.GREEN);
            }
            case "cancel" -> {
                Edit ed = editing.remove(id);
                if (ed == null) {
                    tell(p, "Siz tahrirlash rejimida emassiz.", NamedTextColor.RED);
                    return;
                }
                restore(p, ed.saved);
                tell(p, "Tahrirlash bekor qilindi.", NamedTextColor.YELLOW);
            }
            case "delete" -> {
                if (a.length < 3) {
                    tell(p, "Ishlatish: /duel kit delete <nom>", NamedTextColor.YELLOW);
                    return;
                }
                String key = findKit(a[2]);
                if (key == null) {
                    tell(p, "Bunday kit yo'q.", NamedTextColor.RED);
                    return;
                }
                getConfig().set("duel.itemkits." + key, null);
                getConfig().set("duel.kits." + key, null);
                saveConfig();
                tell(p, "Kit '" + key + "' o'chirildi.", NamedTextColor.YELLOW);
            }
            default -> tell(p, "Noma'lum buyruq. /duel kit list", NamedTextColor.RED);
        }
    }

    private void tell(Player p, String text, NamedTextColor c) {
        p.sendMessage(Component.text(text, c));
    }

    private boolean inMatch(Player p) {
        return current != null && isIn(current, p);
    }

    private boolean isIn(Match m, Player p) {
        return p.getUniqueId().equals(m.a) || p.getUniqueId().equals(m.b);
    }

    private void send(Player p, String[] a) {
        if (editing.containsKey(p.getUniqueId())) {
            tell(p, "Avval kit tahrirlashni tugating (/duel kit save yoki cancel).",
                    NamedTextColor.RED);
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            tell(p, "O'yinchi topilmadi.", NamedTextColor.RED);
            return;
        }
        if (t.equals(p)) {
            tell(p, "O'zingizni duelga chaqira olmaysiz.", NamedTextColor.RED);
            return;
        }
        if (a.length < 3) {
            tell(p, "Ishlatish: /duel <nick> <kit> <raund>  (Kitlar: " + kitNames() + ")",
                    NamedTextColor.YELLOW);
            return;
        }
        String kit = findKit(a[1]);
        if (kit == null) {
            tell(p, "Bunday kit yo'q. Kitlar: " + kitNames(), NamedTextColor.RED);
            return;
        }
        int rounds;
        try {
            rounds = Integer.parseInt(a[2]);
        } catch (NumberFormatException ex) {
            tell(p, "Raund son bo'lishi kerak (1, 3, 5...).", NamedTextColor.RED);
            return;
        }
        if (rounds < 1 || rounds > 9 || rounds % 2 == 0) {
            tell(p, "Raund toq son bo'lsin: 1, 3, 5, 7 yoki 9.", NamedTextColor.RED);
            return;
        }
        if (getConfig().getLocation("duel.spawn1") == null
                || getConfig().getLocation("duel.spawn2") == null) {
            tell(p, "Arena sozlanmagan. Admin /duelset 1 va /duelset 2 qilsin.",
                    NamedTextColor.RED);
            return;
        }
        if (current != null) {
            tell(p, "Arena hozir band, birozdan keyin urinib ko'ring.", NamedTextColor.RED);
            return;
        }
        Invite inv = new Invite();
        inv.from = p.getUniqueId();
        inv.fromName = p.getName();
        inv.kit = kit;
        inv.rounds = rounds;
        inv.time = System.currentTimeMillis();
        invites.put(t.getUniqueId(), inv);

        Component msg = Component.text("⚔ " + p.getName() + " sizni duelga chaqirdi!",
                        NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("Kit: ", NamedTextColor.GRAY))
                .append(Component.text(kit, NamedTextColor.YELLOW))
                .append(Component.text("   Raund: ", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(rounds), NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("[QABUL QILISH]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/duel accept"))
                        .hoverEvent(HoverEvent.showText(Component.text("Duelni qabul qilish"))))
                .append(Component.text("   "))
                .append(Component.text("[RAD ETISH]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/duel deny"))
                        .hoverEvent(HoverEvent.showText(Component.text("Duelni rad etish"))));
        t.sendMessage(msg);
        tell(p, t.getName() + " ga duel taklifi yuborildi (60 soniya amal qiladi).",
                NamedTextColor.GREEN);
    }

    private void accept(Player p) {
        if (editing.containsKey(p.getUniqueId())) {
            tell(p, "Avval kit tahrirlashni tugating.", NamedTextColor.RED);
            return;
        }
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null || System.currentTimeMillis() - inv.time > 60000) {
            tell(p, "Sizda faol duel taklifi yo'q.", NamedTextColor.RED);
            return;
        }
        Player from = Bukkit.getPlayer(inv.from);
        if (from == null) {
            tell(p, "Taklif yuborgan o'yinchi serverda yo'q.", NamedTextColor.RED);
            return;
        }
        if (current != null) {
            tell(p, "Arena hozir band.", NamedTextColor.RED);
            return;
        }
        startMatch(from, p, inv);
    }

    private void deny(Player p) {
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null) {
            tell(p, "Sizda faol duel taklifi yo'q.", NamedTextColor.RED);
            return;
        }
        tell(p, "Duelni rad etdingiz.", NamedTextColor.YELLOW);
        Player from = Bukkit.getPlayer(inv.from);
        if (from != null) {
            tell(from, p.getName() + " duelni rad etdi.", NamedTextColor.RED);
        }
    }

    // ---------- Duel jarayoni ----------
    private Saved save(Player p) {
        Saved sv = new Saved();
        sv.inv = p.getInventory().getContents().clone();
        sv.armor = p.getInventory().getArmorContents().clone();
        sv.loc = p.getLocation().clone();
        sv.mode = p.getGameMode();
        sv.health = p.getHealth();
        sv.food = p.getFoodLevel();
        return sv;
    }

    private void restore(Player p, Saved sv) {
        p.getInventory().clear();
        p.getInventory().setContents(sv.inv);
        p.getInventory().setArmorContents(sv.armor);
        p.setGameMode(sv.mode);
        p.setHealth(Math.min(sv.health, p.getMaxHealth()));
        p.setFoodLevel(sv.food);
        p.teleport(sv.loc);
    }

    private void startMatch(Player x, Player y, Invite inv) {
        Match m = new Match();
        m.a = x.getUniqueId();
        m.b = y.getUniqueId();
        m.nameA = x.getName();
        m.nameB = y.getName();
        m.kit = inv.kit;
        m.rounds = inv.rounds;
        m.need = inv.rounds / 2 + 1;
        m.saved.put(m.a, save(x));
        m.saved.put(m.b, save(y));
        current = m;
        startRound(m);
    }

    private void prepare(Player p, Location spawn, Match m) {
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        for (PotionEffect ef : p.getActivePotionEffects()) {
            p.removePotionEffect(ef.getType());
        }
        p.setFireTicks(0);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.teleport(spawn);
        if (!giveItemKit(p, m.kit)) {
            for (String c : getConfig().getStringList("duel.kits." + m.kit)) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        c.replace("{player}", p.getName()));
            }
        }
    }

    private void startRound(Match m) {
        Player pa = Bukkit.getPlayer(m.a);
        Player pb = Bukkit.getPlayer(m.b);
        if (pa == null || pb == null) {
            finish(m, null);
            return;
        }
        m.round++;
        prepare(pa, getConfig().getLocation("duel.spawn1"), m);
        prepare(pb, getConfig().getLocation("duel.spawn2"), m);
        m.active = true;
        Component msg = Component.text("Raund " + m.round + "/" + m.rounds
                + " boshlandi! Kit: " + m.kit, NamedTextColor.GOLD);
        pa.sendMessage(msg);
        pb.sendMessage(msg);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (current == null || !(e.getEntity() instanceof Player p)) return;
        Match m = current;
        if (!isIn(m, p)) return;
        if (!m.active) {
            e.setCancelled(true);
            return;
        }
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        e.setCancelled(true);
        endRound(m, p);
    }

    private void endRound(Match m, Player loser) {
        m.active = false;
        boolean aLost = loser.getUniqueId().equals(m.a);
        if (aLost) m.winsB++;
        else m.winsA++;
        String score = m.nameA + " " + m.winsA + " - " + m.winsB + " " + m.nameB;
        Component msg = Component.text("Raund tugadi! Hisob: " + score, NamedTextColor.YELLOW);
        for (UUID id : new UUID[]{m.a, m.b}) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) pl.sendMessage(msg);
        }
        UUID winner = null;
        if (m.winsA >= m.need) winner = m.a;
        if (m.winsB >= m.need) winner = m.b;
        final UUID w = winner;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (current != m) return;
            if (w != null) finish(m, w);
            else startRound(m);
        }, 60L);
    }

    private void finish(Match m, UUID winner) {
        if (winner != null) {
            String wn = winner.equals(m.a) ? m.nameA : m.nameB;
            String ln = winner.equals(m.a) ? m.nameB : m.nameA;
            Bukkit.broadcast(Component.text("⚔ " + wn + " duelda " + ln + " ni yutdi! ("
                    + m.winsA + "-" + m.winsB + ")", NamedTextColor.GOLD));
        }
        for (UUID id : new UUID[]{m.a, m.b}) {
            Player pl = Bukkit.getPlayer(id);
            Saved sv = m.saved.get(id);
            if (pl != null && sv != null) restore(pl, sv);
        }
        if (current == m) current = null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        invites.remove(e.getPlayer().getUniqueId());
        Edit ed = editing.remove(e.getPlayer().getUniqueId());
        if (ed != null) restore(e.getPlayer(), ed.saved);
        if (current != null && isIn(current, e.getPlayer())) {
            UUID other = e.getPlayer().getUniqueId().equals(current.a) ? current.b : current.a;
            current.active = false;
            finish(current, other);
        }
    }
        }
