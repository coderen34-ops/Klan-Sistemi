package me.ailesistemi;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import me.ailesistemi.model.Aile;
import me.ailesistemi.model.AileUyesi;
import me.ailesistemi.model.Rol;

/**
 * Zorunlu aidat.
 *
 * Dönemler aile genelidir: dönem k = [başlangıç + k*P, başlangıç + (k+1)*P). Her üye her dönem
 * bir kez aidat öder (otomatik çekim yok). Dönem bitince ek süre (G) başlar; bu sürede geçen dönemin
 * aidatı hâlâ ödenebilir. Ek süre de biterse üye BORÇLU olur (kasadan para çekemez) ve ardışık
 * ödenmeyen sayacı artar: belirlenen dönemde patrona bildirim, belirlenen dönemde otomatik atma.
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

    private final AileSistemi plugin;

    public AidatManager(AileSistemi plugin) {
        this.plugin = plugin;
    }

    private Mesaj m() { return plugin.mesaj(); }
    private Ayarlar ayar() { return plugin.ayar(); }

    // ------------------------------------------------------------------ DÖNEM HESAPLARI
    public int donem(Aile a, long zaman) {
        return (int) Math.floorDiv(zaman - a.aidatBaslangic, ayar().aidatPeriyotMs());
    }

    public long donemBaslangic(Aile a, int k) { return a.aidatBaslangic + k * ayar().aidatPeriyotMs(); }

    public long donemSonu(Aile a, int k) { return donemBaslangic(a, k + 1); }

    public boolean odendi(AileUyesi u, int k) { return u.odenenDonemler.contains(k); }

    /** Patron (ayara göre) ve katıldığı dönem / yeni üye muafiyet süresi kapsadığı dönemler muaftır. */
    public boolean muaf(Aile a, AileUyesi u, int k) {
        if (u.rol == Rol.PATRON && ayar().patronMuaf()) return true;
        if (donem(a, u.katilma) >= k) return true;           // Katıldığı (ilk) dönem
        return u.muafBitis >= donemSonu(a, k);              // Muafiyet bütün dönemi kapsıyor
    }

    private boolean ekSuredeOdenebilir(Aile a, AileUyesi u, int p, long simdi) {
        if (simdi < donemSonu(a, p) + ayar().aidatEkSureMs()) return true;
        return u.bekleyenDonem == p && (u.ekSureBitis == 0 || simdi < u.ekSureBitis);
    }

    /** Şu an ödenmesi gereken dönem: önce ek süredeki geçmiş dönem, yoksa içinde bulunulan dönem. null = borç yok. */
    public Integer odenecekDonem(Aile a, AileUyesi u, long simdi) {
        int k = donem(a, simdi);
        for (int p = Math.max(u.islenenDonem + 1, k - SAKLANAN_DONEM); p < k; p++) {
            if (!odendi(u, p) && !muaf(a, u, p) && ekSuredeOdenebilir(a, u, p, simdi)) return p;
        }
        if (!odendi(u, k) && !muaf(a, u, k)) return k;
        return null;
    }

    public Durum durum(Aile a, AileUyesi u, long simdi) {
        Integer hedef = odenecekDonem(a, u, simdi);
        int k = donem(a, simdi);
        if (u.borclu) return Durum.BORCLU;
        if (hedef != null && hedef < k) return Durum.EK_SURE;
        if (muaf(a, u, k)) return Durum.MUAF;
        if (odendi(u, k)) return Durum.ODENDI;
        return Durum.BEKLIYOR;
    }

    /** Ödeme için son an (ek süre dahil değil; ek süredeki dönem için ek sürenin bitişi). */
    public long sonOdemeAni(Aile a, AileUyesi u, int donem, long simdi) {
        if (donem < donem(a, simdi)) {
            if (u.bekleyenDonem == donem && u.ekSureBitis > 0) return u.ekSureBitis;
            return donemSonu(a, donem) + ayar().aidatEkSureMs();
        }
        return donemSonu(a, donem);
    }

    // ------------------------------------------------------------------ ÜYE YAŞAM DÖNGÜSÜ
    public void yeniUye(Aile a, AileUyesi u, long simdi) {
        u.muafBitis = simdi + ayar().yeniUyeMuafMs();
        u.islenenDonem = donem(a, simdi); // Önceki dönemler bu üyeyi ilgilendirmez
    }

    /** Rol değişince (örn. yeni patron) hatırlatma bayrakları sıfırlanır. */
    public void rolDegisti(Aile a, AileUyesi u) {
        u.hatirlatma24Donem = -1;
        u.hatirlatma1Donem = -1;
    }

    // ------------------------------------------------------------------ KOMUTLAR
    public void ode(Player p) {
        if (!ayar().aidatAktif()) { m().gonder(p, "aidat-kapali", "&cAidat sistemi şu an kapalı."); return; }
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        if (plugin.kasaKilitliMi(a)) {
            m().gonder(p, "aidat-savas", "&eSavaş sürerken kasa kilitli; aidat süreniz de durduruldu. Savaştan sonra ödeyebilirsiniz.");
            return;
        }
        AileUyesi u = a.uyeler.get(p.getUniqueId());
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
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        AileUyesi u = a.uyeler.get(p.getUniqueId());
        long simdi = System.currentTimeMillis();
        int k = donem(a, simdi);
        p.sendMessage(m().metin("aidat-bilgi-baslik", "&6&l--- AİDAT BİLGİSİ ---"));
        p.sendMessage(m().metin("aidat-bilgi-miktar", "&7Dönem aidatı: &e{miktar} &7| Periyot: &e{periyot}", "miktar", Para.yaz(a.aidatMiktari), "periyot", Zaman.sure(ayar().aidatPeriyotMs())));
        p.sendMessage(m().metin("aidat-bilgi-durum", "&7Durumunuz: {durum}", "durum", durum(a, u, simdi).etiket));
        Integer hedef = odenecekDonem(a, u, simdi);
        if (hedef != null) {
            p.sendMessage(m().metin("aidat-bilgi-son", "&7Son ödeme: &e{tarih} &7({kalan} kaldı)",
                    "tarih", Zaman.tarih(sonOdemeAni(a, u, hedef, simdi)), "kalan", Zaman.sure(sonOdemeAni(a, u, hedef, simdi) - simdi)));
        } else {
            p.sendMessage(m().metin("aidat-bilgi-sonraki", "&7Sonraki dönem: &e{tarih}", "tarih", Zaman.tarih(donemSonu(a, k))));
        }
        if (u.sonOdeme > 0) p.sendMessage(m().metin("aidat-bilgi-son-odeme", "&7Son ödemeniz: &f{tarih}", "tarih", Zaman.tarih(u.sonOdeme)));
        p.sendMessage(m().metin("aidat-bilgi-toplam", "&7Ailenin toplam aidat geliri: &a{toplam}", "toplam", Para.yaz(a.toplamAidat)));
    }

    public void ayarla(Player p, double miktar) {
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) { m().gonder(p, "ailede-degil", "&cBir ailede değilsiniz."); return; }
        if (a.uyeler.get(p.getUniqueId()).rol != Rol.PATRON) { m().gonder(p, "sadece-patron", "&cBunu sadece aile Patronu yapabilir."); return; }
        if (miktar < ayar().aidatMin() || miktar > ayar().aidatMax()) {
            m().gonder(p, "aidat-aralik", "&cAidat {min} ile {max} arasında olmalı.", "min", Para.yaz(ayar().aidatMin()), "max", Para.yaz(ayar().aidatMax()));
            return;
        }
        a.aidatMiktari = Para.kurus(miktar);
        plugin.veri().kaydet();
        plugin.log().yaz(a, p.getName(), "AIDAT_AYARLADI", Para.yaz(miktar));
        plugin.aileManager().aileyeGonder(a, "aidat-degisti", "&eAile aidatı {miktar} olarak belirlendi.", "miktar", Para.yaz(miktar));
    }

    // ------------------------------------------------------------------ PERİYODİK KONTROL
    /** Dakikada bir: ek süresi biten dönemleri işler, hatırlatma gönderir. */
    public void kontrol() {
        if (!ayar().aidatAktif()) return;
        long simdi = System.currentTimeMillis();
        boolean degisti = false;
        for (Aile a : new ArrayList<>(plugin.aileManager().aileler())) {
            // Savaşta kasa kilitliyken aidat sayacı donar (bitince dönemler kilit süresi kadar kaydırılır)
            if (plugin.kasaKilitliMi(a)) continue;
            int k = donem(a, simdi);
            List<AileUyesi> atilacaklar = new ArrayList<>();
            for (AileUyesi u : new ArrayList<>(a.uyeler.values())) {
                if (donemleriIsle(a, u, k, simdi, atilacaklar)) degisti = true;
                hatirlat(a, u, k, simdi);
            }
            for (AileUyesi u : atilacaklar) {
                if (a.uyeler.containsKey(u.uuid)) plugin.aileManager().uyeyiAt(a, u, m().metin("aidat-atan", "Aidat Sistemi"));
            }
        }
        if (degisti) plugin.veri().kaydet();
    }

    private boolean donemleriIsle(Aile a, AileUyesi u, int k, long simdi, List<AileUyesi> atilacaklar) {
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
                m().gonder(oyuncu, "aidat-borclu", "&cAidatınızı ödemediğiniz için BORÇLU oldunuz! Kasa çekme yetkiniz kapandı. &e/aile aidat ode");
            }
            if (u.ardisikOdenmeyen == ayar().patronBildirimDonem()) {
                AileUyesi patron = a.patron();
                Player pp = patron == null ? null : Bukkit.getPlayer(patron.uuid);
                if (pp != null && patron != u) {
                    m().gonder(pp, "aidat-patron-bildirim", "&c{oyuncu} üst üste {donem} dönemdir aidat ödemiyor!", "oyuncu", u.isim, "donem", u.ardisikOdenmeyen);
                }
            }
            if (ayar().otomatikAtma() && u.rol != Rol.PATRON && u.ardisikOdenmeyen >= ayar().otomatikAtmaDonem()) {
                atilacaklar.add(u);
                break;
            }
        }
        return degisti;
    }

    private void hatirlat(Aile a, AileUyesi u, int k, long simdi) {
        Player p = Bukkit.getPlayer(u.uuid);
        if (p == null || odendi(u, k) || muaf(a, u, k)) return;
        long kalan = donemSonu(a, k) - simdi;
        if (kalan <= Zaman.SAAT && u.hatirlatma1Donem != k) {
            u.hatirlatma1Donem = k;
            u.hatirlatma24Donem = k;
            m().gonder(p, "aidat-hatirlatma-1", "&c&lSON 1 SAAT! &eAile aidatınızı ({miktar}) ödemeyi unutmayın: &6/aile aidat ode", "miktar", Para.yaz(a.aidatMiktari));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f);
        } else if (kalan <= 24 * Zaman.SAAT && u.hatirlatma24Donem != k) {
            u.hatirlatma24Donem = k;
            m().gonder(p, "aidat-hatirlatma-24", "&eAile aidatınızı ({miktar}) ödemek için {kalan} kaldı: &6/aile aidat ode", "miktar", Para.yaz(a.aidatMiktari), "kalan", Zaman.sure(kalan));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
        }
    }

    /** Oyuncu girişinde: çevrimdışı korumanın ek süresini başlatır, borç/aidat uyarısı gösterir. */
    public void girisKontrolu(Player p) {
        if (!ayar().aidatAktif()) return;
        Aile a = plugin.aileManager().oyuncununAilesi(p.getUniqueId());
        if (a == null) return;
        AileUyesi u = a.uyeler.get(p.getUniqueId());
        long simdi = System.currentTimeMillis();

        if (u.bekleyenDonem >= 0 && u.ekSureBitis == 0) {
            u.ekSureBitis = simdi + ayar().aidatEkSureMs();
            plugin.veri().kaydet();
            m().gonder(p, "aidat-ek-sure-basladi", "&6Siz yokken aidat süresi doldu. Ödemeniz için {sure} ek süreniz başladı: &e/aile aidat ode",
                    "sure", Zaman.sure(ayar().aidatEkSureMs()));
            return;
        }
        if (u.borclu) {
            m().gonder(p, "aidat-giris-borclu", "&cAidat borcunuz var! Kasa çekme yetkiniz kapalı. &e/aile aidat ode");
            return;
        }
        Integer hedef = odenecekDonem(a, u, simdi);
        if (hedef != null) {
            m().gonder(p, "aidat-giris-bekliyor", "&eÖdenmemiş aile aidatınız var ({miktar}). Son ödeme: &6{tarih}",
                    "miktar", Para.yaz(a.aidatMiktari), "tarih", Zaman.tarih(sonOdemeAni(a, u, hedef, simdi)));
        }
    }

    /** Menüdeki istatistik: [ödendi, bekliyor+ek süre, borçlu, muaf] */
    public int[] istatistik(Aile a) {
        long simdi = System.currentTimeMillis();
        int[] say = new int[4];
        for (AileUyesi u : a.uyeler.values()) {
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
