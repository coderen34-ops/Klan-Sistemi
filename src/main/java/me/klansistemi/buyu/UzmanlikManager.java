package me.klansistemi.buyu;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import me.klansistemi.KlanManager;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.Zaman;
import me.klansistemi.model.Klan;
import me.klansistemi.model.Rol;

/**
 * Büyü alanları (KAZMA, KILIC, ZIRH ...). Bir alanı aynı anda sadece bir klan yönetir; alan savaşla el
 * değiştirmez, klan dağılınca ya da bırakınca boşalır. Sahiplik klan kaydında (klanlar.yml) tutulur.
 */
public class UzmanlikManager {

    private final KlanSistemi plugin;

    public UzmanlikManager(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private KlanManager km() { return plugin.klanManager(); }
    private BuyuEsyasi esya() { return plugin.buyuEsyasi(); }

    private double almaUcreti() { return plugin.getConfig().getDouble("buyu.uzmanlik.alma-ucreti", 100000); }
    private int maxAlan() { return plugin.getConfig().getInt("buyu.uzmanlik.klan-basina-max-alan", 1); }

    /** Alanı yöneten klan (yoksa null). */
    public Klan alanSahibi(String alan) {
        for (Klan k : km().klanlar()) if (k.uzmanliklar.contains(alan.toUpperCase(Locale.ROOT))) return k;
        return null;
    }

    // ------------------------------------------------------------------ KOMUTLAR
    public void liste(CommandSender s) {
        s.sendMessage(m().metin("uzmanlik-liste-baslik", "&6&l--- BÜYÜ ALANLARI ---"));
        for (BuyuEsyasi.AlanTanimi alan : esya().alanlar().values()) {
            Klan sahip = alanSahibi(alan.kod);
            String durum = !alan.aktif ? m().metin("uzmanlik-durum-kapali", "&8Kapalı")
                    : sahip == null ? m().metin("uzmanlik-durum-bos", "&aBOŞ &7(/klan uzmanlik al {alan})", "alan", alan.kod)
                    : m().metin("uzmanlik-durum-sahip", "&cYöneten: &e{klan}", "klan", sahip.isim);
            s.sendMessage(Mesaj.renk("&7- ") + alan.ad + Mesaj.renk(" &8(" + alan.kod + ") &7- ") + durum);
        }
        s.sendMessage(m().metin("uzmanlik-liste-ucret", "&7Alan alma ücreti: &e{ucret} &7(klan kasasından)", "ucret", Para.yaz(almaUcreti())));
    }

    public void al(Player p, String alanKod) {
        Klan k = liderKlani(p);
        if (k == null) return;
        BuyuEsyasi.AlanTanimi alan = esya().alan(alanKod);
        if (alan == null) { m().gonder(p, "uzmanlik-yok", "&cBöyle bir büyü alanı yok. &7/klan uzmanlik liste"); return; }
        if (!alan.aktif) { m().gonder(p, "uzmanlik-kapali", "&cBu alan şu an kullanıma kapalı."); return; }
        if (plugin.kasaKilitliMi(k)) { m().gonder(p, "savas-kilit", "&cKlanınız savaşta! Savaş bitene kadar kasa, ayrılma ve üye atma kilitlidir."); return; }
        if (k.uzmanliklar.contains(alan.kod)) { m().gonder(p, "uzmanlik-zaten", "&eBu alan zaten klanınızda."); return; }
        Klan sahip = alanSahibi(alan.kod);
        if (sahip != null) { m().gonder(p, "uzmanlik-dolu", "&cBu alanı şu an {klan} klanı yönetiyor.", "klan", sahip.isim); return; }
        if (k.uzmanliklar.size() >= maxAlan()) {
            m().gonder(p, "uzmanlik-max", "&cBir klan en fazla {max} alan yönetebilir. Önce mevcut alanı bırakın.", "max", maxAlan()); return;
        }
        double ucret = almaUcreti();
        if (k.kasa < ucret) { m().gonder(p, "uzmanlik-kasa", "&cAlan almak için klan kasasında {ucret} olmalı.", "ucret", Para.yaz(ucret)); return; }

        k.kasa = Para.kurus(k.kasa - ucret);
        k.uzmanliklar.add(alan.kod);
        plugin.veri().kaydet();
        plugin.log().yaz(k, p.getName(), "UZMANLIK_ALDI", alan.kod + " | " + Para.yaz(ucret));
        Bukkit.broadcastMessage(m().onek() + m().metin("uzmanlik-alindi-duyuru", "&e{klan} &7klanı artık {alan} &7alanını yönetiyor!", "klan", k.isim, "alan", alan.ad));
    }

    public void birak(Player p, String alanKod) {
        Klan k = liderKlani(p);
        if (k == null) return;
        String kod = alanKod != null ? alanKod.toUpperCase(Locale.ROOT) : (k.uzmanliklar.size() == 1 ? k.uzmanliklar.iterator().next() : null);
        if (kod == null || !k.uzmanliklar.contains(kod)) { m().gonder(p, "uzmanlik-sizde-yok", "&cKlanınız bu alanı yönetmiyor."); return; }
        k.uzmanliklar.remove(kod);
        plugin.veri().kaydet();
        plugin.log().yaz(k, p.getName(), "UZMANLIK_BIRAKTI", kod);
        BuyuEsyasi.AlanTanimi alan = esya().alan(kod);
        Bukkit.broadcastMessage(m().onek() + m().metin("uzmanlik-birakildi-duyuru", "&e{klan} &7klanı {alan} &7alanını bıraktı, alan artık boş.",
                "klan", k.isim, "alan", alan != null ? alan.ad : kod));
    }

    private Klan liderKlani(Player p) {
        Klan k = km().oyuncununKlani(p.getUniqueId());
        if (k == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return null; }
        if (k.uyeler.get(p.getUniqueId()).rol != Rol.LIDER) { m().gonder(p, "sadece-lider", "&cBunu sadece klan Lideri yapabilir."); return null; }
        return k;
    }

    // ------------------------------------------------------------------ YÖNETİCİ TEST ÜRETİMİ
    /**
     * /klan admin buyu uret <klan> <alan> [eşya]: atölye gelene kadar test için klan katmanı eşya üretip
     * klanın eşya kasasına koyar ve kayıt defterine işler.
     */
    public void adminUret(CommandSender s, String klanIsmi, String alanKod, String esyaAdi) {
        Klan k = km().klanBul(klanIsmi);
        if (k == null) { m().gonder(s, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }
        BuyuEsyasi.AlanTanimi alan = esya().alan(alanKod);
        if (alan == null || alan.esyalar.isEmpty()) { m().gonder(s, "uzmanlik-yok", "&cBöyle bir büyü alanı yok. &7/klan uzmanlik liste"); return; }
        Material mat = esyaAdi == null ? alan.esyalar.iterator().next() : Material.matchMaterial(esyaAdi);
        if (mat == null || !alan.esyalar.contains(mat)) {
            m().gonder(s, "buyu-esya-uygun-degil", "&cBu eşya {alan} alanına uygun değil.", "alan", alan.kod); return;
        }
        KayitDefteri defter = plugin.defter();
        long simdi = System.currentTimeMillis();
        String kod = defter.yeniKod();
        UUID uuid = UUID.randomUUID();
        ItemStack item = esya().olustur(mat, alan, BuyuEsyasi.Katman.KLAN, k.id, k.isim, kod, uuid, simdi);
        ItemStack kalan = plugin.esya().kasayaKoy(k, item);
        if (kalan != null) { m().gonder(s, "buyu-kasa-dolu", "&c{klan} klanının eşya kasası dolu.", "klan", k.isim); return; }
        KayitDefteri.Kayit kayit = new KayitDefteri.Kayit(kod, uuid, k.id, k.isim, k.id, alan.kod, BuyuEsyasi.Katman.KLAN, KayitDefteri.Durum.KASADA, simdi);
        kayit.malzeme = mat.name();
        defter.ekle(kayit);
        plugin.log().yaz(k, s.getName(), "BUYU_URETILDI_ADMIN", kod + " | " + alan.kod + " | " + mat.name());
        m().gonder(s, "buyu-uretildi-admin", "&a{klan} kasasına test eşyası üretildi: &e{kod} &7({esya})", "klan", k.isim, "kod", kod, "esya", mat.name());
    }

    /** /klan admin buyu defter <kod>: kayıt defterindeki bilgiler. */
    public void adminDefter(CommandSender s, String kod) {
        KayitDefteri.Kayit k = plugin.defter().get(kod.toUpperCase(Locale.ROOT));
        if (k == null) { m().gonder(s, "buyu-kayit-yok", "&cBu kimlikte bir kayıt yok."); return; }
        Klan sahip = k.sahipKlan == null ? null : km().klanGetir(k.sahipKlan);
        s.sendMessage(Mesaj.renk("&6&l--- " + k.kod + " ---"));
        s.sendMessage(Mesaj.renk("&7Alan: &f" + k.alan + " &7| Katman: &f" + k.katman + " &7| Durum: &e" + k.durum));
        s.sendMessage(Mesaj.renk("&7Kaynak klan: &f" + k.kaynakKlanAdi + " &7| Sahip: &f" + (sahip != null ? sahip.isim : "-")));
        if (k.oduncOyuncu != null) s.sendMessage(Mesaj.renk("&7Ödünç alan: &f" + km().oyuncuAdi(k.oduncOyuncu)));
        s.sendMessage(Mesaj.renk("&7Üretim: &f" + Zaman.tarih(k.uretim) + " &7| Güncelleme: &f" + Zaman.tarih(k.guncelleme)));
        if (k.not != null) s.sendMessage(Mesaj.renk("&7Not: &f" + k.not));
    }

    /** Klan dağılınca: klan katmanı eşyaları geçersiz olur (kasa-bağlı oldukları için üyelere dağıtılmaz). */
    public void klanDagildi(Klan k) {
        List<KayitDefteri.Kayit> kayitlar = plugin.defter().klanKayitlari(k.id);
        int sayi = 0;
        for (KayitDefteri.Kayit kayit : kayitlar) {
            if (kayit.katman != BuyuEsyasi.Katman.KLAN) continue;
            plugin.defter().durumAyarla(kayit, KayitDefteri.Durum.SILINDI, null, "Klan dağıldı");
            sayi++;
        }
        if (sayi > 0) plugin.log().yaz(k, "-", "BUYU_KLAN_DAGILDI", sayi + " kasa-bağlı eşya geçersiz oldu");
    }

    public Map<String, BuyuEsyasi.AlanTanimi> alanlar() { return esya().alanlar(); }

    public String alanlarMetni(Klan k) {
        if (k.uzmanliklar.isEmpty()) return ChatColor.DARK_GRAY + "yok";
        StringBuilder sb = new StringBuilder();
        for (String kod : k.uzmanliklar) {
            BuyuEsyasi.AlanTanimi a = esya().alan(kod);
            if (sb.length() > 0) sb.append(ChatColor.GRAY).append(", ");
            sb.append(a != null ? a.ad : kod);
        }
        return sb.toString();
    }
}
