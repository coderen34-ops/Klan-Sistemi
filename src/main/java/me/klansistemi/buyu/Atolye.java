package me.klansistemi.buyu;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;

/**
 * Atölye (/klan atolye): alanı yöneten klanın üyeleri klan katmanı büyülü eşya üretir.
 * Ücret klan kasasından, malzeme klan eşya kasasından düşer; üretilen eşya doğrudan eşya kasasına girer.
 * Klan geneli ve üye başı günlük kota vardır.
 */
public class Atolye implements Listener {

    public static class Sahip implements InventoryHolder {
        final UUID klanId;
        private Inventory envanter;
        Sahip(UUID klanId) { this.klanId = klanId; }
        @Override public Inventory getInventory() { return envanter; }
    }

    private final KlanSistemi plugin;
    private final NamespacedKey uretimKey;

    public Atolye(KlanSistemi plugin) {
        this.plugin = plugin;
        this.uretimKey = new NamespacedKey(plugin, "atolye_uretim"); // "ALAN:MALZEME"
    }

    private Mesaj m() { return plugin.mesaj(); }
    private int klanKota() { return plugin.getConfig().getInt("buyu.atolye.gunluk-klan-kota", 5); }
    private int uyeKota() { return plugin.getConfig().getInt("buyu.atolye.gunluk-uye-kota", 2); }

    private double ucret(String alan) { return plugin.getConfig().getDouble("buyu.alanlar." + alan + ".atolye.ucret", 25000); }

    /** Eşya türüne göre gereken malzemeler (eşya kasasından). */
    private Map<Material, Integer> malzemeler(String alan, Material esya) {
        Map<Material, Integer> sonuc = new LinkedHashMap<>();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("buyu.alanlar." + alan + ".atolye.malzemeler." + esya.name());
        if (c == null) return sonuc;
        for (String m : c.getKeys(false)) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) sonuc.put(mat, Math.max(1, c.getInt(m)));
        }
        return sonuc;
    }

    private int bugunKlan(Klan k) {
        return k.atolyeGun == LocalDate.now().toEpochDay() ? k.atolyeSayi : 0;
    }

    private int bugunUye(KlanUyesi u) {
        return u.atolyeGun == LocalDate.now().toEpochDay() ? u.atolyeSayi : 0;
    }

    // ------------------------------------------------------------------ MENÜ
    public void ac(Player p) {
        Klan k = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (k == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (k.uzmanliklar.isEmpty()) {
            m().gonder(p, "atolye-alan-yok", "&cKlanınız bir büyü alanı yönetmiyor. Lider: &e/klan uzmanlik al <alan>"); return;
        }
        KlanUyesi u = k.uyeler.get(p.getUniqueId());
        Sahip sahip = new Sahip(k.id);
        Inventory inv = Bukkit.createInventory(sahip, 54, m().metin("menu-atolye-baslik", "&6&l{klan} &8| &7Atölye", "klan", k.isim));
        sahip.envanter = inv;

        int slot = 0;
        for (String alanKod : k.uzmanliklar) {
            BuyuEsyasi.AlanTanimi alan = plugin.buyuEsyasi().alan(alanKod);
            if (alan == null || !alan.aktif) continue;
            for (Material mat : alan.esyalar) {
                if (slot >= 45) break;
                ItemStack ikon = new ItemStack(mat);
                for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> e : alan.klanSeviye.entrySet()) {
                    if (e.getKey().canEnchantItem(ikon)) ikon.addUnsafeEnchantment(e.getKey(), e.getValue());
                }
                ItemMeta meta = ikon.getItemMeta();
                meta.setDisplayName(alan.ad + ChatColor.GRAY + " - " + ChatColor.WHITE + mat.name());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Ücret: " + ChatColor.GOLD + Para.yaz(ucret(alanKod)) + ChatColor.GRAY + " (klan kasası)");
                Map<Material, Integer> gereken = malzemeler(alanKod, mat);
                if (!gereken.isEmpty()) {
                    lore.add(ChatColor.GRAY + "Malzeme (eşya kasasından):");
                    for (Map.Entry<Material, Integer> g : gereken.entrySet()) {
                        int var = plugin.esya().malzemeSay(k, g.getKey());
                        lore.add((var >= g.getValue() ? ChatColor.GREEN : ChatColor.RED) + "  " + g.getValue() + "x " + g.getKey().name()
                                + ChatColor.DARK_GRAY + " (kasada " + var + ")");
                    }
                }
                lore.add("");
                lore.add(ChatColor.GRAY + "Klan günlük hakkı: " + ChatColor.WHITE + (klanKota() - bugunKlan(k)) + "/" + klanKota());
                lore.add(ChatColor.GRAY + "Sizin günlük hakkınız: " + ChatColor.WHITE + (uyeKota() - bugunUye(u)) + "/" + uyeKota());
                lore.add("");
                lore.add(ChatColor.YELLOW + "► Üretmek için tıkla (eşya klan kasasına girer)");
                meta.setLore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                meta.getPersistentDataContainer().set(uretimKey, PersistentDataType.STRING, alanKod + ":" + mat.name());
                ikon.setItemMeta(meta);
                inv.setItem(slot++, ikon);
            }
        }
        ItemStack bilgi = new ItemStack(Material.CHEST);
        ItemMeta bm = bilgi.getItemMeta();
        bm.setDisplayName(ChatColor.GOLD + "Klan Kasası");
        bm.setLore(List.of(ChatColor.GRAY + "Para: " + ChatColor.GREEN + Para.yaz(k.kasa),
                ChatColor.GRAY + "Malzemeleri eşya kasasına koyun: " + ChatColor.WHITE + "/klan kasa"));
        bilgi.setItemMeta(bm);
        inv.setItem(49, bilgi);
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip sahip)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String veri = item.getItemMeta().getPersistentDataContainer().get(uretimKey, PersistentDataType.STRING);
        if (veri == null) return;
        String[] parca = veri.split(":");
        Material mat = Material.matchMaterial(parca[1]);
        Klan k = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (k == null || !k.id.equals(sahip.klanId)) { p.closeInventory(); return; }
        if (uret(p, k, parca[0], mat)) ac(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Sahip) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ ÜRETİM
    public boolean uret(Player p, Klan k, String alanKod, Material mat) {
        BuyuEsyasi.AlanTanimi alan = plugin.buyuEsyasi().alan(alanKod);
        KlanUyesi u = k.uyeler.get(p.getUniqueId());
        if (alan == null || !alan.aktif || mat == null || !alan.esyalar.contains(mat)) return false;
        if (!k.uzmanliklar.contains(alan.kod)) { m().gonder(p, "atolye-alan-yok", "&cKlanınız bir büyü alanı yönetmiyor. Lider: &e/klan uzmanlik al <alan>"); return false; }
        if (plugin.kasaKilitliMi(k)) { m().gonder(p, "savas-kilit", "&cKlanınız savaşta! Savaş bitene kadar kasa, ayrılma ve üye atma kilitlidir."); return false; }
        if (bugunKlan(k) >= klanKota()) { m().gonder(p, "atolye-klan-kota", "&cKlanınızın bugünkü üretim hakkı doldu ({max})." , "max", klanKota()); return false; }
        if (bugunUye(u) >= uyeKota()) { m().gonder(p, "atolye-uye-kota", "&cBugünkü üretim hakkınız doldu ({max}).", "max", uyeKota()); return false; }
        double ucret = ucret(alan.kod);
        if (k.kasa < ucret) { m().gonder(p, "atolye-para", "&cKlan kasasında yeterli para yok. Gereken: {ucret}", "ucret", Para.yaz(ucret)); return false; }
        Map<Material, Integer> gereken = malzemeler(alan.kod, mat);
        for (Map.Entry<Material, Integer> g : gereken.entrySet()) {
            if (plugin.esya().malzemeSay(k, g.getKey()) < g.getValue()) {
                m().gonder(p, "atolye-malzeme", "&cEşya kasasında yeterli malzeme yok: {adet}x {malzeme}", "adet", g.getValue(), "malzeme", g.getKey().name());
                return false;
            }
        }
        if (!plugin.esya().bosSlotVar(k)) { m().gonder(p, "buyu-kasa-dolu", "&c{klan} klanının eşya kasası dolu.", "klan", k.isim); return false; }

        // Ödeme: para ve malzeme klan kasasından
        k.kasa = Para.kurus(k.kasa - ucret);
        for (Map.Entry<Material, Integer> g : gereken.entrySet()) plugin.esya().malzemeDus(k, g.getKey(), g.getValue());

        long simdi = System.currentTimeMillis();
        KayitDefteri defter = plugin.defter();
        String kod = defter.yeniKod();
        UUID uuid = UUID.randomUUID();
        ItemStack yeni = plugin.buyuEsyasi().olustur(mat, alan, BuyuEsyasi.Katman.KLAN, k.id, k.isim, kod, uuid, simdi);
        plugin.esya().kasayaKoy(k, yeni);
        defter.ekle(new KayitDefteri.Kayit(kod, uuid, k.id, k.isim, k.id, alan.kod, BuyuEsyasi.Katman.KLAN, KayitDefteri.Durum.KASADA, simdi));

        long bugun = LocalDate.now().toEpochDay();
        if (k.atolyeGun != bugun) { k.atolyeGun = bugun; k.atolyeSayi = 0; }
        if (u.atolyeGun != bugun) { u.atolyeGun = bugun; u.atolyeSayi = 0; }
        k.atolyeSayi++;
        u.atolyeSayi++;
        plugin.veri().kaydet();

        plugin.log().yaz(k, p.getName(), "ATOLYE_URETIM", kod + " | " + alan.kod + " | " + mat.name() + " | " + Para.yaz(ucret) + " " + gereken);
        m().gonder(p, "atolye-uretildi", "&a{esya} üretildi ve klan kasasına kondu. &7(ID: {kod})", "esya", mat.name(), "kod", kod);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        return true;
    }
}
