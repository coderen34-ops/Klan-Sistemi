package me.klansistemi.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Klan {
    public final UUID id;
    public String isim;
    public long kurulus;
    public final Map<UUID, KlanUyesi> uyeler = new LinkedHashMap<>();

    public double kasa;
    public double aidatMiktari;
    public long aidatBaslangic;   // Aidat dönemlerinin başladığı an (dönem 0)
    public double aidatPeriyotGun;      // Liderin seçtiği dönem uzunluğu (gün). 0 = config'teki varsayılan
    public double bekleyenPeriyotGun;   // Bir sonraki dönem başında geçerli olacak yeni uzunluk (0 = yok)
    public long periyotDegisimAni;      // Bekleyen uzunluğun devreye gireceği an (mevcut dönemin sonu)
    public double toplamAidat;

    // Açık dünyada husumetli klan üyelerini ağır yaralayarak kazanılan prestij
    public int prestij;
    public final Map<UUID, Integer> prestijRakip = new java.util.HashMap<>(); // rakip klan -> bu klana karşı kazanılan puan

    // Klanın yönettiği büyü alanları (KAZMA, KILIC ...). Bir alanı aynı anda sadece bir klan yönetir.
    public final java.util.Set<String> uzmanliklar = new java.util.LinkedHashSet<>();
    // Atölye klan geneli günlük üretim sayacı
    public long atolyeGun;
    public int atolyeSayi;

    // Ticari büyü satışı (Büyü Ustası): büyü adı (örn. efficiency) -> fiyat, günlük satış sayacı ve geçmiş
    public final Map<String, Double> buyuFiyatlari = new java.util.LinkedHashMap<>();
    public long satisGun;
    public int satisSayi;
    public final java.util.List<SatisKaydi> satislar = new java.util.ArrayList<>(); // En yenisi başta

    public static class SatisKaydi {
        public final long zaman;
        public final UUID alici;
        public final String aliciAdi, buyu, esyaKodu;
        public final int seviye;
        public final double fiyat, vergi;

        public SatisKaydi(long zaman, UUID alici, String aliciAdi, String buyu, int seviye, String esyaKodu, double fiyat, double vergi) {
            this.zaman = zaman;
            this.alici = alici;
            this.aliciAdi = aliciAdi;
            this.buyu = buyu;
            this.seviye = seviye;
            this.esyaKodu = esyaKodu;
            this.fiyat = fiyat;
            this.vergi = vergi;
        }
    }

    public Klan(UUID id, String isim, long kurulus) {
        this.id = id;
        this.isim = isim;
        this.kurulus = kurulus;
    }

    public KlanUyesi lider() {
        for (KlanUyesi u : uyeler.values()) if (u.rol == Rol.LIDER) return u;
        return null;
    }

    /** Yardımcılar kıdem sırasıyla (1. sıra liderin halefi). */
    public List<KlanUyesi> yardimcilar() {
        List<KlanUyesi> liste = new ArrayList<>();
        for (KlanUyesi u : uyeler.values()) if (u.rol == Rol.YARDIMCI) liste.add(u);
        liste.sort(Comparator.comparingInt((KlanUyesi u) -> u.kidem).thenComparingLong(u -> u.katilma));
        return liste;
    }

    /** Yardımcıların kıdemlerini 1..n olacak şekilde mevcut sırayla yeniden numaralar. */
    public void kidemleriDuzenle() {
        int sira = 1;
        for (KlanUyesi u : yardimcilar()) u.kidem = sira++;
        for (KlanUyesi u : uyeler.values()) if (u.rol != Rol.YARDIMCI) u.kidem = 0;
    }

    /** Lider, yardımcılar (kıdem sırasıyla), üyeler. */
    public List<KlanUyesi> siraliUyeler() {
        List<KlanUyesi> liste = new ArrayList<>();
        KlanUyesi p = lider();
        if (p != null) liste.add(p);
        liste.addAll(yardimcilar());
        for (KlanUyesi u : uyeler.values()) if (u.rol == Rol.UYE) liste.add(u);
        return liste;
    }
}
