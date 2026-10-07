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
    public double toplamAidat;

    // Açık dünyada husumetli klan üyelerini ağır yaralayarak kazanılan prestij
    public int prestij;
    public final Map<UUID, Integer> prestijRakip = new java.util.HashMap<>(); // rakip klan -> bu klana karşı kazanılan puan

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
