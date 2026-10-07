package me.klansistemi.buyu;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.command.CommandSender;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.Zaman;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Büyü Ustası (ticari katman, dışarıya hizmet): alan yöneten klanlar elde tutulan eşyaya
 * TİCARİ seviyede büyü satar. Açmak için config'te buyu.ticari.disariya-hizmet: true olmalı.
 *
 *  - Lider fiyatı config'teki min-max aralığında belirler: /klan buyu fiyat <büyü> <miktar|sil>
 *  - Alıcı bankasından öder; ücretin %vergi kadarı sunucudan çıkar, kalanı klan kasasına girer
 *  - Satılan eşya kasa-bağlı değildir ama benzersiz ID, kaynak klan ve tarih taşır (kayıt defterinde TICARI)
 *  - Günlük klan satış kotası, alıcı başına günlük sınır, aynı IP / klan üyesi alımı için log ve admin uyarısı
 */
public class BuyuUstasi implements Listener {

    /** klanId null ise klan listesi, değilse o klanın büyü listesi. */
    public static class Sahip implements InventoryHolder {
        final UUID klanId;
        private Inventory envanter;
        Sahip(UUID klanId) { this.klanId = klanId; }
        @Override public Inventory getInventory() { return envanter; }
    }

    private static final int SLOT_GERI = 49;
    private static final int GECMIS_MAX = 100;

    private final KlanSistemi plugin;
    private final NamespacedKey menuKey;  // ikon: klan UUID'si ya da büyü adı
    private final NamespacedKey npcKey;

    public BuyuUstasi(KlanSistemi plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "buyu_ustasi_secim");
        this.npcKey = new NamespacedKey(plugin, "buyu_ustasi");
    }

    private Mesaj m() { return plugin.mesaj(); }
    private BuyuEsyasi esya() { return plugin.buyuEsyasi(); }

    private boolean hizmetAcik() { return plugin.getConfig().getBoolean("buyu.ticari.disariya-hizmet", false); }
    private double fiyatMin() { return plugin.getConfig().getDouble("buyu.ticari.fiyat-min", 5000); }
    private double fiyatMax() { return plugin.getConfig().getDouble("buyu.ticari.fiyat-max", 100000); }
    private double vergiYuzde() { return plugin.getConfig().getDouble("buyu.ticari.vergi-yuzde", 10); }
    private int klanKota() { return plugin.getConfig().getInt("buyu.ticari.gunluk-klan-satis-kota", 10); }
    private int aliciSinir() { return plugin.getConfig().getInt("buyu.ticari.gunluk-alici-sinir", 2); }

    private static String ad(Enchantment e) { return e.getKey().getKey(); }

    private static Enchantment buyuBul(String isim) {
        if (isim == null) return null;
        NamespacedKey key = NamespacedKey.fromString(isim.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENCHANTMENT.get(key);
    }

    private static long gun(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
    }

    private int bugunSatis(Klan k) {
        return k.satisGun == LocalDate.now().toEpochDay() ? k.satisSayi : 0;
    }

    /** Alıcının bu klandan bugün aldığı büyü sayısı (satış geçmişinden). */
    private int bugunAlici(Klan k, UUID alici) {
        long bugun = LocalDate.now().toEpochDay();
        int sayi = 0;
        for (Klan.SatisKaydi s : k.satislar) {
            if (gun(s.zaman) != bugun) break; // Liste en yeniden eskiye sıralı
            if (s.alici.equals(alici)) sayi++;
        }
        return sayi;
    }

    /** Klanın satabileceği büyüler: yönettiği aktif alanların ticari büyüleri (alan sırasına göre). */
    public List<Enchantment> satilabilir(Klan k) {
        List<Enchantment> sonuc = new ArrayList<>();
        for (String kod : k.uzmanliklar) {
            BuyuEsyasi.AlanTanimi alan = esya().alan(kod);
            if (alan == null || !alan.aktif) continue;
            for (Enchantment e : alan.ticariSeviye.keySet()) if (!sonuc.contains(e)) sonuc.add(e);
        }
        return sonuc;
    }

    /** Eşyaya bu büyüyü uygulayabilecek alan (klanın yönettiği, eşya türü alana ait). */
    private BuyuEsyasi.AlanTanimi alanBul(Klan k, Material mat, Enchantment e) {
        for (String kod : k.uzmanliklar) {
            BuyuEsyasi.AlanTanimi alan = esya().alan(kod);
            if (alan != null && alan.aktif && alan.esyalar.contains(mat) && alan.ticariSeviye.containsKey(e)) return alan;
        }
        return null;
    }

    /** Büyü Ustası'nda görünen klan: hizmet veren, fiyatı belirlenmiş en az bir büyüsü olan ve savaşta olmayan. */
    private boolean hizmetVeriyor(Klan k) {
        if (plugin.kasaKilitliMi(k)) return false;
        for (Enchantment e : satilabilir(k)) if (k.buyuFiyatlari.containsKey(ad(e))) return true;
        return false;
    }

    // ------------------------------------------------------------------ KOMUT (/klan buyu ...)
    public void komut(Player p, String[] args) {
        String alt = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (alt) {
            case "fiyat" -> fiyatAyarla(p, args.length > 2 ? args[2] : null, args.length > 3 ? args[3] : null);
            case "fiyatlar" -> fiyatlar(p);
            case "gecmis", "geçmiş" -> gecmis(p);
            default -> ac(p);
        }
    }

    private Klan liderKlani(Player p) {
        Klan k = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (k == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return null; }
        KlanUyesi u = k.uyeler.get(p.getUniqueId());
        if (u.rol != Rol.LIDER) { m().gonder(p, "buyu-fiyat-lider", "&cBüyü fiyatlarını sadece klan lideri belirleyebilir."); return null; }
        return k;
    }

    private void fiyatAyarla(Player p, String buyu, String miktarYazi) {
        Klan k = liderKlani(p);
        if (k == null) return;
        if (buyu == null || miktarYazi == null) {
            m().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/klan buyu fiyat <büyü> <miktar|sil>");
            return;
        }
        Enchantment e = buyuBul(buyu);
        if (e == null || !satilabilir(k).contains(e)) {
            m().gonder(p, "buyu-fiyat-gecersiz-buyu", "&cKlanınız bu büyüyü satamaz. Satılabilir: &e{liste}", "liste", satilabilirMetni(k));
            return;
        }
        if (miktarYazi.equalsIgnoreCase("sil")) {
            k.buyuFiyatlari.remove(ad(e));
            plugin.veri().kaydet();
            plugin.log().yaz(k, p.getName(), "BUYU_FIYAT", ad(e) + " -> satıştan kaldırıldı");
            m().gonder(p, "buyu-fiyat-silindi", "&e{buyu} artık satılmıyor.", "buyu", ad(e));
            return;
        }
        double miktar;
        try { miktar = Para.kurus(Double.parseDouble(miktarYazi)); } catch (NumberFormatException ex) { miktar = -1; }
        if (!Double.isFinite(miktar) || miktar < fiyatMin() || miktar > fiyatMax()) {
            m().gonder(p, "buyu-fiyat-aralik", "&cFiyat {min} ile {max} arasında olmalı.", "min", Para.yaz(fiyatMin()), "max", Para.yaz(fiyatMax()));
            return;
        }
        k.buyuFiyatlari.put(ad(e), miktar);
        plugin.veri().kaydet();
        plugin.log().yaz(k, p.getName(), "BUYU_FIYAT", ad(e) + " -> " + Para.yaz(miktar));
        m().gonder(p, "buyu-fiyat-ayarlandi", "&a{buyu} fiyatı {fiyat} olarak ayarlandı.", "buyu", ad(e), "fiyat", Para.yaz(miktar));
        if (!hizmetAcik()) m().gonder(p, "buyu-hizmet-kapali", "&cBüyü Ustası şu an hizmet vermiyor (sunucu ayarı).");
    }

    private String satilabilirMetni(Klan k) {
        List<String> adlar = new ArrayList<>();
        for (Enchantment e : satilabilir(k)) adlar.add(ad(e));
        return adlar.isEmpty() ? "-" : String.join(", ", adlar);
    }

    private void fiyatlar(Player p) {
        Klan k = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (k == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        List<Enchantment> liste = satilabilir(k);
        if (liste.isEmpty()) { m().gonder(p, "atolye-alan-yok", "&cKlanınız bir büyü alanı yönetmiyor. Lider: &e/klan uzmanlik al <alan>"); return; }
        m().gonder(p, "buyu-fiyatlar-baslik", "&6--- Büyü Fiyatları &7(bugün {satis}/{kota} satış) &6---", "satis", bugunSatis(k), "kota", klanKota());
        for (Enchantment e : liste) {
            Double f = k.buyuFiyatlari.get(ad(e));
            m().gonder(p, "buyu-fiyatlar-satir", "&e{buyu}&7: {fiyat}", "buyu", ad(e), "fiyat", f == null ? ChatColor.RED + "satılmıyor" : Para.yaz(f));
        }
    }

    private void gecmis(Player p) {
        Klan k = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (k == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (k.satislar.isEmpty()) { m().gonder(p, "buyu-gecmis-bos", "&7Henüz büyü satışı yok."); return; }
        m().gonder(p, "buyu-gecmis-baslik", "&6--- Son Büyü Satışları ---");
        for (int i = 0; i < Math.min(10, k.satislar.size()); i++) {
            Klan.SatisKaydi s = k.satislar.get(i);
            m().gonder(p, "buyu-gecmis-satir", "&8{tarih} &e{alici} &7- {buyu} {seviye} &a{fiyat} &8(ID: {kod})",
                    "tarih", Zaman.tarih(s.zaman), "alici", s.aliciAdi, "buyu", s.buyu, "seviye", s.seviye, "fiyat", Para.yaz(s.fiyat - s.vergi), "kod", s.esyaKodu);
        }
    }

    // ------------------------------------------------------------------ MENÜ
    /** Hizmet veren klanların listesi. */
    public void ac(Player p) {
        if (!hizmetAcik()) { m().gonder(p, "buyu-hizmet-kapali", "&cBüyü Ustası şu an hizmet vermiyor (sunucu ayarı)."); return; }
        Sahip sahip = new Sahip(null);
        Inventory inv = Bukkit.createInventory(sahip, 54, m().metin("menu-buyu-ustasi-baslik", "&5&lBüyü Ustası &8| &7Klan Seç"));
        sahip.envanter = inv;
        int slot = 0;
        for (Klan k : plugin.klanManager().klanlar()) {
            if (slot >= 45) break;
            if (!hizmetVeriyor(k)) continue;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Alanlar: " + plugin.uzmanlik().alanlarMetni(k));
            for (Enchantment e : satilabilir(k)) {
                Double f = k.buyuFiyatlari.get(ad(e));
                if (f != null) lore.add(ChatColor.YELLOW + "  " + ad(e) + ChatColor.GRAY + ": " + ChatColor.GOLD + Para.yaz(f));
            }
            lore.add(ChatColor.GRAY + "Bugünkü satış: " + ChatColor.WHITE + bugunSatis(k) + "/" + klanKota());
            lore.add("");
            lore.add(ChatColor.YELLOW + "► Büyüleri görmek için tıkla");
            inv.setItem(slot++, ikon(Material.ENCHANTING_TABLE, ChatColor.GOLD + k.isim, lore, k.id.toString()));
        }
        if (slot == 0) {
            inv.setItem(22, ikon(Material.BARRIER, ChatColor.RED + "Şu an büyü satan klan yok", List.of(), null));
        }
        p.openInventory(inv);
    }

    /** Seçilen klanın, eldeki eşyaya uygulanabilecek büyüleri. */
    private void klanMenusu(Player p, Klan k) {
        Sahip sahip = new Sahip(k.id);
        Inventory inv = Bukkit.createInventory(sahip, 54, m().metin("menu-buyu-klan-baslik", "&5&lBüyü Ustası &8| &6{klan}", "klan", k.isim));
        sahip.envanter = inv;
        ItemStack el = p.getInventory().getItemInMainHand();
        int slot = 0;
        for (Enchantment e : satilabilir(k)) {
            Double fiyat = k.buyuFiyatlari.get(ad(e));
            if (fiyat == null || slot >= 45) continue;
            List<String> lore = new ArrayList<>();
            String engel = engel(p, k, el, e);
            BuyuEsyasi.AlanTanimi alan = el.getType() == Material.AIR ? null : alanBul(k, el.getType(), e);
            int seviye = alan != null ? alan.ticariSeviye.get(e) : enYuksekSeviye(k, e);
            lore.add(ChatColor.GRAY + "Seviye: " + ChatColor.WHITE + seviye);
            lore.add(ChatColor.GRAY + "Fiyat: " + ChatColor.GOLD + Para.yaz(fiyat) + ChatColor.GRAY + " (bankanızdan)");
            lore.add("");
            if (engel == null) lore.add(ChatColor.GREEN + "► Elinizdeki eşyaya uygulamak için tıkla");
            else lore.add(ChatColor.RED + "✖ " + engel);
            inv.setItem(slot++, ikon(Material.ENCHANTED_BOOK, ChatColor.LIGHT_PURPLE + ad(e) + " " + seviye, lore, ad(e)));
        }
        inv.setItem(SLOT_GERI, ikon(Material.ARROW, ChatColor.YELLOW + "◄ Klan Listesi", List.of(), null));
        p.openInventory(inv);
    }

    private int enYuksekSeviye(Klan k, Enchantment e) {
        int max = 0;
        for (String kod : k.uzmanliklar) {
            BuyuEsyasi.AlanTanimi alan = esya().alan(kod);
            if (alan != null && alan.aktif && alan.ticariSeviye.containsKey(e)) max = Math.max(max, alan.ticariSeviye.get(e));
        }
        return max;
    }

    private ItemStack ikon(Material mat, String isim, List<String> lore, String veri) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(isim);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        if (veri != null) meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, veri);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Sahip sahip)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (sahip.klanId != null && slot == SLOT_GERI) { ac(p); return; }
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String veri = item.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
        if (veri == null) return;
        if (sahip.klanId == null) {
            Klan k = plugin.klanManager().klanGetir(UUID.fromString(veri));
            if (k == null || !hizmetVeriyor(k)) { ac(p); return; }
            klanMenusu(p, k);
        } else {
            Klan k = plugin.klanManager().klanGetir(sahip.klanId);
            if (k == null) { ac(p); return; }
            satinAl(p, k, buyuBul(veri));
            if (p.isOnline()) klanMenusu(p, k);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Sahip) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ SATIŞ
    /** Satışa engel varsa sebebi, yoksa null. */
    private String engel(Player p, Klan k, ItemStack el, Enchantment e) {
        if (!hizmetAcik()) return "Hizmet kapalı";
        if (plugin.kasaKilitliMi(k)) return "Klan şu an savaşta";
        if (el == null || el.getType() == Material.AIR) return "Elinizde eşya yok";
        if (el.getAmount() != 1) return "Tek eşya tutmalısınız";
        if (alanBul(k, el.getType(), e) == null || !e.canEnchantItem(new ItemStack(el.getType()))) return "Bu eşyaya uygulanamaz";
        BuyuEsyasi.Katman katman = esya().katman(el);
        if (katman == BuyuEsyasi.Katman.KLAN) return "Klan eşyalarına satış yapılmaz";
        if (katman == BuyuEsyasi.Katman.TICARI && !k.id.equals(esya().kaynakKlan(el))) return "Başka klanın büyüsünü taşıyor";
        if (katman == null && esya().buyuluMu(el)) return "Geçersiz eşya";
        BuyuEsyasi.AlanTanimi alan = alanBul(k, el.getType(), e);
        int seviye = alan.ticariSeviye.get(e);
        if (el.getEnchantmentLevel(e) >= seviye) return "Bu büyü zaten bu seviyede";
        for (Enchantment var : el.getEnchantments().keySet()) {
            // Alanın kendi listesindeki büyüler birlikte basılabilir (örn. zırhta Protection + Fire Protection)
            if (!var.equals(e) && var.conflictsWith(e) && !alan.ticariSeviye.containsKey(var)) return ad(var) + " ile birlikte olamaz";
        }
        if (bugunSatis(k) >= klanKota()) return "Klanın bugünkü satış kotası doldu";
        if (bugunAlici(k, p.getUniqueId()) >= aliciSinir()) return "Bu klandan bugünkü alım sınırınız doldu";
        return null;
    }

    public boolean satinAl(Player p, Klan k, Enchantment e) {
        if (e == null) return false;
        Double fiyat = k.buyuFiyatlari.get(ad(e));
        if (fiyat == null || !satilabilir(k).contains(e)) { m().gonder(p, "buyu-satista-degil", "&cBu büyü artık satılmıyor."); return false; }
        ItemStack el = p.getInventory().getItemInMainHand();
        String engel = engel(p, k, el, e);
        if (engel != null) { m().gonder(p, "buyu-satin-alinamaz", "&cSatın alınamaz: {sebep}", "sebep", engel); return false; }
        if (!plugin.meslek().bankadanCek(p.getUniqueId(), fiyat)) {
            m().gonder(p, "buyu-para-yok", "&cBanka hesabınızda yeterli para yok. Gereken: {fiyat}", "fiyat", Para.yaz(fiyat));
            return false;
        }
        double vergi = Para.kurus(fiyat * vergiYuzde() / 100.0);
        k.kasa = Para.kurus(k.kasa + fiyat - vergi);

        BuyuEsyasi.AlanTanimi alan = alanBul(k, el.getType(), e);
        int seviye = alan.ticariSeviye.get(e);
        el.addUnsafeEnchantment(e, seviye);
        long simdi = System.currentTimeMillis();
        KayitDefteri defter = plugin.defter();
        String kod = esya().kisaKod(el);
        if (kod == null) {
            // İlk ticari büyü: eşyaya kimlik verilir ve kayıt defterine işlenir
            kod = defter.yeniKod();
            UUID uuid = UUID.randomUUID();
            esya().kimlikYaz(el, alan.kod, BuyuEsyasi.Katman.TICARI, k.id, k.isim, kod, uuid, simdi);
            KayitDefteri.Kayit kayit = new KayitDefteri.Kayit(kod, uuid, k.id, k.isim, k.id, alan.kod, BuyuEsyasi.Katman.TICARI, KayitDefteri.Durum.TICARI, simdi);
            kayit.malzeme = el.getType().name();
            kayit.not = "Satıldı: " + p.getName();
            defter.ekle(kayit);
        } else {
            esya().loreYenile(el);
            KayitDefteri.Kayit kayit = defter.get(kod);
            if (kayit != null) defter.durumAyarla(kayit, KayitDefteri.Durum.TICARI, null, "Büyü eklendi: " + ad(e) + " " + seviye + " (" + p.getName() + ")");
        }
        p.getInventory().setItemInMainHand(el);

        long bugun = LocalDate.now().toEpochDay();
        if (k.satisGun != bugun) { k.satisGun = bugun; k.satisSayi = 0; }
        k.satisSayi++;
        k.satislar.add(0, new Klan.SatisKaydi(simdi, p.getUniqueId(), p.getName(), ad(e), seviye, kod, fiyat, vergi));
        while (k.satislar.size() > GECMIS_MAX) k.satislar.remove(k.satislar.size() - 1);
        plugin.veri().kaydet();

        plugin.log().yaz(k, p.getName(), "BUYU_SATIS", kod + " | " + ad(e) + " " + seviye + " | " + Para.yaz(fiyat) + " (vergi " + Para.yaz(vergi) + ")");
        m().gonder(p, "buyu-satin-alindi", "&a{buyu} {seviye} eşyanıza basıldı. &7({fiyat}, ID: {kod})", "buyu", ad(e), "seviye", seviye, "fiyat", Para.yaz(fiyat), "kod", kod);
        plugin.klanManager().klanaGonder(k, "buyu-satis-klana", "&6{oyuncu} klanımızdan {buyu} {seviye} aldı. Kasaya {miktar} girdi.",
                "oyuncu", p.getName(), "buyu", ad(e), "seviye", seviye, "miktar", Para.yaz(fiyat - vergi));
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.0f);
        supheliKontrol(p, k, kod);
        return true;
    }

    /** Klan üyesinin kendi klanından alması ya da satıcı klanla aynı IP'den alım: log ve admin uyarısı. */
    private void supheliKontrol(Player alici, Klan k, String kod) {
        String sebep = null;
        if (k.uyeler.containsKey(alici.getUniqueId())) {
            sebep = "klan üyesi kendi klanından aldı";
        } else if (plugin.getConfig().getBoolean("buyu.ticari.ayni-ip-uyari", true)) {
            String ip = ip(alici);
            if (ip != null) {
                for (KlanUyesi u : k.uyeler.values()) {
                    Player uye = Bukkit.getPlayer(u.uuid);
                    if (uye != null && ip.equals(ip(uye))) { sebep = "aynı IP: " + uye.getName(); break; }
                }
            }
        }
        if (sebep == null) return;
        plugin.log().yaz(k, alici.getName(), "BUYU_SATIS_SUPHELI", kod + " | " + sebep);
        plugin.getLogger().warning("[Büyü] Şüpheli satış: " + alici.getName() + " <- " + k.isim + " (" + kod + "): " + sebep);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("klan.admin")) {
                m().gonder(o, "buyu-supheli-satis", "&c[Büyü] Şüpheli satış: &e{oyuncu} &7<- &6{klan} &7(ID: {kod}): {sebep}",
                        "oyuncu", alici.getName(), "klan", k.isim, "kod", kod, "sebep", sebep);
            }
        }
    }

    private static String ip(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    // ------------------------------------------------------------------ NPC (/klan admin buyu npc kur|sil)
    public void npcKomut(CommandSender s, String islem) {
        if (!(s instanceof Player p)) { m().gonder(s, "sadece-oyuncu", "&cBu komut sadece oyun içinden kullanılabilir."); return; }
        if ("sil".equalsIgnoreCase(islem)) {
            int silinen = 0;
            for (Entity en : p.getNearbyEntities(5, 5, 5)) {
                if (en.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) { en.remove(); silinen++; }
            }
            m().gonder(p, "buyu-npc-silindi", "&e{sayi} Büyü Ustası NPC'si silindi.", "sayi", silinen);
            return;
        }
        Villager v = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setProfession(Villager.Profession.LIBRARIAN);
        v.setCustomName(m().metin("buyu-npc-isim", "&5&lBüyü Ustası"));
        v.setCustomNameVisible(true);
        v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        m().gonder(p, "buyu-npc-kuruldu", "&aBüyü Ustası NPC'si kuruldu.");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onNpc(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        ac(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcHasar(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ PANEL (Diplomasi Paneli sekmesi)
    public List<ItemStack> satisIkonlari(Klan k) {
        List<ItemStack> sonuc = new ArrayList<>();
        for (Klan.SatisKaydi s : k.satislar) {
            sonuc.add(ikon(Material.ENCHANTED_BOOK, ChatColor.YELLOW + s.aliciAdi + ChatColor.GRAY + " - " + ChatColor.LIGHT_PURPLE + s.buyu + " " + s.seviye,
                    List.of(ChatColor.GRAY + "Tarih: " + ChatColor.WHITE + Zaman.tarih(s.zaman),
                            ChatColor.GRAY + "Fiyat: " + ChatColor.GOLD + Para.yaz(s.fiyat),
                            ChatColor.GRAY + "Vergi: " + ChatColor.RED + Para.yaz(s.vergi),
                            ChatColor.GRAY + "Kasaya giren: " + ChatColor.GREEN + Para.yaz(s.fiyat - s.vergi),
                            ChatColor.GRAY + "Eşya ID: " + ChatColor.WHITE + s.esyaKodu), null));
        }
        return sonuc;
    }

    /** Admin için alan haritası (tab tamamlama): satılabilir tüm büyü adları. */
    public List<String> tumBuyuAdlari() {
        List<String> sonuc = new ArrayList<>();
        for (BuyuEsyasi.AlanTanimi alan : esya().alanlar().values()) {
            for (Map.Entry<Enchantment, Integer> e : alan.ticariSeviye.entrySet()) if (!sonuc.contains(ad(e.getKey()))) sonuc.add(ad(e.getKey()));
        }
        return sonuc;
    }
}
