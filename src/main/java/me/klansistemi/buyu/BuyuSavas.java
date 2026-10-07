package me.klansistemi.buyu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import me.klansistemi.EsyaKasasi;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;

/**
 * Büyü sisteminin savaşla ilgili kısmı:
 *  - Savaş öncesi: kasa kilitlenirken iki klanın çevrimiçi üyelerindeki kasa-bağlı eşyalar kasaya döner
 *  - Ganimet: kazanan, kaybedenin klan katmanı eşyalarının %min-%max'ını alır (kayıt defterinden; kasadaki
 *    ve ödünçteki dahil). Ticari eşyalar ganimete girmez.
 *  - Arena dengesi: arenadaki oyuncuların eşyalarında vanilla üst sınırını aşan büyüler geçici olarak
 *    sınıra indirilir; asıl seviyeler eşyanın içine yazılır, savaştan sonra (çökmede girişte) geri yüklenir.
 */
public class BuyuSavas {

    private final KlanSistemi plugin;
    private final NamespacedKey orijinalKey;

    public BuyuSavas(KlanSistemi plugin) {
        this.plugin = plugin;
        this.orijinalKey = new NamespacedKey(plugin, "arena_orijinal_buyu");
    }

    private Mesaj m() { return plugin.mesaj(); }
    private BuyuEsyasi esya() { return plugin.buyuEsyasi(); }
    private KayitDefteri defter() { return plugin.defter(); }

    private double ganimetMin() { return plugin.getConfig().getDouble("savas.buyu-ganimet-min", 25); }
    private double ganimetMax() { return plugin.getConfig().getDouble("savas.buyu-ganimet-max", 50); }

    // ------------------------------------------------------------------ SAVAŞ ÖNCESİ
    /** Kasa kilitlenirken: klanın çevrimiçi üyelerindeki kasa-bağlı eşyalar kasaya döner. */
    public void savasOncesiTopla(Klan k) {
        int sayi = 0;
        for (KlanUyesi u : k.uyeler.values()) {
            Player p = Bukkit.getPlayer(u.uuid);
            if (p == null) continue; // Çevrimdışı olanlar girişte (klan savaştaysa) iade edilir
            sayi += oyuncudanTopla(p, k, "Savaş başlıyor");
        }
        if (sayi > 0) {
            plugin.log().yaz(k, "-", "BUYU_SAVAS_ONCESI_IADE", sayi + " eşya kasaya döndü");
            plugin.klanManager().klanaGonder(k, "buyu-savas-oncesi", "&eSavaş öncesi üyelerdeki {sayi} klan eşyası kasaya geri alındı.", "sayi", sayi);
        }
    }

    private int oyuncudanTopla(Player p, Klan k, String sebep) {
        int sayi = 0;
        ItemStack[] icerik = p.getInventory().getContents();
        for (int i = 0; i < icerik.length; i++) {
            ItemStack item = icerik[i];
            if (!esya().kasaBagliMi(item)) continue;
            KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(item));
            if (kayit == null || !k.id.equals(kayit.sahipKlan)) continue;
            p.getInventory().setItem(i, null);
            plugin.buyuKurallari().iadeEt(kayit, item, sebep);
            sayi++;
        }
        if (esya().kasaBagliMi(p.getItemOnCursor())) {
            KayitDefteri.Kayit kayit = defter().get(esya().kisaKod(p.getItemOnCursor()));
            if (kayit != null && k.id.equals(kayit.sahipKlan)) {
                ItemStack item = p.getItemOnCursor();
                p.setItemOnCursor(null);
                plugin.buyuKurallari().iadeEt(kayit, item, sebep);
                sayi++;
            }
        }
        return sayi;
    }

    // ------------------------------------------------------------------ GANİMET
    /**
     * Kaybedenin klan katmanı eşyalarından puan farkına göre %min-%max kadarını kazanana aktarır.
     * @return aktarılan eşya sayısı
     */
    public int ganimetAktar(Klan kaybeden, Klan kazanan, double farkOrani) {
        List<KayitDefteri.Kayit> adaylar = new ArrayList<>();
        for (KayitDefteri.Kayit k : defter().klanKayitlari(kaybeden.id)) {
            if (k.katman == BuyuEsyasi.Katman.KLAN && (k.durum == KayitDefteri.Durum.KASADA || k.durum == KayitDefteri.Durum.ODUNC)) adaylar.add(k);
        }
        if (adaylar.isEmpty()) return 0;
        double oran = ganimetMin() + (ganimetMax() - ganimetMin()) * Math.max(0, Math.min(1, farkOrani));
        int adet = (int) Math.round(adaylar.size() * oran / 100.0);
        if (adet <= 0) return 0;
        Collections.shuffle(adaylar);

        int aktarilan = 0;
        for (int i = 0; i < adet; i++) {
            if (aktar(adaylar.get(i), kaybeden, kazanan)) aktarilan++;
        }
        if (aktarilan > 0) {
            plugin.log().yaz(kazanan, "-", "BUYU_GANIMET", kaybeden.isim + " -> " + aktarilan + " büyülü eşya (%" + Math.round(oran) + ")");
            plugin.klanManager().klanaGonder(kazanan, "buyu-ganimet-kazanan", "&6{sayi} büyülü eşya ele geçirildi ve klan kasasına kondu!", "sayi", aktarilan);
            plugin.klanManager().klanaGonder(kaybeden, "buyu-ganimet-kaybeden", "&c{sayi} büyülü eşyanız {klan} klanına geçti.", "sayi", aktarilan, "klan", kazanan.isim);
        }
        return aktarilan;
    }

    private boolean aktar(KayitDefteri.Kayit kayit, Klan kaybeden, Klan kazanan) {
        ItemStack fiziksel = null;
        if (kayit.durum == KayitDefteri.Durum.KASADA) {
            fiziksel = kasadanCikar(kaybeden, kayit.kod);
        } else if (kayit.oduncOyuncu != null) {
            Player p = Bukkit.getPlayer(kayit.oduncOyuncu);
            if (p != null) {
                fiziksel = oyuncudanCikar(p, kayit.kod);
                if (fiziksel != null) m().gonder(p, "buyu-ganimet-alindi", "&c{kod} kimlikli klan eşyası savaş ganimeti olarak alındı.", "kod", kayit.kod);
            }
        }
        if (fiziksel == null) {
            // Eşya çevrimdışı bir üyede (ya da bulunamadı): kazanan için yeni kimlikle yeniden üretilir.
            // Eski kopyanın kimliği artık kayıtla uyuşmadığı için oyuncu girince geçersiz sayılıp kaldırılır.
            fiziksel = yenidenUret(kayit);
            if (fiziksel == null) {
                defter().durumAyarla(kayit, KayitDefteri.Durum.SILINDI, null, "Ganimette yeniden üretilemedi");
                return false;
            }
        }
        kayit.sahipKlan = kazanan.id;
        ItemMeta meta = fiziksel.getItemMeta();
        meta.getPersistentDataContainer().set(esya().eleGecirenKey, PersistentDataType.STRING, kazanan.isim);
        fiziksel.setItemMeta(meta);
        esya().loreYenile(fiziksel);

        ItemStack kalan = plugin.esya().kasayaKoy(kazanan, fiziksel);
        if (kalan == null) {
            defter().durumAyarla(kayit, KayitDefteri.Durum.KASADA, null, "Ganimet: " + kaybeden.isim + " -> " + kazanan.isim);
        } else {
            KlanUyesi lider = kazanan.lider();
            plugin.esya().bekleyenEkle(lider.uuid, kalan);
            defter().durumAyarla(kayit, KayitDefteri.Durum.ODUNC, lider.uuid, "Ganimet (kasa dolu, liderin bekleyen eşyalarına)");
        }
        return true;
    }

    private ItemStack kasadanCikar(Klan k, String kod) {
        for (Inventory inv : plugin.esya().kasaSayfalari(k)) {
            for (int i = 0; i < EsyaKasasi.DEPO_SLOT; i++) {
                ItemStack item = inv.getItem(i);
                if (kod.equals(esya().kisaKod(item))) {
                    inv.setItem(i, null);
                    return item;
                }
            }
        }
        return null;
    }

    private ItemStack oyuncudanCikar(Player p, String kod) {
        ItemStack[] icerik = p.getInventory().getContents();
        for (int i = 0; i < icerik.length; i++) {
            if (kod.equals(esya().kisaKod(icerik[i]))) {
                ItemStack item = icerik[i];
                p.getInventory().setItem(i, null);
                return item;
            }
        }
        if (kod.equals(esya().kisaKod(p.getItemOnCursor()))) {
            ItemStack item = p.getItemOnCursor();
            p.setItemOnCursor(null);
            return item;
        }
        return null;
    }

    /** Kayıttaki bilgilerden eşyayı yeni bir kimlik (UUID) ile yeniden üretir; kısa kod aynı kalır. */
    private ItemStack yenidenUret(KayitDefteri.Kayit kayit) {
        BuyuEsyasi.AlanTanimi alan = esya().alan(kayit.alan);
        Material mat = kayit.malzeme == null ? null : Material.matchMaterial(kayit.malzeme);
        if (alan == null || mat == null) return null;
        kayit.uuid = UUID.randomUUID();
        return esya().olustur(mat, alan, kayit.katman, kayit.kaynakKlan, kayit.kaynakKlanAdi, kayit.kod, kayit.uuid, kayit.uretim);
    }

    // ------------------------------------------------------------------ ARENA DENGESİ
    /** Arenaya giren oyuncunun eşyalarında vanilla üst sınırını aşan büyüleri sınıra indirir. */
    public void arenaSeviyeDusur(Player p) {
        boolean degisti = false;
        for (ItemStack item : p.getInventory().getContents()) degisti |= dusur(item);
        if (degisti) {
            p.updateInventory();
            m().gonder(p, "buyu-arena-dusuruldu", "&7Arena dengesi: eşyalarınızdaki üst seviye büyüler savaş boyunca vanilla sınırına indirildi.");
        }
    }

    private boolean dusur(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getType() == Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(orijinalKey, PersistentDataType.STRING)) return false; // Zaten düşürülmüş
        StringBuilder orijinal = new StringBuilder();
        for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
            if (e.getValue() > e.getKey().getMaxLevel()) {
                orijinal.append(e.getKey().getKey()).append('=').append(e.getValue()).append(';');
                meta.addEnchant(e.getKey(), e.getKey().getMaxLevel(), true);
            }
        }
        if (orijinal.length() == 0) return false;
        meta.getPersistentDataContainer().set(orijinalKey, PersistentDataType.STRING, orijinal.toString());
        item.setItemMeta(meta);
        return true;
    }

    /** Arenadan çıkan / girişte savaşta olmayan oyuncunun düşürülmüş büyülerini geri yükler (çökmeye karşı da güvenli). */
    public void arenaSeviyeGeriYukle(Player p) {
        boolean degisti = false;
        for (ItemStack item : p.getInventory().getContents()) degisti |= geriYukle(item);
        for (ItemStack item : p.getEnderChest().getContents()) degisti |= geriYukle(item);
        if (degisti) p.updateInventory();
    }

    private boolean geriYukle(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        String orijinal = meta.getPersistentDataContainer().get(orijinalKey, PersistentDataType.STRING);
        if (orijinal == null) return false;
        for (String parca : orijinal.split(";")) {
            if (parca.isBlank()) continue;
            String[] kv = parca.split("=");
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.fromString(kv[0].toLowerCase(Locale.ROOT)));
            if (e != null && kv.length == 2) meta.addEnchant(e, Integer.parseInt(kv[1]), true);
        }
        meta.getPersistentDataContainer().remove(orijinalKey);
        item.setItemMeta(meta);
        return true;
    }
}
