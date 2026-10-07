package me.ailesistemi;

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

import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Rol;

/**
 * Ailelerin bellekteki kaydı ve tüm üyelik/kasa işlemleri.
 * Tüm metotlar ana thread'den çağrılır. Her değişiklikten sonra veri asenkron kaydedilir.
 */
public class AileManager {

    private final AileSistemi plugin;

    private final Map<UUID, Aile> aileler = new HashMap<>();
    private final Map<String, UUID> isimIndeksi = new HashMap<>();   // normalize isim -> aile id
    private final Map<UUID, UUID> oyuncuAilesi = new HashMap<>();    // oyuncu -> aile id
    private final Map<UUID, Long> ayrilmaBekleme = new HashMap<>();  // oyuncu -> başka aileye katılabileceği an
    private final Map<UUID, Map<UUID, Long>> davetler = new HashMap<>(); // oyuncu -> (aile id -> davetin bitişi)
    private final Set<UUID> sohbetModu = ConcurrentHashMap.newKeySet();   // aile sohbeti açık oyuncular

    public AileManager(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private Ayarlar ayar() { return plugin.ayar(); }

    // ------------------------------------------------------------------ SORGULAR
    public Collection<Aile> aileler() { return Collections.unmodifiableCollection(aileler.values()); }

    public Map<UUID, Long> ayrilmaBeklemeleri() { return ayrilmaBekleme; }

    public Aile aileBul(String isim) {
        UUID id = isimIndeksi.get(anahtar(isim));
        return id == null ? null : aileler.get(id);
    }

    public Aile aileGetir(UUID id) { return aileler.get(id); }

    public Aile oyuncununAilesi(UUID oyuncu) {
        UUID id = oyuncuAilesi.get(oyuncu);
        return id == null ? null : aileler.get(id);
    }

    public AileUyesi uye(UUID oyuncu) {
        Aile a = oyuncununAilesi(oyuncu);
        return a == null ? null : a.uyeler.get(oyuncu);
    }

    public boolean sohbetAcikMi(UUID oyuncu) { return sohbetModu.contains(oyuncu); }

    /** Türkçe büyük/küçük harf farkını yok sayan isim anahtarı (İzmir = izmir = IZMIR). */
    public static String anahtar(String isim) {
        return isim.toLowerCase(Locale.ROOT).replace("̇", "").replace('ı', 'i');
    }

    public void yuklenenAileyiEkle(Aile a) {
        aileler.put(a.id, a);
        isimIndeksi.put(anahtar(a.isim), a.id);
        for (UUID u : a.uyeler.keySet()) oyuncuAilesi.put(u, a.id);
    }

    private void kaydet() { plugin.veri().kaydet(); }

    /** Ailenin çevrimiçi üyelerine mesaj. */
    public void aileyeGonder(Aile a, String anahtar, String varsayilan, Object... yt) {
        for (AileUyesi u : a.uyeler.values()) {
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

    private static void ses(Player p, boolean basarili) {
        p.playSound(p.getLocation(), basarili ? Sound.ENTITY_VILLAGER_YES : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    // ------------------------------------------------------------------ İSİM KONTROLÜ
    /** Geçerliyse null, değilse oyuncuya gösterilecek hata metni. */
    public String isimHatasi(String isim) {
        if (isim.length() < ayar().isimMin() || isim.length() > ayar().isimMax()) {
            return m().metin("isim-uzunluk", "&cAile adı {min}-{max} karakter olmalı.", "min", ayar().isimMin(), "max", ayar().isimMax());
        }
        if (!isim.matches("[A-Za-z0-9_ÇĞİÖŞÜçğıöşü]+")) {
            return m().metin("isim-karakter", "&cAile adında boşluk ve özel karakter olamaz (harf, rakam ve _ kullanın).");
        }
        String norm = anahtar(isim);
        for (String kelime : ayar().yasakliKelimeler()) {
            if (!kelime.isBlank() && norm.contains(anahtar(kelime))) {
                return m().metin("isim-yasakli", "&cBu aile adı uygunsuz bir ifade içeriyor.");
            }
        }
        if (isimIndeksi.containsKey(norm)) {
            return m().metin("isim-alinmis", "&cBu isimde bir aile zaten var.");
        }
        return null;
    }

    // ------------------------------------------------------------------ KURMA
    public void kur(Player p, String isim) {
        UUID pId = p.getUniqueId();
        if (oyuncuAilesi.containsKey(pId)) { m().gonder(p, "zaten-ailede", "&cZaten bir ailedesiniz."); ses(p, false); return; }
        long kalan = beklemeKalan(pId);
        if (kalan > 0) {
            m().gonder(p, "ayrilma-bekleme", "&cAileden ayrıldığınız için {sure} boyunca yeni bir aileye giremezsiniz.", "sure", Zaman.sure(kalan));
            ses(p, false); return;
        }
        String hata = isimHatasi(isim);
        if (hata != null) { p.sendMessage(m().onek() + hata); ses(p, false); return; }

        double ucret = ayar().kurmaUcreti();
        if (!plugin.meslek().bankadanCek(pId, ucret)) {
            m().gonder(p, "kurma-bakiye", "&cAile kurmak için banka hesabınızda {ucret} olmalı.", "ucret", Para.yaz(ucret));
            ses(p, false); return;
        }

        long simdi = System.currentTimeMillis();
        Aile a = new Aile(UUID.randomUUID(), isim, simdi);
        a.aidatMiktari = ayar().aidatVarsayilan();
        a.aidatBaslangic = simdi;
        AileUyesi patron = new AileUyesi(pId, p.getName(), Rol.PATRON, simdi);
        plugin.aidat().yeniUye(a, patron, simdi);
        a.uyeler.put(pId, patron);
        yuklenenAileyiEkle(a);
        davetler.remove(pId);
        kaydet();

        plugin.log().yaz(a, p.getName(), "AILE_KURULDU", "ücret " + Para.yaz(ucret));
        plugin.etiket().guncelle(a, patron);
        m().gonder(p, "kuruldu", "&a{aile} ailesi kuruldu! Artık ailenin &6Patronu&a sizsiniz. ({ucret} bankanızdan çekildi)",
                "aile", isim, "ucret", Para.yaz(ucret));
        Bukkit.broadcastMessage(m().onek() + m().metin("kuruldu-duyuru", "&e{oyuncu} &7yeni bir aile kurdu: &6{aile}", "oyuncu", p.getName(), "aile", isim));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    // ------------------------------------------------------------------ DAVET / KATILMA
    public void davetEt(Player p, Player hedef) {
        Aile a = oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        AileUyesi ben = a.uyeler.get(p.getUniqueId());
        if (ben.rol == Rol.UYE) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); ses(p, false); return; }
        if (hedef.getUniqueId().equals(p.getUniqueId())) return;
        if (oyuncuAilesi.containsKey(hedef.getUniqueId())) {
            m().gonder(p, "hedef-ailede", "&c{oyuncu} zaten bir ailede.", "oyuncu", hedef.getName()); return;
        }
        if (a.uyeler.size() >= ayar().maxUye()) {
            m().gonder(p, "aile-dolu", "&cAileniz dolu! (En fazla {max} üye)", "max", ayar().maxUye()); return;
        }

        long bitis = System.currentTimeMillis() + ayar().davetGecerlilikMs();
        davetler.computeIfAbsent(hedef.getUniqueId(), k -> new HashMap<>()).put(a.id, bitis);

        m().gonder(p, "davet-gonderildi", "&a{oyuncu} adlı oyuncuya aile daveti gönderildi.", "oyuncu", hedef.getName());
        Component buton = m().bilesen("davet-buton", "&a&l[KATIL]")
                .clickEvent(ClickEvent.runCommand("/aile katil " + a.isim))
                .hoverEvent(HoverEvent.showText(m().bilesen("davet-buton-ipucu", "&7{aile} ailesine katıl", "aile", a.isim)));
        hedef.sendMessage(m().onekBileseni()
                .append(m().bilesen("davet-alindi", "&e{oyuncu} &7sizi &6{aile} &7ailesine davet etti. ", "oyuncu", p.getName(), "aile", a.isim))
                .append(buton));
        hedef.playSound(hedef.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
    }

    public void katil(Player p, String aileIsmi) {
        UUID pId = p.getUniqueId();
        if (oyuncuAilesi.containsKey(pId)) { m().gonder(p, "zaten-ailede", "&cZaten bir ailedesiniz."); return; }
        Aile a = aileBul(aileIsmi);
        if (a == null) { m().gonder(p, "aile-yok", "&cBöyle bir aile bulunamadı."); return; }

        Map<UUID, Long> benimDavetlerim = davetler.get(pId);
        Long davetBitis = benimDavetlerim == null ? null : benimDavetlerim.get(a.id);
        if (davetBitis == null || davetBitis < System.currentTimeMillis()) {
            if (benimDavetlerim != null) benimDavetlerim.remove(a.id);
            m().gonder(p, "davet-yok", "&cBu aileden geçerli bir davetiniz yok."); ses(p, false); return;
        }
        long kalan = beklemeKalan(pId);
        if (kalan > 0) {
            m().gonder(p, "ayrilma-bekleme", "&cAileden ayrıldığınız için {sure} boyunca yeni bir aileye giremezsiniz.", "sure", Zaman.sure(kalan));
            ses(p, false); return;
        }
        if (a.uyeler.size() >= ayar().maxUye()) {
            m().gonder(p, "katilma-dolu", "&cBu aile dolu."); return;
        }

        long simdi = System.currentTimeMillis();
        AileUyesi u = new AileUyesi(pId, p.getName(), Rol.UYE, simdi);
        plugin.aidat().yeniUye(a, u, simdi);
        a.uyeler.put(pId, u);
        oyuncuAilesi.put(pId, a.id);
        davetler.remove(pId);
        kaydet();

        plugin.log().yaz(a, p.getName(), "KATILDI", null);
        plugin.etiket().guncelle(a, u);
        aileyeGonder(a, "katildi", "&a{oyuncu} aileye katıldı!", "oyuncu", p.getName());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    // ------------------------------------------------------------------ AYRILMA / ATMA
    public void ayril(Player p, boolean onay) {
        UUID pId = p.getUniqueId();
        Aile a = oyuncununAilesi(pId);
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        AileUyesi ben = a.uyeler.get(pId);

        if (ben.rol == Rol.PATRON) {
            List<AileUyesi> yardimcilar = a.yardimcilar();
            if (yardimcilar.isEmpty()) {
                // Halef yok: aile dağılır. Bu yüzden onay istenir.
                if (!onay) {
                    m().gonder(p, "ayril-dagilma-uyari", "&cAilenizde yardımcı olmadığı için ayrılırsanız aile DAĞILIR ve kasa üyelere bölünür. Onaylamak için: &e/aile ayril onayla");
                    return;
                }
                plugin.log().yaz(a, p.getName(), "PATRON_AYRILDI", "halef yok, aile dağıldı");
                aileyiDagit(a, m().metin("dagildi-patron-ayrildi", "Patron aileden ayrıldı"));
                beklemeBaslat(pId);
                kaydet();
                return;
            }
            AileUyesi yeniPatron = yardimcilar.get(0);
            yeniPatron.rol = Rol.PATRON;
            uyeyiCikar(a, pId);
            a.kidemleriDuzenle();
            plugin.aidat().rolDegisti(a, yeniPatron);
            kaydet();
            plugin.log().yaz(a, p.getName(), "PATRON_AYRILDI", "yeni patron " + yeniPatron.isim);
            plugin.etiket().kaldir(p.getName());
            plugin.etiket().ailedekileriGuncelle(a); // Kıdemler/roller değişti
            m().gonder(p, "ayrildin", "&e{aile} ailesinden ayrıldınız.", "aile", a.isim);
            aileyeGonder(a, "patron-devri", "&6{eski} aileden ayrıldı. Yeni Patron: &e{yeni}", "eski", p.getName(), "yeni", yeniPatron.isim);
            return;
        }

        uyeyiCikar(a, pId);
        a.kidemleriDuzenle();
        kaydet();
        plugin.log().yaz(a, p.getName(), "AYRILDI", null);
        plugin.etiket().kaldir(p.getName());
        m().gonder(p, "ayrildin", "&e{aile} ailesinden ayrıldınız.", "aile", a.isim);
        aileyeGonder(a, "uye-ayrildi", "&e{oyuncu} aileden ayrıldı.", "oyuncu", p.getName());
    }

    public void at(Player p, String hedefIsim) {
        Aile a = oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        AileUyesi ben = a.uyeler.get(p.getUniqueId());
        AileUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null) { m().gonder(p, "uye-bulunamadi", "&cAilenizde bu isimde bir üye yok."); return; }
        if (hedef.uuid.equals(p.getUniqueId())) { m().gonder(p, "kendini-atamaz", "&cKendinizi atamazsınız, /aile ayril kullanın."); return; }

        boolean yetkili = ben.rol == Rol.PATRON || (ben.rol == Rol.YARDIMCI && hedef.rol == Rol.UYE);
        if (!yetkili) { m().gonder(p, "yetki-yok", "&cBu işlem için yetkiniz yok."); ses(p, false); return; }

        uyeyiAt(a, hedef, p.getName());
    }

    /** Üyeyi aileden atar (komut ya da aidat sistemi). Patron atılamaz. */
    public void uyeyiAt(Aile a, AileUyesi hedef, String atan) {
        if (hedef.rol == Rol.PATRON) return;
        uyeyiCikar(a, hedef.uuid);
        a.kidemleriDuzenle();
        kaydet();
        plugin.log().yaz(a, atan, "ATTI", hedef.isim);
        plugin.etiket().kaldir(hedef.isim);
        aileyeGonder(a, "uye-atildi", "&c{oyuncu} aileden atıldı. &7({atan})", "oyuncu", hedef.isim, "atan", atan);
        Player hp = Bukkit.getPlayer(hedef.uuid);
        if (hp != null) {
            m().gonder(hp, "atildin", "&c{aile} ailesinden atıldınız. &7({atan})", "aile", a.isim, "atan", atan);
            ses(hp, false);
        }
    }

    private void uyeyiCikar(Aile a, UUID oyuncu) {
        a.uyeler.remove(oyuncu);
        oyuncuAilesi.remove(oyuncu);
        sohbetModu.remove(oyuncu);
        beklemeBaslat(oyuncu);
    }

    private void beklemeBaslat(UUID oyuncu) {
        long bekleme = ayar().ayrilmaBeklemeMs();
        if (bekleme > 0) ayrilmaBekleme.put(oyuncu, System.currentTimeMillis() + bekleme);
    }

    public AileUyesi uyeBulIsimle(Aile a, String isim) {
        for (AileUyesi u : a.uyeler.values()) if (u.isim.equalsIgnoreCase(isim)) return u;
        return null;
    }

    // ------------------------------------------------------------------ ROLLER / KIDEM
    public void terfi(Player p, String hedefIsim) {
        Aile a = patronunAilesi(p);
        if (a == null) return;
        AileUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null) { m().gonder(p, "uye-bulunamadi", "&cAilenizde bu isimde bir üye yok."); return; }
        if (hedef.rol != Rol.UYE) { m().gonder(p, "terfi-olmaz", "&cSadece Üye rolündekiler Yardımcı yapılabilir."); return; }
        hedef.rol = Rol.YARDIMCI;
        hedef.kidem = a.yardimcilar().size() + 1; // En sona eklenir
        a.kidemleriDuzenle();
        plugin.aidat().rolDegisti(a, hedef);
        kaydet();
        plugin.log().yaz(a, p.getName(), "TERFI", hedef.isim + " -> Yardımcı (kıdem " + hedef.kidem + ")");
        plugin.etiket().guncelle(a, hedef);
        aileyeGonder(a, "terfi-edildi", "&a{oyuncu} &eYardımcı&a oldu! (Kıdem sırası: {kidem})", "oyuncu", hedef.isim, "kidem", hedef.kidem);
    }

    public void indir(Player p, String hedefIsim) {
        Aile a = patronunAilesi(p);
        if (a == null) return;
        AileUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null) { m().gonder(p, "uye-bulunamadi", "&cAilenizde bu isimde bir üye yok."); return; }
        if (hedef.rol != Rol.YARDIMCI) { m().gonder(p, "indir-olmaz", "&cSadece Yardımcılar Üye'ye indirilebilir."); return; }
        hedef.rol = Rol.UYE;
        a.kidemleriDuzenle();
        kaydet();
        plugin.log().yaz(a, p.getName(), "RUTBE_INDIRDI", hedef.isim + " -> Üye");
        plugin.etiket().guncelle(a, hedef);
        aileyeGonder(a, "indirildi", "&e{oyuncu} artık Üye.", "oyuncu", hedef.isim);
    }

    /** Patron yardımcının kıdem sırasını belirler (1. sıra patron ayrılırsa yerine geçer). */
    public void kidem(Player p, String hedefIsim, int sira) {
        Aile a = patronunAilesi(p);
        if (a == null) return;
        AileUyesi hedef = uyeBulIsimle(a, hedefIsim);
        if (hedef == null || hedef.rol != Rol.YARDIMCI) {
            m().gonder(p, "kidem-sadece-yardimci", "&cKıdem sadece Yardımcılara verilir."); return;
        }
        List<AileUyesi> liste = new ArrayList<>(a.yardimcilar());
        sira = Math.max(1, Math.min(sira, liste.size()));
        liste.remove(hedef);
        liste.add(sira - 1, hedef);
        for (int i = 0; i < liste.size(); i++) liste.get(i).kidem = i + 1;
        kaydet();
        plugin.log().yaz(a, p.getName(), "KIDEM", hedef.isim + " -> " + sira);
        StringBuilder sb = new StringBuilder();
        for (AileUyesi y : liste) sb.append(y.kidem).append(". ").append(y.isim).append("  ");
        m().gonder(p, "kidem-ayarlandi", "&aKıdem sırası güncellendi: &e{liste}", "liste", sb.toString().trim());
    }

    private Aile patronunAilesi(Player p) {
        Aile a = oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return null; }
        if (a.uyeler.get(p.getUniqueId()).rol != Rol.PATRON) {
            m().gonder(p, "sadece-patron", "&cBunu sadece aile Patronu yapabilir."); ses(p, false); return null;
        }
        return a;
    }

    // ------------------------------------------------------------------ KASA
    public void yatir(Player p, double miktar) {
        Aile a = oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        if (miktar <= 0) { m().gonder(p, "gecersiz-miktar", "&cGeçerli bir miktar yazın."); return; }
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
        m().gonder(p, "yatirildi", "&a{miktar} aile kasasına yatırıldı. Kasa: &e{kasa}", "miktar", Para.yaz(miktar), "kasa", Para.yaz(a.kasa));
        ses(p, true);
    }

    public void cek(Player p, double miktar) {
        Aile a = oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        AileUyesi ben = a.uyeler.get(p.getUniqueId());
        if (miktar <= 0) { m().gonder(p, "gecersiz-miktar", "&cGeçerli bir miktar yazın."); return; }
        if (ben.rol == Rol.UYE) { m().gonder(p, "cek-yetki-yok", "&cÜyeler kasadan para çekemez, sadece yatırabilir."); ses(p, false); return; }
        if (ben.borclu) {
            m().gonder(p, "cek-borclu", "&cAidat borcunuz olduğu için kasadan para çekemezsiniz. Önce &e/aile aidat ode&c."); ses(p, false); return;
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
        Aile a = patronunAilesi(p);
        if (a == null) return;
        if (!onay) {
            m().gonder(p, "dagit-uyari", "&cAileyi dağıtmak üzeresiniz! Kasa ({kasa}) üyelere eşit bölünecek. Onaylamak için: &e/aile dagit onayla",
                    "kasa", Para.yaz(a.kasa));
            return;
        }
        plugin.log().yaz(a, p.getName(), "DAGITTI", null);
        aileyiDagit(a, m().metin("dagildi-patron", "Patron aileyi dağıttı"));
        kaydet();
    }

    /** Aileyi kaldırır; kasadaki para üyelerin banka hesaplarına eşit bölünür (çevrimdışı üyeler dahil). */
    public void aileyiDagit(Aile a, String sebep) {
        List<AileUyesi> uyeler = new ArrayList<>(a.uyeler.values());
        if (!uyeler.isEmpty() && a.kasa > 0) {
            double pay = Math.floor(a.kasa / uyeler.size() * 100.0) / 100.0;
            double artan = Para.kurus(a.kasa - pay * uyeler.size());
            AileUyesi patron = a.patron();
            for (AileUyesi u : uyeler) {
                double odenecek = pay + (u == patron ? artan : 0);
                if (odenecek > 0) {
                    plugin.meslek().bankayaYatir(u.uuid, odenecek);
                    plugin.log().yaz(a, u.isim, "DAGILMA_PAYI", Para.yaz(odenecek));
                }
            }
            aileyeGonder(a, "dagilma-payi", "&eAile kasasından payınıza düşen {pay} banka hesabınıza yatırıldı.", "pay", Para.yaz(pay));
        }
        aileyeGonder(a, "dagildi", "&c{aile} ailesi dağıldı. &7({sebep})", "aile", a.isim, "sebep", sebep);

        for (AileUyesi u : uyeler) {
            oyuncuAilesi.remove(u.uuid);
            sohbetModu.remove(u.uuid);
            plugin.etiket().kaldir(u.isim);
        }
        a.uyeler.clear();
        a.kasa = 0;
        aileler.remove(a.id);
        isimIndeksi.remove(anahtar(a.isim));
        for (Map<UUID, Long> d : davetler.values()) d.remove(a.id);
        plugin.log().yaz(a, "-", "AILE_DAGILDI", sebep);
    }

    // ------------------------------------------------------------------ SOHBET
    public void sohbetDegistir(Player p) {
        if (oyuncununAilesi(p.getUniqueId()) == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        if (sohbetModu.remove(p.getUniqueId())) {
            m().gonder(p, "sohbet-kapandi", "&eAile sohbeti kapatıldı. Mesajlarınız genel sohbete gidiyor.");
        } else {
            sohbetModu.add(p.getUniqueId());
            m().gonder(p, "sohbet-acildi", "&aAile sohbeti açıldı. Mesajlarınız sadece ailenize gidiyor. Kapatmak için tekrar &e/aile sohbet");
        }
    }

    /** Aile sohbetine mesaj gönderir (ana thread). */
    public void aileSohbeti(Player p, String mesaj) {
        Aile a = oyuncununAilesi(p.getUniqueId());
        if (a == null) { sohbetModu.remove(p.getUniqueId()); m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        AileUyesi u = a.uyeler.get(p.getUniqueId());
        String satir = m().metin("sohbet-format", "&8[&6{aile}&8] {rol}&r {oyuncu}&7: &f{mesaj}",
                "aile", a.isim, "rol", u.rol.renkliAd(), "oyuncu", p.getName(), "mesaj", "\u0000");
        // Oyuncu mesajındaki & kodları renge çevrilmesin
        satir = satir.replace("\u0000", mesaj);
        for (AileUyesi uye : a.uyeler.values()) {
            Player alici = Bukkit.getPlayer(uye.uuid);
            if (alici != null) alici.sendMessage(satir);
        }
        plugin.getLogger().info("[Aile Sohbeti] [" + a.isim + "] " + p.getName() + ": " + mesaj);
    }

    // ------------------------------------------------------------------ YÖNETİCİ
    public void adminKasaDuzelt(CommandSender s, Aile a, double yeniKasa) {
        double eski = a.kasa;
        a.kasa = Para.kurus(Math.max(0, yeniKasa));
        kaydet();
        plugin.log().yaz(a, s.getName(), "ADMIN_KASA_DUZELT", Para.yaz(eski) + " -> " + Para.yaz(a.kasa));
        m().gonder(s, "admin-kasa-duzeltildi", "&a{aile} kasası {eski} -> {yeni} olarak düzeltildi.", "aile", a.isim, "eski", Para.yaz(eski), "yeni", Para.yaz(a.kasa));
    }

    public void adminDagit(CommandSender s, Aile a) {
        plugin.log().yaz(a, s.getName(), "ADMIN_DAGITTI", null);
        String isim = a.isim;
        aileyiDagit(a, m().metin("dagildi-admin", "Yönetim tarafından dağıtıldı"));
        kaydet();
        m().gonder(s, "admin-dagitildi", "&a{aile} ailesi zorla dağıtıldı.", "aile", isim);
    }

    /** Oyuncu girişinde bilinen adı günceller (isim değişikliği). */
    public void isimGuncelle(Player p) {
        AileUyesi u = uye(p.getUniqueId());
        if (u != null && !u.isim.equals(p.getName())) {
            u.isim = p.getName();
            kaydet();
            plugin.etiket().guncelle(oyuncununAilesi(p.getUniqueId()), u);
        }
    }

    public String oyuncuAdi(UUID uuid) {
        AileUyesi u = uye(uuid);
        if (u != null) return u.isim;
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString().substring(0, 8);
    }
}
