package me.klansistemi.savas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import me.klansistemi.KlanManager;
import me.klansistemi.KlanSistemi;
import me.klansistemi.Mesaj;
import me.klansistemi.Para;
import me.klansistemi.Zaman;
import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.IliskiDurumu;
import me.klansistemi.model.Rol;

/**
 * Klanlar arası arena düellosu.
 *
 * Akış: teklif (husumet şart) -> karşı lider kabul (arena ayrılır, kasalar kilitlenir) -> hazırlık geri sayımı
 * (üyeler /klan savas katil) -> arena -> puan / süre -> sonuç, ganimet, herkes eski konumuna.
 */
public class SavasManager implements Listener {

    private static final int GECMIS_MAX = 200;

    private final KlanSistemi plugin;
    private final Map<UUID, Map<UUID, Long>> teklifler = new HashMap<>();  // gönderen klan -> (hedef klan -> zaman)
    private final Map<UUID, Savas> klanSavasi = new HashMap<>();
    private final List<Savas> savaslar = new ArrayList<>();
    private final Map<UUID, Long> kaybedenBekleme = new HashMap<>();       // klan -> kaybettiği an
    private final Map<String, Long> ciftBekleme = new HashMap<>();         // klan çifti -> son savaş
    private final List<SavasKaydi> gecmis = new ArrayList<>();
    private final Map<UUID, Location> donusKonumlari = new HashMap<>();    // oyuncu -> savaştan önceki konumu
    private final Set<UUID> izinliIsinlanma = new HashSet<>();

    public SavasManager(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private KlanManager am() { return plugin.klanManager(); }

    // ------------------------------------------------------------------ AYARLAR
    private double c(String yol, double v) { return plugin.getConfig().getDouble("savas." + yol, v); }
    private long teklifGecerlilik() { return (long) (c("teklif-gecerlilik-dakika", 10) * 60000L); }
    private int minCevrimici() { return (int) c("min-cevrimici", 3); }
    private int minKatilimci() { return (int) c("min-katilimci", 3); }
    private int maxKatilimci() { return (int) c("max-katilimci", 5); }
    private long geriSayim() { return (long) (c("geri-sayim-saniye", 60) * 1000L); }
    private int hedefPuan() { return (int) c("hedef-puan", 15); }
    private long savasSuresi() { return (long) (c("sure-dakika", 15) * 60000L); }
    private int dogmaSaniye() { return (int) c("yeniden-dogma-saniye", 5); }
    private long tekrarOldurme() { return (long) (c("ayni-oyuncu-puan-bekleme-saniye", 5) * 1000L); }
    private long cikisTolerans() { return (long) (c("cikis-tolerans-saniye", 60) * 1000L); }
    private boolean esyaKaybi() { return plugin.getConfig().getBoolean("savas.esya-kaybi", false); }
    private boolean dostAtes() { return plugin.getConfig().getBoolean("savas.klan-ici-hasar", false); }
    private double ganimetMin() { return c("ganimet-min", 25); }
    private double ganimetMax() { return c("ganimet-max", 50); }
    private long kaybedenBeklemeMs() { return (long) (c("kaybeden-bekleme-saat", 6) * Zaman.SAAT); }
    private long ciftBeklemeMs() { return (long) (c("ayni-klanlar-bekleme-saat", 12) * Zaman.SAAT); }
    private List<String> yasakKomutlar() { return plugin.getConfig().getStringList("savas.yasak-komutlar"); }

    private static String cift(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    // ------------------------------------------------------------------ SORGULAR
    public boolean kilitliMi(UUID klan) { return klanSavasi.containsKey(klan); }

    public Savas klaninSavasi(UUID klan) { return klanSavasi.get(klan); }

    public Savas katilimciSavasi(UUID oyuncu) {
        for (Savas s : savaslar) if (s.katilimcilar.containsKey(oyuncu)) return s;
        return null;
    }

    public List<SavasKaydi> gecmis() { return gecmis; }
    public Map<UUID, Long> kaybedenBeklemeleri() { return kaybedenBekleme; }
    public Map<String, Long> ciftBeklemeleri() { return ciftBekleme; }
    public Map<UUID, Location> donusKonumlari() { return donusKonumlari; }

    /** Bu klana gelen geçerli teklifler: gönderen klan -> zaman */
    public Map<UUID, Long> gelenTeklifler(UUID hedef) {
        Map<UUID, Long> sonuc = new HashMap<>();
        long simdi = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<UUID, Long>> e : teklifler.entrySet()) {
            Long z = e.getValue().get(hedef);
            if (z != null && simdi - z < teklifGecerlilik()) sonuc.put(e.getKey(), z);
        }
        return sonuc;
    }

    public Map<UUID, Long> gidenTeklifler(UUID gonderen) {
        Map<UUID, Long> sonuc = new HashMap<>();
        long simdi = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : teklifler.getOrDefault(gonderen, Map.of()).entrySet()) {
            if (simdi - e.getValue() < teklifGecerlilik()) sonuc.put(e.getKey(), e.getValue());
        }
        return sonuc;
    }

    /** Savaş engeli varsa açıklaması (yoksa null). Her iki klan için de kontrol edilir. */
    private String savasEngeli(Klan benim, Klan rakip) {
        long simdi = System.currentTimeMillis();
        if (plugin.iliski().iliski(benim.id, rakip.id) != IliskiDurumu.HUSUMET) {
            return m().metin("savas-husumet-yok", "&cDüello için iki klan arasında HUSUMET olmalı.");
        }
        for (Klan a : new Klan[]{benim, rakip}) {
            if (klanSavasi.containsKey(a.id)) return m().metin("savas-zaten-savasta", "&c{klan} şu an başka bir savaşta.", "klan", a.isim);
            long koruma = a.kurulus + plugin.ayar().yeniKlanKorumaMs() - simdi;
            if (koruma > 0) return m().metin("savas-yeni-klan", "&c{klan} yeni kuruldu, {sure} daha savaşa giremez.", "klan", a.isim, "sure", Zaman.sure(koruma));
            Long kayip = kaybedenBekleme.get(a.id);
            if (kayip != null && simdi - kayip < kaybedenBeklemeMs()) {
                return m().metin("savas-kaybeden-bekleme", "&c{klan} son savaşını kaybettiği için {sure} daha savaşamaz.", "klan", a.isim,
                        "sure", Zaman.sure(kayip + kaybedenBeklemeMs() - simdi));
            }
        }
        Long son = ciftBekleme.get(cift(benim.id, rakip.id));
        if (son != null && simdi - son < ciftBeklemeMs()) {
            return m().metin("savas-cift-bekleme", "&cBu iki klan tekrar savaşmak için {sure} beklemeli.", "sure", Zaman.sure(son + ciftBeklemeMs() - simdi));
        }
        return null;
    }

    private Klan liderKlani(Player p) {
        Klan a = am().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return null; }
        if (a.uyeler.get(p.getUniqueId()).rol != Rol.LIDER) { m().gonder(p, "sadece-lider", "&cBunu sadece klan Lideri yapabilir."); return null; }
        return a;
    }

    private int cevrimiciSayisi(Klan a) {
        int n = 0;
        for (KlanUyesi u : a.uyeler.values()) if (Bukkit.getPlayer(u.uuid) != null) n++;
        return n;
    }

    private void lidereGonder(Klan a, String anahtar, String varsayilan, Object... yt) {
        KlanUyesi p = a.lider();
        Player pp = p == null ? null : Bukkit.getPlayer(p.uuid);
        if (pp != null) m().gonder(pp, anahtar, varsayilan, yt);
    }

    // ------------------------------------------------------------------ TEKLİF / KABUL / RED
    public void teklif(Player p, String hedefIsim) {
        Klan benim = liderKlani(p);
        if (benim == null) return;
        Klan rakip = am().klanBul(hedefIsim);
        if (rakip == null) { m().gonder(p, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }
        if (rakip.id.equals(benim.id)) return;
        String engel = savasEngeli(benim, rakip);
        if (engel != null) { p.sendMessage(m().onek() + engel); return; }
        if (gidenTeklifler(benim.id).containsKey(rakip.id)) { m().gonder(p, "savas-teklif-zaten", "&eBu klana zaten bekleyen bir düello teklifiniz var."); return; }

        teklifler.computeIfAbsent(benim.id, k -> new HashMap<>()).put(rakip.id, System.currentTimeMillis());
        plugin.log().yaz(benim, p.getName(), "DUELLO_TEKLIF", rakip.isim);
        m().gonder(p, "savas-teklif-gonderildi", "&a{klan} klanına düello teklifi gönderildi. ({sure} geçerli)", "klan", rakip.isim, "sure", Zaman.sure(teklifGecerlilik()));
        am().klanaGonder(benim, "savas-teklif-klan", "&6Klanımız {klan} klanına DÜELLO teklif etti!", "klan", rakip.isim);
        am().klanaGonder(rakip, "savas-teklif-geldi-klan", "&c&l{klan} klanı bize DÜELLO teklif etti! &7(Karar Liderde)", "klan", benim.isim);

        KlanUyesi rp = rakip.lider();
        Player rpp = rp == null ? null : Bukkit.getPlayer(rp.uuid);
        if (rpp != null) {
            rpp.sendMessage(m().onekBileseni()
                    .append(m().bilesen("savas-teklif-butonlar", "&7Yanıtınız: "))
                    .append(m().bilesen("savas-kabul-buton", "&a&l[KABUL] ").clickEvent(ClickEvent.runCommand("/klan savas kabul " + benim.isim)))
                    .append(m().bilesen("savas-red-buton", "&c&l[RED]").clickEvent(ClickEvent.runCommand("/klan savas red " + benim.isim))));
            rpp.playSound(rpp.getLocation(), Sound.EVENT_RAID_HORN, 0.6f, 1f);
        }
    }

    public void kabul(Player p, String gonderenIsim) {
        Klan benim = liderKlani(p);
        if (benim == null) return;
        Klan rakip = teklifSahibi(p, benim, gonderenIsim);
        if (rakip == null) return;
        String engel = savasEngeli(rakip, benim);
        if (engel != null) { p.sendMessage(m().onek() + engel); return; }
        for (Klan a : new Klan[]{benim, rakip}) {
            if (cevrimiciSayisi(a) < minCevrimici()) {
                m().gonder(p, "savas-az-cevrimici", "&c{klan} klanının en az {min} üyesi çevrimiçi olmalı.", "klan", a.isim, "min", minCevrimici());
                return;
            }
        }
        Arena arena = plugin.arenalar().bosArena();
        if (arena == null) {
            m().gonder(p, "savas-arena-dolu", "&cArena dolu ya da kurulu değil! Teklif geçerli olduğu sürece daha sonra tekrar deneyin.");
            return;
        }

        teklifler.getOrDefault(rakip.id, new HashMap<>()).remove(benim.id);
        teklifler.getOrDefault(benim.id, new HashMap<>()).remove(rakip.id);

        long simdi = System.currentTimeMillis();
        Savas s = new Savas(rakip.id, benim.id, arena, simdi);
        s.hazirlikBitis = simdi + geriSayim();
        arena.kullanan = s;
        savaslar.add(s);
        klanSavasi.put(rakip.id, s);
        klanSavasi.put(benim.id, s);
        // Kasalar kilitlendi: üyelerdeki kasa-bağlı büyülü eşyalar kasaya döner
        plugin.buyuSavas().savasOncesiTopla(rakip);
        plugin.buyuSavas().savasOncesiTopla(benim);

        plugin.log().yaz(benim, p.getName(), "DUELLO_KABUL", rakip.isim + " | arena " + arena.isim);
        Bukkit.broadcastMessage(m().onek() + m().metin("savas-duyuru", "&4&l⚔ {a} ile {b} arasında DÜELLO kabul edildi! &7(Arena: {arena})",
                "a", rakip.isim, "b", benim.isim, "arena", arena.isim));
        for (Klan a : new Klan[]{rakip, benim}) {
            for (KlanUyesi u : a.uyeler.values()) {
                Player up = Bukkit.getPlayer(u.uuid);
                if (up == null) continue;
                up.sendMessage(m().onekBileseni()
                        .append(m().bilesen("savas-hazirlik", "&eSavaş {sure} saniye sonra başlıyor! Kasalar kilitlendi. Katılmak için: ",
                                "sure", geriSayim() / 1000))
                        .append(m().bilesen("savas-katil-buton", "&a&l[KATIL]").clickEvent(ClickEvent.runCommand("/klan savas katil"))));
                up.playSound(up.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
            }
        }
    }

    public void red(Player p, String gonderenIsim) {
        Klan benim = liderKlani(p);
        if (benim == null) return;
        Klan rakip = teklifSahibi(p, benim, gonderenIsim);
        if (rakip == null) return;
        teklifler.getOrDefault(rakip.id, new HashMap<>()).remove(benim.id);
        plugin.log().yaz(benim, p.getName(), "DUELLO_RED", rakip.isim);
        m().gonder(p, "savas-reddedildi", "&e{klan} klanının düello teklifini reddettiniz.", "klan", rakip.isim);
        am().klanaGonder(rakip, "savas-teklif-reddedildi", "&e{klan} klanı düello teklifimizi reddetti.", "klan", benim.isim);
    }

    /** İsim verilmişse o klandan, verilmemişse tek bekleyen tekliften gönderen klan. */
    private Klan teklifSahibi(Player p, Klan benim, String gonderenIsim) {
        Map<UUID, Long> gelen = gelenTeklifler(benim.id);
        if (gelen.isEmpty()) { m().gonder(p, "savas-teklif-yok", "&cBekleyen bir düello teklifi yok."); return null; }
        Klan rakip;
        if (gonderenIsim != null) {
            rakip = am().klanBul(gonderenIsim);
            if (rakip == null || !gelen.containsKey(rakip.id)) { m().gonder(p, "savas-teklif-yok", "&cBekleyen bir düello teklifi yok."); return null; }
        } else if (gelen.size() == 1) {
            rakip = am().klanGetir(gelen.keySet().iterator().next());
        } else {
            m().gonder(p, "savas-teklif-sec", "&eBirden fazla teklif var, klan adını yazın: &6/klan savas kabul <klan>");
            return null;
        }
        return rakip;
    }

    // ------------------------------------------------------------------ KATILMA / BIRAKMA
    public void katil(Player p) {
        Klan a = am().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        Savas s = klanSavasi.get(a.id);
        if (s == null) { m().gonder(p, "savas-yok", "&cKlanınızın şu an bir savaşı yok."); return; }
        if (s.durum != Savas.Durum.HAZIRLIK) { m().gonder(p, "savas-basladi", "&cSavaş başladı, artık katılamazsınız."); return; }
        if (s.katilimcilar.containsKey(p.getUniqueId())) { m().gonder(p, "savas-zaten-katildin", "&eZaten hazırsınız."); return; }
        if (s.katilimciSayisi(a.id) >= maxKatilimci()) { m().gonder(p, "savas-dolu", "&cKlanınızdan en fazla {max} kişi katılabilir.", "max", maxKatilimci()); return; }
        if (plugin.meslek().hapisteMi(p.getUniqueId()) || plugin.meslek().durusmadaMi(p.getUniqueId())) {
            m().gonder(p, "savas-hapis", "&cHapisteyken ya da duruşmadayken savaşa katılamazsınız."); return;
        }
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
            m().gonder(p, "savas-oyun-modu", "&cSavaşa sadece Survival/Adventure modunda katılabilirsiniz."); return;
        }
        s.katilimcilar.put(p.getUniqueId(), a.id);
        am().klanaGonder(a, "savas-hazir-oldu", "&a{oyuncu} savaşa hazır! &7({sayi}/{max})", "oyuncu", p.getName(), "sayi", s.katilimciSayisi(a.id), "max", maxKatilimci());
    }

    public void birak(Player p) {
        Savas s = katilimciSavasi(p.getUniqueId());
        if (s == null) { m().gonder(p, "savas-katilimci-degil", "&cBir savaşa katılmıyorsunuz."); return; }
        UUID klan = s.katilimcilar.remove(p.getUniqueId());
        if (s.durum == Savas.Durum.HAZIRLIK) {
            m().gonder(p, "savas-birakti-hazirlik", "&eSavaş katılımından vazgeçtiniz.");
            return;
        }
        // Aktif savaştan ayrılmak rakibe puan kazandırır
        s.puanEkle(s.rakip(klan), 1);
        geriGonder(p, s);
        savasaDuyur(s, "savas-ayrildi", "&e{oyuncu} savaştan ayrıldı, rakibe +1 puan.", "oyuncu", p.getName());
        kontrolEt(s);
    }

    public void durum(Player p) {
        Klan a = am().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        Savas s = klanSavasi.get(a.id);
        if (s == null) {
            m().gonder(p, "savas-durum-yok", "&7Klanınız şu an savaşta değil.");
            Map<UUID, Long> gelen = gelenTeklifler(a.id);
            if (!gelen.isEmpty()) m().gonder(p, "savas-durum-teklif", "&e{sayi} bekleyen düello teklifi var. &6/klan panel", "sayi", gelen.size());
            return;
        }
        Klan rakip = am().klanGetir(s.rakip(a.id));
        long simdi = System.currentTimeMillis();
        if (s.durum == Savas.Durum.HAZIRLIK) {
            m().gonder(p, "savas-durum-hazirlik", "&eHazırlık: {rakip} ile düello {sure} sonra. Hazır: {biz} - {onlar}",
                    "rakip", rakip != null ? rakip.isim : "?", "sure", Zaman.sure(s.hazirlikBitis - simdi),
                    "biz", s.katilimciSayisi(a.id), "onlar", s.katilimciSayisi(s.rakip(a.id)));
        } else {
            m().gonder(p, "savas-durum-aktif", "&c{rakip} ile savaştasınız! Skor: &e{biz} - {onlar} &7| Kalan: {sure}",
                    "rakip", rakip != null ? rakip.isim : "?", "biz", s.puan(a.id), "onlar", s.puan(s.rakip(a.id)), "sure", Zaman.sure(s.bitis - simdi));
        }
    }

    // ------------------------------------------------------------------ AKIŞ (saniyede bir)
    public void tick() {
        long simdi = System.currentTimeMillis();
        // Süresi dolan teklifler temizlenir
        for (Map<UUID, Long> t : teklifler.values()) t.values().removeIf(z -> simdi - z >= teklifGecerlilik());

        for (Savas s : new ArrayList<>(savaslar)) {
            if (s.durum == Savas.Durum.HAZIRLIK) {
                long kalan = (s.hazirlikBitis - simdi + 999) / 1000;
                if (kalan == 30 || kalan == 10 || (kalan <= 5 && kalan > 0)) {
                    for (UUID klan : new UUID[]{s.klanA, s.klanB}) {
                        Klan a = am().klanGetir(klan);
                        if (a != null) am().klanaGonder(a, "savas-geri-sayim", "&eSavaş {sure} saniye sonra başlıyor! Hazır: &a{sayi}",
                                "sure", kalan, "sayi", s.katilimciSayisi(klan));
                    }
                }
                if (simdi >= s.hazirlikBitis) baslat(s);
                continue;
            }

            // Oyundan çıkıp tolerans süresinde dönmeyenler savaştan düşer, rakibe +1
            for (Map.Entry<UUID, Long> e : new ArrayList<>(s.cikislar.entrySet())) {
                if (simdi - e.getValue() < cikisTolerans()) continue;
                UUID oyuncu = e.getKey();
                s.cikislar.remove(oyuncu);
                UUID klan = s.katilimcilar.remove(oyuncu);
                if (klan == null) continue;
                plugin.meslek().saglikMuafiyeti(oyuncu, false);
                s.puanEkle(s.rakip(klan), 1);
                savasaDuyur(s, "savas-cikis-dusuldu", "&e{oyuncu} geri dönmediği için savaştan düşürüldü, rakibe +1 puan.", "oyuncu", am().oyuncuAdi(oyuncu));
            }
            if (!kontrolEt(s)) continue;
            barGuncelle(s, simdi);
        }
    }

    private void baslat(Savas s) {
        Klan a = am().klanGetir(s.klanA), b = am().klanGetir(s.klanB);
        // Çevrimdışı olanlar katılımcı sayılmaz
        s.katilimcilar.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        if (a == null || b == null || s.katilimciSayisi(s.klanA) < minKatilimci() || s.katilimciSayisi(s.klanB) < minKatilimci()) {
            String sebep = m().metin("savas-iptal-katilim", "Yeterli katılım olmadı (her klandan en az {min} kişi)", "min", minKatilimci());
            bitir(s, null, "IPTAL", sebep);
            return;
        }
        if (!s.arena.hazir()) { bitir(s, null, "IPTAL", m().metin("savas-iptal-arena", "Arena kullanılamıyor")); return; }

        long simdi = System.currentTimeMillis();
        s.durum = Savas.Durum.AKTIF;
        s.baslangic = simdi;
        s.bitis = simdi + savasSuresi();
        s.bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);

        for (Map.Entry<UUID, UUID> e : s.katilimcilar.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            donusKonumlari.put(p.getUniqueId(), p.getLocation().clone());
            plugin.meslek().saglikMuafiyeti(p.getUniqueId(), true);
            isinla(p, takimSpawn(s, e.getValue()));
            plugin.buyuSavas().arenaSeviyeDusur(p); // Arena dengesi: üst seviye büyüler vanilla sınırına
            s.bar.addPlayer(p);
            p.showTitle(net.kyori.adventure.title.Title.title(
                    m().bilesen("savas-basladi-baslik", "&4&lSAVAŞ!"),
                    m().bilesen("savas-basladi-alt", "&e{hedef} puana ulaşan kazanır", "hedef", hedefPuan())));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1f);
        }
        plugin.veri().kaydet();
        barGuncelle(s, simdi);
        plugin.log().yaz(a, "-", "SAVAS_BASLADI", a.isim + " vs " + b.isim + " | " + s.katilimciSayisi(s.klanA) + " - " + s.katilimciSayisi(s.klanB) + " kişi");
    }

    private Location takimSpawn(Savas s, UUID klan) {
        return klan.equals(s.klanA) ? s.arena.spawn1 : s.arena.spawn2;
    }

    private void isinla(Player p, Location hedef) {
        izinliIsinlanma.add(p.getUniqueId());
        try {
            p.teleport(hedef);
        } finally {
            izinliIsinlanma.remove(p.getUniqueId());
        }
    }

    private void barGuncelle(Savas s, long simdi) {
        if (s.bar == null) return;
        Klan a = am().klanGetir(s.klanA), b = am().klanGetir(s.klanB);
        long kalan = Math.max(0, s.bitis - simdi);
        s.bar.setTitle(m().metin("savas-bar", "&6{a} &f{pa} &7- &f{pb} &6{b} &8| &7{dk}:{sn}",
                "a", a != null ? a.isim : "?", "b", b != null ? b.isim : "?", "pa", s.puanA, "pb", s.puanB,
                "dk", kalan / 60000, "sn", String.format("%02d", (kalan / 1000) % 60)));
        s.bar.setProgress(Math.max(0, Math.min(1, kalan / (double) savasSuresi())));
    }

    /** Puan/süre/katılımcı durumuna göre savaşı bitirir. Savaş devam ediyorsa true. */
    private boolean kontrolEt(Savas s) {
        if (s.durum != Savas.Durum.AKTIF) return true;
        if (s.puanA >= hedefPuan() || s.puanB >= hedefPuan()) {
            bitir(s, s.puanA > s.puanB ? s.klanA : s.klanB, "KAZANDI", m().metin("savas-sebep-puan", "Hedef puana ulaşıldı"));
            return false;
        }
        boolean aBos = s.aktifKatilimci(s.klanA) == 0 && s.katilimciSayisi(s.klanA) == 0;
        boolean bBos = s.aktifKatilimci(s.klanB) == 0 && s.katilimciSayisi(s.klanB) == 0;
        if (aBos || bBos) {
            UUID kazanan = aBos && bBos ? null : (aBos ? s.klanB : s.klanA);
            bitir(s, kazanan, kazanan == null ? "BERABERE" : "KAZANDI", m().metin("savas-sebep-kimse-kalmadi", "Rakip tarafta kimse kalmadı"));
            return false;
        }
        if (System.currentTimeMillis() >= s.bitis) {
            if (s.puanA == s.puanB) bitir(s, null, "BERABERE", m().metin("savas-sebep-sure", "Süre doldu"));
            else bitir(s, s.puanA > s.puanB ? s.klanA : s.klanB, "KAZANDI", m().metin("savas-sebep-sure", "Süre doldu"));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ BİTİŞ
    /** Savaşı sonlandırır. kazanan null ise berabere/iptal (ganimet yok). */
    public void bitir(Savas s, UUID kazanan, String sonuc, String sebep) {
        if (!savaslar.remove(s)) return;
        long simdi = System.currentTimeMillis();
        klanSavasi.remove(s.klanA);
        klanSavasi.remove(s.klanB);
        s.arena.kullanan = null;

        for (UUID oyuncu : new ArrayList<>(s.katilimcilar.keySet())) {
            Player p = Bukkit.getPlayer(oyuncu);
            if (p != null) geriGonder(p, s);
            else plugin.meslek().saglikMuafiyeti(oyuncu, false); // Dönüş konumu girişte uygulanır
        }
        if (s.bar != null) s.bar.removeAll();

        Klan a = am().klanGetir(s.klanA), b = am().klanGetir(s.klanB);
        // Kilit süresince aidat sayacı dondu: dönem başlangıcı kilit süresi kadar ileri alınır
        long kilitSuresi = simdi - s.kilitBaslangic;
        for (Klan x : new Klan[]{a, b}) if (x != null) x.aidatBaslangic += kilitSuresi;

        double para = 0;
        int esya = 0;
        int buyuGanimet = 0;
        if (kazanan != null && a != null && b != null) {
            Klan kazananKlan = kazanan.equals(a.id) ? a : b;
            Klan kaybeden = kazanan.equals(a.id) ? b : a;
            int fark = Math.abs(s.puanA - s.puanB);
            double oran = ganimetMin() + (ganimetMax() - ganimetMin()) * Math.min(1.0, fark / (double) Math.max(1, hedefPuan()));
            para = Math.floor(kaybeden.kasa * oran / 100.0 * 100.0) / 100.0;
            kaybeden.kasa = Para.kurus(kaybeden.kasa - para);
            kazananKlan.kasa = Para.kurus(kazananKlan.kasa + para);
            esya = plugin.esya().ganimetAktar(kaybeden, kazananKlan, oran);
            buyuGanimet = plugin.buyuSavas().ganimetAktar(kaybeden, kazananKlan, fark / (double) Math.max(1, hedefPuan()));
            kaybedenBekleme.put(kaybeden.id, simdi);
            plugin.log().yaz(kazananKlan, "-", "GANIMET", kaybeden.isim + " -> %" + Math.round(oran) + " | " + Para.yaz(para) + " + " + esya + " yığın eşya");
        }
        if (!"IPTAL".equals(sonuc) || s.durum == Savas.Durum.AKTIF) ciftBekleme.put(cift(s.klanA, s.klanB), simdi);

        String isimA = a != null ? a.isim : "?", isimB = b != null ? b.isim : "?";
        gecmis.add(0, new SavasKaydi(s.klanA, s.klanB, isimA, isimB, s.puanA, s.puanB, kazanan, sonuc, para, esya, buyuGanimet, simdi,
                s.baslangic > 0 ? simdi - s.baslangic : 0));
        while (gecmis.size() > GECMIS_MAX) gecmis.remove(gecmis.size() - 1);

        String sonucMetni = switch (sonuc) {
            case "KAZANDI" -> m().metin("savas-sonuc-kazandi", "&6&l{kazanan} KAZANDI! &7({pa} - {pb}) &aGanimet: {para} + {esya} yığın eşya + {buyu} büyülü eşya", "buyu", buyuGanimet,
                    "kazanan", kazanan.equals(s.klanA) ? isimA : isimB, "pa", s.puanA, "pb", s.puanB, "para", Para.yaz(para), "esya", esya);
            case "BERABERE" -> m().metin("savas-sonuc-berabere", "&eBERABERE! &7({pa} - {pb}) Kasalar değişmedi.", "pa", s.puanA, "pb", s.puanB);
            default -> m().metin("savas-sonuc-iptal", "&7Savaş iptal edildi.");
        };
        Bukkit.broadcastMessage(m().onek() + m().metin("savas-bitti", "&4⚔ {a} - {b}: ", "a", isimA, "b", isimB) + sonucMetni
                + m().metin("savas-sebep", " &8({sebep})", "sebep", sebep));
        plugin.log().yaz(a, "-", "SAVAS_BITTI", isimA + " " + s.puanA + " - " + s.puanB + " " + isimB + " | " + sonuc + " | " + sebep);
        plugin.veri().kaydet();
    }

    /** Oyuncuyu savaştan önceki konumuna döndürür, sağlık muafiyetini kaldırır. */
    private void geriGonder(Player p, Savas s) {
        if (s != null) {
            if (s.bar != null) s.bar.removePlayer(p);
            s.korumada.remove(p.getUniqueId());
        }
        Location donus = donusKonumlari.get(p.getUniqueId());
        if (donus == null) { // Henüz arenaya ışınlanmamıştı (hazırlık)
            plugin.meslek().saglikMuafiyeti(p.getUniqueId(), false);
            return;
        }
        if (p.isDead()) return;    // Konum saklı kalır; yeniden doğunca orada doğar (onRespawn)
        plugin.buyuSavas().arenaSeviyeGeriYukle(p);
        donusKonumlari.remove(p.getUniqueId());
        if (plugin.meslek().hapisteMi(p.getUniqueId())) {
            // Savaşta hapis cezası aldı: eski konumuna değil, Meslek hücresine gider (muafiyet kalkınca)
            plugin.meslek().saglikMuafiyeti(p.getUniqueId(), false);
            plugin.veri().kaydet();
            return;
        }
        if (donus.getWorld() == null) donus = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.setFireTicks(0);
        AttributeInstance can = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(can != null ? can.getValue() : 20.0);
        p.setFoodLevel(20);
        isinla(p, donus);
        plugin.meslek().saglikMuafiyeti(p.getUniqueId(), false); // Işınlandıktan sonra (Meslek hücreye alma ile çakışmasın)
        plugin.veri().kaydet();
    }

    /** Sunucu kapanırken: süren tüm savaşlar iptal edilir, herkes eski konumuna döner. */
    public void hepsiniIptalEt() {
        for (Savas s : new ArrayList<>(savaslar)) bitir(s, null, "IPTAL", m().metin("savas-sebep-kapanis", "Sunucu kapandı"));
    }

    public void adminBitir(CommandSender s, Klan a) {
        Savas savas = klanSavasi.get(a.id);
        if (savas == null) { m().gonder(s, "savas-yok-admin", "&c{klan} klanının süren bir savaşı yok.", "klan", a.isim); return; }
        bitir(savas, null, "IPTAL", m().metin("savas-sebep-admin", "Yönetim tarafından bitirildi ({oyuncu})", "oyuncu", s.getName()));
        m().gonder(s, "savas-admin-bitirildi", "&aSavaş bitirildi.");
    }

    private void savasaDuyur(Savas s, String anahtar, String varsayilan, Object... yt) {
        for (UUID klan : new UUID[]{s.klanA, s.klanB}) {
            Klan a = am().klanGetir(klan);
            if (a != null) am().klanaGonder(a, anahtar, varsayilan, yt);
        }
    }

    // ------------------------------------------------------------------ OLAYLAR
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player kurban = event.getPlayer();
        Savas s = katilimciSavasi(kurban.getUniqueId());
        if (s == null || s.durum != Savas.Durum.AKTIF) return;

        if (!esyaKaybi()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        UUID kurbanKlan = s.katilimcilar.get(kurban.getUniqueId());
        Player katil = kurban.getKiller();
        if (katil != null && s.katilimcilar.containsKey(katil.getUniqueId()) && !s.katilimcilar.get(katil.getUniqueId()).equals(kurbanKlan)) {
            long simdi = System.currentTimeMillis();
            long son = s.sonOlum.getOrDefault(kurban.getUniqueId(), 0L);
            if (simdi - son >= tekrarOldurme()) {
                s.sonOlum.put(kurban.getUniqueId(), simdi);
                s.puanEkle(s.rakip(kurbanKlan), 1);
                savasaDuyur(s, "savas-puan", "&c{katil} &7→ &f{kurban} &a(+1)", "katil", katil.getName(), "kurban", kurban.getName());
                katil.playSound(katil.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
            } else {
                m().gonder(katil, "savas-tekrar-oldurme", "&7Aynı oyuncuyu bu kadar kısa sürede tekrar öldürmek puan vermez.");
            }
        }
        // Ölüm ekranında beklemeden yeniden doğsun
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (kurban.isOnline() && kurban.isDead()) kurban.spigot().respawn();
        });
        kontrolEt(s);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Savas s = katilimciSavasi(p.getUniqueId());
        if (s != null && s.durum == Savas.Durum.AKTIF) {
            event.setRespawnLocation(takimSpawn(s, s.katilimcilar.get(p.getUniqueId())));
            s.korumada.add(p.getUniqueId());
            int sn = dogmaSaniye();
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, sn * 20, 0, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, sn * 20, 10, false, false));
                m().gonder(p, "savas-dogma-bekleme", "&7{sure} saniye sonra savaşa dönüyorsunuz...", "sure", sn);
            });
            Bukkit.getScheduler().runTaskLater(plugin, () -> s.korumada.remove(p.getUniqueId()), sn * 20L);
            return;
        }
        // Savaş, oyuncu ölüyken bittiyse eski konumunda doğar
        Location donus = s == null ? donusKonumlari.remove(p.getUniqueId()) : null;
        if (donus != null) {
            if (donus.getWorld() != null && !plugin.meslek().hapisteMi(p.getUniqueId())) event.setRespawnLocation(donus);
            plugin.meslek().saglikMuafiyeti(p.getUniqueId(), false);
            plugin.veri().kaydet();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        Savas s = katilimciSavasi(u);
        if (s == null) return;
        if (s.durum == Savas.Durum.HAZIRLIK) {
            s.katilimcilar.remove(u);
            return;
        }
        s.cikislar.put(u, System.currentTimeMillis());
        savasaDuyur(s, "savas-oyuncu-cikti", "&7{oyuncu} oyundan çıktı. {sure} saniye içinde dönmezse savaştan düşecek.",
                "oyuncu", event.getPlayer().getName(), "sure", cikisTolerans() / 1000);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Savas s = katilimciSavasi(p.getUniqueId());
        if (s != null && s.durum == Savas.Durum.AKTIF && s.cikislar.remove(p.getUniqueId()) != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                isinla(p, takimSpawn(s, s.katilimcilar.get(p.getUniqueId())));
                if (s.bar != null) s.bar.addPlayer(p);
                m().gonder(p, "savas-geri-dondun", "&aSavaşa geri döndünüz!");
            });
            return;
        }
        // Savaş, oyuncu çevrimdışıyken bittiyse eski konumuna döner
        if (s == null && donusKonumlari.containsKey(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                Location donus = donusKonumlari.remove(p.getUniqueId());
                // Hapse düştüyse Meslek zaten hücreye alır; eski konuma ışınlanmaz
                if (donus != null && donus.getWorld() != null && !plugin.meslek().hapisteMi(p.getUniqueId())) isinla(p, donus);
                plugin.meslek().saglikMuafiyeti(p.getUniqueId(), false);
                plugin.veri().kaydet();
                m().gonder(p, "savas-donus", "&7Siz yokken savaş bitti, eski konumunuza döndünüz.");
            });
        }
    }

    private static Player saldiran(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player kurban)) return;
        Player saldiran = saldiran(event);
        Savas ks = katilimciSavasi(kurban.getUniqueId());
        Savas ss = saldiran == null ? null : katilimciSavasi(saldiran.getUniqueId());
        if (ks == null && ss == null) return;
        if (saldiran == null) {
            if (ks != null && ks.korumada.contains(kurban.getUniqueId())) event.setCancelled(true);
            return;
        }
        boolean ayniSavas = ks != null && ks == ss && ks.durum == Savas.Durum.AKTIF;
        if (!ayniSavas) {
            // Savaştakiler dışarıdakilere, dışarıdakiler savaştakilere vuramaz
            if ((ks != null && ks.durum == Savas.Durum.AKTIF) || (ss != null && ss.durum == Savas.Durum.AKTIF)) event.setCancelled(true);
            return;
        }
        if (ks.korumada.contains(kurban.getUniqueId()) || ks.korumada.contains(saldiran.getUniqueId())) { event.setCancelled(true); return; }
        if (!dostAtes() && ks.katilimcilar.get(kurban.getUniqueId()).equals(ks.katilimcilar.get(saldiran.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (savaslar.isEmpty()) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = event.getPlayer();
        Savas s = katilimciSavasi(p.getUniqueId());
        if (s != null && s.durum == Savas.Durum.AKTIF) {
            if (!s.arena.icinde(to)) {
                event.setTo(from);
                p.sendActionBar(m().bilesen("savas-arena-disi", "&cSavaş bitmeden arenadan çıkamazsınız!"));
            }
            return;
        }
        if (p.hasPermission("klan.admin")) return;
        for (Savas x : savaslar) {
            if (x.durum == Savas.Durum.AKTIF && x.arena.icinde(to) && !x.arena.icinde(from)) {
                event.setTo(from);
                p.sendActionBar(m().bilesen("savas-arena-giris", "&cBu arenada savaş sürüyor, giremezsiniz!"));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (savaslar.isEmpty() || izinliIsinlanma.contains(event.getPlayer().getUniqueId())) return;
        Player p = event.getPlayer();
        Savas s = katilimciSavasi(p.getUniqueId());
        if (s != null && s.durum == Savas.Durum.AKTIF) {
            if (!s.arena.icinde(event.getTo())) {
                event.setCancelled(true);
                m().gonder(p, "savas-isinlanma", "&cSavaş sürerken arenadan ışınlanamazsınız!");
            }
            return;
        }
        if (p.hasPermission("klan.admin")) return;
        for (Savas x : savaslar) {
            if (x.durum == Savas.Durum.AKTIF && x.arena.icinde(event.getTo())) {
                event.setCancelled(true);
                m().gonder(p, "savas-arena-giris-isinlanma", "&cSavaş süren bir arenaya ışınlanamazsınız!");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Savas s = katilimciSavasi(event.getPlayer().getUniqueId());
        if (s == null || s.durum != Savas.Durum.AKTIF) return;
        String govde = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        String komut = govde.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        int onek = komut.lastIndexOf(':');
        if (onek >= 0) komut = komut.substring(onek + 1);
        for (String yasak : yasakKomutlar()) {
            if (yasak.equalsIgnoreCase(komut)) {
                event.setCancelled(true);
                m().gonder(event.getPlayer(), "savas-komut-yasak", "&cSavaş sırasında bu komut kullanılamaz!");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission("klan.admin") && plugin.arenalar().arenaAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission("klan.admin") && plugin.arenalar().arenaAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Ana menü / panel için kısa durum metni. */
    public List<String> durumSatirlari(Klan a) {
        List<String> satirlar = new ArrayList<>();
        long simdi = System.currentTimeMillis();
        Savas s = klanSavasi.get(a.id);
        if (s != null) {
            Klan rakip = am().klanGetir(s.rakip(a.id));
            String rakipAdi = rakip != null ? rakip.isim : "?";
            if (s.durum == Savas.Durum.HAZIRLIK) {
                satirlar.add(Mesaj.renk("&eHazırlık: &f" + rakipAdi + " &7(" + Zaman.sure(s.hazirlikBitis - simdi) + ")"));
                satirlar.add(Mesaj.renk("&7Hazır: &a" + s.katilimciSayisi(a.id) + " &7- &c" + s.katilimciSayisi(s.rakip(a.id))));
            } else {
                satirlar.add(Mesaj.renk("&cSavaşta: &f" + rakipAdi));
                satirlar.add(Mesaj.renk("&7Skor: &e" + s.puan(a.id) + " - " + s.puan(s.rakip(a.id))));
            }
            satirlar.add(Mesaj.renk("&cKasa kilitli"));
            return satirlar;
        }
        satirlar.add(Mesaj.renk("&7Şu an savaşta değil."));
        long koruma = a.kurulus + plugin.ayar().yeniKlanKorumaMs() - simdi;
        if (koruma > 0) satirlar.add(Mesaj.renk("&bYeni klan koruması: " + Zaman.sure(koruma)));
        Long kayip = kaybedenBekleme.get(a.id);
        if (kayip != null && simdi - kayip < kaybedenBeklemeMs()) satirlar.add(Mesaj.renk("&cYenilgi beklemesi: " + Zaman.sure(kayip + kaybedenBeklemeMs() - simdi)));
        int gelen = gelenTeklifler(a.id).size();
        if (gelen > 0) satirlar.add(Mesaj.renk("&e" + gelen + " bekleyen düello teklifi"));
        return satirlar;
    }

    /** Hazırlıktaki savaşa tıklayarak katılma (ana menü). */
    public boolean hazirliktaMi(UUID klan) {
        Savas s = klanSavasi.get(klan);
        return s != null && s.durum == Savas.Durum.HAZIRLIK;
    }
}
