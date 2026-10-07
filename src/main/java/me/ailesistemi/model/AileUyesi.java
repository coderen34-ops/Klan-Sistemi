package me.ailesistemi.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class AileUyesi {
    public final UUID uuid;
    public String isim;          // Son bilinen oyuncu adı (çevrimdışı işlemler için)
    public Rol rol;
    public long katilma;
    public int kidem;            // Yardımcılar için sıra (1 = patronun halefi). Diğer roller için 0.

    // --- Aidat ---
    public long muafBitis;                                   // Yeni üye muafiyetinin bittiği an
    public final Set<Integer> odenenDonemler = new HashSet<>(); // Aidatı ödenmiş dönem numaraları
    public int islenenDonem;                                 // Aidat kontrolü yapılmış son dönem
    public int ardisikOdenmeyen;
    public boolean borclu;
    public int bekleyenDonem = -1;                           // Çevrimdışı koruması bekleyen dönem
    public long ekSureBitis;                                 // Girişte başlayan kişisel ek sürenin bitişi (0 = başlamadı)
    public long sonOdeme;
    public int hatirlatma24Donem = -1;
    public int hatirlatma1Donem = -1;

    // --- Yardımcı günlük çekim limiti ---
    public long cekimGunu;                                   // epochDay
    public double gunlukCekilen;

    public AileUyesi(UUID uuid, String isim, Rol rol, long katilma) {
        this.uuid = uuid;
        this.isim = isim;
        this.rol = rol;
        this.katilma = katilma;
    }
}
