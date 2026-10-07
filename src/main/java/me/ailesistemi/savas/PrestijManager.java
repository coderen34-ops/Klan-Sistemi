package me.ailesistemi.savas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import me.ailesistemi.AileManager;
import me.ailesistemi.AileSistemi;
import me.ailesistemi.Mesaj;
import me.ailesistemi.Zaman;
import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.IliskiDurumu;

/**
 * Açık dünya husumet prestiji: husumetli bir ailenin üyesini açık dünyada ağır yaralamak
 * (Meslek sağlık sisteminde ölümcül vuruş = ağır yaralı) ailene prestij puanı kazandırır.
 * Para/kasa transferi yoktur. Sıralama: /aile prestij (baltop gibi).
 *
 * Algılama: ölümcül vuruş anında saldıran kaydedilir; bir tick sonra kurbanın gerçekten ağır yaralı
 * olduğu (ya da öldüğü) MeslekAPI ile doğrulanır. Böylece iptal edilen/engellenen vuruşlar sayılmaz.
 */
public class PrestijManager implements Listener {

    private static final int SAYFA = 10;

    private final AileSistemi plugin;
    private final Map<UUID, Long> sonPuan = new HashMap<>(); // kurban -> en son puan verildiği an

    public PrestijManager(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private AileManager am() { return plugin.aileManager(); }

    private boolean aktif() { return plugin.getConfig().getBoolean("prestij.aktif", true); }
    private int puan() { return plugin.getConfig().getInt("prestij.puan", 1); }
    private long kurbanBekleme() { return (long) (plugin.getConfig().getDouble("prestij.ayni-kurban-bekleme-dakika", 30) * 60000L); }
    private long yeniUyeKoruma() { return (long) (plugin.getConfig().getDouble("prestij.yeni-uye-koruma-saat", 24) * Zaman.SAAT); }

    private static Player saldiran(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    // Meslek'in sağlık sistemi ölümcül vuruşu NORMAL öncelikte iptal edip oyuncuyu ağır yaralı yapar;
    // bu yüzden vuruş ondan önce (LOW) yakalanır, sonuç bir tick sonra kontrol edilir.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!aktif() || !(event.getEntity() instanceof Player kurban)) return;
        Player saldiran = saldiran(event);
        if (saldiran == null || saldiran.equals(kurban)) return;
        if (kurban.getHealth() - event.getFinalDamage() > 0) return; // Ölümcül değil

        UUID kId = kurban.getUniqueId();
        boolean onceYarali = plugin.meslek().agirYaraliMi(kId);
        if (onceYarali) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean yaralandi = plugin.meslek().agirYaraliMi(kId) || kurban.isDead();
            if (yaralandi) degerlendir(saldiran, kurban);
        });
    }

    private void degerlendir(Player saldiran, Player kurban) {
        Aile a = am().oyuncununAilesi(saldiran.getUniqueId());
        Aile b = am().oyuncununAilesi(kurban.getUniqueId());
        if (a == null || b == null || a.id.equals(b.id)) return;
        if (plugin.iliski().iliski(a.id, b.id) != IliskiDurumu.HUSUMET) return;
        // Arena düellosu ayrı sayılır
        if (plugin.savas().katilimciSavasi(saldiran.getUniqueId()) != null || plugin.savas().katilimciSavasi(kurban.getUniqueId()) != null) return;
        if (plugin.meslek().hapisteMi(kurban.getUniqueId()) || plugin.meslek().durusmadaMi(kurban.getUniqueId())) return;

        if (plugin.guvenliBolgeler().guvenliMi(kurban.getLocation()) || plugin.guvenliBolgeler().guvenliMi(saldiran.getLocation())) {
            m().gonder(saldiran, "prestij-guvenli", "&7Güvenli bölgede yaralama prestij kazandırmaz.");
            return;
        }
        long simdi = System.currentTimeMillis();
        AileUyesi kurbanUye = b.uyeler.get(kurban.getUniqueId());
        if (kurbanUye != null && simdi - kurbanUye.katilma < yeniUyeKoruma()) {
            m().gonder(saldiran, "prestij-yeni-uye", "&7{oyuncu} ailesine yeni katıldı, prestij kazandırmaz.", "oyuncu", kurban.getName());
            return;
        }
        Long son = sonPuan.get(kurban.getUniqueId());
        if (son != null && simdi - son < kurbanBekleme()) {
            m().gonder(saldiran, "prestij-bekleme", "&7{oyuncu} kısa süre önce yaralandı, {sure} sonra tekrar prestij kazandırır.",
                    "oyuncu", kurban.getName(), "sure", Zaman.sure(son + kurbanBekleme() - simdi));
            return;
        }

        sonPuan.put(kurban.getUniqueId(), simdi);
        int p = puan();
        a.prestij += p;
        a.prestijRakip.merge(b.id, p, Integer::sum);
        plugin.veri().kaydet();

        int biz = a.prestijRakip.getOrDefault(b.id, 0), onlar = b.prestijRakip.getOrDefault(a.id, 0);
        plugin.log().yaz(a, saldiran.getName(), "PRESTIJ", kurban.getName() + " (" + b.isim + ") yaralandı, +" + p + " | " + biz + "-" + onlar);
        am().aileyeGonder(a, "prestij-kazanildi", "&6{oyuncu} &7düşman &c{kurban} &7({aile}) oyuncusunu ağır yaraladı! &a+{puan} prestij &8({biz} - {onlar})",
                "oyuncu", saldiran.getName(), "kurban", kurban.getName(), "aile", b.isim, "puan", p, "biz", biz, "onlar", onlar);
        am().aileyeGonder(b, "prestij-kaybedildi", "&c{kurban} &7düşman &6{oyuncu} &7({aile}) tarafından ağır yaralandı! &8({biz} - {onlar})",
                "oyuncu", saldiran.getName(), "kurban", kurban.getName(), "aile", a.isim, "biz", onlar, "onlar", biz);
        saldiran.playSound(saldiran.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
    }

    // ------------------------------------------------------------------ SIRALAMA (/aile prestij)
    private List<Aile> sirali() {
        List<Aile> liste = new ArrayList<>(am().aileler());
        liste.sort(Comparator.comparingInt((Aile a) -> a.prestij).reversed().thenComparing(a -> a.isim));
        return liste;
    }

    public int sira(Aile a) {
        List<Aile> liste = sirali();
        return liste.indexOf(a) + 1;
    }

    public void siralama(org.bukkit.command.CommandSender s, int sayfa) {
        List<Aile> liste = sirali();
        if (liste.isEmpty()) { m().gonder(s, "liste-bos", "&7Henüz hiç aile kurulmadı."); return; }
        int sayfaSayisi = Math.max(1, (liste.size() + SAYFA - 1) / SAYFA);
        sayfa = Math.max(1, Math.min(sayfa, sayfaSayisi));
        s.sendMessage(m().metin("prestij-baslik", "&6&l--- AİLE PRESTİJ SIRALAMASI &7({sayfa}/{toplam}) &6&l---", "sayfa", sayfa, "toplam", sayfaSayisi));
        for (int i = (sayfa - 1) * SAYFA; i < Math.min(liste.size(), sayfa * SAYFA); i++) {
            Aile a = liste.get(i);
            String renk = i == 0 ? "&6" : i == 1 ? "&7" : i == 2 ? "&c" : "&f";
            AileUyesi patron = a.patron();
            s.sendMessage(Mesaj.renk(m().metin("prestij-satir", "{renk}{sira}. &e{aile} &7- &a{prestij} prestij &8(Patron: {patron})",
                    "renk", renk, "sira", i + 1, "aile", a.isim, "prestij", a.prestij, "patron", patron != null ? patron.isim : "-")));
        }
        if (s instanceof Player p) {
            Aile benim = am().oyuncununAilesi(p.getUniqueId());
            if (benim != null) {
                s.sendMessage(m().metin("prestij-kendi", "&7Aileniz: &e{aile} &7- &f{sira}. sırada &7({prestij} prestij)",
                        "aile", benim.isim, "sira", sira(benim), "prestij", benim.prestij));
            }
        }
        if (sayfa < sayfaSayisi) s.sendMessage(m().metin("prestij-sonraki", "&7Sonraki sayfa: &e/aile prestij {sayfa}", "sayfa", sayfa + 1));
    }
}
