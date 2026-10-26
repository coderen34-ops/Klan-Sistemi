package me.klansistemi.bolge;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;
import me.klansistemi.savas.Savas;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Klan Bölgesi / chunk-bazlı claim sistemi (Entry #4 / Faz 3).
 *
 * Tasarım (kullanıcıyla konuşulan): pos1/pos2 tarzı serbest alan işaretlemenin yerine
 * Towny/Factions tarzı chunk-bazlı claim. Özel bir eşya (Nether Yıldızı, nadir kullanılan
 * bir item - /klan bolge alet ile alınır) ile bulunduğun chunk, klanının adına claim edilir.
 * Chunk maliyeti sabit $100.000, klan kasasından düşülür. Bir klan en fazla 4 chunk claim
 * edebilir (şimdilik sabit limit, ileride ayarlanabilir). Klan üyesi olmayan biri, klanı
 * sahip klana karşı AKTİF savaş açmadığı sürece claim edilmiş bir chunk'ta blok
 * kıramaz/koyamaz - bu kontrol SavasManager'ın mevcut aktif-savaş durumunu kullanıyor
 * (yeni bir bayrak icat edilmedi).
 *
 * Veri: GuvenliBolgeManager ile aynı desen - kendi yml dosyası (klan-bolgeleri.yml),
 * dünyası henüz yüklü olmayan kayıtlar atlanmadan ham olarak saklanır ki silinmesin.
 */
public class BolgeManager implements Listener {

    public static final double CHUNK_MALIYETI = 100000.0;
    public static final int CHUNK_LIMITI = 4; // şimdilik sabit (ileride ayarlanabilir)

    private static final class ChunkAnahtari {
        final String dunya;
        final int x, z;
        ChunkAnahtari(String dunya, int x, int z) { this.dunya = dunya; this.x = x; this.z = z; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ChunkAnahtari)) return false;
            ChunkAnahtari k = (ChunkAnahtari) o;
            return x == k.x && z == k.z && dunya.equals(k.dunya);
        }
        @Override public int hashCode() { return (dunya.hashCode() * 31 + x) * 31 + z; }
        @Override public String toString() { return dunya + ":" + x + ":" + z; }
        static ChunkAnahtari of(Chunk c) { return new ChunkAnahtari(c.getWorld().getName(), c.getX(), c.getZ()); }
    }

    private final KlanSistemi plugin;
    private final NamespacedKey aletKey;
    private final File dosya;
    private final Map<ChunkAnahtari, UUID> claimler = new LinkedHashMap<>();

    public BolgeManager(KlanSistemi plugin) {
        this.plugin = plugin;
        this.aletKey = new NamespacedKey(plugin, "klan_bolge_aleti");
        this.dosya = new File(plugin.getDataFolder(), "klan-bolgeleri.yml");
        yukle();
    }

    private Mesaj m() { return plugin.mesaj(); }

    /** (x,z) etrafındaki yarıçap içinde herhangi bir klan bölgesi chunk'ı var mı (yol/han/kervan korumaları için). */
    public synchronized boolean claimVarMi(World w, int x, int z, int yaricap) {
        if (claimler.isEmpty()) return false;
        for (int cx = (x - yaricap) >> 4; cx <= (x + yaricap) >> 4; cx++)
            for (int cz = (z - yaricap) >> 4; cz <= (z + yaricap) >> 4; cz++)
                if (claimler.containsKey(new ChunkAnahtari(w.getName(), cx, cz))) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Komutlar: /klan bolge <alet|bilgi|liste>
    // ------------------------------------------------------------------
    public void komut(Player p, String[] args) {
        String islem = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "bilgi";
        switch (islem) {
            case "alet" -> aletVer(p);
            case "liste" -> liste(p);
            default -> bilgi(p);
        }
    }

    private void aletVer(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        KlanUyesi u = plugin.klanManager().uye(p.getUniqueId());
        if (u == null || u.rol == Rol.UYE) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok (sadece Lider/Yardımcı)."); return; }

        ItemStack alet = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = alet.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Klan Bölge Aleti");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Bulunduğun chunk'ı klanına claim etmek için");
        lore.add(ChatColor.GRAY + "sağ tıkla. Maliyet: " + ChatColor.YELLOW + Para.yaz(CHUNK_MALIYETI) + ChatColor.GRAY + " (klan kasasından).");
        lore.add(ChatColor.DARK_GRAY + "Harcanmaz, tekrar tekrar kullanılabilir.");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(aletKey, PersistentDataType.BYTE, (byte) 1);
        alet.setItemMeta(meta);

        if (p.getInventory().firstEmpty() == -1) { m().gonder(p, "envanter-dolu", "&cEnvanterin dolu!"); return; }
        p.getInventory().addItem(alet);
        m().gonder(p, "bolge-alet-verildi", "&aKlan Bölge Aleti verildi. Claim etmek istediğin chunk'ta sağ tıkla.");
    }

    private void bilgi(Player p) {
        Chunk c = p.getLocation().getChunk();
        UUID sahipId = claimler.get(ChunkAnahtari.of(c));
        if (sahipId == null) { m().gonder(p, "bolge-bilgi-bos", "&7Bulunduğun chunk kimseye ait değil."); return; }
        Klan sahip = plugin.klanManager().klanGetir(sahipId);
        String ad = sahip != null ? sahip.isim : "(silinmiş klan)";
        m().gonder(p, "bolge-bilgi", "&7Bulunduğun chunk &e{klan} &7klanına ait.", "klan", ad);
    }

    private void liste(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        List<ChunkAnahtari> kendi = new ArrayList<>();
        for (Map.Entry<ChunkAnahtari, UUID> e : claimler.entrySet()) if (e.getValue().equals(a.id)) kendi.add(e.getKey());
        p.sendMessage(Mesaj.renk("&6&l--- " + a.isim + " Klan Bölgeleri (" + kendi.size() + "/" + CHUNK_LIMITI + ") ---"));
        if (kendi.isEmpty()) { p.sendMessage(Mesaj.renk("&7Henüz hiç chunk claim edilmemiş. &e/klan bolge alet")); return; }
        for (ChunkAnahtari k : kendi) {
            p.sendMessage(Mesaj.renk("&e" + k.dunya + " &7chunk (" + k.x + ", " + k.z + ") &8- &7blok: &f" + (k.x * 16) + ",~ " + (k.z * 16) + ",~"));
        }
    }

    // ------------------------------------------------------------------
    // Claim etme: alet ile sağ tık
    // ------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onAletKullan(PlayerInteractEvent event) {
        ItemStack elindeki = event.getItem();
        if (elindeki == null || elindeki.getType() != Material.NETHER_STAR || !elindeki.hasItemMeta()) return;
        if (!elindeki.getItemMeta().getPersistentDataContainer().has(aletKey, PersistentDataType.BYTE)) return;
        if (!event.getAction().isRightClick()) return;
        event.setCancelled(true);

        Player p = event.getPlayer();
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        KlanUyesi u = plugin.klanManager().uye(p.getUniqueId());
        if (u == null || u.rol == Rol.UYE) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok (sadece Lider/Yardımcı)."); return; }

        Chunk c = p.getLocation().getChunk();
        ChunkAnahtari anahtar = ChunkAnahtari.of(c);
        UUID mevcutSahip = claimler.get(anahtar);
        if (mevcutSahip != null) {
            if (mevcutSahip.equals(a.id)) { m().gonder(p, "bolge-zaten-senin", "&eBu chunk zaten klanına ait."); return; }
            Klan sahipKlan = plugin.klanManager().klanGetir(mevcutSahip);
            m().gonder(p, "bolge-dolu", "&cBu chunk zaten &e{klan} &cklanına ait.", "klan", sahipKlan != null ? sahipKlan.isim : "başka bir klan");
            return;
        }

        int mevcutSayi = 0;
        for (UUID sahip : claimler.values()) if (sahip.equals(a.id)) mevcutSayi++;
        if (mevcutSayi >= CHUNK_LIMITI) {
            m().gonder(p, "bolge-limit", "&cKlanın zaten en fazla &e{limit} &cchunk claim edebilir.", "limit", CHUNK_LIMITI);
            return;
        }

        if (a.kasa < CHUNK_MALIYETI) {
            m().gonder(p, "bolge-bakiye", "&cKlan kasasında yeterli bakiye yok! Gereken: &e{miktar} &c(kasa: {kasa})",
                    "miktar", Para.yaz(CHUNK_MALIYETI), "kasa", Para.yaz(a.kasa));
            return;
        }

        a.kasa = Para.kurus(a.kasa - CHUNK_MALIYETI);
        claimler.put(anahtar, a.id);
        kaydet();
        plugin.veri().kaydet();

        plugin.log().yaz(a, p.getName(), "BOLGE_CLAIM", anahtar.dunya + " chunk (" + anahtar.x + "," + anahtar.z + ") - " + Para.yaz(CHUNK_MALIYETI));

        m().gonder(p, "bolge-claim-edildi", "&aChunk klanına claim edildi! &7(Kasadan {miktar} düşüldü, kasa: {kasa})",
                "miktar", Para.yaz(CHUNK_MALIYETI), "kasa", Para.yaz(a.kasa));
        try {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Koruma: klan üyesi olmayan, sahip klana karşı aktif savaşı olmayan biri
    // claim edilmiş bir chunk'ta blok kıramaz/koyamaz.
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (korumaIhlali(event.getPlayer(), event.getBlock().getChunk())) {
            event.setCancelled(true);
            m().gonder(event.getPlayer(), "bolge-korunuyor", "&cBurası başka bir klanın bölgesi! Blok kırma/koyma korumalıdır (savaşta bile).");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (korumaIhlali(event.getPlayer(), event.getBlock().getChunk())) {
            event.setCancelled(true);
            m().gonder(event.getPlayer(), "bolge-korunuyor", "&cBurası başka bir klanın bölgesi! Blok kırma/koyma korumalıdır (savaşta bile).");
        }
    }

    /** true = bu oyuncunun bu chunk'ta blok kırma/koyma işlemi engellenmeli. */
    private boolean korumaIhlali(Player p, Chunk c) {
        if (p.hasPermission("klan.bolge.bypass")) return false;
        UUID sahipId = claimler.get(ChunkAnahtari.of(c));
        if (sahipId == null) return false; // claim edilmemiş chunk - serbest

        Klan oyuncuKlani = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (oyuncuKlani != null && oyuncuKlani.id.equals(sahipId)) return false; // kendi klanının bölgesi

        // Karar (Faz 4): savaşta PvP, eşya çalma ve kasa açma serbest ama blok kırma/koyma korumalı kalır
        // (yapılara zarar verilemez). Bu yüzden aktif savaş artık blok korumasını KALDIRMAZ.
        return true;
    }

    /** Claim aleti (özel etiketli Nether Yıldızı) HİÇBİR tarifte (fener dahil) malzeme olarak kullanılamaz; etiketsiz Nether Yıldızı etkilenmez. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAletTarifi(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        for (ItemStack it : event.getInventory().getMatrix()) {
            if (aletMi(it)) { event.getInventory().setResult(null); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAletDemirci(org.bukkit.event.inventory.PrepareSmithingEvent event) {
        for (ItemStack it : event.getInventory().getContents()) {
            if (aletMi(it)) { event.setResult(null); return; }
        }
    }

    private boolean aletMi(ItemStack it) {
        return it != null && it.getType() == Material.NETHER_STAR && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(aletKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    // Kalıcılık (GuvenliBolgeManager ile aynı desen)
    // ------------------------------------------------------------------
    private void kaydet() {
        // claimler.<world>:<x>:<z>: <klanUUID> - liste olarak tutuluyor (bkz. yukle()) ki
        // ConfigurationSection anahtar olarak ':' karakterini desteklemediği ortamlarda sorun olmasın.
        List<Map<String, Object>> liste = new ArrayList<>();
        for (Map.Entry<ChunkAnahtari, UUID> e : claimler.entrySet()) {
            Map<String, Object> satir = new LinkedHashMap<>();
            satir.put("dunya", e.getKey().dunya);
            satir.put("x", e.getKey().x);
            satir.put("z", e.getKey().z);
            satir.put("klan", e.getValue().toString());
            liste.add(satir);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("claimler", liste);
        try {
            y.save(dosya);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "klan-bolgeleri.yml kaydedilemedi", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void yukle() {
        claimler.clear();
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        List<?> liste = y.getList("claimler");
        if (liste == null) return;
        for (Object o : liste) {
            try {
                Map<String, Object> satir = (Map<String, Object>) o;
                String dunya = String.valueOf(satir.get("dunya"));
                int x = Integer.parseInt(String.valueOf(satir.get("x")));
                int z = Integer.parseInt(String.valueOf(satir.get("z")));
                UUID sahip = UUID.fromString(String.valueOf(satir.get("klan")));
                claimler.put(new ChunkAnahtari(dunya, x, z), sahip);
            } catch (Exception e) {
                plugin.getLogger().warning("[Bölge] bir chunk claim kaydı okunamadı, atlandı: " + e.getMessage());
            }
        }
    }
}
