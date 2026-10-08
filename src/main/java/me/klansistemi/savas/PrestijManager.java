package me.klansistemi.savas;

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

import me.klansistemi.KlanManager;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Zaman;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.IliskiDurumu;

/**
 * Açık dünya husumet prestiji: husumetli bir klanın üyesini açık dünyada ağır yaralamak
 * (Meslek sağlık sisteminde ölümcül vuruş = ağır yaralı) klanına prestij puanı kazandırır.
 * Para/kasa transferi yoktur. Sıralama: /klan prestij (baltop gibi).
 *
 * Algılama: ölümcül vuruş anında saldıran kaydedilir; bir tick sonra kurbanın gerçekten ağır yaralı
 * olduğu (ya da öldüğü) MeslekAPI ile doğrulanır. Böylece iptal edilen/engellenen vuruşlar sayılmaz.
 */
public class PrestijManager implements Listener {

    private static final int SAYFA = 10;

    private final KlanSistemi plugin;
    private final Map<UUID, Long> sonPuan = new HashMap<>(); // kurban -> en son puan verildiği an

    public PrestijManager(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private KlanManager am() { return plugin.klanManager(); }

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
        // Sağlıktan muaf oyuncular (Arena Ligi maçı, klan savaşı) arena dövüşündedir; prestij sayılmaz
        if (plugin.meslek().saglikMuafMi(kId) || plugin.meslek().saglikMuafMi(saldiran.getUniqueId())) return;
        boolean onceYarali = plugin.meslek().agirYaraliMi(kId);
        if (onceYarali) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean yaralandi = plugin.meslek().agirYaraliMi(kId) || kurban.isDead();
            if (yaralandi) degerlendir(saldiran, kurban);
        });
    }

    private void degerlendir(Player saldiran, Player kurban) {
        Klan a = am().oyuncununKlani(saldiran.getUniqueId());
        Klan b = am().oyuncununKlani(kurban.getUniqueId());
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
        KlanUyesi kurbanUye = b.uyeler.get(kurban.getUniqueId());
        if (kurbanUye != null && simdi - kurbanUye.katilma < yeniUyeKoruma()) {
            m().gonder(saldiran, "prestij-yeni-uye", "&7{oyuncu} klanına yeni katıldı, prestij kazandırmaz.", "oyuncu", kurban.getName());
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
        am().klanaGonder(a, "prestij-kazanildi", "&6{oyuncu} &7düşman &c{kurban} &7({klan}) oyuncusunu ağır yaraladı! &a+{puan} prestij &8({biz} - {onlar})",
                "oyuncu", saldiran.getName(), "kurban", kurban.getName(), "klan", b.isim, "puan", p, "biz", biz, "onlar", onlar);
        am().klanaGonder(b, "prestij-kaybedildi", "&c{kurban} &7düşman &6{oyuncu} &7({klan}) tarafından ağır yaralandı! &8({biz} - {onlar})",
                "oyuncu", saldiran.getName(), "kurban", kurban.getName(), "klan", a.isim, "biz", onlar, "onlar", biz);
        saldiran.playSound(saldiran.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
    }

    // ------------------------------------------------------------------ SIRALAMA (/klan prestij)
    private List<Klan> sirali() {
        List<Klan> liste = new ArrayList<>(am().klanlar());
        liste.sort(Comparator.comparingInt((Klan a) -> a.prestij).reversed().thenComparing(a -> a.isim));
        return liste;
    }

    public int sira(Klan a) {
        List<Klan> liste = sirali();
        return liste.indexOf(a) + 1;
    }

    public void siralama(org.bukkit.command.CommandSender s, int sayfa) {
        List<Klan> liste = sirali();
        if (liste.isEmpty()) { m().gonder(s, "liste-bos", "&7Henüz hiç klan kurulmadı."); return; }
        int sayfaSayisi = Math.max(1, (liste.size() + SAYFA - 1) / SAYFA);
        sayfa = Math.max(1, Math.min(sayfa, sayfaSayisi));
        s.sendMessage(m().metin("prestij-baslik", "&6&l--- KLAN PRESTİJ SIRALAMASI &7({sayfa}/{toplam}) &6&l---", "sayfa", sayfa, "toplam", sayfaSayisi));
        for (int i = (sayfa - 1) * SAYFA; i < Math.min(liste.size(), sayfa * SAYFA); i++) {
            Klan a = liste.get(i);
            String renk = i == 0 ? "&6" : i == 1 ? "&7" : i == 2 ? "&c" : "&f";
            KlanUyesi lider = a.lider();
            s.sendMessage(Mesaj.renk(m().metin("prestij-satir", "{renk}{sira}. &e{klan} &7- &a{prestij} prestij &8(Lider: {lider})",
                    "renk", renk, "sira", i + 1, "klan", a.isim, "prestij", a.prestij, "lider", lider != null ? lider.isim : "-")));
        }
        if (s instanceof Player p) {
            Klan benim = am().oyuncununKlani(p.getUniqueId());
            if (benim != null) {
                s.sendMessage(m().metin("prestij-kendi", "&7Klanınız: &e{klan} &7- &f{sira}. sırada &7({prestij} prestij)",
                        "klan", benim.isim, "sira", sira(benim), "prestij", benim.prestij));
            }
        }
        if (sayfa < sayfaSayisi) s.sendMessage(m().metin("prestij-sonraki", "&7Sonraki sayfa: &e/klan prestij {sayfa}", "sayfa", sayfa + 1));
    }
}
