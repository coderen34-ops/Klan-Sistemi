package me.klansistemi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Klanların bellekteki kaydı ve tüm üyelik/kasa işlemleri.
 * Tüm metotlar ana thread'den çağrılır. Her değişiklikten sonra veri asenkron kaydedilir.
 */
public class KlanManager {

    private final KlanSistemi plugin;

    private final Map<UUID, Klan> klanlar = new HashMap<>();
    private final Map<String, UUID> isimIndeksi = new HashMap<>();   // normalize isim -> klan id
    private final Map<UUID, UUID> oyuncuKlani = new HashMap<>();    // oyuncu -> klan id
    private final Map<UUID, Long> ayrilmaBekleme = new HashMap<>();  // oyuncu -> başka klana katılabileceği an
    private final Map<UUID, Map<UUID, Long>> davetler = new HashMap<>(); // oyuncu -> (klan id -> davetin bitişi)
    private final Set<UUID> sohbetModu = ConcurrentHashMap.newKeySet();   // klan sohbeti açık oyuncular

    public KlanManager(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private Ayarlar ayar() { return plugin.ayar(); }

    // ------------------------------------------------------------------ SORGULAR
    public Collection<Klan> klanlar() { return Collections.unmodifiableCollection(klanlar.values()); }

    public Map<UUID, Long> ayrilmaBeklemeleri() { return ayrilmaBekleme; }

    public Klan klanBul(String isim) {
        UUID id = isimIndeksi.get(anahtar(isim));
        return id == null ? null : klanlar.get(id);
    }

    public Klan klanGetir(UUID id) { return klanlar.get(id); }

    public Klan oyuncununKlani(UUID oyuncu) {
        UUID id = oyuncuKlani.get(oyuncu);
        return id == null ? null : klanlar.get(id);
    }

    public KlanUyesi uye(UUID oyuncu) {
        Klan a = oyuncununKlani(oyuncu);
        return a == null ? null : a.uyeler.get(oyuncu);
    }

    public boolean sohbetAcikMi(UUID oyuncu) { return sohbetModu.contains(oyuncu); }

    /** Türkçe büyük/küçük harf farkını yok sayan isim anahtarı (İzmir = izmir = IZMIR). */
    public static String anahtar(String isim) {
        return isim.toLowerCase(Locale.ROOT).replace("̇", "").replace('ı', 'i');
    }

    public void yuklenenKlaniEkle(Klan a) {
        klanlar.put(a.id, a);
        isimIndeksi.put(anahtar(a.isim), a.id);
        for (UUID u : a.uyeler.keySet()) oyuncuKlani.put(u, a.id);
    }

    private void kaydet() { plugin.veri().kaydet(); }

    /** Klanın çevrimiçi üyelerine mesaj. */
    public void klanaGonder(Klan a, String anahtar, String varsayilan, Object... yt) {
        for (KlanUyesi u : a.uyeler.values()) {
            Player p = Bukkit.getPlayer(u.uuid);
            if (p != null) m().gonder(p, anahtar, varsayilan, yt);
        }
    }

    private long beklemeKalan(UUID oyuncu) {
        Long bitis = ayrilmaBekleme.get(oyuncu);
        if (bitis == null) return 0;
        long kalan = bitis - System.currentTimeMillis();
        if (kalan <= 0) { ayrilmaBekleme.remove(oyuncu); return 0; }
        return kalan;
    }

    /** Savaş sırasında kasa ve üyelik işlemleri kilitlidir. Kilitliyse oyuncuya bildirir. */
    private boolean kilitli(Player p, Klan a) {
        if (!plugin.kasaKilitliMi(a)) return false;
        m().gonder(p, "savas-kilit", "&cKlanınız savaşta! Savaş bitene kadar kasa, ayrılma ve üye atma kilitlidir.");
        ses(p, false);
        return true;
    }

    private static void ses(Player p, boolean basarili) {
        p.playSound(p.getLocation(), basarili ? Sound.ENTITY_VILLAGER_YES : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    // ------------------------------------------------------------------ İSİM KONTROLÜ
    /** Geçerliyse null, değilse oyuncuya gösterilecek hata metni. */
    public String isimHatasi(String isim) {
        if (isim.length() < ayar().isimMin() || isim.length() > ayar().isimMax()) {
            return m().metin("isim-uzunluk", "&cKlan adı {min}-{max} karakter olmalı.", "min", ayar().isimMin(), "max", ayar().isimMax());
        }
        if (!isim.matches("[A-Za-z0-9_ÇĞİÖŞÜçğıöşü]+")) {
            return m().metin("isim-karakter", "&cKlan adında boşluk ve özel karakter olamaz (harf, rakam ve _ kullanın).");
        }
        String norm = anahtar(isim);
        for (String kelime : ayar().yasakliKelimeler()) {
            if (!kelime.isBlank() && norm.contains(anahtar(kelime))) {
                return m().metin("isim-yasakli", "&cBu klan adı uygunsuz bir ifade içeriyor.");
            }
        }
        if (isimIndeksi.containsKey(norm)) {
            return m().metin("isim-alinmis", "&cBu isimde bir klan zaten var.");
        }
        return null;
    }

    // ------------------------------------------------------------------ KURMA
    public void kur(Player p, String isim) {
        UUID pId = p.getUniqueId();
        if (oyuncuKlani.containsKey(pId)) { m().gonder(p, "zaten-klanda", "&cZaten bir klandasınız."); ses(p, false); return; }
        long kalan = beklemeKalan(pId);
        if (kalan > 0) {
            m().gonder(p, "ayrilma-bekleme", "&cKlandan ayrıldığınız için {sure} boyunca yeni bir klana giremezsiniz.", "sure", Zaman.sure(kalan));
            ses(p, false); return;
        }
        String hata = isimHatasi(isim);
        if (hata != null) { p.sendMessage(m().onek() + hata); ses(p, false); return; }

        double ucret = ayar().kurmaUcreti();
        if (!plugin.meslek().bankadanCek(pId, ucret)) {
            m().gonder(p, "kurma-bakiye", "&cKlan kurmak için banka hesabınızda {ucret} olmalı.", "ucret", Para.yaz(ucret));
            ses(p, false); return;
        }

        long simdi = System.currentTimeMillis();
        Klan a = new Klan(UUID.randomUUID(), isim, simdi);
        a.aidatMiktari = ayar().aidatVarsayilan();
        a.aidatBaslangic = simdi;
        KlanUyesi lider = new KlanUyesi(pId, p.getName(), Rol.LIDER, simdi);
        plugin.aidat().yeniUye(a, lider, simdi);
        a.uyeler.put(pId, lider);
        yuklenenKlaniEkle(a);
        davetler.remove(pId);
        kaydet();

        plugin.log().yaz(a, p.getName(), "KLAN_KURULDU", "ücret " + Para.yaz(ucret));
        plugin.etiket().guncelle(a, lider);
        m().gonder(p, "kuruldu", "&a{klan} klanı kuruldu! Artık klanın &6Lideri&a sizsiniz. ({ucret} bankanızdan çekildi)",
                "klan", isim, "ucret", Para.yaz(ucret));
        Bukkit.broadcastMessage(m().onek() + m().metin("kuruldu-duyuru", "&e{oyuncu} &7yeni bir klan kurdu: &6{klan}", "oyuncu", p.getName(), "klan", isim));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    // ------------------------------------------------------------------ DAVET / KATILMA
    public void davetEt(Player p, Player hedef) {
        Klan a = oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        if (ben.rol == Rol.UYE) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); ses(p, false); return; }
        if (hedef.getUniqueId().equals(p.getUniqueId())) return;
        if (oyuncuKlani.containsKey(hedef.getUniqueId())) {
            m().gonder(p, "hedef-klanda", "&c{oyuncu} zaten bir klanda.", "oyuncu", hedef.getName()); return;
        }
        if (a.uyeler.size() >= ayar().maxUye()) {
            m().gonder(p, "klan-dolu", "&cKlanınız dolu! (En fazla {max} üye)", "max", ayar().maxUye()); return;
        }

        long bitis = System.currentTimeMillis() + ayar().davetGecerlilikMs();
        davetler.computeIfAbsent(hedef.getUniqueId(), k -> new HashMap<>()).put(a.id, bitis);

        m().gonder(p, "davet-gonderildi", "&a{oyuncu} adlı oyuncuya klan daveti gönderildi.", "oyuncu", hedef.getName());
        Component buton = m().bilesen("davet-buton", "&a&l[KATIL]")
                .clickEvent(ClickEvent.runCommand("/klan katil " + a.isim))
                .hoverEvent(HoverEvent.showText(m().bilesen("davet-buton-ipucu", "&7{klan} klanına katıl", "klan", a.isim)));
        hedef.sendMessage(m().onekBileseni()
                .append(m().bilesen("davet-alindi", "&e{oyuncu} &7sizi &6{klan} &7klanına davet etti. ", "oyuncu", p.getName(), "klan", a.isim))
                .append(buton));
        hedef.playSound(hedef.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
    }

    public void katil(Player p, String klanIsmi) {
        UUID pId = p.getUniqueId();
        if (oyuncuKlani.containsKey(pId)) { m().gonder(p, "zaten-klanda", "&cZaten bir klandasınız."); return; }
        Klan a = klanBul(klanIsmi);
        if (a == null) { m().gonder(p, "klan-yok", "&cBöyle bir klan bulunamadı."); return; }

        Map<UUID, Long> benimDavetlerim = davetler.get(pId);
        Long davetBitis = benimDavetlerim == null ? null : benimDavetlerim.get(a.id);
        if (davetBitis == null || davetBitis < System.currentTimeMillis()) {
            if (benimDavetlerim != null) benimDavetlerim.remove(a.id);
            m().gonder(p, "davet-yok", "&cBu klandan geçerli bir davetiniz yok."); ses(p, false); return;
        }
        long kalan = beklemeKalan(pId);
        if (kalan > 0) {
            m().gonder(p, "ayrilma-bekleme", "&cKlandan ayrıldığınız için {sure} boyunca yeni bir klana giremezsiniz.", "sure", Zaman.sure(kalan));
            ses(p, false); return;
        }
        if (a.uyeler.size() >= ayar().maxUye()) {
            m().gonder(p, "katilma-dolu", "&cBu klan dolu."); return;
        }
        if (plugin.kasaKilitliMi(a)) { m().gonder(p, "katilma-savasta", "&cBu klan şu an savaşta, savaş bitince tekrar deneyin."); return; }

        long simdi = System.currentTimeMillis();
        KlanUyesi u = new KlanUyesi(pId, p.getName(), Rol.UYE, simdi);
        plugin.aidat().yeniUye(a, u, simdi);
        a.uyeler.put(pId, u);
        oyuncuKlani.put(pId, a.id);
        davetler.remove(pId);
        kaydet();

        plugin.log().yaz(a, p.getName(), "KATILDI", null);
        plugin.etiket().guncelle(a, u);
        klanaGonder(a, "katildi", "&a{oyuncu} klana katıldı!", "oyuncu", p.getName());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    // ------------------------------------------------------------------ AYRILMA / ATMA
    public void ayril(Player p, boolean onay) {
        UUID pId = p.getUniqueId();
        Klan a = oyuncununKlani(pId);
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (kilitli(p, a)) return;
        KlanUyesi ben = a.uyeler.get(pId);

        if (ben.rol == Rol.LIDER) {
            List<KlanUyesi> yardimcilar = a.yardimcilar();
            if (yardimcilar.isEmpty()) {
                // Halef yok: klan dağılır. Bu yüzden onay istenir.
                if (!onay) {
                    m().gonder(p, "ayril-dagilma-uyari", "&cKlanınızda yardımcı olmadığı için ayrılırsanız klan DAĞILIR ve kasa üyelere bölünür. Onaylamak için: &e/klan ayril onayla");
                    return;
                }
                plugin.log().yaz(a, p.getName(), "LIDER_AYRILDI", "halef yok, klan dağıldı");
                klaniDagit(a, m().metin("dagildi-lider-ayrildi", "Lider klandan ayrıldı"));
                beklemeBaslat(pId);
                kaydet();
                return;
            }
            KlanUyesi yeniLider = yardimcilar.get(0);
            yeniLider.rol = Rol.LIDER;
            uyeyiCikar(a, pId);
            a.kidemleriDuzenle();
            plugin.aidat().rolDegisti(a, yeniLider);
            kaydet();
            plugin.log().yaz(a, p.getName(), "LIDER_AYRILDI", "yeni lider " + yeniLider.isim);
            plugin.etiket().kaldir(p.getName());
            plugin.etiket().klandakileriGuncelle(a); // Kıdemler/roller değişti
            m().gonder(p, "ayrildin", "&e{klan} klanından ayrıldınız.", "klan", a.isim);
            klanaGonder(a, "lider-devri", "&6{eski} klandan ayrıldı. Yeni Lider: &e{yeni}", "eski", p.getName(), "yeni", yeniLider.isim);
            return;
        }

        uyeyiCikar(a, pId);
        a.kidemleriDuzenle();
        kaydet();
        plugin.log().yaz(a, p.getName(), "AYRILDI", null);
        plugin.etiket().kaldir(p.getName());
        m().gonder(p, "ayrildin", "&e{klan} klanından ayrıldınız.", "klan", a.isim);
        klanaGonder(a, "uye-ayrildi", "&e{oyuncu} klandan ayrıldı.", "oyuncu", p.getName());
    }

    public void at(Player p, String hedefIsim) {
        Klan a = oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (kilitli(p, a)) return;
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        KlanUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null) { m().gonder(p, "uye-bulunamadi", "&cKlanınızda bu isimde bir üye yok."); return; }
        if (hedef.uuid.equals(p.getUniqueId())) { m().gonder(p, "kendini-atamaz", "&cKendinizi atamazsınız, /klan ayril kullanın."); return; }

        boolean yetkili = ben.rol == Rol.LIDER || (ben.rol == Rol.YARDIMCI && hedef.rol == Rol.UYE);
        if (!yetkili) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); ses(p, false); return; }

        uyeyiAt(a, hedef, p.getName());
    }

    /** Üyeyi klandan atar (komut ya da aidat sistemi). Lider atılamaz. */
    public void uyeyiAt(Klan a, KlanUyesi hedef, String atan) {
        if (hedef.rol == Rol.LIDER) return;
        uyeyiCikar(a, hedef.uuid);
        a.kidemleriDuzenle();
        kaydet();
        plugin.log().yaz(a, atan, "ATTI", hedef.isim);
        plugin.etiket().kaldir(hedef.isim);
        klanaGonder(a, "uye-atildi", "&c{oyuncu} klandan atıldı. &7({atan})", "oyuncu", hedef.isim, "atan", atan);
        Player hp = Bukkit.getPlayer(hedef.uuid);
        if (hp != null) {
            m().gonder(hp, "atildin", "&c{klan} klanından atıldınız. &7({atan})", "klan", a.isim, "atan", atan);
            ses(hp, false);
        }
    }

    /** Klan menüsü, eşya kasası ya da diplomasi paneli açıksa kapatır (yetkisi kalmayan kullanamasın). */
    public void menuleriKapat(UUID oyuncu) {
        Player p = Bukkit.getPlayer(oyuncu);
        if (p == null) return;
        Object sahip = p.getOpenInventory().getTopInventory().getHolder();
        if (sahip instanceof me.klansistemi.gui.KlanMenu.Sahip || sahip instanceof EsyaKasasi.Sahip
                || sahip instanceof me.klansistemi.gui.DiplomasiPanel.Sahip) {
            p.closeInventory();
        }
    }

    private void uyeyiCikar(Klan a, UUID oyuncu) {
        menuleriKapat(oyuncu);
        a.uyeler.remove(oyuncu);
        oyuncuKlani.remove(oyuncu);
        sohbetModu.remove(oyuncu);
        beklemeBaslat(oyuncu);
    }

    private void beklemeBaslat(UUID oyuncu) {
        long bekleme = ayar().ayrilmaBeklemeMs();
        if (bekleme > 0) ayrilmaBekleme.put(oyuncu, System.currentTimeMillis() + bekleme);
    }

    public KlanUyesi uyeBulIsimle(Klan a, String isim) {
        for (KlanUyesi u : a.uyeler.values()) if (u.isim.equalsIgnoreCase(isim)) return u;
        return null;
    }

    // ------------------------------------------------------------------ ROLLER / KIDEM
    public void terfi(Player p, String hedefIsim) {
        Klan a = liderinKlani(p);
        if (a == null) return;
        KlanUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null) { m().gonder(p, "uye-bulunamadi", "&cKlanınızda bu isimde bir üye yok."); return; }
        if (hedef.rol != Rol.UYE) { m().gonder(p, "terfi-olmaz", "&cSadece Üye rolündekiler Yardımcı yapılabilir."); return; }
        hedef.rol = Rol.YARDIMCI;
        hedef.kidem = a.yardimcilar().size() + 1; // En sona eklenir
        a.kidemleriDuzenle();
        plugin.aidat().rolDegisti(a, hedef);
        kaydet();
        plugin.log().yaz(a, p.getName(), "TERFI", hedef.isim + " -> Yardımcı (kıdem " + hedef.kidem + ")");
        plugin.etiket().guncelle(a, hedef);
        klanaGonder(a, "terfi-edildi", "&a{oyuncu} &eYardımcı&a oldu! (Kıdem sırası: {kidem})", "oyuncu", hedef.isim, "kidem", hedef.kidem);
    }

    public void indir(Player p, String hedefIsim) {
        Klan a = liderinKlani(p);
        if (a == null) return;
        KlanUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null) { m().gonder(p, "uye-bulunamadi", "&cKlanınızda bu isimde bir üye yok."); return; }
        if (hedef.rol != Rol.YARDIMCI) { m().gonder(p, "indir-olmaz", "&cSadece Yardımcılar Üye'ye indirilebilir."); return; }
        hedef.rol = Rol.UYE;
        a.kidemleriDuzenle();
        kaydet();
        plugin.log().yaz(a, p.getName(), "RUTBE_INDIRDI", hedef.isim + " -> Üye");
        plugin.etiket().guncelle(a, hedef);
        klanaGonder(a, "indirildi", "&e{oyuncu} artık Üye.", "oyuncu", hedef.isim);
    }

    /** Lider yardımcının kıdem sırasını belirler (1. sıra lider ayrılırsa yerine geçer). */
    public void kidem(Player p, String hedefIsim, int sira) {
        Klan a = liderinKlani(p);
        if (a == null) return;
        KlanUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null || hedef.rol != Rol.YARDIMCI) {
            m().gonder(p, "kidem-sadece-yardimci", "&cKıdem sadece Yardımcılara verilir."); return;
        }
        List<KlanUyesi> liste = new ArrayList<>(a.yardimcilar());
        sira = Math.max(1, Math.min(sira, liste.size()));
        liste.remove(hedef);
        liste.add(sira - 1, hedef);
        for (int i = 0; i < liste.size(); i++) liste.get(i).kidem = i + 1;
        kaydet();
        plugin.log().yaz(a, p.getName(), "KIDEM", hedef.isim + " -> " + sira);
        StringBuilder sb = new StringBuilder();
        for (KlanUyesi y : liste) sb.append(y.kidem).append(". ").append(y.isim).append("  ");
        m().gonder(p, "kidem-ayarlandi", "&aKıdem sırası güncellendi: &e{liste}", "liste", sb.toString().trim());
    }

    private Klan liderinKlani(Player p) {
        Klan a = oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return null; }
        if (a.uyeler.get(p.getUniqueId()).rol != Rol.LIDER) {
            m().gonder(p, "sadece-lider", "&cBunu sadece klan Lideri yapabilir."); ses(p, false); return null;
        }
        return a;
    }

    // ------------------------------------------------------------------ KASA
    public void yatir(Player p, double miktar) {
        Klan a = oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (miktar <= 0) { m().gonder(p, "gecersiz-miktar", "&cGeçerli bir miktar yazın."); return; }
        if (kilitli(p, a)) return;
        double bosluk = ayar().kasaUstSinir() - a.kasa;
        if (miktar > bosluk) {
            m().gonder(p, "kasa-ust-sinir", "&cKasa üst sınırı {sinir}. En fazla {bosluk} yatırabilirsiniz.",
                    "sinir", Para.yaz(ayar().kasaUstSinir()), "bosluk", Para.yaz(Math.max(0, bosluk)));
            return;
        }
        if (!plugin.meslek().bankadanCek(p.getUniqueId(), miktar)) {
            m().gonder(p, "banka-yetersiz", "&cBanka hesabınızda yeterli bakiye yok."); ses(p, false); return;
        }
        a.kasa = Para.kurus(a.kasa + miktar);
        kaydet();
        plugin.log().yaz(a, p.getName(), "YATIRDI", Para.yaz(miktar) + " (kasa: " + Para.yaz(a.kasa) + ")");
        m().gonder(p, "yatirildi", "&a{miktar} klan kasasına yatırıldı. Kasa: &e{kasa}", "miktar", Para.yaz(miktar), "kasa", Para.yaz(a.kasa));
        ses(p, true);
    }

    public void cek(Player p, double miktar) {
        Klan a = oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        KlanUyesi ben = a.uyeler.get(p.getUniqueId());
        if (miktar <= 0) { m().gonder(p, "gecersiz-miktar", "&cGeçerli bir miktar yazın."); return; }
        if (kilitli(p, a)) return;
        if (ben.rol == Rol.UYE) { m().gonder(p, "cek-yetki-yok", "&cÜyeler kasadan para çekemez, sadece yatırabilir."); ses(p, false); return; }
        if (ben.borclu) {
            m().gonder(p, "cek-borclu", "&cAidat borcunuz olduğu için kasadan para çekemezsiniz. Önce &e/klan aidat ode&c."); ses(p, false); return;
        }
        if (miktar > a.kasa) { m().gonder(p, "kasa-yetersiz", "&cKasada yeterli para yok. Kasa: {kasa}", "kasa", Para.yaz(a.kasa)); return; }

        long bugun = LocalDate.now().toEpochDay();
        if (ben.rol == Rol.YARDIMCI) {
            if (ben.cekimGunu != bugun) { ben.cekimGunu = bugun; ben.gunlukCekilen = 0; }
            double kalanLimit = ayar().yardimciGunlukLimit() - ben.gunlukCekilen;
            if (miktar > kalanLimit) {
                m().gonder(p, "cek-limit", "&cGünlük çekim limitiniz doldu. Bugün en fazla {kalan} daha çekebilirsiniz.", "kalan", Para.yaz(Math.max(0, kalanLimit)));
                return;
            }
        }
        if (!plugin.meslek().bankayaYatir(p.getUniqueId(), miktar)) return;
        a.kasa = Para.kurus(a.kasa - miktar);
        if (ben.rol == Rol.YARDIMCI) ben.gunlukCekilen = Para.kurus(ben.gunlukCekilen + miktar);
        kaydet();
        plugin.log().yaz(a, p.getName(), "CEKTI", Para.yaz(miktar) + " (kasa: " + Para.yaz(a.kasa) + ")");
        m().gonder(p, "cekildi", "&a{miktar} kasadan banka hesabınıza aktarıldı. Kasa: &e{kasa}", "miktar", Para.yaz(miktar), "kasa", Para.yaz(a.kasa));
        ses(p, true);
    }

    // ------------------------------------------------------------------ DAĞITMA
    public void dagit(Player p, boolean onay) {
        Klan a = liderinKlani(p);
        if (a == null) return;
        if (kilitli(p, a)) return;
        if (!onay) {
            m().gonder(p, "dagit-uyari", "&cKlanı dağıtmak üzeresiniz! Kasa ({kasa}) üyelere eşit bölünecek. Onaylamak için: &e/klan dagit onayla",
                    "kasa", Para.yaz(a.kasa));
            return;
        }
        plugin.log().yaz(a, p.getName(), "DAGITTI", null);
        klaniDagit(a, m().metin("dagildi-lider", "Lider klanı dağıttı"));
        kaydet();
    }

    /** Klanı kaldırır; kasadaki para üyelerin banka hesaplarına eşit bölünür (çevrimdışı üyeler dahil). */
    public void klaniDagit(Klan a, String sebep) {
        List<KlanUyesi> uyeler = new ArrayList<>(a.uyeler.values());
        if (!uyeler.isEmpty() && a.kasa > 0) {
            double pay = Math.floor(a.kasa / uyeler.size() * 100.0) / 100.0;
            double artan = Para.kurus(a.kasa - pay * uyeler.size());
            KlanUyesi lider = a.lider();
            for (KlanUyesi u : uyeler) {
                double odenecek = pay + (u == lider ? artan : 0);
                if (odenecek > 0) {
                    plugin.meslek().bankayaYatir(u.uuid, odenecek);
                    plugin.log().yaz(a, u.isim, "DAGILMA_PAYI", Para.yaz(odenecek));
                }
            }
            klanaGonder(a, "dagilma-payi", "&eKlan kasasından payınıza düşen {pay} banka hesabınıza yatırıldı.", "pay", Para.yaz(pay));
        }
        klanaGonder(a, "dagildi", "&c{klan} klanı dağıldı. &7({sebep})", "klan", a.isim, "sebep", sebep);
        for (KlanUyesi u : uyeler) menuleriKapat(u.uuid);
        plugin.uzmanlik().klanDagildi(a);
        plugin.esya().dagit(a, uyeler);
        plugin.iliski().klanSilindi(a.id);

        for (KlanUyesi u : uyeler) {
            oyuncuKlani.remove(u.uuid);
            sohbetModu.remove(u.uuid);
            plugin.etiket().kaldir(u.isim);
        }
        a.uyeler.clear();
        a.kasa = 0;
        klanlar.remove(a.id);
        isimIndeksi.remove(anahtar(a.isim));
        for (Klan diger : klanlar.values()) diger.prestijRakip.remove(a.id);
        for (Map<UUID, Long> d : davetler.values()) d.remove(a.id);
        plugin.log().yaz(a, "-", "KLAN_DAGILDI", sebep);
    }

    // ------------------------------------------------------------------ SOHBET
    public void sohbetDegistir(Player p) {
        if (oyuncununKlani(p.getUniqueId()) == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (sohbetModu.remove(p.getUniqueId())) {
            m().gonder(p, "sohbet-kapandi", "&eKlan sohbeti kapatıldı. Mesajlarınız genel sohbete gidiyor.");
        } else {
            sohbetModu.add(p.getUniqueId());
            m().gonder(p, "sohbet-acildi", "&aKlan sohbeti açıldı. Mesajlarınız sadece klanınıza gidiyor. Kapatmak için tekrar &e/klan sohbet");
        }
    }

    /** Klan sohbetine mesaj gönderir (ana thread). */
    public void klanSohbeti(Player p, String mesaj) {
        Klan a = oyuncununKlani(p.getUniqueId());
        if (a == null) { sohbetModu.remove(p.getUniqueId()); m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        KlanUyesi u = a.uyeler.get(p.getUniqueId());
        String satir = m().metin("sohbet-format", "&8[&6{klan}&8] {rol}&r {oyuncu}&7: &f{mesaj}",
                "klan", a.isim, "rol", u.rol.renkliAd(), "oyuncu", p.getName(), "mesaj", "\u0000");
        // Oyuncu mesajındaki & kodları renge çevrilmesin
        satir = satir.replace("\u0000", mesaj);
        for (KlanUyesi uye : a.uyeler.values()) {
            Player alici = Bukkit.getPlayer(uye.uuid);
            if (alici != null) alici.sendMessage(satir);
        }
        plugin.getLogger().info("[Klan Sohbeti] [" + a.isim + "] " + p.getName() + ": " + mesaj);
    }

    // ------------------------------------------------------------------ YÖNETİCİ
    public void adminKasaDuzelt(CommandSender s, Klan a, double yeniKasa) {
        double eski = a.kasa;
        a.kasa = Para.kurus(Math.max(0, yeniKasa));
        kaydet();
        plugin.log().yaz(a, s.getName(), "ADMIN_KASA_DUZELT", Para.yaz(eski) + " -> " + Para.yaz(a.kasa));
        m().gonder(s, "admin-kasa-duzeltildi", "&a{klan} kasası {eski} -> {yeni} olarak düzeltildi.", "klan", a.isim, "eski", Para.yaz(eski), "yeni", Para.yaz(a.kasa));
    }

    public void adminDagit(CommandSender s, Klan a) {
        if (plugin.kasaKilitliMi(a)) plugin.savas().adminBitir(s, a);
        plugin.log().yaz(a, s.getName(), "ADMIN_DAGITTI", null);
        String isim = a.isim;
        klaniDagit(a, m().metin("dagildi-admin", "Yönetim tarafından dağıtıldı"));
        kaydet();
        m().gonder(s, "admin-dagitildi", "&a{klan} klanı zorla dağıtıldı.", "klan", isim);
    }

    /** Oyuncu girişinde bilinen adı günceller (isim değişikliği). */
    public void isimGuncelle(Player p) {
        KlanUyesi u = uye(p.getUniqueId());
        if (u != null && !u.isim.equals(p.getName())) {
            u.isim = p.getName();
            kaydet();
            plugin.etiket().guncelle(oyuncununKlani(p.getUniqueId()), u);
        }
    }

    public String oyuncuAdi(UUID uuid) {
        KlanUyesi u = uye(uuid);
        if (u != null) return u.isim;
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString().substring(0, 8);
    }
}
