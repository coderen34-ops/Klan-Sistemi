package me.ailesistemi.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Aile {
    public final UUID id;
    public String isim;
    public long kurulus;
    public final Map<UUID, AileUyesi> uyeler = new LinkedHashMap<>();

    public double kasa;
    public double aidatMiktari;
    public long aidatBaslangic;   // Aidat dönemlerinin başladığı an (dönem 0)
    public double toplamAidat;

    // Açık dünyada husumetli aile üyelerini ağır yaralayarak kazanılan prestij
    public int prestij;
    public final Map<UUID, Integer> prestijRakip = new java.util.HashMap<>(); // rakip aile -> bu aileye karşı kazanılan puan

    public Aile(UUID id, String isim, long kurulus) {
        this.id = id;
        this.isim = isim;
        this.kurulus = kurulus;
    }

    public AileUyesi patron() {
        for (AileUyesi u : uyeler.values()) if (u.rol == Rol.PATRON) return u;
        return null;
    }

    /** Yardımcılar kıdem sırasıyla (1. sıra patronun halefi). */
    public List<AileUyesi> yardimcilar() {
        List<AileUyesi> liste = new ArrayList<>();
        for (AileUyesi u : uyeler.values()) if (u.rol == Rol.YARDIMCI) liste.add(u);
        liste.sort(Comparator.comparingInt((AileUyesi u) -> u.kidem).thenComparingLong(u -> u.katilma));
        return liste;
    }

    /** Yardımcıların kıdemlerini 1..n olacak şekilde mevcut sırayla yeniden numaralar. */
    public void kidemleriDuzenle() {
        int sira = 1;
        for (AileUyesi u : yardimcilar()) u.kidem = sira++;
        for (AileUyesi u : uyeler.values()) if (u.rol != Rol.YARDIMCI) u.kidem = 0;
    }

    /** Patron, yardımcılar (kıdem sırasıyla), üyeler. */
    public List<AileUyesi> siraliUyeler() {
        List<AileUyesi> liste = new ArrayList<>();
        AileUyesi p = patron();
        if (p != null) liste.add(p);
        liste.addAll(yardimcilar());
        for (AileUyesi u : uyeler.values()) if (u.rol == Rol.UYE) liste.add(u);
        return liste;
    }
}
