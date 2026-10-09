package me.klansistemi;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import me.klansistemi.model.Klan;
import me.klansistemi.model.KlanUyesi;
import me.klansistemi.model.Rol;

/**
 * Zorunlu aidat.
 *
 * Dönemler klan genelidir: dönem k = [başlangıç + k*P, başlangıç + (k+1)*P). Her üye her dönem
 * bir kez aidat öder (otomatik çekim yok). Dönem bitince ek süre (G) başlar; bu sürede geçen dönemin
 * aidatı hâlâ ödenebilir. Ek süre de biterse üye BORÇLU olur (kasadan para çekemez) ve ardışık
 * ödenmeyen sayacı artar: belirlenen dönemde lidere bildirim, belirlenen dönemde otomatik atma.
 * Borç birikmez; her dönem bağımsızdır ve borçlu işareti bir sonraki ödemede kalkar.
 *
 * Çevrimdışı koruma: ek süre bittiğinde oyunda olmayan üyenin ek süresi oyuna girdiği anda yeniden başlar.
 */
public class AidatManager {

    public enum Durum {
        ODENDI(ChatColor.GREEN + "ÖDENDİ"),
        BEKLIYOR(ChatColor.YELLOW + "BEKLİYOR"),
        EK_SURE(ChatColor.GOLD + "EK SÜRE"),
        BORCLU(ChatColor.RED + "BORÇLU"),
        MUAF(ChatColor.AQUA + "MUAF");

        public final String etiket;
        Durum(String etiket) { this.etiket = etiket; }
    }

    private static final int SAKLANAN_DONEM = 6;

    private final KlanSistemi plugin;

    public AidatManager(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private Ayarlar ayar() { return plugin.ayar(); }

    // ------------------------------------------------------------------ DÖNEM HESAPLARI
    /** Klanın dönem uzunluğu: lider seçtiyse o (config aralığına sıkıştırılır), yoksa config varsayılanı. */
    public long periyotMs(Klan a) {
        double gun = a.aidatPeriyotGun > 0 ? a.aidatPeriyotGun : ayar().aidatPeriyotVarsayilanGun();
        return (long) (gun * Zaman.GUN);
    }

    public int donem(Klan a, long zaman) {
        return (int) Math.floorDiv(zaman - a.aidatBaslangic, periyotMs(a));
    }

    public long donemBaslangic(Klan a, int k) { return a.aidatBaslangic + k * periyotMs(a); }

    public long donemSonu(Klan a, int k) { return donemBaslangic(a, k + 1); }

    public boolean odendi(KlanUyesi u, int k) { return u.odenenDonemler.contains(k); }

    /** Lider (ayara göre) ve katıldığı dönem / yeni üye muafiyet süresi kapsadığı dönemler muaftır. */
    public boolean muaf(Klan a, KlanUyesi u, int k) {
        if (u.rol == Rol.LIDER && ayar().liderMuaf()) return true;
        if (donem(a, u.katilma) >= k) return true;           // Katıldığı (ilk) dönem
        return u.muafBitis >= donemSonu(a, k);              // Muafiyet bütün dönemi kapsıyor
    }

    private boolean ekSuredeOdenebilir(Klan a, KlanUyesi u, int p, long simdi) {
        if (simdi < donemSonu(a, p) + ayar().aidatEkSureMs()) return true;
        return u.bekleyenDonem == p && (u.ekSureBitis == 0 || simdi < u.ekSureBitis);
    }

    /** Şu an ödenmesi gereken dönem: önce ek süredeki geçmiş dönem, yoksa içinde bulunulan dönem. null = borç yok. */
    public Integer odenecekDonem(Klan a, KlanUyesi u, long simdi) {
        int k = donem(a, simdi);
        for (int p = Math.max(u.islenenDonem + 1, k - SAKLANAN_DONEM); p < k; p++) {
            if (!odendi(u, p) && !muaf(a, u, p) && ekSuredeOdenebilir(a, u, p, simdi)) return p;
        }
        if (!odendi(u, k) && !muaf(a, u, k)) return k;
        return null;
    }

    public Durum durum(Klan a, KlanUyesi u, long simdi) {
        Integer hedef = odenecekDonem(a, u, simdi);
        int k = donem(a, simdi);
        if (u.borclu) return Durum.BORCLU;
        if (hedef != null && hedef < k) return Durum.EK_SURE;
        if (muaf(a, u, k)) return Durum.MUAF;
        if (odendi(u, k)) return Durum.ODENDI;
        return Durum.BEKLIYOR;
    }

    /** Ödeme için son an (ek süre dahil değil; ek süredeki dönem için ek sürenin bitişi). */
    public long sonOdemeAni(Klan a, KlanUyesi u, int donem, long simdi) {
        if (donem < donem(a, simdi)) {
            if (u.bekleyenDonem == donem && u.ekSureBitis > 0) return u.ekSureBitis;
            return donemSonu(a, donem) + ayar().aidatEkSureMs();
        }
        return donemSonu(a, donem);
    }

    // ------------------------------------------------------------------ ÜYE YAŞAM DÖNGÜSÜ
    public void yeniUye(Klan a, KlanUyesi u, long simdi) {
        u.muafBitis = simdi + ayar().yeniUyeMuafMs();
        u.islenenDonem = donem(a, simdi); // Önceki dönemler bu üyeyi ilgilendirmez
    }

    /** Rol değişince (örn. yeni lider) hatırlatma bayrakları sıfırlanır. */
    public void rolDegisti(Klan a, KlanUyesi u) {
        u.hatirlatma24Donem = -1;
        u.hatirlatma1Donem = -1;
        // Aidattan muaf lider olan borçlu, kasa yetkisiz kalmasın
        if (u.rol == Rol.LIDER && ayar().liderMuaf()) {
            u.borclu = false;
            u.ardisikOdenmeyen = 0;
            u.bekleyenDonem = -1;
            u.ekSureBitis = 0;
        }
    }

    // ------------------------------------------------------------------ KOMUTLAR
    public void ode(Player p) {
        if (!ayar().aidatAktif()) { m().gonder(p, "aidat-kapali", "&cAidat sistemi şu an kapalı."); return; }
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (plugin.kasaKilitliMi(a)) {
            m().gonder(p, "aidat-savas", "&eSavaş sürerken kasa kilitli; aidat süreniz de durduruldu. Savaştan sonra ödeyebilirsiniz.");
            return;
        }
        KlanUyesi u = a.uyeler.get(p.getUniqueId());
        long simdi = System.currentTimeMillis();
        Integer hedef = odenecekDonem(a, u, simdi);
        if (hedef == null) {
            Durum d = durum(a, u, simdi);
            if (d == Durum.MUAF) m().gonder(p, "aidat-muaf", "&bBu dönem aidattan muafsınız.");
            else m().gonder(p, "aidat-zaten-odendi", "&aBu dönemin aidatını zaten ödediniz.");
            return;
        }
        double miktar = a.aidatMiktari;
        if (!plugin.meslek().bankadanCek(p.getUniqueId(), miktar)) {
            m().gonder(p, "aidat-bakiye", "&cAidat ({miktar}) için banka hesabınızda yeterli bakiye yok.", "miktar", Para.yaz(miktar));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        // Aidat zorunlu ödeme olduğu için kasa üst sınırına takılmaz
        a.kasa = Para.kurus(a.kasa + miktar);
        a.toplamAidat = Para.kurus(a.toplamAidat + miktar);
        u.odenenDonemler.add(hedef);
        u.odenenDonemler.removeIf(d -> d < donem(a, simdi) - SAKLANAN_DONEM);
        u.sonOdeme = simdi;
        u.borclu = false;
        u.ardisikOdenmeyen = 0;
        if (u.bekleyenDonem == hedef) { u.bekleyenDonem = -1; u.ekSureBitis = 0; }
        plugin.veri().kaydet();

        plugin.log().yaz(a, p.getName(), "AIDAT_ODEDI", Para.yaz(miktar) + " (dönem " + hedef + ")");
        m().gonder(p, "aidat-odendi", "&a{miktar} aidat ödendi. Teşekkürler! Kasa: &e{kasa}", "miktar", Para.yaz(miktar), "kasa", Para.yaz(a.kasa));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
    }

    public void bilgi(Player p) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        KlanUyesi u = a.uyeler.get(p.getUniqueId());
        long simdi = System.currentTimeMillis();
        int k = donem(a, simdi);
        p.sendMessage(m().metin("aidat-bilgi-baslik", "&6&l--- AİDAT BİLGİSİ ---"));
        p.sendMessage(m().metin("aidat-bilgi-miktar", "&7Dönem aidatı: &e{miktar} &7| Periyot: &e{periyot}", "miktar", Para.yaz(a.aidatMiktari), "periyot", Zaman.sure(periyotMs(a))));
        if (a.bekleyenPeriyotGun > 0) {
            p.sendMessage(m().metin("aidat-gun-bekliyor", "&7Yeni dönem uzunluğu: &e{gun} gün &7({tarih} tarihinde başlar)",
                    "gun", sade(a.bekleyenPeriyotGun), "tarih", Zaman.tarih(a.periyotDegisimAni)));
        }
        p.sendMessage(m().metin("aidat-bilgi-durum", "&7Durumunuz: {durum}", "durum", durum(a, u, simdi).etiket));
        Integer hedef = odenecekDonem(a, u, simdi);
        if (hedef != null) {
            p.sendMessage(m().metin("aidat-bilgi-son", "&7Son ödeme: &e{tarih} &7({kalan} kaldı)",
                    "tarih", Zaman.tarih(sonOdemeAni(a, u, hedef, simdi)), "kalan", Zaman.sure(sonOdemeAni(a, u, hedef, simdi) - simdi)));
        } else {
            p.sendMessage(m().metin("aidat-bilgi-sonraki", "&7Sonraki dönem: &e{tarih}", "tarih", Zaman.tarih(donemSonu(a, k))));
        }
        if (u.sonOdeme > 0) p.sendMessage(m().metin("aidat-bilgi-son-odeme", "&7Son ödemeniz: &f{tarih}", "tarih", Zaman.tarih(u.sonOdeme)));
        p.sendMessage(m().metin("aidat-bilgi-toplam", "&7Klanın toplam aidat geliri: &a{toplam}", "toplam", Para.yaz(a.toplamAidat)));
    }

    public void ayarla(Player p, double miktar) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (a.uyeler.get(p.getUniqueId()).rol != Rol.LIDER) { m().gonder(p, "sadece-lider", "&cBunu sadece klan Lideri yapabilir."); return; }
        if (miktar < ayar().aidatMin() || miktar > ayar().aidatMax()) {
            m().gonder(p, "aidat-aralik", "&cAidat {min} ile {max} arasında olmalı.", "min", Para.yaz(ayar().aidatMin()), "max", Para.yaz(ayar().aidatMax()));
            return;
        }
        a.aidatMiktari = Para.kurus(miktar);
        plugin.veri().kaydet();
        plugin.log().yaz(a, p.getName(), "AIDAT_AYARLADI", Para.yaz(miktar));
        plugin.klanManager().klanaGonder(a, "aidat-degisti", "&eKlan aidatı {miktar} olarak belirlendi.", "miktar", Para.yaz(miktar));
    }

    /** Lider dönem uzunluğunu (gün) seçer. Mevcut dönem bozulmaz; yeni uzunluk bir sonraki dönem başında geçerli olur. */
    public void ayarlaPeriyot(Player p, double gun) {
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) { m().gonder(p, "klanda-degil", "&cBir klanda değilsiniz."); return; }
        if (a.uyeler.get(p.getUniqueId()).rol != Rol.LIDER) { m().gonder(p, "sadece-lider", "&cBunu sadece klan Lideri yapabilir."); return; }
        if (plugin.kasaKilitliMi(a)) { m().gonder(p, "aidat-savas", "&eSavaş sürerken kasa kilitli; aidat süreniz de durduruldu. Savaştan sonra ödeyebilirsiniz."); return; }
        if (!Double.isFinite(gun) || gun < ayar().aidatPeriyotMinGun() || gun > ayar().aidatPeriyotMaxGun()) {
            m().gonder(p, "aidat-gun-aralik", "&cDönem uzunluğu {min} ile {max} gün arasında olmalı.",
                    "min", sade(ayar().aidatPeriyotMinGun()), "max", sade(ayar().aidatPeriyotMaxGun()));
            return;
        }
        gun = Math.round(gun * 100.0) / 100.0;
        long simdi = System.currentTimeMillis();
        periyotUygula(a, simdi); // Önceki bekleyen değişiklik süresi dolduysa önce o işlensin
        double mevcut = a.aidatPeriyotGun > 0 ? a.aidatPeriyotGun : ayar().aidatPeriyotVarsayilanGun();
        if (Math.abs(gun - mevcut) < 0.005) {
            a.bekleyenPeriyotGun = 0; // Aynı değere dönüldü: bekleyen değişiklik iptal
            a.periyotDegisimAni = 0;
            plugin.veri().kaydet();
            m().gonder(p, "aidat-gun-ayni", "&eDönem uzunluğu zaten {gun} gün.", "gun", sade(mevcut));
            return;
        }
        int k = donem(a, simdi);
        a.bekleyenPeriyotGun = gun;
        a.periyotDegisimAni = donemSonu(a, k);
        plugin.veri().kaydet();
        plugin.log().yaz(a, p.getName(), "AIDAT_PERIYOT_AYARLADI", sade(gun) + " gün (geçerlilik " + Zaman.tarih(a.periyotDegisimAni) + ")");
        plugin.klanManager().klanaGonder(a, "aidat-gun-degisti", "&eAidat dönemi {gun} gün olarak belirlendi. &7{tarih} tarihinde başlayacak (mevcut dönem aynen sürer).",
                "gun", sade(gun), "tarih", Zaman.tarih(a.periyotDegisimAni));
    }

    private static String sade(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /**
     * Bekleyen dönem uzunluğunu, mevcut dönem bittiği anda devreye alır. Yeni dönem sayımı o andan başlar (dönem 0);
     * üyelerin eski dönem numaraları buna göre kaydırılır, ödenmiş/işlenmiş durumlar korunur.
     * @return değişiklik uygulandıysa true
     */
    public boolean periyotUygula(Klan a, long simdi) {
        if (a.bekleyenPeriyotGun <= 0 || a.periyotDegisimAni <= 0 || simdi < a.periyotDegisimAni) return false;
        int kEski = donem(a, a.periyotDegisimAni - 1); // Biten son dönemin numarası
        int kayma = -(kEski + 1);
        a.aidatBaslangic = a.periyotDegisimAni;
        a.aidatPeriyotGun = a.bekleyenPeriyotGun;
        a.bekleyenPeriyotGun = 0;
        a.periyotDegisimAni = 0;
        for (KlanUyesi u : a.uyeler.values()) {
            java.util.Set<Integer> yeni = new java.util.HashSet<>();
            for (int d : u.odenenDonemler) yeni.add(d + kayma);
            u.odenenDonemler.clear();
            u.odenenDonemler.addAll(yeni);
            u.islenenDonem += kayma;
            if (u.bekleyenDonem != -1) u.bekleyenDonem += kayma;
            u.hatirlatma24Donem = -1;
            u.hatirlatma1Donem = -1;
        }
        plugin.log().yaz(a, "Aidat Sistemi", "AIDAT_PERIYOT_UYGULANDI", sade(a.aidatPeriyotGun) + " gün");
        return true;
    }

    /** Config sınırları değiştiyse (örn. üst sınır düşürüldü) klanın aidatını yeni aralığa çeker. */
    private boolean miktariSinirla(Klan a) {
        double min = ayar().aidatMin(), max = ayar().aidatMax();
        double yeni = Math.max(min, Math.min(max, a.aidatMiktari));
        if (Math.abs(yeni - a.aidatMiktari) < 0.0001) return false;
        plugin.log().yaz(a, "Aidat Sistemi", "AIDAT_SINIRLANDI", Para.yaz(a.aidatMiktari) + " -> " + Para.yaz(yeni));
        a.aidatMiktari = Para.kurus(yeni);
        return true;
    }

    // ------------------------------------------------------------------ PERİYODİK KONTROL
    /** Dakikada bir: ek süresi biten dönemleri işler, hatırlatma gönderir. */
    public void kontrol() {
        if (!ayar().aidatAktif()) return;
        long simdi = System.currentTimeMillis();
        boolean degisti = false;
        for (Klan a : new ArrayList<>(plugin.klanManager().klanlar())) {
            // Savaşta kasa kilitliyken aidat sayacı donar (bitince dönemler kilit süresi kadar kaydırılır)
            if (plugin.kasaKilitliMi(a)) continue;
            if (periyotUygula(a, simdi)) degisti = true;
            if (miktariSinirla(a)) degisti = true;
            int k = donem(a, simdi);
            List<KlanUyesi> atilacaklar = new ArrayList<>();
            for (KlanUyesi u : new ArrayList<>(a.uyeler.values())) {
                if (donemleriIsle(a, u, k, simdi, atilacaklar)) degisti = true;
                hatirlat(a, u, k, simdi);
            }
            for (KlanUyesi u : atilacaklar) {
                if (a.uyeler.containsKey(u.uuid)) plugin.klanManager().uyeyiAt(a, u, m().metin("aidat-atan", "Aidat Sistemi"));
            }
        }
        if (degisti) plugin.veri().kaydet();
    }

    private boolean donemleriIsle(Klan a, KlanUyesi u, int k, long simdi, List<KlanUyesi> atilacaklar) {
        boolean degisti = false;
        // Sunucu uzun süre kapalı kaldıysa en fazla son birkaç dönem işlenir
        if (u.islenenDonem < k - 1 - SAKLANAN_DONEM) u.islenenDonem = k - 1 - SAKLANAN_DONEM;
        for (int p = u.islenenDonem + 1; p <= k - 1; p++) {
            if (simdi < donemSonu(a, p) + ayar().aidatEkSureMs()) break; // Hâlâ ek sürede

            if (odendi(u, p) || muaf(a, u, p)) {
                u.islenenDonem = p;
                degisti = true;
                continue;
            }

            Player oyuncu = Bukkit.getPlayer(u.uuid);
            if (ayar().cevrimdisiKoruma()) {
                if (oyuncu == null && u.bekleyenDonem != p) {
                    // Ek süre biterken oyunda değil: girdiğinde kendi ek süresi başlayacak
                    u.bekleyenDonem = p;
                    u.ekSureBitis = 0;
                    degisti = true;
                    break;
                }
                if (u.bekleyenDonem == p && (u.ekSureBitis == 0 || simdi < u.ekSureBitis)) break;
            }

            // Ödenmedi: BORÇLU
            u.islenenDonem = p;
            u.bekleyenDonem = -1;
            u.ekSureBitis = 0;
            u.borclu = true;
            u.ardisikOdenmeyen++;
            degisti = true;
            plugin.log().yaz(a, u.isim, "AIDAT_ODENMEDI", "dönem " + p + ", ardışık " + u.ardisikOdenmeyen);
            if (oyuncu != null) {
                m().gonder(oyuncu, "aidat-borclu", "&cAidatınızı ödemediğiniz için BORÇLU oldunuz! Kasa çekme yetkiniz kapandı. &e/klan aidat ode");
            }
            if (u.ardisikOdenmeyen == ayar().liderBildirimDonem()) {
                KlanUyesi lider = a.lider();
                Player pp = lider == null ? null : Bukkit.getPlayer(lider.uuid);
                if (pp != null && lider != u) {
                    m().gonder(pp, "aidat-lider-bildirim", "&c{oyuncu} üst üste {donem} dönemdir aidat ödemiyor!", "oyuncu", u.isim, "donem", u.ardisikOdenmeyen);
                }
            }
            if (ayar().otomatikAtma() && u.rol != Rol.LIDER && u.ardisikOdenmeyen >= ayar().otomatikAtmaDonem()) {
                atilacaklar.add(u);
                break;
            }
        }
        return degisti;
    }

    private void hatirlat(Klan a, KlanUyesi u, int k, long simdi) {
        Player p = Bukkit.getPlayer(u.uuid);
        if (p == null || odendi(u, k) || muaf(a, u, k)) return;
        long kalan = donemSonu(a, k) - simdi;
        if (kalan <= Zaman.SAAT && u.hatirlatma1Donem != k) {
            u.hatirlatma1Donem = k;
            u.hatirlatma24Donem = k;
            m().gonder(p, "aidat-hatirlatma-1", "&c&lSON 1 SAAT! &eKlan aidatınızı ({miktar}) ödemeyi unutmayın: &6/klan aidat ode", "miktar", Para.yaz(a.aidatMiktari));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f);
        } else if (kalan <= 24 * Zaman.SAAT && u.hatirlatma24Donem != k) {
            u.hatirlatma24Donem = k;
            m().gonder(p, "aidat-hatirlatma-24", "&eKlan aidatınızı ({miktar}) ödemek için {kalan} kaldı: &6/klan aidat ode", "miktar", Para.yaz(a.aidatMiktari), "kalan", Zaman.sure(kalan));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
        }
    }

    /** Oyuncu girişinde: çevrimdışı korumanın ek süresini başlatır, borç/aidat uyarısı gösterir. */
    public void girisKontrolu(Player p) {
        if (!ayar().aidatAktif()) return;
        Klan a = plugin.klanManager().oyuncununKlani(p.getUniqueId());
        if (a == null) return;
        KlanUyesi u = a.uyeler.get(p.getUniqueId());
        long simdi = System.currentTimeMillis();

        if (u.bekleyenDonem >= 0 && u.ekSureBitis == 0) {
            u.ekSureBitis = simdi + ayar().aidatEkSureMs();
            plugin.veri().kaydet();
            m().gonder(p, "aidat-ek-sure-basladi", "&6Siz yokken aidat süresi doldu. Ödemeniz için {sure} ek süreniz başladı: &e/klan aidat ode",
                    "sure", Zaman.sure(ayar().aidatEkSureMs()));
            return;
        }
        if (u.borclu) {
            m().gonder(p, "aidat-giris-borclu", "&cAidat borcunuz var! Kasa çekme yetkiniz kapalı. &e/klan aidat ode");
            return;
        }
        Integer hedef = odenecekDonem(a, u, simdi);
        if (hedef != null) {
            m().gonder(p, "aidat-giris-bekliyor", "&eÖdenmemiş klan aidatınız var ({miktar}). Son ödeme: &6{tarih}",
                    "miktar", Para.yaz(a.aidatMiktari), "tarih", Zaman.tarih(sonOdemeAni(a, u, hedef, simdi)));
        }
    }

    /** Menüdeki istatistik: [ödendi, bekliyor+ek süre, borçlu, muaf] */
    public int[] istatistik(Klan a) {
        long simdi = System.currentTimeMillis();
        int[] say = new int[4];
        for (KlanUyesi u : a.uyeler.values()) {
            switch (durum(a, u, simdi)) {
                case ODENDI -> say[0]++;
                case BEKLIYOR, EK_SURE -> say[1]++;
                case BORCLU -> say[2]++;
                case MUAF -> say[3]++;
            }
        }
        return say;
    }
}
