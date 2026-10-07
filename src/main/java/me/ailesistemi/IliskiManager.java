package me.ailesistemi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Gorus;
import me.ailesistemi.model.IliskiDurumu;
import me.ailesistemi.model.Rol;

/**
 * Aileler arası ilişkiler. Her aile başka bir aile hakkında bir görüş (DOST/TARAFSIZ/HUSUMET + not) yazar.
 * İki aile arasındaki resmi ilişki görüşlerden hesaplanır:
 *   - Taraflardan biri HUSUMET dediyse: HUSUMET (tek taraflı ilan)
 *   - İkisi de DOST dediyse: DOST (karşılıklı kabul = ittifak)
 *   - Diğer durumlar: TARAFSIZ
 */
public class IliskiManager implements Listener {

    private static final Pattern REKLAM = Pattern.compile(
            "(https?://|www\\.|discord\\.gg|\\.(com|net|org|tr|gg|xyz|me)\\b|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b)", Pattern.CASE_INSENSITIVE);

    private final AileSistemi plugin;
    private final Map<UUID, Map<UUID, Gorus>> gorusler = new HashMap<>();   // yazan aile -> (hedef aile -> görüş)
    private final Map<String, Long> dostlukBitisleri = new HashMap<>();     // aile çifti -> dostluğun bittiği an
    private final Map<UUID, UUID> notBekleyenler = new ConcurrentHashMap<>(); // patron -> not yazacağı hedef aile

    public IliskiManager(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }

    private static String ciftAnahtari(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    private long gorusBeklemeMs() { return (long) (plugin.getConfig().getDouble("iliski.gorus-bekleme-saat", 1) * Zaman.SAAT); }
    private long husumetBeklemeMs() { return (long) (plugin.getConfig().getDouble("iliski.dostluk-husumet-bekleme-saat", 12) * Zaman.SAAT); }
    private int notMax() { return plugin.getConfig().getInt("iliski.not-max-karakter", 100); }

    // ------------------------------------------------------------------ SORGULAR
    public Gorus gorus(UUID yazan, UUID hedef) {
        Map<UUID, Gorus> g = gorusler.get(yazan);
        return g == null ? null : g.get(hedef);
    }

    private IliskiDurumu gorusDurumu(UUID yazan, UUID hedef) {
        Gorus g = gorus(yazan, hedef);
        return g == null ? IliskiDurumu.TARAFSIZ : g.durum;
    }

    public IliskiDurumu iliski(UUID a, UUID b) {
        if (a.equals(b)) return IliskiDurumu.DOST;
        IliskiDurumu ab = gorusDurumu(a, b), ba = gorusDurumu(b, a);
        if (ab == IliskiDurumu.HUSUMET || ba == IliskiDurumu.HUSUMET) return IliskiDurumu.HUSUMET;
        if (ab == IliskiDurumu.DOST && ba == IliskiDurumu.DOST) return IliskiDurumu.DOST;
        return IliskiDurumu.TARAFSIZ;
    }

    public List<Gorus> gidenGorusler(UUID aile) {
        List<Gorus> liste = new ArrayList<>(gorusler.getOrDefault(aile, Map.of()).values());
        liste.sort(Comparator.comparingLong((Gorus g) -> g.zaman).reversed());
        return liste;
    }

    public List<Gorus> gelenGorusler(UUID aile) {
        List<Gorus> liste = new ArrayList<>();
        for (Map<UUID, Gorus> g : gorusler.values()) {
            Gorus x = g.get(aile);
            if (x != null) liste.add(x);
        }
        liste.sort(Comparator.comparingLong((Gorus g) -> g.zaman).reversed());
        return liste;
    }

    /** [dost, husumet] sayıları (menü özeti için). */
    public int[] ozet(UUID aile) {
        int[] s = new int[2];
        for (Aile diger : plugin.aileManager().aileler()) {
            if (diger.id.equals(aile)) continue;
            IliskiDurumu d = iliski(aile, diger.id);
            if (d == IliskiDurumu.DOST) s[0]++;
            else if (d == IliskiDurumu.HUSUMET) s[1]++;
        }
        return s;
    }

    public Map<UUID, Map<UUID, Gorus>> tumGorusler() { return gorusler; }
    public Map<String, Long> dostlukBitisleri() { return dostlukBitisleri; }

    public void yukle(Gorus g) {
        gorusler.computeIfAbsent(g.yazanAile, k -> new HashMap<>()).put(g.hedefAile, g);
    }

    // ------------------------------------------------------------------ GÖRÜŞ YAZMA
    /** Not geçerliyse temizlenmiş halini, değilse null döner (oyuncuya sebep bildirilir). */
    private String notuTemizle(Player p, String not) {
        if (not == null) return "";
        not = not.replace("&", "").replace("§", "").trim();
        if (not.length() > notMax()) {
            m().gonder(p, "gorus-uzun", "&cGörüş notu en fazla {max} karakter olabilir.", "max", notMax());
            return null;
        }
        String norm = AileManager.anahtar(not);
        for (String kelime : plugin.ayar().yasakliKelimeler()) {
            if (!kelime.isBlank() && norm.contains(AileManager.anahtar(kelime))) {
                m().gonder(p, "gorus-yasakli", "&cGörüş notunuz uygunsuz bir ifade içeriyor.");
                return null;
            }
        }
        if (REKLAM.matcher(not).find()) {
            m().gonder(p, "gorus-reklam", "&cGörüş notunda bağlantı veya reklam olamaz.");
            return null;
        }
        return not;
    }

    /**
     * Patronun görüşünü yazar/değiştirir. durum null ise mevcut durum korunur (sadece not değişir);
     * not null ise mevcut not korunur (sadece durum değişir).
     */
    public boolean gorusYaz(Player p, Aile hedef, IliskiDurumu durum, String not) {
        Aile benim = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (benim == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return false; }
        if (benim.uyeler.get(p.getUniqueId()).rol != Rol.PATRON) { m().gonder(p, "sadece-patron", "&cBunu sadece aile Patronu yapabilir."); return false; }
        if (hedef.id.equals(benim.id)) { m().gonder(p, "gorus-kendine", "&cKendi aileniz hakkında görüş yazamazsınız."); return false; }

        long simdi = System.currentTimeMillis();
        Gorus eski = gorus(benim.id, hedef.id);
        if (eski != null && simdi - eski.zaman < gorusBeklemeMs()) {
            m().gonder(p, "gorus-bekleme", "&cBu aile hakkındaki görüşünüzü {sure} sonra değiştirebilirsiniz.",
                    "sure", Zaman.sure(eski.zaman + gorusBeklemeMs() - simdi));
            return false;
        }
        IliskiDurumu yeniDurum = durum != null ? durum : (eski != null ? eski.durum : IliskiDurumu.TARAFSIZ);
        String yeniNot = not != null ? notuTemizle(p, not) : (eski != null ? eski.not : "");
        if (yeniNot == null) return false;

        String cift = ciftAnahtari(benim.id, hedef.id);
        IliskiDurumu onceki = iliski(benim.id, hedef.id);
        if (yeniDurum == IliskiDurumu.HUSUMET && onceki != IliskiDurumu.HUSUMET) {
            Long bitis = dostlukBitisleri.get(cift);
            long kalan = bitis == null ? 0 : bitis + husumetBeklemeMs() - simdi;
            if (onceki == IliskiDurumu.DOST) kalan = husumetBeklemeMs(); // Önce dostluk bozulmalı, sonra beklenmeli
            if (kalan > 0) {
                m().gonder(p, "husumet-bekleme", "&cDostluk bozulduktan sonra husumet ilanı için {sure} beklemelisiniz.",
                        "sure", Zaman.sure(kalan));
                if (onceki == IliskiDurumu.DOST) m().gonder(p, "husumet-once-boz", "&7Önce durumu TARAFSIZ yaparak dostluğu bozun.");
                return false;
            }
        }

        Gorus g = new Gorus(benim.id, hedef.id, yeniDurum, yeniNot, p.getName(), simdi);
        gorusler.computeIfAbsent(benim.id, k -> new HashMap<>()).put(hedef.id, g);
        IliskiDurumu sonraki = iliski(benim.id, hedef.id);
        if (onceki == IliskiDurumu.DOST && sonraki != IliskiDurumu.DOST) dostlukBitisleri.put(cift, simdi);
        plugin.veri().kaydet();

        plugin.log().yaz(benim, p.getName(), "GORUS", hedef.isim + " -> " + yeniDurum.ad + (yeniNot.isEmpty() ? "" : " | \"" + yeniNot + "\""));
        m().gonder(p, "gorus-kaydedildi", "&a{aile} hakkındaki görüşünüz kaydedildi: {durum}", "aile", hedef.isim, "durum", yeniDurum.renkliAd());
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        degisimBildir(benim, hedef, onceki, sonraki, yeniDurum);
        return true;
    }

    private void degisimBildir(Aile benim, Aile hedef, IliskiDurumu onceki, IliskiDurumu sonraki, IliskiDurumu benimGorusum) {
        AileManager am = plugin.aileManager();
        if (onceki == sonraki) {
            // Resmi durum değişmedi ama karşı tarafa dostluk teklifi gitmiş olabilir
            if (benimGorusum == IliskiDurumu.DOST && sonraki != IliskiDurumu.DOST) {
                AileUyesi patron = hedef.patron();
                Player pp = patron == null ? null : Bukkit.getPlayer(patron.uuid);
                if (pp != null) m().gonder(pp, "dostluk-teklifi", "&a{aile} ailesi size DOST görüşü bildirdi. Siz de DOST seçerseniz ittifak kurulur: &e/aile panel",
                        "aile", benim.isim);
            }
            return;
        }
        switch (sonraki) {
            case DOST -> {
                am.aileyeGonder(benim, "ittifak-kuruldu", "&a&l{aile} ailesiyle İTTİFAK kuruldu!", "aile", hedef.isim);
                am.aileyeGonder(hedef, "ittifak-kuruldu", "&a&l{aile} ailesiyle İTTİFAK kuruldu!", "aile", benim.isim);
            }
            case HUSUMET -> {
                am.aileyeGonder(benim, "husumet-ilan-edildi", "&c&l{aile} ailesine HUSUMET ilan ettiniz!", "aile", hedef.isim);
                am.aileyeGonder(hedef, "husumet-ilan-geldi", "&4&l{aile} ailesi size HUSUMET ilan etti!", "aile", benim.isim);
                for (AileUyesi u : hedef.uyeler.values()) {
                    Player p = Bukkit.getPlayer(u.uuid);
                    if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.2f);
                }
            }
            case TARAFSIZ -> {
                String anahtar = onceki == IliskiDurumu.DOST ? "dostluk-bozuldu" : "husumet-bitti";
                String varsayilan = onceki == IliskiDurumu.DOST ? "&e{aile} ailesiyle ittifak sona erdi." : "&e{aile} ailesiyle husumet sona erdi.";
                am.aileyeGonder(benim, anahtar, varsayilan, "aile", hedef.isim);
                am.aileyeGonder(hedef, anahtar, varsayilan, "aile", benim.isim);
            }
        }
    }

    // ------------------------------------------------------------------ NOT YAZMA (chat ile)
    public void notBekle(Player p, Aile hedef) {
        notBekleyenler.put(p.getUniqueId(), hedef.id);
        m().gonder(p, "gorus-not-yaz", "&e{aile} hakkındaki görüş notunuzu sohbete yazın (en fazla {max} karakter). İptal için &ciptal&e yazın.",
                "aile", hedef.isim, "max", notMax());
    }

    public boolean notBekliyorMu(UUID oyuncu) { return notBekleyenler.containsKey(oyuncu); }

    /** Chat'ten gelen notu işler (ana thread). */
    public void notGeldi(Player p, String mesaj) {
        UUID hedefId = notBekleyenler.remove(p.getUniqueId());
        if (hedefId == null) return;
        if (mesaj.equalsIgnoreCase("iptal")) { m().gonder(p, "islem-iptal", "&eİşlem iptal edildi."); return; }
        Aile hedef = plugin.aileManager().aileGetir(hedefId);
        if (hedef == null) { m().gonder(p, "aile-yok", "&cBöyle bir aile bulunamadı."); return; }
        if (gorusYaz(p, hedef, null, mesaj)) plugin.panel().aileDetay(p, hedef);
    }

    // ------------------------------------------------------------------ TEMİZLİK
    public void aileSilindi(UUID aile) {
        gorusler.remove(aile);
        for (Map<UUID, Gorus> g : gorusler.values()) g.remove(aile);
        dostlukBitisleri.keySet().removeIf(k -> k.contains(aile.toString()));
        notBekleyenler.values().removeIf(aile::equals);
    }

    // ------------------------------------------------------------------ DOST PvP KORUMASI
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("iliski.dost-pvp-engeli", true)) return;
        if (!(event.getEntity() instanceof Player kurban)) return;
        Player saldiran = null;
        if (event.getDamager() instanceof Player p) saldiran = p;
        else if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) saldiran = p;
        if (saldiran == null || saldiran.equals(kurban)) return;

        AileManager am = plugin.aileManager();
        Aile a = am.oyuncununAilesi(saldiran.getUniqueId());
        Aile b = am.oyuncununAilesi(kurban.getUniqueId());
        if (a == null || b == null || a.id.equals(b.id)) return;
        if (iliski(a.id, b.id) == IliskiDurumu.DOST) {
            event.setCancelled(true);
            saldiran.sendActionBar(m().bilesen("dost-pvp", "&a{aile} ailesi müttefikiniz, saldıramazsınız!", "aile", b.isim));
        }
    }

    public Set<UUID> notBekleyenOyuncular() { return notBekleyenler.keySet(); }
}
