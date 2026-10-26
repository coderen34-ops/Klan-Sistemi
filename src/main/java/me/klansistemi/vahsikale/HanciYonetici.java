package me.klansistemi.vahsikale;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Para;

/**
 * Hanci (istihbarat) NPC'si ve han yapisi. Koy bolgesi (MeslekSistemi koy.yml dikdortgeni) disinda, duz ve
 * dogal arazide, claim'lerle/koyle kesismeyen bir noktaya 9x9 prosedurel bir han kurar (arazi yedeklenir,
 * /npcklan hanci sil ile eski haline donulur) ve icine hanci Villager'i koyar. Sag tik: bilgi, Shift+sag tik: bahsisle dedikodu.
 */
public class HanciYonetici implements Listener {

    private static final int GEN = 9;
    private final KlanSistemi plugin;
    private final NpcDunyaYonetici npc;
    private final NamespacedKey hanciKey;
    private final File dosya;
    private final Random rng = new Random();

    private static final class Han { String key, dunya; UUID villagerId; int vx, vy, vz; boolean yarim; }
    private final java.util.Map<String, Han> hanlar = new java.util.LinkedHashMap<>();
    private boolean veriYuklendi = false;

    public HanciYonetici(KlanSistemi plugin, NpcDunyaYonetici npc) {
        this.plugin = plugin;
        this.npc = npc;
        this.hanciKey = new NamespacedKey(plugin, "hanci");
        this.dosya = new File(plugin.getDataFolder(), "hanci.yml");
        yukle();
    }

    private boolean aktif() { return plugin.getConfig().getBoolean("npc.hanci-aktif", true); }

    private File yedekDosyasi(String key) { return new File(plugin.getDataFolder(), "hanci_yedek_" + key.replaceAll("[^A-Za-z0-9_]", "_") + ".yml"); }

    // ------------------------------------------------------------------ DONGU
    /** Periyodik: kayitli hanlarin hancisi kaybolduysa yeniden dogurur (han YOKSA kurmaz: kurulum kervan koye ulasinca). */
    public void kontrol() {
        if (!aktif() || !veriYuklendi) return;
        for (Han h : new java.util.ArrayList<>(hanlar.values())) {
            try {
                if (h.yarim) continue;
                World w = Bukkit.getWorld(h.dunya);
                if (w == null || !w.isChunkLoaded(h.vx >> 4, h.vz >> 4)) continue; // yuklu degilse dokunma (cift dogurma riski)
                Entity e = h.villagerId == null ? null : Bukkit.getEntity(h.villagerId);
                if (e != null && !e.isDead()) continue;
                boolean bulundu = false;
                for (Entity n : w.getNearbyEntities(new Location(w, h.vx, h.vy, h.vz), 6, 6, 6)) {
                    if (n instanceof Villager v && v.getPersistentDataContainer().has(hanciKey, PersistentDataType.BYTE)) { h.villagerId = v.getUniqueId(); kaydet(); bulundu = true; break; }
                }
                if (!bulundu) dogur(h, w);
            } catch (Exception ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Hanci kontrolu hatasi", ex);
            }
        }
    }

    // ------------------------------------------------------------------ KURULUM
    /** Kervan koye ulasti: o koyde han yoksa kurar. */
    public void koyaHanciKur(String key, World w) {
        if (!aktif() || !veriYuklendi || hanlar.containsKey(key)) return;
        kur(key, w, null);
    }

    /** Admin komutu: koy.yml'deki koye han kurar. */
    public boolean kur(org.bukkit.command.CommandSender admin) {
        double[] koy = YasakAlan.koyDikdortgeniGetir();
        String kd = YasakAlan.koyDunyasiGetir();
        World w = kd != null ? Bukkit.getWorld(kd) : null;
        if (koy == null || w == null) { admin.sendMessage(ChatColor.RED + "Koy bolgesi (koy.yml) tanimli degil."); return false; }
        return kur("koy", w, admin);
    }

    private boolean kur(String key, World w, org.bukkit.command.CommandSender admin) {
        Han mevcut = hanlar.get(key);
        if (mevcut != null) {
            if (admin != null) admin.sendMessage(ChatColor.RED + (mevcut.yarim ? "Yarim kalmis kurulum var: once /npcklan hanci sil (araziyi geri yukler)." : "Han zaten kurulu. Once /npcklan hanci sil."));
            return false;
        }
        double[] koy = YasakAlan.koyDikdortgeniGetir();
        if (koy == null) return false;
        for (int deneme = 0; deneme < 80; deneme++) {
            int yon = rng.nextInt(4);
            double mesafe = 10 + rng.nextDouble() * 35; // koy kenarindan 10-45 blok disarida
            double ort = rng.nextDouble();
            int x, z;
            switch (yon) {
                case 0 -> { x = (int) (koy[0] + (koy[1] - koy[0]) * ort); z = (int) (koy[2] - mesafe); }
                case 1 -> { x = (int) (koy[0] + (koy[1] - koy[0]) * ort); z = (int) (koy[3] + mesafe); }
                case 2 -> { x = (int) (koy[0] - mesafe); z = (int) (koy[2] + (koy[3] - koy[2]) * ort); }
                default -> { x = (int) (koy[1] + mesafe); z = (int) (koy[2] + (koy[3] - koy[2]) * ort); }
            }
            int x0 = x - GEN / 2, z0 = z - GEN / 2;
            int zemin = uygunMu(w, x0, z0);
            if (zemin == Integer.MIN_VALUE) continue;
            if (!insa(key, w, x0, z0, zemin)) return false;
            if (admin != null) admin.sendMessage(ChatColor.GREEN + "Han kuruldu: " + x + ", " + zemin + ", " + z);
            return true;
        }
        if (admin != null) admin.sendMessage(ChatColor.RED + "Uygun duz arazi bulunamadi, tekrar dene.");
        return false;
    }

    /** Uygunsa zemin Y'si (taban blogu), degilse Integer.MIN_VALUE. */
    private int uygunMu(World w, int x0, int z0) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -1; dx <= GEN; dx++) {
            for (int dz = -1; dz <= GEN; dz++) {
                int x = x0 + dx, z = z0 + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) w.getChunkAt(x >> 4, z >> 4);
                int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                Material m = w.getBlockAt(x, y, z).getType();
                if (!m.isSolid() || m == Material.WATER || m == Material.LAVA || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m)
                        || Tag.PLANKS.isTagged(m) || Tag.ICE.isTagged(m) || m == Material.BEDROCK) return Integer.MIN_VALUE;
                min = Math.min(min, y); max = Math.max(max, y);
            }
        }
        if (max - min > 2) return Integer.MIN_VALUE;
        int cx = x0 + GEN / 2, cz = z0 + GEN / 2;
        if (YasakAlan.koyaDegiyor(w, cx, cz, GEN / 2 + 6)) return Integer.MIN_VALUE;
        if (YasakAlan.claimeDegiyor(w, cx, cz, GEN / 2 + 6)) return Integer.MIN_VALUE;
        // Vahsi Kale yakinligi
        for (VahsiKale k : plugin.vahsiKale().tumKaleler()) {
            if (k.merkez != null && k.merkez.getWorld() != null && k.merkez.getWorld().equals(w) && k.merkez.distance(new Location(w, cx, k.merkez.getY(), cz)) < 120) return Integer.MIN_VALUE;
        }
        return max; // zemin: en yuksek nokta, cukurlar kayalikla doldurulur
    }

    private void set(World w, int x, int y, int z, String veri) {
        w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(veri), false);
    }

    private boolean insa(String key, World w, int x0, int z0, int fy) {
        // 1) arazi yedegi (kutu: x0..x0+8, fy-5..fy+8, z0..z0+8)
        int yMin = fy - 5, yMax = fy + 8;
        List<String> yedek = new ArrayList<>();
        for (int x = 0; x < GEN; x++) for (int y = yMin; y <= yMax; y++) for (int z = 0; z < GEN; z++) {
            yedek.add(w.getBlockAt(x0 + x, y, z0 + z).getBlockData().getAsString());
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("dunya", w.getName()); y.set("x0", x0); y.set("z0", z0); y.set("fy", fy); y.set("yedek", yedek);
        File yedekDosya = yedekDosyasi(key);
        // villager henuz yok; once yedegi diske yaz, sonra insa et (yarim kalirsa geri alinabilsin)
        try { y.save(yedekDosya); } catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.WARNING, "han yedegi yazilamadi - han kurulmadi", e); return false; }
        Han h = new Han(); h.key = key; h.dunya = w.getName(); h.yarim = true; // insa bitene kadar yarim isaretli (cokerse otomatik yeniden kurulmaz)
        h.vx = x0 + 4; h.vy = fy + 1; h.vz = z0 + 4;
        hanlar.put(key, h);
        kaydet();

        // 2) temizle (zeminin ustu) + temel
        for (int x = 0; x < GEN; x++) for (int z = 0; z < GEN; z++) {
            for (int yy = fy + 1; yy <= fy + 8; yy++) set(w, x0 + x, yy, z0 + z, "minecraft:air");
            for (int yy = fy - 4; yy < fy; yy++) {
                Block b = w.getBlockAt(x0 + x, yy, z0 + z);
                if (!b.getType().isSolid()) set(w, x0 + x, yy, z0 + z, "minecraft:cobblestone");
            }
            set(w, x0 + x, fy, z0 + z, "minecraft:spruce_planks");
        }
        // 3) duvarlar (y = fy+1..fy+3)
        for (int x = 0; x < GEN; x++) for (int z = 0; z < GEN; z++) {
            boolean kenar = x == 0 || x == GEN - 1 || z == 0 || z == GEN - 1;
            if (!kenar) continue;
            boolean kose = (x == 0 || x == GEN - 1) && (z == 0 || z == GEN - 1);
            for (int yy = 1; yy <= 3; yy++) set(w, x0 + x, fy + yy, z0 + z, kose ? "minecraft:oak_log[axis=y]" : "minecraft:oak_planks");
        }
        // kapi (kuzey duvari, x=4) + pencereler
        set(w, x0 + 4, fy + 1, z0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false]");
        set(w, x0 + 4, fy + 2, z0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false]");
        for (int px : new int[]{2, 6}) set(w, x0 + px, fy + 2, z0, "minecraft:glass_pane");
        for (int pz : new int[]{3, 5}) { set(w, x0, fy + 2, z0 + pz, "minecraft:glass_pane"); set(w, x0 + GEN - 1, fy + 2, z0 + pz, "minecraft:glass_pane"); }
        set(w, x0 + 4, fy + 2, z0 + GEN - 1, "minecraft:glass_pane");
        // 4) besik cati (X ekseni boyunca, tasmali): k=0..3 merdiven katmanlari, k=4 sirt
        for (int k = 0; k <= 4; k++) {
            int yy = fy + 4 + k;
            for (int x = -1; x <= GEN; x++) {
                if (k < 4) {
                    set(w, x0 + x, yy, z0 + k, "minecraft:spruce_stairs[facing=north,half=bottom]");
                    set(w, x0 + x, yy, z0 + GEN - 1 - k, "minecraft:spruce_stairs[facing=south,half=bottom]");
                } else {
                    set(w, x0 + x, yy, z0 + 4, "minecraft:spruce_planks");
                }
            }
            // alin (gable) dolgusu: x=0 ve x=GEN-1
            for (int z = k + 1; z <= GEN - 2 - k; z++) {
                set(w, x0, yy, z0 + z, "minecraft:oak_planks");
                set(w, x0 + GEN - 1, yy, z0 + z, "minecraft:oak_planks");
            }
        }
        // tavan: duvar ust siniri (fy+4) ici dolu olsun
        for (int x = 1; x < GEN - 1; x++) for (int z = 1; z < GEN - 1; z++) set(w, x0 + x, fy + 4, z0 + z, "minecraft:spruce_planks");
        // 5) ic dekor
        set(w, x0 + 1, fy + 1, z0 + 1, "minecraft:barrel[facing=up]");
        set(w, x0 + 1, fy + 2, z0 + 1, "minecraft:lantern[hanging=false]");
        set(w, x0 + 2, fy + 1, z0 + 1, "minecraft:barrel[facing=up]");
        set(w, x0 + 7, fy + 1, z0 + 1, "minecraft:crafting_table");
        set(w, x0 + 7, fy + 1, z0 + 7, "minecraft:red_bed[facing=north,part=foot]");
        set(w, x0 + 7, fy + 1, z0 + 6, "minecraft:red_bed[facing=north,part=head]");
        set(w, x0 + 3, fy + 1, z0 + 6, "minecraft:oak_fence");
        set(w, x0 + 3, fy + 2, z0 + 6, "minecraft:oak_pressure_plate");
        set(w, x0 + 5, fy + 1, z0 + 6, "minecraft:oak_fence");
        set(w, x0 + 5, fy + 2, z0 + 6, "minecraft:oak_pressure_plate");
        set(w, x0 + 4, fy + 3, z0 + 4, "minecraft:lantern[hanging=true]");
        set(w, x0 + 4, fy + 4, z0 + 4, "minecraft:spruce_planks");
        set(w, x0 + 4, fy + 3, z0 + 5, "minecraft:air");
        // kapi onu basamak
        set(w, x0 + 4, fy, z0 - 1, "minecraft:spruce_planks");

        h.yarim = false;
        dogur(h, w);
        plugin.getLogger().info("[Hanci] Han kuruldu (" + key + "): " + h.dunya + " " + h.vx + "," + h.vy + "," + h.vz);
        return true;
    }

    private void dogur(Han h, World w) {
        Location l = new Location(w, h.vx + 0.5, h.vy, h.vz + 0.5);
        Villager v = w.spawn(l, Villager.class);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setProfession(Villager.Profession.LIBRARIAN);
        v.setCustomName(ChatColor.GOLD + "Hanci");
        v.setCustomNameVisible(true);
        v.getPersistentDataContainer().set(hanciKey, PersistentDataType.BYTE, (byte) 1);
        h.villagerId = v.getUniqueId();
        kaydet();
    }

    // ------------------------------------------------------------------ KALDIR
    public boolean sil(org.bukkit.command.CommandSender admin) {
        Han h = hanlar.get("koy");
        File yedekDosya = yedekDosyasi("koy");
        if (h == null || !yedekDosya.exists()) { admin.sendMessage(ChatColor.RED + "Kayitli han yok."); return false; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(yedekDosya);
        World w = Bukkit.getWorld(y.getString("dunya", ""));
        List<String> yedek = y.getStringList("yedek");
        if (w == null || yedek.isEmpty()) { admin.sendMessage(ChatColor.RED + "Yedek okunamadi, han silinmedi."); return false; }
        int x0 = y.getInt("x0"), z0 = y.getInt("z0"), fy = y.getInt("fy");
        Entity e = h.villagerId == null ? null : Bukkit.getEntity(h.villagerId);
        if (e != null) e.remove();
        int i = 0, yMin = fy - 5, yMax = fy + 8;
        for (int x = 0; x < GEN; x++) for (int yy = yMin; yy <= yMax; yy++) for (int z = 0; z < GEN; z++) {
            BlockData bd = Bukkit.createBlockData(yedek.get(i++));
            w.getBlockAt(x0 + x, yy, z0 + z).setBlockData(bd, false);
        }
        hanlar.remove("koy");
        yedekDosya.delete();
        kaydet();
        admin.sendMessage(ChatColor.GREEN + "Han kaldirildi, arazi geri yuklendi.");
        return true;
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler
    public void onEtkilesim(PlayerInteractEntityEvent ev) {
        if (!(ev.getRightClicked() instanceof Villager v) || !v.getPersistentDataContainer().has(hanciKey, PersistentDataType.BYTE)) return;
        ev.setCancelled(true);
        Player p = ev.getPlayer();
        if (p.isSneaking()) { npc.dedikodu(p); return; }
        p.sendMessage(ChatColor.GOLD + "Hanci: " + ChatColor.YELLOW + "\"Kervanlardan haber mi istiyorsun? Bahsis (" + Para.yaz(plugin.getConfig().getDouble("npc.dedikodu-bahsis", 200))
                + ") birak, Shift + sag tikla.\"");
    }

    @EventHandler
    public void onHasar(EntityDamageEvent ev) {
        if (ev.getEntity() instanceof Villager v && v.getPersistentDataContainer().has(hanciKey, PersistentDataType.BYTE)) ev.setCancelled(true);
    }

    // ------------------------------------------------------------------ KALICILIK
    private void yukle() {
        try {
            if (dosya.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
                org.bukkit.configuration.ConfigurationSection hs = y.getConfigurationSection("hanlar");
                if (hs != null) for (String key : hs.getKeys(false)) {
                    Han h = new Han();
                    h.key = key; h.dunya = hs.getString(key + ".dunya");
                    h.vx = hs.getInt(key + ".vx"); h.vy = hs.getInt(key + ".vy"); h.vz = hs.getInt(key + ".vz");
                    String id = hs.getString(key + ".villager");
                    h.villagerId = id == null ? null : UUID.fromString(id);
                    h.yarim = hs.getBoolean(key + ".yarim", false);
                    if (h.yarim) plugin.getLogger().warning("[Hanci] Yarim kalmis han kurulumu var (" + key + ") - /npcklan hanci sil ile geri yukleyin.");
                    hanlar.put(key, h);
                }
            }
            veriYuklendi = true;
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "hanci.yml okunamadi - hanci sistemi KAPALI", ex);
        }
    }

    private synchronized void kaydet() {
        if (!veriYuklendi) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Han h : hanlar.values()) {
                String p = "hanlar." + h.key;
                y.set(p + ".dunya", h.dunya); y.set(p + ".vx", h.vx); y.set(p + ".vy", h.vy); y.set(p + ".vz", h.vz);
                y.set(p + ".villager", h.villagerId == null ? null : h.villagerId.toString()); y.set(p + ".yarim", h.yarim);
            }
            File gecici = new File(plugin.getDataFolder(), "hanci.yml.tmp");
            y.save(gecici);
            java.nio.file.Files.move(gecici.toPath(), dosya.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "hanci.yml yazilamadi", ex);
        }
    }
}
